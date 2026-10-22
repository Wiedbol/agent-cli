package com.agent;

import com.agent.command.CommandRunner;
import com.agent.command.ProcessCommandRunner;
import com.agent.llm.LLMClient;
import com.agent.llm.Message;
import com.agent.roles.ControllerRole;
import com.agent.roles.ExecutorRole;
import com.agent.roles.GuardRole;
import com.agent.roles.LlmRole;
import com.agent.roles.RegistrarRole;
import com.agent.roles.UserRole;
import com.agent.session.SessionRecorder;
import com.agent.session.SessionStore;
import com.agent.task.TaskRecord;
import com.agent.task.TaskStore;
import com.agent.task.TaskAcceptance;
import com.agent.task.TaskCoordinator;
import com.agent.task.TaskProtection;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * 把六个角色装配成一条可运行的链路（依赖注入的唯一地点）。
 *
 * <pre>
 * 用户 ──任务──► 控制员 ──请求──► LLM ──tool_calls──► 控制员
 *                 │                                      │
 *                 ├──► 守卫（放行 / 需确认 / 拒绝）        │
 *                 ├──► 注册员（按名字找到工具）            │
 *                 └──► 执行员（真正执行）──Observation──► LLM
 * </pre>
 *
 * <p>这个类不做任何业务判断，只负责「谁依赖谁」：把 {@link LLMClient}、工具集、沙箱根目录、
 * 输入输出流、会话存储按角色的构造器接起来，然后提供两种运行方式（交互式 REPL 与单任务）。
 *
 * <p>会话存储（{@link SessionStore}）同时担任两个角色：它既是 {@link LlmRole} 与
 * {@link ControllerRole} 的记录器，也是启动时恢复历史的来源。传 {@code null} 表示不持久化，
 * 此时行为与加入本功能之前完全一致。
 */
public class CodingAgentCli {

    static final String EXIT_COMMAND = "exit";
    static final String QUIT_COMMAND = "quit";
    static final String CLEAR_COMMAND = "clear";

    private final Path root;
    private final LlmRole llm;
    private final UserRole user;
    private final RegistrarRole registrar;
    private final ControllerRole controller;
    private final SessionStore session;
    private final PrintStream out;
    private final PrintStream err;
    private final LLMClient client;
    private final CommandRunner commandRunner;
    private final BufferedReader in;
    private final int maxSteps;

    /** 不持久化会话的构造（保留给不需要记录的场景与测试）。 */
    public CodingAgentCli(Path root,
                          LLMClient client,
                          boolean autoApprove,
                          int maxSteps,
                          BufferedReader in,
                          PrintStream out,
                          PrintStream err) {
        this(root, client, autoApprove, maxSteps, in, out, err, null);
    }

    /**
     * @param session 会话存储；{@code null} 表示本次运行不记录也不恢复
     */
    public CodingAgentCli(Path root,
                          LLMClient client,
                          boolean autoApprove,
                          int maxSteps,
                          BufferedReader in,
                          PrintStream out,
                          PrintStream err,
                          SessionStore session) {
        this(root, client, autoApprove, maxSteps, in, out, err, session, new ProcessCommandRunner());
    }

    /** 注入外部命令执行入口，使离线测试不启动真实进程。 */
    public CodingAgentCli(Path root,
                          LLMClient client,
                          boolean autoApprove,
                          int maxSteps,
                          BufferedReader in,
                          PrintStream out,
                          PrintStream err,
                          SessionStore session,
                          CommandRunner commandRunner) {
        this(root, client, autoApprove, maxSteps, in, out, err, session, commandRunner, null);
    }

