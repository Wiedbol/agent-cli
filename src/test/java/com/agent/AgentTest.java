package com.agent;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;

class AgentTest {

    @Test
    void shouldGreetOnHello() {
        assertEquals("Hello, Agent!", new Agent().respond("hello"));
    }

    @Test
    void shouldEchoOtherInput() {
        assertEquals("I heard: hi", new Agent().respond("hi"));
    }

    @Test
    void shouldGreetOnBlankInput() {
        assertEquals("Hello, Agent!", new Agent().respond("   "));
    }
}
