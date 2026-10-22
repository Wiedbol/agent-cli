package com.agent;

import com.agent.command.CommandResult;
import com.agent.command.CommandRunner;
import com.agent.llm.FakeLLMClient;
import com.agent.llm.LLMClient;
import com.agent.llm.LLMResponse;
import com.agent.llm.ToolCall;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.io.StringReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

class CodingAgentCliTaskResumeTest {
    @TempDir Path root;
    private final ByteArrayOutputStream output = new ByteArrayOutputStream();

    @BeforeEach void project() throws Exception {
        Files.writeString(root.resolve("pom.xml"), "<project/>");
        Files.createDirectories(root.resolve("src/main/java"));
        Files.writeString(source(), "broken");
    }

    private Path source() { return root.resolve("src/main/java/App.java"); }
    private String text() { return output.toString(StandardCharsets.UTF_8); }
    private CodingAgentCli cli(LLMClient model, CommandRunner runner, int budget) {
        PrintStream stream = new PrintStream(output, true, StandardCharsets.UTF_8);
        return new CodingAgentCli(root, model, true, budget, new BufferedReader(new StringReader("")),
                stream, stream, null, runner);
    }
    private String id() {
        var match = Pattern.compile("任务: ([0-9a-f-]{36})").matcher(text());
        assertTrue(match.find(), text());
        return match.group(1);
    }
    private CommandRunner acceptance() {
        return (dir, command, timeout) -> {
            boolean fixed = Files.readString(source()).equals("fixed");
            Path reports = Files.createDirectories(root.resolve("target/surefire-reports"));
            Files.writeString(reports.resolve("TEST-AppTest.xml"), "<testsuite tests=\"1\" failures=\""
                    + (fixed ? 0 : 1) + "\" errors=\"0\" skipped=\"0\"><testcase classname=\"AppTest\" name=\"checks\">"
                    + (fixed ? "" : "<failure message=\"broken\"/>") + "</testcase></testsuite>");
            return new CommandResult(fixed ? 0 : 1, false, "test output", 5, CommandResult.OutputCompleteness.COMPLETE);
        };
    }
    private ToolCall edit(String id, String content) {
        return new ToolCall(id, new ToolCall.Function("write_file",
                "{\"path\":\"src/main/java/App.java\",\"content\":\"" + content + "\"}"));
    }

    @Test void shouldResumeCompletedEditWithCumulativeBudget() throws Exception {
        var first = new FakeLLMClient(List.of(new LLMResponse("edit", List.of(edit("edit-once", "fixed")), 2, 3)));
        assertEquals(1, cli(first, acceptance(), 1).startManagedTask("修复计算", null), text());
        String task = id();
        long modified = Files.getLastModifiedTime(source()).toMillis();
        assertEquals(0, cli(null, acceptance(), 1).raiseTaskBudget(task, 2));
        var resumed = new FakeLLMClient(List.of(new LLMResponse("请验收", null, 1, 1)));
        assertEquals(0, cli(resumed, acceptance(), 12).resumeManagedTask(task), text());
        assertEquals(modified, Files.getLastModifiedTime(source()).toMillis());
        assertEquals(1, resumed.callCount());
        var messages = resumed.requests().get(0);
        assertEquals(1, messages.stream().filter(m -> m.role().equals("user") && m.content().contains("修复计算")).count());
        assertEquals(1, messages.stream().filter(m -> "edit-once".equals(m.toolCallId())).count());
        assertTrue(text().contains("模型请求: 2/2"), text());
        assertTrue(text().contains("状态: Verified"), text());
    }

