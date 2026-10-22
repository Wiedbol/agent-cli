package com.agent.llm;

import java.util.List;

/**
 * 一次模型响应的归一化结果。
 *
 * <p>{@link #hasToolCalls()} 是 Agent 循环<b>唯一</b>的分支判据：有工具调用就继续
 * 「执行 → 回传 → 再问」，没有就把 {@code content} 当作最终回答返回。
 *
 * @param content          模型文本内容，可能为空串
 * @param toolCalls        工具调用列表，没有时为 {@code null}
 * @param promptTokens     输入 token 数（供观测与报告记录），-1 表示未提供用量
 * @param completionTokens 输出 token 数，-1 表示未提供用量
 */
public record LLMResponse(
        String content,
        List<ToolCall> toolCalls,
        int promptTokens,
        int completionTokens) {

    public LLMResponse {
        toolCalls = toolCalls == null ? null : List.copyOf(toolCalls);
    }

    public boolean hasToolCalls() {
        return toolCalls != null && !toolCalls.isEmpty();
    }
}
