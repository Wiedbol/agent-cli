package com.agent;

import com.agent.command.CommandResult;
import com.agent.command.CommandRunner;
import com.agent.llm.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.List;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

class CodingAgentCliExperienceTest {
    @Test void shouldPreserveGoalExampleInPersistedUnsentFeedback() throws Exception {
        String task = task(false);
        String block = "\n参考经验 11111111-1111-1111-1111-111111111111；来源任务 22222222-2222-2222-2222-222222222222；匹配原因：示例仅供参考，仍须遵守当前目标、守卫和独立验收。";
        var store = new com.agent.task.TaskStore(root);
        var saved = store.read(task);
        saved.goal = "保留以下原始示例" + block;
        saved.state = com.agent.task.TaskRecord.State.Paused;
        saved.progress = new com.agent.task.TaskRecord.Progress();
        saved.progress.maxRequests = 1;
        saved.checkpoint.messages.clear();
        saved.checkpoint.phase = com.agent.task.TaskRecord.Phase.CODING;
        saved.checkpoint.feedback = saved.goal + "\n已保存但尚未发送的反馈" + block;
        store.save(saved);
        var model = new FakeLLMClient(List.of(new LLMResponse("请验收", null, 1, 1)));
        assertEquals(0, cli(model, "").resumeManagedTask(task, false), text());
        String content = model.requests().get(0).stream().filter(m -> "user".equals(m.role())).findFirst().orElseThrow().content();
        assertTrue(content.startsWith(saved.goal));
        assertEquals(1, Pattern.compile("参考经验 ").matcher(content).results().count());
    }
    @Test void shouldRecheckAdviceWhileContinuingPendingToolsInSameResponse() throws Exception {
        String original = task(false);
        String experience = approve(original);
        Files.writeString(source(), "broken");
        output.reset();
        var model = new FakeLLMClient(List.of(new LLMResponse(null, List.of(
                new ToolCall("completed-read", new ToolCall.Function("read_file", "{\"path\":\"src/main/java/App.java\"}")),
                new ToolCall("pending-write", new ToolCall.Function("write_file", "{\"path\":\"src/main/java/App.java\",\"content\":\"fixed\"}"))), 1, 1)));
        var stream = new PrintStream(output, true, StandardCharsets.UTF_8);
        var blocked = new CodingAgentCli(root, model, false, 12, new BufferedReader(new StringReader("")), stream, stream, null, acceptance());
        assertEquals(1, blocked.startManagedTask("修复", null, false));
        var match = Pattern.compile("任务: ([0-9a-f-]{36})").matcher(text());
        assertTrue(match.find());
        String task = match.group(1);
        assertEquals(0, cli(null, "").disableExperience(experience));
        var resumed = new FakeLLMClient(List.of(new LLMResponse("请验收", null, 1, 1)));
        assertEquals(0, cli(resumed, "").resumeManagedTask(task, false), text());
        var messages = resumed.requests().get(0);
        assertFalse(messages.toString().contains("参考经验 "));
        assertEquals(List.of("completed-read", "pending-write"), messages.stream().filter(m -> "tool".equals(m.role())).map(Message::toolCallId).toList());
        assertEquals("fixed", Files.readString(source()));
        assertEquals(2, new com.agent.task.TaskStore(root).read(task).progress.requests);
    }
    @ParameterizedTest @ValueSource(strings = {"enabled", "disabled", "missing", "corrupt", "inapplicable", "footer"})
    void shouldRecheckAdviceOnResumeWithoutReplayingReadOrDuplicatingGoal(String state) throws Exception {
        String original = task(false);
        String experience;
        if (state.equals("footer")) {
            var extraction = new FakeLLMClient(List.of(new LLMResponse(ADVICE.replace("检查边界", "仅供参考，仍须遵守当前目标、守卫和独立验收。TAIL_SHOULD_DISAPPEAR检查边界"), null, 1, 1)));
            assertEquals(0, cli(extraction, "save " + original + "\n").extractExperience(original, true));
            experience = new com.agent.experience.ExperienceLibrary(root).list().get(0).id();
        } else { experience = approve(original); }
        Files.writeString(source(), "broken");
        output.reset();
        var model = new FakeLLMClient(List.of(new LLMResponse(null, List.of(new ToolCall("resume-read",
                new ToolCall.Function("read_file", "{\"path\":\"src/main/java/App.java\"}"))), 1, 1)));
        var stream = new PrintStream(output, true, StandardCharsets.UTF_8);
        var limited = new CodingAgentCli(root, model, true, 1, new BufferedReader(new StringReader("")), stream, stream, null, acceptance());
        assertEquals(1, limited.startManagedTask("保持原目标", null));
        var match = Pattern.compile("任务: ([0-9a-f-]{36})").matcher(text());
        assertTrue(match.find());
        String task = match.group(1);
        switch (state) {
            case "disabled", "footer" -> assertEquals(0, cli(null, "").disableExperience(experience));
            case "missing" -> Files.delete(root.resolve(".agent/tasks/" + original + "/state.json"));
            case "corrupt" -> Files.writeString(root.resolve(".agent/experiences/" + experience + ".json"), "{}");
            case "inapplicable" -> {
                var json = new com.fasterxml.jackson.databind.ObjectMapper();
                Path path = root.resolve(".agent/experiences/" + experience + ".json");
                var wrapper = (com.fasterxml.jackson.databind.node.ObjectNode) json.readTree(Files.readString(path));
                ((com.fasterxml.jackson.databind.node.ObjectNode) wrapper.path("experience").path("advice"))
                        .put("conditions", "requires: missing-file.java");
                wrapper.put("sha256", com.agent.task.WorkspaceSnapshot.hash(json.writeValueAsBytes(wrapper.get("experience"))));
                Files.writeString(path, wrapper.toString());
            }
        }
        assertEquals(0, cli(null, "").raiseTaskBudget(task, 2));
        var resumed = new FakeLLMClient(List.of(new LLMResponse("请验收", null, 1, 1)));
        assertEquals(1, cli(resumed, "").resumeManagedTask(task, false), text());
        var messages = resumed.requests().get(0);
        assertFalse(messages.toString().contains("TAIL_SHOULD_DISAPPEAR"));
        assertEquals(state.equals("enabled") ? 1 : 0,
                Pattern.compile("参考经验 " + experience).matcher(messages.toString()).results().count());
        assertEquals(1, messages.stream().filter(m -> m.content() != null && m.content().contains("保持原目标")).count());
        assertEquals(1, messages.stream().filter(m -> "resume-read".equals(m.toolCallId())).count());
        var saved = new com.agent.task.TaskStore(root).read(task);
        assertEquals(2, saved.progress.requests);
        assertEquals(1, saved.progress.tools);
        assertEquals(com.agent.task.TaskRecord.State.Paused, saved.state);
    }
    @Test void shouldUpdateAdviceForDistinctFailureWithoutRepeatingOldAdvice() throws Exception {
        String original = task(false);
        String first = approve(original);
        var extraction = new FakeLLMClient(List.of(new LLMResponse(
                ADVICE.replace("expected fixed", "second failure"), null, 1, 1)));
        assertEquals(0, cli(extraction, "save " + original + "\n").extractExperience(original, true));
        Files.writeString(source(), "broken");
        int[] checks = {0};
        CommandRunner runner = (dir, command, timeout) -> {
            var result = acceptance().run(dir, command, timeout);
            if (++checks[0] > 1) {
                Path report = root.resolve("target/surefire-reports/TEST-AppTest.xml");
                Files.writeString(report, Files.readString(report).replace("expected fixed", "second failure"));
            }
            return result;
        };
        var model = new FakeLLMClient(List.of(new LLMResponse("请验收", null, 1, 1), new LLMResponse("仍请验收", null, 1, 1)));
        var stream = new PrintStream(output, true, StandardCharsets.UTF_8);
        var facade = new CodingAgentCli(root, model, true, 2, new BufferedReader(new StringReader("")), stream, stream, null, runner);
        assertEquals(1, facade.startManagedTask("修复", null), text());
        assertEquals(2, model.callCount());
        String context = model.requests().get(1).toString();
        assertTrue(context.contains("second failure"));
        assertEquals(1, Pattern.compile("参考经验 " + first).matcher(context).results().count());
        assertEquals(2, Pattern.compile("参考经验 ").matcher(context).results().count());
        assertTrue(text().contains("状态: Paused"));
    }
    @Test void shouldNotTreatMissingFileMentionedInFailureAsSatisfiedPrerequisite() throws Exception {
        String original = task(false);
        String advice = ADVICE.replace("App计算边界错误", "requires: missing-module/Only.java");
        var extraction = new FakeLLMClient(List.of(new LLMResponse(advice, null, 1, 1)));
        assertEquals(0, cli(extraction, "save " + original + "\n").extractExperience(original, true));
        Files.writeString(source(), "broken");
        CommandRunner runner = (dir, command, timeout) -> {
            var result = acceptance().run(dir, command, timeout);
            Path report = root.resolve("target/surefire-reports/TEST-AppTest.xml");
            Files.writeString(report, Files.readString(report).replace("expected fixed", "expected fixed missing-module/Only.java"));
            return result;
        };
        var model = new FakeLLMClient(List.of(new LLMResponse("未修复", null, 1, 1)));
        var stream = new PrintStream(output, true, StandardCharsets.UTF_8);
        var facade = new CodingAgentCli(root, model, true, 1, new BufferedReader(new StringReader("")), stream, stream, null, runner);
        assertEquals(1, facade.startManagedTask("修复", null));
        assertFalse(model.requests().get(0).toString().contains("参考经验 "));
    }
    @Test void shouldLimitDeduplicateAndOrderAdviceDeterministically() throws Exception {
        String original = task(false);
        for (int i = 0; i < 5; i++) {
            var model = new FakeLLMClient(List.of(new LLMResponse(ADVICE.replace("检查边界", "方案" + i + "检查边界"), null, 1, 1)));
            assertEquals(0, cli(model, "save " + original + "\n").extractExperience(original, true));
        }
        var duplicate = new FakeLLMClient(List.of(new LLMResponse(ADVICE.replace("检查边界", "方案4检查边界"), null, 1, 1)));
        assertEquals(0, cli(duplicate, "save " + original + "\n").extractExperience(original, true));
        Files.writeString(source(), "broken");
        var first = new FakeLLMClient(List.of(new LLMResponse("未修复", null, 1, 1)));
        assertEquals(1, cli(first, "").startManagedTask("修复", null));
        String context = first.requests().get(0).toString();
        assertEquals(3, Pattern.compile("参考经验 ").matcher(context).results().count());
        var second = new FakeLLMClient(List.of(new LLMResponse("未修复", null, 1, 1)));
        assertEquals(1, cli(second, "").startManagedTask("修复", null));
        var ids1 = Pattern.compile("参考经验 ([0-9a-f-]{36})").matcher(context).results().map(m -> m.group(1)).toList();
        var ids2 = Pattern.compile("参考经验 ([0-9a-f-]{36})").matcher(second.requests().get(0).toString()).results().map(m -> m.group(1)).toList();
        assertEquals(ids1, ids2);
        assertEquals(ids1.stream().sorted().toList(), ids1);
    }