    @Test void shouldContinueOnlyPendingCallsAfterCompletionSavedButChatAppendFails() throws Exception {
        var first = new FakeLLMClient(List.of(new LLMResponse("two edits",
                List.of(edit("first", "intermediate"), edit("second", "fixed")), 2, 3)));
        Path[] damaged = {null};
        byte[][] saved = {null};
        PrintStream fault = new PrintStream(output, true, StandardCharsets.UTF_8) {
            @Override public void println(String value) {
                super.println(value);
                if (value.contains("[执行员] 执行成功") && damaged[0] == null) {
                    try {
                        Path log = root.resolve(".agent/sessions/task-" + id() + ".jsonl");
                        saved[0] = Files.readAllBytes(log);
                        Files.delete(log);
                        Files.createDirectory(log);
                        damaged[0] = log;
                    } catch (IOException failure) { throw new UncheckedIOException(failure); }
                }
            }
        };
        var interrupted = new CodingAgentCli(root, first, true, 12, new BufferedReader(new StringReader("")),
                fault, fault, null, acceptance());
        assertEquals(4, interrupted.startManagedTask("修复计算", null), text());
        assertEquals("intermediate", Files.readString(source()));
        String task = id();
        Files.delete(damaged[0]);
        Files.write(damaged[0], saved[0]);
        var resumed = new FakeLLMClient(List.of(new LLMResponse("请验收", null, 1, 1)));
        assertEquals(0, cli(resumed, acceptance(), 12).resumeManagedTask(task), text());
        assertEquals(1, resumed.callCount());
        assertEquals("fixed", Files.readString(source()));
        var messages = resumed.requests().get(0);
        assertEquals(List.of("first", "second"), messages.stream().filter(m -> m.role().equals("tool"))
                .map(com.agent.llm.Message::toolCallId).toList());
        assertEquals(1, messages.stream().filter(m -> m.toolCalls() != null).count());
        assertTrue(text().contains("模型请求: 2/12"), text());
        assertTrue(text().contains("工具调用: 2"), text());
    }

    private static final class PowerLoss extends Error { }

    @Test void shouldRetryIncompleteReadWithoutRepeatingModelResponse() throws Exception {
        ToolCall read = new ToolCall("read-again", new ToolCall.Function("read_file", "{\"path\":\"src/main/java/App.java\"}"));
        var first = new FakeLLMClient(List.of(new LLMResponse(null, List.of(read, edit("repair", "fixed")), 2, 3)));
        PrintStream fault = powerLossAt("[执行员] 执行成功");
        var interrupted = new CodingAgentCli(root, first, true, 12, new BufferedReader(new StringReader("")),
                fault, fault, null, acceptance());
        assertThrows(PowerLoss.class, () -> interrupted.startManagedTask("修复", null));
        var resumed = new FakeLLMClient(List.of(new LLMResponse("请验收", null, 1, 1)));
        assertEquals(0, cli(resumed, acceptance(), 12).resumeManagedTask(id()), text());
        assertEquals(1, resumed.callCount());
        assertEquals(1, resumed.requests().get(0).stream().filter(m -> "read-again".equals(m.toolCallId())).count());
        assertTrue(text().contains("工具调用: 3"), text());
    }

    @Test void shouldPauseUnknownWriteWithoutExecutingPendingCall() throws Exception {
        var first = new FakeLLMClient(List.of(new LLMResponse(null,
                List.of(edit("unknown-write", "intermediate"), edit("must-not-run", "fixed")), 2, 3)));
        PrintStream fault = powerLossAt("[执行员] 执行成功");
        var interrupted = new CodingAgentCli(root, first, true, 12, new BufferedReader(new StringReader("")),
                fault, fault, null, acceptance());
        assertThrows(PowerLoss.class, () -> interrupted.startManagedTask("修复", null));
        assertEquals("intermediate", Files.readString(source()));
        LLMClient forbidden = (messages, tools) -> { throw new AssertionError("未知写入不能请求模型"); };
        CommandRunner noCommands = (dir, command, timeout) -> { throw new AssertionError("未知写入不能执行命令"); };
        assertEquals(1, cli(forbidden, noCommands, 12).resumeManagedTask(id()), text());
        assertEquals("intermediate", Files.readString(source()));
        assertTrue(text().contains("工具结果不明: unknown-write"), text());
    }

    @Test void shouldNotPromoteBlankCompletionToVerifiedOnResume() throws Exception {
        Files.writeString(source(), "fixed");
        var first = new FakeLLMClient(List.of(new LLMResponse("", null, 1, 1)));
        assertEquals(1, cli(first, acceptance(), 12).startManagedTask("检查", null));
        LLMClient forbidden = (messages, tools) -> { throw new AssertionError("空声明不能请求模型"); };
        assertEquals(1, cli(forbidden, acceptance(), 12).resumeManagedTask(id()), text());
        assertFalse(text().contains("状态: Verified"), text());
    }

