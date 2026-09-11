package com.agent;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;

class AgentTest {

    private final Agent agent = new Agent();

    @Test
    void shouldGreetOnHello() {
        assertEquals("Hello, Agent!", agent.respond("hello"));
    }

    @Test
    void shouldEchoOtherInput() {
        assertEquals("I heard: hi", agent.respond("hi"));
    }

    @Test
    void shouldGreetOnBlankInput() {
        assertEquals("Hello, Agent!", agent.respond("   "));
    }

    // ---- 以下为学生在 AI 生成测试之外补充的边界测试 ----

    @Test
    void shouldGreetOnEmptyInput() {
        assertEquals("Hello, Agent!", agent.respond(""));
    }

    @Test
    void shouldGreetOnNullInput() {
        assertEquals("Hello, Agent!", agent.respond(null));
    }

    @Test
    void shouldGreetOnHelloRegardlessOfCase() {
        assertEquals("Hello, Agent!", agent.respond("HELLO"));
        assertEquals("Hello, Agent!", agent.respond("Hello"));
        assertEquals("Hello, Agent!", agent.respond("hElLo"));
    }

    @Test
    void shouldGreetOnHelloWithSurroundingSpaces() {
        assertEquals("Hello, Agent!", agent.respond("  hello  "));
    }

    @Test
    void shouldEchoInputVerbatimWhenNotGreeting() {
        assertEquals("I heard:   hi  ", agent.respond("  hi  "));
    }

    @Test
    void shouldEchoNonAsciiInput() {
        assertEquals("I heard: 你好", agent.respond("你好"));
    }
}
