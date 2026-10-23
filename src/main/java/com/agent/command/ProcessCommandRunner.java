package com.agent.command;

import com.agent.ConsoleStreams;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;

/** 把进程和输出捕获集中在同一实现内，工具只负责将结果展示给模型。 */
public final class ProcessCommandRunner implements CommandRunner {

    private final Path outputDirectory;
    private final java.util.function.Consumer<ProcessHandle> observer;

    public ProcessCommandRunner() {
        this(null);
    }

    /** 测试可指定输出目录，避免临时日志写出其隔离目录；null 使用系统临时目录。 */
    public ProcessCommandRunner(Path outputDirectory) {
        this(outputDirectory, null);
    }

    private ProcessCommandRunner(Path outputDirectory, java.util.function.Consumer<ProcessHandle> observer) {
        this.outputDirectory = outputDirectory == null ? null : outputDirectory.toAbsolutePath().normalize();
        this.observer = observer;
    }

    @Override public CommandRunner withProcessObserver(java.util.function.Consumer<ProcessHandle> value) {
        return new ProcessCommandRunner(outputDirectory, value);
    }

    @Override
    public CommandResult run(Path workingDirectory, String command, long timeoutSeconds)
            throws IOException, InterruptedException {
        if (timeoutSeconds <= 0) {
            throw new IllegalArgumentException("timeoutSeconds 必须为正数: " + timeoutSeconds);
        }
        long startedAt = System.nanoTime();
        Path outputFile = outputDirectory == null
                ? Files.createTempFile("agent-command-", ".log")
                : Files.createTempFile(outputDirectory, "agent-command-", ".log");
        Process process = null;
        try {
            ProcessBuilder builder = new ProcessBuilder(shellCommand(command));
            builder.directory(workingDirectory.toAbsolutePath().normalize().toFile());
            builder.redirectErrorStream(true);
            builder.redirectOutput(outputFile.toFile());
            process = builder.start();

            if (!waitFor(process, timeoutSeconds)) {
                killProcessTree(process);
                return new CommandResult(null, true, "", elapsedMillis(startedAt),
                        CommandResult.OutputCompleteness.INCOMPLETE);
            }

            String output = new String(Files.readAllBytes(outputFile), outputCharset());
            // shell 退出不证明后台后代已关闭输出句柄，只能确认已读取当时的完整日志快照。
            return new CommandResult(process.exitValue(), false, output, elapsedMillis(startedAt),
                    CommandResult.OutputCompleteness.UNKNOWN);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw interrupted;
        } finally {
            if (process != null && process.isAlive()) {
                killProcessTree(process);
            }
            deleteOutput(outputFile);
        }
    }

    /** 进程退出通知可能先于Windows释放重定向句柄，清理短暂重试且不覆盖原始执行结果。 */
    private static void deleteOutput(Path outputFile) {
        for (int attempt = 0; attempt < 10; attempt++) {
            try {
                Files.deleteIfExists(outputFile);
                return;
            } catch (IOException stillLocked) {
                if (attempt == 9) { return; }
                try { Thread.sleep(20); }
                catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    private boolean waitFor(Process process, long timeoutSeconds) throws InterruptedException {
        if (observer == null) { return process.waitFor(timeoutSeconds, TimeUnit.SECONDS); }
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds);
        while (true) {
            observer.accept(process.toHandle());
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) { return false; }
            if (process.waitFor(Math.min(remaining, TimeUnit.MILLISECONDS.toNanos(100)), TimeUnit.NANOSECONDS)) { return true; }
        }
    }

    private static List<String> shellCommand(String command) {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")
                ? List.of("cmd.exe", "/d", "/c", command)
                : List.of("/bin/sh", "-c", command);
    }

    private static Charset outputCharset() {
        return ConsoleStreams.charset(System.getProperty("agent.shell.encoding"),
                System.getProperty("native.encoding"), StandardCharsets.UTF_8);
    }

    /** 先结束后代再结束 shell，避免只杀 shell 后留下占用工作目录的进程。 */
    private static void killProcessTree(Process process) throws IOException {
        List<ProcessHandle> descendants = process.descendants().toList();
        List<CompletableFuture<?>> exits = new ArrayList<>();
        for (ProcessHandle descendant : descendants) {
            exits.add(descendant.onExit());
        }
        exits.add(process.onExit());
        for (int index = descendants.size() - 1; index >= 0; index--) {
            descendants.get(index).destroyForcibly();
        }
        process.destroyForcibly();
        try {
            // join 不清除中断标记，清理期间仍会等待后代释放继承的输出文件句柄。
            CompletableFuture.allOf(exits.toArray(CompletableFuture<?>[]::new))
                    .orTimeout(5, TimeUnit.SECONDS).join();
        } catch (CompletionException failed) {
            throw new IOException("进程树未能在 5 秒内退出", failed);
        }
    }

    private static long elapsedMillis(long startedAt) {
        return (System.nanoTime() - startedAt) / 1_000_000;
    }
}
