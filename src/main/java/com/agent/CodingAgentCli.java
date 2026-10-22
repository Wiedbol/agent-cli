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
        this.root = root.toAbsolutePath().normalize();
        this.out = out;
        this.err = err;
        this.session = session;
        this.user = new UserRole(in, out, autoApprove);
        this.llm = new LlmRole(client, restoredHistory(session), recorder(session));
        this.registrar = RegistrarRole.standard(this.root, commandRunner);
        GuardRole guard = new GuardRole(this.root);
        ExecutorRole executor = new ExecutorRole(this.root, ExecutorRole.DEFAULT_MAX_RESULT_CHARS, out);
        this.controller = new ControllerRole(llm, registrar, guard, executor, user, maxSteps, out,
                recorder(session));
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
        out.println("Agent > " + controller.runTask(task));
        return 0;
    }
}
