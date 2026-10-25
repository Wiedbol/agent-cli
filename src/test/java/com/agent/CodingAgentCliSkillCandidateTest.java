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

class CodingAgentCliSkillCandidateTest {
    @TempDir Path root;
    private final ByteArrayOutputStream output = new ByteArrayOutputStream();
    private static final String GUIDE = """
            {"title":"边界修复流程","conditions":"相关边界计算失败","exclusions":"不适用于构建故障",
             "diagnosis":"定位失败断言与边界分支","repair":"修正业务分支，保留原验收",
             "verification":"运行当前独立测试，检查反例"}
            """;
    private String text() { return output.toString(StandardCharsets.UTF_8); }
    private Path source() { return root.resolve("src/main/java/App.java"); }
    private CodingAgentCli cli(LLMClient model, String input) {
        var stream = new PrintStream(output, true, StandardCharsets.UTF_8);
        return new CodingAgentCli(root, model, true, 4, new BufferedReader(new StringReader(input)),
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
    private String id(String label) {
        var match = Pattern.compile(label + ": ([0-9a-f-]{36})").matcher(text());
        assertTrue(match.find(), text());
        return match.group(1);
    }
    private String experience() throws Exception {
        Files.writeString(root.resolve("pom.xml"), "<project/>");
        Files.createDirectories(source().getParent());
        Files.writeString(source(), "broken");
        var repair = new FakeLLMClient(List.of(new LLMResponse(null, List.of(new ToolCall("repair",
                new ToolCall.Function("write_file", "{\"path\":\"src/main/java/App.java\",\"content\":\"fixed\"}"))), 1, 1),
                new LLMResponse("请验收", null, 1, 1)));
        output.reset();
        assertEquals(0, cli(repair, "").startManagedTask("修复计算", null), text());
        String task = id("任务");
        var advice = new FakeLLMClient(List.of(new LLMResponse("""
                {"conditions":"边界计算","errorFeatures":"AppTest checks expected fixed",
                 "recommendation":"检查边界并独立验收","limitations":"不适用于构建故障"}
                """, null, 1, 1)));
        assertEquals(0, cli(advice, "save " + task + "\n").extractExperience(task, true), text());
        return id("已保存经验");
    }
    private String batch(int limit) {
        output.reset();
        assertEquals(0, cli(null, "").startSkillBatch(limit), text());
        return id("技能批次");
    }

    @Test void shouldGenerateSourcedInactiveCandidateAndInspectWithoutKeyAfterRestart() throws Exception {
        String experience = experience();
        String batch = batch(2);
        var model = new FakeLLMClient(List.of(new LLMResponse(GUIDE, null, 7, 11)));
        assertEquals(0, cli(model, "").generateSkillCandidate(batch, List.of(experience), null, null), text());
        String candidate = id("技能候选");
        output.reset();
        assertEquals(0, cli(null, "").showSkillCandidate(candidate), text());
        assertTrue(text().contains("启用: false"), text());
        assertTrue(text().contains("迁移验证: 未验证"), text());
        for (String part : List.of("边界修复流程", "适用条件", "不适用情形", "诊断", "修复", "验证", experience, "来源任务")) {
            assertTrue(text().contains(part), text());
        }
        assertEquals(0, cli(null, "").showSkillBatch(batch), text());
        assertTrue(text().contains("累计请求: 1/2"), text());
        assertTrue(text().contains("已知输入 token: 7"), text());
        assertTrue(text().contains("已知输出 token: 11"), text());
        assertEquals(1, model.callCount());
        assertTrue(model.requests().get(0).toString().contains(experience));
        assertTrue(model.toolRequests().get(0).isEmpty());
    }

    @Test void shouldReserveSourceAndTargetBeforeSendingAndKeepFailedRequestAfterRestart() throws Exception {
        String experience = experience();
        String batch = batch(1);
        LLMClient failure = (messages, tools) -> {
            output.reset();
            assertEquals(0, cli(null, "").showSkillBatch(batch), text());
            assertTrue(text().contains("累计请求: 1/1"), text());
            assertTrue(text().contains(experience), text());
            assertTrue(text().contains("新增"), text());
            throw new IOException("offline connection failed");
        };
        assertEquals(4, cli(failure, "").generateSkillCandidate(batch, List.of(experience), null, null), text());
        output.reset();
        assertEquals(0, cli(null, "").showSkillBatch(batch), text());
        assertTrue(text().contains("FAILED"), text());
        assertTrue(text().contains("输入用量未知请求: 1"), text());
        var never = new FakeLLMClient(List.of());
        assertEquals(1, cli(never, "").generateSkillCandidate(batch, List.of(experience), null, null), text());
        assertEquals(0, never.callCount());
    }

    @Test void shouldRetainUnknownReservationAfterInterruptionWithoutAutomaticReplay() throws Exception {
        String experience = experience();
        String batch = batch(1);
        LLMClient interrupted = (messages, tools) -> { throw new AssertionError("simulated process interruption"); };
        assertThrows(AssertionError.class, () -> cli(interrupted, "").generateSkillCandidate(batch, List.of(experience), null, null));
        output.reset();
        assertEquals(0, cli(null, "").showSkillBatch(batch), text());
        assertTrue(text().contains("RESERVED"), text());
        assertTrue(text().contains("输入用量未知请求: 1"), text());
        var never = new FakeLLMClient(List.of());
        assertEquals(1, cli(never, "").generateSkillCandidate(batch, List.of(experience), null, null), text());
        assertEquals(0, never.callCount());
    }

    @ParameterizedTest @ValueSource(strings = {"invalid-json", "source", "tool", "empty", "secret", "number", "trailing"})
    void shouldChargeInvalidResponseWithoutSavingOrEnablingCandidate(String invalid) throws Exception {
        String experience = experience();
        String batch = batch(1);
        String content = switch (invalid) {
            case "invalid-json" -> "not json";
            case "source" -> GUIDE.replace("\"title\":", "\"sourceTask\":\"forged\",\"title\":");
            case "empty" -> GUIDE.replace("边界修复流程", "");
            case "secret" -> GUIDE.replace("边界修复流程", "api_key=should-not-be-saved");
            case "number" -> GUIDE.replace("\"边界修复流程\"", "7");
            case "trailing" -> GUIDE + " extra content";
            default -> GUIDE;
        };
        var model = new FakeLLMClient(List.of(new LLMResponse(content, invalid.equals("tool")
                ? List.of(new ToolCall("unsafe", new ToolCall.Function("write_file", "{}"))) : null, 7, -1)));
        assertEquals(4, cli(model, "").generateSkillCandidate(batch, List.of(experience), null, null), text());
        output.reset();
        assertEquals(0, cli(null, "").listSkillCandidates(), text());
        assertTrue(text().contains("暂无技能候选"), text());
        assertEquals(0, cli(null, "").showSkillBatch(batch), text());
        assertTrue(text().contains("累计请求: 1/1"), text());
        assertTrue(text().contains("已知输入 token: 7"), text());
        assertTrue(text().contains("输出用量未知请求: 1"), text());
        assertFalse(text().contains("should-not-be-saved"));
    }

    @ParameterizedTest @ValueSource(strings = {"disabled", "missing-task", "missing-evidence", "tampered-intent", "unconfirmed", "foreign-project"})
    void shouldRejectIneligibleExperienceBeforeRequest(String invalid) throws Exception {
        String experience = experience();
        Path file = root.resolve(".agent/experiences/" + experience + ".json");
        var json = new com.fasterxml.jackson.databind.ObjectMapper();
        var wrapper = (com.fasterxml.jackson.databind.node.ObjectNode) json.readTree(Files.readAllBytes(file));
        String task = wrapper.path("experience").path("sourceTask").asText();
        if (invalid.equals("disabled")) { assertEquals(0, cli(null, "").disableExperience(experience)); }
        else if (invalid.equals("missing-task")) { Files.delete(root.resolve(".agent/tasks/" + task + "/state.json")); }
        else if (invalid.equals("missing-evidence")) {
            String baseline = wrapper.path("experience").path("baseline").path("id").asText();
            Files.delete(root.resolve(".agent/tasks/" + task + "/" + baseline + "/result.json"));
        } else {
            var value = (com.fasterxml.jackson.databind.node.ObjectNode) wrapper.get("experience");
            if (invalid.equals("unconfirmed")) { value.put("confirmedAt", 0); }
            else if (invalid.equals("foreign-project")) { value.put("project", root.resolve("foreign").toString()); }
            else { ((com.fasterxml.jackson.databind.node.ObjectNode) value.path("repairs").get(0)).put("callId", "forged"); }
            wrapper.put("sha256", com.agent.task.WorkspaceSnapshot.hash(json.writeValueAsBytes(value)));
            Files.write(file, json.writeValueAsBytes(wrapper));
        }
        String batch = batch(1);
        var never = new FakeLLMClient(List.of());
        assertEquals(4, cli(never, "").generateSkillCandidate(batch, List.of(experience), null, null), text());
        assertEquals(0, never.callCount());
        assertEquals(0, cli(null, "").showSkillBatch(batch), text());
        assertTrue(text().contains("累计请求: 0/1"), text());
    }

    @ParameterizedTest @ValueSource(strings = {"markdown", "candidate", "batch", "source"})
    void shouldDetectTamperingWhenQueryingCandidateWithoutModel(String target) throws Exception {
        String experience = experience();
        String batch = batch(1);
        var model = new FakeLLMClient(List.of(new LLMResponse(GUIDE, null, 1, 1)));
        assertEquals(0, cli(model, "").generateSkillCandidate(batch, List.of(experience), null, null), text());
        String candidate = id("技能候选");
        if (target.equals("source")) { assertEquals(0, cli(null, "").disableExperience(experience)); }
        else {
            Path file = switch (target) {
                case "markdown" -> root.resolve(".agent/skills/candidates/" + candidate + ".md");
                case "candidate" -> root.resolve(".agent/skills/candidates/" + candidate + ".json");
                default -> root.resolve(".agent/skills/batches/" + batch + ".json");
            };
            Files.writeString(file, Files.readString(file).replace(target.equals("markdown") ? "诊断" : "\"schemaVersion\":1",
                    target.equals("markdown") ? "篡改" : "\"schemaVersion\":99"));
        }
        output.reset();
        assertEquals(4, cli(null, "").showSkillCandidate(candidate), text());
    }

    @Test void shouldKeepIdenticalSuggestionsSeparateAndRejectUnreviewedParent() throws Exception {
        String experience = experience();
        String batch = batch(3);
        var model = new FakeLLMClient(List.of(new LLMResponse(GUIDE, null, 1, 1), new LLMResponse(GUIDE, null, 1, 1),
                new LLMResponse(GUIDE.replace("边界修复流程", "修订边界流程"), null, 1, 1)));
        assertEquals(0, cli(model, "").generateSkillCandidate(batch, List.of(experience), null, null), text());
        String first = id("技能候选");
        output.reset();
        assertEquals(0, cli(model, "").generateSkillCandidate(batch, List.of(experience), null, null), text());
        assertNotEquals(first, id("技能候选"));
        // 旧占位版本没有真实审核记录，不能伪装成已发布父版本。
        var store = new com.agent.skill.SkillStore(root);
        var parentCandidate = store.candidate(first);
        String parent = java.util.UUID.randomUUID().toString();
        var json = new com.fasterxml.jackson.databind.ObjectMapper();
        var version = new com.agent.skill.SkillStore.Version(1, parent, parentCandidate.skillId(), parentCandidate.project(),
                first, com.agent.task.WorkspaceSnapshot.hash(json.writeValueAsBytes(parentCandidate)), System.currentTimeMillis(), true);
        var wrapper = json.createObjectNode();
        wrapper.set("value", json.valueToTree(version));
        wrapper.put("sha256", com.agent.task.WorkspaceSnapshot.hash(json.writeValueAsBytes(version)));
        Path file = root.resolve(".agent/skills/versions/" + parent + ".json");
        Files.createDirectories(file.getParent());
        Files.write(file, json.writeValueAsBytes(wrapper));
        output.reset();
        assertEquals(4, cli(model, "").generateSkillCandidate(batch, List.of(experience), parent, "补充边界限制"), text());
        assertTrue(text().contains("未完整批准"), text());
        assertEquals(2, model.callCount());
    }

    @Test void shouldKeepLedgerWhenCandidatePersistenceFailsAndRefuseToClaimSuccess() throws Exception {
        String experience = experience();
        String batch = batch(1);
        LLMClient diskFailure = (messages, tools) -> {
            Files.writeString(root.resolve(".agent/skills/candidates"), "blocked storage fixture");
            return new LLMResponse(GUIDE, null, 2, 3);
        };
        assertEquals(4, cli(diskFailure, "").generateSkillCandidate(batch, List.of(experience), null, null), text());
        output.reset();
        assertEquals(0, cli(null, "").showSkillBatch(batch), text());
        assertTrue(text().contains("累计请求: 1/1"), text());
        assertTrue(text().contains("FAILED"), text());
        assertFalse(text().contains("COMPLETED"));
    }

    @Test void shouldKeepReadOnlyQueriesFreeOfMetadataWrites() {
        assertEquals(0, cli(null, "").listSkillCandidates(), text());
        assertEquals(0, cli(null, "").listSkillBatches(), text());
        assertFalse(Files.exists(root.resolve(".agent")));
        assertEquals(4, cli(null, "").startSkillBatch(0), text());
        assertFalse(Files.exists(root.resolve(".agent")));
    }

    @Test void shouldRejectIncompleteBatchInsteadOfResettingConsumption() throws Exception {
        String batch = batch(2);
        Path file = root.resolve(".agent/skills/batches/" + batch + ".json");
        var json = new com.fasterxml.jackson.databind.ObjectMapper();
        var wrapper = (com.fasterxml.jackson.databind.node.ObjectNode) json.readTree(Files.readAllBytes(file));
        ((com.fasterxml.jackson.databind.node.ObjectNode) wrapper.get("value")).remove("requests");
        wrapper.put("sha256", com.agent.task.WorkspaceSnapshot.hash(json.writeValueAsBytes(wrapper.get("value"))));
        Files.write(file, json.writeValueAsBytes(wrapper));
        assertEquals(4, cli(null, "").showSkillBatch(batch), text());
    }

    @Test void shouldBindMultipleSourcesAndRejectSourceChangesDuringResponse() throws Exception {
        String first = experience();
        String second = experience();
        String batch = batch(2);
        var model = new FakeLLMClient(List.of(new LLMResponse(GUIDE, null, 1, 1)));
        assertEquals(0, cli(model, "").generateSkillCandidate(batch, List.of(second, first), null, null), text());
        String candidate = id("技能候选");
        output.reset();
        assertEquals(0, cli(null, "").showSkillCandidate(candidate), text());
        assertTrue(text().contains(first) && text().contains(second), text());
        LLMClient altered = (messages, tools) -> {
            Path file = root.resolve(".agent/experiences/" + first + ".json");
            var json = new com.fasterxml.jackson.databind.ObjectMapper();
            var wrapper = (com.fasterxml.jackson.databind.node.ObjectNode) json.readTree(Files.readAllBytes(file));
            ((com.fasterxml.jackson.databind.node.ObjectNode) wrapper.path("experience").path("advice")).put("conditions", "新条件");
            wrapper.put("sha256", com.agent.task.WorkspaceSnapshot.hash(json.writeValueAsBytes(wrapper.get("experience"))));
            Files.write(file, json.writeValueAsBytes(wrapper));
            return new LLMResponse(GUIDE, null, 1, 1);
        };
        output.reset();
        assertEquals(4, cli(altered, "").generateSkillCandidate(batch, List.of(first), null, null), text());
        assertTrue(text().contains("来源或父版本改变"), text());
        assertEquals(0, cli(null, "").showSkillBatch(batch), text());
        assertTrue(text().contains("累计请求: 2/2"), text());
    }

    @Test void shouldRejectMissingParentAndDuplicateSourcesWithoutSpendingBudget() throws Exception {
        String experience = experience();
        String batch = batch(2);
        var never = new FakeLLMClient(List.of());
        assertEquals(4, cli(never, "").generateSkillCandidate(batch, List.of(experience, experience), null, null), text());
        assertEquals(4, cli(never, "").generateSkillCandidate(batch, List.of(experience), java.util.UUID.randomUUID().toString(), "修订"), text());
        assertEquals(4, cli(never, "").generateSkillCandidate(batch, List.of(experience), null, "无父版本"), text());
        assertEquals(0, never.callCount());
        assertEquals(0, cli(null, "").showSkillBatch(batch), text());
        assertTrue(text().contains("累计请求: 0/2"), text());
    }

    @Test void shouldNeverResendHttpRequestUnderOneReservation() throws Exception {
        String experience = experience();
        String batch = batch(1);
        try (var server = new okhttp3.mockwebserver.MockWebServer()) {
            server.enqueue(new okhttp3.mockwebserver.MockResponse().setResponseCode(503).setHeader("Retry-After", "0"));
            server.enqueue(new okhttp3.mockwebserver.MockResponse().setResponseCode(503).setHeader("Retry-After", "0"));
            server.start();
            Path temporary = Files.createDirectories(root.resolve("process-tmp"));
            Path log = root.resolve("process.log");
            var builder = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                    "-Dagent.encoding=UTF-8", "-Djava.io.tmpdir=" + temporary,
                    "-Dagent.deepseek.endpoint=" + server.url("/chat/completions"),
                    "-cp", System.getProperty("surefire.test.class.path", System.getProperty("java.class.path")),
                    "com.agent.Main", "--root", root.toString(), "--generate-skill", experience, "--skill-batch", batch);
            builder.directory(root.toFile()).redirectErrorStream(true).redirectOutput(log.toFile());
            builder.environment().put("DEEPSEEK_API_KEY", "offline-fixture-only");
            var process = builder.start();
            try {
                assertTrue(process.waitFor(30, java.util.concurrent.TimeUnit.SECONDS));
                assertEquals(4, process.exitValue(), Files.readString(log));
            } finally { if (process.isAlive()) { process.destroyForcibly().waitFor(); } }
            assertEquals(1, server.getRequestCount(), "同一次持久化预留不能发送第二次 HTTP 请求");
        }
        output.reset();
        assertEquals(0, cli(null, "").showSkillBatch(batch), text());
        assertTrue(text().contains("累计请求: 1/1"), text());
        assertTrue(text().contains("输入用量未知请求: 1"), text());
    }
}
