package com.agent.session;

import com.agent.llm.Message;

/**
 * 会话记录器：角色们通过这个接口把「发生了什么」交给持久化层。
 *
 * <p>为什么用接口而不是让角色直接依赖 {@link SessionStore}：
 *
 * <ul>
 *   <li>角色只知道「有人在记录」，不关心文件格式、目录与轮转；</li>
 *   <li>测试可以注入只收集事件的内存替身，不必碰文件系统；</li>
 *   <li>{@link #NONE} 表示不记录，让「不开记录」时的行为与改造前完全一致。</li>
 * </ul>
 *
 * <p>记录失败（磁盘满、权限不足）不应该打断对话：实现方需要吞掉 I/O 异常并给出提示，
 * 因为「让对话继续」比「记录完整」优先级更高。
 */
public interface SessionRecorder {

    /** 记录一条普通会话消息（user / assistant 最终回答）。 */
    void appendMessage(Message message);

    /**
     * 记录一条工具执行结果。
     *
     * @param toolMessage       role=tool 的消息
     * @param toolArgumentsJson 触发这次执行的参数（用于按路径判断是否属于敏感文件）
     */
    void appendToolResult(Message toolMessage, String toolArgumentsJson);

    /**
     * 记录一轮（step）的元数据。
     *
     * @param step             第几轮
     * @param elapsedMillis    该轮调用模型耗时
     * @param promptTokens     输入 token
     * @param completionTokens 输出 token
     * @param toolNames        该轮请求的工具名（逗号分隔），没有则为 {@code "-"}
     */
    void appendTurn(int step, long elapsedMillis, int promptTokens, int completionTokens, String toolNames);

    /** 不记录：所有方法都是空实现。 */
    SessionRecorder NONE = new SessionRecorder() {
        @Override
        public void appendMessage(Message message) {
        }

        @Override
        public void appendToolResult(Message toolMessage, String toolArgumentsJson) {
        }

        @Override
        public void appendTurn(int step, long elapsedMillis, int promptTokens, int completionTokens,
                               String toolNames) {
        }
    };
}
