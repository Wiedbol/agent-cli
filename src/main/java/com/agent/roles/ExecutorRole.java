package com.agent.roles;

import com.agent.tool.Tool;
import com.agent.tool.ToolRequest;
import com.agent.tool.ToolResult;
import com.fasterxml.jackson.databind.JsonNode;

import java.io.PrintStream;
import java.nio.file.Path;

/**
 * 角色五：<b>执行员</b>。
 *
 * <p>执行员是唯一真正「动手」的角色：把 {@link Tool} 和参数变成 {@link ToolResult}。
 *
 * <p>它承担三件事：
 *
 * <ol>
 *   <li><b>统一兜底</b>：工具抛出的任何异常都转成失败结果，绝不让异常穿透到控制员之外
 *       —— 一次工具失败不应该终止整个任务；</li>
 *   <li><b>统一裁剪</b>：超长输出在此截断，避免一次读取把上下文撑爆；</li>
 *   <li><b>统一记账</b>：记录耗时，便于观测「哪一步慢」。</li>
 * </ol>
 *
 * <p>执行员<b>不做</b>安全判断 —— 它只会被控制员在守卫放行之后调用。
 */
public class ExecutorRole {

    /** 默认单次结果上限，与 Lab03 的 ToolRegistry 保持一致。 */
    public static final int DEFAULT_MAX_RESULT_CHARS = 8000;

    private final Path root;
    private final int maxResultChars;
    private final PrintStream trace;

    public ExecutorRole(Path root) {
        this(root, DEFAULT_MAX_RESULT_CHARS, null);
    }

    public ExecutorRole(Path root, int maxResultChars, PrintStream trace) {
        if (maxResultChars <= 0) {
            throw new IllegalArgumentException("maxResultChars 必须为正数: " + maxResultChars);
        }
        this.root = root.toAbsolutePath().normalize();
        this.maxResultChars = maxResultChars;
        this.trace = trace;
    }

    public Path root() {
        return root;
    }

    /** 执行一次工具调用，永不抛异常。 */
    public ToolResult execute(Tool tool, JsonNode arguments) {
        return truncate(attempt(tool, () -> tool.execute(new ToolRequest(root, arguments))));
    }

    /** Diff 不使用模型结果长度限制，避免用户批准没有完整看到的变更。 */
    public ToolResult preview(Tool tool, JsonNode arguments) {
        return attempt(tool, () -> ToolResult.ok(tool.name(), tool.preview(new ToolRequest(root, arguments)), 0));
    }

    @FunctionalInterface
    private interface Operation { ToolResult run() throws Exception; }

    private ToolResult attempt(Tool tool, Operation operation) {
        long start = System.nanoTime();
        try {
            return operation.run();
        } catch (com.agent.command.CommandRunner.TrackingFailure trackingFailed) {
            throw trackingFailed;
        } catch (Exception failed) {
            long elapsed = elapsedMillis(start);
            String message = describe(failed);
            if (trace != null) {
                trace.println("[执行员] " + tool.name() + " 失败（" + elapsed + " ms）: " + message);
            }
            return ToolResult.fail(tool.name(), message, elapsed);
        }
    }

    /** 把异常整理成「模型看得懂、人能排查」的一句话。 */
    private static String describe(Exception failed) {
        String message = failed.getMessage() == null ? failed.toString() : failed.getMessage();
        if (failed instanceof SecurityException) {
            return "安全校验失败: " + message;
        }
        if (failed instanceof IllegalArgumentException) {
            return "参数错误: " + message;
        }
        return failed.getClass().getSimpleName() + ": " + message;
    }

    private ToolResult truncate(ToolResult result) {
        String output = result.output() == null ? "" : result.output();
        if (output.length() <= maxResultChars) {
            return result;
        }
        String truncated = output.substring(0, maxResultChars)
                + "\n...(结果已截断，原长度 " + output.length() + " 字符)";
        return new ToolResult(result.toolName(), result.success(), truncated, result.durationMillis());
    }

    private static long elapsedMillis(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }
}
