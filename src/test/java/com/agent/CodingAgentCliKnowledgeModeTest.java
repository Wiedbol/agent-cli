package com.agent;

import com.agent.command.CommandResult;
import com.agent.command.CommandRunner;
import com.agent.llm.FakeLLMClient;
import com.agent.llm.LLMClient;
import com.agent.llm.LLMResponse;
import com.agent.llm.ToolCall;
import com.agent.task.KnowledgeMode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.params.provider.CsvSource;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.HexFormat;
import java.security.MessageDigest;
import java.util.function.Consumer;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/** 从公开任务流程观察模式与工具结果，避免用内部装配断言替代行为。 */
class CodingAgentCliKnowledgeModeTest {
    @TempDir Path root;
    private final ByteArrayOutputStream output = new ByteArrayOutputStream();

    @BeforeEach void project() throws Exception {
        Files.writeString(root.resolve("pom.xml"), "<project/>");
        Files.createDirectories(source().getParent());
        Files.writeString(source(), "broken");
    }

    private Path source() { return root.resolve("src/main/java/App.java"); }
    private String text() { return output.toString(StandardCharsets.UTF_8); }
    private String id() {
        var match = Pattern.compile("任务: ([0-9a-f-]{36})").matcher(text());
        assertTrue(match.find(), text());
        return match.group(1);
    }

    private CodingAgentCli cli(LLMClient model, int budget) {
        var stream = new PrintStream(output, true, StandardCharsets.UTF_8);
        return new CodingAgentCli(root, model, true, budget, new BufferedReader(new StringReader("")),
                stream, stream, null, acceptance());
    }

    private CommandRunner acceptance() {
        return (dir, command, timeout) -> {
            boolean fixed = Files.readString(source()).equals("fixed");
            Path reports = Files.createDirectories(root.resolve("target/surefire-reports"));
            Files.writeString(reports.resolve("TEST-AppTest.xml"),
                    "<testsuite tests=\"1\" failures=\"" + (fixed ? 0 : 1)
                    + "\" errors=\"0\" skipped=\"0\"><testcase classname=\"AppTest\" name=\"checks\">"
                    + (fixed ? "" : "<failure message=\"expected fixed\"/>") + "</testcase></testsuite>");
            return new CommandResult(fixed ? 0 : 1, false, "test output", 1,
                    CommandResult.OutputCompleteness.COMPLETE);
        };
    }

    private LLMResponse edit() {
        return new LLMResponse(null, List.of(new ToolCall("repair", new ToolCall.Function("write_file",
                "{\"path\":\"src/main/java/App.java\",\"content\":\"fixed\"}"))), 1, 1);
    }

    @ParameterizedTest @EnumSource(KnowledgeMode.class)
    void shouldRepairAndInspectKnowledgeModeWithoutAvailableSkills(KnowledgeMode mode) {
        var model = new FakeLLMClient(List.of(edit(), new LLMResponse("请验收", null, 1, 1)));
        assertEquals(0, cli(model, 4).startManagedTask("修复边界", null, false, mode), text());
        String task = id();
        output.reset();
        assertEquals(0, cli(null, 4).showTask(task), text());
        assertTrue(text().contains("知识模式: " + mode), text());
        assertTrue(text().contains("状态: Verified"), text());
        assertTrue(text().contains("baseline: FAILED"), text());
        assertTrue(text().contains("final: PASSED"), text());
        assertFalse(model.requests().toString().contains("参考经验 "));
        assertTrue(model.toolRequests().stream().flatMap(List::stream).noneMatch(tool -> tool.name().contains("skill")));
    }

