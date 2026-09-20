package com.agent.llm;

import java.util.List;
import java.util.Objects;

/**
 * 一条对话历史消息（Agent 的工作记忆）。
 *
 * <p>四种角色的字段组合是固定的：
 *
 * <ul>
 *   <li>{@code system} / {@code user}：只有 {@code content}；</li>
 *   <li>{@code assistant}：只有 {@code content}，或只有 {@code toolCalls}；</li>
 *   <li>{@code tool}：只有 {@code content} 与 {@code toolCallId}。</li>
 * </ul>
 *
 * <p>{@code toolCalls} 与 {@code toolCallId} 分别表示「我要调用工具」与「这是我调用过的工具的
 * 结果」，语义互斥，因此同一条消息不允许两者同时非空 —— 这个不变量由紧凑构造器强制。
 *
 * @param role       角色：{@code system} / {@code user} / {@code assistant} / {@code tool}
 * @param content    文本内容，可为 {@code null}（模型只发起工具调用时没有文本）
 * @param toolCalls  工具调用意图，仅 {@code assistant} 消息可能非空
 * @param toolCallId 工具调用关联 ID，仅 {@code tool} 消息非空
 */
public record Message(
        String role,
        String content,
        List<ToolCall> toolCalls,
        String toolCallId) {

    public static final String ROLE_SYSTEM = "system";
    public static final String ROLE_USER = "user";
    public static final String ROLE_ASSISTANT = "assistant";
    public static final String ROLE_TOOL = "tool";

    public Message {
        Objects.requireNonNull(role, "role");
        if (toolCalls != null && toolCallId != null) {
            throw new IllegalArgumentException("toolCalls 与 toolCallId 不能同时非空");
        }
        toolCalls = toolCalls == null ? null : List.copyOf(toolCalls);
    }

    public static Message system(String content) {
        return new Message(ROLE_SYSTEM, content, null, null);
    }

    public static Message user(String content) {
        return new Message(ROLE_USER, content, null, null);
    }

    public static Message assistant(String content) {
        return new Message(ROLE_ASSISTANT, content, null, null);
    }

    public static Message assistant(String content, List<ToolCall> toolCalls) {
        return new Message(ROLE_ASSISTANT, content, toolCalls, null);
    }

    public static Message tool(String toolCallId, String content) {
        return new Message(ROLE_TOOL, content, null, toolCallId);
    }
}
