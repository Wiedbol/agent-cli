package com.agent;

import java.io.BufferedReader;
import java.io.Console;
import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

/**
 * 统一处理命令行程序的字符编码问题（stdin 解码与 stdout 编码）。
 *
 * <p><b>问题背景</b>：JDK 18 起 {@code file.encoding} 固定为 UTF-8，但 JDK 19 起
 * {@code System.in} / {@code System.out} 使用 {@code stdin.encoding} / {@code stdout.encoding}
 * （Windows 上等于控制台代码页，简体中文环境通常是 GBK）。于是会出现两种典型故障：
 *
 * <ul>
 *   <li><b>输入乱码</b>：{@code new InputStreamReader(System.in)} 使用默认的 UTF-8 去解码
 *       控制台给出的 GBK 字节，中文变成 {@code ???}（替换字符 U+FFFD）；</li>
 *   <li><b>输出乱码</b>：{@code System.out} 按 GBK 写出字节，而 UTF-8 终端（IDE 运行窗口、
 *       Windows Terminal、VS Code）按 UTF-8 解码。</li>
 * </ul>
 *
 * <p>只要「写出的字节编码」与「对端的解码方式」不一致就会出问题，因此固定任何一种编码都不行，
 * 只能按运行环境推断：
 *
 * <ol>
 *   <li>{@code -Dagent.encoding=UTF-8}（或 {@code GBK} 等）显式指定，优先级最高；</li>
 *   <li>读写对象不是真实终端（重定向、管道、IDE 运行窗口、CI）时使用 UTF-8，
 *       因为现代工具链默认按 UTF-8 解码；</li>
 *   <li>连接到真实终端时使用 JVM 探测到的控制台编码（中文 Windows 通常是 GBK）。</li>
 * </ol>
 */
public final class ConsoleStreams {

    /** 覆盖编码的系统属性名：{@code -Dagent.encoding=UTF-8}。 */
    public static final String ENCODING_PROPERTY = "agent.encoding";

    private ConsoleStreams() {
    }

    /**
     * 编码选择规则（抽成纯函数以便单元测试）。
     *
     * @param forced          显式指定的编码，可为 {@code null} 或空白
     * @param consoleEncoding JVM 探测到的控制台编码（{@code stdin.encoding} / {@code stdout.encoding}）
     * @param hasTerminal     是否连接到真实终端
     */
    public static Charset charset(String forced, String consoleEncoding, boolean hasTerminal) {
        if (forced != null && !forced.isBlank()) {
            return Charset.forName(forced);
        }
        if (!hasTerminal) {
            return StandardCharsets.UTF_8;
        }
        if (consoleEncoding != null && !consoleEncoding.isBlank()) {
            return Charset.forName(consoleEncoding);
        }
        return StandardCharsets.UTF_8;
    }

    /** 读取标准输入时应使用的编码。 */
    public static Charset stdinCharset() {
        return charset(System.getProperty(ENCODING_PROPERTY),
                System.getProperty("stdin.encoding"),
                isInteractiveTerminal());
    }

    /** 写出标准输出时应使用的编码。 */
    public static Charset stdoutCharset() {
        return charset(System.getProperty(ENCODING_PROPERTY),
                System.getProperty("stdout.encoding"),
                isInteractiveTerminal());
    }

    /**
     * 创建按环境正确解码的标准输入读取器。
     *
     * <p>与直接 {@code new BufferedReader(new InputStreamReader(System.in))} 的区别在于：
     * 后者使用默认字符集（UTF-8），在 GBK 控制台输入中文时会得到 {@code ???}。
     */
    public static BufferedReader newStdinReader() {
        return new BufferedReader(new InputStreamReader(System.in, stdinCharset()));
    }

    /**
     * 按环境重建 {@link System#out}（直接写 {@link FileDescriptor#out}，绕开其自带的编码器），
     * 使中文输出在 GBK 控制台与 UTF-8 终端下都能正确显示。
     *
     * @return 实际生效的输出编码
     */
    public static Charset configureStdout() {
        Charset charset = stdoutCharset();
        System.setOut(new PrintStream(new FileOutputStream(FileDescriptor.out), true, charset));
        return charset;
    }

    /**
     * 判断标准输入/输出是否连接到真实终端（而不是管道、文件或 IDE 捕获的管道）。
     *
     * <p>JDK 22 起 {@code System.console()} 即使 stdin/stdout 被重定向也会返回对象，
     * 需要用新增的 {@code Console.isTerminal()} 才能区分；本工程以 {@code --release 17}
     * 编译，编译期没有这个方法，因此用反射调用。在 JDK 17~21 上 {@code console()} 返回
     * {@code null} 即代表被重定向，语义与 {@code isTerminal() == false} 一致。
     */
    public static boolean isInteractiveTerminal() {
        Console console = System.console();
        if (console == null) {
            return false;
        }
        try {
            Method isTerminal = Console.class.getMethod("isTerminal");
            return (Boolean) isTerminal.invoke(console);
        } catch (NoSuchMethodException jdk17To21) {
            return true;
        } catch (IllegalAccessException | InvocationTargetException unexpected) {
            return true;
        }
    }
}
