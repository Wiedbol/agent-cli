package com.agent.tool;

import com.fasterxml.jackson.databind.JsonNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;

/**
 * Tool：递归列出目录下的文件。
 *
 * <p>返回的是相对 {@code root} 的路径，并统一使用 {@code /} 作为分隔符 —— 这样同一份输出
 * 在 macOS 与 Windows 上完全一致，后续喂给 LLM 或写进测试断言都不会因平台而异。
 */
public class ListFilesTool implements Tool {

    private final IgnoreRules ignoreRules;

    public ListFilesTool() {
        this(IgnoreRules.defaults());
    }

    public ListFilesTool(IgnoreRules ignoreRules) {
        this.ignoreRules = Objects.requireNonNull(ignoreRules, "ignoreRules");
    }

    /** 用配置文件（如 .agentignore）构造。 */
    public static ListFilesTool fromConfig(Path configFile) throws IOException {
        return new ListFilesTool(IgnoreRules.load(configFile));
    }

    // ---- 统一 Tool 接口（V4）：把 listFiles 适配成模型可调用的工具 ----

    @Override
    public String name() {
        return "list_files";
    }

    @Override
    public String description() {
        return "递归列出项目内某个目录下的文件（相对路径，忽略 .agentignore 中的规则），"
                + "maxDepth=1 时只看直接子项";
    }

    @Override
    public JsonNode parameters() {
        return ToolSchemas.object(
                ToolSchemas.Param.required("path", "string", "目录路径（相对项目根目录）"),
                ToolSchemas.Param.optional("maxDepth", "integer", "最大递归层数，默认不限"));
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
        Path dir = request.resolvePath("path");
        int maxDepth = (int) request.optionalLong("maxDepth", Integer.MAX_VALUE);
        List<String> files = listFiles(dir, maxDepth);
        String output = files.isEmpty()
                ? "目录下没有文件: " + request.optionalString("path", ".")
                : "文件列表（共 " + files.size() + " 个）:\n" + String.join("\n", files);
        return ToolResult.ok(name(), output, (System.nanoTime() - start) / 1_000_000);
    }

    /**
     * 递归列出 {@code root} 下的所有文件。
     *
     * @param root 起始目录
     * @return 相对路径列表，按字典序排序
     * @throws NoSuchFileException {@code root} 不存在或不是目录
     * @throws IOException         遍历过程中发生 I/O 错误
     */
    public List<String> listFiles(Path root) throws IOException {
        return listFiles(root, Integer.MAX_VALUE);
    }

    /**
     * 带最大深度的递归列出（选做任务 1）。
     *
     * @param maxDepth 最大深度，1 表示只看 {@code root} 的直接子项
     * @throws IllegalArgumentException {@code maxDepth} 为负数
     */
    public List<String> listFiles(Path root, int maxDepth) throws IOException {
        if (maxDepth < 0) {
            throw new IllegalArgumentException("maxDepth 不能为负数: " + maxDepth);
        }
        requireDirectory(root);
        try (Stream<Path> stream = Files.walk(root, maxDepth)) {
            return stream
                    .filter(Files::isRegularFile)
                    .filter(path -> !ignoreRules.isIgnored(path, root))
                    .map(path -> toRelativeString(root, path))
                    .sorted()
                    .toList();
        }
    }

    /**
     * 以树形结构（带缩进）输出目录内容（挑战任务 1）。
     *
     * @return 每一行一个条目，目录以 {@code /} 结尾，缩进为 2 空格 × 层数
     */
    public List<String> tree(Path root) throws IOException {
        requireDirectory(root);
        try (Stream<Path> stream = Files.walk(root)) {
            List<String> lines = new ArrayList<>();
            for (Path path : stream.sorted().toList()) {
                if (path.equals(root) || ignoreRules.isIgnored(path, root)) {
                    continue;
                }
                Path relative = root.relativize(path);
                String indent = "  ".repeat(relative.getNameCount() - 1);
                String label = path.getFileName().toString() + (Files.isDirectory(path) ? "/" : "");
                lines.add(indent + label);
            }
            return List.copyOf(lines);
        }
    }

    private static String toRelativeString(Path root, Path path) {
        return root.relativize(path).toString().replace('\\', '/');
    }

    private static void requireDirectory(Path root) throws NoSuchFileException {
        if (root == null || !Files.isDirectory(root)) {
            throw new NoSuchFileException("不是目录或不存在: " + root);
        }
    }
}
