package com.agent.command;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/** 统一进程调用入口，让命令工具和验收使用同一契约，也允许离线替换外部进程。 */
@FunctionalInterface
public interface CommandRunner {

    CommandResult run(Path workingDirectory, String command, long timeoutSeconds)
            throws IOException, InterruptedException;

    /** 自动批准后只能使用已审查的进程参数。 */
    default CommandResult runDirect(Path workingDirectory, List<String> invocation, long timeoutSeconds)
            throws IOException, InterruptedException {
        throw new IOException("当前命令执行器不支持直接执行参数列表");
    }

    /** 不支持进程观察的替身仍可执行，但人工处置不能假定其旧进程已停止。 */
    default CommandRunner withProcessObserver(java.util.function.Consumer<ProcessHandle> observer) { return this; }

    final class TrackingFailure extends java.io.UncheckedIOException {
        public TrackingFailure(IOException cause) { super("任务进程记录失败", cause); }
    }
}
