package com.agent.task;

import com.agent.llm.LLMResponse;
import com.agent.llm.ToolCall;
import com.agent.llm.Message;
import com.agent.roles.ControllerRole;
import com.agent.task.TaskRecord.DecisionState;
import com.agent.task.TaskRecord.OperationState;
import com.agent.tool.RunCommandTool;
import com.agent.tool.Tool;
import com.agent.tool.ToolRequest;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.Map;
import java.util.TreeSet;

/** 在请求和工具副作用前保存预留，记录失败来源与实际文件差异。 */
public final class TaskProgress implements ControllerRole.DecisionObserver {
    private static final ObjectMapper JSON = new ObjectMapper();
    public static final class Pause extends RuntimeException {
        public Pause(String reason) { super(reason); }
    }
    public static final class Recheck extends RuntimeException { }

    private final Path root;
    private final TaskStore store;
    private final TaskRecord task;
    private final boolean interactive;
    private long lastTick = System.nanoTime();
    private TaskRecord.Decision decision;
    private int operationIndex;
    private Map<String, String> before;
    private Tool.Risk currentRisk;

    public TaskProgress(Path root, TaskStore store, TaskRecord task, boolean interactive) {
        this.root = root;
        this.store = store;
        this.task = task;
        this.interactive = interactive;
    }

    @Override public void taskStarted(String feedback) {
        task.checkpoint.messages.add(Message.user(feedback));
        task.checkpoint.feedback = null;
        save();
    }

    @Override public ControllerRole.Continuation continuation() {
        LLMResponse pending = null;
        if (!task.progress.decisions.isEmpty()) {
            decision = task.progress.decisions.get(task.progress.decisions.size() - 1);
            if (decision.status == DecisionState.RESPONDED && !decision.roundFinished && !decision.operations.isEmpty()) {
                operationIndex = 0;
                while (operationIndex < decision.operations.size()) {
                    var op = decision.operations.get(operationIndex);
                    if (!op.finished()) { break; }
                    operationIndex++;
                }
                pending = new LLMResponse(decision.content, decision.operations.stream()
                        .map(op -> new ToolCall(op.callId, new ToolCall.Function(op.tool, op.arguments))).toList(), -1, -1);
            }
        }
        return new ControllerRole.Continuation(java.util.List.copyOf(task.checkpoint.messages), pending, operationIndex);
    }

    @Override public void beforeRequest() {
        if (task.progress.requests >= task.progress.maxRequests) { throw new Pause("累计模型请求预算已耗尽"); }
        decision = new TaskRecord.Decision();
        decision.startedAt = System.currentTimeMillis();
        decision.acceptanceVersion = task.acceptanceVersion;
        decision.failureSource = "";
        if (!task.checks.isEmpty()) {
            var reference = task.checks.get(task.checks.size() - 1);
            try {
                if (store.readEvidence(task, reference).verdict().equals("FAILED")) {
                    decision.failureSource = reference.id();
                }
            } catch (IOException failure) { throw new UncheckedIOException(failure); }
        }
        decision.number = ++task.progress.requests;
        task.progress.unknownRequests++;
        task.progress.decisions.add(decision);
        operationIndex = 0;
        save();
    }

    @Override public void responded(LLMResponse response, long millis) {
        decision.status = DecisionState.RESPONDED;
        decision.durationMillis = millis;
        decision.content = response.content();
        task.checkpoint.messages.add(Message.assistant(response.content(), response.toolCalls()));
        if (!response.hasToolCalls()) { task.checkpoint.beginAcceptance(true); }
        if (response.promptTokens() >= 0) {
            decision.promptTokens = response.promptTokens();
            task.progress.promptTokens += response.promptTokens();
        }
        if (response.completionTokens() >= 0) {
            decision.completionTokens = response.completionTokens();
            task.progress.completionTokens += response.completionTokens();
        }
        if (decision.promptTokens != null && decision.completionTokens != null) { task.progress.unknownRequests--; }
        if (response.hasToolCalls()) {
            for (ToolCall call : response.toolCalls()) {
                TaskRecord.Operation operation = new TaskRecord.Operation();
                operation.operationId = java.util.UUID.randomUUID().toString();
                operation.callId = call.id();
                operation.tool = call.name();
                operation.arguments = call.arguments();
                decision.operations.add(operation);
            }
        }
        save();
    }

