package com.agent.task;

import com.agent.command.CommandRunner;
import com.agent.roles.ControllerRole;
import com.agent.roles.GuardRole;
import com.agent.session.SessionPaths;
import com.agent.session.SessionStore;
import com.agent.tool.RunCommandTool;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.io.BufferedReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

/** 真实失败驱动修复，显式恢复可靠边界，验收判定独立于模型声明。 */
public final class TaskCoordinator {
    private final Path root;
    private final CommandRunner runner;
    @FunctionalInterface
    public interface ControllerFactory {
        ControllerRole create(SessionStore session, TaskProtection protection, CommandRunner runner);
    }
    private final ControllerFactory controllerFactory;
    private final PrintStream out;

    public TaskCoordinator(Path root, CommandRunner runner,
                           ControllerFactory controllerFactory, PrintStream out) {
        this.root = root;
        this.runner = runner;
        this.controllerFactory = controllerFactory;
        this.out = out;
    }

    public TaskRecord start(String goal, String command) throws IOException, InterruptedException {
        return start(goal, command, ControllerRole.DEFAULT_MAX_STEPS);
    }

    public TaskRecord start(String goal, String command, int maxRequests) throws IOException, InterruptedException {
        return start(goal, command, maxRequests, false);
    }

    public TaskRecord start(String goal, String command, int maxRequests, boolean interactive)
            throws IOException, InterruptedException {
        Path project = root.toRealPath();
        if (maxRequests <= 0) { throw new IOException("总请求预算必须为正数"); }
        if (goal == null || goal.isBlank() || command == null || command.isBlank()
                || !Files.isRegularFile(project.resolve("pom.xml"))) {
            throw new IOException("管理任务需要非空目标、验收命令和 Maven 项目的 pom.xml");
        }
        var arguments = new ObjectMapper().createObjectNode().put("command", command);
        GuardRole.Decision decision = new GuardRole(project).review(new RunCommandTool(runner), arguments);
        if (!decision.allowed()) { throw new IOException("验收命令被守卫拒绝: " + decision.reason()); }
        TaskStore store = new TaskStore(project);
        try (TaskStore.Writer writer = store.lock()) {
            TaskRecord task = store.create(goal, command);
            if (maxRequests > task.progress.maxRequests) {
                task.progress.budgetChanges.add(new TaskRecord.BudgetChange(task.progress.maxRequests,
                        maxRequests, System.currentTimeMillis()));
            }
            task.progress.maxRequests = maxRequests;
            return execute(store, task, interactive, false);
        }
    }

    /** 恢复必须持有同一项目写锁；旧版本或未知副作用只暂停，不推断结果。 */
    public TaskRecord resume(String id, boolean interactive) throws IOException, InterruptedException {
        TaskStore store = new TaskStore(root);
        try (TaskStore.Writer writer = store.lock()) {
            TaskRecord task = store.read(id);
            if (task.state == TaskRecord.State.Abandoned) { throw new IOException("已放弃任务不能恢复"); }
            return execute(store, task, interactive, true);
        }
    }

