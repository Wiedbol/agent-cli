package com.agent;

import com.agent.llm.FakeLLMClient;
import com.agent.llm.LLMResponse;
import com.agent.session.SessionPaths;
import com.agent.session.SessionStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 端到端：CLI 与会话存储的协作（写入、恢复、clear 开新会话、单任务）。 */
class CodingAgentCliSessionTest {

    @TempDir
    Path root;

    private PrintStream silent() {
        return new PrintStream(OutputStream.nullOutputStream(), true, StandardCharsets.UTF_8);
    }

    private CodingAgentCli cli(SessionStore store, FakeLLMClient client, String input) {
        return new CodingAgentCli(root, client, true, 4,
                new BufferedReader(new StringReader(input)),
                silent(), silent(), store);
    }

    @Test
    void shouldWriteConversationToSessionFile() throws Exception {
        SessionStore store = SessionStore.start(SessionPaths.defaultDir(root),
                SessionStore.Mode.NEW, "cli");
        FakeLLMClient fake = new FakeLLMClient(List.of(new LLMResponse("你好，我是 Agent。", null, 5, 5)));

        cli(store, fake, "你好\nexit\n").runRepl();

        List<String> contents = SessionStore.load(SessionPaths.defaultDir(root), "cli")
                .messages().stream().map(com.agent.llm.Message::content).toList();
        assertEquals(List.of("你好", "你好，我是 Agent。"), contents);
    }

    @Test
    void shouldResumeLastSessionOnNextRun() throws Exception {
        Path dir = SessionPaths.defaultDir(root);
        SessionStore first = SessionStore.start(dir, SessionStore.Mode.NEW, "chat");
        cli(first, new FakeLLMClient(List.of(new LLMResponse("第一轮回答", null, 1, 1))),
                "第一个问题\nexit\n").runRepl();

        SessionStore resumed = SessionStore.start(dir, SessionStore.Mode.AUTO, null);
        FakeLLMClient second = new FakeLLMClient(List.of(new LLMResponse("第二轮回答", null, 1, 1)));

        assertTrue(resumed.resumed());
        assertEquals("chat", resumed.sessionId());

        cli(resumed, second, "第二个问题\nexit\n").runRepl();

        List<String> sentToModel = second.requests().get(0).stream()
                .map(com.agent.llm.Message::content).toList();
        assertTrue(sentToModel.contains("第一个问题"), "恢复后模型应当看到上一轮的用户消息：" + sentToModel);
        assertTrue(sentToModel.contains("第一轮回答"), "恢复后模型应当看到上一轮的助手回答：" + sentToModel);
    }

    @Test
    void clearShouldStartNewSessionAndKeepOldFile() throws Exception {
        Path dir = SessionPaths.defaultDir(root);
        SessionStore store = SessionStore.start(dir, SessionStore.Mode.NEW, "keep");
        Path oldFile = store.currentFile();
        FakeLLMClient fake = new FakeLLMClient(List.of(new LLMResponse("回答", null, 1, 1)));

        cli(store, fake, "问题\nclear\nexit\n").runRepl();

        assertTrue(Files.exists(oldFile), "旧会话文件必须保留");
        assertTrue(SessionStore.listSessions(dir).size() >= 2, "clear 之后应当出现新会话文件");
        assertEquals("问题", SessionStore.load(dir, "keep").messages().get(0).content());
    }

    @Test
    void singleTaskShouldWriteIntoItsOwnSession() throws Exception {
        Path dir = SessionPaths.defaultDir(root);
        String taskId = "task-" + SessionPaths.timestampName();
        SessionStore store = SessionStore.start(dir, SessionStore.Mode.NEW, taskId);
        FakeLLMClient fake = new FakeLLMClient(List.of(new LLMResponse("任务完成", null, 1, 1)));

        cli(store, fake, "").runTask("把 README 里的 TODO 汇总");

        List<String> contents = SessionStore.load(dir, taskId).messages().stream()
                .map(com.agent.llm.Message::content).toList();
        assertEquals(List.of("把 README 里的 TODO 汇总", "任务完成"), contents);
        assertTrue(taskId.startsWith("task-"));
    }

    @Test
    void shouldWarnWhenCorruptLinesWereSkipped() throws Exception {
        Path dir = SessionPaths.defaultDir(root);
        SessionStore store = SessionStore.start(dir, SessionStore.Mode.NEW, "corrupt");
        store.appendMessage(com.agent.llm.Message.user("有效消息"));
        Files.writeString(store.currentFile(), "{坏行}\n", StandardCharsets.UTF_8,
                java.nio.file.StandardOpenOption.APPEND);

        ByteArrayOutputStream errBytes = new ByteArrayOutputStream();
        CodingAgentCli cli = new CodingAgentCli(root,
                new FakeLLMClient(List.of(new LLMResponse("ok", null, 1, 1))), true, 3,
                new BufferedReader(new StringReader("exit\n")),
                silent(), new PrintStream(errBytes, true, StandardCharsets.UTF_8),
                SessionStore.start(dir, SessionStore.Mode.RESUME, "corrupt"));

        cli.runRepl();

        assertTrue(errBytes.toString(StandardCharsets.UTF_8).contains("损坏记录"));
    }
}