    private PrintStream powerLossAt(String fragment) {
        return new PrintStream(output, true, StandardCharsets.UTF_8) {
            @Override public void println(String value) {
                super.println(value);
                if (value.contains(fragment)) { throw new PowerLoss(); }
            }
        };
    }

    @Test void shouldResumeRecordedCompletionWithoutAnotherModelRequest() throws Exception {
        var model = new FakeLLMClient(List.of(new LLMResponse(null, List.of(edit("fix", "fixed")), 2, 3),
                new LLMResponse("请验收", null, 1, 1)));
        PrintStream fault = powerLossAt("模型未请求工具");
        var interrupted = new CodingAgentCli(root, model, true, 12, new BufferedReader(new StringReader("")),
                fault, fault, null, acceptance());
        assertThrows(PowerLoss.class, () -> interrupted.startManagedTask("修复", null));
        LLMClient noRequest = (messages, tools) -> { throw new AssertionError("已保存最终响应不能重问"); };
        assertEquals(0, cli(noRequest, acceptance(), 12).resumeManagedTask(id()), text());
        assertEquals(2, model.callCount());
        assertTrue(text().contains("模型请求: 2/12"), text());
    }

    @Test void shouldPauseUncertainAcceptanceInsteadOfRerunningIt() throws Exception {
        int[] executions = {0};
        CommandRunner runner = (dir, command, timeout) -> {
            if (++executions[0] == 2) { throw new PowerLoss(); }
            return acceptance().run(dir, command, timeout);
        };
        var model = new FakeLLMClient(List.of(new LLMResponse(null, List.of(edit("fix", "fixed")), 2, 3),
                new LLMResponse("请验收", null, 1, 1)));
        assertThrows(PowerLoss.class, () -> cli(model, runner, 12).startManagedTask("修复", null));
        LLMClient noRequest = (messages, tools) -> { throw new AssertionError("验收命令结果不明不能请求模型"); };
        CommandRunner forbidden = (dir, command, timeout) -> { throw new AssertionError("不应重试验收命令"); };
        assertEquals(1, cli(noRequest, forbidden, 12).resumeManagedTask(id()), text());
        assertTrue(text().contains("验收命令结果不明"), text());
        assertEquals(2, executions[0]);
    }

    @Test void shouldStopAfterEffectsWhenCompletionStorageFailsAndPauseOnRestart() throws Exception {
        Path[] damaged = {null};
        CommandRunner runner = (dir, command, timeout) -> {
            if (command.equals("fixture-command")) {
                Files.writeString(source(), "intermediate");
                Path previous = root.resolve(".agent/tasks/" + id() + "/state.previous.json");
                Files.delete(previous);
                Files.createDirectory(previous);
                damaged[0] = previous;
                return new CommandResult(0, false, "command effect", 1, CommandResult.OutputCompleteness.COMPLETE);
            }
            return acceptance().run(dir, command, timeout);
        };
        var model = new FakeLLMClient(List.of(new LLMResponse(null, List.of(
                new ToolCall("unknown-command", new ToolCall.Function("run_command", "{\"command\":\"fixture-command\"}")),
                edit("pending", "fixed")), 2, 3)));
        assertEquals(4, cli(model, runner, 12).startManagedTask("修复", null), text());
        assertEquals("intermediate", Files.readString(source()));
        Files.delete(damaged[0]);
        LLMClient noRequest = (messages, tools) -> { throw new AssertionError("未知命令不能继续"); };
        CommandRunner forbidden = (dir, command, timeout) -> { throw new AssertionError("未知命令不能重放"); };
        assertEquals(1, cli(noRequest, forbidden, 12).resumeManagedTask(id()), text());
        assertTrue(text().contains("工具结果不明: unknown-command"), text());
        assertEquals("intermediate", Files.readString(source()));
    }

    private String pausedRead() {
        var model = new FakeLLMClient(List.of(new LLMResponse(null, List.of(new ToolCall("known-read",
                new ToolCall.Function("read_file", "{\"path\":\"src/main/java/App.java\"}"))), 2, 3)));
        assertEquals(1, cli(model, acceptance(), 1).startManagedTask("修复", null), text());
        return id();
    }

