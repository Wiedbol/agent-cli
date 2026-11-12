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
import com.agent.session.SessionExporter;
import com.agent.session.SessionPaths;
import com.agent.session.SessionRecorder;
import com.agent.session.SessionStore;
import com.agent.skill.ChatLearning;
import com.agent.task.TaskRecord;
import com.agent.task.TaskStore;
import com.agent.task.TaskAcceptance;
import com.agent.task.TaskCoordinator;
import com.agent.task.TaskProtection;
import com.agent.task.KnowledgeMode;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

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

    private enum ApprovalMode { MANUAL, FILES, PROJECT, ALL }

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
    private final ChatTrace chatTrace;
    private ApprovalMode approvalMode;
    private String currentTaskId;

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
        this(root, client, autoApprove, false, maxSteps, in, out, err, session, commandRunner);
    }

    CodingAgentCli(Path root, LLMClient client, boolean autoApprove, boolean autoApproveFiles, int maxSteps,
                   BufferedReader in, PrintStream out, PrintStream err, SessionStore session, CommandRunner commandRunner) {
        this(root, client, autoApprove ? ApprovalMode.ALL : autoApproveFiles ? ApprovalMode.FILES : ApprovalMode.MANUAL,
                maxSteps, in, out, err, session,
                commandRunner, null, null, new ChatTrace(out));
    }

    private CodingAgentCli(Path root, LLMClient client, ApprovalMode initialApprovalMode, int maxSteps,
                           BufferedReader in, PrintStream out, PrintStream err, SessionStore session,
                           CommandRunner commandRunner, TaskProtection protection, com.agent.skill.SkillKnowledge knowledge,
                           ChatTrace chatTrace) {
        this.root = root.toAbsolutePath().normalize();
        this.out = out;
        this.err = err;
        this.session = session;
        this.client = client;
        this.commandRunner = commandRunner;
        this.in = in;
        this.maxSteps = maxSteps;
        this.chatTrace = chatTrace;
        this.approvalMode = initialApprovalMode;
        this.user = new UserRole(in, out, () -> approvalMode == ApprovalMode.ALL, chatTrace.log(), chatTrace::clearStatus);
        this.llm = client == null ? null : new LlmRole(client, restoredHistory(session), recorder(session));
        if (client == null) {
            this.registrar = null;
            this.controller = null;
            return;
        }
        this.registrar = protection == null ? RegistrarRole.standard(this.root, commandRunner)
                : RegistrarRole.standard(this.root, commandRunner, protection::checkFileVersion, protection::isReadable,
                        knowledge == null ? null : knowledge.frozenCommand());
        if (knowledge != null) { knowledge.tools().forEach(registrar::register); }
        GuardRole guard = new GuardRole(this.root, path -> protection != null
                && (protection.isWriteProtected(path) || knowledge != null && knowledge.isProtected(path)),
                () -> approvalMode == ApprovalMode.FILES || approvalMode == ApprovalMode.PROJECT,
                () -> approvalMode == ApprovalMode.PROJECT);
        ExecutorRole executor = new ExecutorRole(this.root, knowledge == null ? ExecutorRole.DEFAULT_MAX_RESULT_CHARS : 32000, chatTrace.log());
        this.controller = new ControllerRole(llm, registrar, guard, executor, user, maxSteps, chatTrace.log(),
                recorder(session), () -> { if (protection != null) { protection.check(); } if (knowledge != null) { knowledge.check(); } });
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
                printExecutionError("[会话记录] 有 " + loaded.skippedLines() + " 行损坏记录已跳过（其余历史正常载入）");
            }
            return loaded.messages();
        } catch (IOException failed) {
            printExecutionError("[会话记录] 历史读取失败，本次以空历史启动：" + failed.getMessage());
            return List.of();
        }
    }

    /** 交互式模式：反复读取自然语言任务，直到 exit/quit 或输入结束。 */
    public int runRepl() {
        return runRepl(null, null, client, ConsoleStreams.hasInteractiveConsole());
    }

    int runRepl(Path skillControl, Path skillSource, LLMClient taskClient, boolean interactive) {
        if (client == null) { err.println("对话需要模型配置"); return 2; }
        currentTaskId = null;
        out.println("AI Coding Agent 已就绪：root=" + root
                + "，可用工具=" + String.join(", ", registrar.names()));
        if (session != null) {
            List<Message> history = llm.history();
            out.println(session.describe() + "，共恢复 " + (history.size() - 1) + " 条历史消息");
            if (session.resumed() && !session.sessionId().startsWith("task-")) {
                showLastChatTurn(history);
            }
        } else {
            out.println("本次运行不记录会话。");
        }
        out.println("直接输入自然语言任务，或输入 /help 查看聊天命令。");
        if (user.autoApprove()) {
            out.println("注意：已开启 --yes，写文件与执行命令将不再逐次确认。");
        } else if (approvalMode == ApprovalMode.FILES) {
            out.println("文件模式：自动批准项目内新建文件和带版本校验的精确编辑。");
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
                startNewChat();
                continue;
            }

            String[] command = task.split("\\s+", 2);
            String name = command[0];
            String argument = command.length == 2 ? command[1] : "";
            switch (name) {
                case "/help" -> {
                    if (!argument.isEmpty()) { out.println("用法: /help"); }
                    else { printReplHelp(); }
                }
                case "/approval" -> {
                    if (!interactive) { out.println(); }
                    chatApproval(argument);
                }
                case "/use-experience", "/use-skill" -> useChatKnowledge(name, argument, skillControl, skillSource, interactive);
                case "/learn" -> learnFromChat(argument, interactive, taskClient);
                case "/make-skill" -> makeSkill(argument, skillControl, skillSource, taskClient);
                case "/trace" -> {
                    if (!argument.isEmpty()) { out.println("用法: /trace"); }
                    else {
                        if (!interactive) { out.println(); }
                        chatTrace.show();
                    }
                }
                case "/task" -> chatTask(argument, interactive, taskClient);
                case "/skill" -> {
                    if (argument.isBlank()) { out.println("用法: /skill <修复目标>"); }
                    else {
                        currentTaskId = null;
                        runChatRequest(interactive, () -> startSkillTask(argument, null, skillControl, skillSource,
                                interactive, taskClient));
                    }
                }
                case "/extract-experience" -> {
                    String sourceTaskId = argument.isBlank() ? currentTaskId : argument;
                    if (sourceTaskId == null) {
                        out.println("本次聊天暂无当前任务。提炼管理任务的经验，请在 Maven 项目中用 /task、/skill 或 /resume-task，"
                                + "或输入 /extract-experience <任务ID>。普通聊天可用 /learn <验证说明>。");
                    } else if (sourceTaskId.chars().anyMatch(Character::isWhitespace)) {
                        out.println("用法: /extract-experience [任务ID]");
                    } else {
                        extractExperience(sourceTaskId, interactive, taskClient);
                    }
                }
                case "/tasks", "/skills", "/experiences", "/sessions", "/new" -> {
                    if (!argument.isEmpty()) { out.println("用法: " + name); }
                    else {
                        if (!interactive && !name.equals("/new")) { out.println(); }
                        switch (name) {
                            case "/tasks" -> listTasks();
                            case "/skills" -> skillVersions(skillControl, skillSource, null, true);
                            case "/experiences" -> listExperiences();
                            case "/sessions" -> listChatSessions();
                            case "/new" -> startNewChat();
                        }
                    }
                }
                case "/status", "/resume-task", "/skill-info", "/experience", "/share-experience", "/unshare-experience",
                        "/share-skill", "/unshare-skill", "/replan-task", "/abandon-task" -> {
                    if (argument.isBlank() || argument.chars().anyMatch(Character::isWhitespace)) {
                        String id = switch (name) {
                            case "/skill-info", "/share-skill", "/unshare-skill" -> "版本ID";
                            case "/experience", "/share-experience", "/unshare-experience" -> "经验ID";
                            default -> "任务ID";
                        };
                        out.println("用法: " + name + " <" + id + ">");
                    } else switch (name) {
                        case "/status" -> showTask(argument);
                        case "/resume-task" -> {
                            currentTaskId = null;
                            runChatRequest(interactive, () -> resumeManagedTask(argument, interactive, null, taskClient));
                        }
                        case "/skill-info" -> skillVersions(skillControl, skillSource, argument, true);
                        case "/share-skill", "/unshare-skill" -> setSkillSharing(skillControl, skillSource, argument, name.equals("/share-skill"));
                        case "/experience" -> showExperience(argument);
                        case "/share-experience" -> updateExperienceSharing(argument, true);
                        case "/unshare-experience" -> updateExperienceSharing(argument, false);
                        case "/replan-task" -> replanTask(argument, interactive);
                        case "/abandon-task" -> abandonTask(argument, interactive);
                    }
                }
                case "/switch" -> {
                    if (argument.isBlank()) { out.println("用法: /switch <会话ID>"); }
                    else { switchChat(argument); }
                }
                case "/export" -> exportChat(argument);
                case "/reagree-acceptance", "/retry-operation" -> {
                    String[] arguments = argument.split("\\s+", 2);
                    boolean retry = name.equals("/retry-operation");
                    if (arguments.length != 2 || retry && arguments[1].chars().anyMatch(Character::isWhitespace)) {
                        out.println("用法: " + name + " <任务ID> " + (retry ? "<操作ID>" : "<验收命令>"));
                    } else if (retry) { retryUncertainOperation(arguments[0], arguments[1], interactive); }
                    else { reagreeAcceptance(arguments[0], arguments[1], interactive); }
                }
                case "/task-budget" -> {
                    String[] arguments = argument.split("\\s+");
                    String usage = "用法: /task-budget <任务ID> <新总上限>";
                    if (arguments.length != 2) { out.println(usage); }
                    else {
                        try {
                            int maximum = Integer.parseInt(arguments[1]);
                            if (maximum <= 0) { out.println(usage); }
                            else { raiseTaskBudget(arguments[0], maximum); }
                        } catch (NumberFormatException invalid) { out.println(usage); }
                    }
                }
                default -> {
                    if (SkillCommands.isChatCommand(name)) {
                        try {
                            var options = SkillCommands.parseChat(name, argument, root);
                            if (!interactive && options.isList()) { out.println(); }
                            SkillCommands.run(options, taskClient, in, out, err, interactive);
                        } catch (IllegalArgumentException invalid) {
                            err.println("技能参数错误: " + invalid.getMessage());
                        }
                    } else {
                        runChatRequest(interactive, () -> printAnswer(controller.runTask(task)));
                    }
                }
            }
        }

        out.println("再见。");
        return 0;
    }

    private void runChatRequest(boolean interactive, Runnable request) {
        chatTrace.begin(interactive);
        try {
            request.run();
        } finally {
            chatTrace.finish();
        }
    }

    private void printAnswer(String answer) {
        user.say("Agent > " + answer);
    }

    /** 预览只读已恢复的消息，避免将回显写回会话。 */
    private void showLastChatTurn(List<Message> history) {
        String answer = null;
        for (int index = history.size() - 1; index >= 0; index--) {
            Message message = history.get(index);
            String content = message.content();
            if (Message.ROLE_USER.equals(message.role())) {
                if (content != null && !content.isBlank()) {
                    out.println("最近一轮对话");
                    user.say("用户 > " + content);
                    if (answer != null) { printAnswer(answer); }
                }
                return;
            }
            if (answer == null && Message.ROLE_ASSISTANT.equals(message.role())
                    && (message.toolCalls() == null || message.toolCalls().isEmpty())
                    && content != null && !content.isBlank()) {
                answer = content;
            }
        }
    }

    private void printExecutionError(String message) {
        chatTrace.clearStatus();
        err.println(message);
    }

    private void chatApproval(String argument) {
        switch (argument) {
            case "" -> { }
            case "files" -> approvalMode = ApprovalMode.FILES;
            case "project" -> approvalMode = ApprovalMode.PROJECT;
            case "manual" -> approvalMode = ApprovalMode.MANUAL;
            default -> {
                out.println("用法: /approval [files|project|manual]");
                return;
            }
        }
        String status = switch (approvalMode) {
            case MANUAL -> "manual（写入和命令需要人工确认）";
            case FILES -> "files（新建文件和精确编辑自动批准）";
            case PROJECT -> "project（自动批准新建文件、精确编辑和支持的编译、测试、运行命令。仅用于可信项目）";
            case ALL -> "all（启动时使用了 --yes）";
        };
        out.println("当前审批模式: " + status);
    }

    private void chatTask(String argument, boolean interactive, LLMClient taskClient) {
        String[] parts = argument.split("\\s+", 2);
        boolean chatOnly = parts[0].equals("--chat");
        String goal = chatOnly ? (parts.length == 2 ? parts[1] : "") : argument;
        if (goal.isBlank()) {
            out.println("用法: /task [--chat] <需求>");
            return;
        }
        currentTaskId = null;
        if (chatOnly || !Files.isRegularFile(root.resolve("pom.xml"))) {
            runChatRequest(interactive, () -> printAnswer(controller.runTask(goal)));
        } else {
            runChatRequest(interactive, () -> startManagedTask(goal, null, interactive, KnowledgeMode.EXPERIENCE,
                    null, taskClient));
        }
    }

    private void useChatKnowledge(String command, String argument, Path control, Path source, boolean interactive) {
        boolean skill = command.equals("/use-skill");
        String[] parts = argument.split("\\s+", 2);
        if (parts.length != 2 || parts[1].isBlank()) {
            out.println("用法: " + command + (skill ? " <版本ID> <需求>" : " <经验ID> <需求>") + "（支持唯一前缀）");
            return;
        }
        try {
            var reference = skill ? com.agent.skill.ChatKnowledge.skill(root, control, source, out, parts[0])
                    : com.agent.skill.ChatKnowledge.experience(root, out, parts[0]);
            if (!interactive) { out.println(); }
            String label = reference.learned() ? (skill ? "普通聊天技能（用户确认）: " : "普通聊天经验（用户确认）: ")
                    : (skill ? "技能版本: " : "经验: ");
            out.println("本轮参考" + label + reference.id());
            runChatRequest(interactive, () -> printAnswer(controller.runTaskWithAdvice(parts[1], reference.advice())));
        } catch (IOException invalid) {
            printExecutionError("本轮参考知识不可用: " + invalid.getMessage());
        }
    }

    private void learnFromChat(String result, boolean interactive, LLMClient model) {
        if (result.isBlank()) { out.println("用法: /learn <验证说明>"); return; }
        if (!interactive) { out.println("请在交互终端中使用 /learn，以便核对并确认保存。"); return; }
        if (session != null && session.sessionId().startsWith("task-")) {
            out.println("请在普通聊天中用 /learn，管理任务可用 /extract-experience <任务ID>。");
            return;
        }
        try {
            new ChatLearning(root).learn(session == null ? null : session.sessionId(), llm.history(), result, model, in, out);
        } catch (IOException failed) {
            printExecutionError("普通聊天提炼失败: " + failed.getMessage());
        }
    }

    private void makeSkill(String argument, Path control, Path source, LLMClient model) {
        if (argument.isBlank()) {
            out.println("用法: /make-skill <经验ID[,经验ID…]>（支持短 ID，生成候选最多使用 1 次模型请求）");
            return;
        }
        try {
            var references = SkillCommands.identifiers(argument);
            new com.agent.skill.SkillCandidates(root, out, control, source).generateInNewBatch(references, model);
        } catch (IOException | IllegalArgumentException invalid) {
            err.println("技能制作失败: " + invalid.getMessage());
        }
    }

    private void printReplHelp() {
        out.println("""
                聊天命令
                  /help                         查看此列表
                  /approval [files|project|manual] 查看模式，切换文件、可信项目或人工审批
                  /use-experience <经验ID> <需求> 用指定经验处理本轮聊天，ID 可用唯一前缀
                  /use-skill <ID> <需求>           用指定技能处理本轮聊天，ID 可用唯一前缀
                  /learn <验证说明>               从普通聊天一次生成经验和技能，核对后保存
                  /make-skill <经验ID[,经验ID…]> 从已确认经验生成待审核候选，最多 1 次模型请求
                  /trace                        展开最近一次执行过程
                  /task [--chat] <需求>          无 pom.xml 或指定 --chat 时走普通聊天，否则走 Maven 管理修复
                  /skill <修复目标>             发起技能修复任务
                  /tasks                        查看当前项目的任务
                  /status <任务ID>               查看任务详情和验收状态
                  /task-budget <任务ID> <新总上限> 提高暂停任务的累计请求上限
                  /resume-task <任务ID>          继续原任务
                  /replan-task <任务ID>          审阅外部文件变化并重新规划
                  /reagree-acceptance <任务ID> <验收命令> 重新约定验收
                  /retry-operation <任务ID> <操作ID> 处置结果不明的操作
                  /abandon-task <任务ID>         确认放弃任务
                  /skills                       查看本项目及共享的技能，包括用户确认的普通聊天技能
                  /skill-info <版本ID>           查看指定版本的技能指南和批准记录
                  /share-skill <版本ID>          共享本项目的一项技能版本
                  /unshare-skill <版本ID>        在原项目取消共享技能版本
                  /extract-experience [任务ID]   提炼经验，省略 ID 时使用当前任务
                  /experiences                  列出本项目及共享经验
                  /experience <经验ID>           查看经验详情及来源
                  /share-experience <经验ID>     共享本项目的一条经验
                  /unshare-experience <经验ID>   在原项目取消共享
                  /sessions                     列出会话
                  /switch <会话ID>               切换到已有普通聊天
                  /export [会话ID]               导出 Markdown，省略 ID 时导出当前会话
                  /new                          开始新聊天
                  clear                         清空聊天上下文并开始新会话
                  exit / quit                   退出聊天

                技能发布命令沿用命令行参数，例如 /start-skill-batch --request-limit 13
                  /start-skill-batch /generate-skill /skill-batches /skill-batch /skill-candidates /skill-candidate
                  /trial-skill /resume-skill-trial /skill-trials /skill-trial
                  /skill-review /attest-skill-trial /approve-skill /reject-skill
                  /skill-versions /skill-version /disable-skill /enable-skill
                  /skill-feedbacks /register-skill-feedback /revise-from-feedback
                独立试用用 --root 指向冻结工作区，聊天留在另一个目录。
                """);
    }

    private void startNewChat() {
        try {
            String id = session == null ? null : session.startNewSession();
            currentTaskId = null;
            llm.reset();
            chatTrace.reset();
            out.println(id == null ? "对话历史已清空。" : "对话历史已清空，并开始新会话 " + id + "（旧记录已保留）。");
        } catch (IOException failed) {
            err.println("新会话创建失败: " + failed.getMessage());
        }
    }

    private void listChatSessions() {
        if (session == null) { out.println("当前未启用会话记录。"); return; }
        try {
            var list = new ConsoleList(out, true);
            for (String id : SessionStore.listSessions(session.dir())) {
                list.heading(id);
                if (id.equals(session.sessionId())) { list.detail("状态: 当前"); }
                if (id.startsWith("task-")) { list.detail("类型: 任务记录"); }
            }
        } catch (IOException failed) {
            err.println("会话列表读取失败: " + failed.getMessage());
        }
    }

    private void switchChat(String id) {
        if (session == null) { out.println("当前未启用会话记录。"); return; }
        try {
            var history = session.switchTo(id);
            llm.restoreHistory(history.messages());
            currentTaskId = null;
            chatTrace.reset();
            if (history.skippedLines() > 0) { err.println("[会话记录] 已跳过 " + history.skippedLines() + " 行损坏记录"); }
            out.println("已切换到会话 " + session.sessionId() + "，恢复 " + history.messages().size() + " 条消息。");
            showLastChatTurn(history.messages());
        } catch (IOException failed) {
            err.println("会话切换失败: " + failed.getMessage());
        }
    }

    private void exportChat(String argument) {
        if (session == null) { out.println("当前未启用会话记录。"); return; }
        try {
            String id = argument.isBlank() ? session.sessionId() : argument;
            String markdown = SessionExporter.toMarkdownForSession(session.dir(), id);
            Path destination = session.dir().resolve("export-" + SessionPaths.timestampName() + "-" + UUID.randomUUID() + ".md");
            Files.writeString(destination, markdown, StandardOpenOption.CREATE_NEW);
            out.println("会话已导出 " + destination.toAbsolutePath().toString().replace('\\', '/'));
        } catch (IOException failed) {
            err.println("会话导出失败: " + failed.getMessage());
        }
    }

    /** 单任务模式：跑完一个任务后退出，供脚本化调用（配合 --yes）。 */
    public int runTask(String task) {
        if (client == null) { err.println("对话需要模型配置"); return 2; }
        printAnswer(controller.runTask(task));
        return 0;
    }

    /** 显式任务和普通对话分流，查询不需要模型配置。 */
    public int startManagedTask(String goal, String acceptanceCommand) {
        return startManagedTask(goal, acceptanceCommand, ConsoleStreams.hasInteractiveConsole());
    }

    public int startManagedTask(String goal, String acceptanceCommand, boolean interactive) {
        return startManagedTask(goal, acceptanceCommand, interactive, true);
    }

    public int startManagedTask(String goal, String acceptanceCommand, boolean interactive, boolean experiencesEnabled) {
        return startManagedTask(goal, acceptanceCommand, interactive,
                experiencesEnabled ? KnowledgeMode.EXPERIENCE : KnowledgeMode.NONE);
    }

    public int startManagedTask(String goal, String acceptanceCommand, boolean interactive, KnowledgeMode knowledgeMode) {
        if (knowledgeMode == KnowledgeMode.SKILL) { return startSkillTask(goal, acceptanceCommand, null, null, interactive); }
        return startManagedTask(goal, acceptanceCommand, interactive, knowledgeMode, null);
    }
    public int startSkillTask(String goal, String command, Path control, Path source, boolean interactive) {
        return startSkillTask(goal, command, control, source, interactive, client);
    }
    private int startSkillTask(String goal, String command, Path control, Path source, boolean interactive, LLMClient taskClient) {
        try {
            var knowledge = new com.agent.skill.PublishedSkills(root, chatTrace.log(), control, source);
            return startManagedTask(goal, command, interactive, KnowledgeMode.SKILL, knowledge, taskClient);
        }
        catch (IOException failed) { printExecutionError("技能任务配置失败: " + failed.getMessage()); return 4; }
    }

    public int startManagedTask(String goal, String acceptanceCommand, boolean interactive, KnowledgeMode knowledgeMode,
                                com.agent.skill.SkillKnowledge knowledge) {
        return startManagedTask(goal, acceptanceCommand, interactive, knowledgeMode, knowledge, client);
    }

    private int startManagedTask(String goal, String acceptanceCommand, boolean interactive, KnowledgeMode knowledgeMode,
                                 com.agent.skill.SkillKnowledge knowledge, LLMClient taskClient) {
        return startManagedTask(goal, acceptanceCommand, interactive, knowledgeMode, knowledge, taskClient, true);
    }

    /** 冻结对照沿用原协议的项目经验范围，避免读取运行者另外共享的经验。 */
    public int startFrozenExperienceTask(String goal, String acceptanceCommand, boolean experiencesEnabled) {
        return startManagedTask(goal, acceptanceCommand, false,
                experiencesEnabled ? KnowledgeMode.EXPERIENCE : KnowledgeMode.NONE, null, client, false);
    }

    private int startManagedTask(String goal, String acceptanceCommand, boolean interactive, KnowledgeMode knowledgeMode,
                                 com.agent.skill.SkillKnowledge knowledge, LLMClient taskClient, boolean includeSharedExperiences) {
        if (knowledgeMode == null) { printExecutionError("任务需要明确的知识模式"); return 2; }
        if (taskClient == null) { printExecutionError("任务需要模型配置"); return 2; }
        try {
            LLMClient managedClient = knowledge == null ? taskClient : knowledge.audit(taskClient);
            TaskRecord task = new TaskCoordinator(root, commandRunner, (managedSession, protection, managedRunner) ->
                    new CodingAgentCli(root, managedClient, approvalMode, maxSteps, in, out, err,
                            managedSession, managedRunner, protection, knowledge, chatTrace).controller,
                    chatTrace.log(), knowledge, includeSharedExperiences, this::printAnswer)
                    .start(goal, acceptanceCommand == null ? "mvn test" : acceptanceCommand, maxSteps, interactive, knowledgeMode);
            currentTaskId = task.id;
            return printTask(task) == TaskRecord.State.Verified ? 0 : 1;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt(); printExecutionError("任务验收被中断"); return 4;
        } catch (IOException | java.io.UncheckedIOException failed) {
            printExecutionError("任务执行或持久化失败: " + failed.getMessage()); return 4;
        }
    }

    public int listTasks() {
        try {
            List<TaskRecord> tasks = new TaskStore(root).list();
            if (tasks.isEmpty()) { out.println("暂无任务。"); }
            var list = new ConsoleList(out, true);
            for (TaskRecord task : tasks) { printTask(task, list); }
            return 0;
        } catch (IOException failed) { err.println("任务查询失败: " + failed.getMessage()); return 4; }
    }

    public int startSkillTrial(com.agent.skill.SkillTrials.Config config) {
        try { return new com.agent.skill.SkillTrials(root, out).start(config, client, commandRunner); }
        catch (IOException | java.io.UncheckedIOException failed) { err.println("技能试用失败: " + failed.getMessage()); return 4; }
    }
    public int resumeSkillTrial(Path control, String id, int maximum, String note, String model, String endpoint) {
        try { return new com.agent.skill.SkillTrials(root, out).resume(control, id, maximum, note, model, endpoint, client, commandRunner); }
        catch (IOException | java.io.UncheckedIOException failed) { err.println("试用恢复失败: " + failed.getMessage()); return 4; }
    }
    public int showSkillTrial(Path control, String id) {
        try { new com.agent.skill.SkillTrials(root, out).show(control, id); return 0; }
        catch (IOException failed) { err.println("试用查询失败: " + failed.getMessage()); return 4; }
    }
    public int listSkillTrials(Path control) {
        try { new com.agent.skill.SkillTrials(root, out).list(control); return 0; }
        catch (IOException failed) { err.println("试用查询失败: " + failed.getMessage()); return 4; }
    }
    public int reviewSkill(com.agent.skill.SkillReviews.Selection selection) {
        try { new com.agent.skill.SkillReviews(root, out).review(selection); return 0; }
        catch (IOException failed) { err.println("技能审核失败: " + failed.getMessage()); return 4; }
    }
    public int approveSkill(com.agent.skill.SkillReviews.Selection selection, boolean interactive) {
        try { return new com.agent.skill.SkillReviews(root, out).approve(selection, in, interactive) ? 0 : 1; }
        catch (IOException failed) { err.println("技能发布失败: " + failed.getMessage()); return 4; }
    }
    public int attestSkillTrial(Path control, String id, String note, boolean interactive) {
        try { return new com.agent.skill.SkillReviews(root, out).attest(control, id, note, in, interactive) ? 0 : 1; }
        catch (IOException failed) { err.println("反例人工核验失败: " + failed.getMessage()); return 4; }
    }
    public int rejectSkill(Path control, Path source, String id, String sha256, String reason, boolean interactive) {
        try { return new com.agent.skill.SkillReviews(root, out).reject(control, source, id, sha256, reason, in, interactive) ? 0 : 1; }
        catch (IOException failed) { err.println("技能拒绝失败: " + failed.getMessage()); return 4; }
    }
    public int showSkillVersion(Path control, Path source, String id) { return skillVersions(control, source, id); }
    public int setSkillEnabled(Path control, Path source, String id, String sha256, boolean enabled, String reason, boolean interactive) {
        try { return new com.agent.skill.SkillReviews(root, out).setEnabled(control, source, id, sha256, enabled, reason, in, interactive) ? 0 : 1; }
        catch (IOException failure) { err.println("技能可用性变更失败: " + failure.getMessage()); return 4; }
    }
    public int listSkillVersions(Path control, Path source) { return skillVersions(control, source, null); }
    public int listSkillFeedback(Path control, Path source) {
        try { new com.agent.skill.SkillFeedback(root, control, source).list(out); return 0; }
        catch (IOException failure) { err.println("技能反馈查询失败: " + failure.getMessage()); return 4; }
    }
    public int registerSkillFeedback() {
        try { com.agent.skill.SkillFeedback.registerExisting(root); out.println("已补齐合格来源与任务反馈，未调用模型或提炼经验"); return 0; }
        catch (IOException failure) { err.println("反馈登记失败: " + failure.getMessage()); return 4; }
    }
    public int startSkillEvaluation(com.agent.skill.SkillEvaluation.Config config, com.agent.skill.SkillEvaluation.Models models) {
        try {
            var result = new com.agent.skill.SkillEvaluation(root, out).execute(config, models, commandRunner);
            return result.cells.stream().allMatch(cell -> "Verified".equals(cell.state)) ? 0 : 1;
        } catch (IOException | IllegalArgumentException failed) { err.println("技能对照失败: " + failed.getMessage()); return 4; }
    }
    public int showSkillEvaluation(Path archive) {
        try { com.agent.skill.SkillEvaluation.print(com.agent.skill.SkillEvaluation.read(archive), out); return 0; }
        catch (IOException | IllegalArgumentException failed) { err.println("技能对照报告不可用: " + failed.getMessage()); return 4; }
    }
    public int reviseSkillFromFeedback(Path control, Path source, String batch, String parent, String reason, com.agent.skill.SkillFeedback.Selection selection) {
        try { return new com.agent.skill.SkillCandidates(root, out, control, source).revise(batch, parent, reason, selection, client) ? 0 : 1; }
        catch (IOException failure) { err.println("反馈修订失败: " + failure.getMessage()); return 4; }
    }
    private int skillVersions(Path control, Path source, String id) {
        return skillVersions(control, source, id, false);
    }
    private int skillVersions(Path control, Path source, String id, boolean includeShared) {
        try { new com.agent.skill.SkillReviews(root, out).versions(control, source, id, includeShared); return 0; }
        catch (IOException failed) { err.println("技能版本查询失败: " + failed.getMessage()); return 4; }
    }
    private void setSkillSharing(Path control, Path source, String id, boolean share) {
        try { new com.agent.skill.SkillReviews(root, out).setShared(control, source, id, share); }
        catch (IOException failed) { err.println("技能共享操作失败: " + failed.getMessage()); }
    }
    public int startSkillBatch(int limit) { return skillOperation(skills -> skills.startBatch(limit)); }
    public int startSkillBatch(Path control, Path source, int limit) {
        return skillOperation(control, source, skills -> skills.startBatch(limit));
    }
    public int generateSkillCandidate(String batch, java.util.List<String> sources, String parent, String reason) {
        return generateSkillCandidate(null, null, batch, sources, parent, reason);
    }
    public int generateSkillCandidate(Path control, Path source, String batch, java.util.List<String> sources, String parent, String reason) {
        try { return new com.agent.skill.SkillCandidates(root, out, control, source).generate(batch, sources, parent, reason, client) ? 0 : 1; }
        catch (IOException failed) { err.println("技能生成失败: " + failed.getMessage()); return 4; }
    }
    public int listSkillCandidates() { return skillOperation(com.agent.skill.SkillCandidates::listCandidates); }
    public int showSkillCandidate(String id) { return skillOperation(skills -> skills.showCandidate(id)); }
    public int listSkillBatches() { return skillOperation(com.agent.skill.SkillCandidates::listBatches); }
    public int showSkillBatch(String id) { return skillOperation(skills -> skills.showBatch(id)); }
    public int listSkillCandidates(Path control, Path source) { return skillOperation(control, source, com.agent.skill.SkillCandidates::listCandidates); }
    public int showSkillCandidate(Path control, Path source, String id) { return skillOperation(control, source, skills -> skills.showCandidate(id)); }
    public int listSkillBatches(Path control, Path source) { return skillOperation(control, source, com.agent.skill.SkillCandidates::listBatches); }
    public int showSkillBatch(Path control, Path source, String id) { return skillOperation(control, source, skills -> skills.showBatch(id)); }
    @FunctionalInterface private interface SkillOperation {
        void run(com.agent.skill.SkillCandidates skills) throws IOException;
    }
    private int skillOperation(SkillOperation operation) {
        return skillOperation(null, null, operation);
    }
    private int skillOperation(Path control, Path source, SkillOperation operation) {
        try { operation.run(new com.agent.skill.SkillCandidates(root, out, control, source)); return 0; }
        catch (IOException failed) { err.println("技能操作失败: " + failed.getMessage()); return 4; }
    }

    public int extractExperience(String id, boolean interactive) {
        return extractExperience(id, interactive, client);
    }

    private int extractExperience(String id, boolean interactive, LLMClient taskClient) {
        try {
            return new com.agent.experience.ExperienceLibrary(root).extract(id, taskClient, in, out, interactive) ? 0 : 1;
        } catch (IOException failed) { err.println("经验提炼失败: " + failed.getMessage()); return 4; }
    }

    public int listExperiences() {
        try {
            var library = new com.agent.experience.ExperienceLibrary(root);
            var experiences = library.visible(out);
            var learned = new ChatLearning(root).local("");
            if (experiences.isEmpty() && learned.isEmpty()) { out.println("暂无经验。"); }
            library.printList(experiences, out);
            var list = new ConsoleList(out, true);
            for (var entry : learned) { ChatLearning.printExperience(list, entry); }
            return 0;
        } catch (IOException failed) { err.println("经验查询失败: " + failed.getMessage()); return 4; }
    }

    public int showExperience(String id) {
        try {
            var learned = new ChatLearning(root).local(id.toLowerCase(java.util.Locale.ROOT));
            if (learned.size() > 1) { throw new IOException("ID 前缀匹配多个条目，请输入更多位"); }
            if (!learned.isEmpty()) {
                ChatLearning.printExperience(new ConsoleList(out, false), learned.get(0));
                return 0;
            }
            var library = new com.agent.experience.ExperienceLibrary(root);
            library.print(library.readVisible(id), out);
            return 0;
        } catch (IOException failed) { err.println("经验查询失败: " + failed.getMessage()); return 4; }
    }

    public int updateExperienceSharing(String id, boolean share) {
        try {
            var library = new com.agent.experience.ExperienceLibrary(root);
            if (share) {
                library.share(id);
                out.println("已共享经验: " + id);
            } else {
                boolean removed = library.unshare(id);
                out.println((removed ? "已取消共享: " : "经验未共享: ") + id);
            }
            return 0;
        } catch (IOException failed) { err.println("经验共享操作失败: " + failed.getMessage()); return 4; }
    }

    public int disableExperience(String id) {
        try {
            var library = new com.agent.experience.ExperienceLibrary(root);
            library.print(library.disable(id), out);
            return 0;
        } catch (IOException failed) { err.println("经验禁用失败: " + failed.getMessage()); return 4; }
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

    /** 普通工具审批采用本次运行当前的模式。 */
    public int resumeManagedTask(String id, boolean interactive) {
        return resumeManagedTask(id, interactive, null);
    }

    /** 受控试用复用可靠检查点和原加载审计，调用方不能用普通知识库替换试用快照。 */
    public int resumeManagedTask(String id, boolean interactive, com.agent.skill.SkillKnowledge trialKnowledge) {
        return resumeManagedTask(id, interactive, trialKnowledge, client);
    }

    private int resumeManagedTask(String id, boolean interactive, com.agent.skill.SkillKnowledge trialKnowledge, LLMClient taskClient) {
        if (taskClient == null) { printExecutionError("任务需要模型配置"); return 2; }
        try {
            var saved = new TaskStore(root).read(id);
            com.agent.skill.SkillKnowledge knowledge = trialKnowledge != null ? trialKnowledge : saved.knowledgeMode == KnowledgeMode.SKILL
                    ? com.agent.skill.PublishedSkills.restore(root, chatTrace.log(), saved.skills) : null;
            LLMClient managedClient = knowledge == null ? taskClient : knowledge.audit(taskClient);
            var coordinator = new TaskCoordinator(root, commandRunner, (managedSession, protection, managedRunner) ->
                    new CodingAgentCli(root, managedClient, approvalMode, maxSteps, in, out, err,
                            managedSession, managedRunner, protection, knowledge, chatTrace).controller,
                    chatTrace.log(), knowledge, true, this::printAnswer);
            TaskRecord task = trialKnowledge == null ? coordinator.resume(id, interactive) : coordinator.resumeTrial(id, interactive);
            currentTaskId = task.id;
            return printTask(task) == TaskRecord.State.Verified ? 0 : 1;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt(); printExecutionError("任务恢复被中断"); return 4;
        } catch (IOException | java.io.UncheckedIOException failed) {
            printExecutionError("任务恢复或持久化失败: " + failed.getMessage()); return 4;
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
        return printTask(task, new ConsoleList(out, false));
    }

    private TaskRecord.State printTask(TaskRecord task, ConsoleList output) throws IOException {
        TaskRecord.State state = task.state;
        String reason = task.reason;
        TaskStore store = new TaskStore(root);
        if (state == TaskRecord.State.Verified && !store.isCurrentVerified(task)) {
            state = TaskRecord.State.Paused;
            reason = "项目输入版本已变化，原验收失效；请新建任务";
        }
        if (chatTrace.active()) {
            String acceptance = "暂无最终验收结果";
            if (!task.checks.isEmpty()) {
                var evidence = store.readEvidence(task, task.checks.get(task.checks.size() - 1));
                if ("final".equals(evidence.kind()) && evidence.acceptanceVersion().equals(task.acceptanceVersion)) {
                    if (state == TaskRecord.State.Verified) {
                        acceptance = "独立验收 " + evidence.tests() + "/" + evidence.tests() + " 通过";
                    } else if (!"PASSED".equals(evidence.verdict())) {
                        acceptance = "最近一次独立验收未通过";
                    }
                }
            }
            String summary = "任务: " + task.id + " · " + state + " · " + acceptance;
            if (state == TaskRecord.State.Paused && reason != null && !reason.isBlank()) {
                summary += " · 暂停原因：" + reason.replaceAll("\\R", " ");
            }
            user.say(summary);
            return state;
        }
        output.heading("任务: " + task.id);
        output.detail("状态: " + state + "\n目标: " + task.goal
                + "\n会话: " + task.sessionId + "\n验收: " + task.command
                + "\n知识模式: " + task.knowledgeMode
                + "\n验收版本: " + task.acceptanceVersion + "\n原因: " + reason);
        var progress = task.progress;
        if (task.skills != null) {
            output.detail("技能库: " + (task.skills.source == null ? "本地项目" : task.skills.source.replace('\\', '/'))
                    + "\n技能审计起点: 第 " + task.skills.firstRequest + " 次请求");
            output.detail("固定技能: " + task.skills.loaded);
            if (task.skills.loaded.isEmpty()) { output.detail("技能使用: 未加载"); }
            for (var pin : task.skills.loaded) {
                String declaration = task.skills.events.stream().filter(e -> e.type().equals("DECLARATION") && pin.skillId().equals(e.skillId()) && pin.versionId().equals(e.versionId()))
                        .map(com.agent.skill.SkillTask.Event::detail).reduce((a, b) -> b).orElse("未声明");
                output.detail("技能声明 " + pin.skillId() + "@" + pin.versionId() + ": " + declaration);
                String sharedProject = task.skills.sharedProject(pin.versionId());
                if (sharedProject != null) { output.detail("技能共享来源: " + sharedProject); }
            }
            for (var event : task.skills.events) { output.detail("技能事件: " + event); }
            for (var decision : progress.decisions) {
                if (decision.skillRequest != null) { output.detail("最终请求审计 " + decision.number + ": " + new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(decision.skillRequest)); }
            }
        }
        output.detail("模型请求: " + progress.requests + "/" + progress.maxRequests
                + "\n工具调用: " + progress.tools + "\ntoken: 输入=" + progress.promptTokens
                + "，输出=" + progress.completionTokens + "，未知请求=" + progress.unknownRequests
                + "\n累计耗时: " + progress.elapsedMillis + " ms");
        for (var decision : progress.decisions) {
            output.detail("决策 " + decision.number + ": " + decision.status + "，验收版本="
                    + decision.acceptanceVersion + "，失败来源=" + decision.failureSource);
            for (var operation : decision.operations) {
                output.detail("工具 " + operation.callId + ": " + operation.tool + " " + operation.status
                        + "，修改=" + operation.changes.keySet()
                        + "，操作ID: " + operation.reference(decision.number)
                        + "，已记录进程=" + operation.processes
                        + (operation.status == TaskRecord.OperationState.RUNNING && !"READ".equals(operation.risk) ? "，结果不明" : ""));
            }
        }
        for (var resolution : task.resolutions) {
            output.detail("人工处置: " + resolution.action + "，目标=" + resolution.target + "，新操作="
                    + resolution.replacementId + "，时间=" + resolution.at
                    + "，旧进程停止确认=" + resolution.stoppedConfirmed
                    + "，取消旧操作=" + resolution.cancelledOperations
                    + (resolution.originalOperation == null ? "" : "，原操作结果不明"));
        }
        if (task.checkpoint.acceptancePending) {
            output.detail("验收结果不明，操作ID: " + com.agent.task.TaskResolution.acceptanceReference(task)
                    + "，已记录进程=" + task.checkpoint.acceptanceProcesses);
        }
        for (var change : progress.budgetChanges) {
            output.detail("预算调整: " + change.previous() + " -> " + change.current() + "，时间=" + change.at());
        }
        for (var error : progress.errors.entrySet()) {
            output.detail("核心错误轮次: " + error.getValue() + "，错误=" + progress.errorDescriptions.get(error.getKey()));
        }
        for (TaskRecord.EvidenceRef ref : task.checks) {
            TaskAcceptance.Evidence evidence = store.readEvidence(task, ref);
            output.detail(evidence.kind() + ": " + evidence.verdict() + "，测试数=" + evidence.tests()
                    + "，原因=" + evidence.reason());
        }
        for (TaskRecord.Agreement agreement : task.agreementHistory) {
            output.detail("历史约定: " + agreement.version() + "，仅用于审计，不是当前验收证据");
        }
        return state;
    }
}
