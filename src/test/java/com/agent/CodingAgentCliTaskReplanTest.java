package com.agent;

import com.agent.command.CommandResult;
import com.agent.command.CommandRunner;
import com.agent.llm.FakeLLMClient;
import com.agent.llm.LLMClient;
import com.agent.llm.LLMResponse;
import com.agent.llm.ToolCall;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

class CodingAgentCliTaskReplanTest {
    @TempDir Path root;
    private final ByteArrayOutputStream output = new ByteArrayOutputStream();
    @BeforeEach void project() throws Exception {
        Files.writeString(root.resolve("pom.xml"), "<project/>");
        Files.createDirectories(root.resolve("src/main/java"));
        Files.writeString(source(), "broken");
    }
    private Path source() { return root.resolve("src/main/java/App.java"); }
    private String text() { return output.toString(StandardCharsets.UTF_8); }
    private CodingAgentCli cli(LLMClient model, String input, int budget) {
        PrintStream stream = new PrintStream(output, true, StandardCharsets.UTF_8);
        return new CodingAgentCli(root, model, true, budget, new BufferedReader(new StringReader(input)),
                stream, stream, null, acceptance());
    }
    private String id() {
        var match = Pattern.compile("任务: ([0-9a-f-]{36})").matcher(text());
        assertTrue(match.find(), text()); return match.group(1);
    }
    private CommandRunner acceptance() {
        return (dir, command, timeout) -> {
            boolean fixed = Files.exists(source()) && Files.readString(source()).contains("human-fixed");
            Path reports = Files.createDirectories(root.resolve("target/surefire-reports"));
            Files.writeString(reports.resolve("TEST-AppTest.xml"), "<testsuite tests=\"1\" failures=\""
                    + (fixed ? 0 : 1) + "\" errors=\"0\" skipped=\"0\"><testcase classname=\"AppTest\" name=\"checks\">"
                    + (fixed ? "" : "<failure message=\"broken\"/>") + "</testcase></testsuite>");
            return new CommandResult(fixed ? 0 : 1, false, "real fixture result", 1, CommandResult.OutputCompleteness.COMPLETE);
        };
    }
    private String pausedRead() {
        var model = new FakeLLMClient(List.of(new LLMResponse(null, List.of(new ToolCall("read-before-interruption",
                new ToolCall.Function("read_file", "{\"path\":\"src/main/java/App.java\"}"))), 2, 3)));
        assertEquals(1, cli(model, "", 1).startManagedTask("修复计算目标", null), text());
        return id();
    }

    @Test void shouldReplanFromHumanFilesAndPreserveGoalAndCumulativeBudget() throws Exception {
        String task = pausedRead();
        Files.writeString(source(), "human-fixed // keep my changes");
        assertEquals(0, cli(null, "", 12).raiseTaskBudget(task, 3));
        assertEquals(1, cli(null, "replan " + task + "\n", 12).replanTask(task, true), text());
        assertEquals("human-fixed // keep my changes", Files.readString(source()));
        assertTrue(text().contains("REPLAN"), text());
        var model = new FakeLLMClient(List.of(new LLMResponse("请验收", null, 1, 1)));
        assertEquals(0, cli(model, "", 12).resumeManagedTask(task, false), text());
        assertEquals(1, model.callCount());
        var messages = model.requests().get(0);
        assertTrue(messages.stream().anyMatch(m -> m.content() != null && m.content().contains("human-fixed // keep my changes")));
        assertEquals(1, messages.stream().filter(m -> m.role().equals("user") && m.content().contains("修复计算目标")).count());
        assertTrue(text().contains("模型请求: 2/3"), text());
        assertTrue(text().contains("状态: Verified"), text());
    }