    private CodingAgentCli(Path root, LLMClient client, boolean autoApprove, int maxSteps,
                           BufferedReader in, PrintStream out, PrintStream err, SessionStore session,
                           CommandRunner commandRunner, TaskProtection protection) {
        this.root = root.toAbsolutePath().normalize();
        this.out = out;
        this.err = err;
        this.session = session;
        this.client = client;
        this.commandRunner = commandRunner;
        this.in = in;
        this.maxSteps = maxSteps;
        this.user = new UserRole(in, out, autoApprove);
        this.llm = client == null ? null : new LlmRole(client, restoredHistory(session), recorder(session));
        if (client == null) {
            this.registrar = null;
            this.controller = null;
            return;
        }
        this.registrar = protection == null ? RegistrarRole.standard(this.root, commandRunner)
                : RegistrarRole.standard(this.root, commandRunner, protection::checkFileVersion);
        GuardRole guard = protection == null ? new GuardRole(this.root)
                : new GuardRole(this.root, protection::isWriteProtected);
        ExecutorRole executor = new ExecutorRole(this.root, ExecutorRole.DEFAULT_MAX_RESULT_CHARS, out);
        this.controller = new ControllerRole(llm, registrar, guard, executor, user, maxSteps, out,
                recorder(session), protection == null ? () -> { } : protection::check);
    }

    private static SessionRecorder recorder(SessionStore session) {
        return session == null ? SessionRecorder.NONE : session;
    }

    /** 读取需要恢复的历史；损坏行只警告，不阻断启动。 */
    private List<Message> restoredHistory(SessionStore session) {
        if (session == null) {
            return List.of();
        }
        try {
            SessionStore.LoadResult loaded = session.loadMessages();
            if (loaded.skippedLines() > 0) {
                err.println("[会话记录] 有 " + loaded.skippedLines() + " 行损坏记录已跳过（其余历史正常载入）");
            }
            return loaded.messages();
        } catch (IOException failed) {
            err.println("[会话记录] 历史读取失败，本次以空历史启动：" + failed.getMessage());
            return List.of();
        }
    }

    /** 交互式模式：反复读取自然语言任务，直到 exit/quit 或输入结束。 */
    public int runRepl() {
        if (client == null) { err.println("对话需要模型配置"); return 2; }
        out.println("AI Coding Agent 已就绪：root=" + root
                + "，可用工具=" + String.join(", ", registrar.names()));
        if (session != null) {
            out.println(session.describe() + "，共恢复 " + (llm.history().size() - 1) + " 条历史消息");
        } else {
            out.println("本次运行不记录会话。");
        }
        out.println("直接输入自然语言任务；clear 清空对话历史，exit/quit 退出。");
        if (user.autoApprove()) {
            out.println("注意：已开启 --yes，写文件与执行命令将不再逐次确认。");
        }

        while (true) {
            out.print(System.lineSeparator() + "用户 > ");
            out.flush();

            Optional<String> line;
            try {
                line = user.nextTask();
            } catch (IOException failed) {
                err.println("读取输入失败: " + failed.getMessage());
                return 1;
            }
            if (line.isEmpty()) {
                break;
            }

            String task = line.get();
            if (task.isEmpty()) {
                continue;
            }
            if (EXIT_COMMAND.equalsIgnoreCase(task) || QUIT_COMMAND.equalsIgnoreCase(task)) {
                break;
            }
            if (CLEAR_COMMAND.equalsIgnoreCase(task)) {
                llm.reset();
                if (session == null) {
                    out.println("对话历史已清空。");
                } else {
                    try {
                        out.println("对话历史已清空，并开始新会话 " + session.startNewSession() + "（旧记录已保留）。");
                    } catch (IOException failed) {
                        out.println("对话历史已清空（新会话文件创建失败：" + failed.getMessage() + "）。");
                    }
                }
                continue;
            }

            out.println("Agent > " + controller.runTask(task));
        }

        out.println("再见。");
        return 0;
    }

    /** 单任务模式：跑完一个任务后退出，供脚本化调用（配合 --yes）。 */
    public int runTask(String task) {
        if (client == null) { err.println("对话需要模型配置"); return 2; }
        out.println("Agent > " + controller.runTask(task));
        return 0;
    }

