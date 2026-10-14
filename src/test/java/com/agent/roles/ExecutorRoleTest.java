package com.agent.roles;

import com.agent.tool.Tool;
import com.agent.tool.ToolRequest;
import com.agent.tool.ToolResult;
import com.agent.tool.WriteFileTool;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 执行员：统一兜底、统一裁剪，绝不让异常穿透。 */
class ExecutorRoleTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @TempDir
    Path root;

    private ExecutorRole executor;

    @BeforeEach
    void createExecutor() {
        // @TempDir 在字段初始化之后注入，因此这里才能拿到 root
        executor = new ExecutorRole(root, 40, null);
    }

    /** 允许抛受检异常的测试用函数式接口（工具接口本身就允许抛 Exception）。 */
    @FunctionalInterface
    private interface ToolBody {
        ToolResult run(ToolRequest request) throws Exception;
    }

    private static Tool toolNamed(String name, ToolBody body) {
        return new Tool() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public String description() {
                return "测试用工具";
            }

            @Override
            public JsonNode parameters() {
                return MAPPER.createObjectNode();
            }

            @Override
            public Risk risk() {
                return Risk.READ;
            }

            @Override
            public ToolResult execute(ToolRequest request) throws Exception {
                return body.run(request);
            }
        };
    }

    @Test
    void shouldReturnToolResultOnSuccess() throws Exception {
        Tool tool = toolNamed("ok_tool", request -> ToolResult.ok("ok_tool", "一切正常", 1));

        ToolResult result = executor.execute(tool, MAPPER.readTree("{}"));

        assertTrue(result.success());
        assertEquals("一切正常", result.output());
    }

    @Test
    void shouldConvertIllegalArgumentIntoActionableFailure() throws Exception {
        Tool tool = toolNamed("bad_args", request -> {
            throw new IllegalArgumentException("缺少必填参数: path");
        });

        ToolResult result = executor.execute(tool, MAPPER.readTree("{}"));

        assertFalse(result.success());
        assertTrue(result.output().contains("参数错误"), result.output());
        assertTrue(result.output().contains("path"), result.output());
    }

    @Test
    void shouldConvertSecurityExceptionWithClearPrefix() throws Exception {
        Tool tool = toolNamed("unsafe", request -> {
            throw new SecurityException("路径越出项目根目录");
        });

        ToolResult result = executor.execute(tool, MAPPER.readTree("{}"));

        assertFalse(result.success());
        assertTrue(result.output().contains("安全校验失败"), result.output());
    }

    @Test
    void shouldConvertIoExceptionIntoFailure() throws Exception {
        Tool tool = toolNamed("io_fail", request -> {
            throw new IOException("磁盘写入失败");
        });

        ToolResult result = executor.execute(tool, MAPPER.readTree("{}"));

        assertFalse(result.success());
        assertTrue(result.output().contains("IOException"), result.output());
    }

    @Test
    void shouldTruncateOverlongOutput() throws Exception {
        Tool tool = toolNamed("verbose", request -> ToolResult.ok("verbose", "x".repeat(200), 1));

        ToolResult result = executor.execute(tool, MAPPER.readTree("{}"));

        assertTrue(result.output().contains("结果已截断"), result.output());
        assertTrue(result.output().length() < 200);
    }

    @Test
    void shouldExecuteRealToolAgainstProjectRoot() throws Exception {
        ToolResult result = executor.execute(new WriteFileTool(),
                MAPPER.readTree("{\"path\":\"out.txt\",\"content\":\"hello\"}"));

        assertTrue(result.success(), result.output());
        assertEquals("hello", Files.readString(root.resolve("out.txt")));
    }
}
