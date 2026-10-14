package com.agent.roles;

import com.agent.llm.FakeLLMClient;
import com.agent.llm.LLMClient;
import com.agent.llm.LLMResponse;
import com.agent.llm.Message;
import com.agent.llm.ToolCall;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 控制员：把「模型决策 → 守卫裁决 → 用户确认 → 执行员执行 → 结果回传」串起来。
 *
 * <p>全部用 {@link FakeLLMClient} 离线编排，因此可以精确断言「第几次请求里有什么」。
 */
class ControllerRoleTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @TempDir
    Path root;

    private LlmRole llm;
    private ControllerRole controller;

    @BeforeEach
    void prepareProject() throws IOException {
        Files.writeString(root.resolve("note.txt"), "项目说明：这是一个 Agent CLI。", StandardCharsets.UTF_8);
    }

    /** 装配控制员；user 为 null 时默认「自动同意」。 */
    private void build(LLMClient client, UserRole user, int maxSteps) {
        UserRole effectiveUser = user == null ? autoApproveUser() : user;
        this.llm = new LlmRole(client);
        this.controller = new ControllerRole(llm,
                RegistrarRole.standard(root),
                new GuardRole(root),
                new ExecutorRole(root, ExecutorRole.DEFAULT_MAX_RESULT_CHARS, null),
                effectiveUser,
                maxSteps,
                null);
    }

    private static UserRole autoApproveUser() {
        return new UserRole(new BufferedReader(new StringReader("")), silentOut(), true);
    }

    private static UserRole interactiveUser(String confirmAnswer) {
        return new UserRole(new BufferedReader(new StringReader(confirmAnswer)), silentOut(), false);
    }

    private static PrintStream silentOut() {
        return new PrintStream(OutputStream.nullOutputStream(), true, StandardCharsets.UTF_8);
    }

    private static ToolCall call(String id, String tool, Map<String, Object> args) throws IOException {
        return new ToolCall(id, new ToolCall.Function(tool, MAPPER.writeValueAsString(args)));
    }

    private List<Message> observations() {
        return llm.history().stream().filter(message -> "tool".equals(message.role())).toList();
    }

    @Test
    void shouldReturnFinalAnswerWithoutToolCalls() {
        FakeLLMClient fake = new FakeLLMClient(List.of(
                new LLMResponse("你好，我是 Coding Agent。", null, 10, 5)));
        build(fake, null, 5);

        String answer = controller.runTask("你好");

        assertEquals("你好，我是 Coding Agent。", answer);
        assertEquals(1, fake.callCount());
    }

    @Test
    void shouldExecuteReadToolAndFeedResultBack() throws Exception {
        FakeLLMClient fake = new FakeLLMClient(List.of(
                new LLMResponse("", List.of(call("call_1", "read_file", Map.of("path", "note.txt"))), 50, 5),
                new LLMResponse("这个项目是一个 Agent CLI。", null, 80, 10)));
        build(fake, null, 5);

        String answer = controller.runTask("note.txt 写了什么？");

        assertEquals("这个项目是一个 Agent CLI。", answer);
        assertEquals(2, fake.callCount());
        assertEquals(1, observations().size());
        assertEquals("call_1", observations().get(0).toolCallId());
        assertTrue(observations().get(0).content().contains("这是一个 Agent CLI"),
                observations().get(0).content());
    }

    @Test
    void shouldReportUnknownToolWithoutStoppingTheTask() throws Exception {
        FakeLLMClient fake = new FakeLLMClient(List.of(
                new LLMResponse("", List.of(call("call_1", "no_such_tool", Map.of())), 10, 5),
                new LLMResponse("我换用可用工具完成了任务。", null, 20, 10)));
        build(fake, null, 5);

        String answer = controller.runTask("调用一个不存在的工具");

        assertEquals("我换用可用工具完成了任务。", answer);
        assertTrue(observations().get(0).content().contains("未知工具"));
    }

    @Test
    void shouldFeedGuardDenialBackAsObservation() throws Exception {
        FakeLLMClient fake = new FakeLLMClient(List.of(
                new LLMResponse("", List.of(call("call_1", "read_file", Map.of("path", "../secret.txt"))), 10, 5),
                new LLMResponse("那个文件在项目外，我不能读取。", null, 20, 10)));
        build(fake, null, 5);

        String answer = controller.runTask("读取项目外的文件");

        assertTrue(answer.contains("不能读取"));
        assertTrue(observations().get(0).content().contains("守卫拒绝"),
                observations().get(0).content());
    }

    @Test
    void shouldWriteFileAfterUserApproval() throws Exception {
        FakeLLMClient fake = new FakeLLMClient(List.of(
                new LLMResponse("", List.of(call("call_1", "write_file",
                        Map.of("path", "gen/report.md", "content", "# 报告\n由 Agent 生成"))), 10, 5),
                new LLMResponse("已生成报告。", null, 20, 10)));
        build(fake, null, 5);

        controller.runTask("生成一份报告");

        Path created = root.resolve("gen/report.md");
        assertTrue(Files.exists(created));
        assertEquals("# 报告\n由 Agent 生成", Files.readString(created));
    }

    @Test
    void shouldNotWriteFileWhenUserRejects() throws Exception {
        FakeLLMClient fake = new FakeLLMClient(List.of(
                new LLMResponse("", List.of(call("call_1", "write_file",
                        Map.of("path", "gen/report.md", "content", "x"))), 10, 5),
                new LLMResponse("好的，我没有写入文件。", null, 20, 10)));
        build(fake, interactiveUser("n\n"), 5);

        String answer = controller.runTask("生成一份报告");

        assertFalse(Files.exists(root.resolve("gen/report.md")), "用户拒绝后不能留下任何文件");
        assertTrue(answer.contains("没有写入"));
        assertTrue(observations().get(0).content().contains("用户拒绝"),
                observations().get(0).content());
    }

    @Test
    void shouldAcceptYesAsConfirmation() throws Exception {
        FakeLLMClient fake = new FakeLLMClient(List.of(
                new LLMResponse("", List.of(call("call_1", "write_file",
                        Map.of("path", "gen/ok.txt", "content", "ok"))), 10, 5),
                new LLMResponse("已写入。", null, 20, 10)));
        build(fake, interactiveUser("yes\n"), 5);

        controller.runTask("写一个文件");

        assertTrue(Files.exists(root.resolve("gen/ok.txt")));
    }

    @Test
    void shouldExecuteEveryToolCallInOneResponse() throws Exception {
        FakeLLMClient fake = new FakeLLMClient(List.of(
                new LLMResponse("", List.of(
                        call("call_1", "read_file", Map.of("path", "note.txt")),
                        call("call_2", "list_files", Map.of("path", "."))), 10, 5),
                new LLMResponse("都完成了。", null, 20, 10)));
        build(fake, null, 5);

        controller.runTask("读文件并列出目录");

        List<String> ids = observations().stream().map(Message::toolCallId).toList();
        assertEquals(List.of("call_1", "call_2"), ids);
    }

    @Test
    void shouldStopAtMaxSteps() throws Exception {
        LLMClient alwaysCallsTool = (messages, tools) -> {
            try {
                return new LLMResponse("", List.of(call("call_" + messages.size(),
                        "list_files", Map.of("path", "."))), 1, 1);
            } catch (IOException impossible) {
                throw new IllegalStateException(impossible);
            }
        };
        build(alwaysCallsTool, null, 3);

        String answer = controller.runTask("无限调用工具");

        assertTrue(answer.contains("达到最大步数限制"), answer);
        assertEquals(3, observations().size());
    }

    @Test
    void shouldReportLlmFailureAsAnswer() {
        LLMClient failing = (messages, tools) -> {
            throw new IOException("连接超时");
        };
        build(failing, null, 5);

        assertEquals("LLM 调用失败: 连接超时", controller.runTask("你好"));
    }

    @Test
    void shouldTreatMalformedArgumentsAsObservation() throws Exception {
        FakeLLMClient fake = new FakeLLMClient(List.of(
                new LLMResponse("", List.of(new ToolCall("call_1",
                        new ToolCall.Function("read_file", "{不是 JSON}"))), 10, 5),
                new LLMResponse("我重新组织参数。", null, 20, 10)));
        build(fake, null, 5);

        controller.runTask("读文件");

        assertTrue(observations().get(0).content().contains("不是合法 JSON"), observations().get(0).content());
    }
}