    /** 构造旧格式或有效摘要的坏字段，确保测到语义校验而非仅测摘要损坏。 */
    private void changeState(String task, Consumer<ObjectNode> change) throws Exception {
        var json = new ObjectMapper();
        Path file = root.resolve(".agent/tasks/" + task + "/state.json");
        var state = (ObjectNode) json.readTree(Files.readAllBytes(file));
        state.remove("checkpointSha256");
        change.accept(state);
        state.put("checkpointSha256", HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(json.writeValueAsBytes(state))));
        Files.write(file, json.writeValueAsBytes(state));
    }

    @ParameterizedTest
    @ValueSource(strings = {"missing", "null", "number", "unknown", "flag-type", "inconsistent"})
    void shouldRejectInvalidModeInNewCheckpointWithoutCallingModel(String invalid) throws Exception {
        var paused = new FakeLLMClient(List.of(new LLMResponse("尚未修复", null, 1, 1)));
        assertEquals(1, cli(paused, 1).startManagedTask("修复", null, false, KnowledgeMode.SKILL), text());
        String task = id();
        changeState(task, state -> {
            switch (invalid) {
                case "missing" -> state.remove("knowledgeMode");
                case "null" -> state.putNull("knowledgeMode");
                case "number" -> state.put("knowledgeMode", 0);
                case "unknown" -> state.put("knowledgeMode", "UNKNOWN");
                case "flag-type" -> state.put("experiencesEnabled", "false");
                default -> state.put("experiencesEnabled", true);
            }
        });
        Path stateFile = root.resolve(".agent/tasks/" + task + "/state.json");
        byte[] before = Files.readAllBytes(stateFile);
        output.reset();
        assertEquals(4, cli(null, 4).showTask(task), text());
        var never = new FakeLLMClient(List.of());
        assertEquals(4, cli(never, 4).resumeManagedTask(task, false), text());
        assertEquals(0, never.callCount());
        assertArrayEquals(before, Files.readAllBytes(stateFile));
    }

    @ParameterizedTest @CsvSource({"6, EXPERIENCE", "7, NONE", "7, EXPERIENCE"})
    void shouldInspectAndResumeLegacyModeWithoutChangingItsChoice(int schema, KnowledgeMode expected) throws Exception {
        var paused = new FakeLLMClient(List.of(new LLMResponse(null,
                List.of(read("source", "src/main/java/App.java")), 1, 1)));
        assertEquals(1, cli(paused, 1).startManagedTask("修复", null, false, expected), text());
        String task = id();
        changeState(task, state -> {
            state.put("schemaVersion", schema);
            state.remove("knowledgeMode");
            if (schema < 7) { state.remove(List.of("experiencesEnabled", "experienceAdviceIds", "experienceFailures")); }
        });
        output.reset();
        assertEquals(0, cli(null, 4).showTask(task), text());
        assertTrue(text().contains("知识模式: " + expected), text());
        assertEquals(0, cli(null, 4).raiseTaskBudget(task, 3), text());
        var resumed = new FakeLLMClient(List.of(edit(), new LLMResponse("请验收", null, 1, 1)));
        assertEquals(0, cli(resumed, 4).resumeManagedTask(task, false), text());
        output.reset();
        assertEquals(0, cli(null, 4).showTask(task), text());
        assertTrue(text().contains("知识模式: " + expected), text());
        assertTrue(text().contains("模型请求: 3/3"), text());
    }

    @ParameterizedTest @ValueSource(strings = {"read_file", "list_files", "search_code"})
    void shouldPauseLegacyHistoryThatMayContainMetadataBeforeRequestingModel(String tool) throws Exception {
        Path hidden = root.resolve(".agent/skills/internal.md");
        Files.createDirectories(hidden.getParent());
        Files.writeString(hidden, "PRIVATE_MATCH");
        String path = tool.equals("read_file") ? ".agent/skills/internal.md" : ".";
        String arguments = new ObjectMapper().createObjectNode().put("path", path).put("keyword", "MATCH").toString();
        var paused = new FakeLLMClient(List.of(new LLMResponse(null,
                List.of(new ToolCall("legacy", new ToolCall.Function(tool, arguments))), 1, 1)));
        assertEquals(1, cli(paused, 1).startManagedTask("修复", null, false, KnowledgeMode.NONE), text());
        String task = id();
        // 还原旧版允许读入历史的检查点，摘要保持有效，消息与操作仍成对。
        changeState(task, state -> {
            state.put("schemaVersion", 7);
            state.remove("knowledgeMode");
            var operation = (ObjectNode) state.path("progress").path("decisions").get(0).path("operations").get(0);
            operation.put("status", "SUCCEEDED");
            operation.put("observation", "PRIVATE_MATCH");
            for (var message : state.path("checkpoint").path("messages")) {
                if ("tool".equals(message.path("role").asText())) {
                    ((ObjectNode) message).put("content", "PRIVATE_MATCH");
                }
            }
        });
        assertEquals(0, cli(null, 4).raiseTaskBudget(task, 3), text());
        output.reset();
        var never = new FakeLLMClient(List.of(edit(), new LLMResponse("请验收", null, 1, 1)));
        assertEquals(1, cli(never, 4).resumeManagedTask(task, false), text());
        assertEquals(0, never.callCount());
        assertTrue(text().contains("旧任务上下文可能包含知识元数据"), text());
        assertTrue(text().contains("知识模式: NONE"), text());
        assertEquals("broken", Files.readString(source()));
    }

    private ToolCall read(String callId, String path) throws Exception {
        return new ToolCall(callId, new ToolCall.Function("read_file",
                new ObjectMapper().createObjectNode().put("path", path).toString()));
    }

    private String observation(FakeLLMClient model, String callId) {
        return model.requests().get(1).stream().filter(message -> callId.equals(message.toolCallId()))
                .findFirst().orElseThrow().content();
    }

    @ParameterizedTest @EnumSource(KnowledgeMode.class)
    void shouldDenyManagedMetadataReadWhileKeepingSourceReadable(KnowledgeMode mode) throws Exception {
        Path hidden = root.resolve(".agent/skills/review.md");
        Files.createDirectories(hidden.getParent());
        Files.writeString(hidden, "KNOWLEDGE_MARKER_PRIVATE");
        var model = new FakeLLMClient(List.of(new LLMResponse(null,
                List.of(read("hidden", ".agent/skills/review.md"), read("source", "src/main/java/App.java"),
                        edit().toolCalls().get(0)), 1, 1), new LLMResponse("请验收", null, 1, 1)));
        assertEquals(0, cli(model, 4).startManagedTask("修复", null, false, mode), text());
        assertFalse(observation(model, "hidden").contains("KNOWLEDGE_MARKER_PRIVATE"));
        assertTrue(observation(model, "hidden").contains("安全校验失败"), observation(model, "hidden"));
        assertTrue(observation(model, "source").contains("broken"));
    }

    @ParameterizedTest @EnumSource(KnowledgeMode.class)
    void shouldHideMetadataFromRootListingWithoutHidingOrdinaryFiles(KnowledgeMode mode) throws Exception {
        Path hidden = root.resolve(".agent/skills/internal.md");
        Files.createDirectories(hidden.getParent());
        Files.writeString(hidden, "KNOWLEDGE_MARKER_PRIVATE");
        Files.writeString(root.resolve(".agent-notes.md"), "VISIBLE_MATCH");
        var model = new FakeLLMClient(List.of(new LLMResponse(null, List.of(
                new ToolCall("listing", new ToolCall.Function("list_files", "{\"path\":\".\"}")),
                read("ordinary", ".agent-notes.md"), edit().toolCalls().get(0)), 1, 1),
                new LLMResponse("请验收", null, 1, 1)));
        assertEquals(0, cli(model, 4).startManagedTask("修复", null, false, mode), text());
        assertFalse(observation(model, "listing").contains(".agent/"), observation(model, "listing"));
        assertTrue(observation(model, "listing").contains("src/main/java/App.java"));
        assertTrue(observation(model, "ordinary").contains("VISIBLE_MATCH"));
    }

    @ParameterizedTest @EnumSource(KnowledgeMode.class)
    void shouldSearchSourceWithoutReturningHistoricalKnowledge(KnowledgeMode mode) throws Exception {
        Path hidden = root.resolve(".agent/skills/internal.md");
        Files.createDirectories(hidden.getParent());
        Files.writeString(hidden, "PRIVATE_MATCH");
        Files.writeString(root.resolve("notes.md"), "VISIBLE_MATCH");
        var model = new FakeLLMClient(List.of(new LLMResponse(null, List.of(
                new ToolCall("search", new ToolCall.Function("search_code", "{\"path\":\".\",\"keyword\":\"MATCH\"}")),
                edit().toolCalls().get(0)), 1, 1), new LLMResponse("请验收", null, 1, 1)));
        assertEquals(0, cli(model, 4).startManagedTask("修复", null, false, mode), text());
        assertFalse(observation(model, "search").contains("PRIVATE_MATCH"), observation(model, "search"));
        assertFalse(observation(model, "search").contains(".agent/"), observation(model, "search"));
        assertTrue(observation(model, "search").contains("VISIBLE_MATCH"));
    }

    @ParameterizedTest
    @CsvSource({"read_file, relative", "read_file, normalized", "read_file, absolute", "read_file, case",
            "list_files, relative", "list_files, normalized", "list_files, absolute", "list_files, case",
            "search_code, relative", "search_code, normalized", "search_code, absolute", "search_code, case"})
    void shouldRejectMetadataSubtreeRegardlessOfPathSpelling(String tool, String spelling) throws Exception {
        Path hidden = root.resolve(".agent/skills/internal.md");
        Files.createDirectories(hidden.getParent());
        Files.writeString(hidden, "PRIVATE_MATCH");
        String path = switch (spelling) {
            case "normalized" -> ".agent/skills/../skills";
            case "absolute" -> hidden.getParent().toString();
            case "case" -> root.getFileSystem().getSeparator().equals("\\") ? ".AgEnT/skills" : ".agent/skills";
            default -> ".agent/skills";
        };
        if (tool.equals("read_file")) { path += "/internal.md"; }
        String arguments = new ObjectMapper().createObjectNode().put("path", path).put("keyword", "MATCH").toString();
        var model = new FakeLLMClient(List.of(new LLMResponse(null,
                List.of(new ToolCall("blocked", new ToolCall.Function(tool, arguments)), edit().toolCalls().get(0)), 1, 1),
                new LLMResponse("请验收", null, 1, 1)));
        assertEquals(0, cli(model, 4).startManagedTask("修复", null, false, KnowledgeMode.NONE), text());
        assertTrue(observation(model, "blocked").contains("安全校验失败"), observation(model, "blocked"));
        assertFalse(observation(model, "blocked").contains("PRIVATE_MATCH"));
    }

    @Test void shouldKeepOrdinaryChatMetadataToolsUnchanged() throws Exception {
        Path hidden = root.resolve(".agent/skills/internal.md");
        Files.createDirectories(hidden.getParent());
        Files.writeString(hidden, "PRIVATE_MATCH");
        var model = new FakeLLMClient(List.of(new LLMResponse(null, List.of(
                read("read", ".agent/skills/internal.md"),
                new ToolCall("list", new ToolCall.Function("list_files", "{\"path\":\".\"}")),
                new ToolCall("search", new ToolCall.Function("search_code", "{\"path\":\".\",\"keyword\":\"MATCH\"}"))), 1, 1),
                new LLMResponse("说明完成", null, 1, 1)));
        assertEquals(0, cli(model, 4).runTask("说明文件"), text());
        assertTrue(observation(model, "read").contains("PRIVATE_MATCH"));
        assertTrue(observation(model, "list").contains(".agent/skills/internal.md"));
        assertTrue(observation(model, "search").contains("PRIVATE_MATCH"));
    }
}
