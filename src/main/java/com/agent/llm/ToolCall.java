package com.agent.llm;

/**
 * 模型返回的一次工具调用意图。
 *
 * <p>{@code arguments} 是<b>包含 JSON 的字符串</b>（例如 {@code {"path":"pom.xml"}}），
 * 而不是已经解析好的对象 —— 解析这一步交给 {@code ToolRegistry}，因为「怎么解释参数」
 * 是工具侧的职责，不是协议侧的职责。
 *
 * @param id       本次调用的唯一标识，回传结果时必须原样带回
 * @param function 工具名与参数 JSON
 */
public record ToolCall(String id, Function function) {

    public record Function(String name, String arguments) {
    }

    /** 便捷访问器：{@code function} 缺失时返回 {@code null} 而不是抛空指针。 */
    public String name() {
        return function == null ? null : function.name();
    }

    /** 便捷访问器：{@code function} 缺失时返回 {@code null} 而不是抛空指针。 */
    public String arguments() {
        return function == null ? null : function.arguments();
    }
}
