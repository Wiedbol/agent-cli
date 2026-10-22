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

class CodingAgentCliTaskResolutionTest {
    @TempDir Path root;
    private final ByteArrayOutputStream output = new ByteArrayOutputStream();
    private static final class PowerLoss extends Error { }

    @BeforeEach void project() throws Exception {
        Files.writeString(root.resolve("pom.xml"), "<project/>");
        Files.createDirectories(root.resolve("src/main/java"));
        Files.writeString(source(), "broken");
    }
    private Path source() { return root.resolve("src/main/java/App.java"); }
    private String text() { return output.toString(StandardCharsets.UTF_8); }
    private CodingAgentCli cli(LLMClient model, CommandRunner runner, String input) {
        PrintStream stream = new PrintStream(output, true, StandardCharsets.UTF_8);
        return new CodingAgentCli(root, model, true, 12, new BufferedReader(new StringReader(input)),
                stream, stream, null, runner);
    }
    private String taskId() {
        var match = Pattern.compile("任务: ([0-9a-f-]{36})").matcher(text());
        assertTrue(match.find(), text()); return match.group(1);
    }
    private CommandRunner acceptance() {
        return (dir, command, timeout) -> {
            boolean fixed = Files.readString(source()).equals("fixed");
            Path reports = Files.createDirectories(root.resolve("target/surefire-reports"));
            Files.writeString(reports.resolve("TEST-AppTest.xml"), "<testsuite tests=\"1\" failures=\""
                    + (fixed ? 0 : 1) + "\" errors=\"0\" skipped=\"0\"><testcase classname=\"AppTest\" name=\"checks\">"
                    + (fixed ? "" : "<failure message=\"broken\"/>") + "</testcase></testsuite>");
            return new CommandResult(fixed ? 0 : 1, false, "test output", 1, CommandResult.OutputCompleteness.COMPLETE);
        };
    }
    private String unknownWrite() throws Exception {
        var model = new FakeLLMClient(List.of(new LLMResponse(null, List.of(new ToolCall("unknown-write",
                new ToolCall.Function("write_file", "{\"path\":\"src/main/java/App.java\",\"content\":\"fixed\"}"))), 1, 1)));
        PrintStream fault = new PrintStream(output, true, StandardCharsets.UTF_8) {
            @Override public void println(String value) {
                super.println(value);
                if (value.contains("[执行员] 执行成功")) { throw new PowerLoss(); }
            }
        };
        var interrupted = new CodingAgentCli(root, model, true, 12, new BufferedReader(new StringReader("")),
                fault, fault, null, acceptance());
        assertThrows(PowerLoss.class, () -> interrupted.startManagedTask("修复", null));
        assertEquals("fixed", Files.readString(source()));
        return taskId();
    }

    @Test void shouldAbandonUnknownTaskWithoutRollingBackOrClaimingSuccess() throws Exception {
        String task = unknownWrite();
        assertEquals(1, cli(null, acceptance(), "abandon " + task + "\n").abandonTask(task, true), text());
        assertEquals("fixed", Files.readString(source()));
        output.reset();
        assertEquals(0, cli(null, acceptance(), "").showTask(task));
        assertTrue(text().contains("状态: Abandoned"), text());
        assertTrue(text().contains("ABANDON"), text());
        assertTrue(text().contains("结果不明"), text());
        assertFalse(text().contains("final: PASSED"), text());
    }

    private String operationId() {
        var match = Pattern.compile("操作ID: ([0-9a-f-]{36})").matcher(text());
        assertTrue(match.find(), text()); return match.group(1);
    }

    @Test void shouldApproveNewRetryOperationAndRequireFreshAcceptance() throws Exception {
        String task = unknownWrite();
        assertEquals(0, cli(null, acceptance(), "").showTask(task));
        String original = operationId();
        assertEquals(1, cli(null, acceptance(), "retry " + task + " " + original + "\n")
                .retryUncertainOperation(task, original, true), text());
        assertEquals("fixed", Files.readString(source()));
        assertTrue(text().contains("状态: Paused"), text());
        assertTrue(text().contains("RETRY"), text());
        assertTrue(text().contains("原操作结果不明"), text());
        output.reset();
        assertEquals(0, cli(null, acceptance(), "").showTask(task));
        assertNotEquals(original, operationId());
        var resumed = new FakeLLMClient(List.of(new LLMResponse("请验收", null, 1, 1)));
        assertEquals(0, cli(resumed, acceptance(), "").resumeManagedTask(task, false), text());
        assertEquals(1, resumed.callCount());
        assertEquals(1, resumed.requests().get(0).stream().filter(m -> "unknown-write".equals(m.toolCallId())).count());
        assertTrue(text().contains("工具调用: 2"), text());
        assertTrue(text().contains("状态: Verified"), text());
    }

