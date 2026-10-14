package com.agent.roles;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.PrintStream;
import java.util.Optional;

/**
 * 角色一：<b>用户</b>。
 *
 * <p>职责边界很窄，只有两件事：
 *
 * <ol>
 *   <li><b>提供自然语言任务</b>：{@link #nextTask()} 从标准输入读取一条任务；</li>
 *   <li><b>行使最终决定权</b>：守卫把「需要人工确认」的操作交回这里（{@link #confirm(String)}），
 *       用户同意才继续，拒绝则该操作不会被执行。</li>
 * </ol>
 *
 * <p>用户不解析任务、不选工具、不碰文件 ——「理解任务」是 LLM 的职责，
 * 「执行任务」是执行员的职责。用户只在需要人类判断的节点上出现。
 */
public class UserRole {

    private final BufferedReader in;
    private final PrintStream out;
    private final boolean autoApprove;

    /**
     * @param in          输入流（交互式终端或测试用的字符串流）
     * @param out         输出流，用于打印确认提示
     * @param autoApprove 是否自动同意所有确认请求（对应 CLI 的 {@code --yes}，
     *                    适合脚本化场景；此时用户仍可随时中断进程）
     */
    public UserRole(BufferedReader in, PrintStream out, boolean autoApprove) {
        this.in = in;
        this.out = out;
        this.autoApprove = autoApprove;
    }

    /** 是否处于「自动同意」模式。 */
    public boolean autoApprove() {
        return autoApprove;
    }

    /** 读取下一条任务；输入结束（EOF）时返回 {@link Optional#empty()}。 */
    public Optional<String> nextTask() throws IOException {
        String line = in.readLine();
        if (line == null) {
            return Optional.empty();
        }
        return Optional.of(line.trim());
    }

    /**
     * 回应守卫的确认请求。
     *
     * <p>拿不到输入（管道结束、EOF）时按<b>拒绝</b>处理 —— 安全策略的默认值必须是「不放行」。
     *
     * @param question 守卫给出的问题描述（说明为什么要确认）
     * @return 用户是否同意
     */
    public boolean confirm(String question) {
        if (autoApprove) {
            out.println("用户确认 > " + question + " → 已按 --yes 自动同意");
            return true;
        }
        out.println("用户确认 > " + question + " （输入 y 同意 / 其它任意输入拒绝）");
        out.print("y/N > ");
        out.flush();
        String answer;
        try {
            answer = in.readLine();
        } catch (IOException failed) {
            return false;
        }
        if (answer == null) {
            out.println("（没有收到输入，按拒绝处理）");
            return false;
        }
        String normalized = answer.trim();
        return normalized.equalsIgnoreCase("y")
                || normalized.equalsIgnoreCase("yes")
                || normalized.equals("是");
    }

    /** 用户角色的输出（不参与决策，只负责显示）。 */
    public void say(String message) {
        out.println(message);
    }
}