    @Test void shouldCancelPendingCallsAndKeepProtocolPairsWithoutExecutingOldWrite() throws Exception {
        var model = new FakeLLMClient(List.of(new LLMResponse(null, List.of(new ToolCall("first-read",
                new ToolCall.Function("read_file", "{\"path\":\"src/main/java/App.java\"}")), new ToolCall("stale-write",
                new ToolCall.Function("write_file", "{\"path\":\"src/main/java/App.java\",\"content\":\"overwrite human\"}"))), 2, 3)));
        PrintStream stream = new PrintStream(output, true, StandardCharsets.UTF_8);
        var blocked = new CodingAgentCli(root, model, false, 12, new BufferedReader(new StringReader("")),
                stream, stream, null, acceptance());
        assertEquals(1, blocked.startManagedTask("修复计算目标", null, false), text());
        String task = id();
        Files.writeString(source(), "human-fixed // must remain");
        assertEquals(1, cli(null, "replan " + task + "\n", 12).replanTask(task, true), text());
        var resumed = new FakeLLMClient(List.of(new LLMResponse("请验收", null, 1, 1)));
        assertEquals(0, cli(resumed, "", 12).resumeManagedTask(task, false), text());
        assertEquals("human-fixed // must remain", Files.readString(source()));
        var results = resumed.requests().get(0).stream().filter(m -> m.role().equals("tool")).toList();
        assertEquals(List.of("first-read", "stale-write"), results.stream().map(com.agent.llm.Message::toolCallId).toList());
        assertTrue(results.get(1).content().contains("取消"));
        assertTrue(results.get(1).content().contains("不代表成功"));
        assertTrue(text().contains("CANCELLED"), text());
        assertTrue(text().contains("工具调用: 2"), text());
    }

    @Test void shouldDetectAddedAndDeletedFilesAndPreserveThemAfterReplan() throws Exception {
        String task = pausedRead();
        Files.delete(source());
        Path added = root.resolve("src/main/java/Human.java");
        Files.writeString(added, "human addition");
        var untouched = new FakeLLMClient(List.of());
        assertEquals(1, cli(untouched, "", 12).resumeManagedTask(task, false), text());
        assertEquals(0, untouched.callCount());
        assertTrue(text().contains("新增 src/main/java/Human.java"), text());
        assertTrue(text().contains("删除 src/main/java/App.java"), text());
        assertEquals(1, cli(null, "replan " + task + "\n", 12).replanTask(task, true), text());
        var saved = new com.agent.task.TaskStore(root).read(task);
        assertFalse(saved.files.containsKey("src/main/java/App.java"));
        assertTrue(saved.checkpoint.replanContext.contains("human addition"));
        assertFalse(Files.exists(source()));
        assertEquals("human addition", Files.readString(added));
    }

    @Test void shouldIgnoreGeneratedFilesWhenResuming() throws Exception {
        String task = pausedRead();
        Files.writeString(Files.createDirectories(root.resolve("target/classes")).resolve("App.class"), "generated");
        Files.writeString(Files.createDirectories(root.resolve(".agent/cache")).resolve("new.txt"), "generated");
        assertEquals(0, cli(null, "", 12).raiseTaskBudget(task, 2));
        var model = new FakeLLMClient(List.of(new LLMResponse("仍未修复", null, 1, 1)));
        assertEquals(1, cli(model, "", 12).resumeManagedTask(task, false), text());
        assertEquals(1, model.callCount(), text());
        assertFalse(text().contains("文件版本不符合"), text());
    }

    @Test void shouldRequireExactApprovalAndNeverReadNoninteractiveInput() throws Exception {
        String task = pausedRead();
        Files.writeString(source(), "human-fixed");
        var store = new com.agent.task.TaskStore(root);
        var expected = store.read(task).files;
        for (String answer : List.of("", "y\n", "replan wrong-id\n")) {
            assertEquals(1, cli(null, answer, 12).replanTask(task, true), text());
            assertEquals(expected, store.read(task).files);
            assertTrue(store.read(task).resolutions.isEmpty());
        }
        PrintStream stream = new PrintStream(output, true, StandardCharsets.UTF_8);
        var input = new BufferedReader(new StringReader("")) {
            @Override public String readLine() { fail("非交互不应读取输入"); return null; }
        };
        var facade = new CodingAgentCli(root, null, true, 12, input, stream, stream, null, acceptance());
        assertEquals(1, facade.replanTask(task, false));
        assertEquals(expected, store.read(task).files);
        assertTrue(store.read(task).resolutions.isEmpty());
        assertEquals("human-fixed", Files.readString(source()));
    }

    @Test void shouldRejectChangesDuringApprovalWithoutAdoptingThem() throws Exception {
        String task = pausedRead();
        Files.writeString(source(), "human-fixed version one");
        PrintStream stream = new PrintStream(output, true, StandardCharsets.UTF_8);
        var input = new BufferedReader(new StringReader("")) {
            @Override public String readLine() throws java.io.IOException {
                Files.writeString(source(), "human-fixed version two");
                return "replan " + task;
            }
        };
        var facade = new CodingAgentCli(root, null, true, 12, input, stream, stream, null, acceptance());
        assertEquals(1, facade.replanTask(task, true), text());
        assertTrue(new com.agent.task.TaskStore(root).read(task).resolutions.isEmpty());
        assertTrue(text().contains("确认期间现场版本变化"), text());
        assertEquals("human-fixed version two", Files.readString(source()));
    }

