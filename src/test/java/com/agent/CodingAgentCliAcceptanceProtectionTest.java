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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/** 从 CLI 和真实临时文件验证保护边界；模型和进程是唯一离线替身。 */
class CodingAgentCliAcceptanceProtectionTest {
    @TempDir Path root;
    private final ByteArrayOutputStream output = new ByteArrayOutputStream();
    private final ByteArrayOutputStream errors = new ByteArrayOutputStream();
    private static final String TEST = "src/test/java/AppTest.java";

    @BeforeEach void project() throws IOException {
        Files.writeString(root.resolve("pom.xml"), "<project/>");
        Files.createDirectories(root.resolve("src/main/java"));
        Files.createDirectories(root.resolve("src/test/java"));
        Files.writeString(root.resolve("src/main/java/App.java"), "broken");
        Files.writeString(root.resolve(TEST), "original acceptance");
    }

    private CodingAgentCli cli(LLMClient model, CommandRunner runner, BufferedReader input) {
        return new CodingAgentCli(root, model, true, 6, input,
                new PrintStream(output, true, StandardCharsets.UTF_8),
                new PrintStream(errors, true, StandardCharsets.UTF_8), null, runner);
    }

    private CodingAgentCli cli(LLMClient model, CommandRunner runner) {
        return cli(model, runner, new BufferedReader(new StringReader("")));
    }

    private String text() { return output.toString(StandardCharsets.UTF_8); }
    private String taskId() {
        var match = Pattern.compile("任务: ([0-9a-f-]{36})").matcher(text());
        assertTrue(match.find(), text() + errors.toString(StandardCharsets.UTF_8));
        return match.group(1);
    }
    private static ToolCall write(String id, String path, String content) {
        return new ToolCall(id, new ToolCall.Function("write_file",
                "{\"path\":\"" + path + "\",\"content\":\"" + content + "\"}"));
    }
    private FakeLLMClient model(ToolCall... calls) {
        return new FakeLLMClient(List.of(new LLMResponse(null, List.of(calls), 1, 1),
                new LLMResponse("完成", null, 1, 1)));
    }
    private void report(boolean passed) throws IOException {
        Path dir = Files.createDirectories(root.resolve("target/surefire-reports"));
        Files.writeString(dir.resolve("TEST-AppTest.xml"), "<testsuite tests=\"1\" failures=\""
                + (passed ? 0 : 1) + "\" errors=\"0\" skipped=\"0\"><testcase classname=\"AppTest\" name=\"checks\">"
                + (passed ? "" : "<failure/>") + "</testcase></testsuite>");
    }
    private CommandRunner checks() {
        return (dir, command, timeout) -> {
            boolean passed = Files.readString(root.resolve("src/main/java/App.java")).equals("fixed");
            report(passed);
            return new CommandResult(passed ? 0 : 1, false, "", 1, CommandResult.OutputCompleteness.UNKNOWN);
        };
    }

    @Test void shouldBlockWritingAnExistingAcceptanceTest() throws Exception {
        FakeLLMClient model = model(write("weaken", TEST, "weakened acceptance"));

        assertNotEquals(0, cli(model, checks()).startManagedTask("修复", null));
        assertEquals("original acceptance", Files.readString(root.resolve(TEST)));
        assertTrue(model.requests().get(1).stream().anyMatch(message -> "tool".equals(message.role())
                && message.content().contains("验收")), model.requests().toString());
    }

    @Test void shouldPauseImmediatelyAfterCommandChangesProtectedInputs() throws Exception {
        int[] acceptanceRuns = {0};
        CommandRunner runner = (dir, command, timeout) -> {
            if (command.equals("mvn test")) { acceptanceRuns[0]++; return checks().run(dir, command, timeout); }
            Files.writeString(root.resolve(TEST), "weakened by command");
            return new CommandResult(0, false, "done", 1, CommandResult.OutputCompleteness.UNKNOWN);
        };
        FakeLLMClient model = model(new ToolCall("command", new ToolCall.Function("run_command",
                "{\"command\":\"echo change\"}")), write("next", "src/main/java/App.java", "fixed"));

        assertNotEquals(0, cli(model, runner).startManagedTask("修复", null));
        assertEquals("broken", Files.readString(root.resolve("src/main/java/App.java")));
        assertEquals(1, acceptanceRuns[0]);
        assertTrue(text().contains("Paused"), text());
        assertTrue(text().contains(TEST), text());
    }

