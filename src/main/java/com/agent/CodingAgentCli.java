package com.agent;

import com.agent.llm.LLMClient;
import com.agent.roles.ControllerRole;
import com.agent.roles.ExecutorRole;
import com.agent.roles.GuardRole;
import com.agent.roles.LlmRole;
import com.agent.roles.RegistrarRole;
import com.agent.roles.UserRole;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Path;
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
 * 输入输出流按角色的构造器接起来，然后提供两种运行方式（交互式 REPL 与单任务）。
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
    private final PrintStream out;
    private final PrintStream err;

    public CodingAgentCli(Path root,
                          LLMClient client,
                          boolean autoApprove,
                          int maxSteps,
                          BufferedReader in,
                          PrintStream out,
                          PrintStream err) {
        this.root = root.toAbsolutePath().normalize();
        this.out = out;
        this.err = err;
        this.user = new UserRole(in, out, autoApprove);
        this.llm = new LlmRole(client);
        this.registrar = RegistrarRole.standard(this.root);
        GuardRole guard = new GuardRole(this.root);
        ExecutorRole executor = new ExecutorRole(this.root, ExecutorRole.DEFAULT_MAX_RESULT_CHARS, out);
        this.controller = new ControllerRole(llm, registrar, guard, executor, user, maxSteps, out);
    }

    /** 交互式模式：反复读取自然语言任务，直到 exit/quit 或输入结束。 */
    public int runRepl() {
        out.println("AI Coding Agent 已就绪：root=" + root
                + "，可用工具=" + String.join(", ", registrar.names()));
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
                out.println("对话历史已清空。");
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
