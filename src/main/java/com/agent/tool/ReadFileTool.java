package com.agent.tool;

import com.fasterxml.jackson.databind.JsonNode;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Tool：读取指定文本文件的内容。
 *
 * <p>编码固定为 UTF-8；文件中的非法 UTF-8 字节序列会抛出 {@code MalformedInputException}
 * （{@link IOException} 的子类），而不是被静默替换成问号，以便调用方明确区分
 * 「读取文本」与「误读二进制」。
 */
public class ReadFileTool implements Tool {

    @Override
    public String name() {
        return "read_file";
    }

    @Override
    public String description() {
        return "读取项目内一个 UTF-8 文本文件的完整内容";
    }

    @Override
    public JsonNode parameters() {
        return ToolSchemas.object(
                ToolSchemas.Param.required("path", "string", "文件路径（相对项目根目录）"));
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
        String content = readFile(request.resolvePath("path"));
        return ToolResult.ok(name(), "文件内容:\n" + content,
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

    private static void requireRegularFile(Path path) throws NoSuchFileException {
        if (path == null || !Files.isRegularFile(path)) {
            throw new NoSuchFileException("不是文件或不存在: " + path);
        }
    }
}
