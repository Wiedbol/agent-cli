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

/** 经公共 CLI 验证真实文件、模型协议与持久化后的可见行为。 */
class CodingAgentCliBoundedRepairTest {
    @TempDir Path root;
    private final ByteArrayOutputStream output = new ByteArrayOutputStream();

    @BeforeEach
    void project() throws Exception {
        Files.writeString(root.resolve("pom.xml"), "<project/>");
        Files.createDirectories(root.resolve("src/main/java"));
        Files.writeString(root.resolve("src/main/java/App.java"), "broken");
    }

    private CodingAgentCli cli(LLMClient client, CommandRunner runner, int budget) {
        PrintStream stream = new PrintStream(output, true, StandardCharsets.UTF_8);
        return new CodingAgentCli(root, client, true, budget,
                new BufferedReader(new StringReader("")), stream, stream, null, runner);
    }

    private String text() { return output.toString(StandardCharsets.UTF_8); }

    private String taskId() {
        var matcher = Pattern.compile("任务: ([0-9a-f-]{36})").matcher(text());
        assertTrue(matcher.find(), text());
        return matcher.group(1);
    }

    private void report(boolean passed, String failure) throws java.io.IOException {
        Path directory = Files.createDirectories(root.resolve("target/surefire-reports"));
        Files.writeString(directory.resolve("TEST-AppTest.xml"),
                "<testsuite tests=\"1\" failures=\"" + (passed ? 0 : 1)
                + "\" errors=\"0\" skipped=\"0\"><testcase classname=\"AppTest\" name=\"calculates\">"
                + (passed ? "" : "<failure message=\"" + failure + "\"/>") + "</testcase></testsuite>");
    }

    private CommandRunner acceptance() {
        return (dir, command, timeout) -> {
            boolean fixed = Files.readString(root.resolve("src/main/java/App.java")).equals("fixed");
            report(fixed, "expected 2 but was 3");
            return new CommandResult(fixed ? 0 : 1, false, "real test output", 5,
                    CommandResult.OutputCompleteness.COMPLETE);
        };
    }

    private LLMResponse edit(String id, String content) {
        return new LLMResponse(null, List.of(new ToolCall(id, new ToolCall.Function("write_file",
                "{\"path\":\"src/main/java/App.java\",\"content\":\"" + content + "\"}"))), 2, 3);
    }

    private LLMResponse done() { return new LLMResponse("请验收", null, 1, 1); }

    @Test
    void shouldFeedFailedAcceptanceBackUntilCurrentTestsPass() throws Exception {
        FakeLLMClient model = new FakeLLMClient(List.of(edit("wrong-edit", "still broken"), done(),
                edit("correct-edit", "fixed"), done()));

        assertEquals(0, cli(model, acceptance(), 12).startManagedTask("修复计算", null), text());
        assertEquals("fixed", Files.readString(root.resolve("src/main/java/App.java")));
        assertEquals(4, model.callCount());
        assertTrue(model.requests().get(2).stream().anyMatch(m -> m.content() != null
                && m.content().contains("expected 2 but was 3") && m.content().contains("AppTest#calculates")));
        String id = taskId();
        output.reset();
        assertEquals(0, cli(null, acceptance(), 12).showTask(id));
        assertTrue(text().contains("状态: Verified"), text());
        assertTrue(text().contains("final: FAILED"), text());
        assertTrue(text().contains("final: PASSED"), text());
    }

    @Test
    void shouldLimitTotalRequestsAndExposePersistedConsumption() throws Exception {
        FakeLLMClient model = new FakeLLMClient(java.util.stream.IntStream.range(0, 20)
                .mapToObj(i -> new LLMResponse(null, List.of(new ToolCall("read-" + i,
                        new ToolCall.Function("read_file", "{\"path\":\"src/main/java/App.java\"}"))), 2, 3)).toList());
        CommandRunner passing = (dir, command, timeout) -> {
            report(true, "");
            return new CommandResult(0, false, "passed", 5, CommandResult.OutputCompleteness.COMPLETE);
        };

        assertEquals(1, cli(model, passing, 12).startManagedTask("检查", null));
        assertEquals(12, model.callCount());
        String id = taskId();
        output.reset();
        assertEquals(0, cli(null, passing, 12).showTask(id));
        assertTrue(text().contains("模型请求: 12/12"), text());
        assertTrue(text().contains("工具调用: 12"), text());
        assertTrue(text().contains("token: 输入=24，输出=36，未知请求=0"), text());
        assertTrue(text().contains("read-11"), text());
    }

