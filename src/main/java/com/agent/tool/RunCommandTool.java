package com.agent.tool;

import com.agent.command.CommandResult;
import com.agent.command.CommandRunner;
import com.agent.command.ProcessCommandRunner;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.Objects;

/** 将结构化进程结果转成兼容的 Observation，裁剪只发生在展示层。 */
public class RunCommandTool implements Tool {

    public static final long DEFAULT_TIMEOUT_SECONDS = 60;
    private static final int MAX_OUTPUT_CHARS = 4000;

    private final CommandRunner commandRunner;

    public RunCommandTool() {
        this(new ProcessCommandRunner());
    }

    /** 允许装配入口替换外部进程，而不绕过工具和守卫。 */
    public RunCommandTool(CommandRunner commandRunner) {
        this.commandRunner = Objects.requireNonNull(commandRunner, "commandRunner");
    }

    @Override
    public String name() {
        return "run_command";
    }

    @Override
    public String description() {
        return "在项目根目录下执行一条命令行指令并返回退出码与输出（例如 mvn -q -DskipTests compile、"
                + "git status --short、java -version）。执行前会经过守卫的安全检查";
    }

    @Override
    public JsonNode parameters() {
        return ToolSchemas.object(
                ToolSchemas.Param.required("command", "string", "要执行的完整命令行"),
                ToolSchemas.Param.optional("timeoutSeconds", "integer",
                        "超时秒数，默认 " + DEFAULT_TIMEOUT_SECONDS));
    }

    @Override
    public Risk risk() {
        return Risk.EXECUTE;
    }

    @Override
    public ToolResult execute(ToolRequest request) throws Exception {
        String command = request.requireString("command");
        long timeoutSeconds = request.optionalLong("timeoutSeconds", DEFAULT_TIMEOUT_SECONDS);
        if (timeoutSeconds <= 0) {
            throw new IllegalArgumentException("timeoutSeconds 必须为正数: " + timeoutSeconds);
        }

        CommandResult result = commandRunner.run(request.root(), command, timeoutSeconds);
        if (result.timedOut()) {
            return ToolResult.fail(name(),
                    "命令超时（超过 " + timeoutSeconds + " 秒）已被强制结束: " + command,
                    result.durationMillis());
        }
        String header = "命令: " + command + "\n退出码: " + result.exitCode() + "\n";
        String output = formatOutput(result.output());
        if (result.outputCompleteness() == CommandResult.OutputCompleteness.INCOMPLETE) {
            output += "\n...(命令输出不完整)";
        }
        return new ToolResult(name(), result.success(), header + "输出:\n" + output, result.durationMillis());
    }

    private static String formatOutput(String output) {
        String text = output.strip();
        if (text.isEmpty()) {
            return "(无输出)";
        }
        if (text.length() > MAX_OUTPUT_CHARS) {
            return text.substring(0, MAX_OUTPUT_CHARS)
                    + "\n...(命令输出已截断，原长度 " + text.length() + " 字符)";
        }
        return text;
    }
}
