package com.agent;

import com.agent.llm.ApiKeyLoader;
import com.agent.llm.DeepSeekClient;
import com.agent.roles.ControllerRole;
import com.agent.session.SessionExporter;
import com.agent.session.SessionPaths;
import com.agent.session.SessionStore;
import com.agent.task.KnowledgeMode;

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

        try {
            var skill = SkillCommands.parse(args);
            if (skill != null) {
                int code = SkillCommands.run(skill);
                if (code != 0) { System.exit(code); }
                return;
            }
        } catch (IllegalArgumentException invalid) {
            System.err.println("参数错误: " + invalid.getMessage());
            System.exit(2);
            return;
        }

        Options options;
        try { options = Options.parse(args); }
        catch (IllegalArgumentException invalid) {
            System.err.println("参数错误: " + invalid.getMessage());
            System.exit(2);
            return;
        }

        if (options.help()) { printHelp(System.out); return; }

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

        DeepSeekClient model;
        try {
            boolean skillMode = options.knowledgeMode() == KnowledgeMode.SKILL || options.resumeTask() != null
                    && new com.agent.task.TaskStore(root).read(options.resumeTask()).knowledgeMode == KnowledgeMode.SKILL;
            model = skillMode ? DeepSeekClient.singleAttempt(apiKey, DeepSeekClient.defaultEndpoint(), DeepSeekClient.defaultModel()) : new DeepSeekClient(apiKey);
        } catch (IOException failed) {
            System.err.println("模型任务配置失败: " + failed.getMessage()); System.exit(4); return;
        }
        CodingAgentCli cli = new CodingAgentCli(root,
                model,
                options.autoApprove(),
                options.maxSteps(),
                ConsoleStreams.newStdinReader(),
                System.out,
                System.err,
                session);

        int exitCode = options.extractExperience() != null
                ? cli.extractExperience(options.extractExperience(), true)
                : options.resumeTask() != null ? cli.resumeManagedTask(options.resumeTask()) : options.task() != null
                ? options.knowledgeMode() == KnowledgeMode.SKILL
                    ? cli.startSkillTask(options.task(), options.acceptanceCommand(), options.skillControl(), options.skillSource(), ConsoleStreams.hasInteractiveConsole())
                    : cli.startManagedTask(options.task(), options.acceptanceCommand(), ConsoleStreams.hasInteractiveConsole(), options.knowledgeMode()) : cli.runRepl();
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
                   String extractExperience, boolean experiences, String experience, String disableExperience, boolean noExperiences,
                   KnowledgeMode knowledgeMode, boolean help, Path skillControl, Path skillSource) {

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
            KnowledgeMode knowledgeMode = null;
            Path skillControl = null, skillSource = null;
            boolean help = false;

            if (args != null) {
                for (int i = 0; i < args.length; i++) {
                    String arg = args[i];
                    switch (arg) {
                        case "--help", "-h" -> help = true;
                        case "--tasks" -> tasks = true;
                        case "--no-experiences" -> {
                            if (noExperiences) { throw new IllegalArgumentException("知识模式选项不能重复"); }
                            noExperiences = true;
                        }
                        case "--knowledge-mode" -> {
                            if (knowledgeMode != null) { throw new IllegalArgumentException("知识模式选项不能重复"); }
                            if (i + 1 >= args.length || args[i + 1].isBlank() || args[i + 1].startsWith("--")) {
                                throw new IllegalArgumentException("--knowledge-mode 需要 none、experience 或 skill");
                            }
                            try { knowledgeMode = KnowledgeMode.valueOf(args[++i].toUpperCase(Locale.ROOT)); }
                            catch (IllegalArgumentException invalid) {
                                throw new IllegalArgumentException("--knowledge-mode 只支持 none、experience 或 skill");
                            }
                        }
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
                        case "--skill-control", "--skill-source" -> {
                            String flag = args[i];
                            if (i + 1 >= args.length || args[i + 1].isBlank() || args[i + 1].startsWith("--")) { throw new IllegalArgumentException(flag + " 需要规范绝对路径"); }
                            Path path = Path.of(args[++i]);
                            if (flag.equals("--skill-control")) {
                                if (skillControl != null) { throw new IllegalArgumentException("--skill-control 不能重复"); }
                                skillControl = path;
                            } else {
                                if (skillSource != null) { throw new IllegalArgumentException("--skill-source 不能重复"); }
                                skillSource = path;
                            }
                        }
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
            if (knowledgeMode != null && task == null) {
                throw new IllegalArgumentException("--knowledge-mode 仅用于新任务；恢复沿用已保存模式");
            }
            if (knowledgeMode != null && noExperiences) {
                throw new IllegalArgumentException("--knowledge-mode 与 --no-experiences 不能混用");
            }
            if ((skillControl == null) != (skillSource == null) || skillControl != null && (task == null || knowledgeMode != KnowledgeMode.SKILL)) {
                throw new IllegalArgumentException("--skill-control/--skill-source 只成对用于新建 SKILL 任务，恢复读取已保存绑定");
            }
            return new Options(task, autoApprove, local, upperCase, root, maxSteps,
                    resume, newSession, sessionName, sessionDir, exportId, tasks, taskStatus, acceptanceCommand, reagreeId, budgetTaskId,
                    resumeTask, retryTask, operation, abandonTask, replanTask,
                    extractExperience, experiences, experience, disableExperience, noExperiences,
                    knowledgeMode != null ? knowledgeMode : noExperiences ? KnowledgeMode.NONE : KnowledgeMode.EXPERIENCE, help, skillControl, skillSource);
        }
    }

    /** 帮助在读取 Key、会话和项目状态之前返回，防止查看选项意外启动执行。 */
    private static void printHelp(PrintStream out) {
        out.println("""
                AI Coding Agent CLI
                用法: java -jar agent-cli.jar [--root 项目目录] [任务或查询选项]
                  --task 目标                新建管理任务，独立验收后判断完成
                  --knowledge-mode 模式      仅新任务：none | experience | skill，默认 experience
                    none: 不使用历史知识；experience: 使用合格经验
                    skill: 技能模式，当前不加载技能，继续独立修复
                  --no-experiences           none 的兼容别名，不能与 --knowledge-mode 混用
                  --resume-task 任务ID       显式恢复，沿用已保存知识模式与累计预算
                  --tasks / --task-status ID 无模型查询任务及知识模式
                  --accept-command 命令      新任务的验收命令，默认 mvn test
                  --max-steps 次数           新任务累计请求上限，默认 12
                  --yes                      仅跳过普通工具确认
                  --start-skill-batch --request-limit N 显式新建累计有界技能批次，无需 Key
                  --generate-skill E1,E2 --skill-batch B 从合格经验生成未启用候选
                  --revise-skill V --revision-reason 原因 生成时指定已发布父版本及修订原因
                  --skill-candidates / --skill-candidate C 无 Key 查看候选和来源
                  --skill-batches / --skill-batch B 无 Key 查看累计请求、已知与未知用量
                  --trial-skill C --skill-batch B --trial-control A --trial-source S
                    --trial-catalog M --trial-max-steps N 指定候选的隔离有界开发试用
                  --skill-trial T / --skill-trials --trial-control A 无 Key 查看试用及最终请求审计
                  --skill-review C --skill-digest H --trial-control A --trial-source S 查看候选及差异
                  --related-trial P --counterexample-trial N 审核或批准时绑定正反例
                  --attest-skill-trial N --trial-control A --review-note TEXT 交互核验反例轨迹
                  --approve-skill C --skill-digest H --related-trial P --counterexample-trial N --trial-control A --trial-source S 交互发布
                  --reject-skill C --skill-digest H --review-note TEXT --trial-control A --trial-source S 交互拒绝
                  --skill-version V / --skill-versions --trial-control A --trial-source S 无 Key 查看版本
                  --disable-skill V / --enable-skill V --skill-digest H --review-note TEXT --trial-control A --trial-source S 交互停用/恢复原版
                  --skill-feedbacks [--trial-control A --trial-source S] 无 Key 查看事实与待办；--register-skill-feedback 无模型补齐当前项目记录
                  --revise-from-feedback F1,F2 --skill-batch B --revise-skill V --revision-reason TEXT [--feedback-source S --feedback-catalog C] 显式有界修订
                  --regression-trials T1,T2 审核/批准修订时绑定必要旧开发回归
                  --task TEXT --knowledge-mode skill [--skill-control A --skill-source S] 使用已批准技能；恢复不覆盖技能库
                  --local                    无模型本地回显
                  --help / -h                仅显示帮助，不读取 Key 或执行任务
                不指定任务入口时进入普通聊天；知识模式不改变普通聊天工具规则。""");
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