    @Test
    void shouldReserveBeforeNetworkFailureAndKeepUnknownUsage() throws Exception {
        LLMClient failed = (messages, tools) -> {
            assertEquals(0, cli(null, acceptance(), 12).showTask(taskId()));
            assertTrue(text().contains("模型请求: 1/12"), text());
            assertTrue(text().contains("RESERVED"), text());
            throw new java.io.IOException("network unavailable");
        };

        assertEquals(1, cli(failed, acceptance(), 12).startManagedTask("修复", null));
        String id = taskId();
        output.reset();
        assertEquals(0, cli(null, acceptance(), 12).showTask(id));
        assertTrue(text().contains("模型请求: 1/12"), text());
        assertTrue(text().contains("未知请求=1"), text());
        assertTrue(text().contains("MODEL_FAILED"), text());
    }

    @Test
    void shouldPauseAfterThreeRoundsOfSameErrorDespiteUnrelatedSuccess() throws Exception {
        int[] check = {0};
        CommandRunner runner = (dir, command, timeout) -> {
            report(false, "expected 2 but was 3 at /tmp/junit" + (++check[0])
                    + "/cache 2026-10-22T12:30:0" + check[0]);
            return new CommandResult(1, false, "failed", 1, CommandResult.OutputCompleteness.COMPLETE);
        };
        FakeLLMClient model = new FakeLLMClient(java.util.stream.IntStream.range(0, 10)
                .mapToObj(i -> new LLMResponse(null, List.of(new ToolCall("unrelated-" + i,
                        new ToolCall.Function("read_file", "{\"path\":\"pom.xml\"}"))), 1, 1)).toList());

        assertEquals(1, cli(model, runner, 12).startManagedTask("修复", null));
        assertEquals(3, model.callCount());
        assertTrue(text().contains("同一核心错误持续三个决策轮次"), text());
        assertTrue(check[0] >= 2, "暂停前应重新核验当前错误仍存在");
    }

    @Test
    void shouldIncreaseTotalBudgetWithoutResettingConsumptionOrExecuting() throws Exception {
        FakeLLMClient model = new FakeLLMClient(List.of(done()));
        assertEquals(1, cli(model, acceptance(), 1).startManagedTask("修复", null));
        String id = taskId();
        output.reset();
        CommandRunner forbidden = (dir, command, timeout) -> { throw new AssertionError("预算调整不能执行命令"); };
        assertEquals(0, cli(null, forbidden, 12).raiseTaskBudget(id, 3));
        output.reset();
        assertEquals(0, cli(null, forbidden, 12).showTask(id));
        assertTrue(text().contains("模型请求: 1/3"), text());
        assertTrue(text().contains("预算调整: 1 -> 3"), text());
        assertTrue(text().contains("状态: Paused"), text());
        assertEquals(1, model.callCount());
        assertNotEquals(0, cli(null, forbidden, 12).raiseTaskBudget(id, 1));
    }

    @Test
    void shouldRedactSensitivePathObservationsInTaskEvidence() throws Exception {
        String canary = "private material for offline test only";
        Files.writeString(root.resolve("fixture.pem"), canary);
        FakeLLMClient model = new FakeLLMClient(List.of(new LLMResponse(null,
                List.of(new ToolCall("sensitive-read", new ToolCall.Function("read_file",
                        "{\"path\":\"fixture.pem\"}"))), 1, 1), done()));
        cli(model, acceptance(), 2).startManagedTask("检查", null);
        try (var paths = Files.walk(root.resolve(".agent"))) {
            for (Path path : paths.filter(Files::isRegularFile).toList()) {
                assertFalse(Files.readString(path).contains(canary), "敏感路径内容不应进入持久化证据: " + path);
            }
        }
    }

