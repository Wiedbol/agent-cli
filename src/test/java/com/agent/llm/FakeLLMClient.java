package com.agent.llm;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;

/**
 * 离线测试替身：按预先编排的脚本依次返回响应，并记录每次收到的消息历史与工具定义。
 *
 * <p>Agent 的行为是非确定性的外部依赖，用真实模型测会「联网 + 花钱 + 结果不稳定」；
 * 用 Fake 之后，循环控制逻辑可以被精确断言（第几次请求里有没有 {@code tool_call_id} 等）。
 */
public class FakeLLMClient implements LLMClient {

    private final Queue<LLMResponse> script;
    private final List<List<Message>> requests = new ArrayList<>();
    private final List<List<ToolDef>> toolRequests = new ArrayList<>();

    public FakeLLMClient(List<LLMResponse> responses) {
        this.script = new ArrayDeque<>(responses);
    }

    @Override
    public LLMResponse chat(List<Message> messages, List<ToolDef> tools) {
        requests.add(List.copyOf(messages));
        toolRequests.add(tools == null ? List.of() : List.copyOf(tools));
        return script.isEmpty()
                ? new LLMResponse("（无更多响应）", null, 0, 0)
                : script.remove();
    }

    public List<List<Message>> requests() {
        return List.copyOf(requests);
    }

    public List<List<ToolDef>> toolRequests() {
        return List.copyOf(toolRequests);
    }

    public int callCount() {
        return requests.size();
    }
}