    @ParameterizedTest @ValueSource(strings = {"disabled", "missing-source", "corrupt", "unrelated", "prerequisite"})
    void shouldExcludeUnusableOrInapplicableAdvice(String reason) throws Exception {
        String original = task(false);
        String advice = reason.equals("unrelated") ? ADVICE.replace("AppTest#checks expected fixed", "NetworkTimeout database unavailable")
                : reason.equals("prerequisite") ? ADVICE.replace("App计算边界错误", "requires: missing-module/src/main/java/Only.java") : ADVICE;
        var extraction = new FakeLLMClient(List.of(new LLMResponse(advice, null, 1, 1)));
        assertEquals(0, cli(extraction, "save " + original + "\n").extractExperience(original, true));
        var library = new com.agent.experience.ExperienceLibrary(root);
        String experience = library.list().get(0).id();
        if (reason.equals("disabled")) { assertEquals(0, cli(null, "").disableExperience(experience)); }
        if (reason.equals("missing-source")) { Files.delete(root.resolve(".agent/tasks/" + original + "/state.json")); }
        if (reason.equals("corrupt")) { Files.writeString(root.resolve(".agent/experiences/" + experience + ".json"), "{}"); }
        Files.writeString(source(), "broken");
        var model = new FakeLLMClient(List.of(new LLMResponse("未修复", null, 1, 1)));
        assertEquals(1, cli(model, "").startManagedTask("修复", null), text());
        assertFalse(model.requests().get(0).toString().contains("参考经验 "));
    }