    @Test
    void shouldKeepOneBudgetAcrossRepeatedAcceptanceCycles() throws Exception {
        int[] checks = {0};
        CommandRunner runner = (dir, command, timeout) -> {
            report(false, "expected 2 but was " + (++checks[0] + 2));
            return new CommandResult(1, false, "failed", 1, CommandResult.OutputCompleteness.COMPLETE);
        };
        FakeLLMClient model = new FakeLLMClient(List.of(done(), done(), done(), done()));
        assertEquals(1, cli(model, runner, 3).startManagedTask("修复", null));
        assertEquals(3, model.callCount());
        assertEquals(4, checks[0]);
        assertTrue(text().contains("累计模型请求预算已耗尽"), text());
        assertTrue(text().contains("模型请求: 3/3"), text());
    }

    @Test
    void shouldAllowFinalAcceptanceOnLastAvailableRequest() throws Exception {
        FakeLLMClient model = new FakeLLMClient(List.of(edit("last-edit", "fixed"), done()));
        assertEquals(0, cli(model, acceptance(), 2).startManagedTask("修复", null), text());
        assertEquals(2, model.callCount());
        assertTrue(text().contains("模型请求: 2/2"), text());
        assertTrue(text().contains("last-edit"), text());
        assertTrue(text().contains("修改=[src/main/java/App.java]"), text());
        assertTrue(text().contains("失败来源=baseline-"), text());
    }

    @Test
    void shouldStopBeforeEditingWhenResponseCannotBePersisted() throws Exception {
        FakeLLMClient scripted = new FakeLLMClient(List.of(edit("must-not-run", "fixed")));
        LLMClient model = (messages, tools) -> {
            Path previous = root.resolve(".agent/tasks").resolve(taskId()).resolve("state.previous.json");
            Files.delete(previous);
            Files.createDirectory(previous);
            return scripted.chat(messages, tools);
        };
        assertEquals(4, cli(model, acceptance(), 12).startManagedTask("修复", null));
        assertEquals("broken", Files.readString(root.resolve("src/main/java/App.java")));
        assertEquals(1, scripted.callCount());
    }

    @Test
    void shouldPauseOnRepeatedToolErrorDespitePassingUnrelatedRead() throws Exception {
        CommandRunner passing = (dir, command, timeout) -> {
            report(true, "");
            return new CommandResult(0, false, "passed", 1, CommandResult.OutputCompleteness.COMPLETE);
        };
        FakeLLMClient model = new FakeLLMClient(List.of(
                new LLMResponse(null, List.of(new ToolCall("missing-1", new ToolCall.Function("read_file",
                        "{\"path\":\"missing.java\"}"))), 1, 1),
                new LLMResponse(null, List.of(new ToolCall("unrelated", new ToolCall.Function("read_file",
                        "{\"path\":\"pom.xml\"}"))), 1, 1),
                new LLMResponse(null, List.of(new ToolCall("missing-2", new ToolCall.Function("read_file",
                        "{\"path\":\"missing.java\"}"))), 1, 1), done()));
        assertEquals(1, cli(model, passing, 12).startManagedTask("读取缺失文件", null));
        assertEquals(3, model.callCount());
        assertTrue(text().contains("同一核心错误持续三个决策轮次"), text());
    }

    @Test
    void shouldRejectDamagedConsumptionInsteadOfReadingDefaults() throws Exception {
        assertEquals(1, cli(new FakeLLMClient(List.of(done())), acceptance(), 1).startManagedTask("修复", null));
        String id = taskId();
        Path state = root.resolve(".agent/tasks").resolve(id).resolve("state.json");
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        var json = mapper.readTree(Files.readAllBytes(state));
        ((com.fasterxml.jackson.databind.node.ObjectNode) json.path("progress")).remove("requests");
        Files.write(state, mapper.writeValueAsBytes(json));
        output.reset();
        assertEquals(4, cli(null, acceptance(), 12).showTask(id));
        assertTrue(text().contains("损坏"), text());
    }

