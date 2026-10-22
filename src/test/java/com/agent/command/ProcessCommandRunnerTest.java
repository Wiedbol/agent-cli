package com.agent.command;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 通过真实本地进程验证公共契约，输出临时文件也限制在测试目录。 */
class ProcessCommandRunnerTest {

    private static final boolean WINDOWS = System.getProperty("os.name", "").toLowerCase().contains("win");

    @TempDir
    Path root;

    @Test
    void shouldNotClaimOutputFinalityFromShellExitAlone() throws Exception {
        CommandResult result = new ProcessCommandRunner(root).run(root, "echo snapshot", 5);

        assertEquals(CommandResult.OutputCompleteness.UNKNOWN, result.outputCompleteness());
        assertFalse(result.outputComplete());
        assertTrue(result.output().contains("snapshot"));
        assertTrue(result.success());
    }

    @Test
    void shouldRunShellOperatorsInRequestedWorkingDirectory() throws Exception {
        Path workingDirectory = Files.createDirectory(root.resolve("command project"));
        String command = WINDOWS ? "echo real-result && cd" : "echo real-result && pwd";
        CommandRunner runner = new ProcessCommandRunner(root);

        CommandResult result = runner.run(workingDirectory, command, 5);

        assertEquals(Integer.valueOf(0), result.exitCode());
        assertFalse(result.timedOut());
        assertEquals(CommandResult.OutputCompleteness.UNKNOWN, result.outputCompleteness());
        assertTrue(result.output().contains("real-result"));
        assertTrue(result.output().replace('\\', '/').contains(workingDirectory.toString().replace('\\', '/')));
        assertTrue(result.durationMillis() >= 0);
    }

    @Test
    void shouldExposeNonZeroExitWithoutLosingProcessFacts() throws Exception {
        CommandResult result = new ProcessCommandRunner(root).run(root, "exit 7", 5);

        assertEquals(Integer.valueOf(7), result.exitCode());
        assertFalse(result.success());
        assertFalse(result.timedOut());
        assertEquals(CommandResult.OutputCompleteness.UNKNOWN, result.outputCompleteness());
        assertEquals("", result.output());
        assertNoOutputLogs();
    }

    @Test
    void shouldPreserveOutputBeyondTheToolDisplayLimit() throws Exception {
        String payload = "x".repeat(5000);

        CommandResult result = new ProcessCommandRunner(root).run(root, "echo " + payload, 5);

        assertEquals(payload + System.lineSeparator(), result.output());
        assertEquals(CommandResult.OutputCompleteness.UNKNOWN, result.outputCompleteness());
        assertTrue(result.success());
        assertNoOutputLogs();
    }

    @Test
    void shouldDecodeOutputUsingExplicitShellEncoding() throws Exception {
        Files.write(root.resolve("encoded-output.bin"), "命令输出".getBytes(Charset.forName("GBK")));
        String previous = System.getProperty("agent.shell.encoding");
        System.setProperty("agent.shell.encoding", "GBK");
        try {
            String command = WINDOWS ? "type encoded-output.bin" : "cat encoded-output.bin";

            CommandResult result = new ProcessCommandRunner(root).run(root, command, 5);

            assertEquals("命令输出", result.output());
            assertTrue(result.success());
        } finally {
            if (previous == null) {
                System.clearProperty("agent.shell.encoding");
            } else {
                System.setProperty("agent.shell.encoding", previous);
            }
        }
        assertNoOutputLogs();
    }

    @Test
    void shouldReportTimeoutAndReleaseChildProcessOutput() throws Exception {
        String command = WINDOWS ? "ping -n 4 127.0.0.1 > nul" : "sleep 5";

        CommandResult result = new ProcessCommandRunner(root).run(root, command, 1);

        assertTrue(result.timedOut());
        assertNull(result.exitCode());
        assertFalse(result.success());
        assertFalse(result.outputComplete());
        assertNoOutputLogs();
    }

    @Test
    void shouldReleaseOutputFileWhenProcessCannotStart() throws Exception {
        CommandRunner runner = new ProcessCommandRunner(root);

        assertThrows(IOException.class, () -> runner.run(root.resolve("missing"), "echo hello", 5));

        assertNoOutputLogs();
    }

    private void assertNoOutputLogs() throws IOException {
        try (var files = Files.list(root)) {
            assertFalse(files.anyMatch(file -> file.getFileName().toString().startsWith("agent-command-")));
        }
    }
}
