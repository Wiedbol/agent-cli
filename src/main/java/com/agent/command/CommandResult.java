package com.agent.command;

/**
 * 保留进程事实而非面向模型的文案，避免未来验收依赖被裁剪的 Observation。
 *
 * @param exitCode 进程正常结束的退出码；超时时可以为 null
 * @param timedOut 是否因超时被结束
 * @param output 捕获的原始解码文本，不包含工具层的格式化和裁剪
 * @param durationMillis 本次命令耗时
 * @param outputCompleteness 输出是否已确认完整；无法证明后台进程已停止输出时为 UNKNOWN
 */
public record CommandResult(Integer exitCode, boolean timedOut, String output,
                            long durationMillis, OutputCompleteness outputCompleteness) {

    /** 避免把“无法确认”误作“不完整”或“完整”，供后续验收保守判断。 */
    public enum OutputCompleteness {
        COMPLETE, INCOMPLETE, UNKNOWN
    }

    public boolean outputComplete() {
        return outputCompleteness == OutputCompleteness.COMPLETE;
    }

    public boolean success() {
        return !timedOut && Integer.valueOf(0).equals(exitCode);
    }
}