    @Test
    void shouldNotConfuseDifferentAbsolutePathsInsideTemporaryDirectory() throws Exception {
        CommandRunner passing = (dir, command, timeout) -> {
            report(true, "");
            return new CommandResult(0, false, "passed", 1, CommandResult.OutputCompleteness.COMPLETE);
        };
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        String missing = mapper.writeValueAsString(java.util.Map.of("path", root.resolve("missing.java").toString()));
        String existing = mapper.writeValueAsString(java.util.Map.of("path", root.resolve("pom.xml").toString()));
        FakeLLMClient model = new FakeLLMClient(List.of(
                new LLMResponse(null, List.of(new ToolCall("missing-absolute", new ToolCall.Function("read_file", missing))), 1, 1),
                new LLMResponse(null, List.of(new ToolCall("unrelated-absolute", new ToolCall.Function("read_file", existing))), 1, 1),
                new LLMResponse(null, List.of(new ToolCall("missing-again", new ToolCall.Function("read_file", missing))), 1, 1), done()));
        assertEquals(1, cli(model, passing, 12).startManagedTask("读取缺失文件", null), text());
        assertEquals(3, model.callCount());
    }

    @Test
    void shouldIncludeFailedBaselineTimeInPersistedElapsedTime() throws Exception {
        CommandRunner slow = (dir, command, timeout) -> {
            Thread.sleep(100);
            return new CommandResult(127, false, "maven missing", 100, CommandResult.OutputCompleteness.COMPLETE);
        };
        assertEquals(1, cli(new FakeLLMClient(List.of()), slow, 12).startManagedTask("修复", null));
        String id = taskId();
        output.reset();
        assertEquals(0, cli(null, slow, 12).showTask(id));
        var match = Pattern.compile("累计耗时: (\\d+) ms").matcher(text());
        assertTrue(match.find(), text());
        assertTrue(Long.parseLong(match.group(1)) >= 100, text());
    }

    @Test
    void shouldKeepOriginalFailureFeedbackInMemoryAndRedactAllPersistedCopies() throws Exception {
        String original = "expected credential token=offline-fixture-value";
        CommandRunner runner = (dir, command, timeout) -> {
            report(false, original);
            return new CommandResult(1, false, "failed", 1, CommandResult.OutputCompleteness.COMPLETE);
        };
        FakeLLMClient model = new FakeLLMClient(List.of(done()));
        assertEquals(1, cli(model, runner, 1).startManagedTask("修复", null));
        assertTrue(model.requests().get(0).stream().anyMatch(m -> m.content() != null && m.content().contains(original)));
        try (var paths = Files.walk(root.resolve(".agent"))) {
            for (Path path : paths.filter(Files::isRegularFile).toList()) {
                assertFalse(Files.readString(path).contains(original), "落盘副本应过滤: " + path);
            }
        }
    }

    @Test
    void shouldPauseWithoutReadingConfirmationInNonInteractiveTask() throws Exception {
        PrintStream stream = new PrintStream(output, true, StandardCharsets.UTF_8);
        BufferedReader forbidden = new BufferedReader(new StringReader("")) {
            @Override public String readLine() { throw new AssertionError("非交互审批不能读输入"); }
        };
        FakeLLMClient model = new FakeLLMClient(List.of(edit("needs-approval", "fixed"), done()));
        var cli = new CodingAgentCli(root, model, false, 12, forbidden, stream, stream, null, acceptance());
        assertEquals(1, cli.startManagedTask("修复", null, false), text());
        assertEquals(1, model.callCount());
        assertEquals("broken", Files.readString(root.resolve("src/main/java/App.java")));
        assertTrue(text().contains("非交互"), text());
        assertTrue(text().contains("Paused"), text());
    }

}
