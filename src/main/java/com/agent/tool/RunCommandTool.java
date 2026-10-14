package com.agent.tool;

import com.agent.ConsoleStreams;
import com.fasterxml.jackson.databind.JsonNode;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Tool：在项目根目录下执行一条外部命令（构建、测试、Git 查询等）。
 *
 * <p>实现要点：
 *
 * <ul>
 *   <li><b>工作目录固定为项目根目录</b>，不继承 Agent 进程的任意当前目录；</li>
 *   <li><b>超时保护</b>：默认 60 秒，超时强制结束进程，避免卡死整个 Agent；</li>
 *   <li><b>输出重定向到临时文件</b>，而不是用管道：命令输出超过管道缓冲（约 64KB）时，
 *       「先 waitFor 再读管道」会死锁，重定向到文件可以彻底避免；</li>
 *   <li><b>合并 stderr</b>：模型需要看到错误信息才能自行纠正；</li>
 *   <li><b>按控制台编码解码输出</b>：Windows 上子进程（git / mvn）通常输出 GBK，
 *       用 UTF-8 解会把中文变成乱码。</li>
 * </ul>
 *
 * <p>风险等级为 {@link Tool.Risk#EXECUTE}：是否允许执行由守卫裁决（黑名单 + 人工确认），
 * 本类只负责「给什么就执行什么」以外的技术细节。
 */
public class RunCommandTool implements Tool {

    public static final long DEFAULT_TIMEOUT_SECONDS = 60;
    private static final int MAX_OUTPUT_CHARS = 4000;
    private static final String ENCODING_PROPERTY = "agent.shell.encoding";

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

    /** 按当前平台选择 shell：Windows 用 cmd，类 Unix 用 sh。 */
    static List<String> shellCommand(String command) {
        if (System.getProperty("os.name", "").toLowerCase().contains("win")) {
            return List.of("cmd.exe", "/d", "/c", command);
        }
        return List.of("/bin/sh", "-c", command);
    }

    /** 子进程输出的解码字符集：显式属性 > native.encoding > UTF-8。 */
    static Charset outputCharset() {
        return ConsoleStreams.charset(System.getProperty(ENCODING_PROPERTY),
                System.getProperty("native.encoding"), StandardCharsets.UTF_8);
    }

    @Override
    public ToolResult execute(ToolRequest request) throws Exception {
        long start = System.nanoTime();
        String command = request.requireString("command");
        long timeoutSeconds = request.optionalLong("timeoutSeconds", DEFAULT_TIMEOUT_SECONDS);
        if (timeoutSeconds <= 0) {
            throw new IllegalArgumentException("timeoutSeconds 必须为正数: " + timeoutSeconds);
        }

        Path outputFile = Files.createTempFile("agent-command-", ".log");
        Process process = null;
        try {
            ProcessBuilder builder = new ProcessBuilder(shellCommand(command));
            builder.directory(request.root().toFile());
            builder.redirectErrorStream(true);
            builder.redirectOutput(outputFile.toFile());
            process = builder.start();

            boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
            if (!finished) {
                killProcessTree(process);
                return ToolResult.fail(name(),
                        "命令超时（超过 " + timeoutSeconds + " 秒）已被强制结束: " + command,
                        elapsedMillis(start));
            }

            String output = readOutput(outputFile);
            int exitCode = process.exitValue();
            String header = "命令: " + command + "\n退出码: " + exitCode + "\n";
            if (exitCode == 0) {
                return ToolResult.ok(name(), header + "输出:\n" + output, elapsedMillis(start));
            }
            return ToolResult.fail(name(), header + "输出:\n" + output, elapsedMillis(start));
        } finally {
            if (process != null && process.isAlive()) {
                killProcessTree(process);
            }
            try {
                Files.deleteIfExists(outputFile);
            } catch (IOException stillLocked) {
                // Windows 上被强杀的进程可能还没释放文件句柄：临时文件删不掉不影响本次调用结果
            }
        }
    }

    /**
     * 结束整棵进程树。
     *
     * <p>只杀 shell 是不够的：Windows 上 {@code cmd /c ping ...} 的真正工作进程是 cmd 的子进程，
     * 杀掉 cmd 之后子进程仍然存活（继续占用工作目录与句柄）。因此必须先杀后代进程。
     */
    private static void killProcessTree(Process process) {
        process.descendants().forEach(ProcessHandle::destroyForcibly);
        process.destroyForcibly();
        try {
            process.waitFor(5, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private static String readOutput(Path outputFile) throws IOException {
        byte[] bytes = Files.readAllBytes(outputFile);
        String text = new String(bytes, outputCharset()).strip();
        if (text.isEmpty()) {
            return "(无输出)";
        }
        if (text.length() > MAX_OUTPUT_CHARS) {
            return text.substring(0, MAX_OUTPUT_CHARS)
                    + "\n...(命令输出已截断，原长度 " + text.length() + " 字符)";
        }
        return text;
    }

    private static long elapsedMillis(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }
}
