package com.agent.session;

import com.agent.llm.Message;
import com.agent.llm.ToolCall;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 会话存储：追加写、读回、轮转、损坏行、会话选择与导出。 */
class SessionStoreTest {

    @TempDir
    Path dir;

    private SessionStore newSession(String name) throws IOException {
        return SessionStore.start(dir, SessionStore.Mode.NEW, name);
    }

    @Test
    void shouldAppendAndReloadMessages() throws Exception {
        SessionStore store = newSession("demo");
        store.appendMessage(Message.user("读取 pom.xml"));
        store.appendMessage(Message.assistant("已读取"));

        SessionStore.LoadResult loaded = SessionStore.load(dir, "demo");

        assertEquals(0, loaded.skippedLines());
        assertEquals(List.of("user", "assistant"),
                loaded.messages().stream().map(Message::role).toList());
        assertEquals("读取 pom.xml", loaded.messages().get(0).content());
    }

    @Test
    void shouldKeepToolCallPairingAfterReload() throws Exception {
        SessionStore store = newSession("pair");
        ToolCall call = new ToolCall("call_1", new ToolCall.Function("read_file", "{\"path\":\"pom.xml\"}"));
        store.appendMessage(Message.assistant("", List.of(call)));
        store.appendToolResult(Message.tool("call_1", "文件内容:\n<project/>"), call.arguments());

        List<Message> restored = SessionStore.load(dir, "pair").messages();

        assertEquals(2, restored.size());
        assertEquals("call_1", restored.get(0).toolCalls().get(0).id());
        assertEquals("call_1", restored.get(1).toolCallId());
        assertEquals("read_file", restored.get(0).toolCalls().get(0).name());
    }

    @Test
    void shouldRedactSensitiveToolResultOnDiskButMessageStaysIntact() throws Exception {
        SessionStore store = newSession("secret");
        Message toolMessage = Message.tool("call_1", "API_KEY=sk-abcdefghijklmnopqrstuvwxyz");
        store.appendToolResult(toolMessage, "{\"path\":\".env\"}");

        String raw = Files.readString(store.currentFile(), StandardCharsets.UTF_8);
        assertTrue(raw.contains("\"redacted\":true"), raw);
        assertTrue(raw.contains(Redactor.PLACEHOLDER));
        assertEquals("API_KEY=sk-abcdefghijklmnopqrstuvwxyz", toolMessage.content(),
                "内存中的消息不受过滤影响（R②：只替换落盘文本）");
    }

    @Test
    void shouldWriteTurnMetadata() throws Exception {
        SessionStore store = newSession("turn");
        store.appendTurn(1, 820, 673, 53, "read_file");

        String raw = Files.readString(store.currentFile(), StandardCharsets.UTF_8);

        assertTrue(raw.contains("\"type\":\"turn\""), raw);
        assertTrue(raw.contains("\"elapsedMillis\":820"), raw);
        assertTrue(raw.contains("\"promptTokens\":673"), raw);
        assertTrue(raw.contains("\"tools\":\"read_file\""), raw);
    }

    @Test
    void shouldSkipCorruptLines() throws Exception {
        SessionStore store = newSession("broken");
        store.appendMessage(Message.user("第一条"));
        Files.writeString(store.currentFile(), "{这行不是合法 JSON}\n", StandardCharsets.UTF_8,
                StandardOpenOption.APPEND);
        store.appendMessage(Message.user("第二条"));

        SessionStore.LoadResult loaded = SessionStore.load(dir, "broken");

        assertEquals(1, loaded.skippedLines());
        assertEquals(2, loaded.messages().size());
    }

    @Test
    void shouldRotateShardsAndReadThemAllBack() throws Exception {
        SessionStore store = SessionStore.start(dir, SessionStore.Mode.NEW, "rotate", 220, null);
        for (int i = 1; i <= 6; i++) {
            store.appendMessage(Message.user("消息 " + i + " 这是一段用来撑大文件的测试文本"));
        }

        List<Path> shards = SessionStore.shards(dir, "rotate");
        assertTrue(shards.size() > 1, "超过阈值后应该产生分片: " + shards);
        assertTrue(Files.exists(SessionPaths.shard(dir, "rotate", 2)));

        List<Message> restored = SessionStore.load(dir, "rotate").messages();
        assertEquals(6, restored.size(), "恢复时必须按顺序读回全部分片");
        assertTrue(restored.get(0).content().startsWith("消息 1"));
        assertTrue(restored.get(5).content().startsWith("消息 6"));
    }