    private String verifiedTask() throws IOException {
        Files.writeString(root.resolve("src/main/java/App.java"), "fixed");
        FakeLLMClient done = new FakeLLMClient(List.of(new LLMResponse("完成", null, 1, 1)));
        assertEquals(0, cli(done, checks()).startManagedTask("检查", null));
        return taskId();
    }

    @Test void shouldRequireExplicitConfirmationDespiteYes() throws Exception {
        String id = verifiedTask();
        Path state = root.resolve(".agent/tasks").resolve(id).resolve("state.json");
        byte[] before = Files.readAllBytes(state);
        CommandRunner never = (dir, command, timeout) -> { throw new AssertionError("未经明确确认不能执行"); };
        CodingAgentCli confirm = cli(null, never, new BufferedReader(new StringReader("y\n")));

        assertNotEquals(0, confirm.reagreeAcceptance(id, "mvn -q test", true));
        assertArrayEquals(before, Files.readAllBytes(state));
        assertTrue(text().contains("approve " + id), text());
    }

    @Test void shouldSaveNewAgreementAndBaselineWithoutReusingOldSuccess() throws Exception {
        String id = verifiedTask();
        String oldOutput = text();
        Files.writeString(root.resolve(TEST), "legitimately revised acceptance");
        output.reset();
        CommandRunner runner = (dir, command, timeout) -> {
            assertEquals("mvn -q test", command);
            ByteArrayOutputStream checkpoint = new ByteArrayOutputStream();
            CodingAgentCli query = new CodingAgentCli(root, null, false, 1,
                    new BufferedReader(new StringReader("")), new PrintStream(checkpoint, true, StandardCharsets.UTF_8),
                    System.err, null, checks());
            assertEquals(0, query.showTask(id));
            String state = checkpoint.toString(StandardCharsets.UTF_8);
            assertTrue(state.contains("状态: Paused"), state);
            assertTrue(state.contains("旧验证已失效"), state);
            return checks().run(dir, command, timeout);
        };
        CodingAgentCli approving = cli(null, runner, new BufferedReader(new StringReader("approve " + id + "\n")));

        assertEquals(1, approving.reagreeAcceptance(id, "mvn -q test", true));
        assertTrue(text().contains("历史约定:"), text());
        assertTrue(text().contains("baseline: PASSED"), text());
        assertFalse(text().contains("final: PASSED"), text());
        assertFalse(text().contains("状态: Verified"), text());
        var versions = Pattern.compile("验收版本: ([0-9a-f]+)");
        var oldVersion = versions.matcher(oldOutput); assertTrue(oldVersion.find());
        var newVersion = versions.matcher(text()); assertTrue(newVersion.find());
        assertNotEquals(oldVersion.group(1), newVersion.group(1));
        output.reset();
        assertEquals(0, cli(null, checks()).showTask(id));
        assertTrue(text().contains("历史约定:"));
        assertTrue(text().contains("状态: Paused"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"pom.xml", ".mvn/maven.config", "mvnw.cmd", "mvnw", ".agent/forged-state.json",
            "src/test/java/../java/AppTest.java"})
    void shouldBlockProtectedBuildAndMetadataWrites(String path) throws Exception {
        FakeLLMClient model = model(write("edit", path, "weakened"));
        cli(model, checks()).startManagedTask("修复", null);
        assertEquals("original acceptance", Files.readString(root.resolve(TEST)));
        assertEquals("<project/>", Files.readString(root.resolve("pom.xml")));
        if (!path.equals("pom.xml") && !path.contains("AppTest")) { assertFalse(Files.exists(root.resolve(path))); }
        assertTrue(model.requests().get(1).stream().anyMatch(message -> "tool".equals(message.role())
                && message.content().contains("受保护")), model.requests().toString());
    }

    @Test void shouldPermitAdditionalTestsAlongsideExistingIdentities() throws Exception {
        FakeLLMClient model = model(write("add", "src/test/java/NewTest.java", "additional coverage"),
                write("fix", "src/main/java/App.java", "fixed"));
        CommandRunner runner = (dir, command, timeout) -> {
            CommandResult result = checks().run(dir, command, timeout);
            if (Files.exists(root.resolve("src/test/java/NewTest.java"))) {
                Files.writeString(root.resolve("target/surefire-reports/TEST-NewTest.xml"),
                        "<testsuite tests=\"1\" failures=\"0\" errors=\"0\" skipped=\"0\">"
                        + "<testcase classname=\"NewTest\" name=\"additional\"/></testsuite>");
            }
            return result;
        };
        assertEquals(0, cli(model, runner).startManagedTask("修复并补测试", null));
        assertEquals("original acceptance", Files.readString(root.resolve(TEST)));
        assertEquals("additional coverage", Files.readString(root.resolve("src/test/java/NewTest.java")));
        assertTrue(text().contains("final: PASSED，测试数=2"), text());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "no\n", "approve wrong-id\n"})
    void shouldLeaveAgreementUntouchedWithoutExactApproval(String answer) throws Exception {
        String id = verifiedTask();
        Path state = root.resolve(".agent/tasks").resolve(id).resolve("state.json");
        byte[] before = Files.readAllBytes(state);
        CommandRunner never = (dir, command, timeout) -> { throw new AssertionError("不得运行基线"); };
        assertEquals(1, cli(null, never, new BufferedReader(new StringReader(answer)))
                .reagreeAcceptance(id, "mvn test", true));
        assertArrayEquals(before, Files.readAllBytes(state));
    }

