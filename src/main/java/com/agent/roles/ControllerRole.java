package com.agent.roles;

import com.agent.llm.LLMResponse;
import com.agent.llm.ToolCall;
import com.agent.session.SessionRecorder;
import com.agent.tool.Tool;
import com.agent.tool.ToolResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.NullNode;

import java.io.IOException;
import java.io.PrintStream;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * 角色六：<b>控制员</b>。
 *
 * <p>控制员是流程的驱动者：把「模型决策 → 守卫裁决 → 用户确认 → 执行员执行 → 结果回传」
 * 串成一个循环，并负责两件事：
 *
 * <ul>
 *   <li><b>解释模型意图</b>：{@code hasToolCalls()} 为真就走工具分支，否则视为最终回答；</li>
 *   <li><b>执行/终止判定</b>：没有工具调用、达到最大步数、模型调用失败，三种情况如何收尾。</li>
 * </ul>
 *
 * <p>控制员自己不读文件、不执行命令、不做安全判断，也不理解任务内容 —— 它只负责「顺序与边界」。
 */
public class ControllerRole {

    public enum StopReason { COMPLETED, MODEL_FAILED, LIMIT_REACHED }
    public record RunResult(StopReason reason, String answer) { }

    /** 管理任务严格保存执行边界，普通聊天不引入任务持久化语义。 */
    public interface DecisionObserver {
        DecisionObserver NONE = new DecisionObserver() { };
        default void beforeRequest() { }
        default void responded(LLMResponse response, long millis) { }
        default void modelFailed(IOException failure, long millis) { }
        default void beforeTool(ToolCall call, Tool.Risk risk) { }
        default void approvalRequired() { }
        default void toolFinished(ToolCall call, boolean success, String observation) { }
        default void roundFinished() { }
    }

    public static final int DEFAULT_MAX_STEPS = 12;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final LlmRole llm;
    private final RegistrarRole registrar;
    private final GuardRole guard;
    private final ExecutorRole executor;
    private final UserRole user;
    private final int maxSteps;
    private final PrintStream trace;
    private final SessionRecorder recorder;
    private final Runnable checkInputs;

    public ControllerRole(LlmRole llm, RegistrarRole registrar, GuardRole guard,
                          ExecutorRole executor, UserRole user, int maxSteps, PrintStream trace) {
        this(llm, registrar, guard, executor, user, maxSteps, trace, SessionRecorder.NONE);
    }

    /** 带会话记录的构造：每轮结束写一条 turn 事件（时间戳 / 耗时 / token / 工具名）。 */
    public ControllerRole(LlmRole llm, RegistrarRole registrar, GuardRole guard,
                          ExecutorRole executor, UserRole user, int maxSteps, PrintStream trace,
                          SessionRecorder recorder) {
        this(llm, registrar, guard, executor, user, maxSteps, trace, recorder, () -> { });
    }

    /** 策略由装配点提供，控制员只保证工具执行边界的顺序。 */
    public ControllerRole(LlmRole llm, RegistrarRole registrar, GuardRole guard,
                          ExecutorRole executor, UserRole user, int maxSteps, PrintStream trace,
                          SessionRecorder recorder, Runnable checkInputs) {
        this.llm = llm;
        this.registrar = registrar;
        this.guard = guard;
        this.executor = executor;
        this.user = user;
        this.maxSteps = maxSteps;
        this.trace = trace;
        this.recorder = recorder == null ? SessionRecorder.NONE : recorder;
        this.checkInputs = checkInputs;
    }

    /**
     * 跑完一个任务，返回最终回答。
     *
     * <p>任何异常都被转换成可读文本返回：CLI 不应该因为一次模型或工具故障而崩溃。
     */
    public String runTask(String task) {
        return runTaskResult(task).answer();
    }

    /** 用类型区分真正的完成声明与故障，避免任务协调器解析中文提示。 */
    public RunResult runTaskResult(String task) {
        return runTaskResult(task, DecisionObserver.NONE);
    }

