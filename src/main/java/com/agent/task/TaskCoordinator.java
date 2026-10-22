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
import java.util.function.BiFunction;

/** 一次编码后独立验收；恢复和自动修复由后续票增量实现。 */
public final class TaskCoordinator {
    private final Path root;
    private final CommandRunner runner;
    private final BiFunction<SessionStore, TaskProtection, ControllerRole> controllerFactory;
    private final PrintStream out;

    public TaskCoordinator(Path root, CommandRunner runner,
                           BiFunction<SessionStore, TaskProtection, ControllerRole> controllerFactory, PrintStream out) {
        this.root = root;
        this.runner = runner;
        this.controllerFactory = controllerFactory;
        this.out = out;
    }

    public TaskRecord start(String goal, String command) throws IOException, InterruptedException {
        Path project = root.toRealPath();
        if (goal == null || goal.isBlank() || command.isBlank() || !Files.isRegularFile(project.resolve("pom.xml"))) {
            throw new IOException("管理任务需要非空目标、验收命令和 Maven 项目的 pom.xml");
        }
        var arguments = new ObjectMapper().createObjectNode().put("command", command);
        GuardRole.Decision decision = new GuardRole(project).review(new RunCommandTool(runner), arguments);
        if (!decision.allowed()) { throw new IOException("验收命令被守卫拒绝: " + decision.reason()); }
        TaskStore store = new TaskStore(project);
        try (TaskStore.Writer writer = store.lock()) {
            TaskRecord task = store.create(goal, command);
            out.println("任务: " + task.id);
            try {
                Path sessions = SessionPaths.defaultDir(project);
                Files.createDirectories(sessions);
                WorkspaceSnapshot.requireInside(project, sessions);
                SessionStore session = SessionStore.startStrict(sessions, task.sessionId);
                task.sessionId = session.sessionId();
                store.save(task);
                TaskAcceptance acceptance = new TaskAcceptance(project, store, runner);
                task.state = TaskRecord.State.Verifying;
                store.save(task);
                TaskAcceptance.Evidence baseline = acceptance.check(task, "baseline", Set.of());
                if (!baseline.codingAllowed()) {
                    return pause(store, task, "初始验收证据不可用: " + baseline.reason());
                }
                task.state = TaskRecord.State.Running;
                store.save(task);
                ControllerRole.RunResult result = controllerFactory.apply(session,
                        new TaskProtection(project, task.acceptanceFiles)).runTaskResult(goal
                        + "\n初始验收: " + baseline.verdict() + " " + baseline.reason());
                out.println("Agent > " + result.answer());
                if (result.reason() != ControllerRole.StopReason.COMPLETED || result.answer().isBlank()) {
                    return pause(store, task, result.answer().isBlank() ? "模型没有给出完成声明" : result.answer());
                }
                task.state = TaskRecord.State.Verifying;
                store.save(task);
                TaskAcceptance.Evidence verified = acceptance.check(task, "final", baseline.identities());
                if (!verified.verdict().equals("PASSED")) { return pause(store, task, verified.reason()); }
                task.state = TaskRecord.State.Verified;
                task.reason = "";
                store.save(task);
                return task;
            } catch (TaskProtection.Violation violation) {
                return pause(store, task, violation.getMessage());
            } catch (IOException | UncheckedIOException | InterruptedException failed) {
                try { pause(store, task, "执行或持久化失败: " + failed.getMessage()); }
                catch (IOException saveFailed) { failed.addSuppressed(saveFailed); }
                throw failed;
            }
        }
    }

    private TaskRecord pause(TaskStore store, TaskRecord task, String reason) throws IOException {
        task.state = TaskRecord.State.Paused;
        task.reason = reason + "；下一步：检查原因，可新建任务；合法验收变更须明确重新约定，本版不会自动重试";
        store.save(task);
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
                TaskAcceptance.Evidence baseline = new TaskAcceptance(project, store, runner)
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
