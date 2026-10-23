package com.agent;

import com.agent.llm.ApiKeyLoader;
import com.agent.llm.DeepSeekClient;
import com.agent.roles.ControllerRole;
import com.agent.session.SessionExporter;
import com.agent.session.SessionPaths;
import com.agent.session.SessionStore;

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

        Options options;
        try { options = Options.parse(args); }
        catch (IllegalArgumentException invalid) {
            System.err.println("参数错误: " + invalid.getMessage());
            System.exit(2);
            return;
        }

        Path root = options.root() == null ? Path.of("").toAbsolutePath() : options.root();
        if (options.tasks() || options.taskStatus() != null || options.reagreeId() != null || options.budgetTaskId() != null
                || options.retryTask() != null || options.abandonTask() != null || options.replanTask() != null
                || options.experiences() || options.experience() != null || options.disableExperience() != null
                || options.extractExperience() != null && !ConsoleStreams.hasInteractiveConsole()) {
            CodingAgentCli query = new CodingAgentCli(root, null, false, options.maxSteps(),
                    ConsoleStreams.newStdinReader(), System.out, System.err);
            int code = options.extractExperience() != null ? query.extractExperience(options.extractExperience(), false)
                    : options.experiences() ? query.listExperiences()
                    : options.experience() != null ? query.showExperience(options.experience())
                    : options.disableExperience() != null ? query.disableExperience(options.disableExperience())
                    : options.replanTask() != null ? query.replanTask(options.replanTask(), ConsoleStreams.hasInteractiveConsole())
                    : options.retryTask() != null
                    ? query.retryUncertainOperation(options.retryTask(), options.operation(), ConsoleStreams.hasInteractiveConsole())
                    : options.abandonTask() != null ? query.abandonTask(options.abandonTask(), ConsoleStreams.hasInteractiveConsole())
                    : options.budgetTaskId() != null
                    ? query.raiseTaskBudget(options.budgetTaskId(), options.maxSteps()) : options.reagreeId() != null
                    ? query.reagreeAcceptance(options.reagreeId(), options.acceptanceCommand(), ConsoleStreams.hasInteractiveConsole())
                    : options.tasks() ? query.listTasks() : query.showTask(options.taskStatus());
            if (code != 0) { System.exit(code); }
            return;
        }

        if (options.local()) {
            run(new Agent(), ConsoleStreams.newStdinReader(), System.out, options.upperCase());
            return;
        }

        Path sessionDir = SessionPaths.resolveDir(root, options.sessionDir());

        if (options.exportId() != null) {
            exportSession(sessionDir, options.exportId());
            return;
        }

        String apiKey = ApiKeyLoader.loadDeepSeekKey();
        if (apiKey == null || apiKey.isBlank()) {
            if (options.task() != null || options.resumeTask() != null || options.extractExperience() != null) {
                System.err.println("错误：--task / --resume-task / --extract-experience 需要 DeepSeek Key。");
                System.err.println("      请在项目根目录 .env 写入 DEEPSEEK_API_KEY=你的Key。");
                System.exit(2);
                return;
            }
            System.err.println("提示：未找到 DEEPSEEK_API_KEY，进入本地响应模式（Lab01 行为）。");
            System.err.println("      在项目根目录 .env 写入 DEEPSEEK_API_KEY=你的Key 即可启用 Coding Agent。");
            run(new Agent(), ConsoleStreams.newStdinReader(), System.out, options.upperCase());
            return;
        }

        SessionStore session;
        try {
            session = options.task() == null && options.resumeTask() == null && options.extractExperience() == null
                    ? openSession(sessionDir, options) : null;
        } catch (IOException failed) {
            System.err.println("会话初始化失败：" + failed.getMessage());
            System.exit(4);
            return;
        }

        CodingAgentCli cli = new CodingAgentCli(root,
                new DeepSeekClient(apiKey),
                options.autoApprove(),
                options.maxSteps(),
                ConsoleStreams.newStdinReader(),
                System.out,
                System.err,
                session);

        int exitCode = options.extractExperience() != null
                ? cli.extractExperience(options.extractExperience(), true)
                : options.resumeTask() != null ? cli.resumeManagedTask(options.resumeTask()) : options.task() != null
                ? cli.startManagedTask(options.task(), options.acceptanceCommand(), ConsoleStreams.hasInteractiveConsole(), !options.noExperiences()) : cli.runRepl();
        if (exitCode != 0) {
            System.exit(exitCode);
        }
    }

    /**
     * 按命令行参数决定这次运行使用哪个会话。
     *
     * <ul>
     *   <li>管理任务由任务协调器创建独立聊天，不经过普通聊天恢复；</li>
     *   <li>{@code --resume <id>}：续上指定会话；</li>
     *   <li>{@code --new}：强制新建；</li>
     *   <li>{@code --session <名字>}：同名则续、否则新建；</li>
     *   <li>都没有：自动续上一次会话，没有历史就新建（D③ 的默认行为）。</li>
     * </ul>
     */
    private static SessionStore openSession(Path dir, Options options) throws IOException {
        if (options.resume() != null) {
            return SessionStore.start(dir, SessionStore.Mode.RESUME, options.resume());
        }
        if (options.newSession()) {
            return SessionStore.start(dir, SessionStore.Mode.NEW, options.sessionName());
        }
        if (options.sessionName() != null) {
            return SessionStore.start(dir, SessionStore.Mode.NAMED, options.sessionName());
        }
        return SessionStore.start(dir, SessionStore.Mode.AUTO, null);
    }

    /** 导出会话为 markdown（N①）；不需要 API Key，因此放在 Key 检查之前。 */
    private static void exportSession(Path dir, String id) {
        try {
            System.out.print(SessionExporter.toMarkdown(dir, id));
        } catch (IOException failed) {
            System.err.println("导出失败：" + failed.getMessage());
            System.exit(3);
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
     * @param resume     要恢复的会话名（{@code --resume}），可为 {@code latest}
     * @param newSession 是否强制新建会话（{@code --new}）
     * @param sessionName 具名会话（{@code --session}）：存在则续、否则新建
     * @param sessionDir 会话目录（{@code --session-dir}），{@code user} 表示用户目录
     * @param exportId   要导出成 markdown 的会话（{@code --export}），不配置则正常运行
     */
    record Options(String task, boolean autoApprove, boolean local, boolean upperCase,
                   Path root, int maxSteps, String resume, boolean newSession,
                   String sessionName, String sessionDir, String exportId,
                   boolean tasks, String taskStatus, String acceptanceCommand, String reagreeId, String budgetTaskId,
                   String resumeTask, String retryTask, String operation, String abandonTask, String replanTask,
                   String extractExperience, boolean experiences, String experience, String disableExperience, boolean noExperiences) {

        static Options parse(String[] args) {
            String task = null;
            boolean autoApprove = false;
            boolean local = false;
            boolean upperCase = false;
            Path root = null;
            int maxSteps = ControllerRole.DEFAULT_MAX_STEPS;
            String resume = null;
            boolean newSession = false;
            String sessionName = null;
            String sessionDir = null;
            String exportId = null;
            boolean tasks = false;
            String taskStatus = null;
            String acceptanceCommand = null;
            String reagreeId = null;
            String budgetTaskId = null;
            String resumeTask = null;
            String retryTask = null;
            String operation = null;
            String abandonTask = null;
            String replanTask = null;
            String extractExperience = null;
            boolean experiences = false;
            String experience = null;
            String disableExperience = null;
            boolean maximumSpecified = false;
            boolean noExperiences = false;

            if (args != null) {
                for (int i = 0; i < args.length; i++) {
                    String arg = args[i];
                    switch (arg) {
                        case "--tasks" -> tasks = true;
                        case "--no-experiences" -> noExperiences = true;
                        case "--experiences" -> experiences = true;
                        case "--task-status", "--accept-command", "--reagree-acceptance", "--task-budget", "--resume-task",
                                "--retry-operation", "--operation", "--abandon-task", "--replan-task",
                                "--extract-experience", "--experience", "--disable-experience" -> {
                            if (i + 1 >= args.length || args[i + 1].isBlank() || args[i + 1].startsWith("--")) {
                                throw new IllegalArgumentException(arg + " 需要非空参数");
                            }
                            if (arg.equals("--task-status")) { taskStatus = args[++i]; }
                            else if (arg.equals("--extract-experience")) { extractExperience = args[++i]; }
                            else if (arg.equals("--experience")) { experience = args[++i]; }
                            else if (arg.equals("--disable-experience")) { disableExperience = args[++i]; }
                            else if (arg.equals("--replan-task")) { replanTask = args[++i]; }
                            else if (arg.equals("--retry-operation")) { retryTask = args[++i]; }
                            else if (arg.equals("--operation")) { operation = args[++i]; }
                            else if (arg.equals("--abandon-task")) { abandonTask = args[++i]; }
                            else if (arg.equals("--resume-task")) { resumeTask = args[++i]; }
                            else if (arg.equals("--task-budget")) { budgetTaskId = args[++i]; }
                            else if (arg.equals("--reagree-acceptance")) { reagreeId = args[++i]; }
                            else { acceptanceCommand = args[++i]; }
                        }
                        case "--task", "-t" -> {
                            if (i + 1 >= args.length || args[i + 1].isBlank() || args[i + 1].startsWith("--")) {
                                throw new IllegalArgumentException("--task 需要非空目标");
                            }
                            task = args[++i];
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
                            if (i + 1 >= args.length) { throw new IllegalArgumentException("--max-steps 需要正整数"); }
                            maxSteps = Integer.parseInt(args[++i]);
                            maximumSpecified = true;
                        }
                        case "--new" -> newSession = true;
                        case "--resume" -> {
                            if (i + 1 < args.length) {
                                resume = args[++i];
                            }
                        }
                        case "--session" -> {
                            if (i + 1 < args.length) {
                                sessionName = args[++i];
                            }
                        }
                        case "--session-dir" -> {
                            if (i + 1 < args.length) {
                                sessionDir = args[++i];
                            }
                        }
                        case "--export" -> {
                            if (i + 1 < args.length) {
                                exportId = args[++i];
                            }
                        }
                        default -> {
                            // 未知参数忽略：CLI 由脚本调用，不能因为多一个参数就跑不起来
                        }
                    }
                }
            }
            int managedModes = (task == null ? 0 : 1) + (tasks ? 1 : 0) + (taskStatus == null ? 0 : 1)
                    + (reagreeId == null ? 0 : 1) + (budgetTaskId == null ? 0 : 1) + (resumeTask == null ? 0 : 1)
                    + (retryTask == null ? 0 : 1) + (abandonTask == null ? 0 : 1) + (replanTask == null ? 0 : 1)
                    + (extractExperience == null ? 0 : 1) + (experiences ? 1 : 0) + (experience == null ? 0 : 1)
                    + (disableExperience == null ? 0 : 1);
            if (managedModes > 1 || managedModes > 0 && (local || resume != null || newSession
                    || sessionName != null || sessionDir != null || exportId != null)) {
                throw new IllegalArgumentException("管理任务、查询、本地回显和聊天恢复参数不能混用");
            }
            if (acceptanceCommand != null && task == null && reagreeId == null) {
                throw new IllegalArgumentException("--accept-command 必须与 --task 或 --reagree-acceptance 一起使用");
            }
            if (reagreeId != null && acceptanceCommand == null) {
                throw new IllegalArgumentException("--reagree-acceptance 必须明确提供 --accept-command");
            }
            if (maxSteps <= 0) { throw new IllegalArgumentException("--max-steps 必须为正数"); }
            if (budgetTaskId != null && !maximumSpecified) {
                throw new IllegalArgumentException("--task-budget 必须明确提供 --max-steps 新总预算");
            }
            if (resumeTask != null && maximumSpecified) {
                throw new IllegalArgumentException("恢复沿用已记录总预算；提高额度请使用 --task-budget");
            }
            if ((retryTask == null) != (operation == null)) {
                throw new IllegalArgumentException("--retry-operation 与 --operation 必须一起提供");
            }
            if ((retryTask != null || abandonTask != null || replanTask != null) && maximumSpecified) {
                throw new IllegalArgumentException("人工处置不调整预算；请单独使用 --task-budget");
            }
            if ((extractExperience != null || experiences || experience != null || disableExperience != null) && maximumSpecified) {
                throw new IllegalArgumentException("经验操作不调整任务预算");
            }
            if (noExperiences && task == null) { throw new IllegalArgumentException("--no-experiences 仅用于新任务；恢复沿用已保存选项"); }
            return new Options(task, autoApprove, local, upperCase, root, maxSteps,
                    resume, newSession, sessionName, sessionDir, exportId, tasks, taskStatus, acceptanceCommand, reagreeId, budgetTaskId,
                    resumeTask, retryTask, operation, abandonTask, replanTask,
                    extractExperience, experiences, experience, disableExperience, noExperiences);
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
