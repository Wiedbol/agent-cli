package com.agent.roles;

import com.agent.llm.FakeLLMClient;
import com.agent.llm.LLMResponse;
import com.agent.llm.Message;
import com.agent.llm.ToolCall;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** LLM 角色：消息历史的组织方式（顺序错了服务端会直接报配对错误）。 */
class LlmRoleTest {

    private final FakeLLMClient client = new FakeLLMClient(List.of());

    @Test
    void shouldStartWithSystemPromptOnly() {
        LlmRole llm = new LlmRole(client);

        assertEquals(1, llm.history().size());
        assertEquals("system", llm.history().get(0).role());
        assertEquals(LlmRole.SYSTEM_PROMPT, llm.history().get(0).content());
    }

    @Test
    void shouldAppendUserTask() {
        LlmRole llm = new LlmRole(client);

        llm.beginTask("把 README 里的 TODO 汇总");

        assertEquals(List.of("system", "user"), llm.history().stream().map(Message::role).toList());
        assertEquals("把 README 里的 TODO 汇总", llm.history().get(1).content());
    }

    @Test
    void shouldKeepAssistantToolCallBeforeToolObservation() {
        LlmRole llm = new LlmRole(client);
        ToolCall call = new ToolCall("call_1", new ToolCall.Function("read_file", "{\"path\":\"pom.xml\"}"));
        LLMResponse response = new LLMResponse("", List.of(call), 10, 5);

        llm.beginTask("读 pom");
        llm.recordToolCalls(response);
        llm.recordToolResult(call, "文件内容:\n<project/>");

        List<Message> history = llm.history();
        assertEquals(List.of("system", "user", "assistant", "tool"),
                history.stream().map(Message::role).toList());
        assertEquals("call_1", history.get(2).toolCalls().get(0).id());
        assertEquals("call_1", history.get(3).toolCallId());
    }

    @Test
    void shouldRecordFinalAnswer() {
        LlmRole llm = new LlmRole(client);

        llm.beginTask("你好");
        llm.recordFinalAnswer("你好，我是 Agent。");

        assertEquals("assistant", llm.history().get(2).role());
        assertEquals("你好，我是 Agent。", llm.history().get(2).content());
    }

    @Test
    void shouldResetHistoryKeepingSystemPrompt() {
        LlmRole llm = new LlmRole(client);
        llm.beginTask("任务");
        llm.recordFinalAnswer("回答");

        llm.reset();

        assertEquals(1, llm.history().size());
        assertEquals("system", llm.history().get(0).role());
    }

    @Test
    void shouldReturnReadOnlyHistorySnapshot() {
        LlmRole llm = new LlmRole(client);

        assertThrows(UnsupportedOperationException.class,
                () -> llm.history().add(Message.user("注入")));
    }

    @Test
    void shouldPassToolDefinitionsToTheClient() throws Exception {
        FakeLLMClient fake = new FakeLLMClient(List.of(new LLMResponse("ok", null, 1, 1)));
        LlmRole llm = new LlmRole(fake);

        llm.ask(List.of(new com.agent.llm.LLMClient.ToolDef("read_file", "读文件", null)));

        assertEquals(1, fake.toolRequests().get(0).size());
        assertEquals("read_file", fake.toolRequests().get(0).get(0).name());
        assertTrue(fake.requests().get(0).get(0).content().contains("编程助手"));
    }

    @Test
    void shouldRejectNullClient() {
        assertThrows(NullPointerException.class, () -> new LlmRole(null));
    }
}