    /** 显式任务和普通对话分流，查询不需要模型配置。 */
    public int startManagedTask(String goal, String acceptanceCommand) {
        return startManagedTask(goal, acceptanceCommand, ConsoleStreams.hasInteractiveConsole());
    }

    public int startManagedTask(String goal, String acceptanceCommand, boolean interactive) {
        if (client == null) { err.println("任务需要模型配置"); return 2; }
        try {
            TaskRecord task = new TaskCoordinator(root, commandRunner, (managedSession, protection, managedRunner) ->
                    new CodingAgentCli(root, client, user.autoApprove(), maxSteps, in, out, err,
                            managedSession, managedRunner, protection).controller, out)
                    .start(goal, acceptanceCommand == null ? "mvn test" : acceptanceCommand, maxSteps, interactive);
            return printTask(task) == TaskRecord.State.Verified ? 0 : 1;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt(); err.println("任务验收被中断"); return 4;
        } catch (IOException | java.io.UncheckedIOException failed) {
            err.println("任务执行或持久化失败: " + failed.getMessage()); return 4;
        }
    }

    public int listTasks() {
        try {
            List<TaskRecord> tasks = new TaskStore(root).list();
            if (tasks.isEmpty()) { out.println("暂无任务。"); }
            for (TaskRecord task : tasks) { printTask(task); }
            return 0;
        } catch (IOException failed) { err.println("任务查询失败: " + failed.getMessage()); return 4; }
    }

    public int abandonTask(String id, boolean interactive) {
        try {
            printTask(new com.agent.task.TaskResolution(root, in, out).abandon(id, interactive));
            return 1;
        } catch (IOException | java.io.UncheckedIOException failure) {
            err.println("放弃任务失败: " + failure.getMessage()); return 4;
        }
    }

    public int replanTask(String id, boolean interactive) {
        try {
            printTask(new com.agent.task.TaskReplan(root, in, out).approve(id, interactive));
            return 1;
        } catch (IOException | java.io.UncheckedIOException failed) {
            err.println("重新规划失败: " + failed.getMessage()); return 4;
        }
    }

    public int retryUncertainOperation(String id, String operationId, boolean interactive) {
        try {
            printTask(new com.agent.task.TaskResolution(root, in, out).retry(id, operationId, interactive));
            return 1;
        } catch (IOException | java.io.UncheckedIOException failure) {
            err.println("处置结果不明操作失败: " + failure.getMessage()); return 4;
        }
    }

    public int resumeManagedTask(String id) {
        return resumeManagedTask(id, ConsoleStreams.hasInteractiveConsole());
    }

    /** 本次普通工具审批选择独立于上次进程，不继承旧 --yes。 */
    public int resumeManagedTask(String id, boolean interactive) {
        if (client == null) { err.println("任务需要模型配置"); return 2; }
        try {
            TaskRecord task = new TaskCoordinator(root, commandRunner, (managedSession, protection, managedRunner) ->
                    new CodingAgentCli(root, client, user.autoApprove(), maxSteps, in, out, err,
                            managedSession, managedRunner, protection).controller, out).resume(id, interactive);
            return printTask(task) == TaskRecord.State.Verified ? 0 : 1;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt(); err.println("任务恢复被中断"); return 4;
        } catch (IOException | java.io.UncheckedIOException failed) {
            err.println("任务恢复或持久化失败: " + failed.getMessage()); return 4;
        }
    }

    public int showTask(String id) {
        try { printTask(new TaskStore(root).read(id)); return 0; }
        catch (IOException failed) { err.println("任务查询失败: " + failed.getMessage()); return 4; }
    }

    public int raiseTaskBudget(String id, int maximum) {
        try {
            printTask(new TaskCoordinator(root, commandRunner, null, out).raiseBudget(id, maximum));
            return 0;
        } catch (IOException failed) { err.println("提高预算失败: " + failed.getMessage()); return 4; }
    }