    @Test void shouldPersistOffChoiceAndNeverInjectOnResume() throws Exception {
        String original = task(false);
        approve(original);
        Files.writeString(source(), "broken");
        var stream = new PrintStream(output, true, StandardCharsets.UTF_8);
        var model = new FakeLLMClient(List.of(new LLMResponse(null, List.of(new ToolCall("read",
                new ToolCall.Function("read_file", "{\"path\":\"src/main/java/App.java\"}"))), 1, 1)));
        output.reset();
        var limited = new CodingAgentCli(root, model, true, 1, new BufferedReader(new StringReader("")), stream, stream, null, acceptance());
        assertEquals(1, limited.startManagedTask("修复", null, false, false));
        var match = Pattern.compile("任务: ([0-9a-f-]{36})").matcher(text());
        assertTrue(match.find());
        String task = match.group(1);
        assertFalse(new com.agent.task.TaskStore(root).read(task).experiencesEnabled);
        assertEquals(0, cli(null, "").raiseTaskBudget(task, 3));
        var resumed = new FakeLLMClient(List.of(new LLMResponse("未修复", null, 1, 1)));
        assertEquals(1, cli(resumed, "").resumeManagedTask(task, false));
        assertFalse(resumed.requests().get(0).toString().contains("参考经验 "));
    }
    @Test void shouldIncludeSourcedAdviceInNewFailureContext() throws Exception {
        String original = task(false);
        String experience = approve(original);
        Files.writeString(source(), "broken");
        var model = new FakeLLMClient(List.of(new LLMResponse("尚未修复", null, 1, 1)));
        assertEquals(1, cli(model, "").startManagedTask("修复计算", null), text());
        assertTrue(model.requests().get(0).stream().anyMatch(message -> message.content() != null
                && message.content().contains(experience) && message.content().contains(original)), text());
    }
    @TempDir Path root;
    private final ByteArrayOutputStream output = new ByteArrayOutputStream();
    private static final String ADVICE = "{\"conditions\":\"App计算边界错误\",\"errorFeatures\":\"AppTest#checks expected fixed\","
            + "\"recommendation\":\"检查边界分支并修复返回值，运行当前测试验证\",\"limitations\":\"不适用于构建或网络错误\"}";
    private String text() { return output.toString(StandardCharsets.UTF_8); }
    private Path source() { return root.resolve("src/main/java/App.java"); }
    private CodingAgentCli cli(LLMClient model, String input) {
        var stream = new PrintStream(output, true, StandardCharsets.UTF_8);
        return new CodingAgentCli(root, model, true, 12, new BufferedReader(new StringReader(input)),
                stream, stream, null, acceptance());
    }
    private CommandRunner acceptance() {
        return (dir, command, timeout) -> {
            boolean fixed = Files.readString(source()).equals("fixed");
            Path reports = Files.createDirectories(root.resolve("target/surefire-reports"));
            Files.writeString(reports.resolve("TEST-AppTest.xml"), "<testsuite tests=\"1\" failures=\""
                    + (fixed ? 0 : 1) + "\" errors=\"0\" skipped=\"0\"><testcase classname=\"AppTest\" name=\"checks\">"
                    + (fixed ? "" : "<failure message=\"expected fixed\"/>") + "</testcase></testsuite>");
            return new CommandResult(fixed ? 0 : 1, false, "fixture", 1, CommandResult.OutputCompleteness.COMPLETE);
        };
    }
    private String task(boolean initiallyFixed) throws Exception {
        Files.writeString(root.resolve("pom.xml"), "<project/>");
        Files.createDirectories(source().getParent());
        Files.writeString(source(), initiallyFixed ? "fixed" : "broken");
        var model = new FakeLLMClient(List.of(new LLMResponse(null, List.of(new ToolCall("repair",
                new ToolCall.Function("write_file", "{\"path\":\"src/main/java/App.java\",\"content\":\"fixed\"}"))), 1, 1),
                new LLMResponse("请验收", null, 1, 1)));
        assertEquals(0, cli(model, "").startManagedTask("修复计算", null), text());
        var match = Pattern.compile("任务: ([0-9a-f-]{36})").matcher(text());
        assertTrue(match.find(), text());
        return match.group(1);
    }

