package com.agent.skill;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChatKnowledgeTest {
    @Test
    void shouldRequireLongerPrefixWhenTwoIdsMatch() throws Exception {
        String first = "d2c8347b-57f9-4fcb-bacb-68da11d50d7e";
        String second = "d2c8347b-608a-4fcb-bacb-68da11d50d7e";
        var ambiguous = assertThrows(IOException.class,
                () -> ChatKnowledge.resolvePrefix("d2c8347b", Stream.of(first, second)));
        assertTrue(ambiguous.getMessage().contains("请输入更多位"));
        assertEquals(first, ChatKnowledge.resolvePrefix("d2c8347b-5", Stream.of(first, first, second)));
        assertThrows(IOException.class, () -> ChatKnowledge.resolvePrefix("abcd", Stream.of(first, second)));
    }
}
