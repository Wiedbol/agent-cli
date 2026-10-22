package com.agent;

import com.agent.command.CommandResult;
import com.agent.command.CommandRunner;
import com.agent.llm.FakeLLMClient;
import com.agent.llm.LLMResponse;
import com.agent.llm.Message;
import com.agent.llm.ToolCall;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static com.agent.command.CommandResult.OutputCompleteness.COMPLETE;
import static com.agent.command.CommandResult.OutputCompleteness.INCOMPLETE;
import static com.agent.command.CommandResult.OutputCompleteness.UNKNOWN;

/** 从装配入口验证命令替身，确保离线测试走真实的守卫和工具链路。 */
class CodingAgentCliCommandTest {

    @TempDir
    Path root;

    @Test
    void shouldFeedInjectedCommandSuccessBackToModel() {
        CommandRunner runner = (workingDirectory, command, timeoutSeconds) -> {
            assertEquals(root.toAbsolutePath().normalize(), workingDirectory);
            assertEquals("echo real-output", command);
            assertEquals(60, timeoutSeconds);
            return new CommandResult(0, false, "offline-output\n", 23, COMPLETE);
        };
        FakeLLMClient model = commandModel("{\"command\":\"echo real-output\"}");

        assertEquals(0, cli(model, runner).runTask("执行命令"));

        assertEquals("命令: echo real-output\n退出码: 0\n输出:\noffline-output",
                observation(model));
    }

    @Test
    void shouldFeedInjectedTimeoutBackToModelWithoutAnExitCode() {
        CommandRunner runner = (workingDirectory, command, timeoutSeconds) -> {
            assertEquals(2, timeoutSeconds);
            return new CommandResult(null, true, "", 2000, INCOMPLETE);
        };
        FakeLLMClient model = commandModel(
                "{\"command\":\"echo real-output\",\"timeoutSeconds\":2}");

        cli(model, runner).runTask("执行命令");

        assertEquals("[工具执行失败] 命令超时（超过 2 秒）已被强制结束: echo real-output",
                observation(model));
    }

    @Test
    void shouldKeepLegacyObservationWhenOutputFinalityIsUnknown() {
        CommandRunner runner = (workingDirectory, command, timeoutSeconds) ->
                new CommandResult(0, false, "captured-output", 5, UNKNOWN);
        FakeLLMClient model = commandModel("{\"command\":\"echo real-output\"}");

        cli(model, runner).runTask("执行命令");

        assertEquals("命令: echo real-output\n退出码: 0\n输出:\ncaptured-output", observation(model));
    }

    @Test
    void shouldMarkIncompleteCapturedOutputForTheModel() {
        CommandRunner runner = (workingDirectory, command, timeoutSeconds) ->
                new CommandResult(0, false, "partial-output", 5, INCOMPLETE);
        FakeLLMClient model = commandModel("{\"command\":\"echo real-output\"}");

        cli(model, runner).runTask("执行命令");

        assertEquals("命令: echo real-output\n退出码: 0\n输出:\npartial-output\n...(命令输出不完整)",
                observation(model));
    }

    @Test
    void shouldFeedInjectedNonZeroExitBackToModelAsFailure() {
        CommandRunner runner = (workingDirectory, command, timeoutSeconds) ->
                new CommandResult(7, false, "compile failed", 12, COMPLETE);
        FakeLLMClient model = commandModel("{\"command\":\"echo real-output\"}");

        cli(model, runner).runTask("执行命令");

        assertEquals("[工具执行失败] 命令: echo real-output\n退出码: 7\n输出:\ncompile failed",
                observation(model));
    }

    @Test
    void shouldKeepTheExistingOutputLimitForTheModel() {
        CommandRunner runner = (workingDirectory, command, timeoutSeconds) ->
                new CommandResult(0, false, "x".repeat(4001), 5, COMPLETE);
        FakeLLMClient model = commandModel("{\"command\":\"echo real-output\"}");

        cli(model, runner).runTask("执行命令");

        assertEquals("命令: echo real-output\n退出码: 0\n输出:\n" + "x".repeat(4000)
                + "\n...(命令输出已截断，原长度 4001 字符)", observation(model));
    }

    @Test
    void shouldRejectInvalidTimeoutBeforeInvokingTheRunner() {
        CommandRunner runner = (workingDirectory, command, timeoutSeconds) -> {
            throw new AssertionError("非法参数不能传给外部进程");
        };
        FakeLLMClient model = commandModel(
                "{\"command\":\"echo real-output\",\"timeoutSeconds\":0}");

        cli(model, runner).runTask("执行命令");

        assertEquals("[工具执行失败] 参数错误: timeoutSeconds 必须为正数: 0", observation(model));
    }

    @Test
    void shouldKeepGuardDecisionsInFrontOfTheInjectedRunner() {
        CommandRunner runner = (workingDirectory, command, timeoutSeconds) -> {
            throw new AssertionError("守卫拒绝的命令不能到达外部进程");
        };
        FakeLLMClient model = commandModel("{\"command\":\"git reset --hard\"}");

        cli(model, runner).runTask("执行命令");

        assertTrue(observation(model).startsWith("守卫拒绝了这次工具调用"));
    }

    @Test
    void shouldFeedRunnerIoFailureBackToModel() {
        CommandRunner runner = (workingDirectory, command, timeoutSeconds) -> {
            throw new IOException("cannot start process");
        };
        FakeLLMClient model = commandModel("{\"command\":\"echo real-output\"}");

        cli(model, runner).runTask("执行命令");

        assertEquals("[工具执行失败] IOException: cannot start process", observation(model));
    }

    private CodingAgentCli cli(FakeLLMClient model, CommandRunner runner) {
        PrintStream silent = new PrintStream(OutputStream.nullOutputStream(), true, StandardCharsets.UTF_8);
        return new CodingAgentCli(root, model, true, 4,
                new BufferedReader(new StringReader("")), silent, silent, null, runner);
    }

    private static FakeLLMClient commandModel(String arguments) {
        ToolCall call = new ToolCall("command-1", new ToolCall.Function("run_command", arguments));
        return new FakeLLMClient(List.of(
                new LLMResponse(null, List.of(call), 1, 1),
                new LLMResponse("命令已处理", null, 1, 1)));
    }

    private static String observation(FakeLLMClient model) {
        return model.requests().get(1).stream()
                .filter(message -> "tool".equals(message.role()))
                .map(Message::content)
                .findFirst().orElseThrow();
    }
}