    @Test void shouldApproveSourcedExperienceAndManageItAfterRebuildingCli() throws Exception {
        String task = task(false);
        var model = new FakeLLMClient(List.of(new LLMResponse(ADVICE, null, 2, 3)));
        assertEquals(0, cli(model, "save " + task + "\n").extractExperience(task, true), text());
        assertEquals(1, model.callCount());
        assertTrue(model.requests().get(0).stream().anyMatch(m -> m.content().contains("expected fixed")));
        output.reset();
        assertEquals(0, cli(null, "").listExperiences(), text());
        var match = Pattern.compile("经验: ([0-9a-f-]{36})").matcher(text());
        assertTrue(match.find(), text());
        String experience = match.group(1);
        assertEquals(0, cli(null, "").showExperience(experience), text());
        assertTrue(text().contains(task), text());
        assertTrue(text().contains("repair"), text());
        assertTrue(text().contains("AppTest#checks"), text());
        assertEquals(0, cli(null, "").disableExperience(experience), text());
        output.reset();
        assertEquals(0, cli(null, "").showExperience(experience), text());
        assertTrue(text().contains("启用: false"), text());
        assertEquals(0, cli(null, "").showTask(task));
        assertTrue(text().contains("状态: Verified"), text());
    }

    @ParameterizedTest @ValueSource(strings = {"no-failure", "stale", "unverified", "missing-repair", "wrong-call", "wrong-failure", "corrupt-evidence"})
    void shouldRejectUnprovenSourceBeforeAskingModel(String invalid) throws Exception {
        String task = task(invalid.equals("no-failure"));
        var store = new com.agent.task.TaskStore(root);
        var record = store.read(task);
        switch (invalid) {
            case "stale" -> Files.writeString(source(), "human edit");
            case "unverified" -> { record.state = com.agent.task.TaskRecord.State.Paused; store.save(record); }
            case "missing-repair" -> { record.progress.decisions.get(0).operations.get(0).changes.clear(); store.save(record); }
            case "wrong-call" -> { record.progress.decisions.get(0).operations.get(0).callId = "nonexistent-call"; store.save(record); }
            case "wrong-failure" -> { record.progress.decisions.get(0).failureSource = record.checks.get(1).id(); store.save(record); }
            case "corrupt-evidence" -> Files.writeString(root.resolve(".agent/tasks/" + task + "/" + record.checks.get(0).id() + "/result.json"), "{}");
        }
        var model = new FakeLLMClient(List.of(new LLMResponse(ADVICE, null, 1, 1)));
        assertEquals(4, cli(model, "save " + task + "\n").extractExperience(task, true), text());
        assertEquals(0, model.callCount());
        assertFalse(Files.exists(root.resolve(".agent/experiences")));
    }