    @Test void shouldRefuseNoninteractiveApprovalWithoutReadingInput() throws Exception {
        String id = verifiedTask();
        Path state = root.resolve(".agent/tasks").resolve(id).resolve("state.json");
        byte[] before = Files.readAllBytes(state);
        BufferedReader input = new BufferedReader(new StringReader("approve " + id)) {
            @Override public String readLine() { throw new AssertionError("非交互不能消费批准输入"); }
        };
        CommandRunner never = (dir, command, timeout) -> { throw new AssertionError("不得运行基线"); };
        assertEquals(1, cli(null, never, input).reagreeAcceptance(id, "mvn test", false));
        assertArrayEquals(before, Files.readAllBytes(state));
    }

    @Test void shouldRejectProposalThatChangesDuringConfirmation() throws Exception {
        String id = verifiedTask();
        Path state = root.resolve(".agent/tasks").resolve(id).resolve("state.json");
        byte[] before = Files.readAllBytes(state);
        BufferedReader input = new BufferedReader(new StringReader("")) {
            @Override public String readLine() throws IOException {
                Files.writeString(root.resolve(TEST), "changed while confirming");
                return "approve " + id;
            }
        };
        CommandRunner never = (dir, command, timeout) -> { throw new AssertionError("不得运行基线"); };
        assertEquals(1, cli(null, never, input).reagreeAcceptance(id, "mvn test", true));
        assertArrayEquals(before, Files.readAllBytes(state));
        assertTrue(text().contains("确认期间"), text());
    }

    @Test void shouldKeepOldSuccessInvalidWhenNewBaselineFails() throws Exception {
        String id = verifiedTask();
        Files.writeString(root.resolve("src/main/java/App.java"), "broken again");
        output.reset();
        assertEquals(1, cli(null, checks(), new BufferedReader(new StringReader("approve " + id + "\n")))
                .reagreeAcceptance(id, "mvn test", true));
        assertTrue(text().contains("baseline: FAILED"), text());
        assertTrue(text().contains("历史约定:"), text());
        assertFalse(text().contains("状态: Verified"), text());
    }

