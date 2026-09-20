package com.agent.llm;

import com.fasterxml.jackson.databind.JsonNode;

import java.io.IOException;
import java.util.List;

/**
 * 大模型客户端接口：Agent 依赖这个接口而不是 {@code GlmClient} 具体类。
 *
 * <p>这样做的收益有两个：一是单元测试可以用 {@code FakeLLMClient} 离线编排响应，
 * 不联网、不消耗 Token；二是将来换成别的兼容模型时，Agent 一行都不用改。
 */
public interface LLMClient {

    /**
     * 工具的协议描述。
     *
     * @param name        工具名，模型按此名称发起调用
     * @param description 给模型看的能力说明，决定模型「知不知道何时该用它」
     * @param parameters  JSON Schema
     */
    record ToolDef(String name, String description, JsonNode parameters) {
    }

    /**
     * 发送一次对话请求。
     *
     * @param messages 完整消息历史（含 system / user / assistant / tool）
     * @param tools    可用工具定义，没有工具时传空列表
     * @throws IOException 网络错误或服务端返回非 2xx
     */
    LLMResponse chat(List<Message> messages, List<ToolDef> tools) throws IOException;
}
