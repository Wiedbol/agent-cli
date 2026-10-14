package com.agent.tool;

/**
 * 工具执行结果。
 *
 * <p>失败不是异常而是<b>结果</b>：调用方（执行员 → 控制员）会把失败文本当作 Observation
 * 回传给模型，让模型自己决定改参数还是换做法。
 *
 * @param toolName       工具名
 * @param success        是否成功
 * @param output         回传给模型的文本（成功是内容，失败是原因）
 * @param durationMillis 执行耗时，用于观测与控制台输出
 */
public record ToolResult(String toolName, boolean success, String output, long durationMillis) {

    public static ToolResult ok(String toolName, String output, long durationMillis) {
        return new ToolResult(toolName, true, output, durationMillis);
    }

    public static ToolResult fail(String toolName, String message, long durationMillis) {
        return new ToolResult(toolName, false, message, durationMillis);
    }

    /** 回传给模型时统一加失败标记，便于模型识别「这条 Observation 是错误」。 */
    public String toObservation() {
        String text = output == null ? "" : output;
        return success ? text : "[工具执行失败] " + text;
    }
}
