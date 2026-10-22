package com.agent.tool;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;

/**
 * 统一 Tool 接口（V4）：把 Lab02/Lab03 里「签名各不相同」的文件工具抽象成同一形状。
 *
 * <pre>
 * 名字 + 描述 + JSON Schema  ──► 交给 LLM 决定调不调、怎么调
 * 风险等级 + 路径参数         ──► 交给守卫做安全裁决
 * execute(ToolRequest)       ──► 交给执行员真正执行
 * </pre>
 *
 * <p>三类信息刻意声明在接口上，而不是让调用方去猜：
 *
 * <ul>
 *   <li>{@link #risk()} 让守卫知道这次操作会不会改文件、会不会起进程；</li>
 *   <li>{@link #pathArguments()} 让守卫知道该检查哪些参数里的路径是否越界；</li>
 *   <li>{@link #parameters()} 是给模型看的 JSON Schema，决定它「知不知道怎么传参」。</li>
 * </ul>
 */
public interface Tool {

    /** 工具对系统的影响面，守卫据此决定放行 / 需确认 / 拒绝。 */
    enum Risk {
        /** 只读取信息，不改动任何状态。 */
        READ,
        /** 会创建或修改文件。 */
        WRITE,
        /** 会启动外部进程（可能产生任意副作用）。 */
        EXECUTE
    }

    /** 工具名，模型按此名称发起调用，必须全局唯一。 */
    String name();

    /** 给模型看的能力说明，直接决定模型「知不知道什么时候该用它」。 */
    String description();

    /** 参数的 JSON Schema。 */
    JsonNode parameters();

    /** 风险等级。 */
    Risk risk();

    /**
     * 哪些参数是路径（相对项目根目录）。
     *
     * <p>守卫会对这些参数逐个做「归一化后仍位于项目根目录内」的检查。
     */
    default List<String> pathArguments() {
        return List.of();
    }

    /**
     * 执行工具。
     *
     * <p>允许抛异常：执行员会捕获并转换成失败结果，绝不会让异常穿透到控制员之外。
     */
    ToolResult execute(ToolRequest request) throws Exception;

    /** 只读预览必须先于人工确认；普通工具没有可审阅的文本修改。 */
    default String preview(ToolRequest request) throws Exception { return ""; }
}
