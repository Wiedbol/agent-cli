package com.agent.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.NullNode;

import java.nio.file.Path;

/**
 * 一次工具调用的输入：项目根目录 + 模型给出的参数。
 *
 * <p>把「根目录」随请求传入，而不是让工具自己保存状态，有两个好处：
 * 工具实例可以在不同任务间复用；路径沙箱的基准来自调用方而不是工具的全局状态。
 *
 * @param root      项目根目录（所有相对路径的基准，也是安全边界）
 * @param arguments 模型给出的参数 JSON，允许为 {@code null}
 */
public record ToolRequest(Path root, JsonNode arguments) {

    public ToolRequest {
        if (root == null) {
            throw new IllegalArgumentException("root 不能为空");
        }
        root = root.toAbsolutePath().normalize();
        arguments = arguments == null ? NullNode.getInstance() : arguments;
    }

    public JsonNode arg(String name) {
        return arguments.path(name);
    }

    public boolean has(String name) {
        JsonNode node = arguments.path(name);
        return !node.isMissingNode() && !node.isNull() && !node.asText().isBlank();
    }

    /** 必填字符串参数；缺失或空白会抛出 IllegalArgumentException。 */
    public String requireString(String name) {
        if (!has(name)) {
            throw new IllegalArgumentException("缺少必填参数: " + name);
        }
        return arguments.path(name).asText();
    }

    public String optionalString(String name, String fallback) {
        return has(name) ? arguments.path(name).asText() : fallback;
    }

    public long optionalLong(String name, long fallback) {
        if (!has(name)) {
            return fallback;
        }
        return Long.parseLong(arguments.path(name).asText().trim());
    }

    /**
     * 解析路径参数并执行沙箱校验。
     *
     * <p>这是**工具自身的防御**：守卫已经在裁决阶段检查过一遍，但工具不能假设
     * 「调用方一定检查过」，否则任何绕过守卫的调用路径都会变成越权读写。
     */
    public Path resolvePath(String name) {
        String raw = requireString(name);
        Path path;
        try {
            path = Path.of(raw);
        } catch (RuntimeException invalid) {
            throw new IllegalArgumentException("路径不合法: " + raw);
        }
        Path resolved = (path.isAbsolute() ? path : root.resolve(path)).normalize();
        if (!resolved.startsWith(root)) {
            throw new SecurityException("路径越出项目根目录: " + raw);
        }
        return resolved;
    }
}
