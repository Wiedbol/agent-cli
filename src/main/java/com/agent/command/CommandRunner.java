package com.agent.command;

import java.io.IOException;
import java.nio.file.Path;

/** 统一进程调用入口，让命令工具和验收使用同一契约，也允许离线替换外部进程。 */
@FunctionalInterface
public interface CommandRunner {

    CommandResult run(Path workingDirectory, String command, long timeoutSeconds)
            throws IOException, InterruptedException;
}
