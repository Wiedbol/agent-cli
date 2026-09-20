package com.agent.llm;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LLMResponseTest {

    @Test
    void shouldReportToolCallsWhenPresent() {
        LLMResponse response = new LLMResponse("", List.of(
                new ToolCall("call_1", new ToolCall.Function("list_files", "{}"))), 10, 5);

        assertTrue(response.hasToolCalls());
        assertEquals(10, response.promptTokens());
        assertEquals(5, response.completionTokens());
    }

    @Test
    void shouldTreatNullAndEmptyToolCallsAsFinalAnswer() {
        assertFalse(new LLMResponse("answer", null, 0, 0).hasToolCalls());
        assertFalse(new LLMResponse("answer", List.of(), 0, 0).hasToolCalls());
    }

    @Test
    void shouldCopyToolCallsDefensively() {
        List<ToolCall> mutable = new ArrayList<>();
        mutable.add(new ToolCall("call_1", new ToolCall.Function("f", "{}")));

        LLMResponse response = new LLMResponse("", mutable, 0, 0);
        mutable.clear();

        assertEquals(1, response.toolCalls().size());
    }

    @Test
    void shouldRejectNullToolCallElements() {
        List<ToolCall> withNull = new ArrayList<>();
        withNull.add(null);

        assertThrows(NullPointerException.class, () -> new LLMResponse("", withNull, 0, 0));
    }
}