    private TaskRecord execute(TaskStore store, TaskRecord task, boolean interactive, boolean resumed)
            throws IOException, InterruptedException {
        Path project = root.toRealPath();
        TaskProgress progress = new TaskProgress(project, store, task, interactive);
        out.println("任务: " + task.id);
        try {
            if (resumed) { TaskCheckpoint.requireResumable(task); }
            TaskProtection protection = new TaskProtection(project, task.acceptanceFiles, () -> task.files);
            protection.check();
            if (task.state == TaskRecord.State.Verified) { return task; }
            Path sessions = SessionPaths.defaultDir(project);
            Files.createDirectories(sessions);
            WorkspaceSnapshot.requireInside(project, sessions);
            SessionStore session = resumed ? SessionStore.resumeStrict(sessions, task.sessionId)
                    : SessionStore.startStrict(sessions, task.sessionId);
            task.sessionId = session.sessionId();
            store.save(task);
            CommandRunner observedRunner = runner.withProcessObserver(new TaskProcesses(store, task));
            ControllerRole controller = controllerFactory.create(session, protection, observedRunner);
            TaskAcceptance acceptance = new TaskAcceptance(project, store, observedRunner);
            while (true) {
                protection.check();
                switch (task.checkpoint.phase) {
                    case BASELINE -> {
                        task.state = TaskRecord.State.Verifying;
                        store.save(task);
                        TaskAcceptance.Evidence baseline = currentAcceptance(store, task, acceptance, "baseline", Set.of());
                        if (!baseline.codingAllowed()) {
                            return pause(store, task, "初始验收证据不可用: " + baseline.reason(), progress);
                        }
                        if (!task.checkpoint.acceptanceProcessed) {
                            task.checkpoint.acceptanceProcessed = true;
                            progress.acceptanceChecked(baseline, false);
                        }
                        task.checkpoint.phase = TaskRecord.Phase.CODING;
                        task.checkpoint.feedback = (task.checkpoint.messages.isEmpty() ? task.goal : "验收已重新约定")
                                + "\n初始" + feedback(baseline);
                        progress.save();
                    }
                    case CODING -> {
                        task.state = TaskRecord.State.Running;
                        store.save(task);
                        ControllerRole.RunResult result;
                        try { result = controller.runTaskResult(task.checkpoint.feedback, progress); }
                        catch (TaskProgress.Recheck repeated) { result = null; }
                        if (result != null) {
                            out.println("Agent > " + result.answer());
                            if (result.reason() != ControllerRole.StopReason.COMPLETED || result.answer().isBlank()) {
                                return pause(store, task, result.answer().isBlank() ? "模型没有给出完成声明" : result.answer(), progress);
                            }
                        }
                    }
                    case ACCEPTANCE -> {
                        task.state = TaskRecord.State.Verifying;
                        store.save(task);
                        var baseline = store.readEvidence(task, task.checks.get(0));
                        var verified = currentAcceptance(store, task, acceptance, "final", baseline.identities());
                        if (!verified.codingAllowed()) { return pause(store, task, verified.reason(), progress); }
                        if (!task.checkpoint.acceptanceProcessed) {
                            task.checkpoint.acceptanceProcessed = true;
                            progress.acceptanceChecked(verified, task.checkpoint.completionClaim);
                        }
                        if (task.checkpoint.completionClaim && verified.verdict().equals("PASSED")) {
                            task.state = TaskRecord.State.Verified;
                            task.reason = "";
                            task.checkpoint.phase = TaskRecord.Phase.COMPLETE;
                            progress.save();
                            return task;
                        }
                        task.checkpoint.feedback = feedback(verified) + "\n请根据真实验收结果继续处理目标，完成后申请重新验收。";
                        task.checkpoint.phase = TaskRecord.Phase.CODING;
                        task.state = TaskRecord.State.Running;
                        progress.save();
                    }
                    case COMPLETE -> { throw new IOException("任务完成阶段与状态不匹配"); }
                }
            }
        } catch (TaskProgress.Pause stopped) {
            return pause(store, task, stopped.getMessage(), progress);
        } catch (TaskProtection.Violation violation) {
            return pause(store, task, violation.getMessage(), progress);
        } catch (IOException | UncheckedIOException | InterruptedException failed) {
            try { pause(store, task, "执行或持久化失败: " + failed.getMessage(), progress); }
            catch (IOException saveFailed) { failed.addSuppressed(saveFailed); }
            throw failed;
        }
    }

    private TaskAcceptance.Evidence currentAcceptance(TaskStore store, TaskRecord task, TaskAcceptance acceptance,
                                                     String kind, Set<String> identities)
            throws IOException, InterruptedException {
        if (task.checkpoint.acceptancePending) { throw new TaskProgress.Pause("验收命令结果不明，需要人工处置"); }
        if (task.checkpoint.acceptanceResult != null) {
            var reference = task.checks.stream().filter(ref -> ref.id().equals(task.checkpoint.acceptanceResult))
                    .findFirst().orElseThrow(() -> new IOException("检查点缺少验收结果关联"));
            var evidence = store.readEvidence(task, reference);
            if (!kind.equals(evidence.kind())) { throw new IOException("检查点验收阶段不匹配"); }
            return evidence;
        }
        var evidence = acceptance.check(task, kind, identities);
        out.println("[任务] " + kind + " 验收结果已记录: " + evidence.verdict());
        return evidence;
    }

