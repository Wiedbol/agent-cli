package com.agent;

import com.agent.llm.ApiKeyLoader;
import com.agent.llm.DeepSeekClient;
import com.agent.roles.ControllerRole;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.Locale;

/**
 * CLI 入口：解析参数 → 装配角色 → 运行。
 *
 * <p>三种运行形态：
 *
 * <ul>
 *   <li><b>Coding Agent（默认）</b>：有 DeepSeek Key 时启动六个角色组成的完整链路；</li>
 *   <li><b>本地模式</b>：{@code --local}，或没有 Key 时自动降级为 Lab01 的输入回显；</li>
 *   <li><b>单任务模式</b>：{@code --task "..."}，跑完一个任务即退出，便于脚本化验收。</li>
 * </ul>
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

        Options options = Options.parse(args);

        if (options.local()) {
            run(new Agent(), ConsoleStreams.newStdinReader(), System.out, options.upperCase());
            return;
        }

        String apiKey = ApiKeyLoader.loadDeepSeekKey();
        if (apiKey == null || apiKey.isBlank()) {
            if (options.task() != null) {
                System.err.println("错误：--task 需要 DeepSeek Key。");
                System.err.println("      请在项目根目录 .env 写入 DEEPSEEK_API_KEY=你的Key。");
                System.exit(2);
                return;
            }
            System.err.println("提示：未找到 DEEPSEEK_API_KEY，进入本地响应模式（Lab01 行为）。");
            System.err.println("      在项目根目录 .env 写入 DEEPSEEK_API_KEY=你的Key 即可启用 Coding Agent。");
            run(new Agent(), ConsoleStreams.newStdinReader(), System.out, options.upperCase());
            return;
        }

        Path root = options.root() == null ? Path.of("").toAbsolutePath() : options.root();
        CodingAgentCli cli = new CodingAgentCli(root,
                new DeepSeekClient(apiKey),
                options.autoApprove(),
                options.maxSteps(),
                ConsoleStreams.newStdinReader(),
                System.out,
                System.err);

        int exitCode = options.task() != null ? cli.runTask(options.task()) : cli.runRepl();
        if (exitCode != 0) {
            System.exit(exitCode);
        }
    }

    /**
     * 命令行参数。
     *
     * @param task       单任务模式的任务文本，{@code null} 表示交互式
     * @param autoApprove 是否自动同意守卫的确认请求（{@code --yes}）
     * @param local      是否强制使用本地回显模式（{@code --local}）
     * @param upperCase  是否把输出转成大写（{@code --upper}，Lab01 挑战任务 3）
     * @param root       项目根目录（沙箱边界），默认当前工作目录
     * @param maxSteps   单个任务的最大步数
     */
    record Options(String task, boolean autoApprove, boolean local, boolean upperCase,
                   Path root, int maxSteps) {

        static Options parse(String[] args) {
            String task = null;
            boolean autoApprove = false;
            boolean local = false;
            boolean upperCase = false;
            Path root = null;
            int maxSteps = ControllerRole.DEFAULT_MAX_STEPS;

            if (args != null) {
                for (int i = 0; i < args.length; i++) {
                    String arg = args[i];
                    switch (arg) {
                        case "--task", "-t" -> {
                            if (i + 1 < args.length) {
                                task = args[++i];
                            }
                        }
                        case "--yes", "-y" -> autoApprove = true;
                        case "--local" -> local = true;
                        case UPPER_FLAG -> upperCase = true;
                        case "--root" -> {
                            if (i + 1 < args.length) {
                                root = Path.of(args[++i]);
                            }
                        }
                        case "--max-steps" -> {
                            if (i + 1 < args.length) {
                                maxSteps = Integer.parseInt(args[++i]);
                            }
                        }
                        default -> {
                            // 未知参数忽略：CLI 由脚本调用，不能因为多一个参数就跑不起来
                        }
                    }
                }
            }
            return new Options(task, autoApprove, local, upperCase, root, maxSteps);
        }
    }

    /** 解析 {@code --upper}：只判断开关是否存在。 */
    static boolean hasUpperFlag(String[] args) {
        return Options.parse(args).upperCase();
    }

    /** Lab01 的默认行为：不做任何大小写转换。 */
    static void run(Agent agent, BufferedReader reader, PrintStream out) throws IOException {
        run(agent, reader, out, false);
    }

    /**
     * 本地模式的 I/O 循环：逐行读取 → 交给 {@link Agent} → 打印；
     * 读到 {@code quit}/{@code exit} 或 EOF 结束。
     *
     * @param upperCaseOutput Lab01 挑战任务 3：把输出统一转成大写
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

    private static String format(String text, boolean upperCaseOutput) {
        String safeText = text == null ? "" : text;
        return upperCaseOutput ? safeText.toUpperCase(Locale.ROOT) : safeText;
    }
}
