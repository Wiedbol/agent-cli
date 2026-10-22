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
import java.io.PrintStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/** 从既有 CLI 入口验证任务，而不依赖存储的序列化布局。 */
class CodingAgentCliManagedTaskTest {
    @TempDir Path root;
    private final ByteArrayOutputStream output = new ByteArrayOutputStream();
    private final ByteArrayOutputStream errors = new ByteArrayOutputStream();

    @BeforeEach
    void project() throws Exception {
        Files.writeString(root.resolve("pom.xml"), "<project/>");
        Files.createDirectories(root.resolve("src/main/java"));
        Files.writeString(root.resolve("src/main/java/App.java"), "broken");
    }

    private CodingAgentCli cli(LLMClient model, CommandRunner runner) {
        return new CodingAgentCli(root, model, true, 4,
                new BufferedReader(new StringReader("")),
                new PrintStream(output, true, StandardCharsets.UTF_8),
                new PrintStream(errors, true, StandardCharsets.UTF_8), null, runner);
    }

    private FakeLLMClient completedModel() {
        return new FakeLLMClient(List.of(new LLMResponse("修复完成", null, 1, 1)));
    }

    private String text() { return output.toString(StandardCharsets.UTF_8); }

    private String taskId() {
        var match = Pattern.compile("任务: ([0-9a-f-]{36})").matcher(text());
        assertTrue(match.find(), text() + errors.toString(StandardCharsets.UTF_8));
        return match.group(1);
    }

    @Test
    void shouldPersistPausedTaskAndInspectItAfterRecreation() throws Exception {
        CommandRunner failing = (dir, command, timeout) ->
                new CommandResult(1, false, "tests failed", 5, CommandResult.OutputCompleteness.COMPLETE);
        assertNotEquals(0, cli(completedModel(), failing).startManagedTask("修复计算错误", null));
        String id = taskId();
        output.reset();
        CodingAgentCli query = cli(null, (dir, command, timeout) -> {
            throw new AssertionError("查询不得执行命令");
        });

        assertEquals(0, query.listTasks());
        assertEquals(0, query.showTask(id));
        assertTrue(text().contains(id));
        assertTrue(text().contains("修复计算错误"));
        assertTrue(text().contains("Paused"));
        assertTrue(text().contains("mvn test"));
        assertTrue(text().contains("验收"));
    }

    @Test
    void shouldVerifyRepairOnlyAfterIndependentCurrentReports() throws Exception {
        int[] checks = {0};
        CommandRunner runner = (dir, command, timeout) -> {
            assertEquals("mvn test", command);
            boolean repaired = Files.readString(root.resolve("src/main/java/App.java")).equals("fixed");
            writeReport(repaired ? 0 : 1);
            checks[0]++;
            return new CommandResult(repaired ? 0 : 1, false, "x".repeat(5000), 5,
                    CommandResult.OutputCompleteness.UNKNOWN);
        };
        FakeLLMClient model = new FakeLLMClient(List.of(
                new LLMResponse(null, List.of(new ToolCall("edit", new ToolCall.Function("write_file",
                        "{\"path\":\"src/main/java/App.java\",\"content\":\"fixed\"}"))), 1, 1),
                new LLMResponse("修复完成", null, 1, 1)));

        assertEquals(0, cli(model, runner).startManagedTask("修复计算错误", null));
        assertEquals(2, checks[0]);
        String id = taskId();
        output.reset();
        assertEquals(0, cli(null, runner).showTask(id));
        assertTrue(text().contains("Verified"), text());
        assertTrue(text().contains("baseline: FAILED"), text());
        assertTrue(text().contains("final: PASSED"), text());
        assertTrue(model.requests().get(0).stream().anyMatch(message ->
                message.content() != null && message.content().contains("初始验收")));
    }

    private void writeReport(int failures) throws java.io.IOException {
        Path reports = Files.createDirectories(root.resolve("target/surefire-reports"));
        Files.writeString(reports.resolve("TEST-AppTest.xml"),
                "<testsuite tests=\"1\" failures=\"" + failures + "\" errors=\"0\" skipped=\"0\">"
                + "<testcase classname=\"AppTest\" name=\"calculates\">"
                + (failures == 0 ? "" : "<failure message=\"wrong result\"/>") + "</testcase></testsuite>");
    }

