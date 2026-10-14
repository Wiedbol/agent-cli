package com.agent.roles;

import com.agent.llm.LLMClient;
import com.agent.llm.LLMResponse;
import com.agent.llm.Message;
import com.agent.llm.ToolCall;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 角色二：<b>LLM</b>。
 *
 * <p>它代表「模型这一侧的会话状态」：系统提示词 + 完整消息历史，以及把消息发出去的
 * {@link #ask(List)}。
 *
 * <p>为什么把历史放在这里而不是控制员手里？因为「历史怎么组织、哪种角色该带哪些字段」
 * 属于模型协议知识（例如 {@code assistant(tool_calls)} 必须与 {@code tool(tool_call_id)}
 * 成对出现）；控制员只关心「下一步做什么」。
 *
 * <p>本类不决定调用哪个工具（由模型通过 {@code tool_calls} 决定），也不执行任何工具。
 */
public class LlmRole {

    /** 系统提示词：定义身份、可用能力与工作方式。 */
    public static final String SYSTEM_PROMPT = """
            你是一个可以操作本地代码库的编程助手 Agent。

            你可以使用这些工具：
            - list_files：查看目录结构；
            - read_file：读取文件内容；
            - search_code：按关键字搜索代码；
            - write_file：创建或修改文件；
            - run_command：在项目根目录执行命令（如 mvn、git）。

            工作方式要求：
            1. 需要了解现状时先调用只读工具（list_files / read_file / search_code），不要凭记忆猜测文件内容；
            2. 修改文件用 write_file，并保证内容是完整可用的（不是片段或伪代码）；
            3. 验证改动时用 run_command 执行构建或测试命令；
            4. 每一步都基于工具返回的真实结果推进，工具报错时先读错误原因再决定下一步；
            5. 任务完成后，用简洁的中文总结「做了什么、改了哪些文件、验证结果如何」。
            """;

    private final LLMClient client;
    private final List<Message> history = new ArrayList<>();

    public LlmRole(LLMClient client) {
        this.client = Objects.requireNonNull(client, "client");
        reset();
    }

    /** 清空对话历史，只保留系统提示词。 */
    public void reset() {
        history.clear();
        history.add(Message.system(SYSTEM_PROMPT));
    }

    /** 把一条用户任务加入历史。 */
    public void beginTask(String task) {
        history.add(Message.user(task));
    }

    /** 请求模型决策；返回的响应由控制员解释。 */
    public LLMResponse ask(List<LLMClient.ToolDef> tools) throws IOException {
        return client.chat(List.copyOf(history), tools);
    }

    /** 保存「模型发起了工具调用」这条 assistant 消息（必须在下一条 tool 消息之前）。 */
    public void recordToolCalls(LLMResponse response) {
        history.add(Message.assistant(response.content(), response.toolCalls()));
    }

    /** 保存工具执行结果（Observation）。 */
    public void recordToolResult(ToolCall call, String observation) {
        history.add(Message.tool(call.id(), observation));
    }

    /** 保存最终回答。 */
    public void recordFinalAnswer(String content) {
        history.add(Message.assistant(content));
    }

    /** 当前历史的只读快照。 */
    public List<Message> history() {
        return List.copyOf(history);
    }
}
