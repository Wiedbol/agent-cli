package com.agent;

import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 验证 CLI 的 I/O 循环：用「字节流 + 指定编码」模拟真实控制台的输入，
 * 从而复现并锁定「中文输入乱码」这一缺陷。
 */
class MainTest {

    private static final Charset GBK = Charset.forName("GBK");

    private static BufferedReader stdin(String rawText, Charset charset) {
        return new BufferedReader(new InputStreamReader(
                new ByteArrayInputStream(rawText.getBytes(charset)), charset));
    }

    /** 以给定编码运行 CLI，返回按 UTF-8 解码的输出（换行符统一成 \n）。 */
    private static String runWith(String rawText, Charset inputCharset) throws Exception {
        return runWith(rawText, inputCharset, false);
    }

    /** 同上，但可以打开 Lab01 挑战任务 3 的 {@code --upper} 输出转换。 */
    private static String runWith(String rawText, Charset inputCharset, boolean upperCaseOutput)
            throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (PrintStream out = new PrintStream(bytes, true, StandardCharsets.UTF_8)) {
            Main.run(new Agent(), stdin(rawText, inputCharset), out, upperCaseOutput);
        }
        return bytes.toString(StandardCharsets.UTF_8).replace("\r\n", "\n");
    }

    @Test
    void shouldEchoChineseTypedInGbkConsole() throws Exception {
        // 修复前：InputStreamReader 用默认 UTF-8 解码 GBK 字节，输出 "I heard: ???"
        assertEquals("I heard: 你好\n", runWith("你好\n", GBK));
    }

    @Test
    void shouldEchoChineseFromUtf8Pipe() throws Exception {
        assertEquals("I heard: 你好\n", runWith("你好\n", StandardCharsets.UTF_8));
    }

    @Test
    void shouldGreetAndKeepReadingFollowingLines() throws Exception {
        assertEquals("Hello, Agent!\nI heard: ok\n",
                runWith("hello\nok\n", StandardCharsets.UTF_8));
    }

    @Test
    void shouldStopAtQuitAndIgnoreRemainingInput() throws Exception {
        assertEquals("Hello, Agent!\n", runWith("hello\nquit\nhi\n", StandardCharsets.UTF_8));
    }

    @Test
    void shouldStopAtQuitRegardlessOfCase() throws Exception {
        assertEquals("", runWith("QUIT\nhi\n", StandardCharsets.UTF_8));
    }

    @Test
    void shouldProduceNoOutputWhenInputIsEmpty() throws Exception {
        assertEquals("", runWith("", StandardCharsets.UTF_8));
    }

    // ---- Lab03：REPL 命令与 Lab01 挑战任务 3（--upper）----

    @Test
    void shouldAcceptExitAsAliasForQuit() throws Exception {
        assertEquals("Hello, Agent!\n", runWith("hello\nexit\nhi\n", StandardCharsets.UTF_8));
    }

    @Test
    void shouldHandleClearCommandWithoutConsumingNextLine() throws Exception {
        assertEquals("历史已清空。\nHello, Agent!\n",
                runWith("clear\nhello\n", StandardCharsets.UTF_8));
    }

    @Test
    void shouldUpperCaseOutputWhenUpperFlagIsEnabled() throws Exception {
        assertEquals("I HEARD: HI\n", runWith("hi\n", StandardCharsets.UTF_8, true));
    }

    @Test
    void shouldKeepOutputUnchangedByDefault() throws Exception {
        assertEquals("I heard: hi\n", runWith("hi\n", StandardCharsets.UTF_8, false));
    }

    @Test
    void shouldParseUpperFlagFromCommandLine() {
        assertTrue(Main.hasUpperFlag(new String[]{"--upper"}));
        assertTrue(Main.hasUpperFlag(new String[]{"--verbose", "--upper"}));
        assertFalse(Main.hasUpperFlag(new String[]{}));
        assertFalse(Main.hasUpperFlag(null));
    }
}