    @Test void shouldRejectChangedInputsBeforeStartingAnOrdinaryCommand() throws Exception {
        LLMClient externallyChanged = (messages, tools) -> {
            Files.writeString(root.resolve(TEST), "changed before command");
            return new LLMResponse(null, List.of(new ToolCall("cmd", new ToolCall.Function("run_command",
                    "{\"command\":\"echo noop\"}"))), 1, 1);
        };
        CommandRunner baselineOnly = (dir, command, timeout) -> {
            assertEquals("mvn test", command, "保护输入变化后不得执行普通命令");
            return checks().run(dir, command, timeout);
        };
        assertEquals(1, cli(externallyChanged, baselineOnly).startManagedTask("修复", null));
        assertTrue(text().contains("Paused"), text());
    }

    @ParameterizedTest
    @ValueSource(strings = {"delete", "build", "new-build"})
    void shouldPauseForCommandDeletionAndBuildChanges(String action) throws Exception {
        CommandRunner runner = (dir, command, timeout) -> {
            if (command.equals("mvn test")) { return checks().run(dir, command, timeout); }
            switch (action) {
                case "delete" -> Files.delete(root.resolve(TEST));
                case "build" -> Files.writeString(root.resolve("pom.xml"), "changed build");
                case "new-build" -> {
                    Files.createDirectories(root.resolve(".mvn"));
                    Files.writeString(root.resolve(".mvn/maven.config"), "-DskipTests");
                }
                default -> throw new AssertionError(action);
            }
            return new CommandResult(0, false, "", 1, CommandResult.OutputCompleteness.UNKNOWN);
        };
        assertEquals(1, cli(model(new ToolCall("cmd", new ToolCall.Function("run_command",
                "{\"command\":\"echo mutate\"}"))), runner).startManagedTask("修复", null));
        assertTrue(text().contains("Paused"), text());
        assertFalse(text().contains("final:"), text());
    }

    @Test void shouldBlockAppendingToExistingAcceptanceTest() throws Exception {
        ToolCall append = new ToolCall("append", new ToolCall.Function("write_file",
                "{\"path\":\"" + TEST + "\",\"content\":\"skip tests\",\"append\":true}"));
        cli(model(append), checks()).startManagedTask("修复", null);
        assertEquals("original acceptance", Files.readString(root.resolve(TEST)));
    }

    @Test void shouldReadAndReagreeAnOldSnapshotWithoutImplicitMigration() throws Exception {
        String id = verifiedTask();
        Path state = root.resolve(".agent/tasks").resolve(id).resolve("state.json");
        var json = new com.fasterxml.jackson.databind.ObjectMapper();
        var legacy = (com.fasterxml.jackson.databind.node.ObjectNode) json.readTree(Files.readAllBytes(state));
        legacy.put("schemaVersion", 1);
        legacy.remove("agreementHistory");
        Files.write(state, json.writeValueAsBytes(legacy));
        byte[] before = Files.readAllBytes(state);
        output.reset();
        assertEquals(0, cli(null, checks()).showTask(id));
        assertTrue(text().contains("状态: Verified"), text());
        assertArrayEquals(before, Files.readAllBytes(state));
        output.reset();
        assertEquals(1, cli(null, checks(), new BufferedReader(new StringReader("approve " + id + "\n")))
                .reagreeAcceptance(id, "mvn test", true));
        assertTrue(text().contains("历史约定:"), text());
    }

    @Test void shouldFailClosedWhenStorageBreaksAfterAgreementInvalidation() throws Exception {
        String id = verifiedTask();
        CommandRunner breakStorage = (dir, command, timeout) -> {
            report(true);
            Path state = root.resolve(".agent/tasks").resolve(id).resolve("state.json");
            Files.delete(state);
            Files.createDirectory(state);
            return new CommandResult(0, false, "", 1, CommandResult.OutputCompleteness.UNKNOWN);
        };
        output.reset();
        assertEquals(4, cli(null, breakStorage, new BufferedReader(new StringReader("approve " + id + "\n")))
                .reagreeAcceptance(id, "mvn test", true));
        output.reset();
        assertNotEquals(0, cli(null, checks()).showTask(id));
        assertFalse(text().contains("状态: Verified"), text());
    }