    @Test
    void shouldAutoResumeLatestSession() throws Exception {
        SessionStore first = newSession("auto");
        first.appendMessage(Message.user("上次的问题"));

        SessionStore reopened = SessionStore.start(dir, SessionStore.Mode.AUTO, null);

        assertTrue(reopened.resumed());
        assertEquals("auto", reopened.sessionId());
        assertEquals(1, reopened.loadMessages().messages().size());
    }

    @Test
    void shouldCreateNewSessionWhenNothingToResume() throws Exception {
        SessionStore store = SessionStore.start(dir, SessionStore.Mode.AUTO, null);

        assertFalse(store.resumed());
        assertTrue(store.sessionId().startsWith("20"), store.sessionId());
    }

    @Test
    void shouldFailWhenResumeTargetIsMissing() {
        IOException failure = assertThrows(IOException.class,
                () -> SessionStore.start(dir, SessionStore.Mode.RESUME, "nope"));

        assertTrue(failure.getMessage().contains("nope"));
    }

    @Test
    void shouldResumeNamedSessionAndSuffixDuplicateNames() throws Exception {
        newSession("demo").appendMessage(Message.user("第一次"));

        SessionStore named = SessionStore.start(dir, SessionStore.Mode.NAMED, "demo");
        assertTrue(named.resumed(), "同名会话存在时应当续上");

        SessionStore forced = SessionStore.start(dir, SessionStore.Mode.NEW, "demo");
        assertEquals("demo-2", forced.sessionId(), "强制新建且重名时应加后缀");
    }

    @Test
    void startNewSessionShouldKeepOldFile() throws Exception {
        SessionStore store = newSession("old");
        store.appendMessage(Message.user("旧会话内容"));
        Path oldFile = store.currentFile();

        String newId = store.startNewSession();
        store.appendMessage(Message.user("新会话内容"));

        assertTrue(Files.exists(oldFile), "旧会话文件必须保留");
        assertTrue(Files.size(oldFile) > 0);
        assertFalse(newId.equals("old"));
        assertEquals(1, SessionStore.load(dir, "old").messages().size());
        assertEquals(1, SessionStore.load(dir, newId).messages().size());
    }

    @Test
    void shouldListSessionsAndResolveLatest() throws Exception {
        newSession("a").appendMessage(Message.user("a"));
        newSession("b").appendMessage(Message.user("b"));

        List<String> sessions = SessionStore.listSessions(dir);

        assertEquals(2, sessions.size());
        assertTrue(sessions.containsAll(List.of("a", "b")));
        assertTrue(SessionStore.latest(dir).isPresent());
    }

    @Test
    void shouldExportSessionAsMarkdown() throws Exception {
        SessionStore store = newSession("export");
        ToolCall call = new ToolCall("call_1", new ToolCall.Function("read_file", "{\"path\":\"pom.xml\"}"));
        store.appendTurn(1, 512, 100, 20, "read_file");
        store.appendMessage(Message.user("读 pom"));
        store.appendMessage(Message.assistant("", List.of(call)));
        store.appendToolResult(Message.tool("call_1", "文件内容:\n<project/>"), "{\"path\":\"pom.xml\"}");
        store.appendMessage(Message.assistant("这个项目是 agent-cli。"));

        String markdown = SessionExporter.toMarkdown(dir, "export");

        assertTrue(markdown.contains("# 会话 export"), markdown);
        assertTrue(markdown.contains("## 👤 用户"));
        assertTrue(markdown.contains("读 pom"));
        assertTrue(markdown.contains("**调用工具**：`read_file`"));
        assertTrue(markdown.contains("## 🔧 工具结果"));
        assertTrue(markdown.contains("第 1 轮"));
        assertTrue(markdown.contains("这个项目是 agent-cli"));
    }

    @Test
    void shouldExportRedactedMarker() throws Exception {
        SessionStore store = newSession("redacted");
        store.appendToolResult(Message.tool("call_1", "SECRET=1"), "{\"path\":\".env\"}");

        String markdown = SessionExporter.toMarkdown(dir, "redacted");

        assertTrue(markdown.contains("被敏感过滤替换"), markdown);
    }
}
