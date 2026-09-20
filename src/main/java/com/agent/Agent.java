package com.agent;

import com.agent.llm.LLMClient;
import com.agent.llm.LLMResponse;
import com.agent.llm.Message;
import com.agent.llm.ToolCall;
import com.agent.tool.ToolRegistry;

import java.io.PrintStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Agent：既保留 Lab01 的本地响应逻辑，又在 Lab03 中承担 ReAct 循环。
 *
 * <p>两种模式由构造器区分，避免把「没有模型可用」当成一种错误：
 *
 * <ul>
 *   <li>{@code new Agent()} —— 本地模式：{@link #run(String)} 退化为 {@link #respond(String)}，
 *       行为与 Lab01 完全一致，也让原有测试与管道用法继续有效；</li>
 *   <li>{@code new Agent(llmClient, toolRegistry)} —— 模型模式：{@link #run(String)} 执行
 *       「模型决策 → 执行工具 → 回传观察 → 继续决策」的闭环。</li>
 * </ul>
 *
 * <p>本类只维护消息历史与控制循环，不实现任何文件操作（那是 {@code ToolRegistry} 与 Lab02 工具的
 * 职责），也不做 HTTP 通信（那是 {@link LLMClient} 实现的职责）。
 */
public class Agent {

    /** 循环安全阀：连续多轮都在调用工具时强制退出，避免失控与无限消耗。 */
    public static final int MAX_ITERATIONS = 10;

    /** 系统提示词：告诉模型身份、可用工具与「需要项目信息时先调用工具」的行为约束。 */
    public static final String SYSTEM_PROMPT = """
            你是一个 Java 编程助手 Agent，可以读取和搜索用户的项目代码。

            可用工具：
            1. list_files：列出目录下的文件；
            2. read_file：读取文件内容；
            3. search_code：搜索代码。

            需要了解项目代码时，请调用工具，不要凭空猜测文件内容。
            获得工具结果后，再基于结果继续完成任务。
            请用中文回复用户。
            """;

    private static final int TRACE_PREVIEW_CHARS = 200;

    private final LLMClient llm;
    private final ToolRegistry registry;
    private final PrintStream trace;
    private final List<Message> history = new ArrayList<>();

    /** 本地模式（Lab01 行为）：不访问网络，{@code run} 等价于 {@code respond}。 */
    public Agent() {
        this(null, null, null);
    }

    /** 模型模式，过程信息打印到标准输出。 */
    public Agent(LLMClient llm, ToolRegistry registry) {
        this(llm, registry, System.out);
    }

    /**
     * 模型模式，过程信息打印到指定流。
     *
     * @param trace 过程输出流；传 {@code null} 表示静默（单元测试用）
     */
    public Agent(LLMClient llm, ToolRegistry registry, PrintStream trace) {
        if ((llm == null) != (registry == null)) {
            throw new IllegalArgumentException("LLMClient 与 ToolRegistry 必须同时提供或同时省略");
        }
        this.llm = llm;
        this.registry = registry;
        this.trace = trace;
        history.add(Message.system(SYSTEM_PROMPT));
    }

    /** 是否已接入大模型（false 表示本地模式）。 */
    public boolean isLlmEnabled() {
        return llm != null;
    }

    /**
     * Lab01 的本地响应逻辑：问候语与其余输入的原样回显。
     *
     * @param input 用户输入，可为 {@code null}
     */
    public String respond(String input) {
        if (input == null || input.isBlank()) {
            return "Hello, Agent!";
        }
        if ("hello".equalsIgnoreCase(input.trim())) {
            return "Hello, Agent!";
        }
        return "I heard: " + input;
    }

    /**
     * 处理一次用户输入。
     *
     * <p>本地模式下等价于 {@link #respond(String)}；模型模式下执行 ReAct 循环：
     *
     * <pre>
     * LLMResponse
     *    ├── hasToolCalls() == true  → 保存 assistant 调用 → 逐个执行工具
     *    │                             → 回传 role=tool 的 Observation → 下一轮
     *    └── hasToolCalls() == false → 保存并返回最终回答 → 退出
     * </pre>
     *
     * @return 最终回答（或错误说明，绝不抛异常给调用方）
     */
    public String run(String userInput) {
        if (llm == null) {
            return respond(userInput);
        }

        history.add(Message.user(userInput));

        for (int round = 1; round <= MAX_ITERATIONS; round++) {
            trace("思考中……第 " + round + " 轮");

            LLMResponse response;
            try {
                response = llm.chat(history, registry.getToolDefinitions());
            } catch (Exception failed) {
                return "LLM 调用失败: " + failed.getMessage();
            }

            trace("Token：输入=" + response.promptTokens() + "，输出=" + response.completionTokens());

            if (response.hasToolCalls()) {
                // 顺序不能错：先保存「模型发起的调用」，再逐个回传结果，
                // 否则模型看不到自己上一轮调用了什么，服务端会报调用配对错误。
                history.add(Message.assistant(response.content(), response.toolCalls()));
                for (ToolCall call : response.toolCalls()) {
                    trace("执行工具: " + call.name() + "，参数: " + call.arguments());
                    String observation = registry.execute(call.name(), call.arguments());
                    trace("观察结果: " + preview(observation));
                    history.add(Message.tool(call.id(), observation));
                }
                continue;
            }

            String answer = response.content() == null ? "" : response.content();
            history.add(Message.assistant(answer));
            return answer;
        }

        return "达到最大推理轮数限制（" + MAX_ITERATIONS + "）";
    }

    /** 清空对话历史，重新只保留 system 提示词。 */
    public void clearHistory() {
        history.clear();
        history.add(Message.system(SYSTEM_PROMPT));
    }

    /** 当前消息历史的快照（只读），供 CLI 观测与单元测试断言。 */
    public List<Message> history() {
        return List.copyOf(history);
    }

    private void trace(String message) {
        if (trace != null) {
            trace.println(message);
        }
    }

    private static String preview(String text) {
        if (text == null || text.length() <= TRACE_PREVIEW_CHARS) {
            return text;
        }
        return text.substring(0, TRACE_PREVIEW_CHARS) + "...";
    }
}
