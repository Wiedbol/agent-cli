package com.agent.tool;

import com.fasterxml.jackson.databind.JsonNode;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;

/** 以预期原文和版本约束局部修改，避免用猜测内容覆盖整个文件。 */
public final class ReplaceTextTool implements Tool {
    private final java.util.function.BiConsumer<Path, String> checkVersion;

    public ReplaceTextTool() { this((path, version) -> { }); }

    /** 任务记录的预期版本由装配点提供，工具不自行读写任务元数据。 */
    public ReplaceTextTool(java.util.function.BiConsumer<Path, String> checkVersion) {
        this.checkVersion = checkVersion;
    }
    @Override public String name() { return "replace_text"; }
    @Override public String description() { return "精确替换已有文本中的唯一原文，必须提供原始文件 SHA-256 版本"; }
    @Override public Risk risk() { return Risk.WRITE; }
    @Override public List<String> pathArguments() { return List.of("path"); }

    @Override public JsonNode parameters() {
        return ToolSchemas.object(
                ToolSchemas.Param.required("path", "string", "文件路径"),
                ToolSchemas.Param.required("old_text", "string", "非空且唯一匹配的预期原文"),
                ToolSchemas.Param.required("new_text", "string", "替换文本，空串表示删除"),
                ToolSchemas.Param.required("expected_version", "string", "原始文件字节的 SHA-256"),
                ToolSchemas.Param.optional("encoding", "string", "无 BOM 默认 UTF-8，可显式指定 GBK、UTF-16LE 或 UTF-16BE"));
    }

    @Override public ToolResult execute(ToolRequest request) throws Exception {
        long started = System.nanoTime();
        Edit edit = plan(request);
        Path temporary = Files.createTempFile(edit.file().getParent(), ".agent-edit-", ".tmp");
        try {
            Files.write(temporary, edit.after());
            requireFile(request);
            if (!java.util.Arrays.equals(edit.before(), Files.readAllBytes(edit.file()))) {
                throw new IllegalArgumentException("文件版本变化，拒绝修改");
            }
            Files.move(temporary, edit.file(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally { Files.deleteIfExists(temporary); }
        return ToolResult.ok(name(), "已精确替换 1 处: " + edit.relative()
                + "（" + edit.before().length + " -> " + edit.after().length + " 字节）"
                + "；新版本 SHA-256: " + FileVersion.hash(edit.after()), (System.nanoTime() - started) / 1_000_000);
    }

    @Override public String preview(ToolRequest request) throws Exception {
        Edit edit = plan(request);
        long oldLines = edit.oldContent().lines().count();
        long newLines = edit.newContent().lines().count();
        return "--- a/" + edit.relative() + "\n+++ b/" + edit.relative()
                + "\n@@ -" + (oldLines == 0 ? 0 : 1) + "," + oldLines
                + " +" + (newLines == 0 ? 0 : 1) + "," + newLines
                + " @@\n" + lines(edit.oldContent(), "-") + lines(edit.newContent(), "+");
    }

    private String lines(String text, String prefix) {
        String result = text.lines().map(line -> prefix + line + "\n").collect(java.util.stream.Collectors.joining());
        return result + (!text.isEmpty() && !text.endsWith("\n") && !text.endsWith("\r")
                ? "\\ No newline at end of file\n" : "");
    }

    private record Edit(Path file, String relative, byte[] before, byte[] after, String oldContent, String newContent) { }

    private Edit plan(ToolRequest request) throws Exception {
        Path file = requireFile(request);
        byte[] before = Files.readAllBytes(file);
        if (!FileVersion.hash(before).equals(request.requireString("expected_version"))) {
            throw new IllegalArgumentException("文件版本变化，拒绝修改");
        }
        checkVersion.accept(file, request.requireString("expected_version"));
        String oldText = argument(request, "old_text");
        String newText = argument(request, "new_text");
        if (oldText.isEmpty()) { throw new IllegalArgumentException("预期原文不能为空"); }
        EncodedText original = EncodedText.decode(before,
                request.arg("encoding").isMissingNode() ? null : request.requireString("encoding"));
        String content = original.text();
        int index = content.indexOf(oldText);
        if (index < 0 || content.indexOf(oldText, index + 1) >= 0) {
            throw new IllegalArgumentException("预期原文必须恰好匹配一处");
        }
        String changed = content.substring(0, index) + original.replacement(newText) + content.substring(index + oldText.length());
        return new Edit(file, request.root().relativize(file).toString().replace('\\', '/'), before,
                original.encode(changed), content, changed);
    }

    /** 词法根目录检查之外，再拒绝会改变真实写入目标的符号链接和目录别名。 */
    private Path requireFile(ToolRequest request) throws Exception {
        Path file = request.resolvePath("path");
        Path actualRoot = request.root().toRealPath();
        Path expected = actualRoot.resolve(request.root().relativize(file)).normalize();
        Path actual = file.toRealPath();
        if (!actual.startsWith(actualRoot) || !actual.equals(expected) || Files.isSymbolicLink(file)) {
            throw new SecurityException("路径包含链接或越出实际项目根目录");
        }
        if (!Files.isRegularFile(file) || !Files.isWritable(file)) {
            throw new IllegalArgumentException("精确替换需要可写的已有普通文件");
        }
        return file;
    }

    private String argument(ToolRequest request, String name) {
        JsonNode value = request.arg(name);
        if (!value.isTextual()) { throw new IllegalArgumentException(name + " 必须是字符串"); }
        return value.textValue();
    }

}