    public RunResult runTaskResult(String task, DecisionObserver observer) {
        llm.beginTask(task);

        for (int step = 1; step <= maxSteps; step++) {
            trace("[控制员] 第 " + step + "/" + maxSteps + " 步：让 LLM 决定下一步");

            LLMResponse response;
            checkInputs.run();
            observer.beforeRequest();
            long startedAt = System.nanoTime();
            try {
                response = llm.ask(registrar.toolDefinitions());
            } catch (IOException failed) {
                observer.modelFailed(failed, (System.nanoTime() - startedAt) / 1_000_000);
                return new RunResult(StopReason.MODEL_FAILED, "LLM 调用失败: " + failed.getMessage());
            }
            observer.responded(response, (System.nanoTime() - startedAt) / 1_000_000);
            recorder.appendTurn(step, (System.nanoTime() - startedAt) / 1_000_000,
                    response.promptTokens(), response.completionTokens(), toolNamesOf(response));

            if (!response.hasToolCalls()) {
                String answer = response.content() == null ? "" : response.content();
                llm.recordFinalAnswer(answer);
                trace("[控制员] 模型未请求工具，任务结束（共 " + step + " 步）");
                return new RunResult(StopReason.COMPLETED, answer);
            }

            llm.recordToolCalls(response);
            for (ToolCall call : response.toolCalls()) {
                checkInputs.run();
                observer.beforeTool(call, registrar.find(call.name()).map(Tool::risk).orElse(Tool.Risk.READ));
                DispatchResult result = dispatch(call, observer);
                observer.toolFinished(call, result.success(), result.observation());
                llm.recordToolResult(call, result.observation());
                checkInputs.run();
            }
            observer.roundFinished();
        }

        return new RunResult(StopReason.LIMIT_REACHED, "达到最大步数限制（" + maxSteps + "），任务未完成。"
                + "可以补充更明确的指令，或提高 --max-steps 后重试。");
    }

    /** 处理单个工具调用：查表 → 守卫 → 确认 → 执行 → 返回 Observation 文本。 */
    private record DispatchResult(boolean success, String observation) { }

    private DispatchResult dispatch(ToolCall call, DecisionObserver observer) {
        trace("[控制员] 模型请求调用工具: " + call.name() + "，参数: " + call.arguments());

        Optional<Tool> found = registrar.find(call.name());
        if (found.isEmpty()) {
            return new DispatchResult(false, "未知工具: " + call.name() + "（可用工具: " + String.join(", ", registrar.names()) + "）");
        }

        JsonNode arguments = parseArguments(call.arguments());
        if (arguments == null) {
            return new DispatchResult(false, "工具参数不是合法 JSON 对象: " + call.arguments());
        }

        Tool tool = found.get();
        GuardRole.Decision decision = guard.review(tool, arguments);
        trace("[守卫] " + decision.outcome() + "：" + decision.reason());

        if (!decision.allowed()) {
            return new DispatchResult(false, "守卫拒绝了这次工具调用 —— " + decision.reason());
        }
        ToolResult preview = executor.preview(tool, arguments);
        if (!preview.success()) { return new DispatchResult(false, preview.toObservation()); }
        if (preview.output() != null && !preview.output().isEmpty()) { trace(preview.output()); }
        if (decision.needsConfirmation() && !user.autoApprove()) { observer.approvalRequired(); }
        if (decision.needsConfirmation() && !user.confirm(decision.reason() + "，是否允许？")) {
            return new DispatchResult(false, "用户拒绝了这次工具调用 —— " + decision.reason());
        }

        ToolResult result = executor.execute(tool, arguments);
        trace("[执行员] " + (result.success() ? "执行成功" : "执行失败")
                + "（" + result.durationMillis() + " ms）");
        return new DispatchResult(result.success(), result.toObservation());
    }

    /** 解析模型给出的参数 JSON；非法 JSON 返回 {@code null} 由调用方转成 Observation。 */
    private static JsonNode parseArguments(String argumentsJson) {
        if (argumentsJson == null || argumentsJson.isBlank()) {
            return NullNode.getInstance();
        }
        try {
            JsonNode node = MAPPER.readTree(argumentsJson);
            if (node == null || node.isMissingNode() || node.isNull()) {
                return NullNode.getInstance();
            }
            return node.isObject() ? node : null;
        } catch (IOException malformed) {
            return null;
        }
    }

    private void trace(String message) {
        if (trace != null) {
            trace.println(message);
        }
    }

    /** 该轮请求调用的工具名，用于 turn 事件（没有则为 {@code "-"}）。 */
    private static String toolNamesOf(LLMResponse response) {
        if (!response.hasToolCalls()) {
            return "-";
        }
        return response.toolCalls().stream()
                .map(ToolCall::name)
                .collect(Collectors.joining(", "));
    }
}
