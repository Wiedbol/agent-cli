package com.agent;

import org.junit.jupiter.api.Test;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 验证「控制台编码」的选择规则：显式指定 &gt; 非终端用 UTF-8 &gt; 终端用控制台编码。
 */
class ConsoleStreamsTest {

    private static final Charset GBK = Charset.forName("GBK");

    @Test
    void shouldPreferExplicitlyForcedEncoding() {
        assertEquals(StandardCharsets.UTF_8,
                ConsoleStreams.charset("UTF-8", "GBK", true));
        assertEquals(GBK,
                ConsoleStreams.charset("GBK", "UTF-8", false));
    }

    @Test
    void shouldIgnoreBlankForcedEncoding() {
        assertEquals(StandardCharsets.UTF_8,
                ConsoleStreams.charset("   ", "GBK", false));
    }

    @Test
    void shouldUseUtf8WhenNotConnectedToTerminal() {
        // 重定向、管道、IDE 运行窗口、CI：现代工具链按 UTF-8 解码
        assertEquals(StandardCharsets.UTF_8,
                ConsoleStreams.charset(null, "GBK", false));
    }

    @Test
    void shouldUseConsoleEncodingWhenTerminalAttached() {
        assertEquals(GBK,
                ConsoleStreams.charset(null, "GBK", true));
    }

    @Test
    void shouldFallBackToUtf8WhenConsoleEncodingIsUnknown() {
        assertEquals(StandardCharsets.UTF_8,
                ConsoleStreams.charset(null, null, true));
        assertEquals(StandardCharsets.UTF_8,
                ConsoleStreams.charset(null, "  ", true));
    }

    @Test
    void shouldHonourEncodingSystemProperty() {
        String property = ConsoleStreams.ENCODING_PROPERTY;
        String previous = System.getProperty(property);
        try {
            System.setProperty(property, "GBK");
            assertEquals(GBK, ConsoleStreams.stdinCharset());
            assertEquals(GBK, ConsoleStreams.stdoutCharset());
        } finally {
            if (previous == null) {
                System.clearProperty(property);
            } else {
                System.setProperty(property, previous);
            }
        }
    }
}
