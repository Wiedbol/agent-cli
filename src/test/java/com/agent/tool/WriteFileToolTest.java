package com.agent.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 写文件工具：Agent 从「只读」变成「可改写代码」的关键能力。 */
class WriteFileToolTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @TempDir
    Path root;

    private final WriteFileTool tool = new WriteFileTool();

    private ToolResult run(String json) throws Exception {
        return tool.execute(new ToolRequest(root, MAPPER.readTree(json)));
    }

    @Test
    void shouldDeclareWriteRiskAndPathArgument() {
        assertEquals("write_file", tool.name());
        assertEquals(Tool.Risk.WRITE, tool.risk());
        assertEquals(java.util.List.of("path"), tool.pathArguments());
        assertEquals("object", tool.parameters().path("type").asText());
        assertEquals(2, tool.parameters().path("required").size());
    }

    @Test
    void shouldCreateFileAndParentDirectories() throws Exception {
        ToolResult result = run("{\"path\":\"docs/notes/todo.md\",\"content\":\"# 待办\\n- 写测试\"}");

        assertTrue(result.success());
        Path file = root.resolve("docs/notes/todo.md");
        assertEquals("# 待办\n- 写测试", Files.readString(file, StandardCharsets.UTF_8));
        assertTrue(result.output().contains("docs/notes/todo.md"));
    }

    @Test
    void shouldOverwriteExistingFileByDefault() throws Exception {
        Files.writeString(root.resolve("a.txt"), "旧内容");

        run("{\"path\":\"a.txt\",\"content\":\"新内容\"}");

        assertEquals("新内容", Files.readString(root.resolve("a.txt")));
    }

    @Test
    void shouldNeverReplaceAFileThatAppearedAfterCreateApproval() throws Exception {
        var args = MAPPER.readTree("{\"path\":\"src/new.txt\",\"content\":\"first\"}");
        var request = new ToolRequest(root, args);
        assertTrue(tool.createNew(request).success());

        assertThrows(FileAlreadyExistsException.class, () -> tool.createNew(request));
        assertEquals("first", Files.readString(root.resolve("src/new.txt")));
    }

    @Test
    void shouldRejectAutomaticCreationThroughALink(@TempDir Path outside) throws Exception {
        try {
            Files.createSymbolicLink(root.resolve("link"), outside);
        } catch (UnsupportedOperationException | IOException unavailable) {
            org.junit.jupiter.api.Assumptions.assumeTrue(false, "当前环境不支持创建符号链接");
        }
        var args = MAPPER.readTree("{\"path\":\"link/nested/new.txt\",\"content\":\"content\"}");

        assertThrows(SecurityException.class, () -> tool.createNew(new ToolRequest(root, args)));
        assertTrue(Files.notExists(outside.resolve("nested")));
    }

    @Test
    void shouldAppendWhenRequested() throws Exception {
        Files.writeString(root.resolve("log.txt"), "第一行\n");

        run("{\"path\":\"log.txt\",\"content\":\"第二行\\n\",\"append\":true}");

        assertEquals("第一行\n第二行\n", Files.readString(root.resolve("log.txt")));
    }

    @Test
    void shouldRejectWritingOutsideProjectRoot() {
        assertThrows(SecurityException.class,
                () -> run("{\"path\":\"../escaped.txt\",\"content\":\"x\"}"));
        assertTrue(Files.notExists(root.getParent().resolve("escaped.txt")));
    }

    @Test
    void shouldReportByteCountInOutput() throws Exception {
        ToolResult result = run("{\"path\":\"a.txt\",\"content\":\"abc\"}");

        assertTrue(result.output().contains("3 字节"));
    }
}