    @ParameterizedTest @ValueSource(strings = {"", "y\n", "save wrong-id\n"})
    void shouldKeepVerifiedTaskWhenExperienceIsDeclined(String answer) throws Exception {
        String task = task(false);
        var before = Files.readAllBytes(root.resolve(".agent/tasks/" + task + "/state.json"));
        var model = new FakeLLMClient(List.of(new LLMResponse(ADVICE, null, 1, 1)));
        assertEquals(1, cli(model, answer).extractExperience(task, true), text());
        assertArrayEquals(before, Files.readAllBytes(root.resolve(".agent/tasks/" + task + "/state.json")));
        assertFalse(Files.exists(root.resolve(".agent/experiences")));
    }

    @Test void shouldNeverReadInputOrAskModelForNoninteractiveExtraction() throws Exception {
        String task = task(false);
        var model = new FakeLLMClient(List.of());
        var input = new BufferedReader(new StringReader("")) {
            @Override public String readLine() { fail("非交互不能读取确认"); return null; }
        };
        var stream = new PrintStream(output, true, StandardCharsets.UTF_8);
        var facade = new CodingAgentCli(root, model, true, 12, input, stream, stream, null, acceptance());
        assertEquals(1, facade.extractExperience(task, false), text());
        assertEquals(0, model.callCount());
        assertFalse(Files.exists(root.resolve(".agent/experiences")));
        assertTrue(text().contains("--yes 不适用"), text());
    }

