package com.agent;

import com.agent.llm.FakeLLMClient;
import com.agent.llm.LLMResponse;
import com.agent.llm.Message;
import com.agent.llm.ToolCall;
import com.agent.command.ProcessCommandRunner;
import com.agent.session.SessionPaths;
import com.agent.session.SessionStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 端到端：CLI 与会话存储的协作（写入、恢复、clear 开新会话、单任务）。 */
class CodingAgentCliSessionTest {

    @TempDir
    Path root;

    private PrintStream silent() {
        return new PrintStream(OutputStream.nullOutputStream(), true, StandardCharsets.UTF_8);
    }

    private CodingAgentCli cli(SessionStore store, FakeLLMClient client, String input) {
        return new CodingAgentCli(root, client, true, 4,
                new BufferedReader(new StringReader(input)),
                silent(), silent(), store);
    }

    @Test
    void shouldWriteConversationToSessionFile() throws Exception {
        SessionStore store = SessionStore.start(SessionPaths.defaultDir(root),
                SessionStore.Mode.NEW, "cli");
        FakeLLMClient fake = new FakeLLMClient(List.of(new LLMResponse("你好，我是 Agent。", null, 5, 5)));

        cli(store, fake, "你好\nexit\n").runRepl();

        List<String> contents = SessionStore.load(SessionPaths.defaultDir(root), "cli")
                .messages().stream().map(com.agent.llm.Message::content).toList();
        assertEquals(List.of("你好", "你好，我是 Agent。"), contents);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void shouldUseTaskInOrdinaryChat(boolean mavenProject) throws Exception {
        if (mavenProject) { Files.writeString(root.resolve("pom.xml"), "<project/>"); }
        var session = SessionStore.start(SessionPaths.defaultDir(root), SessionStore.Mode.NEW, "chat-task");
        var create = new ToolCall("create", new ToolCall.Function("write_file",
                "{\"path\":\"hello.py\",\"content\":\"print('hello')\"}"));
        var model = new FakeLLMClient(List.of(new LLMResponse(null, List.of(create), 1, 1),
                new LLMResponse("已创建", null, 1, 1), new LLMResponse("可以运行", null, 1, 1)));
        String goal = "创建 hello.py";
        String command = mavenProject ? "/task --chat " : "/task ";
        var input = new BufferedReader(new StringReader("/approval files\n" + command + goal
                + "\n如何运行它？\nexit\n"));
        var cli = new CodingAgentCli(root, model, false, 4, input, silent(), silent(), session,
                (dir, cmd, timeout) -> { throw new AssertionError("普通聊天不应运行 Maven 验收"); });

        assertEquals(0, cli.runRepl(null, null, new FakeLLMClient(List.of()), false));

        assertEquals("print('hello')", Files.readString(root.resolve("hello.py")));
        assertFalse(Files.exists(root.resolve(".agent/tasks")));
        var expected = List.of(goal, "如何运行它？");
        assertEquals(expected, model.requests().get(2).stream().filter(message -> message.role().equals("user"))
                .map(Message::content).toList());
        assertEquals(expected, session.loadMessages().messages().stream().filter(message -> message.role().equals("user"))
                .map(Message::content).toList());
    }

    @Test
    void shouldHandleQueriesAndUsageErrorsInChatWithoutCallingModel(@TempDir Path isolatedHome) {
        var output = new ByteArrayOutputStream();
        var stream = new PrintStream(output, true, StandardCharsets.UTF_8);
        var model = new FakeLLMClient(List.of());
        var input = new BufferedReader(new StringReader(
                "/help\n/help extra\n/approval\n/trace\n/trace extra\n/tasks\n/tasks extra\n/status\n/resume-task a b\n/skills\n/skill-info missing\n"
                        + "/task\n/task --chat\n/use-experience\n/use-skill missing-goal\n"
                        + "/task-budget\n/task-budget task 0\n/task-budget task 2147483648\n/task-budget task 4 extra\n"
                        + "/experiences\n/experience\n/switch\n/retry-operation task op extra\n/reagree-acceptance task\nexit\n"));
        var cli = new CodingAgentCli(root, model, false, 4, input, stream, stream);

        String oldHome = System.getProperty("user.home");
        System.setProperty("user.home", isolatedHome.toString());
        try { assertEquals(0, cli.runRepl()); }
        finally { System.setProperty("user.home", oldHome); }
        assertEquals(0, model.callCount());
        String text = output.toString(StandardCharsets.UTF_8);
        for (String expected : List.of("当前审批模式: manual", "当前聊天暂无执行过程", "用法: /trace", "暂无任务", "用法: /tasks", "用法: /status <任务ID>", "用法: /resume-task <任务ID>",
                "用法: /use-experience <经验ID> <需求>", "用法: /use-skill <版本ID> <需求>",
                "暂无已发布技能版本", "技能版本查询失败", "暂无经验", "用法: /experience <经验ID>", "用法: /switch <会话ID>",
                "用法: /retry-operation <任务ID> <操作ID>", "用法: /reagree-acceptance <任务ID> <验收命令>", "再见")) {
            assertTrue(text.contains(expected), text);
        }
        assertEquals(4, text.lines().filter(line -> line.endsWith("用法: /task-budget <任务ID> <新总上限>")).count(), text);
        assertEquals(2, text.lines().filter(line -> line.endsWith("用法: /task [--chat] <需求>")).count(), text);
        assertTrue(text.contains("用法: /help"), text);
        for (String command : List.of("/help", "/approval [files|project|manual]", "/trace", "/skill <修复目标>", "/tasks", "/status <任务ID>", "/resume-task <任务ID>",
                "/use-experience <经验ID> <需求>", "/use-skill <ID> <需求>", "/learn <验证说明>",
                "/task-budget <任务ID> <新总上限>", "/skills", "/skill-info <版本ID>", "/extract-experience [任务ID]",
                "/experiences", "/experience <经验ID>", "/task [--chat] <需求>", "/sessions", "/switch <会话ID>",
                "/export [会话ID]", "/new", "/replan-task <任务ID>", "/reagree-acceptance <任务ID> <验收命令>",
                "/retry-operation <任务ID> <操作ID>", "/abandon-task <任务ID>", "clear", "exit / quit")) {
            assertTrue(text.lines().anyMatch(line -> line.startsWith("  " + command + " ")), text);
        }
    }

    @Test
    void shouldShowManualPreviewAndKeepRoleLogsBehindTrace() throws Exception {
        Path file = Files.writeString(root.resolve("example.txt"), "before\n");
        String version = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)));
        String args = """
                {"path":"example.txt","old_text":"before","new_text":"after","expected_version":"%s"}
                """.formatted(version);
        var edit = new ToolCall("edit", new ToolCall.Function("replace_text", args));
        var model = new FakeLLMClient(List.of(new LLMResponse(null, List.of(edit), 1, 1),
                new LLMResponse("已按要求停止修改", null, 1, 1)));
        var output = new ByteArrayOutputStream();
        var stream = new PrintStream(output, true, StandardCharsets.UTF_8);
        var input = new BufferedReader(new StringReader("修改文本\nn\n/trace\nclear\n/trace\nexit\n"));
        var repl = new CodingAgentCli(root, model, false, 4, input, stream, stream);

        assertEquals(0, repl.runRepl(null, null, model, true));

        String text = output.toString(StandardCharsets.UTF_8);
        int preview = text.indexOf("--- a/example.txt");
        int approval = text.indexOf("y/N > ");
        int answer = text.indexOf("Agent > 已按要求停止修改");
        int trace = text.indexOf("最近一次执行过程");
        assertTrue(text.contains("正在处理…\r") && preview >= 0 && preview < approval
                && approval < answer && answer < trace, text);
        assertFalse(text.substring(0, trace).contains("[守卫]"), text);
        assertTrue(text.indexOf("[守卫]") > trace && text.indexOf("[控制员]") > trace, text);
        assertTrue(text.contains("当前聊天暂无执行过程"), text);
        assertEquals("before\n", Files.readString(file));
        assertEquals(2, model.callCount());
    }

    @Test
    void shouldAutoApproveFileCreationAndVersionedEditsButStillAskForOverwritesAndCommands() throws Exception {
        String version = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest("before".getBytes(StandardCharsets.UTF_8)));
        var create = new ToolCall("create", new ToolCall.Function("write_file", "{\"path\":\"src/demo.txt\",\"content\":\"before\"}"));
        var edit = new ToolCall("edit", new ToolCall.Function("replace_text", """
                {"path":"src/demo.txt","old_text":"before","new_text":"after","expected_version":"%s"}
                """.formatted(version)));
        var staleEdit = new ToolCall("stale", new ToolCall.Function("replace_text", """
                {"path":"src/demo.txt","old_text":"after","new_text":"invalid","expected_version":"%s"}
                """.formatted(version)));
        var overwrite = new ToolCall("overwrite", new ToolCall.Function("write_file", "{\"path\":\"src/demo.txt\",\"content\":\"overwrite\"}"));
        var secret = new ToolCall("protected", new ToolCall.Function("write_file", "{\"path\":\".env\",\"content\":\"blocked\"}"));
        var escaped = new ToolCall("escape", new ToolCall.Function("write_file", "{\"path\":\"../outside.txt\",\"content\":\"blocked\"}"));
        var command = new ToolCall("command", new ToolCall.Function("run_command", "{\"command\":\"echo manual\"}"));
        var model = new FakeLLMClient(List.of(new LLMResponse(null, List.of(create, edit, staleEdit, overwrite, secret, escaped, command), 1, 1),
                new LLMResponse("已处理", null, 1, 1)));
        var output = new ByteArrayOutputStream();
        var stream = new PrintStream(output, true, StandardCharsets.UTF_8);
        var input = new BufferedReader(new StringReader("处理文件\nn\nn\n/trace\nexit\n"));
        var repl = new CodingAgentCli(root, model, false, true, 4, input, stream, stream, null,
                (dir, cmd, timeout) -> { throw new AssertionError("命令被用户拒绝，不能执行"); });

        assertEquals(0, repl.runRepl(null, null, model, true));

        String text = output.toString(StandardCharsets.UTF_8);
        assertEquals("after", Files.readString(root.resolve("src/demo.txt")));
        assertTrue(Files.notExists(root.resolve(".env")));
        assertTrue(Files.notExists(root.resolve("../outside.txt")));
        assertEquals(2, java.util.regex.Pattern.compile("y/N > ").matcher(text).results().count(), text);
        assertTrue(text.contains("自动批准新建项目文件") && text.contains("自动批准带版本校验的精确替换"), text);
        assertTrue(text.contains("文件版本变化，拒绝修改"), text);
        assertTrue(text.contains("受保护路径") && text.contains("路径越出项目根目录"), text);
        assertEquals(2, model.callCount());
    }

    @Test
    void shouldSwitchFromYesToFileAndManualApprovalInOneChat() throws Exception {
        var first = new ToolCall("first", new ToolCall.Function("write_file", "{\"path\":\"first.txt\",\"content\":\"file mode\"}"));
        var second = new ToolCall("second", new ToolCall.Function("write_file", "{\"path\":\"second.txt\",\"content\":\"manual mode\"}"));
        var command = new ToolCall("command", new ToolCall.Function("run_command", "{\"command\":\"echo manual\"}"));
        var done = new LLMResponse("已处理", null, 1, 1);
        var model = new FakeLLMClient(List.of(new LLMResponse(null, List.of(first, command), 1, 1), done,
                new LLMResponse(null, List.of(second), 1, 1), done));
        var output = new ByteArrayOutputStream();
        var stream = new PrintStream(output, true, StandardCharsets.UTF_8);
        var input = new BufferedReader(new StringReader(
                "/approval\n/approval files\n第一次操作\nn\n/approval manual\n/approval all\n第二次操作\nn\n/approval\nexit\n"));
        var repl = new CodingAgentCli(root, model, true, 4, input, stream, stream, null,
                (dir, cmd, timeout) -> { throw new AssertionError("切换到文件模式后命令不能自动执行"); });

        assertEquals(0, repl.runRepl(null, null, model, false));

        String text = output.toString(StandardCharsets.UTF_8);
        assertEquals("file mode", Files.readString(root.resolve("first.txt")));
        assertTrue(Files.notExists(root.resolve("second.txt")));
        assertEquals(2, java.util.regex.Pattern.compile("y/N > ").matcher(text).results().count(), text);
        assertTrue(text.contains("当前审批模式: all") && text.contains("当前审批模式: files")
                && text.contains("当前审批模式: manual") && text.contains("用法: /approval [files|project|manual]"), text);
        assertEquals(4, model.callCount());
        assertFalse(model.requests().stream().flatMap(List::stream)
                .anyMatch(message -> "user".equals(message.role()) && message.content().startsWith("/approval")));
    }

    @Test
    void shouldRunTrustedProjectCommandsAndStillAskAboutSensitiveOperations() throws Exception {
        var create = new ToolCall("create", new ToolCall.Function("write_file", """
                {"path":"Echo.java","content":"class Echo { public static void main(String[] args) { System.out.println(\\"project-mode-ok\\"); } }"}
                """));
        var compile = commandCall("compile", "javac Echo.java");
        var run = commandCall("run", "java Echo.java");
        var overwrite = new ToolCall("overwrite", new ToolCall.Function("write_file", """
                {"path":"Echo.java","content":"overwritten"}
                """));
        var chain = commandCall("chain", "java Echo.java && echo extra");
        var blocked = commandCall("blocked", "rm -rf .");
        var done = new LLMResponse("已处理", null, 1, 1);
        var model = new FakeLLMClient(List.of(
                new LLMResponse(null, List.of(create, compile, run, overwrite, chain, blocked), 1, 1), done,
                new LLMResponse(null, List.of(commandCall("manual", "java Echo.java")), 1, 1), done));
        var output = new ByteArrayOutputStream();
        var stream = new PrintStream(output, true, StandardCharsets.UTF_8);
        var input = new BufferedReader(new StringReader("/approval project\n写并运行回显程序\nn\nn\n/trace\n"
                + "/approval manual\n再运行一次\nn\nexit\n"));
        var repl = new CodingAgentCli(root, model, false, 4, input, stream, stream, null, new ProcessCommandRunner(root));

        assertEquals(0, repl.runRepl(null, null, model, false));

        String text = output.toString(StandardCharsets.UTF_8);
        assertEquals(3, java.util.regex.Pattern.compile("y/N > ").matcher(text).results().count(), text);
        assertTrue(text.contains("当前审批模式: project") && text.contains("当前审批模式: manual"), text);
        assertTrue(text.contains("自动批准命令 javac Echo.java") && text.contains("自动批准命令 java Echo.java"), text);
        assertTrue(text.contains("命令包含高危片段"), text);
        assertTrue(Files.isRegularFile(root.resolve("Echo.class")), text);
        assertTrue(model.requests().get(1).stream().filter(message -> message.role().equals("tool"))
                .anyMatch(message -> message.content().contains("退出码: 0") && message.content().contains("project-mode-ok")), text);
        assertTrue(Files.readString(root.resolve("Echo.java")).contains("project-mode-ok"));
    }

    private static ToolCall commandCall(String id, String command) {
        return new ToolCall(id, new ToolCall.Function("run_command", "{\"command\":\"" + command + "\"}"));
    }

    @Test
    void shouldSwitchAndExportSessionsWithoutMixingHistoryOrTaskAudit() throws Exception {
        Path dir = root.resolve("custom sessions");
        SessionStore original = SessionStore.start(dir, SessionStore.Mode.NEW, "original");
        original.appendMessage(Message.user("原聊天历史"));
        SessionStore other = SessionStore.start(dir, SessionStore.Mode.NEW, "review notes");
        other.appendMessage(Message.user("目标旧问题"));
        other.appendMessage(Message.assistant("目标旧回复"));
        other.appendMessage(Message.user("目标聊天历史"));
        SessionStore audit = SessionStore.startStrict(dir, "task-audit");
        audit.appendMessage(Message.user("任务审计消息"));
        byte[] auditBefore = Files.readAllBytes(audit.currentFile());
        var output = new ByteArrayOutputStream();
        var stream = new PrintStream(output, true, StandardCharsets.UTF_8);
        var fake = new FakeLLMClient(List.of(new LLMResponse("回答一", null, 1, 1),
                new LLMResponse("回答二", null, 1, 1), new LLMResponse("回答三", null, 1, 1)));
        var input = new BufferedReader(new StringReader("/sessions\n/switch task-audit\n/switch missing\n原会话提问\n"
                + "/switch review notes\n/trace\n切换后提问\n/export\n/export task-audit\n/new\n新会话提问\nexit\n"));
        var cli = new CodingAgentCli(root, fake, false, 4, input, stream, stream, original);

        assertEquals(0, cli.runRepl());
        String text = output.toString(StandardCharsets.UTF_8);
        String lines = text.replace("\r\n", "\n");
        assertTrue(lines.contains("\noriginal\n  状态: 当前\n\n"), text);
        assertTrue(lines.contains("\ntask-audit\n  类型: 任务记录\n"), text);
        assertTrue(text.contains("聊天切换需要普通会话") && text.contains("会话不存在或记录不可用: missing"), text);
        assertTrue(text.contains("已切换到会话 review notes，恢复 3 条消息"), text);
        assertTrue(lines.contains("最近一轮对话\n用户 > 目标聊天历史\n\n用户 > "), text);
        assertFalse(text.contains("任务审计消息") || text.contains("目标旧回复"), text);
        assertTrue(text.contains("当前聊天暂无执行过程"), text);
        assertEquals(List.of(List.of("原聊天历史", "原会话提问"), List.of("目标旧问题", "目标聊天历史", "切换后提问"), List.of("新会话提问")),
                fake.requests().stream().map(request -> request.stream().filter(message -> message.role().equals("user"))
                        .map(Message::content).toList()).toList());
        assertEquals(List.of("原聊天历史", "原会话提问", "回答一"), SessionStore.load(dir, "original").messages().stream().map(Message::content).toList());
        assertEquals(List.of("目标旧问题", "目标旧回复", "目标聊天历史", "切换后提问", "回答二"),
                SessionStore.load(dir, "review notes").messages().stream().map(Message::content).toList());
        assertEquals(List.of("新会话提问", "回答三"), original.loadMessages().messages().stream().map(Message::content).toList());
        assertArrayEquals(auditBefore, Files.readAllBytes(audit.currentFile()));
        try (var files = Files.list(dir)) {
            List<Path> exports = files.filter(file -> file.toString().endsWith(".md")).toList();
            assertEquals(2, exports.size());
            List<String> contents = new java.util.ArrayList<>();
            for (Path file : exports) {
                assertTrue(text.contains(file.toString().replace('\\', '/')), text);
                contents.add(Files.readString(file));
            }
            assertTrue(contents.stream().anyMatch(value -> value.startsWith("# 会话 review notes\n") && value.contains("切换后提问")));
            assertTrue(contents.stream().anyMatch(value -> value.startsWith("# 会话 task-audit\n") && value.contains("任务审计消息")));
        }
    }

    @Test
    void shouldResumeLastSessionOnNextRun() throws Exception {
        Path dir = SessionPaths.defaultDir(root);
        SessionStore first = SessionStore.start(dir, SessionStore.Mode.NEW, "chat");
        first.appendMessage(Message.user("更早的问题"));
        first.appendMessage(Message.assistant("更早的回复"));
        first.appendMessage(Message.user("第一个问题"));
        first.appendMessage(Message.assistant("工具调用中的文字", List.of(commandCall("old-call", "java -version"))));
        first.appendMessage(Message.tool("old-call", "工具输出"));
        first.appendMessage(Message.assistant("第一轮回答\n第二行"));

        SessionStore resumed = SessionStore.start(dir, SessionStore.Mode.AUTO, null);
        FakeLLMClient second = new FakeLLMClient(List.of(new LLMResponse("第二轮回答", null, 1, 1)));

        assertTrue(resumed.resumed());
        assertEquals("chat", resumed.sessionId());

        var output = new ByteArrayOutputStream();
        var stream = new PrintStream(output, true, StandardCharsets.UTF_8);
        var input = new BufferedReader(new StringReader("第二个问题\nexit\n"));
        var repl = new CodingAgentCli(root, second, false, 4, input, stream, stream, resumed);
        assertEquals(0, repl.runRepl());

        String text = output.toString(StandardCharsets.UTF_8).replace("\r\n", "\n");
        assertTrue(text.contains("最近一轮对话\n用户 > 第一个问题\nAgent > 第一轮回答\n第二行\n"), text);
        assertFalse(text.contains("更早的") || text.contains("工具调用中的文字") || text.contains("工具输出"), text);
        assertEquals(1, second.callCount());
        assertEquals(8, resumed.loadMessages().messages().size());

        List<String> sentToModel = second.requests().get(0).stream()
                .map(com.agent.llm.Message::content).toList();
        assertTrue(sentToModel.contains("第一个问题"), "恢复后模型应当看到上一轮的用户消息：" + sentToModel);
        assertTrue(sentToModel.contains("第一轮回答\n第二行"), "恢复后模型应当看到上一轮的助手回答：" + sentToModel);
    }

    @Test
    void clearShouldStartNewSessionAndKeepOldFile() throws Exception {
        Path dir = SessionPaths.defaultDir(root);
        SessionStore store = SessionStore.start(dir, SessionStore.Mode.NEW, "keep");
        Path oldFile = store.currentFile();
        FakeLLMClient fake = new FakeLLMClient(List.of(new LLMResponse("回答", null, 1, 1)));

        cli(store, fake, "问题\nclear\nexit\n").runRepl();

        assertTrue(Files.exists(oldFile), "旧会话文件必须保留");
        assertTrue(SessionStore.listSessions(dir).size() >= 2, "clear 之后应当出现新会话文件");
        assertEquals("问题", SessionStore.load(dir, "keep").messages().get(0).content());
    }

    @Test
    void singleTaskShouldWriteIntoItsOwnSession() throws Exception {
        Path dir = SessionPaths.defaultDir(root);
        String taskId = "task-" + SessionPaths.timestampName();
        SessionStore store = SessionStore.start(dir, SessionStore.Mode.NEW, taskId);
        FakeLLMClient fake = new FakeLLMClient(List.of(new LLMResponse("任务完成", null, 1, 1)));

        cli(store, fake, "").runTask("把 README 里的 TODO 汇总");

        List<String> contents = SessionStore.load(dir, taskId).messages().stream()
                .map(com.agent.llm.Message::content).toList();
        assertEquals(List.of("把 README 里的 TODO 汇总", "任务完成"), contents);
        assertTrue(taskId.startsWith("task-"));
    }

    @Test
    void shouldWarnWhenCorruptLinesWereSkipped() throws Exception {
        Path dir = SessionPaths.defaultDir(root);
        SessionStore store = SessionStore.start(dir, SessionStore.Mode.NEW, "corrupt");
        store.appendMessage(com.agent.llm.Message.user("有效消息"));
        Files.writeString(store.currentFile(), "{坏行}\n", StandardCharsets.UTF_8,
                java.nio.file.StandardOpenOption.APPEND);

        ByteArrayOutputStream errBytes = new ByteArrayOutputStream();
        CodingAgentCli cli = new CodingAgentCli(root,
                new FakeLLMClient(List.of(new LLMResponse("ok", null, 1, 1))), true, 3,
                new BufferedReader(new StringReader("exit\n")),
                silent(), new PrintStream(errBytes, true, StandardCharsets.UTF_8),
                SessionStore.start(dir, SessionStore.Mode.RESUME, "corrupt"));

        cli.runRepl();

        assertTrue(errBytes.toString(StandardCharsets.UTF_8).contains("损坏记录"));
    }
}