    /** 重新约定只记录新基线，不隐式继续编码，也不会恢复旧 Verified。 */
    public int reagreeAcceptance(String id, String command, boolean interactive) {
        try {
            TaskRecord task = new TaskCoordinator(root, commandRunner, null, out)
                    .reagree(id, command, in, interactive);
            if (task != null) { printTask(task); }
            return 1;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt(); err.println("重新约定验收被中断"); return 4;
        } catch (IOException | java.io.UncheckedIOException failed) {
            err.println("重新约定失败: " + failed.getMessage()); return 4;
        }
    }

    private TaskRecord.State printTask(TaskRecord task) throws IOException {
        TaskRecord.State state = task.state;
        String reason = task.reason;
        TaskStore store = new TaskStore(root);
        if (state == TaskRecord.State.Verified && !store.isCurrentVerified(task)) {
            state = TaskRecord.State.Paused;
            reason = "项目输入版本已变化，原验收失效；请新建任务";
        }
        out.println("任务: " + task.id + "\n状态: " + state + "\n目标: " + task.goal
                + "\n会话: " + task.sessionId + "\n验收: " + task.command
                + "\n验收版本: " + task.acceptanceVersion + "\n原因: " + reason);
        var progress = task.progress;
        out.println("模型请求: " + progress.requests + "/" + progress.maxRequests
                + "\n工具调用: " + progress.tools + "\ntoken: 输入=" + progress.promptTokens
                + "，输出=" + progress.completionTokens + "，未知请求=" + progress.unknownRequests
                + "\n累计耗时: " + progress.elapsedMillis + " ms");
        for (var decision : progress.decisions) {
            out.println("决策 " + decision.number + ": " + decision.status + "，验收版本="
                    + decision.acceptanceVersion + "，失败来源=" + decision.failureSource);
            for (var operation : decision.operations) {
                out.println("工具 " + operation.callId + ": " + operation.tool + " " + operation.status
                        + "，修改=" + operation.changes.keySet()
                        + "，操作ID: " + operation.reference(decision.number)
                        + "，已记录进程=" + operation.processes
                        + (operation.status == TaskRecord.OperationState.RUNNING && !"READ".equals(operation.risk) ? "，结果不明" : ""));
            }
        }
        for (var resolution : task.resolutions) {
            out.println("人工处置: " + resolution.action + "，目标=" + resolution.target + "，新操作="
                    + resolution.replacementId + "，时间=" + resolution.at
                    + "，旧进程停止确认=" + resolution.stoppedConfirmed
                    + "，取消旧操作=" + resolution.cancelledOperations
                    + (resolution.originalOperation == null ? "" : "，原操作结果不明"));
        }
        if (task.checkpoint.acceptancePending) {
            out.println("验收结果不明，操作ID: " + com.agent.task.TaskResolution.acceptanceReference(task)
                    + "，已记录进程=" + task.checkpoint.acceptanceProcesses);
        }
        for (var change : progress.budgetChanges) {
            out.println("预算调整: " + change.previous() + " -> " + change.current() + "，时间=" + change.at());
        }
        for (var error : progress.errors.entrySet()) {
            out.println("核心错误轮次: " + error.getValue() + "，错误=" + progress.errorDescriptions.get(error.getKey()));
        }
        for (TaskRecord.EvidenceRef ref : task.checks) {
            TaskAcceptance.Evidence evidence = store.readEvidence(task, ref);
            out.println(evidence.kind() + ": " + evidence.verdict() + "，测试数=" + evidence.tests()
                    + "，原因=" + evidence.reason());
        }
        for (TaskRecord.Agreement agreement : task.agreementHistory) {
            out.println("历史约定: " + agreement.version() + "，仅用于审计，不是当前验收证据");
        }
        return state;
    }
}
