package com.agent.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 用本地 MockWebServer 离线验证 DeepSeek 协议：请求形状、鉴权头、响应解析、错误诊断。
 *
 * <p>这些用例不需要联网、不需要真实 API Key，却覆盖了 {@code DeepSeekClient} 的全部代码路径；
 * 真实接口的连通性验证（AT01/AT03/AT04）另见实验报告。
 */
class DeepSeekClientTest {

    private static final String CONTENT_RESPONSE = """
            {
              "choices": [{"message": {"role": "assistant", "content": "你好，我是 Agent。"}}],
              "usage": {"prompt_tokens": 11, "completion_tokens": 7}
            }
            """;

    private static final String TOOL_CALL_RESPONSE = """
            {
              "choices": [{
                "message": {
                  "role": "assistant",
                  "content": null,
                  "tool_calls": [{
                    "id": "call_abc",
                    "type": "function",
                    "function": {"name": "read_file", "arguments": "{\\"path\\":\\"pom.xml\\"}"}
                  }]
                }
              }],
              "usage": {"prompt_tokens": 100, "completion_tokens": 20}
            }
            """;

    private final ObjectMapper mapper = new ObjectMapper();
    private MockWebServer server;

    @BeforeEach
    void startServer() throws IOException {
        server = new MockWebServer();
        server.start();
    }

    @AfterEach
    void stopServer() throws IOException {
        server.shutdown();
    }

    private DeepSeekClient client() {
        return new DeepSeekClient("test-key",
                server.url("/chat/completions").toString(), "deepseek-chat");
    }

    private void enqueueJson(int code, String body) {
        server.enqueue(new MockResponse()
                .setResponseCode(code)
                .setHeader("Content-Type", "application/json")
                .setBody(body));
    }

    @Test
    void shouldSendBearerAuthAndOpenAiCompatibleBody() throws Exception {
        enqueueJson(200, CONTENT_RESPONSE);

        LLMResponse response = client().chat(
                List.of(Message.system("你是助手"), Message.user("你好")), List.of());

        assertEquals("你好，我是 Agent。", response.content());
        assertFalse(response.hasToolCalls());
        assertEquals(11, response.promptTokens());
        assertEquals(7, response.completionTokens());

        RecordedRequest request = server.takeRequest();
        assertEquals("POST", request.getMethod());
        assertEquals("/chat/completions", request.getPath());
        assertEquals("Bearer test-key", request.getHeader("Authorization"));
        assertTrue(request.getHeader("Content-Type").startsWith("application/json"));

        JsonNode body = mapper.readTree(request.getBody().readUtf8());
        assertEquals("deepseek-chat", body.path("model").asText());
        assertFalse(body.path("stream").asBoolean(true), "应显式关闭流式返回");
        assertEquals(2, body.path("messages").size());
        assertEquals("system", body.path("messages").get(0).path("role").asText());
        assertEquals("你好", body.path("messages").get(1).path("content").asText());
        assertFalse(body.has("tools"), "没有工具时不应发送 tools 字段");
    }

    @Test
    void shouldParseToolCallsEvenWhenContentIsNull() throws Exception {
        enqueueJson(200, TOOL_CALL_RESPONSE);

        LLMResponse response = client().chat(List.of(Message.user("读 pom")), List.of());

        assertTrue(response.hasToolCalls());
        assertEquals("", response.content(), "content 为 null 时应归一化成空串");
        ToolCall call = response.toolCalls().get(0);
        assertEquals("call_abc", call.id());
        assertEquals("read_file", call.name());
        assertEquals("{\"path\":\"pom.xml\"}", call.arguments());
    }