    @Test
    void shouldPauseOnModelFailureInsteadOfAcceptingCompletion() throws Exception {
        CommandRunner passing = (dir, command, timeout) -> {
            writeReport(0);
            return new CommandResult(0, false, "passed", 1, CommandResult.OutputCompleteness.UNKNOWN);
        };
        LLMClient failed = (messages, tools) -> { throw new java.io.IOException("offline failure"); };

        assertNotEquals(0, cli(failed, passing).startManagedTask("修复", null));
        assertTrue(text().contains("Paused"));
        assertTrue(text().contains("LLM 调用失败"));
    }

    private CommandRunner passingRunner() {
        return (dir, command, timeout) -> {
            writeReport(0);
            return new CommandResult(0, false, "passed", 1, CommandResult.OutputCompleteness.UNKNOWN);
        };
    }

    @Test
    void shouldInvalidateVerifiedStatusAfterSourceChangesWithoutWritingState() throws Exception {
        assertEquals(0, cli(completedModel(), passingRunner()).startManagedTask("检查", null));
        String id = taskId();
        Path metadata = root.resolve(".agent/tasks").resolve(id).resolve("state.json");
        byte[] before = Files.readAllBytes(metadata);
        Files.writeString(root.resolve("src/main/java/App.java"), "changed after verification");
        output.reset();

        assertEquals(0, cli(null, passingRunner()).showTask(id));
        assertTrue(text().contains("Paused"), text());
        assertTrue(text().contains("版本"), text());
        assertFalse(text().contains("状态: Verified"), text());
        assertArrayEquals(before, Files.readAllBytes(metadata));
    }

    @ParameterizedTest
    @ValueSource(strings = {"missing", "stale", "zero", "skipped", "malformed", "nonzero", "timeout",
            "identity", "changed", "duplicate", "counts"})
    void shouldRejectInvalidCurrentAcceptanceEvidence(String failure) throws Exception {
        writeReport(0);
        int[] invocation = {0};
        CommandRunner runner = (dir, command, timeout) -> {
            if (++invocation[0] == 1) { writeReport(0); }
            else {
                Path report = root.resolve("target/surefire-reports/TEST-AppTest.xml");
                switch (failure) {
                    case "missing", "stale" -> { }
                    case "zero" -> Files.writeString(report,
                            "<testsuite tests=\"0\" failures=\"0\" errors=\"0\" skipped=\"0\"/>");
                    case "malformed" -> Files.writeString(report, "<broken");
                    default -> {
                        writeReport(0);
                        String xml = Files.readString(report);
                        if (failure.equals("skipped")) {
                            xml = xml.replace("skipped=\"0\"", "skipped=\"1\"")
                                    .replace("</testcase>", "<skipped/></testcase>");
                        }
                        if (failure.equals("identity")) { xml = xml.replace("calculates", "anotherTest"); }
                        if (failure.equals("counts")) { xml = xml.replace("tests=\"1\"", "tests=\"2\""); }
                        Files.writeString(report, xml);
                        if (failure.equals("duplicate")) {
                            Files.writeString(report.resolveSibling("TEST-Duplicate.xml"), xml);
                        }
                        if (failure.equals("changed")) {
                            Files.writeString(root.resolve("src/main/java/App.java"), "concurrent change");
                        }
                    }
                }
            }
            boolean timedOut = invocation[0] == 2 && failure.equals("timeout");
            return new CommandResult(timedOut ? null : failure.equals("nonzero") && invocation[0] == 2 ? 7 : 0,
                    timedOut, "", 1, CommandResult.OutputCompleteness.UNKNOWN);
        };

        assertNotEquals(0, cli(completedModel(), runner).startManagedTask("独立验收", null), failure);
        assertTrue(text().contains("Paused"), text());
    }

    @Test
    void shouldRejectConcurrentManagedWriter() throws Exception {
        boolean[] attempted = {false};
        FakeLLMClient secondModel = completedModel();
        CommandRunner runner = (dir, command, timeout) -> {
            if (!attempted[0]) {
                attempted[0] = true;
                assertNotEquals(0, cli(secondModel, passingRunner()).startManagedTask("第二个任务", null));
                assertEquals(0, secondModel.callCount());
                assertTrue(errors.toString(StandardCharsets.UTF_8).contains("加锁"));
            }
            writeReport(0);
            return new CommandResult(0, false, "", 1, CommandResult.OutputCompleteness.UNKNOWN);
        };
        assertEquals(0, cli(completedModel(), runner).startManagedTask("第一个任务", null));
        assertTrue(attempted[0]);
    }

