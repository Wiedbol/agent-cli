package com.agent;

import org.junit.jupiter.api.Test;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * 验证编码选择规则：显式指定 &gt; JVM 探测值（{@code stdin.encoding} / {@code stdout.encoding}）
 * &gt; 兜底值；名字为空或不可用时逐级回退。
 */
class ConsoleStreamsTest {

    private static final Charset GBK = Charset.forName("GBK");

    @Test
    void shouldPreferExplicitEncodingOverDetectedOne() {
        assertEquals(GBK, ConsoleStreams.charset("GBK", "UTF-8", StandardCharsets.UTF_8));
        assertEquals(StandardCharsets.UTF_8, ConsoleStreams.charset("UTF-8", "GBK", GBK));
    }

    @Test
    void shouldIgnoreBlankOrUnknownForcedEncoding() {
        assertEquals(GBK, ConsoleStreams.charset("   ", "GBK", StandardCharsets.UTF_8));
        assertEquals(GBK, ConsoleStreams.charset("no-such-charset", "GBK", StandardCharsets.UTF_8));
    }

    @Test
    void shouldUseJvmDetectedEncodingByDefault() {
        // 中文 Windows：控制台代码页 936 ⇒ GBK（JDK 有时用别名 x-mswin-936 报告，
        // 与 Charset.forName("GBK") 不是同一个对象，因此这里只断言「非 UTF-8 的中文编码」）
        assertEquals(GBK, ConsoleStreams.charset(null, "GBK", StandardCharsets.UTF_8));
        assertNotEquals(StandardCharsets.UTF_8,
                ConsoleStreams.charset(null, "x-mswin-936", StandardCharsets.UTF_8));
        // UTF-8 终端 / macOS / Linux
        assertEquals(StandardCharsets.UTF_8, ConsoleStreams.charset(null, "UTF-8", GBK));
    }

    @Test
    void shouldFallBackWhenDetectedEncodingIsUnavailable() {
        assertEquals(StandardCharsets.UTF_8,
                ConsoleStreams.charset(null, null, StandardCharsets.UTF_8));
        assertEquals(StandardCharsets.UTF_8,
                ConsoleStreams.charset(null, "  ", StandardCharsets.UTF_8));
        assertEquals(GBK,
                ConsoleStreams.charset(null, "no-such-charset", GBK));
    }

    @Test
    void shouldHonourEncodingSystemProperty() {
        String property = ConsoleStreams.ENCODING_PROPERTY;
        String previous = System.getProperty(property);
        try {
            System.setProperty(property, "GBK");
            assertEquals(GBK, ConsoleStreams.stdinCharset());
            assertEquals(GBK, ConsoleStreams.stdoutCharset());
            assertEquals(GBK, ConsoleStreams.stderrCharset(),
                    "stderr 必须与 stdout 遵循同一套编码优先级，否则 -D 只修好一半");
        } finally {
            if (previous == null) {
                System.clearProperty(property);
            } else {
                System.setProperty(property, previous);
            }
        }
    }
}
