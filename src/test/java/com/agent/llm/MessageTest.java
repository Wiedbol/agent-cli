package com.agent.llm;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MessageTest {

    @Test
    void shouldBuildSystemUserAssistantAndToolMessages() {
        assertEquals("system", Message.system("s").role());
        assertEquals("user", Message.user("u").role());
        assertEquals("assistant", Message.assistant("a").role());
        assertEquals("tool", Message.tool("call_1", "r").role());
        assertEquals("call_1", Message.tool("call_1", "r").toolCallId());
        assertNull(Message.system("s").toolCalls());
        assertNull(Message.system("s").toolCallId());
    }

    @Test
    void assistantMessageCanCarryToolCalls() {
        ToolCall call = new ToolCall("call_1", new ToolCall.Function("read_file", "{}"));

        Message message = Message.assistant("", List.of(call));

        assertEquals(1, message.toolCalls().size());
        assertNull(message.toolCallId(), "发起调用的 assistant 消息不应带 tool_call_id");
        assertEquals("read_file", message.toolCalls().get(0).name());
    }

    @Test
    void shouldRejectMessageWithBothToolCallsAndToolCallId() {
        List<ToolCall> calls = List.of(new ToolCall("c", new ToolCall.Function("f", "{}")));

        assertThrows(IllegalArgumentException.class,
                () -> new Message("assistant", "x", calls, "call_1"));
    }

    @Test
    void shouldRejectNullRole() {
        assertThrows(NullPointerException.class,
                () -> new Message(null, "x", null, null));
    }

    @Test
    void shouldCopyToolCallsDefensively() {
        List<ToolCall> mutable = new ArrayList<>();
        mutable.add(new ToolCall("c", new ToolCall.Function("f", "{}")));

        Message message = Message.assistant("", mutable);
        mutable.clear();

        assertEquals(1, message.toolCalls().size(), "内部列表不应被外部修改影响");
        assertThrows(UnsupportedOperationException.class,
                () -> message.toolCalls().add(new ToolCall("c2", new ToolCall.Function("f", "{}"))));
    }

    @Test
    void toolCallAccessorsAreNullSafe() {
        ToolCall broken = new ToolCall("call_1", null);

        assertNull(broken.name());
        assertNull(broken.arguments());
        assertTrue(broken.id().startsWith("call_"));
    }
}