    @Test void shouldRequireExplicitStoppedConfirmationBeforeRetryingUnknownCommand() throws Exception {
        int[] commands = {0};
        CommandRunner runner = (dir, command, timeout) -> {
            if (command.equals("fixture-command")) {
                Files.writeString(source(), "fixed");
                if (++commands[0] == 1) { throw new PowerLoss(); }
                return new CommandResult(0, false, "repeated effect", 1, CommandResult.OutputCompleteness.COMPLETE);
            }
            return acceptance().run(dir, command, timeout);
        };
        var model = new FakeLLMClient(List.of(new LLMResponse(null, List.of(new ToolCall("unknown-command",
                new ToolCall.Function("run_command", "{\"command\":\"fixture-command\"}"))), 1, 1)));
        assertThrows(PowerLoss.class, () -> cli(model, runner, "").startManagedTask("修复", null));
        String task = taskId();
        assertEquals(0, cli(null, runner, "").showTask(task));
        String operation = operationId();
        assertEquals(1, cli(null, runner, "retry " + task + " " + operation + "\n")
                .retryUncertainOperation(task, operation, true), text());
        assertTrue(text().contains("未明确确认旧进程树已停止"), text());
        assertEquals(1, commands[0]);
        String confirmation = "stopped " + task + " " + operation + "\nretry " + task + " " + operation + "\n";
        assertEquals(1, cli(null, runner, confirmation).retryUncertainOperation(task, operation, true), text());
        assertEquals(1, commands[0]);
        var resumed = new FakeLLMClient(List.of(new LLMResponse("请验收", null, 1, 1)));
        assertEquals(0, cli(resumed, runner, "").resumeManagedTask(task, false), text());
        assertEquals(2, commands[0]);
        assertTrue(text().contains("原操作结果不明"), text());
    }

    @Test void shouldRefuseRetryWhileRecordedOldProcessIsAliveEvenWithConfirmation() throws Exception {
        CommandRunner tracked = new CommandRunner() {
            java.util.function.Consumer<ProcessHandle> observer;
            @Override public CommandRunner withProcessObserver(java.util.function.Consumer<ProcessHandle> value) {
                observer = value; return this;
            }
            @Override public CommandResult run(Path dir, String command, long timeout) throws java.io.IOException, InterruptedException {
                if (command.equals("fixture-command")) {
                    observer.accept(ProcessHandle.current());
                    throw new PowerLoss();
                }
                return acceptance().run(dir, command, timeout);
            }
        };
        var model = new FakeLLMClient(List.of(new LLMResponse(null, List.of(new ToolCall("live-command",
                new ToolCall.Function("run_command", "{\"command\":\"fixture-command\"}"))), 1, 1)));
        assertThrows(PowerLoss.class, () -> cli(model, tracked, "").startManagedTask("修复", null));
        String task = taskId();
        assertEquals(0, cli(null, tracked, "").showTask(task));
        String operation = operationId();
        String confirmation = "stopped " + task + " " + operation + "\nretry " + task + " " + operation + "\n";
        assertEquals(1, cli(null, tracked, confirmation).retryUncertainOperation(task, operation, true), text());
        assertTrue(text().contains("仍在运行"), text());
        assertEquals("broken", Files.readString(source()));
    }