    @ParameterizedTest @ValueSource(strings = {"modify", "add", "delete"})
    void shouldPauseExternalChangesWithoutLosingHumanFiles(String change) throws Exception {
        String task = pausedRead();
        switch (change) {
            case "modify" -> Files.writeString(source(), "human edit");
            case "add" -> Files.writeString(root.resolve("src/main/java/Human.java"), "human addition");
            case "delete" -> Files.delete(source());
        }
        LLMClient noRequest = (messages, tools) -> { throw new AssertionError("外部变化不能继续模型"); };
        CommandRunner forbidden = (dir, command, timeout) -> { throw new AssertionError("外部变化不能继续命令"); };
        assertEquals(1, cli(noRequest, forbidden, 12).resumeManagedTask(task), text());
        assertTrue(text().contains("文件版本不符合任务记录"), text());
        if (change.equals("modify")) { assertEquals("human edit", Files.readString(source())); }
        if (change.equals("add")) { assertEquals("human addition", Files.readString(root.resolve("src/main/java/Human.java"))); }
        if (change.equals("delete")) { assertFalse(Files.exists(source())); }
    }

    @ParameterizedTest @ValueSource(strings = {"missing-state", "truncated-state", "missing-evidence", "corrupt-evidence"})
    void shouldRejectMissingOrDamagedCheckpointWithoutFallingBackToPrevious(String damage) throws Exception {
        String task = pausedRead();
        Path directory = root.resolve(".agent/tasks/" + task);
        if (damage.endsWith("state")) {
            Path state = directory.resolve("state.json");
            if (damage.startsWith("missing")) { Files.delete(state); }
            else { Files.writeString(state, "{\"schemaVersion\":4"); }
        } else {
            Path evidence;
            try (var entries = Files.walk(directory)) {
                evidence = entries.filter(p -> p.getFileName().toString().equals("result.json")).findFirst().orElseThrow();
            }
            if (damage.startsWith("missing")) { Files.delete(evidence); }
            else { Files.writeString(evidence, "{}"); }
        }
        LLMClient noRequest = (messages, tools) -> { throw new AssertionError("损坏检查点不能继续模型"); };
        assertEquals(4, cli(noRequest, acceptance(), 12).resumeManagedTask(task), text());
        assertEquals("broken", Files.readString(source()));
    }

    @Test void shouldRespectSingleWriterLockOnResume() throws Exception {
        String task = pausedRead();
        try (var channel = java.nio.channels.FileChannel.open(root.resolve(".agent/tasks/writer.lock"), StandardOpenOption.WRITE);
             var lock = channel.lock()) {
            LLMClient noRequest = (messages, tools) -> { throw new AssertionError("不能并发执行"); };
            assertEquals(4, cli(noRequest, acceptance(), 12).resumeManagedTask(task), text());
            assertTrue(text().contains("无法加锁"), text());
        }
    }

    @Test void shouldRejectTaskCopiedToAnotherProject() throws Exception {
        String task = pausedRead();
        Path copied = root.resolve("copied-project");
        List<Path> entries;
        try (var paths = Files.walk(root)) { entries = paths.toList(); }
        for (Path path : entries) {
            Path target = copied.resolve(root.relativize(path));
            if (Files.isDirectory(path)) { Files.createDirectories(target); }
            else { Files.copy(path, target); }
        }
        PrintStream stream = new PrintStream(output, true, StandardCharsets.UTF_8);
        LLMClient noRequest = (messages, tools) -> { throw new AssertionError("不同项目不能恢复"); };
        var other = new CodingAgentCli(copied, noRequest, true, 12, new BufferedReader(new StringReader("")),
                stream, stream, null, acceptance());
        assertEquals(4, other.resumeManagedTask(task), text());
        assertTrue(text().contains("其他项目"), text());
    }

