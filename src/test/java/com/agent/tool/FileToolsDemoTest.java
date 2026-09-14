package com.agent.tool;

import org.junit.jupiter.api.Test;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 验证 Demo 的输出编码选择规则：显式指定 &gt; 重定向场景用 UTF-8 &gt; 真实控制台用控制台编码。
 */
class FileToolsDemoTest {

    private static final Charset GBK = Charset.forName("GBK");

    @Test
    void shouldPreferExplicitlyForcedEncoding() {
        assertEquals(StandardCharsets.UTF_8,
                FileToolsDemo.chooseStdoutEncoding("UTF-8", "GBK", true));
        assertEquals(GBK,
                FileToolsDemo.chooseStdoutEncoding("GBK", "UTF-8", false));
    }

    @Test
    void shouldIgnoreBlankForcedEncoding() {
        assertEquals(StandardCharsets.UTF_8,
                FileToolsDemo.chooseStdoutEncoding("   ", "GBK", false));
    }

    @Test
    void shouldUseUtf8WhenOutputIsRedirected() {
        assertEquals(StandardCharsets.UTF_8,
                FileToolsDemo.chooseStdoutEncoding(null, "GBK", false));
    }

    @Test
    void shouldUseConsoleEncodingWhenConsoleIsAttached() {
        assertEquals(GBK,
                FileToolsDemo.chooseStdoutEncoding(null, "GBK", true));
    }

    @Test
    void shouldFallBackToUtf8WhenConsoleEncodingIsUnknown() {
        assertEquals(StandardCharsets.UTF_8,
                FileToolsDemo.chooseStdoutEncoding(null, null, true));
        assertEquals(StandardCharsets.UTF_8,
                FileToolsDemo.chooseStdoutEncoding(null, "  ", true));
    }
}