    @Test void shouldExplicitlyRetryUncertainFinalAcceptanceWithoutTrustingItsOldReport() throws Exception {
        int[] checks = {0};
        CommandRunner runner = (dir, command, timeout) -> {
            CommandResult result = acceptance().run(dir, command, timeout);
            if (++checks[0] == 2) { throw new PowerLoss(); }
            return result;
        };
        var model = new FakeLLMClient(List.of(new LLMResponse(null, List.of(new ToolCall("repair",
                new ToolCall.Function("write_file", "{\"path\":\"src/main/java/App.java\",\"content\":\"fixed\"}"))), 1, 1),
                new LLMResponse("请验收", null, 1, 1)));
        assertThrows(PowerLoss.class, () -> cli(model, runner, "").startManagedTask("修复", null));
        String task = taskId();
        assertEquals(0, cli(null, runner, "").showTask(task));
        var match = Pattern.compile("验收结果不明，操作ID: ([0-9a-f-]{36})").matcher(text());
        assertTrue(match.find(), text());
        String operation = match.group(1);
        assertEquals(1, cli(null, runner, "stopped " + task + " " + operation + "\nretry " + task + " " + operation + "\n")
                .retryUncertainOperation(task, operation, true), text());
        assertEquals(2, checks[0]);
        assertFalse(text().contains("状态: Verified"), text());
        LLMClient noRequest = (messages, tools) -> { throw new AssertionError("保存的完成声明不应重问"); };
        assertEquals(0, cli(noRequest, runner, "").resumeManagedTask(task, false), text());
        assertEquals(3, checks[0]);
        assertTrue(text().contains("final: PASSED"), text());
    }

    @ParameterizedTest @ValueSource(strings = {"", "y\n", "retry wrong wrong\n"})
    void shouldKeepUnknownOperationOnEofOrRefusedRetryDespiteYes(String input) throws Exception {
        String task = unknownWrite();
        assertEquals(0, cli(null, acceptance(), "").showTask(task));
        String original = operationId();
        assertEquals(1, cli(null, acceptance(), input).retryUncertainOperation(task, original, true), text());
        assertEquals("fixed", Files.readString(source()));
        assertTrue(text().contains("未明确批准重试"), text());
        output.reset();
        assertEquals(0, cli(null, acceptance(), "").showTask(task));
        assertEquals(original, operationId());
        assertFalse(text().contains("人工处置: RETRY"), text());
    }

    @ParameterizedTest @ValueSource(strings = {"retry", "abandon"})
    void shouldPauseNoninteractiveResolutionWithoutReadingInput(String action) throws Exception {
        String task = unknownWrite();
        assertEquals(0, cli(null, acceptance(), "").showTask(task));
        String original = operationId();
        BufferedReader forbidden = new BufferedReader(new StringReader("")) {
            @Override public String readLine() { throw new AssertionError("非交互处置不能读取输入"); }
        };
        PrintStream stream = new PrintStream(output, true, StandardCharsets.UTF_8);
        var cli = new CodingAgentCli(root, null, true, 12, forbidden, stream, stream, null, acceptance());
        assertEquals(1, action.equals("retry") ? cli.retryUncertainOperation(task, original, false)
                : cli.abandonTask(task, false), text());
        assertEquals("fixed", Files.readString(source()));
        assertTrue(text().contains("非交互"), text());
        assertTrue(text().contains("--yes 不适用"), text());
    }

    @ParameterizedTest @ValueSource(strings = {"", "y\n"})
    void shouldNotAbandonWithoutExactConfirmation(String input) throws Exception {
        String task = unknownWrite();
        assertEquals(1, cli(null, acceptance(), input).abandonTask(task, true));
        assertEquals("fixed", Files.readString(source()));
        assertFalse(text().contains("状态: Abandoned"), text());
    }

    @Test void shouldRejectChangesDuringRetryReviewAndKeepHumanEdit() throws Exception {
        String task = unknownWrite();
        assertEquals(0, cli(null, acceptance(), "").showTask(task));
        String original = operationId();
        BufferedReader changing = new BufferedReader(new StringReader("")) {
            @Override public String readLine() throws java.io.IOException {
                Files.writeString(source(), "human edit");
                return "retry " + task + " " + original;
            }
        };
        PrintStream stream = new PrintStream(output, true, StandardCharsets.UTF_8);
        var cli = new CodingAgentCli(root, null, true, 12, changing, stream, stream, null, acceptance());
        assertEquals(1, cli.retryUncertainOperation(task, original, true), text());
        assertEquals("human edit", Files.readString(source()));
        assertTrue(text().contains("确认期间现场版本变化"), text());
    }