    @Test void shouldRequireSeparateAgreementForAcceptanceChanges() throws Exception {
        String task = pausedRead();
        Files.writeString(root.resolve("pom.xml"), "<project><!--human build edit--></project>");
        Files.writeString(source(), "human-fixed");
        assertEquals(1, cli(null, "replan " + task + "\n", 12).replanTask(task, true), text());
        assertTrue(new com.agent.task.TaskStore(root).read(task).resolutions.isEmpty());
        assertTrue(text().contains("须先 --reagree-acceptance"), text());
        assertEquals(1, cli(null, "approve " + task + "\n", 12).reagreeAcceptance(task, "mvn test", true), text());
        assertEquals(1, cli(null, "replan " + task + "\n", 12).replanTask(task, true), text());
        assertEquals(0, cli(null, "", 12).raiseTaskBudget(task, 3));
        var model = new FakeLLMClient(List.of(new LLMResponse("请验收", null, 1, 1)));
        assertEquals(0, cli(model, "", 12).resumeManagedTask(task, false), text());
        assertTrue(Files.readString(root.resolve("pom.xml")).contains("human build edit"));
    }

    @Test void shouldInvalidatePreviousSuccessAndRequireFreshFinalEvidence() throws Exception {
        Files.writeString(source(), "human-fixed original");
        var model = new FakeLLMClient(List.of(new LLMResponse("完成", null, 1, 1)));
        assertEquals(0, cli(model, "", 12).startManagedTask("保持正确", null), text());
        String task = id();
        var store = new com.agent.task.TaskStore(root);
        int checks = store.read(task).checks.size();
        Files.writeString(source(), "human-fixed new version");
        assertEquals(1, cli(null, "replan " + task + "\n", 12).replanTask(task, true), text());
        assertEquals(com.agent.task.TaskRecord.State.Paused, store.read(task).state);
        assertEquals(checks, store.read(task).checks.size());
        var resumed = new FakeLLMClient(List.of(new LLMResponse("新版本完成", null, 1, 1)));
        assertEquals(0, cli(resumed, "", 12).resumeManagedTask(task, false), text());
        assertEquals(checks + 2, store.read(task).checks.size());
        assertEquals(2, store.read(task).progress.requests);
    }

    @Test void shouldNotResetExhaustedBudgetOrResurrectAbandonedTask() throws Exception {
        String task = pausedRead();
        Files.writeString(source(), "human-fixed");
        assertEquals(1, cli(null, "replan " + task + "\n", 12).replanTask(task, true), text());
        var model = new FakeLLMClient(List.of());
        assertEquals(1, cli(model, "", 12).resumeManagedTask(task, false), text());
        assertEquals(0, model.callCount());
        assertEquals(1, new com.agent.task.TaskStore(root).read(task).progress.requests);
        assertEquals(1, cli(null, "abandon " + task + "\n", 12).abandonTask(task, true), text());
        assertEquals(4, cli(null, "replan " + task + "\n", 12).replanTask(task, true), text());
        assertEquals(com.agent.task.TaskRecord.State.Abandoned, new com.agent.task.TaskStore(root).read(task).state);
        assertEquals("human-fixed", Files.readString(source()));
    }

    @Test void shouldKeepRequiredTestIdentitiesForLegacyCheckpoint() throws Exception {
        String task = pausedRead();
        var store = new com.agent.task.TaskStore(root);
        var saved = store.read(task);
        saved.schemaVersion = 5;
        saved.checkpoint.baselineEvidenceId = null;
        store.save(saved);
        Files.writeString(source(), "human-fixed");
        assertEquals(1, cli(null, "replan " + task + "\n", 12).replanTask(task, true), text());
        CommandRunner droppedTest = (dir, command, timeout) -> {
            Path reports = Files.createDirectories(root.resolve("target/surefire-reports"));
            Files.writeString(reports.resolve("TEST-Other.xml"), "<testsuite tests=\"1\" failures=\"0\" errors=\"0\" skipped=\"0\">"
                    + "<testcase classname=\"Other\" name=\"unrelated\"/></testsuite>");
            return new CommandResult(0, false, "unrelated test passed", 1, CommandResult.OutputCompleteness.COMPLETE);
        };
        assertEquals(0, cli(null, "", 12).raiseTaskBudget(task, 3));
        var model = new FakeLLMClient(List.of(new LLMResponse("完成", null, 1, 1)));
        PrintStream stream = new PrintStream(output, true, StandardCharsets.UTF_8);
        var resumed = new CodingAgentCli(root, model, true, 12, new BufferedReader(new StringReader("")),
                stream, stream, null, droppedTest);
        assertEquals(1, resumed.resumeManagedTask(task, false), text());
        assertEquals(0, model.callCount());
        assertTrue(text().contains("缺少先前测试身份"), text());
    }