    @Test void shouldResumeAfterExplicitReagreementUsingNewAcceptance() throws Exception {
        Files.writeString(source(), "fixed");
        var model = new FakeLLMClient(List.of(new LLMResponse("请验收", null, 1, 1)));
        assertEquals(0, cli(model, acceptance(), 12).startManagedTask("检查", null), text());
        String task = id();
        PrintStream stream = new PrintStream(output, true, StandardCharsets.UTF_8);
        var agreement = new CodingAgentCli(root, null, false, 12,
                new BufferedReader(new StringReader("approve " + task + "\n")), stream, stream, null, acceptance());
        assertEquals(1, agreement.reagreeAcceptance(task, "mvn verify", true), text());
        var resumed = new FakeLLMClient(List.of(new LLMResponse("重新验收", null, 1, 1)));
        assertEquals(0, cli(resumed, acceptance(), 12).resumeManagedTask(task), text());
        assertTrue(text().contains("验收: mvn verify"), text());
        assertTrue(text().contains("模型请求: 2/12"), text());
    }

    @Test void shouldRecoverResponseSavedBeforeAnyToolIntentOrChatTurn() throws Exception {
        Path[] damaged = {null};
        byte[][] saved = {null};
        var scripted = new FakeLLMClient(List.of(new LLMResponse(null, List.of(edit("saved-response", "fixed")), 2, 3)));
        LLMClient first = (messages, tools) -> {
            Path log = root.resolve(".agent/sessions/task-" + id() + ".jsonl");
            saved[0] = Files.readAllBytes(log);
            Files.delete(log);
            Files.createDirectory(log);
            damaged[0] = log;
            return scripted.chat(messages, tools);
        };
        assertEquals(4, cli(first, acceptance(), 12).startManagedTask("修复", null), text());
        assertEquals("broken", Files.readString(source()));
        Files.delete(damaged[0]);
        Files.write(damaged[0], saved[0]);
        PrintStream stream = new PrintStream(output, true, StandardCharsets.UTF_8);
        var blocked = new CodingAgentCli(root, new FakeLLMClient(List.of()), false, 12,
                new BufferedReader(new StringReader("")), stream, stream, null, acceptance());
        assertEquals(1, blocked.resumeManagedTask(id(), false), text());
        assertEquals("broken", Files.readString(source()));
        assertTrue(text().contains("非交互任务需要人工批准"), text());
        var resumed = new FakeLLMClient(List.of(new LLMResponse("请验收", null, 1, 1)));
        assertEquals(0, cli(resumed, acceptance(), 12).resumeManagedTask(id()), text());
        assertEquals(1, resumed.callCount());
        assertEquals("fixed", Files.readString(source()));
        assertEquals(1, resumed.requests().get(0).stream().filter(m -> "saved-response".equals(m.toolCallId())).count());
    }

    @Test void shouldKeepReservedRequestsUnknownAcrossRestart() throws Exception {
        LLMClient first = (messages, tools) -> { throw new IOException("network unavailable"); };
        assertEquals(1, cli(first, acceptance(), 2).startManagedTask("修复", null));
        var resumed = new FakeLLMClient(List.of(new LLMResponse(null, List.of(edit("repair", "fixed")), 2, 3)));
        assertEquals(1, cli(resumed, acceptance(), 12).resumeManagedTask(id()), text());
        assertEquals("fixed", Files.readString(source()));
        assertEquals(1, resumed.callCount());
        assertTrue(text().contains("模型请求: 2/2"), text());
        assertTrue(text().contains("未知请求=1"), text());
    }

    @Test void shouldKeepErrorRoundsAcrossRestartsAndBudgetIncreases() throws Exception {
        String task = pausedRead();
        assertEquals(0, cli(null, acceptance(), 12).raiseTaskBudget(task, 2));
        var read = new ToolCall("read-two", new ToolCall.Function("read_file", "{\"path\":\"pom.xml\"}"));
        assertEquals(1, cli(new FakeLLMClient(List.of(new LLMResponse(null, List.of(read), 1, 1))), acceptance(), 12)
                .resumeManagedTask(task), text());
        assertEquals(0, cli(null, acceptance(), 12).raiseTaskBudget(task, 3));
        var third = new FakeLLMClient(List.of(new LLMResponse(null, List.of(read), 1, 1)));
        assertEquals(1, cli(third, acceptance(), 12).resumeManagedTask(task), text());
        assertTrue(text().contains("同一核心错误持续三个决策轮次"), text());
        assertTrue(text().contains("模型请求: 3/3"), text());
        LLMClient forbidden = (messages, tools) -> { throw new AssertionError("恢复不能重置错误计数"); };
        assertEquals(1, cli(forbidden, acceptance(), 12).resumeManagedTask(task), text());
    }