    @Test
    void shouldRejectCopiedProjectState() throws Exception {
        assertEquals(0, cli(completedModel(), passingRunner()).startManagedTask("检查", null));
        String id = taskId();
        List<Path> originalFiles;
        try (var paths = Files.walk(root)) { originalFiles = paths.toList(); }
        Path original = root;
        Path copy = Files.createDirectory(root.resolve("copied-project"));
        for (Path source : originalFiles) {
            Path destination = copy.resolve(original.relativize(source));
            if (Files.isDirectory(source)) { Files.createDirectories(destination); }
            else { Files.copy(source, destination); }
        }
        root = copy;
        try {
            output.reset();
            assertNotEquals(0, cli(null, passingRunner()).showTask(id));
            assertTrue(errors.toString(StandardCharsets.UTF_8).contains("其他项目"));
        } finally { root = original; }
    }

    @Test
    void shouldReportCorruptStateWithoutExecutingAnything() throws Exception {
        assertEquals(0, cli(completedModel(), passingRunner()).startManagedTask("检查", null));
        String id = taskId();
        Files.writeString(root.resolve(".agent/tasks").resolve(id).resolve("state.json"), "{broken");

        assertNotEquals(0, cli(null, passingRunner()).showTask(id));
    }

    @Test
    void shouldStopBeforeModelWhenTaskStorageFails() throws Exception {
        Files.createDirectories(root.resolve(".agent"));
        Files.writeString(root.resolve(".agent/tasks"), "not a directory");
        FakeLLMClient model = completedModel();
        CommandRunner never = (dir, command, timeout) -> { throw new AssertionError("存储失败不得继续"); };

        assertNotEquals(0, cli(model, never).startManagedTask("检查", null));
        assertEquals(0, model.callCount());
        assertTrue(errors.toString(StandardCharsets.UTF_8).contains("持久化"));
    }

    @Test
    void shouldStopBeforeNextToolWhenTaskChatCannotBeRecorded() throws Exception {
        CommandRunner runner = (dir, command, timeout) -> {
            if (command.equals("mvn test")) { writeReport(0); }
            else {
                Path sessions = root.resolve(".agent/sessions");
                try (var paths = Files.list(sessions)) {
                    Path chat = paths.filter(path -> path.toString().endsWith(".jsonl")).findFirst().orElseThrow();
                    Files.delete(chat);
                    Files.createDirectory(chat);
                }
            }
            return new CommandResult(0, false, "", 1, CommandResult.OutputCompleteness.UNKNOWN);
        };
        FakeLLMClient model = new FakeLLMClient(List.of(new LLMResponse(null, List.of(
                new ToolCall("first", new ToolCall.Function("run_command", "{\"command\":\"echo noop\"}")),
                new ToolCall("next", new ToolCall.Function("write_file",
                        "{\"path\":\"must-not-exist.txt\",\"content\":\"wrong\"}"))), 1, 1)));

        assertNotEquals(0, cli(model, runner).startManagedTask("检查", null));
        assertFalse(Files.exists(root.resolve("must-not-exist.txt")));
        assertTrue(errors.toString(StandardCharsets.UTF_8).contains("持久化"));
    }

    @Test
    void shouldRejectMissingModelAndKeepEmptyQueriesReadOnly() throws Exception {
        CodingAgentCli query = cli(null, (dir, command, timeout) -> { throw new AssertionError("不能执行命令"); });
        assertEquals(0, query.listTasks());
        assertFalse(Files.exists(root.resolve(".agent")));
        assertEquals(2, query.startManagedTask("检查", null));
        assertNotEquals(0, query.showTask("../outside"));
        assertNotEquals(0, query.showTask("00000000-0000-0000-0000-000000000000"));
        assertFalse(Files.exists(root.resolve(".agent")));
    }

    @Test
    void shouldPauseAtModelRequestLimitWithoutFinalVerification() throws Exception {
        ToolCall read = new ToolCall("read", new ToolCall.Function("read_file", "{\"path\":\"pom.xml\"}"));
        LLMResponse response = new LLMResponse(null, List.of(read), 1, 1);
        FakeLLMClient looping = new FakeLLMClient(java.util.Collections.nCopies(4, response));

        assertNotEquals(0, cli(looping, passingRunner()).startManagedTask("未完成", null));
        assertTrue(text().contains("Paused"), text());
        assertTrue(text().contains("最大步数"), text());
        assertFalse(text().contains("final:"), text());
    }