    @Test void shouldNotBypassUnknownAcceptanceThroughReplan() throws Exception {
        class PowerLoss extends Error { }
        CommandRunner interrupted = (dir, command, timeout) -> { throw new PowerLoss(); };
        PrintStream stream = new PrintStream(output, true, StandardCharsets.UTF_8);
        var facade = new CodingAgentCli(root, new FakeLLMClient(List.of()), true, 12, new BufferedReader(new StringReader("")),
                stream, stream, null, interrupted);
        assertThrows(PowerLoss.class, () -> facade.startManagedTask("修复", null));
        String task = id();
        Files.writeString(source(), "human-fixed");
        assertEquals(1, cli(null, "replan " + task + "\n", 12).replanTask(task, true), text());
        var saved = new com.agent.task.TaskStore(root).read(task);
        assertTrue(saved.checkpoint.acceptancePending);
        assertTrue(saved.resolutions.isEmpty());
        assertTrue(text().contains("不能用重新规划绕过"), text());
        assertEquals("human-fixed", Files.readString(source()));
    }

    @Test void shouldAcceptRecordedAgentWritesWithoutReplanning() throws Exception {
        var writer = new FakeLLMClient(List.of(new LLMResponse(null, List.of(new ToolCall("agent-write",
                new ToolCall.Function("write_file", "{\"path\":\"src/main/java/App.java\",\"content\":\"human-fixed by agent\"}"))), 1, 1)));
        assertEquals(1, cli(writer, "", 1).startManagedTask("修复", null), text());
        String task = id();
        assertEquals(0, cli(null, "", 12).raiseTaskBudget(task, 2));
        var resumed = new FakeLLMClient(List.of(new LLMResponse("完成", null, 1, 1)));
        assertEquals(0, cli(resumed, "", 12).resumeManagedTask(task, false), text());
        var saved = new com.agent.task.TaskStore(root).read(task);
        assertTrue(saved.resolutions.isEmpty());
        assertFalse(saved.progress.decisions.get(0).operations.get(0).changes.isEmpty());
    }

    @Test void shouldRetainCoreErrorCountsWhenHumanChangesDoNotFixFailure() throws Exception {
        var model = new FakeLLMClient(List.of(new LLMResponse("未解决", null, 1, 1),
                new LLMResponse("仍未解决", null, 1, 1), new LLMResponse("仍未解决", null, 1, 1)));
        assertEquals(1, cli(model, "", 12).startManagedTask("修复", null), text());
        String task = id();
        var store = new com.agent.task.TaskStore(root);
        var counts = java.util.Map.copyOf(store.read(task).progress.errors);
        Files.writeString(source(), "broken // unrelated human comment");
        assertEquals(1, cli(null, "replan " + task + "\n", 12).replanTask(task, true), text());
        assertEquals(counts, store.read(task).progress.errors);
        var resumed = new FakeLLMClient(List.of());
        assertEquals(1, cli(resumed, "", 12).resumeManagedTask(task, false), text());
        assertEquals(0, resumed.callCount());
        assertEquals(counts, store.read(task).progress.errors);
    }

    @Test void shouldKeepOldCheckpointWhenApprovalCannotBeSaved() throws Exception {
        String task = pausedRead();
        var store = new com.agent.task.TaskStore(root);
        var before = store.read(task).files;
        Files.writeString(source(), "human-fixed keep me");
        Path previous = root.resolve(".agent/tasks/" + task + "/state.previous.json");
        var input = new BufferedReader(new StringReader("")) {
            @Override public String readLine() throws java.io.IOException {
                Files.delete(previous);
                Files.createDirectory(previous);
                return "replan " + task;
            }
        };
        PrintStream stream = new PrintStream(output, true, StandardCharsets.UTF_8);
        var facade = new CodingAgentCli(root, null, true, 12, input, stream, stream, null, acceptance());
        assertEquals(4, facade.replanTask(task, true), text());
        Files.delete(previous);
        var saved = store.read(task);
        assertEquals(before, saved.files);
        assertTrue(saved.resolutions.isEmpty());
        assertNull(saved.checkpoint.replanContext);
        assertEquals("human-fixed keep me", Files.readString(source()));
    }
}
