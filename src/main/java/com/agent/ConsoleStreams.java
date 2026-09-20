package com.agent;

import java.io.BufferedReader;
import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.Charset;
import java.nio.charset.IllegalCharsetNameException;
import java.nio.charset.StandardCharsets;
import java.nio.charset.UnsupportedCharsetException;

/**
 * 统一处理命令行程序的字符编码问题（stdin 解码与 stdout / stderr 编码）。
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
 * <p>只要「写出的字节编码」与「对端的解码方式」不一致就会出问题，因此固定任何一种编码都不行。
 * 本类的策略是：<b>默认不做任何猜测，直接采用 JVM 探测到的编码</b>（{@code stdin.encoding} /
 * {@code stdout.encoding}，JDK 19+ 由控制台代码页或平台编码决定），只在需要时显式覆盖：
 *
 * <ol>
 *   <li>{@code -Dagent.encoding=UTF-8}（或 {@code GBK} 等）显式指定，优先级最高；</li>
 *   <li>否则使用 JVM 探测到的 {@code stdin.encoding} / {@code stdout.encoding}
 *       —— 中文 Windows 上通常是 GBK，UTF-8 终端或 macOS / Linux 上是 UTF-8；</li>
 *   <li>探测不到时（如较早的 JDK）回退到 {@code native.encoding}，再回退 UTF-8。</li>
 * </ol>
 *
 * <p>为什么不自己猜测终端类型？因为「输出被重定向 / 由 IDE 捕获」并不等价于「对端按 UTF-8 解码」：
 * 例如在中文 Windows 上由 IDE 运行窗口捕获输出时，对端往往仍按系统编码（GBK）解码。与其用启发式
 * 规则制造意外，不如遵循 JVM 的探测结果 —— 这也让默认行为与「直接用 System.out」完全一致，
 * 需要不同编码时用系统属性显式声明即可。
 */
public final class ConsoleStreams {

    /** 覆盖编码的系统属性名：{@code -Dagent.encoding=UTF-8}。 */
    public static final String ENCODING_PROPERTY = "agent.encoding";

    private ConsoleStreams() {
    }

    /**
     * 编码选择规则（抽成纯函数以便单元测试）：显式指定优先，其次 JVM 探测值，最后兜底值。
     *
     * @param forced   显式指定的编码（{@code -Dagent.encoding}），可为 {@code null} 或空白
     * @param detected JVM 探测到的编码（{@code stdin.encoding} / {@code stdout.encoding}）
     * @param fallback 两者都不可用时的兜底编码
     */
    public static Charset charset(String forced, String detected, Charset fallback) {
        Charset chosen = resolve(forced);
        if (chosen == null) {
            chosen = resolve(detected);
        }
        return chosen == null ? fallback : chosen;
    }

    /** 读取标准输入时应使用的编码。 */
    public static Charset stdinCharset() {
        return charset(System.getProperty(ENCODING_PROPERTY),
                System.getProperty("stdin.encoding"),
                nativeCharset());
    }

    /** 写出标准输出时应使用的编码。 */
    public static Charset stdoutCharset() {
        return charset(System.getProperty(ENCODING_PROPERTY),
                System.getProperty("stdout.encoding"),
                nativeCharset());
    }

    /**
     * 写出标准错误时应使用的编码。
     *
     * <p>与 {@link #stdoutCharset()} 用同一套优先级，避免出现
     * 「{@code -Dagent.encoding=UTF-8} 之后 stdout 正常、stderr 仍然乱码」这种只修一半的情况。
     */
    public static Charset stderrCharset() {
        return charset(System.getProperty(ENCODING_PROPERTY),
                System.getProperty("stderr.encoding"),
                nativeCharset());
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
     * 按环境重建 {@link System#err}，规则与 {@link #configureStdout()} 相同。
     *
     * @return 实际生效的错误输出编码
     */
    public static Charset configureStderr() {
        Charset charset = stderrCharset();
        System.setErr(new PrintStream(new FileOutputStream(FileDescriptor.err), true, charset));
        return charset;
    }

    /** 平台默认编码：JDK 17+ 的 {@code native.encoding}，取不到时用 UTF-8。 */
    private static Charset nativeCharset() {
        Charset nativeCharset = resolve(System.getProperty("native.encoding"));
        return nativeCharset == null ? StandardCharsets.UTF_8 : nativeCharset;
    }

    /** 把编码名解析为 {@link Charset}；名字为空或不可用时返回 {@code null}。 */
    private static Charset resolve(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        try {
            return Charset.forName(name);
        } catch (IllegalCharsetNameException | UnsupportedCharsetException unsupported) {
            return null;
        }
    }
}