    @Test void shouldNotLetYesOverrideUnknownIntent() throws Exception {
        var first = new FakeLLMClient(List.of(new LLMResponse(null, List.of(edit("pending-approval", "fixed")), 2, 3)));
        PrintStream fault = powerLossAt("[守卫]");
        var interrupted = new CodingAgentCli(root, first, false, 12, new BufferedReader(new StringReader("")),
                fault, fault, null, acceptance());
        assertThrows(PowerLoss.class, () -> interrupted.startManagedTask("修复", null));
        // 守卫裁决时写意图已保存，不能用本次 --yes 掩盖不确定性。
        assertEquals(1, cli(new FakeLLMClient(List.of()), acceptance(), 12).resumeManagedTask(id()), text());
        assertEquals("broken", Files.readString(source()));
        assertTrue(text().contains("工具结果不明"), text());
    }

    @Test void shouldResumeBlockedApprovalWithExplicitCurrentYes() throws Exception {
        var first = new FakeLLMClient(List.of(new LLMResponse(null, List.of(edit("approved-later", "fixed")), 2, 3)));
        PrintStream stream = new PrintStream(output, true, StandardCharsets.UTF_8);
        var blocked = new CodingAgentCli(root, first, false, 12, new BufferedReader(new StringReader("")),
                stream, stream, null, acceptance());
        assertEquals(1, blocked.startManagedTask("修复", null, false), text());
        String task = id();
        assertEquals("broken", Files.readString(source()));
        var resumed = new FakeLLMClient(List.of(new LLMResponse("请验收", null, 1, 1)));
        assertEquals(0, cli(resumed, acceptance(), 12).resumeManagedTask(task, false), text());
        assertEquals("fixed", Files.readString(source()));
        assertTrue(text().contains("工具调用: 2"), text());
    }

    @Test void shouldPauseIfNecessaryCheckpointTextWasRedacted() throws Exception {
        var first = new FakeLLMClient(List.of(new LLMResponse(null, List.of(new ToolCall("read",
                new ToolCall.Function("read_file", "{\"path\":\"pom.xml\"}"))), 2, 3)));
        assertEquals(1, cli(first, acceptance(), 1).startManagedTask("修复 token=offline-secret-value", null));
        LLMClient forbidden = (messages, tools) -> { throw new AssertionError("脱敏后不能猜测必要内容"); };
        assertEquals(1, cli(forbidden, acceptance(), 12).resumeManagedTask(id()), text());
        assertTrue(text().contains("必要内容已脱敏"), text());
    }

    @Test void shouldKeepTaskChatsOutOfAutomaticResumeAndAllowExplicitExportWithoutExecution() throws Exception {
        Path sessions = root.resolve(".agent/sessions");
        var chat = com.agent.session.SessionStore.start(sessions, com.agent.session.SessionStore.Mode.NEW, "ordinary-chat");
        chat.appendMessage(com.agent.llm.Message.user("普通聊天"));
        String task = pausedRead();
        var automatic = com.agent.session.SessionStore.start(sessions, com.agent.session.SessionStore.Mode.AUTO, null);
        assertEquals("ordinary-chat", automatic.sessionId());
        var explicit = com.agent.session.SessionStore.start(sessions, com.agent.session.SessionStore.Mode.RESUME, "task-" + task);
        PrintStream stream = new PrintStream(output, true, StandardCharsets.UTF_8);
        LLMClient forbidden = (messages, tools) -> { throw new AssertionError("载入聊天不执行任务"); };
        CommandRunner noCommands = (dir, command, timeout) -> { throw new AssertionError("载入聊天不执行命令"); };
        var repl = new CodingAgentCli(root, forbidden, true, 12, new BufferedReader(new StringReader("")),
                stream, stream, explicit, noCommands);
        assertEquals(0, repl.runRepl());
        assertTrue(com.agent.session.SessionExporter.toMarkdown(sessions, "task-" + task).contains("修复"));
        assertEquals("broken", Files.readString(source()));
    }

