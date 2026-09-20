package com.agent.tool;

import com.agent.llm.LLMClient;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ToolRegistry 的离线验证：Schema、参数解析、复用 Lab02 工具、错误 Observation 与路径治理。
 */
class ToolRegistryTest {

    @TempDir
    Path root;

    @BeforeEach
    void prepareProject() throws IOException {
        Files.writeString(root.resolve("pom.xml"), "<project/>\n", StandardCharsets.UTF_8);
        Files.writeString(root.resolve("notes.txt"), "line1\nTODO: refactor\nline3\n",
                StandardCharsets.UTF_8);
        Files.createDirectories(root.resolve("src/main"));
        Files.writeString(root.resolve("src/main/Hello.java"),
                "public class Hello {\n    // TODO: greet\n}\n", StandardCharsets.UTF_8);
        Files.writeString(root.resolve(".agentignore"), "target/\n*.class\n", StandardCharsets.UTF_8);
    }

    private ToolRegistry registry() {
        return new ToolRegistry(root);
    }

    @Test
    void shouldDescribeThreeFileToolsWithSchemas() {
        List<LLMClient.ToolDef> definitions = registry().getToolDefinitions();

        List<String> names = definitions.stream().map(LLMClient.ToolDef::name).toList();
        assertEquals(List.of("list_files", "read_file", "search_code"), names);

        LLMClient.ToolDef readFile = definitions.get(1);
        JsonNode schema = readFile.parameters();
        assertEquals("object", schema.path("type").asText());
        assertEquals("string", schema.path("properties").path("path").path("type").asText());
        assertEquals("path", schema.path("required").get(0).asText());
        assertFalse(readFile.description().isBlank(), "工具描述决定模型是否知道何时调用它");

        JsonNode listFiles = definitions.get(0).parameters();
        assertEquals(2, listFiles.path("properties").size());
        assertEquals(1, listFiles.path("required").size(), "maxDepth 是可选项");
    }

    @Test
    void shouldReadFileThroughLab02Tool() {
        String result = registry().execute("read_file", "{\"path\":\"pom.xml\"}");

        assertTrue(result.startsWith("文件内容:"));
        assertTrue(result.contains("<project/>"));
    }

    @Test
    void shouldSupportPagedRead() {
        String result = registry().execute("read_file",
                "{\"path\":\"notes.txt\",\"offset\":1,\"limit\":1}");

        assertTrue(result.contains("TODO: refactor"));
        assertFalse(result.contains("line1"), "offset=1 应跳过第一行");
    }

    @Test
    void shouldListFilesWithOptionalMaxDepth() {
        String all = registry().execute("list_files", "{\"path\":\".\"}");
        assertTrue(all.contains("src/main/Hello.java"));
        assertTrue(all.contains("notes.txt"));

        String shallow = registry().execute("list_files", "{\"path\":\".\",\"maxDepth\":1}");
        assertTrue(shallow.contains("pom.xml"));
        assertFalse(shallow.contains("src/main/Hello.java"), "maxDepth=1 只看直接子项");
    }

    @Test
    void shouldSearchCodeThroughLab02Tool() {
        String result = registry().execute("search_code",
                "{\"keyword\":\"TODO\",\"path\":\".\"}");

        assertTrue(result.contains("notes.txt:2"));
        assertTrue(result.contains("src/main/Hello.java:2"));
    }

    @Test
    void shouldReportEmptySearchResultWithoutFailing() {
        String result = registry().execute("search_code",
                "{\"keyword\":\"这个关键字不存在\",\"path\":\".\"}");

        assertTrue(result.contains("没有找到"));
    }

    @Test
    void shouldReturnErrorMessageForUnknownTool() {
        String result = registry().execute("no_such_tool", "{}");

        assertTrue(result.contains("未知工具: no_such_tool"));
        assertTrue(result.contains("read_file"), "错误信息应列出可用工具，便于模型自我纠正");
    }

    @Test
    void shouldReturnErrorMessageForMalformedArguments() {
        assertTrue(registry().execute("read_file", "{not json}").contains("不是合法 JSON 对象"));
        assertTrue(registry().execute("read_file", "[1,2]").contains("不是合法 JSON 对象"));
    }

    @Test
    void shouldTreatBlankArgumentsAsEmpty() {
        String result = registry().execute("read_file", null);

        assertTrue(result.contains("执行工具失败"));
        assertTrue(result.contains("路径不能为空"), "缺参数应变成可读的 Observation 而不是异常");
    }

    @Test
    void shouldRejectPathEscapingProjectRoot() {
        String result = registry().execute("read_file", "{\"path\":\"../outside.txt\"}");

        assertTrue(result.contains("只允许访问项目根目录内的路径"));
    }

    @Test
    void shouldRejectAbsolutePathOutsideProjectRoot() {
        Path outside = root.getParent().resolve("outside.txt");
        String result = registry().execute("read_file",
                "{\"path\":\"" + outside.toString().replace('\\', '/') + "\"}");

        assertTrue(result.contains("只允许访问项目根目录内的路径"));
    }

    @Test
    void shouldReportMissingFileAsObservation() {
        String result = registry().execute("read_file", "{\"path\":\"not-exist.txt\"}");

        assertTrue(result.contains("执行工具失败"));
    }

    @Test
    void shouldTruncateOverlongToolResult() {
        ToolRegistry small = new ToolRegistry(root, 20);

        String result = small.execute("read_file", "{\"path\":\"notes.txt\"}");

        assertTrue(result.contains("结果已截断"), "超长结果必须截断并明确告知模型内容不完整");
    }

    @Test
    void shouldExposeRegisteredToolNamesAndRoot() {
        ToolRegistry registry = registry();

        assertEquals(root.toAbsolutePath().normalize(), registry.root());
        assertTrue(registry.toolNames().containsAll(List.of("list_files", "read_file", "search_code")));
    }
}