    @Override public void modelFailed(IOException failure, long millis) {
        decision.status = DecisionState.MODEL_FAILED;
        decision.failure = failure.getMessage();
        decision.durationMillis = millis;
        save();
    }

    @Override public void beforeTool(ToolCall call, Tool.Risk risk) {
        currentRisk = risk;
        try { before = WorkspaceSnapshot.capture(root); }
        catch (IOException failure) { throw new UncheckedIOException(failure); }
        var operation = decision.operations.get(operationIndex);
        operation.attempts++;
        task.progress.tools++;
        operation.status = OperationState.RUNNING;
        operation.risk = risk.name();
        save();
    }

    @Override public void approvalRequired() {
        if (!interactive) {
            toolBlocked();
            throw new Pause("非交互任务需要人工批准，未执行该工具；可信任务可使用 --yes");
        }
    }

    @Override public void toolBlocked() {
        decision.operations.get(operationIndex).status = OperationState.BLOCKED;
        save();
    }

    @Override public void toolFinished(ToolCall call, boolean success, String observation) {
        TaskRecord.Operation operation = decision.operations.get(operationIndex++);
        operation.status = success ? OperationState.SUCCEEDED : OperationState.FAILED;
        operation.observation = observation;
        task.checkpoint.messages.add(Message.tool(call.id(), observation));
        String request = toolRequestKey(call.name(), call.arguments());
        boolean optionalSkillUnavailable = "load_skill".equals(operation.tool) && task.skills != null
                && task.skills.firstUnavailableSharedLoad(call.id());
        if (success || optionalSkillUnavailable) { task.progress.toolErrors.remove(request); }
        else {
            String error = CoreError.key(call.name() + " " + observation);
            task.progress.toolErrors.put(request, error);
            task.progress.errorDescriptions.put(error, CoreError.normalize(observation));
        }
        try {
            Map<String, String> after = WorkspaceSnapshot.capture(root);
            var paths = new TreeSet<>(before.keySet());
            paths.addAll(after.keySet());
            var expected = new java.util.LinkedHashMap<>(task.files);
            for (String path : paths) {
                if (!java.util.Objects.equals(before.get(path), after.get(path))) {
                    operation.changes.put(path, new TaskRecord.Change(before.get(path), after.get(path)));
                    // 只认可成功写操作在已知版本上产生的变化，读取与失败不能承认外部修改。
                    if (success && currentRisk != Tool.Risk.READ
                            && java.util.Objects.equals(task.files.get(path), before.get(path))) {
                        if (after.containsKey(path)) { expected.put(path, after.get(path)); }
                        else { expected.remove(path); }
                    }
                }
            }
            task.files = expected;
        } catch (IOException failure) { throw new UncheckedIOException(failure); }
        save();
    }

    @Override public void roundFinished() {
        if (!firstPreparationProgress()) { advanceRound(); }
        decision.roundFinished = true;
        if (repeated()) { task.checkpoint.beginAcceptance(false); }
        save();
        if (repeated()) { throw new Recheck(); }
    }

    /** 仅豁免无副作用的首次知识审阅或源码诊断，请求仍由 beforeRequest 计入预算。 */
    private boolean firstPreparationProgress() {
        if (decision.operations.isEmpty() || decision.operations.stream().anyMatch(op ->
                op.status != OperationState.SUCCEEDED || !Tool.Risk.READ.name().equals(op.risk) || !op.changes.isEmpty())) {
            return false;
        }
        boolean firstKnowledge = decision.operations.stream().anyMatch(op -> (op.tool.equals("load_skill") || op.tool.equals("declare_skill"))
                && task.progress.decisions.stream().filter(previous -> previous != decision)
                .flatMap(previous -> previous.operations.stream())
                .noneMatch(previous -> previous.status == OperationState.SUCCEEDED && previous.tool.equals(op.tool)));
        return firstKnowledge || firstSourceDiagnosis();
    }

