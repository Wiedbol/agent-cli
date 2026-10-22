package com.agent;

import com.agent.command.CommandRunner;
import com.agent.llm.FakeLLMClient;
import com.agent.llm.LLMClient;
import com.agent.command.CommandResult;
import com.agent.llm.LLMResponse;
import com.agent.llm.ToolCall;
import com.agent.tool.ReadFileTool;
import com.agent.tool.ToolRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.params.provider.CsvSource;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** 从既有 CLI 验证注册工具、文件结果、模型反馈和用户审批。 */
class CodingAgentCliPreciseEditTest {
    @TempDir Path root;
    private final ObjectMapper json = new ObjectMapper();
    private final ByteArrayOutputStream output = new ByteArrayOutputStream();

    private String text() { return output.toString(StandardCharsets.UTF_8); }

    private String version(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private ToolCall replacement(String path, String oldText, String newText, String hash) throws Exception {
        var args = json.createObjectNode().put("path", path).put("old_text", oldText)
                .put("new_text", newText).put("expected_version", hash);
        return new ToolCall("edit-1", new ToolCall.Function("replace_text", json.writeValueAsString(args)));
    }

    private CodingAgentCli cli(LLMClient model, boolean approve, BufferedReader reader) {
        PrintStream stream = new PrintStream(output, true, StandardCharsets.UTF_8);
        CommandRunner forbidden = (dir, command, timeout) -> { throw new AssertionError("普通编辑不得运行命令"); };
        return new CodingAgentCli(root, model, approve, 4, reader, stream, stream, null, forbidden);
    }

    private FakeLLMClient model(ToolCall call) {
        return new FakeLLMClient(List.of(new LLMResponse(null, List.of(call), 1, 1),
                new LLMResponse("完成", null, 1, 1)));
    }

    @Test
    void shouldReplaceUniqueOriginalThroughRegisteredTool() throws Exception {
        byte[] before = "class App { int value = 1; }\n".getBytes(StandardCharsets.UTF_8);
        Path file = root.resolve("App.java");
        Files.write(file, before);
        FakeLLMClient model = model(replacement("App.java", "value = 1", "value = 2", version(before)));

        assertEquals(0, cli(model, true, new BufferedReader(new StringReader(""))).runTask("修改常量"));
        assertEquals("class App { int value = 2; }\n", Files.readString(file));
        assertTrue(text().contains("--- a/App.java"), "--yes 仍须显示 Diff");
        assertTrue(model.toolRequests().get(0).stream().anyMatch(tool -> tool.name().equals("replace_text")));
        assertTrue(model.requests().get(1).stream().anyMatch(message -> "tool".equals(message.role())
                && message.content().contains("新版本") && message.content().contains(versionUnchecked(file))));
    }

    private String versionUnchecked(Path file) {
        try { return version(Files.readAllBytes(file)); }
        catch (Exception failure) { throw new AssertionError(failure); }
    }

    @ParameterizedTest
    @ValueSource(strings = {"y", "n", ""})
    void shouldShowDiffBeforeApprovalAndRespectRejection(String answer) throws Exception {
        Path file = root.resolve("App.java");
        byte[] before = "old\n".getBytes(StandardCharsets.UTF_8);
        Files.write(file, before);
        BufferedReader reader = new BufferedReader(new StringReader(answer.isEmpty() ? "" : answer + "\n")) {
            @Override public String readLine() throws java.io.IOException {
                assertTrue(text().contains("--- a/App.java"), text());
                assertTrue(text().contains("+++ b/App.java"), text());
                assertTrue(text().contains("-old") && text().contains("+new"), text());
                assertArrayEquals(before, Files.readAllBytes(file), "预览和审批前不允许写入");
                return super.readLine();
            }
        };
        FakeLLMClient model = model(replacement("App.java", "old", "new", version(before)));
        cli(model, false, reader).runTask("修改");
        assertEquals(answer.equals("y") ? "new\n" : "old\n", Files.readString(file));
    }

    @Test
    void shouldExposeOriginalByteVersionWhenReadingForAnEdit() throws Exception {
        byte[] bytes = "original\r\n".getBytes(StandardCharsets.UTF_8);
        Files.write(root.resolve("App.java"), bytes);
        FakeLLMClient model = new FakeLLMClient(List.of(
                new LLMResponse(null, List.of(new ToolCall("read", new ToolCall.Function("read_file", "{\"path\":\"App.java\"}"))), 1, 1),
                new LLMResponse("完成", null, 1, 1)));
        cli(model, true, new BufferedReader(new StringReader(""))).runTask("读取文件");
        String hash = version(bytes);
        assertTrue(model.requests().get(1).stream().anyMatch(message -> "tool".equals(message.role())
                && message.content().contains("文件版本 SHA-256: " + hash)), text());
        assertArrayEquals(bytes, Files.readAllBytes(root.resolve("App.java")));
    }

    @ParameterizedTest
    @ValueSource(strings = {"absent", "multiple", "overlap", "empty", "stale", "escape", "protected", "missing-version"})
    void shouldRejectInvalidEditWithoutApprovalOrPartialChanges(String scenario) throws Exception {
        String content = scenario.equals("multiple") ? "old old\n" : scenario.equals("overlap") ? "aaa" : "old\n";
        byte[] before = content.getBytes(StandardCharsets.UTF_8);
        Path file = root.resolve(scenario.equals("protected") ? ".env" : "App.java");
        Files.write(file, before);
        String oldText = scenario.equals("absent") ? "missing" : scenario.equals("overlap") ? "aa" : scenario.equals("empty") ? "" : "old";
        String path = scenario.equals("escape") ? "../outside.java" : file.getFileName().toString();
        var call = replacement(path, oldText, "new", scenario.equals("stale") ? "0".repeat(64) : version(before));
        if (scenario.equals("missing-version")) {
            var args = (com.fasterxml.jackson.databind.node.ObjectNode) json.readTree(call.arguments());
            args.remove("expected_version");
            call = new ToolCall(call.id(), new ToolCall.Function(call.name(), args.toString()));
        }
        BufferedReader forbidden = new BufferedReader(new StringReader("")) {
            @Override public String readLine() { throw new AssertionError("无效修改不应请求批准"); }
        };
        FakeLLMClient model = model(call);
        cli(model, false, forbidden).runTask("修改");
        assertArrayEquals(before, Files.readAllBytes(file));
        assertTrue(model.requests().get(1).stream().anyMatch(message -> "tool".equals(message.role())
                && (message.content().contains("失败") || message.content().contains("拒绝"))), text());
    }

    @Test
    void shouldKeepHumanEditMadeWhileReviewingDiff() throws Exception {
        Path file = root.resolve("App.java");
        byte[] before = "old\n".getBytes(StandardCharsets.UTF_8);
        Files.write(file, before);
        BufferedReader reader = new BufferedReader(new StringReader("")) {
            @Override public String readLine() throws java.io.IOException {
                assertTrue(text().contains("-old") && text().contains("+new"));
                Files.writeString(file, "old\nhuman edit\n");
                return "y";
            }
        };
        var model = model(replacement("App.java", "old", "new", version(before)));
        cli(model, false, reader).runTask("修改");
        assertEquals("old\nhuman edit\n", Files.readString(file));
        assertTrue(model.requests().get(1).stream().anyMatch(message -> "tool".equals(message.role())
                && message.content().contains("文件版本变化")));
    }

    @ParameterizedTest
    @ValueSource(strings = {"repeat", "read-first"})
    void shouldRequireManagedTaskVersionEvenIfModelSuppliesCurrentHash(String sequence) throws Exception {
        Files.writeString(root.resolve("pom.xml"), "<project/>");
        Path file = root.resolve("App.java");
        Files.writeString(file, "old\n");
        byte[] human = "old\nhuman\n".getBytes(StandardCharsets.UTF_8);
        ToolCall replace = replacement("App.java", "old", "new", version(human));
        ToolCall first = sequence.equals("read-first")
                ? new ToolCall("read-current", new ToolCall.Function("read_file", "{\"path\":\"App.java\"}")) : replace;
        ToolCall second = new ToolCall("replace-again", replace.function());
        FakeLLMClient model = new FakeLLMClient(List.of(new LLMResponse(null, List.of(first, second), 1, 1),
                new LLMResponse("完成", null, 1, 1)));
        LLMClient changedDuringRequest = (messages, tools) -> {
            if (model.callCount() == 0) { Files.write(file, human); }
            return model.chat(messages, tools);
        };
        CommandRunner failing = (dir, command, timeout) -> {
            Path reports = Files.createDirectories(root.resolve("target/surefire-reports"));
            Files.writeString(reports.resolve("TEST-AppTest.xml"),
                    "<testsuite tests=\"1\" failures=\"1\" errors=\"0\" skipped=\"0\">"
                    + "<testcase classname=\"AppTest\" name=\"checks\"><failure message=\"wrong value\"/></testcase></testsuite>");
            return new CommandResult(1, false, "failed", 1, CommandResult.OutputCompleteness.COMPLETE);
        };
        PrintStream stream = new PrintStream(output, true, StandardCharsets.UTF_8);
        CodingAgentCli cli = new CodingAgentCli(root, changedDuringRequest, true, 3,
                new BufferedReader(new StringReader("")), stream, stream, null, failing);
        assertNotEquals(0, cli.startManagedTask("修复", null));
        assertArrayEquals(human, Files.readAllBytes(file));
        assertTrue(text().contains("任务记录"), text());
        assertEquals(1, model.callCount(), "版本冲突应暂停而非继续下一轮");
    }

    @ParameterizedTest
    @CsvSource({"UTF-8, LF, false", "UTF-8, CRLF, false", "UTF-8, CRLF, true",
            "UTF-16LE, CRLF, true", "UTF-16BE, LF, true", "GBK, CRLF, false", "UTF-16LE, CRLF, false"})
    void shouldPreserveEncodingBomAndExistingLineEndings(String charsetName, String ending, boolean bom) throws Exception {
        var charset = java.nio.charset.Charset.forName(charsetName);
        String newline = ending.equals("CRLF") ? "\r\n" : "\n";
        byte[] prefix = !bom ? new byte[0] : charsetName.equals("UTF-8") ? HexFormat.of().parseHex("efbbbf")
                : charsetName.equals("UTF-16LE") ? HexFormat.of().parseHex("fffe") : HexFormat.of().parseHex("feff");
        byte[] body = ("头" + newline + "旧值" + newline + "尾" + newline).getBytes(charset);
        byte[] before = java.nio.ByteBuffer.allocate(prefix.length + body.length).put(prefix).put(body).array();
        Path file = root.resolve("App.java");
        Files.write(file, before);
        ToolCall call = replacement("App.java", "旧值", "新值\n第二行", version(before));
        if (!bom && !charsetName.equals("UTF-8")) {
            var args = (com.fasterxml.jackson.databind.node.ObjectNode) json.readTree(call.arguments());
            args.put("encoding", charsetName);
            call = new ToolCall(call.id(), new ToolCall.Function(call.name(), args.toString()));
        }
        cli(model(call), true, new BufferedReader(new StringReader(""))).runTask("修改");
        byte[] expectedBody = ("头" + newline + "新值" + newline + "第二行" + newline + "尾" + newline).getBytes(charset);
        byte[] expected = java.nio.ByteBuffer.allocate(prefix.length + expectedBody.length).put(prefix).put(expectedBody).array();
        assertArrayEquals(expected, Files.readAllBytes(file), text());
    }

    @Test
    void shouldRejectRealPathEscapeThroughDirectoryAlias() throws Exception {
        Path workspace = Files.createDirectory(root.resolve("workspace"));
        Path other = Files.createDirectory(root.resolve("other"));
        byte[] before = "old\n".getBytes(StandardCharsets.UTF_8);
        Files.write(other.resolve("App.java"), before);
        Path alias = workspace.resolve("alias");
        if (System.getProperty("os.name").toLowerCase().contains("win")) {
            Process link = new ProcessBuilder("cmd.exe", "/d", "/c", "mklink", "/J", alias.toString(), other.toString())
                    .redirectErrorStream(true).start();
            String diagnostic = new String(link.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            assertEquals(0, link.waitFor(), diagnostic);
        } else { Files.createSymbolicLink(alias, other); }
        FakeLLMClient model = model(replacement("alias/App.java", "old", "new", version(before)));
        PrintStream stream = new PrintStream(output, true, StandardCharsets.UTF_8);
        new CodingAgentCli(workspace, model, true, 4, new BufferedReader(new StringReader("")), stream, stream)
                .runTask("修改");
        assertArrayEquals(before, Files.readAllBytes(other.resolve("App.java")), text());
        assertTrue(model.requests().get(1).stream().anyMatch(message -> "tool".equals(message.role())
                && message.content().contains("安全校验")), text());
    }

    @ParameterizedTest
    @ValueSource(strings = {"malformed", "unrepresentable", "mixed", "bom-conflict", "unsupported", "nul"})
    void shouldRejectUnsafeTextWithoutChangingOriginalBytes(String scenario) throws Exception {
        String oldText = scenario.equals("unrepresentable") ? "旧值" : "old";
        byte[] before = scenario.equals("malformed") ? new byte[]{(byte) 0xff, 0x00, 0x01}
                : scenario.equals("unrepresentable") ? "旧值\r\n".getBytes(java.nio.charset.Charset.forName("GBK"))
                : scenario.equals("mixed") ? "old\r\nsecond\n".getBytes(StandardCharsets.UTF_8)
                : scenario.equals("bom-conflict") ? HexFormat.of().parseHex("efbbbf6f6c640a")
                : "old\n".getBytes(StandardCharsets.UTF_8);
        String replacement = scenario.equals("unrepresentable") ? "😀" : scenario.equals("mixed") ? "one\ntwo"
                : scenario.equals("nul") ? "new\0" : "new";
        Files.write(root.resolve("App.java"), before);
        ToolCall call = replacement("App.java", oldText, replacement, version(before));
        var args = (com.fasterxml.jackson.databind.node.ObjectNode) json.readTree(call.arguments());
        if (scenario.equals("unrepresentable")) { args.put("encoding", "GBK"); }
        if (scenario.equals("bom-conflict")) { args.put("encoding", "UTF-16LE"); }
        if (scenario.equals("unsupported")) { args.put("encoding", "ISO-8859-1"); }
        FakeLLMClient model = model(new ToolCall(call.id(), new ToolCall.Function(call.name(), args.toString())));
        BufferedReader forbidden = new BufferedReader(new StringReader("")) {
            @Override public String readLine() { throw new AssertionError("不安全的编码修改不得请求批准"); }
        };
        cli(model, false, forbidden).runTask("修改");
        assertArrayEquals(before, Files.readAllBytes(root.resolve("App.java")));
        assertTrue(model.requests().get(1).stream().anyMatch(message -> "tool".equals(message.role())
                && message.content().contains("失败")), text());
    }

    @ParameterizedTest
    @CsvSource({"replace_text,pom.xml", "replace_text,src/test/java/Original.java",
            "replace_text,.agent/forbidden.txt", "write_file,pom.xml", "write_file,src/test/java/Original.java"})
    void shouldKeepManagedAcceptanceProtectedForBothWritingTools(String tool, String path) throws Exception {
        Files.writeString(root.resolve("pom.xml"), "<project/>");
        Path file = root.resolve(path);
        Files.createDirectories(file.getParent());
        if (!Files.exists(file)) { Files.writeString(file, "old\n"); }
        byte[] before = Files.readAllBytes(file);
        ToolCall call = tool.equals("replace_text")
                ? replacement(path, new String(before, StandardCharsets.UTF_8), "changed", version(before))
                : new ToolCall("whole-write", new ToolCall.Function("write_file",
                        json.createObjectNode().put("path", path).put("content", "changed").toString()));
        FakeLLMClient model = model(call);
        CommandRunner passing = (dir, command, timeout) -> {
            Path reports = Files.createDirectories(root.resolve("target/surefire-reports"));
            Files.writeString(reports.resolve("TEST-AppTest.xml"),
                    "<testsuite tests=\"1\" failures=\"0\" errors=\"0\" skipped=\"0\">"
                    + "<testcase classname=\"AppTest\" name=\"checks\"/></testsuite>");
            return new CommandResult(0, false, "passed", 1, CommandResult.OutputCompleteness.COMPLETE);
        };
        PrintStream stream = new PrintStream(output, true, StandardCharsets.UTF_8);
        new CodingAgentCli(root, model, true, 3, new BufferedReader(new StringReader("")),
                stream, stream, null, passing).startManagedTask("修改", null);
        assertArrayEquals(before, Files.readAllBytes(file));
        assertTrue(model.requests().get(1).stream().anyMatch(message -> "tool".equals(message.role())
                && message.content().contains("受保护")), text());
        assertFalse(text().contains("--- a/"), "受保护路径不能在批准前泄露修改预览");
    }

    @ParameterizedTest
    @CsvSource({"' ',''", "old,''"})
    void shouldPermitWhitespaceOriginalAndEmptyReplacement(String oldText, String replacement) throws Exception {
        Path file = root.resolve("App.java");
        byte[] before = ("first" + oldText + "last").getBytes(StandardCharsets.UTF_8);
        Files.write(file, before);
        cli(model(replacement("App.java", oldText, replacement, version(before))), true,
                new BufferedReader(new StringReader(""))).runTask("删除");
        assertEquals("firstlast", Files.readString(file), text());
    }

    @Test
    void shouldShowFullDiffEvenWhenItExceedsModelObservationLimit() throws Exception {
        byte[] before = "old\n".getBytes(StandardCharsets.UTF_8);
        Files.write(root.resolve("App.java"), before);
        String replacement = "x".repeat(9000) + "END-OF-CHANGE";
        cli(model(replacement("App.java", "old", replacement, version(before))), true,
                new BufferedReader(new StringReader(""))).runTask("修改");
        assertTrue(text().contains(replacement), "写前审阅不能截掉变更结尾");
        assertEquals(replacement + "\n", Files.readString(root.resolve("App.java")));
    }

    @Test
    void shouldShowValidZeroLineRangeWhenDeletingEntireFileContent() throws Exception {
        byte[] before = "old\n".getBytes(StandardCharsets.UTF_8);
        Files.write(root.resolve("App.java"), before);
        cli(model(replacement("App.java", "old\n", "", version(before))), true,
                new BufferedReader(new StringReader(""))).runTask("清空内容");
        assertEquals(0, Files.size(root.resolve("App.java")));
        assertTrue(text().contains("@@ -1,1 +0,0 @@"), text());
    }
}
