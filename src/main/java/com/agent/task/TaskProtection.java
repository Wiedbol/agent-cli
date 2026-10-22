package com.agent.task;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.TreeMap;
import java.util.List;
import java.util.TreeSet;

/** 把既有测试与构建输入作为约定，新增测试仍可用于增强覆盖。 */
public final class TaskProtection {
    private final Path root;
    private final Map<String, String> frozen;
    private final java.util.function.Supplier<Map<String, String>> expectedFiles;

    public TaskProtection(Path root, Map<String, String> frozen) {
        this(root, frozen, null);
    }

    public TaskProtection(Path root, Map<String, String> frozen,
                          java.util.function.Supplier<Map<String, String>> expectedFiles) {
        this.root = root.toAbsolutePath().normalize();
        this.frozen = Map.copyOf(frozen);
        this.expectedFiles = expectedFiles;
    }

    /** 模型不能自行用新哈希承认未经任务记录的文件变化。 */
    public void checkFileVersion(Path file, String version) {
        if (expectedFiles == null) { return; }
        try {
            String relative = root.relativize(file.toRealPath()).toString().replace('\\', '/');
            if (!version.equals(expectedFiles.get().get(relative))) {
                throw new IllegalArgumentException("文件版本不符合任务记录: " + relative);
            }
        } catch (IOException failed) { throw new IllegalArgumentException("任务记录版本无法校验", failed); }
    }

    public static Map<String, String> freeze(Map<String, String> files) {
        Map<String, String> protectedFiles = new TreeMap<>();
        files.forEach((path, hash) -> {
            String lower = path.toLowerCase(java.util.Locale.ROOT);
            if (lower.startsWith("src/test/") || lower.contains("/src/test/") || isBuildInput(path)) {
                protectedFiles.put(path, hash);
            }
        });
        return protectedFiles;
    }

    private static boolean isBuildInput(String path) {
        path = path.toLowerCase(java.util.Locale.ROOT);
        String name = path.substring(path.lastIndexOf('/') + 1);
        return name.equals("pom.xml") || name.equals("mvnw") || name.equals("mvnw.cmd")
                || path.equals(".mvn") || path.startsWith(".mvn/") || path.contains("/.mvn/");
    }

    public boolean isWriteProtected(Path path) {
        Path normalized = path.toAbsolutePath().normalize();
        try {
            Path existing = normalized;
            while (!Files.exists(existing) && existing.getParent() != null) { existing = existing.getParent(); }
            Path actual = existing.toRealPath().resolve(existing.relativize(normalized)).normalize();
            if (!actual.startsWith(root)) { return true; }
            String relative = root.relativize(actual).toString().replace('\\', '/');
            String lower = relative.toLowerCase(java.util.Locale.ROOT);
            if (lower.equals(".agent") || lower.startsWith(".agent/") || isBuildInput(relative)) { return true; }
            for (String protectedPath : frozen.keySet()) {
                Path original = root.resolve(protectedPath);
                if (actual.equals(original) || original.startsWith(actual)
                        || Files.exists(normalized) && Files.exists(original) && Files.isSameFile(normalized, original)) {
                    return true;
                }
            }
            return false;
        } catch (IOException failed) { return true; }
    }

    public List<String> changes(Map<String, String> current) {
        var paths = new TreeSet<>(frozen.keySet());
        current.keySet().stream().filter(TaskProtection::isBuildInput).forEach(paths::add);
        return paths.stream().filter(path -> !java.util.Objects.equals(frozen.get(path), current.get(path))).toList();
    }

    public static List<String> fileChanges(Map<String, String> expected, Map<String, String> current) {
        var paths = new TreeSet<>(expected.keySet());
        paths.addAll(current.keySet());
        return paths.stream().filter(path -> !java.util.Objects.equals(expected.get(path), current.get(path)))
                .map(path -> (expected.containsKey(path) ? current.containsKey(path) ? "修改 " : "删除 " : "新增 ")
                        + path + " [" + expected.get(path) + " -> " + current.get(path) + "]").toList();
    }

    /** 校验异常必须越过工具 Observation 转换，让协调器停止后续副作用。 */
    public void check() {
        try {
            Map<String, String> current = WorkspaceSnapshot.capture(root);
            List<String> changes = changes(current);
            if (!changes.isEmpty()) { throw new Violation("任务验收输入已改变: " + String.join(", ", changes)
                    + (expectedFiles == null ? "" : "；现场变化: " + fileChanges(expectedFiles.get(), current))
                    + "；请明确重新约定验收，或 --abandon-task 保留文件并放弃"); }
            if (expectedFiles != null) {
                Map<String, String> expected = expectedFiles.get();
                var changed = fileChanges(expected, current);
                if (!changed.isEmpty()) { throw new Violation("文件版本不符合任务记录: " + changed
                        + "；请选择 --replan-task 基于当前文件重新规划，或 --abandon-task 放弃；--yes 不适用"); }
            }
        } catch (IOException failed) { throw new Violation("任务验收输入无法校验: " + failed.getMessage()); }
    }

    public static final class Violation extends RuntimeException {
        public Violation(String message) { super(message); }
    }
}