    @Test
    void shouldSerializeToolDefinitionsAndToolHistory() throws Exception {
        enqueueJson(200, CONTENT_RESPONSE);

        ObjectNode schema = mapper.createObjectNode();
        ObjectNode requestBody = schema;
        requestBody.put("type", "object");
        requestBody.putObject("properties").putObject("path").put("type", "string");

        ToolCall call = new ToolCall("call_abc", new ToolCall.Function("read_file", "{\"path\":\"pom.xml\"}"));
        List<Message> history = List.of(
                Message.system("system"),
                Message.user("读 pom"),
                Message.assistant("", List.of(call)),
                Message.tool("call_abc", "文件内容:\n<project/>"));

        client().chat(history, List.of(new LLMClient.ToolDef(
                "read_file", "读取文件", schema)));

        JsonNode body = mapper.readTree(server.takeRequest().getBody().readUtf8());
        JsonNode toolFunction = body.path("tools").get(0).path("function");
        assertEquals("function", body.path("tools").get(0).path("type").asText());
        assertEquals("read_file", toolFunction.path("name").asText());
        assertEquals("object", toolFunction.path("parameters").path("type").asText());

        JsonNode messages = body.path("messages");
        JsonNode assistantCall = messages.get(2).path("tool_calls").get(0);
        assertEquals("call_abc", assistantCall.path("id").asText());
        assertEquals("read_file", assistantCall.path("function").path("name").asText());
        assertEquals("{\"path\":\"pom.xml\"}", assistantCall.path("function").path("arguments").asText());
        assertEquals("call_abc", messages.get(3).path("tool_call_id").asText());
        assertEquals("tool", messages.get(3).path("role").asText());
    }

    @Test
    void shouldReportAuthErrorWithStatusAndBody() {
        enqueueJson(401, "{\"error\":{\"message\":\"invalid api key\"}}");

        IOException failure = assertThrows(IOException.class,
                () -> client().chat(List.of(Message.user("hi")), List.of()));

        assertTrue(failure.getMessage().contains("401"));
        assertTrue(failure.getMessage().contains("DEEPSEEK_API_KEY"));
        assertTrue(failure.getMessage().contains("invalid api key"),
                "错误信息必须保留响应体，否则无法诊断");
    }

    @Test
    void shouldReportRateLimitAndServerErrors() {
        enqueueJson(429, "{\"error\":{\"message\":\"rate limited\"}}");
        IOException rateLimited = assertThrows(IOException.class,
                () -> client().chat(List.of(Message.user("hi")), List.of()));
        assertTrue(rateLimited.getMessage().contains("429"));

        enqueueJson(503, "{\"error\":{\"message\":\"overloaded\"}}");
        IOException serverError = assertThrows(IOException.class,
                () -> client().chat(List.of(Message.user("hi")), List.of()));
        assertTrue(serverError.getMessage().contains("503"));
    }

    @Test
    void shouldRejectResponseWithoutChoices() {
        enqueueJson(200, "{\"error\":{\"message\":\"bad request\"}}");

        IOException failure = assertThrows(IOException.class,
                () -> client().chat(List.of(Message.user("hi")), List.of()));

        assertTrue(failure.getMessage().contains("choices"));
    }

    @Test
    void shouldRejectResponseThatIsNotJson() {
        enqueueJson(200, "<html>gateway error</html>");

        IOException failure = assertThrows(IOException.class,
                () -> client().chat(List.of(Message.user("hi")), List.of()));

        assertTrue(failure.getMessage().contains("合法 JSON"));
    }

    @Test
    void shouldRejectBlankApiKey() {
        assertThrows(IllegalArgumentException.class, () -> new DeepSeekClient("   "));
        assertThrows(IllegalArgumentException.class, () -> new DeepSeekClient(null));
    }

    @Test
    void defaultsShouldPointAtDeepSeek() {
        assertEquals("deepseek-chat", DeepSeekClient.DEFAULT_MODEL);
        assertTrue(DeepSeekClient.DEFAULT_ENDPOINT.startsWith("https://api.deepseek.com/"));
        assertNull(System.getProperty(DeepSeekClient.ENDPOINT_PROPERTY),
                "默认不应依赖系统属性，属性只作为覆盖手段");
    }
}