    private boolean firstSourceDiagnosis() {
        if (task.progress.acceptanceErrors.isEmpty() || !task.progress.toolErrors.isEmpty()
                || decision.operations.stream().noneMatch(this::readBusinessSource)) {
            return false;
        }
        // 从持久化的历史识别首次读取。恢复任务后，重复读取或读取另一份源码也不能再次豁免。
        return task.progress.decisions.stream().filter(previous -> previous != decision)
                .flatMap(previous -> previous.operations.stream())
                .noneMatch(op -> !Tool.Risk.READ.name().equals(op.risk) || readBusinessSource(op));
    }

    private boolean readBusinessSource(TaskRecord.Operation operation) {
        if (operation.status != OperationState.SUCCEEDED || !"read_file".equals(operation.tool)) { return false; }
        try {
            Path file = new ToolRequest(root, JSON.readTree(operation.arguments)).resolvePath("path");
            String relative = "/" + root.relativize(file).toString().replace('\\', '/');
            return relative.contains("/src/main/java/") && relative.endsWith(".java");
        } catch (IOException | IllegalArgumentException | SecurityException invalid) {
            return false;
        }
    }

    /** 第三个决策轮次须用新报告确认旧错误，不能把尚未验收的修改直接判为失败。 */
    public void acceptanceChecked(TaskAcceptance.Evidence evidence, boolean advance) {
        task.progress.acceptanceErrors.clear();
        if (evidence.verdict().equals("PASSED")) {
            if (advance && task.skillTrialId != null && "final".equals(evidence.kind())) {
                retireUnexecutedCommandErrors();
            }
        } else {
            var failures = evidence.failures() == null || evidence.failures().isEmpty()
                    ? java.util.List.of(evidence.reason()) : evidence.failures();
            for (String failure : failures) {
                String key = CoreError.key(failure);
                task.progress.acceptanceErrors.add(key);
                task.progress.errorDescriptions.put(key, CoreError.normalize(failure));
                // 强制复核已经为当前轮计数；新错误从本轮的一次开始。
                task.progress.errors.putIfAbsent(key, !advance && decision != null ? 1 : 0);
            }
        }
        retainActiveErrors();
        if (advance) { advanceRound(); }
        save();
        if (repeated()) { throw new Pause("同一核心错误持续三个决策轮次；当前复核仍未解决"); }
    }

    /** 任务已独立验证后，未启动进程的试用拒绝不再计为活动错误，操作记录仍保留。 */
    private void retireUnexecutedCommandErrors() {
        for (var decision : task.progress.decisions) {
            for (var operation : decision.operations) {
                if (operation.status == OperationState.FAILED && "run_command".equals(operation.tool)
                        && operation.processes.isEmpty() && operation.changes.isEmpty()
                        && RunCommandTool.isFrozenCommandRejection(task.command, operation.observation)) {
                    task.progress.toolErrors.remove(toolRequestKey(operation.tool, operation.arguments),
                            CoreError.key(operation.tool + " " + operation.observation));
                }
            }
        }
    }

    private static String toolRequestKey(String tool, String arguments) {
        return WorkspaceSnapshot.hash((tool + "\n" + arguments).getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private void retainActiveErrors() {
        var active = new java.util.HashSet<>(task.progress.acceptanceErrors);
        active.addAll(task.progress.toolErrors.values());
        task.progress.errors.keySet().retainAll(active);
        active.forEach(key -> task.progress.errors.putIfAbsent(key, 0));
    }

    private void advanceRound() {
        retainActiveErrors();
        task.progress.errors.replaceAll((key, count) -> count + 1);
    }

    private boolean repeated() { return task.progress.errors.values().stream().anyMatch(count -> count >= 3); }

    public void save() {
        long now = System.nanoTime();
        task.progress.elapsedMillis += (now - lastTick) / 1_000_000;
        lastTick = now;
        try { store.save(task); }
        catch (IOException failure) { throw new UncheckedIOException(failure); }
    }
}