    /** 提高额度与恢复执行分开，非交互调用不会等待输入或重置历史消耗。 */
    public TaskRecord raiseBudget(String id, int maximum) throws IOException {
        TaskStore store = new TaskStore(root);
        try (TaskStore.Writer writer = store.lock()) {
            TaskRecord task = store.read(id);
            if (task.state != TaskRecord.State.Paused || task.schemaVersion < 3) {
                throw new IOException("只能提高已有累计统计的暂停任务预算");
            }
            if (maximum <= task.progress.maxRequests) { throw new IOException("新总预算必须大于当前总预算"); }
            task.progress.budgetChanges.add(new TaskRecord.BudgetChange(task.progress.maxRequests,
                    maximum, System.currentTimeMillis()));
            task.progress.maxRequests = maximum;
            task.reason = "总预算已明确提高，累计消耗未重置；任务仍暂停，未执行恢复";
            store.save(task);
            return task;
        }
    }

    private String feedback(TaskAcceptance.Evidence evidence) {
        return "验收: " + evidence.verdict() + " " + evidence.reason() + "\n"
                + evidence.details() + "\n命令输出: "
                + (evidence.process() == null ? "未执行" : evidence.process().output());
    }

    private TaskRecord pause(TaskStore store, TaskRecord task, String reason) throws IOException {
        return pause(store, task, reason, null);
    }

    private TaskRecord pause(TaskStore store, TaskRecord task, String reason, TaskProgress progress) throws IOException {
        task.state = TaskRecord.State.Paused;
        task.reason = reason + "；下一步：检查证据与预算后显式 --resume-task；未知副作用需人工处置，外部变化需重新规划";
        if (progress == null) { store.save(task); }
        else {
            try { progress.save(); }
            catch (UncheckedIOException failure) { throw failure.getCause(); }
        }
        return task;
    }

    /** 明确确认的是这次提案，不能用普通工具自动放行代替。 */
    public TaskRecord reagree(String id, String command, BufferedReader input, boolean interactive)
            throws IOException, InterruptedException {
        Path project = root.toRealPath();
        if (command == null || command.isBlank() || command.equals(com.agent.session.Redactor.PLACEHOLDER)) {
            throw new IOException("重新约定需要明确提供可执行的新验收命令");
        }
        var arguments = new ObjectMapper().createObjectNode().put("command", command);
        GuardRole.Decision decision = new GuardRole(project).review(new RunCommandTool(runner), arguments);
        if (!decision.allowed()) { throw new IOException("新验收命令被守卫拒绝: " + decision.reason()); }
        TaskStore store = new TaskStore(project);
        try (TaskStore.Writer writer = store.lock()) {
            TaskRecord task = store.read(id);
            if (task.state != TaskRecord.State.Paused && task.state != TaskRecord.State.Verified) {
                throw new IOException("只有暂停或验证通过的任务可重新约定验收");
            }
            if (TaskCheckpoint.hasUncertainOperation(task)) {
                return pause(store, task, "存在结果不明的操作，不能通过重新约定执行新验收；请先显式处置或放弃任务");
            }
            var proposal = WorkspaceSnapshot.capture(project);
            out.println("重新约定任务: " + id + "\n原命令: " + task.command + "\n新命令: " + command
                    + "\n受保护输入变化: " + new TaskProtection(project, task.acceptanceFiles).changes(proposal)
                    + "\n批准将使旧验证立即失效；请输入 approve " + id);
            out.flush();
            if (!interactive) { out.println("非交互运行不能批准重新约定；--yes 不适用。"); return null; }
            if (!("approve " + id).equals(input.readLine())) { out.println("未明确批准，约定保持不变。"); return null; }
            if (!proposal.equals(WorkspaceSnapshot.capture(project))) {
                out.println("确认期间项目输入版本变化，请重新审阅提案。"); return null;
            }
            store.replaceAgreement(task, command, proposal);
            try {
                TaskAcceptance.Evidence baseline = new TaskAcceptance(project, store,
                        runner.withProcessObserver(new TaskProcesses(store, task)))
                        .check(task, "baseline", Set.of());
                task.state = TaskRecord.State.Paused;
                task.reason = "新验收约定初始证据已保存: " + baseline.verdict()
                        + " " + baseline.reason() + "；任务暂停，尚无新约定的最终验收";
                store.save(task);
                return task;
            } catch (IOException | UncheckedIOException | InterruptedException failed) {
                try { pause(store, task, "新约定初始验收失败: " + failed.getMessage()); }
                catch (IOException saveFailed) { failed.addSuppressed(saveFailed); }
                throw failed;
            }
        }
    }
}