    @ParameterizedTest @ValueSource(strings = {"secret", "json-secret", "escaped-secret", "filtered", "blank", "forged-source", "malformed"})
    void shouldRejectUnsafeOrUnusableAdviceWithoutPersistingIt(String invalid) throws Exception {
        String task = task(false);
        String advice = switch (invalid) {
            case "secret" -> ADVICE.replace("检查边界分支并修复返回值，运行当前测试验证", "api_key=sk-1234567890123456789012345");
            case "json-secret" -> ((com.fasterxml.jackson.databind.node.ObjectNode) new com.fasterxml.jackson.databind.ObjectMapper().readTree(ADVICE))
                    .put("recommendation", "使用 {\"api_key\":\"plain-private-value\"}").toString();
            case "escaped-secret" -> ((com.fasterxml.jackson.databind.node.ObjectNode) new com.fasterxml.jackson.databind.ObjectMapper().readTree(ADVICE))
                    .put("recommendation", "使用 {\\\"api_key\\\":\\\"plain-private-value\\\"}").toString();
            case "filtered" -> ADVICE.replace("App计算边界错误", com.agent.session.Redactor.PLACEHOLDER);
            case "blank" -> ADVICE.replace("App计算边界错误", "");
            case "forged-source" -> ADVICE.replace("}", ",\"sourceTask\":\"pretend-source\"}");
            default -> "not json";
        };
        var model = new FakeLLMClient(List.of(new LLMResponse(advice, null, 1, 1)));
        assertEquals(4, cli(model, "save " + task + "\n").extractExperience(task, true), text());
        assertFalse(Files.exists(root.resolve(".agent/experiences")));
        assertEquals(com.agent.task.TaskRecord.State.Verified, new com.agent.task.TaskStore(root).read(task).state);
    }

    @Test void shouldRejectSourceChangesDuringConfirmationAndKeepHumanFiles() throws Exception {
        String task = task(false);
        var before = Files.readAllBytes(root.resolve(".agent/tasks/" + task + "/state.json"));
        var input = new BufferedReader(new StringReader("")) {
            @Override public String readLine() throws IOException {
                Files.writeString(source(), "human change during approval");
                return "save " + task;
            }
        };
        var stream = new PrintStream(output, true, StandardCharsets.UTF_8);
        var model = new FakeLLMClient(List.of(new LLMResponse(ADVICE, null, 1, 1)));
        var facade = new CodingAgentCli(root, model, true, 12, input, stream, stream, null, acceptance());
        assertEquals(4, facade.extractExperience(task, true), text());
        assertFalse(Files.exists(root.resolve(".agent/experiences")));
        assertArrayEquals(before, Files.readAllBytes(root.resolve(".agent/tasks/" + task + "/state.json")));
        assertEquals("human change during approval", Files.readString(source()));
    }

    private String approve(String task) {
        var model = new FakeLLMClient(List.of(new LLMResponse(ADVICE, null, 1, 1)));
        assertEquals(0, cli(model, "save " + task + "\n").extractExperience(task, true), text());
        var match = Pattern.compile("已保存经验: ([0-9a-f-]{36})").matcher(text());
        assertTrue(match.find(), text());
        return match.group(1);
    }

    @Test void shouldKeepHistoricalProvenanceWhileLaterWorkspaceChangesAndMarkMissingSource() throws Exception {
        String task = task(false);
        String experience = approve(task);
        Files.writeString(source(), "different task workspace");
        output.reset();
        assertEquals(0, cli(null, "").showExperience(experience));
        assertTrue(text().contains("来源可用: true"), text());
        Files.delete(root.resolve(".agent/tasks/" + task + "/state.json"));
        output.reset();
        assertEquals(0, cli(null, "").showExperience(experience));
        assertTrue(text().contains("来源可用: false"), text());
        assertEquals(0, cli(null, "").disableExperience(experience));
    }

    @Test void shouldRejectCorruptOrCopiedExperienceAndInvalidIdentifiers() throws Exception {
        String task = task(false);
        String experience = approve(task);
        Path record = root.resolve(".agent/experiences/" + experience + ".json");
        Path other = Files.createDirectories(root.resolve("other-project"));
        Path copy = Files.createDirectories(other.resolve(".agent/experiences")).resolve(record.getFileName());
        Files.copy(record, copy);
        var stream = new PrintStream(output, true, StandardCharsets.UTF_8);
        var copied = new CodingAgentCli(other, null, true, 12, new BufferedReader(new StringReader("")),
                stream, stream, null, acceptance());
        assertEquals(4, copied.showExperience(experience), text());
        Files.writeString(record, Files.readString(record).replace("检查边界", "伪造边界"));
        assertEquals(4, cli(null, "").showExperience(experience), text());
        assertEquals(4, cli(null, "").showExperience("../escape"));
        assertEquals(4, cli(null, "").disableExperience("bad-id"));
    }