    @Test void shouldLeaveLegacyTasksQueryableButDeclineUnsafeResume() throws Exception {
        String task = pausedRead();
        Path state = root.resolve(".agent/tasks/" + task + "/state.json");
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        var legacy = (com.fasterxml.jackson.databind.node.ObjectNode) mapper.readTree(Files.readAllBytes(state));
        legacy.put("schemaVersion", 3);
        legacy.remove(List.of("checkpoint", "checkpointSha256"));
        Files.write(state, mapper.writeValueAsBytes(legacy));
        assertEquals(0, cli(null, acceptance(), 12).showTask(task), text());
        LLMClient forbidden = (messages, tools) -> { throw new AssertionError("旧任务没有恢复证据"); };
        assertEquals(1, cli(forbidden, acceptance(), 12).resumeManagedTask(task), text());
        assertTrue(text().contains("旧任务缺少可靠恢复检查点"), text());
    }

    @Test void shouldNotRerunRecordedFinalAcceptanceAfterInterruption() throws Exception {
        int[] executions = {0};
        CommandRunner runner = (dir, command, timeout) -> {
            executions[0]++;
            return acceptance().run(dir, command, timeout);
        };
        var model = new FakeLLMClient(List.of(new LLMResponse(null, List.of(edit("fix", "fixed")), 2, 3),
                new LLMResponse("请验收", null, 1, 1)));
        PrintStream fault = powerLossAt("final 验收结果已记录");
        var interrupted = new CodingAgentCli(root, model, true, 12, new BufferedReader(new StringReader("")),
                fault, fault, null, runner);
        assertThrows(PowerLoss.class, () -> interrupted.startManagedTask("修复", null));
        LLMClient noRequest = (messages, tools) -> { throw new AssertionError("已完成验收不能重问"); };
        CommandRunner forbidden = (dir, command, timeout) -> { throw new AssertionError("已记录验收不能重复执行"); };
        assertEquals(0, cli(noRequest, forbidden, 12).resumeManagedTask(id()), text());
        assertEquals(2, executions[0]);
    }

    @Test void shouldRedactStructuredToolArgumentsInEveryPersistedCopy() throws Exception {
        String canary = "private offline fixture material";
        var model = new FakeLLMClient(List.of(new LLMResponse(null, List.of(new ToolCall("sensitive-proposal",
                new ToolCall.Function("write_file", "{\"path\":\"fixture.pem\",\"content\":\"" + canary + "\"}"))), 1, 1)));
        assertEquals(1, cli(model, acceptance(), 1).startManagedTask("检查", null), text());
        try (var paths = Files.walk(root.resolve(".agent"))) {
            for (Path path : paths.filter(Files::isRegularFile).toList()) {
                assertFalse(Files.readString(path).contains(canary), "持久化副本泄漏: " + path);
            }
        }
        assertFalse(Files.exists(root.resolve("fixture.pem")));
    }

    @Test void shouldNotExecuteWhenToolIntentCannotBeSavedAndRecoverOnlyAfterStorageRepair() throws Exception {
        Path[] damaged = {null};
        PrintStream fault = new PrintStream(output, true, StandardCharsets.UTF_8) {
            @Override public void println(String value) {
                super.println(value);
                if (value.contains("模型请求调用工具") && damaged[0] == null) {
                    try {
                        Path previous = root.resolve(".agent/tasks/" + id() + "/state.previous.json");
                        Files.delete(previous);
                        Files.createDirectory(previous);
                        damaged[0] = previous;
                    } catch (IOException failure) { throw new UncheckedIOException(failure); }
                }
            }
        };
        var first = new FakeLLMClient(List.of(new LLMResponse(null, List.of(edit("intent-not-saved", "fixed")), 2, 3)));
        var interrupted = new CodingAgentCli(root, first, true, 12, new BufferedReader(new StringReader("")),
                fault, fault, null, acceptance());
        assertEquals(4, interrupted.startManagedTask("修复", null), text());
        assertEquals("broken", Files.readString(source()));
        assertEquals(1, first.callCount());
        Files.delete(damaged[0]);
        var resumed = new FakeLLMClient(List.of(new LLMResponse("请验收", null, 1, 1)));
        assertEquals(0, cli(resumed, acceptance(), 12).resumeManagedTask(id()), text());
        assertEquals("fixed", Files.readString(source()));
        assertEquals(1, resumed.callCount());
    }
}
