package com.agent.task;

import com.agent.llm.LLMResponse;
import com.agent.llm.ToolCall;
import com.agent.roles.ControllerRole;
import com.agent.task.TaskRecord.DecisionState;
import com.agent.task.TaskRecord.OperationState;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.Map;
import java.util.TreeSet;

/** 在请求和工具副作用前保存预留，记录失败来源与实际文件差异。 */
public final class TaskProgress implements ControllerRole.DecisionObserver {
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

    public TaskProgress(Path root, TaskStore store, TaskRecord task, boolean interactive) {
        this.root = root;
        this.store = store;
        this.task = task;
        this.interactive = interactive;
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

    @Override public void beforeTool(ToolCall call) {
        try { before = WorkspaceSnapshot.capture(root); }
        catch (IOException failure) { throw new UncheckedIOException(failure); }
        decision.operations.get(operationIndex).status = OperationState.RUNNING;
        task.progress.tools++;
        save();
    }

    @Override public void approvalRequired() {
        if (!interactive) {
            decision.operations.get(operationIndex).status = OperationState.BLOCKED;
            save();
            throw new Pause("非交互任务需要人工批准，未执行该工具；可信任务可使用 --yes");
        }
    }

    @Override public void toolFinished(ToolCall call, boolean success, String observation) {
        TaskRecord.Operation operation = decision.operations.get(operationIndex++);
        operation.status = success ? OperationState.SUCCEEDED : OperationState.FAILED;
        operation.observation = observation;
        String request = WorkspaceSnapshot.hash((call.name() + "\n" + call.arguments())
                .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        if (success) { task.progress.toolErrors.remove(request); }
        else {
            String error = CoreError.key(call.name() + " " + observation);
            task.progress.toolErrors.put(request, error);
            task.progress.errorDescriptions.put(error, CoreError.normalize(observation));
        }
        try {
            Map<String, String> after = WorkspaceSnapshot.capture(root);
            var paths = new TreeSet<>(before.keySet());
            paths.addAll(after.keySet());
            for (String path : paths) {
                if (!java.util.Objects.equals(before.get(path), after.get(path))) {
                    operation.changes.put(path, new TaskRecord.Change(before.get(path), after.get(path)));
                }
            }
            task.files = after;
        } catch (IOException failure) { throw new UncheckedIOException(failure); }
        save();
    }

    @Override public void roundFinished() {
        advanceRound();
        save();
        if (repeated()) { throw new Recheck(); }
    }

    /** 第三个决策轮次须用新报告确认旧错误，不能把尚未验收的修改直接判为失败。 */
    public void acceptanceChecked(TaskAcceptance.Evidence evidence, boolean advance) {
        task.progress.acceptanceErrors.clear();
        if (!evidence.verdict().equals("PASSED")) {
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
