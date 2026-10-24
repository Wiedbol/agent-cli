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

/**
 * Tool：读取指定文本文件的内容。
 *
 * <p>公开读取方法保持 UTF-8；工具调用可识别 BOM 或显式编码，并返回原始字节版本。
 * 非法字节序列抛出 {@link IOException}，不能被静默替换成问号，否则后续编辑会污染原文。
 */
public class ReadFileTool implements Tool {
    private final Predicate<Path> isVisible;

    public ReadFileTool() {
        this(path -> true);
    }

    public ReadFileTool(Predicate<Path> isVisible) {
        this.isVisible = Objects.requireNonNull(isVisible, "isVisible");
    }

    @Override
    public String name() {
        return "read_file";
    }

    @Override
    public String description() {
        return "读取项目内文本及原始字节 SHA-256；识别 UTF-8/UTF-16 BOM，无 BOM 默认 UTF-8，可显式指定 encoding";
    }

    @Override
    public JsonNode parameters() {
        return ToolSchemas.object(
                ToolSchemas.Param.required("path", "string", "文件路径（相对项目根目录）"),
                ToolSchemas.Param.optional("encoding", "string", "可选 UTF-8、UTF-16LE、UTF-16BE 或 GBK，不猜测旧编码"));
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
        Path file = request.resolvePath("path");
        requireRegularFile(file);
        byte[] bytes = Files.readAllBytes(file);
        String content = EncodedText.decode(bytes,
                request.arg("encoding").isMissingNode() ? null : request.requireString("encoding")).text();
        return ToolResult.ok(name(), "文件版本 SHA-256: " + FileVersion.hash(bytes) + "\n文件内容:\n" + content,
                (System.nanoTime() - start) / 1_000_000);
    }

    /**
     * 读取整个文件。
     *
     * @param path 文件路径
     * @return 文件全部内容
     * @throws NoSuchFileException 文件不存在或不是普通文件
     * @throws IOException         读取失败或内容不是合法 UTF-8
     */
    public String readFile(Path path) throws IOException {
        requireRegularFile(path);
        return Files.readString(path);
    }

    /**
     * 分页读取（挑战任务 2）：只从文件中取出所需的一段行，避免把超大文件整体读入内存。
     *
     * <p>实现上用 {@link BufferedReader} 流式跳过前面的行，内存占用与 {@code limit} 相关，
     * 而不是与文件大小相关。返回内容使用 {@code \n} 连接，保证跨平台结果一致
     * （整文件读取的 {@link #readFile(Path)} 则原样保留文件中的换行符）。
     *
     * @param path   文件路径
     * @param offset 起始行号（0 起），超过文件总行数时返回空字符串
     * @param limit  最多读取的行数，必须为正数
     * @throws IllegalArgumentException {@code offset} 为负或 {@code limit} 非正
     */
    public String readFile(Path path, long offset, long limit) throws IOException {
        if (offset < 0) {
            throw new IllegalArgumentException("offset 不能为负数: " + offset);
        }
        if (limit <= 0) {
            throw new IllegalArgumentException("limit 必须为正数: " + limit);
        }
        requireRegularFile(path);

        List<String> slice = new ArrayList<>();
        try (BufferedReader reader = Files.newBufferedReader(path)) {
            String line;
            long lineNumber = 0;
            while ((line = reader.readLine()) != null) {
                if (lineNumber >= offset) {
                    if (slice.size() >= limit) {
                        break;
                    }
                    slice.add(line);
                }
                lineNumber++;
            }
        }
        return String.join("\n", slice);
    }

    private void requireRegularFile(Path path) throws NoSuchFileException {
        if (path != null && !isVisible.test(path)) {
            throw new SecurityException("管理任务禁止通用工具读取知识元数据或历史记录");
        }
        if (path == null || !Files.isRegularFile(path)) {
            throw new NoSuchFileException("不是文件或不存在: " + path);
        }
    }
}
