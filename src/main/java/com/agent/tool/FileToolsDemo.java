package com.agent.tool;

import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.PrintStream;
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
        useUtf8Stdout();

        Path root = Path.of(".");
        IgnoreRules ignoreRules = IgnoreRules.load(Path.of(IGNORE_CONFIG));
        ListFilesTool lister = new ListFilesTool(ignoreRules);
        ReadFileTool reader = new ReadFileTool();
        SearchCodeTool searcher = new SearchCodeTool(ignoreRules);

        List<String> rest = new ArrayList<>(List.of(args));
        boolean treeMode = rest.remove("--tree");

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
     * 把标准输出固定为 UTF-8。
     *
     * <p>Windows 上 JDK 19+ 的 {@code stdout.encoding} 默认跟随控制台代码页（简体中文环境通常是
     * GBK），而 {@code file.encoding} 却是 UTF-8。于是 Java 按 GBK 写出字节、终端按 UTF-8 解码，
     * 中文就变成了乱码 —— 注意文件内容本身是 UTF-8 且被正确读取，问题只发生在输出编码上。
     *
     * <p>这里直接写入 {@link FileDescriptor#out}，绕开 {@code System.out} 自带的编码器，
     * 使输出与终端设置无关。若在仍使用 GBK 的旧式控制台（如未执行 {@code chcp 65001} 的 cmd.exe）
     * 中运行，可用 {@code -Dstdout.encoding=GBK} 之外的方式还原：先执行 {@code chcp 65001}。
     */
    private static void useUtf8Stdout() {
        System.setOut(new PrintStream(
                new FileOutputStream(FileDescriptor.out), true, StandardCharsets.UTF_8));
    }
}