    @Test void shouldNotReplaceRequiredIdentityWithMorePassingTests() throws Exception {
        CommandRunner runner = (dir, command, timeout) -> {
            CommandResult result = checks().run(dir, command, timeout);
            if (result.success()) {
                Files.delete(root.resolve("target/surefire-reports/TEST-AppTest.xml"));
                Files.writeString(root.resolve("target/surefire-reports/TEST-Extra.xml"),
                        "<testsuite tests=\"2\" failures=\"0\" errors=\"0\" skipped=\"0\">"
                        + "<testcase classname=\"Extra\" name=\"one\"/>"
                        + "<testcase classname=\"Extra\" name=\"two\"/></testsuite>");
            }
            return result;
        };
        assertEquals(1, cli(model(write("fix", "src/main/java/App.java", "fixed")), runner)
                .startManagedTask("修复", null));
        assertTrue(text().contains("缺少先前测试身份"), text());
    }

    @Test void shouldRejectReagreementWhileAnotherManagedWriterIsActive() throws Exception {
        String id = verifiedTask();
        FakeLLMClient done = new FakeLLMClient(List.of(new LLMResponse("完成", null, 1, 1)));
        CommandRunner runner = (dir, command, timeout) -> {
            CommandRunner never = (otherDir, otherCommand, otherTimeout) -> { throw new AssertionError("不得并发执行"); };
            assertEquals(4, cli(null, never).reagreeAcceptance(id, "mvn test", false));
            return checks().run(dir, command, timeout);
        };
        assertEquals(0, cli(done, runner).startManagedTask("另一个任务", null));
    }

    @Test void shouldNotExecuteFinalAcceptanceAgainstAlreadyChangedBuildInputs() throws Exception {
        int[] executions = {0};
        CommandRunner runner = (dir, command, timeout) -> {
            executions[0]++;
            return checks().run(dir, command, timeout);
        };
        LLMClient changedBeforeCompletion = (messages, tools) -> {
            Files.writeString(root.resolve("pom.xml"), "changed before final acceptance");
            return new LLMResponse("完成", null, 1, 1);
        };
        assertEquals(1, cli(changedBeforeCompletion, runner).startManagedTask("修复", null));
        assertEquals(1, executions[0], "检测到构建输入变化后不得运行最终验收命令");
        assertTrue(text().contains("Paused"), text());
    }

    @Test void shouldRejectPipedApprovalInActualCliProcess() throws Exception {
        String id = verifiedTask();
        Path state = root.resolve(".agent/tasks").resolve(id).resolve("state.json");
        byte[] before = Files.readAllBytes(state);
        Path log = root.resolve("target/child-cli.log");
        boolean windows = System.getProperty("os.name", "").toLowerCase().contains("win");
        Path javaExecutable = Path.of(System.getProperty("java.home"), "bin", windows ? "java.exe" : "java");
        Process child = new ProcessBuilder(javaExecutable.toString(), "-Dagent.encoding=UTF-8",
                "-Djava.io.tmpdir=" + root.resolve("target"), "-cp", System.getProperty("java.class.path"),
                "com.agent.Main", "--root", root.toString(), "--reagree-acceptance", id,
                "--accept-command", "echo unapproved > bypass-marker.txt", "--yes")
                .directory(root.toFile()).redirectErrorStream(true).redirectOutput(log.toFile()).start();
        try {
            try (var input = child.getOutputStream()) {
                input.write(("approve " + id + "\n").getBytes(StandardCharsets.UTF_8));
            }
            assertTrue(child.waitFor(15, java.util.concurrent.TimeUnit.SECONDS), "CLI 未及时退出");
            assertEquals(1, child.exitValue(), Files.readString(log));
            assertTrue(Files.readString(log).contains("非交互运行不能批准"), Files.readString(log));
            assertArrayEquals(before, Files.readAllBytes(state));
            assertFalse(Files.exists(root.resolve("bypass-marker.txt")));
        } finally {
            if (child.isAlive()) { child.destroyForcibly().waitFor(); }
        }
    }
}
