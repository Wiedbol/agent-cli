package com.agent.tool;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;

/**
 * Tool：创建或修改项目内的文本文件。
 *
 * <p>这是 Agent 从「只能读」升级到「能自己写」的关键一步，也因此是守卫的重点关注对象：
 * 风险等级声明为 {@link Tool.Risk#WRITE}，路径参数交给守卫与 {@link ToolRequest#resolvePath}
 * 双重校验。
 *
 * <p>默认行为是「覆盖写入」，因此写之前不需要文件存在 —— 父目录会自动创建，
 * 省掉模型「先建目录再写文件」的多余步骤。用 {@code append=true} 可以改为追加。
 */
public class WriteFileTool implements Tool {

    @Override
    public String name() {
        return "write_file";
    }

    @Override
    public String description() {
        return "在项目目录内创建或覆盖一个文本文件，父目录不存在时会自动创建；"
                + "append=true 时改为在文件末尾追加内容";
    }

    @Override
    public com.fasterxml.jackson.databind.JsonNode parameters() {
        return ToolSchemas.object(
                ToolSchemas.Param.required("path", "string", "文件路径（相对项目根目录）"),
                ToolSchemas.Param.required("content", "string", "要写入的文本内容"),
                ToolSchemas.Param.optional("append", "boolean", "true 表示追加而不是覆盖，默认 false"));
    }

    @Override
    public Risk risk() {
        return Risk.WRITE;
    }

    @Override
    public List<String> pathArguments() {
        return List.of("path");
    }

    @Override
    public ToolResult execute(ToolRequest request) throws Exception {
        long start = System.nanoTime();
        Path file = request.resolvePath("path");
        String content = request.requireString("content");
        boolean append = Boolean.parseBoolean(request.optionalString("append", "false"));

        Path parent = file.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        if (append) {
            Files.write(file, bytes, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } else {
            Files.write(file, bytes, StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
        }

        String relative = request.root().relativize(file).toString().replace('\\', '/');
        String action = append ? "追写" : "写入";
        return ToolResult.ok(name(),
                "已" + action + " " + bytes.length + " 字节到 " + relative,
                elapsedMillis(start));
    }

    private static long elapsedMillis(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }
}
