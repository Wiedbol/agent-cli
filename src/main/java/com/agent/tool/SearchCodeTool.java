package com.agent.tool;

import com.fasterxml.jackson.databind.JsonNode;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Tool：在目录中搜索包含指定内容的代码行。
 *
 * <p>单文件读取失败（二进制、无权限、非法编码）时<b>跳过该文件</b>并继续搜索，
 * 因为一次搜索跨越成百上千个文件，任何一个无法解析的文件都不应该让整次搜索失败。
 */
public class SearchCodeTool implements Tool {

    /** 一条命中记录：文件（相对路径）、行号（1 起）、该行内容（已 trim）。 */
    public record Match(String file, int line, String content) {
    }

    private final IgnoreRules ignoreRules;

    public SearchCodeTool() {
        this(IgnoreRules.defaults());
    }

    public SearchCodeTool(IgnoreRules ignoreRules) {
        this.ignoreRules = Objects.requireNonNull(ignoreRules, "ignoreRules");
    }

    /** 用配置文件（如 .agentignore）构造。 */
    public static SearchCodeTool fromConfig(Path configFile) throws IOException {
        return new SearchCodeTool(IgnoreRules.load(configFile));
    }

    // ---- 统一 Tool 接口（V4）：把 searchCode 适配成模型可调用的工具 ----

    @Override
    public String name() {
        return "search_code";
    }

    @Override
    public String description() {
        return "在项目某个目录中搜索包含指定关键字的代码行，返回「文件:行号: 内容」列表";
    }

    @Override
    public JsonNode parameters() {
        return ToolSchemas.object(
                ToolSchemas.Param.required("keyword", "string", "要搜索的关键字（子串匹配）"),
                ToolSchemas.Param.required("path", "string", "搜索起始目录（相对项目根目录）"));
    }

    @Override
    public Risk risk() {
        return Risk.READ;
    }

    @Override
    public List<String> pathArguments() {
        return List.of("path");
    }

    @Override
    public ToolResult execute(ToolRequest request) throws IOException {
        long start = System.nanoTime();
        String keyword = request.requireString("keyword");
        Path dir = request.resolvePath("path");
        List<Match> matches = searchCode(keyword, dir);
        String output;
        if (matches.isEmpty()) {
            output = "没有找到包含「" + keyword + "」的内容";
        } else {
            StringBuilder builder = new StringBuilder("搜索结果（共 " + matches.size() + " 条）:\n");
            for (Match match : matches) {
                builder.append(match.file()).append(':')
                        .append(match.line()).append(": ")
                        .append(match.content()).append('\n');
            }
            output = builder.toString();
        }
        return ToolResult.ok(name(), output, (System.nanoTime() - start) / 1_000_000);
    }

    /**
     * 按关键字做子串匹配。
     *
     * @param keyword 关键字，不能为空
     * @param root    搜索起始目录
     * @throws IllegalArgumentException 关键字为 {@code null} 或空串
     */
    public List<Match> searchCode(String keyword, Path root) throws IOException {
        if (keyword == null || keyword.isEmpty()) {
            throw new IllegalArgumentException("keyword 不能为空");
        }
        return search(root, line -> line.contains(keyword));
    }

    /**
     * 按正则匹配（选做任务 2）。
     *
     * <p>没有重载成 {@code searchCode(Pattern, Path)} 是为了避免
     * {@code searchCode(null, root)} 在 {@code String} 与 {@code Pattern} 两个重载之间
     * 产生歧义 —— 这也是「签名不统一」问题的一个具体表现。
     */
    public List<Match> searchCodeRegex(Pattern pattern, Path root) throws IOException {
        Objects.requireNonNull(pattern, "pattern");
        return search(root, line -> pattern.matcher(line).find());
    }

    private List<Match> search(Path root, Predicate<String> lineMatcher) throws IOException {
        if (root == null || !Files.isDirectory(root)) {
            throw new NoSuchFileException("不是目录或不存在: " + root);
        }
        List<Match> result = new ArrayList<>();
        // 先把路径收集成 List 再逐个读取：确保 Files.walk 的 Stream 已经关闭，
        // 避免 Windows 上目录句柄未释放导致的后续操作失败。
        try (Stream<Path> stream = Files.walk(root)) {
            List<Path> files = stream
                    .filter(Files::isRegularFile)
                    .filter(path -> !ignoreRules.isIgnored(path, root))
                    .sorted()
                    .toList();
            for (Path file : files) {
                searchInFile(lineMatcher, root, file, result);
            }
        }
        return result;
    }

    private void searchInFile(Predicate<String> lineMatcher, Path root, Path file, List<Match> result) {
        try (BufferedReader reader = Files.newBufferedReader(file)) {
            String line;
            int lineNumber = 0;
            while ((line = reader.readLine()) != null) {
                lineNumber++;
                if (lineMatcher.test(line)) {
                    result.add(new Match(
                            root.relativize(file).toString().replace('\\', '/'),
                            lineNumber,
                            line.trim()));
                }
            }
        } catch (IOException skipped) {
            // 二进制文件、无权限文件、非法 UTF-8：跳过该文件，不让整次搜索失败。
        }
    }
}