    @Test void shouldNotSaveWhenStorageFailsAndLeaveTaskUntouched() throws Exception {
        String task = task(false);
        var before = Files.readAllBytes(root.resolve(".agent/tasks/" + task + "/state.json"));
        var input = new BufferedReader(new StringReader("")) {
            @Override public String readLine() throws IOException {
                Files.writeString(root.resolve(".agent/experiences"), "storage unavailable");
                return "save " + task;
            }
        };
        var stream = new PrintStream(output, true, StandardCharsets.UTF_8);
        var model = new FakeLLMClient(List.of(new LLMResponse(ADVICE, null, 1, 1)));
        var facade = new CodingAgentCli(root, model, true, 12, input, stream, stream, null, acceptance());
        assertEquals(4, facade.extractExperience(task, true), text());
        assertArrayEquals(before, Files.readAllBytes(root.resolve(".agent/tasks/" + task + "/state.json")));
        assertEquals("storage unavailable", Files.readString(root.resolve(".agent/experiences")));
    }

    @Test void shouldNotUseFailureFromPreviousAcceptanceAgreement() throws Exception {
        String task = task(false);
        assertEquals(1, cli(null, "approve " + task + "\n").reagreeAcceptance(task, "mvn test", true), text());
        var model = new FakeLLMClient(List.of(new LLMResponse("完成", null, 1, 1)));
        assertEquals(0, cli(model, "").resumeManagedTask(task, false), text());
        var extraction = new FakeLLMClient(List.of());
        assertEquals(4, cli(extraction, "save " + task + "\n").extractExperience(task, true), text());
        assertEquals(0, extraction.callCount());
        assertFalse(Files.exists(root.resolve(".agent/experiences")));
    }

    @Test void shouldAcceptRepairExecutedThroughRecordedCommand() throws Exception {
        Files.writeString(root.resolve("pom.xml"), "<project/>");
        Files.createDirectories(source().getParent());
        Files.writeString(source(), "broken");
        CommandRunner runner = (dir, command, timeout) -> {
            if (command.equals("fixture-repair")) {
                Files.writeString(source(), "fixed");
                return new CommandResult(0, false, "fixed source", 1, CommandResult.OutputCompleteness.COMPLETE);
            }
            return acceptance().run(dir, command, timeout);
        };
        var model = new FakeLLMClient(List.of(new LLMResponse(null, List.of(new ToolCall("command-repair",
                new ToolCall.Function("run_command", "{\"command\":\"fixture-repair\"}"))), 1, 1),
                new LLMResponse("请验收", null, 1, 1)));
        var stream = new PrintStream(output, true, StandardCharsets.UTF_8);
        var facade = new CodingAgentCli(root, model, true, 12, new BufferedReader(new StringReader("")),
                stream, stream, null, runner);
        assertEquals(0, facade.startManagedTask("修复", null), text());
        var match = Pattern.compile("任务: ([0-9a-f-]{36})").matcher(text());
        assertTrue(match.find());
        String task = match.group(1);
        approve(task);
        assertTrue(text().contains("command-repair"), text());
    }

    @Test void shouldNotTurnHumanOnlyRepairIntoRecordedAgentExperience() throws Exception {
        Files.writeString(root.resolve("pom.xml"), "<project/>");
        Files.createDirectories(source().getParent());
        Files.writeString(source(), "broken");
        var model = new FakeLLMClient(List.of(new LLMResponse(null, List.of(new ToolCall("read-only",
                new ToolCall.Function("read_file", "{\"path\":\"src/main/java/App.java\"}"))), 1, 1)));
        var stream = new PrintStream(output, true, StandardCharsets.UTF_8);
        var facade = new CodingAgentCli(root, model, true, 1, new BufferedReader(new StringReader("")),
                stream, stream, null, acceptance());
        assertEquals(1, facade.startManagedTask("修复", null), text());
        var match = Pattern.compile("任务: ([0-9a-f-]{36})").matcher(text());
        assertTrue(match.find());
        String task = match.group(1);
        Files.writeString(source(), "fixed");
        assertEquals(1, cli(null, "replan " + task + "\n").replanTask(task, true));
        assertEquals(0, cli(null, "").raiseTaskBudget(task, 3));
        var completion = new FakeLLMClient(List.of(new LLMResponse("人工已修好，请验收", null, 1, 1)));
        assertEquals(0, cli(completion, "").resumeManagedTask(task, false), text());
        var extraction = new FakeLLMClient(List.of());
        assertEquals(4, cli(extraction, "save " + task + "\n").extractExperience(task, true), text());
        assertEquals(0, extraction.callCount());
        assertFalse(Files.exists(root.resolve(".agent/experiences")));
    }
}
