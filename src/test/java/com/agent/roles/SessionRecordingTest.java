package com.agent.roles;

import com.agent.llm.FakeLLMClient;
import com.agent.llm.LLMResponse;
import com.agent.llm.Message;
import com.agent.llm.ToolCall;
import com.agent.session.SessionStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedReader;
import java.io.OutputStream;
import java.io.PrintStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 角色与记录层的协作：每条消息落盘、每轮元数据落盘、恢复后可继续对话。 */
class SessionRecordingTest {

    @TempDir
    Path dir;

    private static PrintStream silent() {
        return new PrintStream(OutputStream.nullOutputStream(), true, StandardCharsets.UTF_8);
    }

    @Test
    void shouldRecordEveryMessageAppendedToHistory() throws Exception {
        SessionStore store = SessionStore.start(dir, SessionStore.Mode.NEW, "rec");
        LlmRole llm = new LlmRole(new FakeLLMClient(List.of()), List.of(), store);
        ToolCall call = new ToolCall("call_1", new ToolCall.Function("read_file", "{\"path\":\"pom.xml\"}"));

        llm.beginTask("读取 pom.xml");
        llm.recordToolCalls(new LLMResponse("", List.of(call), 10, 2));
        llm.recordToolResult(call, "文件内容:\n<project/>");
        llm.recordFinalAnswer("项目是 agent-cli。");

        List<Message> recorded = SessionStore.load(dir, "rec").messages();
        assertEquals(List.of("user", "assistant", "tool", "assistant"),
                recorded.stream().map(Message::role).toList());
        assertEquals("读取 pom.xml", recorded.get(0).content());
        assertEquals("call_1", recorded.get(2).toolCallId());
    }

    @Test
    void shouldRestoreHistoryIntoNewRole() throws Exception {
        SessionStore first = SessionStore.start(dir, SessionStore.Mode.NEW, "restore");
        first.appendMessage(Message.user("上次的问题"));
        first.appendMessage(Message.assistant("上次的回答"));

        SessionStore reopened = SessionStore.start(dir, SessionStore.Mode.AUTO, null);
        List<Message> restored = reopened.loadMessages().messages();
        LlmRole llm = new LlmRole(new FakeLLMClient(List.of()), restored, reopened);

        List<Message> history = llm.history();
        assertEquals(List.of("system", "user", "assistant"),
                history.stream().map(Message::role).toList());
        assertEquals("上次的问题", history.get(1).content());
        assertTrue(history.get(0).content().contains("编程助手"), "恢复历史时仍然注入最新系统提示词");
    }

    @Test
    void shouldRecordTurnMetadataFromController() throws Exception {
        FakeLLMClient fake = new FakeLLMClient(List.of(new LLMResponse("答案", null, 111, 22)));
        SessionStore store = SessionStore.start(dir, SessionStore.Mode.NEW, "turns");
        LlmRole llm = new LlmRole(fake, List.of(), store);
        UserRole user = new UserRole(new BufferedReader(new StringReader("")), silent(), true);
        ControllerRole controller = new ControllerRole(llm, RegistrarRole.standard(dir), new GuardRole(dir),
                new ExecutorRole(dir, ExecutorRole.DEFAULT_MAX_RESULT_CHARS, null), user, 3, null, store);

        controller.runTask("你好");

        String raw = Files.readString(store.currentFile(), StandardCharsets.UTF_8);
        assertTrue(raw.contains("\"type\":\"turn\""), raw);
        assertTrue(raw.contains("\"promptTokens\":111"), raw);
        assertTrue(raw.contains("\"completionTokens\":22"), raw);
        assertTrue(raw.contains("\"tools\":\"-\""), raw);
    }

    @Test
    void shouldNotWriteAnythingWithoutRecorder() throws Exception {
        LlmRole llm = new LlmRole(new FakeLLMClient(List.of()));

        llm.beginTask("任务");
        llm.recordFinalAnswer("回答");

        assertEquals(3, llm.history().size(), "历史照常维护（system + user + assistant）");
        assertEquals(List.of(), SessionStore.listSessions(dir), "不注入记录器时不应产生任何会话文件");
        assertFalse(Files.exists(dir.resolve(".agent")));
    }
}
