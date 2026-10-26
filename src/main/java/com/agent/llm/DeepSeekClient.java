package com.agent.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/**
 * DeepSeek Chat Completions 客户端：只负责 HTTP 通信与 JSON ↔ 对象的转换。
 *
 * <p>DeepSeek 的接口与 OpenAI Chat Completions 兼容，因此请求体形状是通用的：
 * {@code messages} + 可选 {@code tools}，响应从 {@code choices[0].message} 中取
 * {@code content} / {@code tool_calls}，用量在 {@code usage} 中。
 *
 * <p>按四步理解 {@link #chat}：
 *
 * <ol>
 *   <li>把 {@link Message} 列表转成请求 JSON（含 {@code tool_calls} / {@code tool_call_id}）；</li>
 *   <li>把工具定义放进 {@code tools} 数组（JSON Schema 由 {@code ToolRegistry} 生成）；</li>
 *   <li>发带 Bearer 鉴权的 HTTP POST；</li>
 *   <li>把响应归一化成 {@link LLMResponse}。</li>
 * </ol>
 *
 * <p><b>边界</b>：本类不执行任何本地工具，也不控制 ReAct 循环 —— 那是 {@code Agent} 的职责。
 * 非 2xx 会按状态码转成可诊断的 {@link IOException}（401 鉴权 / 429 限流 / 5xx 服务端），
 * 并且只记录响应体，绝不记录 API Key。
 */
public class DeepSeekClient implements LLMClient {

    /** 默认接口地址，可用系统属性 {@code agent.deepseek.endpoint} 覆盖（测试用 MockWebServer）。 */
    public static final String DEFAULT_ENDPOINT = "https://api.deepseek.com/chat/completions";

    /**
     * 默认模型标识，可用系统属性 {@code agent.deepseek.model} 或环境变量 {@code DEEPSEEK_MODEL} 覆盖。
     *
     * <p>默认选 {@code deepseek-chat} 而不是 {@code deepseek-reasoner}：前者支持 Function Calling，
     * 后者面向纯推理场景（不保证工具调用协议）。
     */
    public static final String DEFAULT_MODEL = "deepseek-chat";

    public static final String ENDPOINT_PROPERTY = "agent.deepseek.endpoint";
    public static final String MODEL_PROPERTY = "agent.deepseek.model";
    public static final String MODEL_ENV = "DEEPSEEK_MODEL";

    private static final MediaType JSON = MediaType.parse("application/json");
    private static final int MAX_BODY_IN_MESSAGE = 500;
    private static final Logger log = LoggerFactory.getLogger(DeepSeekClient.class);

    private final String apiKey;
    private final String endpoint;
    private final String model;
    private final OkHttpClient httpClient;
    private final ObjectMapper mapper = new ObjectMapper();

    public DeepSeekClient(String apiKey) {
        this(apiKey, defaultEndpoint(), defaultModel());
    }

    public DeepSeekClient(String apiKey, String endpoint, String model) {
        this(apiKey, endpoint, model, new OkHttpClient.Builder()
                .connectTimeout(60, TimeUnit.SECONDS)
                .readTimeout(120, TimeUnit.SECONDS)
                .build());
    }

