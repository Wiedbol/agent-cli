package com.agent.tool;

import com.agent.command.ProcessCommandRunner;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 执行命令工具：工作目录、退出码、超时与输出捕获。 */
class RunCommandToolTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final boolean WINDOWS =
            System.getProperty("os.name", "").toLowerCase().contains("win");

    @TempDir
    Path root;

    private ToolResult run(String json) throws Exception {
        return new RunCommandTool(new ProcessCommandRunner(root))
                .execute(new ToolRequest(root, MAPPER.readTree(json)));
    }

    @Test
    void shouldRunCommandInProjectRoot() throws Exception {
        ToolResult result = run("{\"command\":\"echo hello-agent\"}");

        assertTrue(result.success());
        assertTrue(result.output().contains("hello-agent"));
        assertTrue(result.output().contains("退出码: 0"));
    }

    @Test
    void shouldReportNonZeroExitCodeAsFailure() throws Exception {
        ToolResult result = run("{\"command\":\"exit 3\"}");

        assertFalse(result.success());
        assertTrue(result.output().contains("退出码: 3"), result.output());
    }

    @Test
    void shouldReportUnknownCommandWithoutThrowing() throws Exception {
        ToolResult result = run("{\"command\":\"definitely-not-a-command-xyz\"}");

        assertFalse(result.success());
        assertTrue(result.output().contains("退出码:"), result.output());
    }

    @Test
    void shouldUseProjectRootAsWorkingDirectory() throws Exception {
        String command = WINDOWS ? "cd" : "pwd";

        ToolResult result = run("{\"command\":\"" + command + "\"}");

        assertTrue(result.success());
        String normalized = result.output().replace('\\', '/').toLowerCase();
        assertTrue(normalized.contains(root.toString().replace('\\', '/').toLowerCase()),
                "命令必须在项目根目录下执行，实际输出: " + result.output());
    }

    @Test
    void shouldKillCommandThatExceedsTimeout() throws Exception {
        String sleeper = WINDOWS ? "ping -n 4 127.0.0.1 > nul" : "sleep 5";

        ToolResult result = run("{\"command\":\"" + sleeper + "\",\"timeoutSeconds\":1}");

        assertFalse(result.success());
        assertTrue(result.output().contains("超时"), result.output());
    }

    @Test
    void shouldRejectNonPositiveTimeout() {
        assertThrowsIllegalArgument("{\"command\":\"echo hi\",\"timeoutSeconds\":0}");
    }

    private void assertThrowsIllegalArgument(String json) {
        try {
            run(json);
        } catch (Exception expected) {
            assertTrue(expected instanceof IllegalArgumentException, "实际异常: " + expected);
            return;
        }
        throw new AssertionError("应当抛出 IllegalArgumentException");
    }

    @Test
    void shouldInterpretShellOperators() throws Exception {
        ToolResult result = run("{\"command\":\"echo first-marker && echo second-marker\"}");

        assertTrue(result.success());
        assertTrue(result.output().contains("first-marker"));
        assertTrue(result.output().contains("second-marker"));
    }
}