    @Test
    void shouldRejectEmptyModelCompletion() throws Exception {
        FakeLLMClient blank = new FakeLLMClient(List.of(new LLMResponse("", null, 0, 0)));
        assertNotEquals(0, cli(blank, passingRunner()).startManagedTask("检查", null));
        assertTrue(text().contains("没有给出完成声明"), text());
    }

    @Test
    void shouldUseAgreedCustomCommand() throws Exception {
        CommandRunner runner = (dir, command, timeout) -> {
            assertEquals("mvn -q test", command);
            writeReport(0);
            return new CommandResult(0, false, "", 1, CommandResult.OutputCompleteness.UNKNOWN);
        };
        assertEquals(0, cli(completedModel(), runner).startManagedTask("检查", "mvn -q test"));
        assertTrue(text().contains("验收: mvn -q test"));
    }

    @Test
    void shouldKeepTaskChatsOutOfAutomaticChatRestoration() throws Exception {
        assertEquals(0, cli(completedModel(), passingRunner()).startManagedTask("专属任务目标", null));
        String id = taskId();
        Path sessions = root.resolve(".agent/sessions");
        com.agent.session.SessionStore chat = com.agent.session.SessionStore.start(sessions,
                com.agent.session.SessionStore.Mode.AUTO, null);
        assertFalse(chat.sessionId().startsWith("task-"));
        assertTrue(chat.loadMessages().messages().isEmpty());
        assertFalse(com.agent.session.SessionStore.start(sessions,
                com.agent.session.SessionStore.Mode.RESUME, "task-" + id).loadMessages().messages().isEmpty());
    }

    @ParameterizedTest
    @ValueSource(strings = {"missing", "malformed", "skipped", "changed"})
    void shouldPauseBeforeCodingWhenBaselineHasNoUsableReports(String failure) throws Exception {
        FakeLLMClient model = completedModel();
        CommandRunner unavailable = (dir, command, timeout) -> {
            Path report = root.resolve("target/surefire-reports/TEST-AppTest.xml");
            if (!failure.equals("missing")) {
                writeReport(0);
                if (failure.equals("malformed")) { Files.writeString(report, "broken xml"); }
                if (failure.equals("skipped")) {
                    Files.writeString(report, Files.readString(report).replace("skipped=\"0\"", "skipped=\"1\"")
                            .replace("</testcase>", "<skipped/></testcase>"));
                }
                if (failure.equals("changed")) {
                    Files.writeString(root.resolve("src/main/java/App.java"), "changed during baseline");
                }
            }
            return new CommandResult(failure.equals("missing") ? 127 : 0, false, "", 1,
                    CommandResult.OutputCompleteness.UNKNOWN);
        };

        assertNotEquals(0, cli(model, unavailable).startManagedTask("修复", null));
        assertEquals(0, model.callCount());
        assertTrue(text().contains("Paused"), text());
    }

    @ParameterizedTest
    @ValueSource(strings = {"main", "test"})
    void shouldTrackSourcePackagesNamedTarget(String area) throws Exception {
        Path sourcePackage = Files.createDirectories(root.resolve("src/" + area + "/java/example/target"));
        Path source = Files.writeString(sourcePackage.resolve("Important.java"), "original");
        assertEquals(0, cli(completedModel(), passingRunner()).startManagedTask("检查", null));
        String id = taskId();
        Files.writeString(source, "changed after verification");
        output.reset();

        assertEquals(0, cli(null, passingRunner()).showTask(id));
        assertTrue(text().contains("状态: Paused"), text());
        assertTrue(text().contains("版本"), text());
    }

    @Test
    void shouldLeaveReportFixturesInSourceUntouched() throws Exception {
        Path fixtures = Files.createDirectories(root.resolve("src/test/resources/target/surefire-reports"));
        Path fixture = Files.writeString(fixtures.resolve("TEST-Fixture.xml"), "source fixture, not an execution report");

        assertEquals(0, cli(completedModel(), passingRunner()).startManagedTask("检查", null));
        assertEquals("source fixture, not an execution report", Files.readString(fixture));
    }
}
