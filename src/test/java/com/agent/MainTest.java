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
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (PrintStream out = new PrintStream(bytes, true, StandardCharsets.UTF_8)) {
            Main.run(new Agent(), stdin(rawText, inputCharset), out);
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
}
