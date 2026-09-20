package com.agent;

import com.agent.llm.ApiKeyLoader;
import com.agent.llm.DeepSeekClient;
import com.agent.tool.ToolRegistry;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.PrintStream;
import java.util.Locale;

/**
 * CLI 接线：加载配置 → 创建对象 → 处理终端交互。
 *
 * <p>这里刻意不出现任何 LLM 协议解析或文件工具逻辑，{@code main} 只做组装；
 * {@link #run(Agent, BufferedReader, PrintStream, boolean)} 是包级可见的 I/O 循环，
 * 便于用字节流在单元测试中模拟 GBK / UTF-8 控制台。
 */
public class Main {

    static final String QUIT_COMMAND = "quit";
    static final String EXIT_COMMAND = "exit";
    static final String CLEAR_COMMAND = "clear";
    static final String UPPER_FLAG = "--upper";

    public static void main(String[] args) throws IOException {
        // 先按环境把 stdout / stderr 固定为「终端能正确解码的编码」，再取对应流
        ConsoleStreams.configureStdout();
        ConsoleStreams.configureStderr();
        Agent agent = createAgent();
        run(agent, ConsoleStreams.newStdinReader(), System.out, hasUpperFlag(args));
    }

    /**
     * 创建 Agent：有 Key 走 DeepSeek 模型模式，没有 Key 退化为本地模式而不是直接退出。
     *
     * <p>这样安排是因为本地模式仍然是可用的交付物（Lab01 的管道用法与验收命令都依赖它），
     * 而缺少 Key 属于「环境没配好」，用提示信息说明即可。
     */
    static Agent createAgent() {
        String apiKey = ApiKeyLoader.loadDeepSeekKey();
        if (apiKey == null || apiKey.isBlank()) {
            System.err.println("提示：未找到 DEEPSEEK_API_KEY，进入本地响应模式（Lab01 行为）。");
            System.err.println("      在项目根目录 .env 写入 DEEPSEEK_API_KEY=你的Key 即可启用 Agent。");
            return new Agent();
        }
        System.out.println("已启用 DeepSeek Agent（model=" + DeepSeekClient.defaultModel()
                + "），clear 清空历史，exit/quit 退出。");
        return new Agent(new DeepSeekClient(apiKey), new ToolRegistry());
    }

    /** Lab01 的默认行为：不做任何大小写转换。 */
    static void run(Agent agent, BufferedReader reader, PrintStream out) throws IOException {
        run(agent, reader, out, false);
    }

    /**
     * I/O 循环：逐行读取 → 交给 {@code Agent} → 打印；读到 {@code quit}/{@code exit} 或 EOF 结束。
     *
     * @param upperCaseOutput Lab01 挑战任务 3：{@code --upper} 把输出统一转成大写。
     *                        放在输出边界做而不改 {@code Agent} 的公开 API，
     *                        因为它属于展示层需求，与 Agent 的决策逻辑无关。
     */
    static void run(Agent agent, BufferedReader reader, PrintStream out, boolean upperCaseOutput)
            throws IOException {
        String line;
        while ((line = reader.readLine()) != null) {
            String command = line.trim();
            if (QUIT_COMMAND.equalsIgnoreCase(command) || EXIT_COMMAND.equalsIgnoreCase(command)) {
                break;
            }
            if (CLEAR_COMMAND.equalsIgnoreCase(command)) {
                agent.clearHistory();
                out.println("历史已清空。");
                continue;
            }
            out.println(format(agent.run(line), upperCaseOutput));
        }
        out.flush();
    }

    /** 解析 {@code --upper}：只判断开关是否存在，不引入完整的参数解析框架。 */
    static boolean hasUpperFlag(String[] args) {
        if (args == null) {
            return false;
        }
        for (String arg : args) {
            if (UPPER_FLAG.equals(arg)) {
                return true;
            }
        }
        return false;
    }

    private static String format(String text, boolean upperCaseOutput) {
        String safeText = text == null ? "" : text;
        return upperCaseOutput ? safeText.toUpperCase(Locale.ROOT) : safeText;
    }
}
