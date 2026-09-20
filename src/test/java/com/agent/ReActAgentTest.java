package com.agent;

import com.agent.llm.FakeLLMClient;
import com.agent.llm.LLMClient;
import com.agent.llm.LLMResponse;
import com.agent.llm.Message;
import com.agent.llm.ToolCall;
import com.agent.tool.ToolRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ReAct 循环的离线验证：用 {@link FakeLLMClient} 编排响应，精确断言历史增长顺序与退出条件。
 */
class ReActAgentTest {

    @TempDir
    Path root;

    @BeforeEach
    void prepareProject() throws IOException {
        Files.writeString(root.resolve("pom.xml"), "<project>jackson okhttp</project>\n",
                StandardCharsets.UTF_8);
        Files.writeString(root.resolve(".agentignore"), "target/\n", StandardCharsets.UTF_8);
    }

    private ToolRegistry registry() {
        return new ToolRegistry(root);
    }

    private static ToolCall call(String id, String tool, String arguments) {
        return new ToolCall(id, new ToolCall.Function(tool, arguments));
    }

    @Test
    void localModeDelegatesRunToRespond() {
        Agent agent = new Agent();

        assertFalse(agent.isLlmEnabled());
        assertEquals("Hello, Agent!", agent.run("hello"));
        assertEquals("I heard: hi", agent.run("hi"));
    }

    @Test
    void shouldReturnFinalAnswerOnFirstRound() {
        FakeLLMClient fake = new FakeLLMClient(List.of(
                new LLMResponse("你好，我是 Agent。", null, 10, 5)));
        Agent agent = new Agent(fake, registry(), null);

        assertEquals("你好，我是 Agent。", agent.run("hi"));

        assertEquals(1, fake.callCount(), "没有工具调用就不该再请求第二轮");
        List<Message> history = agent.history();
        assertEquals(List.of("system", "user", "assistant"),
                history.stream().map(Message::role).toList());
    }

    @Test
    void shouldOfferToolDefinitionsToModel() {
        FakeLLMClient fake = new FakeLLMClient(List.of(
                new LLMResponse("ok", null, 1, 1)));

        new Agent(fake, registry(), null).run("hi");

        List<String> toolNames = fake.toolRequests().get(0).stream()
                .map(LLMClient.ToolDef::name).toList();
        assertEquals(List.of("list_files", "read_file", "search_code"), toolNames);
    }

    @Test
    void shouldLoopOnToolCallAndFeedObservationBackWithToolCallId() {
        FakeLLMClient fake = new FakeLLMClient(List.of(
                new LLMResponse("", List.of(call("call_1", "read_file", "{\"path\":\"pom.xml\"}")), 100, 20),
                new LLMResponse("这个项目用了 Jackson 与 OkHttp。", null, 200, 30)));
        Agent agent = new Agent(fake, registry(), null);

        String answer = agent.run("读取 pom.xml 并概括依赖");

        assertEquals("这个项目用了 Jackson 与 OkHttp。", answer);
        assertEquals(2, fake.callCount());

        List<Message> secondRequest = fake.requests().get(1);
        Message assistantCall = secondRequest.get(secondRequest.size() - 2);
        Message observation = secondRequest.get(secondRequest.size() - 1);
        assertEquals("assistant", assistantCall.role());
        assertEquals("call_1", assistantCall.toolCalls().get(0).id());
        assertEquals("tool", observation.role());
        assertEquals("call_1", observation.toolCallId(), "回传必须带原始 tool_call_id");
        assertTrue(observation.content().contains("jackson okhttp"), "工具真实执行结果应在历史里");
    }

    @Test
    void shouldExecuteEveryToolCallInOneRound() {
        FakeLLMClient fake = new FakeLLMClient(List.of(
                new LLMResponse("", List.of(
                        call("call_1", "read_file", "{\"path\":\"pom.xml\"}"),
                        call("call_2", "list_files", "{\"path\":\".\"}")), 100, 20),
                new LLMResponse("都读到了。", null, 200, 30)));
        Agent agent = new Agent(fake, registry(), null);

        assertEquals("都读到了。", agent.run("读文件并列出目录"));

        List<String> observationIds = fake.requests().get(1).stream()
                .filter(message -> "tool".equals(message.role()))
                .map(Message::toolCallId)
                .toList();
        assertEquals(List.of("call_1", "call_2"), observationIds);
    }

    @Test
    void shouldFeedUnknownToolBackAsObservation() {
        FakeLLMClient fake = new FakeLLMClient(List.of(
                new LLMResponse("", List.of(call("call_1", "no_such_tool", "{}")), 50, 5),
                new LLMResponse("抱歉，该工具不存在，我改用其他方式。", null, 80, 10)));
        Agent agent = new Agent(fake, registry(), null);

        String answer = agent.run("调用不存在的工具");

        assertTrue(answer.contains("工具不存在"));
        assertTrue(fake.requests().get(1).stream()
                        .anyMatch(message -> "tool".equals(message.role())
                                && message.content().contains("未知工具")),
                "工具错误也应作为 Observation 回传，让模型自我纠正");
    }

    @Test
    void shouldReturnErrorMessageWhenLlmCallFails() {
        LLMClient failing = (messages, tools) -> {
            throw new IOException("连接超时");
        };
        Agent agent = new Agent(failing, registry(), null);

        assertEquals("LLM 调用失败: 连接超时", agent.run("hi"));
    }

    @Test
    void shouldStopAtMaxIterations() {
        LLMClient alwaysCallsTool = (messages, tools) -> new LLMResponse(
                "", List.of(call("call_" + messages.size(), "list_files", "{\"path\":\".\"}")), 1, 1);
        Agent agent = new Agent(alwaysCallsTool, registry(), null);

        String answer = agent.run("无限调用工具");

        assertTrue(answer.contains("达到最大推理轮数限制"), "必须有安全阀，避免失控循环");
        assertTrue(answer.contains(String.valueOf(Agent.MAX_ITERATIONS)));
    }

    @Test
    void shouldClearHistoryKeepingOnlySystemPrompt() {
        FakeLLMClient fake = new FakeLLMClient(List.of(
                new LLMResponse("回答", null, 1, 1)));
        Agent agent = new Agent(fake, registry(), null);
        agent.run("hi");

        agent.clearHistory();

        assertEquals(1, agent.history().size());
        assertEquals("system", agent.history().get(0).role());
        assertEquals(Agent.SYSTEM_PROMPT, agent.history().get(0).content());
    }

    @Test
    void historySnapshotShouldBeReadOnly() {
        Agent agent = new Agent();

        assertThrows(UnsupportedOperationException.class,
                () -> agent.history().add(Message.user("注入")));
    }

    @Test
    void shouldRejectHalfConfiguredAgent() {
        assertThrows(IllegalArgumentException.class, () -> new Agent(new FakeLLMClient(List.of()), null));
        assertThrows(IllegalArgumentException.class, () -> new Agent(null, registry()));
    }
}
