package com.agent.tool;

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
}