    @Test void shouldNotAuthorizeRetryWhenResolutionCannotBePersisted() throws Exception {
        String task = unknownWrite();
        assertEquals(0, cli(null, acceptance(), "").showTask(task));
        String original = operationId();
        Path previous = root.resolve(".agent/tasks/" + task + "/state.previous.json");
        BufferedReader failingStorage = new BufferedReader(new StringReader("")) {
            @Override public String readLine() throws java.io.IOException {
                Files.delete(previous); Files.createDirectory(previous);
                return "retry " + task + " " + original;
            }
        };
        PrintStream stream = new PrintStream(output, true, StandardCharsets.UTF_8);
        var cli = new CodingAgentCli(root, null, true, 12, failingStorage, stream, stream, null, acceptance());
        assertEquals(4, cli.retryUncertainOperation(task, original, true), text());
        Files.delete(previous);
        LLMClient forbidden = (messages, tools) -> { throw new AssertionError("未可靠批准不能继续"); };
        assertEquals(1, cli(forbidden, acceptance(), "").resumeManagedTask(task, false), text());
        assertEquals("fixed", Files.readString(source()));
        assertTrue(text().contains("工具结果不明"), text());
    }

    @Test void shouldStopInsteadOfConvertingProcessTrackingFailureToCompletedToolResult() throws Exception {
        Path[] damaged = {null};
        CommandRunner runner = new CommandRunner() {
            java.util.function.Consumer<ProcessHandle> observer;
            @Override public CommandRunner withProcessObserver(java.util.function.Consumer<ProcessHandle> value) { observer = value; return this; }
            @Override public CommandResult run(Path dir, String command, long timeout) throws java.io.IOException, InterruptedException {
                if (command.equals("fixture-command")) {
                    Path previous = root.resolve(".agent/tasks/" + taskId() + "/state.previous.json");
                    Files.delete(previous); Files.createDirectory(previous); damaged[0] = previous;
                    observer.accept(ProcessHandle.current());
                    throw new AssertionError("记录失败后不能继续执行");
                }
                return acceptance().run(dir, command, timeout);
            }
        };
        var model = new FakeLLMClient(List.of(new LLMResponse(null, List.of(new ToolCall("tracking-failure",
                new ToolCall.Function("run_command", "{\"command\":\"fixture-command\"}")), new ToolCall("must-not-run",
                new ToolCall.Function("write_file", "{\"path\":\"src/main/java/App.java\",\"content\":\"fixed\"}"))), 1, 1)));
        assertEquals(4, cli(model, runner, "").startManagedTask("修复", null), text());
        assertEquals("broken", Files.readString(source()));
        Files.delete(damaged[0]);
        LLMClient forbidden = (messages, tools) -> { throw new AssertionError("未知命令不能继续"); };
        assertEquals(1, cli(forbidden, runner, "").resumeManagedTask(taskId(), false), text());
        assertTrue(text().contains("工具结果不明"), text());
    }

    @Test void shouldKeepAllAttemptHistoryAcrossTwoDeliberateRetries() throws Exception {
        String task = unknownWrite();
        assertEquals(0, cli(null, acceptance(), "").showTask(task));
        String firstId = operationId();
        assertEquals(1, cli(null, acceptance(), "retry " + task + " " + firstId + "\n")
                .retryUncertainOperation(task, firstId, true));
        PrintStream fault = new PrintStream(output, true, StandardCharsets.UTF_8) {
            @Override public void println(String value) {
                super.println(value);
                if (value.contains("[执行员] 执行成功")) { throw new PowerLoss(); }
            }
        };
        var interrupted = new CodingAgentCli(root, new FakeLLMClient(List.of()), true, 12,
                new BufferedReader(new StringReader("")), fault, fault, null, acceptance());
        assertThrows(PowerLoss.class, () -> interrupted.resumeManagedTask(task, false));
        output.reset();
        assertEquals(0, cli(null, acceptance(), "").showTask(task));
        String secondId = operationId();
        assertNotEquals(firstId, secondId);
        assertEquals(1, cli(null, acceptance(), "retry " + task + " " + secondId + "\n")
                .retryUncertainOperation(task, secondId, true));
        var resumed = new FakeLLMClient(List.of(new LLMResponse("请验收", null, 1, 1)));
        output.reset();
        assertEquals(0, cli(resumed, acceptance(), "").resumeManagedTask(task, false), text());
        assertTrue(text().contains("工具调用: 3"), text());
        assertTrue(text().contains("模型请求: 2/12"), text());
        assertEquals(2, text().split("人工处置: RETRY", -1).length - 1);
        assertTrue(text().contains(firstId), text());
        assertTrue(text().contains(secondId), text());
    }