    /** 完整构造器：允许注入自定义 endpoint / model / HTTP 客户端，便于离线测试。 */
    public DeepSeekClient(String apiKey, String endpoint, String model, OkHttpClient httpClient) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalArgumentException("DEEPSEEK_API_KEY 不能为空");
        }
        this.apiKey = apiKey.trim();
        this.endpoint = Objects.requireNonNull(endpoint, "endpoint");
        this.model = Objects.requireNonNull(model, "model");
        this.httpClient = Objects.requireNonNull(httpClient, "httpClient");
    }

    /** 显式有界技能工作每次预留只允许发送一次，后续决策仍可各自建立新请求。 */
    public static DeepSeekClient singleAttempt(String apiKey, String endpoint, String model) {
        var http = new OkHttpClient.Builder().connectTimeout(60, TimeUnit.SECONDS).readTimeout(120, TimeUnit.SECONDS)
                .retryOnConnectionFailure(false).followRedirects(false).followSslRedirects(false)
                .addInterceptor(chain -> {
                    RequestBody body = Objects.requireNonNull(chain.request().body());
                    RequestBody once = new RequestBody() {
                        @Override public MediaType contentType() { return body.contentType(); }
                        @Override public long contentLength() throws IOException { return body.contentLength(); }
                        @Override public void writeTo(okio.BufferedSink sink) throws IOException { body.writeTo(sink); }
                        @Override public boolean isOneShot() { return true; }
                    };
                    return chain.proceed(chain.request().newBuilder().post(once).build());
                }).build();
        return new DeepSeekClient(apiKey, endpoint, model, http);
    }

    /** 读取系统属性 {@code agent.deepseek.endpoint}，缺省用官方地址。 */
    public static String defaultEndpoint() {
        String configured = System.getProperty(ENDPOINT_PROPERTY);
        return configured == null || configured.isBlank() ? DEFAULT_ENDPOINT : configured.trim();
    }

    /** 读取系统属性 {@code agent.deepseek.model} / 环境变量 {@code DEEPSEEK_MODEL}，缺省用 {@code deepseek-chat}。 */
    public static String defaultModel() {
        String configured = System.getProperty(MODEL_PROPERTY);
        if (configured == null || configured.isBlank()) {
            configured = System.getenv(MODEL_ENV);
        }
        return configured == null || configured.isBlank() ? DEFAULT_MODEL : configured.trim();
    }

    @Override
    public LLMResponse chat(List<Message> messages, List<ToolDef> tools) throws IOException {
        String requestJson = buildRequestJson(messages, tools);
        log.debug("DeepSeek 请求: endpoint={}, model={}, messages={}, tools={}",
                endpoint, model,
                messages == null ? 0 : messages.size(),
                tools == null ? 0 : tools.size());

        Request request = new Request.Builder()
                .url(endpoint)
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .post(RequestBody.create(requestJson, JSON))
                .build();

        try (Response response = httpClient.newCall(request).execute()) {
            String responseBody = response.body() == null ? "" : response.body().string();
            if (!response.isSuccessful()) {
                throw new IOException(describeHttpError(response.code(), responseBody));
            }
            return parseResponse(responseBody);
        }
    }

    /** 把消息历史与工具定义序列化成请求 JSON（包级可见，便于测试直接断言）。 */
    String buildRequestJson(List<Message> messages, List<ToolDef> tools) {
        ObjectNode requestBody = mapper.createObjectNode();
        requestBody.put("model", model);
        requestBody.put("stream", false);

        ArrayNode messagesArray = requestBody.putArray("messages");
        if (messages != null) {
            for (Message message : messages) {
                messagesArray.add(toJson(message));
            }
        }

        if (tools != null && !tools.isEmpty()) {
            ArrayNode toolsArray = requestBody.putArray("tools");
            for (ToolDef tool : tools) {
                ObjectNode toolNode = toolsArray.addObject();
                toolNode.put("type", "function");
                ObjectNode function = toolNode.putObject("function");
                function.put("name", tool.name());
                function.put("description", tool.description());
                function.set("parameters", tool.parameters());
            }
        }
        return requestBody.toString();
    }

    private ObjectNode toJson(Message message) {
        ObjectNode node = mapper.createObjectNode();
        node.put("role", message.role());
        if (message.content() != null) {
            node.put("content", message.content());
        }
        if (message.toolCalls() != null && !message.toolCalls().isEmpty()) {
            ArrayNode calls = node.putArray("tool_calls");
            for (ToolCall call : message.toolCalls()) {
                ObjectNode callNode = calls.addObject();
                callNode.put("id", call.id());
                callNode.put("type", "function");
                ObjectNode function = callNode.putObject("function");
                function.put("name", call.name());
                function.put("arguments", call.arguments());
            }
        }
        if (message.toolCallId() != null) {
            node.put("tool_call_id", message.toolCallId());
        }
        return node;
    }

    private LLMResponse parseResponse(String responseBody) throws IOException {
        JsonNode root;
        try {
            root = mapper.readTree(responseBody);
        } catch (IOException malformed) {
            throw new IOException("DeepSeek 响应不是合法 JSON: " + truncate(responseBody), malformed);
        }

        JsonNode choices = root.path("choices");
        if (!choices.isArray() || choices.isEmpty()) {
            throw new IOException("DeepSeek 响应中缺少 choices: " + truncate(responseBody));
        }

        JsonNode message = choices.get(0).path("message");
        JsonNode contentNode = message.path("content");
        // content 为 null 时必须仍然能解析 tool_calls —— 模型只发起工具调用时就没有文本。
        String content = contentNode.isNull() || contentNode.isMissingNode()
                ? "" : contentNode.asText("");

        List<ToolCall> toolCalls = null;
        JsonNode callsNode = message.path("tool_calls");
        if (callsNode.isArray() && !callsNode.isEmpty()) {
            toolCalls = new ArrayList<>();
            for (JsonNode callNode : callsNode) {
                JsonNode function = callNode.path("function");
                toolCalls.add(new ToolCall(
                        callNode.path("id").asText(),
                        new ToolCall.Function(
                                function.path("name").asText(),
                                function.path("arguments").asText("{}"))));
            }
        }

        JsonNode usage = root.path("usage");
        return new LLMResponse(
                content,
                toolCalls,
                tokenCount(usage.path("prompt_tokens")),
                tokenCount(usage.path("completion_tokens")));
    }

    private int tokenCount(JsonNode value) {
        return value.isIntegralNumber() && value.canConvertToInt() && value.intValue() >= 0 ? value.intValue() : -1;
    }

    /** 按状态码给出可诊断的错误信息，并保留响应体（服务端通常会说明具体原因）。 */
    static String describeHttpError(int code, String body) {
        String detail = truncate(body);
        String hint;
        if (code == 401 || code == 403) {
            hint = "鉴权失败：DEEPSEEK_API_KEY 无效、已过期或格式不对";
        } else if (code == 402) {
            hint = "余额不足，请到 DeepSeek 控制台充值";
        } else if (code == 429) {
            hint = "请求过于频繁或额度不足";
        } else if (code >= 500) {
            hint = "DeepSeek 服务端异常，可稍后重试";
        } else {
            hint = "请求被拒绝";
        }
        return "DeepSeek HTTP " + code + "（" + hint + "）：" + detail;
    }

    private static String truncate(String text) {
        if (text == null) {
            return "";
        }
        return text.length() <= MAX_BODY_IN_MESSAGE
                ? text
                : text.substring(0, MAX_BODY_IN_MESSAGE) + "...";
    }
}
