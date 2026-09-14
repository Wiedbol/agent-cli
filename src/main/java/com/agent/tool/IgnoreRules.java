package com.agent.tool;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * {@code .agentignore} 风格的忽略规则：决定 Tool 在遍历目录时跳过哪些路径。
 *
 * <p>规则文件每一行是一条规则，语法刻意与 {@code .gitignore} 保持一致：
 *
 * <ul>
 *   <li>空行与以 {@code #} 开头的行被忽略（注释）；</li>
 *   <li>以 {@code /} 结尾的规则表示目录名，路径中任意一层目录与之同名即忽略
 *       （例如 {@code target/} 会同时忽略 target 目录及其全部内容）；</li>
 *   <li>其它规则按 glob 处理，同时匹配文件名与相对路径
 *       （例如 *.class、build/**）。</li>
 * </ul>
 *
 * <p>把规则从代码里的硬编码常量搬到配置文件，是为了让「跳过哪些文件」成为项目级的
 * 可配置决策，而不是每次调整都要重新编译。
 */
public final class IgnoreRules {

    /** 配置文件缺失时的兜底规则，与仓库根目录的 .agentignore 保持一致。 */
    private static final List<String> DEFAULT_PATTERNS = List.of(
            "target/",
            ".git/",
            ".idea/",
            ".vscode/",
            "node_modules/",
            "*.class",
            "*.jar",
            "*.png",
            "*.jpg",
            "*.jpeg",
            "*.gif",
            "*.ico",
            "*.zip",
            ".DS_Store");

    private final List<String> directoryNames;
    private final List<PathMatcher> globs;
    private final List<String> patterns;

    private IgnoreRules(List<String> directoryNames, List<PathMatcher> globs, List<String> patterns) {
        this.directoryNames = directoryNames;
        this.globs = globs;
        this.patterns = patterns;
    }

    /** 默认规则（不读取任何文件）。 */
    public static IgnoreRules defaults() {
        return parse(DEFAULT_PATTERNS);
    }

    /**
     * 从配置文件加载规则；文件不存在时返回 {@link #defaults()}。
     *
     * @param configFile 规则文件路径，允许为 {@code null}
     * @throws IOException 文件存在但读取失败
     */
    public static IgnoreRules load(Path configFile) throws IOException {
        if (configFile == null || !Files.isRegularFile(configFile)) {
            return defaults();
        }
        return parse(Files.readAllLines(configFile));
    }

    /** 解析规则行（便于测试，也便于把规则写进代码）。 */
    public static IgnoreRules parse(List<String> lines) {
        Objects.requireNonNull(lines, "lines");
        List<String> directoryNames = new ArrayList<>();
        List<PathMatcher> globs = new ArrayList<>();
        List<String> patterns = new ArrayList<>();
        for (String rawLine : lines) {
            String line = rawLine.trim();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            patterns.add(line);
            if (line.endsWith("/")) {
                directoryNames.add(line.substring(0, line.length() - 1));
            } else {
                globs.add(FileSystems.getDefault().getPathMatcher("glob:" + line));
            }
        }
        return new IgnoreRules(List.copyOf(directoryNames), List.copyOf(globs), List.copyOf(patterns));
    }

    /** 生效的规则原文，用于日志与报告。 */
    public List<String> patterns() {
        return patterns;
    }

    /**
     * 判断某个路径是否应被忽略。
     *
     * @param path 待判断路径（应为 {@code root} 之下，或等于 {@code root}）
     * @param root 遍历起点，用于计算相对路径
     */
    public boolean isIgnored(Path path, Path root) {
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(root, "root");
        Path relative;
        try {
            relative = root.relativize(path);
        } catch (IllegalArgumentException notUnderRoot) {
            relative = path;
        }
        Path fileName = relative.getFileName();
        String name = fileName == null ? relative.toString() : fileName.toString();

        for (String ignoredDirectory : directoryNames) {
            for (Path segment : relative) {
                if (segment.toString().equals(ignoredDirectory)) {
                    return true;
                }
            }
        }
        for (PathMatcher glob : globs) {
            if (glob.matches(Path.of(name)) || glob.matches(relative)) {
                return true;
            }
        }
        return false;
    }
}