    @Test void shouldRejectRetryForCompletedOrAlreadyReplacedOperation() throws Exception {
        String task = unknownWrite();
        assertEquals(0, cli(null, acceptance(), "").showTask(task));
        String old = operationId();
        assertEquals(1, cli(null, acceptance(), "retry " + task + " " + old + "\n")
                .retryUncertainOperation(task, old, true));
        assertEquals(4, cli(null, acceptance(), "retry " + task + " " + old + "\n")
                .retryUncertainOperation(task, old, true));
        var resumed = new FakeLLMClient(List.of(new LLMResponse("请验收", null, 1, 1)));
        assertEquals(0, cli(resumed, acceptance(), "").resumeManagedTask(task, false));
        output.reset();
        assertEquals(0, cli(null, acceptance(), "").showTask(task));
        assertEquals(4, cli(null, acceptance(), "").retryUncertainOperation(task, operationId(), true));
    }

    @Test void shouldNotResumeAbandonedTaskOrChangeItsFiles() throws Exception {
        String task = unknownWrite();
        assertEquals(1, cli(null, acceptance(), "abandon " + task + "\n").abandonTask(task, true));
        LLMClient forbidden = (messages, tools) -> { throw new AssertionError("已放弃不能继续"); };
        assertNotEquals(0, cli(forbidden, acceptance(), "").resumeManagedTask(task, false));
        assertNotEquals(0, cli(null, acceptance(), "approve " + task + "\n").reagreeAcceptance(task, "mvn test", true));
        output.reset();
        assertEquals(0, cli(null, acceptance(), "").showTask(task));
        assertTrue(text().contains("状态: Abandoned"), text());
        assertEquals("fixed", Files.readString(source()));
    }

    @ParameterizedTest @ValueSource(booleans = {true, false})
    void shouldPreserveVerifiedTaskWhenAbandonmentIsNotApproved(boolean interactive) throws Exception {
        Files.writeString(source(), "fixed");
        assertEquals(0, cli(new FakeLLMClient(List.of(new LLMResponse("请验收", null, 1, 1))), acceptance(), "")
                .startManagedTask("检查", null));
        String task = taskId();
        assertEquals(1, cli(null, acceptance(), "").abandonTask(task, interactive));
        output.reset();
        assertEquals(0, cli(null, acceptance(), "").showTask(task));
        assertTrue(text().contains("状态: Verified"), text());
        LLMClient forbidden = (messages, tools) -> { throw new AssertionError("已完成任务不应重新请求"); };
        assertEquals(0, cli(forbidden, acceptance(), "").resumeManagedTask(task, false), text());
    }

    @Test void shouldNotBypassUncertainCommandThroughAcceptanceReagreement() throws Exception {
        int[] checks = {0};
        CommandRunner runner = (dir, command, timeout) -> {
            checks[0]++;
            if (checks[0] == 1) { throw new PowerLoss(); }
            return acceptance().run(dir, command, timeout);
        };
        LLMClient forbidden = (messages, tools) -> { throw new AssertionError("未知验收不能请求模型"); };
        assertThrows(PowerLoss.class, () -> cli(forbidden, runner, "").startManagedTask("修复", null));
        String task = taskId();
        assertEquals(1, cli(forbidden, runner, "").resumeManagedTask(task, false));
        Files.writeString(root.resolve("pom.xml"), "<project>human change</project>");
        assertEquals(1, cli(null, runner, "approve " + task + "\n").reagreeAcceptance(task, "mvn test", true), text());
        assertEquals(1, checks[0], "未知验收未处置之前不得再次执行验收命令");
        output.reset();
        assertEquals(0, cli(null, runner, "").showTask(task));
        assertTrue(text().contains("验收结果不明"), text());
        assertFalse(text().contains("历史约定:"), text());
    }
}
