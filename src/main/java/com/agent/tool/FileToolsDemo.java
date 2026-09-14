package com.agent.tool;

import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.Console;
import java.io.PrintStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 命令行演示入口：对当前目录运行三个 File Tool，用于人工验收（实验 §9.3）。
 *
 * <pre>
 * java -cp target/classes com.agent.tool.FileToolsDemo README.md TODO
 * java -cp target/classes com.agent.tool.FileToolsDemo --tree
 * </pre>
 */
public class FileToolsDemo {

    private static final String IGNORE_CONFIG = ".agentignore";
    private static final int LIST_LIMIT = 20;

    public static void main(String[] args) throws Exception {
        Charset stdoutEncoding = configureStdout();

        Path root = Path.of(".");
        IgnoreRules ignoreRules = IgnoreRules.load(Path.of(IGNORE_CONFIG));
        ListFilesTool lister = new ListFilesTool(ignoreRules);
        ReadFileTool reader = new ReadFileTool();
        SearchCodeTool searcher = new SearchCodeTool(ignoreRules);

        List<String> rest = new ArrayList<>(List.of(args));
        boolean treeMode = rest.remove("--tree");

        System.out.println("== stdout encoding: " + stdoutEncoding.name() + " ==");
        System.out.println("== ignore rules (" + IGNORE_CONFIG + ") ==");
        System.out.println(String.join(", ", ignoreRules.patterns()));

        System.out.println("== list_files" + (treeMode ? " (--tree)" : "") + " ==");
        List<String> listing = treeMode ? lister.tree(root) : lister.listFiles(root);
        listing.stream().limit(LIST_LIMIT).forEach(System.out::println);
        if (listing.size() > LIST_LIMIT) {
            System.out.println("... 共 " + listing.size() + " 项，此处只显示前 " + LIST_LIMIT + " 项");
        }

        if (!rest.isEmpty()) {
            String file = rest.get(0);
            System.out.println("== read_file " + file + " ==");
            System.out.println(reader.readFile(Path.of(file)));
        }

        String keyword = rest.size() >= 2 ? rest.get(1) : "TODO";
        System.out.println("== search_code " + keyword + " ==");
        List<SearchCodeTool.Match> matches = searcher.searchCode(keyword, root);
        if (matches.isEmpty()) {
            System.out.println("(无命中)");
        }
        matches.forEach(match ->
                System.out.println(match.file() + ":" + match.line() + ": " + match.content()));
        System.out.println("== 命中 " + matches.size() + " 行 ==");
    }

    /**
     * 选择标准输出的编码，并绕过 {@code System.out} 自带的编码器。
     *
     * <p>背景：Windows 上 JDK 19+ 的 {@code stdout.encoding} 跟随控制台代码页（简体中文环境通常是
     * GBK），而 {@code file.encoding} 是 UTF-8。只要「写出的字节」与「终端解码的方式」不一致，
     * 中文就会变成乱码 —— 文件内容本身始终是 UTF-8 且被正确读取，问题只发生在输出编码上。
     *
     * <p>因为「GBK 写 + UTF-8 终端」与「UTF-8 写 + GBK 终端」都会乱码，固定任何一种编码都不行，
     * 所以这里按运行环境推断：
     *
     * <ol>
     *   <li>{@code -Ddemo.encoding=UTF-8}（或 GBK 等）显式指定，优先级最高；</li>
     *   <li>输出被重定向、或由 IDE 运行窗口 / CI 捕获（非真实终端）时用 UTF-8，
     *       因为现代工具链都按 UTF-8 解码；</li>
     *   <li>连接到真实终端时使用 JVM 探测到的 {@code stdout.encoding}，
     *       即终端代码页对应的编码（中文 Windows 的控制台通常是 GBK）。</li>
     * </ol>
     *
     * @return 实际生效的输出编码
     */
    private static Charset configureStdout() {
        Charset charset = chooseStdoutEncoding(
                System.getProperty("demo.encoding"),
                System.getProperty("stdout.encoding"),
                isInteractiveTerminal());
        System.setOut(new PrintStream(
                new FileOutputStream(FileDescriptor.out), true, charset));
        return charset;
    }

    /**
     * 判断标准输出是否连接到真实终端（而不是管道、文件或 IDE 捕获的管道）。
     *
     * <p>JDK 22 起 {@code System.console()} 即使 stdin/stdout 被重定向也会返回对象，
     * 需要用新增的 {@code Console.isTerminal()} 才能区分；本工程以 {@code --release 17}
     * 编译，编译期没有这个方法，因此用反射调用。在 JDK 17~21 上 {@code console()} 返回
     * {@code null} 就代表输出被重定向，语义与 {@code isTerminal() == false} 一致。
     */
    private static boolean isInteractiveTerminal() {
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

    /**
     * 编码选择规则（抽成静态方法以便单独验证）。
     *
     * @param forced        {@code -Ddemo.encoding} 的值，可为 {@code null}
     * @param consoleEncoding JVM 探测到的控制台编码，可为 {@code null}
     * @param hasConsole    是否连接了真实控制台
     */
    static Charset chooseStdoutEncoding(String forced, String consoleEncoding, boolean hasConsole) {
        if (forced != null && !forced.isBlank()) {
            return Charset.forName(forced);
        }
        if (!hasConsole) {
            return StandardCharsets.UTF_8;
        }
        if (consoleEncoding != null && !consoleEncoding.isBlank()) {
            return Charset.forName(consoleEncoding);
        }
        return StandardCharsets.UTF_8;
    }
}
