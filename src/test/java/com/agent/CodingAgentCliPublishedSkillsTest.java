package com.agent;

import com.agent.command.*;
import com.agent.llm.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class CodingAgentCliPublishedSkillsTest extends SkillPublicationFixture {
    String activeSource = SOURCE;
    String published, skill;
    String templateGuide = GUIDE;
    boolean infrastructureFailure;
    @Override void prepare() throws Exception { super.prepare(templateGuide); }
    @Override CommandRunner commands() {
        return (dir, command, timeout) -> {
            if (infrastructureFailure) { return new CommandResult(1, false, "offline infrastructure failure", 1, CommandResult.OutputCompleteness.COMPLETE); }
            boolean fixed = Set.of("fixed", "dev-fixed").contains(Files.readString(root.resolve(activeSource)));
            Path reports = Files.createDirectories(root.resolve("target/surefire-reports"));
            Files.writeString(reports.resolve("TEST-RuleTest.xml"), "<testsuite tests=\"1\" failures=\"" + (fixed ? 0 : 1)
                    + "\" errors=\"0\" skipped=\"0\"><testcase classname=\"RuleTest\" name=\"checks\">"
                    + (fixed ? "" : "<failure message=\"boundary calculation expected fixed\"/>") + "</testcase></testsuite>");
            return new CommandResult(fixed ? 0 : 1, false, "independent offline fixture", 1, CommandResult.OutputCompleteness.COMPLETE);
        };
    }
    void preparePublished() throws Exception {
        published = publish(preparePair());
        output.reset();
        assertEquals(0, cli(null, "").showSkillVersion(control, sourceArchive, published), text());
        skill = id("技能");
        nextCase(false);
        activeSource = "src/main/java/feature/NewRule.java";
        Files.createDirectories(root.resolve(activeSource).getParent());
        Files.move(root.resolve(SOURCE), root.resolve(activeSource));
        output.reset();
    }
    record Published(String skill, String version) { }
    Published publishAnother(String guide, String parent) throws Exception {
        activeSource = SOURCE;
        output.reset();
        assertEquals(0, cli(null, "").startSkillBatch(control, sourceArchive, 12), text());
        batch = id("技能批次");
        String experience = com.agent.experience.ExperienceLibrary.readOnlyArchive(sourceArchive, root).list().get(0).id();
        output.reset();
        assertEquals(0, cli(new FakeLLMClient(List.of(new LLMResponse(guide, null, 1, 1))), "").generateSkillCandidate(
                control, sourceArchive, batch, List.of(experience), parent, parent == null ? null : "离线反馈补充步骤"), text());
        candidate = id("技能候选");
        var hash = java.util.regex.Pattern.compile("内容摘要: ([a-f0-9]{64})").matcher(text()); assertTrue(hash.find(), text()); version = hash.group(1);
        nextCase(false); String related = trial(false);
        nextCase(true);
        for (var scenario : json.readTree(Files.readAllBytes(catalog)).path("cases")) {
            if (scenario.path("kind").asText().equals("exclusion")) { json.writeValue(control.resolve("current.json").toFile(), scenario); }
        }
        String counter = trial(true);
        String result = publish(selection(related, counter));
        output.reset(); assertEquals(0, cli(null, "").showSkillVersion(control, sourceArchive, result), text());
        return new Published(id("技能"), result);
    }
    void resetBusiness() throws Exception {
        nextCase(false); activeSource = "src/main/java/feature/NewRule.java";
        Files.createDirectories(root.resolve(activeSource).getParent()); Files.move(root.resolve(SOURCE), root.resolve(activeSource));
        output.reset();
    }
    CodingAgentCli cli(LLMClient model, int budget) {
        var stream = new java.io.PrintStream(output, true, java.nio.charset.StandardCharsets.UTF_8);
        return new CodingAgentCli(root, model, true, budget, new java.io.BufferedReader(new java.io.StringReader("")), stream, stream, null, commands());
    }
    @Test void shouldDiscoverPublishedVersionAcrossFilesLoadRepairAndInspectWithoutKey() throws Exception {
        preparePublished();
        var model = new FakeLLMClient(List.of(calls(call("load", "load_skill", Map.of("id", skill, "version", published))),
                calls(call("accept", "declare_skill", Map.of("id", skill, "version", published, "decision", "ACCEPT", "reason", "新的边界计算适用")),
                        call("edit", "write_file", Map.of("path", activeSource, "content", "dev-fixed"))),
                new LLMResponse("请独立验收", null, 7, 11)));
        assertEquals(0, cli(model, "").startSkillTask("修复不同模块的边界计算", null, control, sourceArchive, false), text());
        String task = id("任务");
        assertTrue(model.requests().get(0).toString().contains(skill));
        assertFalse(model.requests().get(0).toString().contains("SKILL_GUIDE_PRIVATE_MARKER"));
        assertTrue(model.requests().get(1).toString().contains("SKILL_GUIDE_PRIVATE_MARKER"));
        assertFalse(model.requests().toString().contains("参考经验 ") || model.requests().toString().contains("OTHER_CANDIDATE_SECRET"));
        output.reset();
        assertEquals(0, cli(null, "").showTask(task), text());
        for (String expected : List.of("状态: Verified", "固定技能", skill, published, version, "ACCEPT", "最终请求审计", "模型请求: 3/8", "final: PASSED")) {
            assertTrue(text().contains(expected), text());
        }
        assertEquals("dev-fixed", Files.readString(root.resolve(activeSource)));
    }
    @Test void shouldResumeFixedContentWithoutReloadingOrResettingBudget() throws Exception {
        preparePublished();
        var first = new FakeLLMClient(List.of(calls(call("load", "load_skill", Map.of("id", skill, "version", published)))));
        assertEquals(1, cli(first, 1).startSkillTask("修复新的边界计算", null, control, sourceArchive, false), text());
        String task = id("任务");
        assertEquals(0, cli(null, 4).raiseTaskBudget(task, 4), text());
        output.reset();
        var resumed = new FakeLLMClient(List.of(calls(call("reject", "declare_skill", Map.of("id", skill, "version", published,
                        "decision", "REJECT", "reason", "读取后决定独立修复")), call("edit", "write_file", Map.of("path", activeSource, "content", "dev-fixed"))),
                new LLMResponse("请验收", null, 7, 11)));
        assertEquals(0, cli(resumed, 4).resumeManagedTask(task), text());
        assertTrue(resumed.requests().get(0).toString().contains("SKILL_GUIDE_PRIVATE_MARKER"));
        assertTrue(text().contains("模型请求: 3/4") && text().contains("REJECT"), text());
        assertEquals(1, first.callCount()); assertEquals(2, resumed.callCount());
    }
    @ParameterizedTest @ValueSource(strings = {"no-match", "skip", "reject", "requires", "version"})
    void shouldKeepIndependentRepairAndEnforceHardConditions(String choice) throws Exception {
        if (choice.equals("requires")) { templateGuide = GUIDE.replace("业务边界失败", "业务边界失败\\nrequires: " + SOURCE); }
        if (choice.equals("version")) {
            String hash = com.agent.task.WorkspaceSnapshot.hash("<project/>".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            templateGuide = GUIDE.replace("业务边界失败", "业务边界失败 " + hash);
        }
        preparePublished();
        if (choice.equals("version")) { Files.writeString(root.resolve("pom.xml"), "<project>changed before task</project>"); }
        var responses = new ArrayList<LLMResponse>();
        if (choice.equals("reject")) {
            responses.add(calls(call("load", "load_skill", Map.of("id", skill, "version", published))));
            responses.add(calls(call("reject", "declare_skill", Map.of("id", skill, "version", published, "decision", "REJECT", "reason", "读取后不采用")),
                    call("edit", "write_file", Map.of("path", activeSource, "content", "dev-fixed"))));
        } else { responses.add(calls(call("edit", "write_file", Map.of("path", activeSource, "content", "dev-fixed")))); }
        responses.add(new LLMResponse("请验收", null, 1, 1));
        var model = new FakeLLMClient(responses);
        assertEquals(0, cli(model, "").startSkillTask(choice.equals("no-match") ? "修复语法解析" : "修复业务边界", null, control, sourceArchive, false), text());
        assertFalse(model.requests().toString().contains("参考经验 "));
        assertEquals(choice.equals("reject"), model.requests().toString().contains("SKILL_GUIDE_PRIVATE_MARKER"));
        if (Set.of("no-match", "requires", "version").contains(choice)) { assertTrue(text().contains("NO_MATCH"), text()); }
        if (choice.equals("skip")) { assertTrue(text().contains("固定技能: []") && text().contains("CATALOG"), text()); }
        if (choice.equals("reject")) { assertTrue(text().contains("REJECT"), text()); }
    }
    @Test void shouldAuditActualHttpPayloadAndNotResendFailedRequest() throws Exception {
        preparePublished();
        try (var server = new okhttp3.mockwebserver.MockWebServer()) {
            for (var response : List.of(calls(call("load", "load_skill", Map.of("id", skill, "version", published))),
                    calls(call("accept", "declare_skill", Map.of("id", skill, "version", published, "decision", "ACCEPT", "reason", "边界适用")),
                            call("edit", "write_file", Map.of("path", activeSource, "content", "dev-fixed"))), new LLMResponse("请验收", null, 2, 3))) {
                var message = json.createObjectNode().put("role", "assistant").put("content", response.content());
                if (response.hasToolCalls()) { message.set("tool_calls", json.valueToTree(response.toolCalls())); }
                var body = json.createObjectNode(); body.putArray("choices").addObject().set("message", message);
                body.putObject("usage").put("prompt_tokens", 2).put("completion_tokens", 3);
                server.enqueue(new okhttp3.mockwebserver.MockResponse().setHeader("Content-Type", "application/json").setBody(body.toString()));
            }
            server.start();
            var model = DeepSeekClient.singleAttempt("offline-fixture-only", server.url("/chat/completions").toString(), "offline-model");
            assertEquals(0, cli(model, "").startSkillTask("修复新的边界", null, control, sourceArchive, false), text());
            String first = server.takeRequest().getBody().readUtf8();
            String second = server.takeRequest().getBody().readUtf8();
            String third = server.takeRequest().getBody().readUtf8();
            assertTrue(first.contains(skill) && first.contains(published));
            assertFalse(first.contains("SKILL_GUIDE_PRIVATE_MARKER"));
            assertTrue(second.contains("SKILL_GUIDE_PRIVATE_MARKER") && third.contains("SKILL_GUIDE_PRIVATE_MARKER"));
            assertFalse((first + second + third).contains("OTHER_CANDIDATE_SECRET") || (first + second + third).contains("参考经验 "));
            Files.writeString(root.resolve(activeSource), "dev-broken");
            server.enqueue(new okhttp3.mockwebserver.MockResponse().setResponseCode(503).setHeader("Retry-After", "0").setBody("offline failure"));
            output.reset();
            assertEquals(1, cli(model, 1).startSkillTask("修复边界", null, control, sourceArchive, false), text());
            assertEquals(4, server.getRequestCount());
            assertTrue(text().contains("模型请求: 1/1") && text().contains("MODEL_FAILED") && text().contains("未知请求=1"), text());
        }
    }
    @Test void shouldBoundStableDirectoryDiscoverAfterFailureAndLoadAtMostThreeDistinctSkills() throws Exception {
        preparePublished();
        var available = new ArrayList<Published>(); available.add(new Published(skill, published));
        for (String name : List.of("A", "B", "C")) { available.add(publishAnother(GUIDE.replace("边界诊断", "边界诊断 " + name), null)); }
        available.sort(Comparator.comparing(Published::skill)); resetBusiness();
        var one = available.get(0); var two = available.get(1); var three = available.get(2); var four = available.get(3);
        var model = new FakeLLMClient(List.of(
                calls(call("one", "load_skill", Map.of("id", one.skill(), "version", one.version())), call("two", "load_skill", Map.of("id", two.skill(), "version", two.version()))),
                new LLMResponse("请验证当前修改", null, 1, 1),
                calls(call("three", "load_skill", Map.of("id", three.skill(), "version", three.version())), call("four", "load_skill", Map.of("id", four.skill(), "version", four.version())),
                        call("duplicate", "load_skill", Map.of("id", one.skill(), "version", one.version())), call("edit", "write_file", Map.of("path", activeSource, "content", "dev-fixed"))),
                new LLMResponse("请独立验收", null, 1, 1)));
        assertEquals(0, cli(model, "").startSkillTask("修复业务边界", null, control, sourceArchive, false), text());
        String first = model.requests().get(0).toString();
        assertTrue(first.indexOf(one.skill()) < first.indexOf(two.skill()) && first.indexOf(two.skill()) < first.indexOf(three.skill()));
        assertFalse(first.contains(four.skill()));
        assertTrue(model.requests().get(2).toString().contains(four.skill()));
        String pins = text().lines().filter(line -> line.startsWith("固定技能:")).reduce((a, b) -> b).orElseThrow();
        assertTrue(pins.contains(one.version()) && pins.contains(two.version()) && pins.contains(three.version()) && !pins.contains(four.version()), pins);
        assertTrue(text().contains("同任务最多三种技能") && text().contains("LOAD_DUPLICATE"), text());
        long bodies = model.requests().get(model.requests().size() - 1).stream().filter(m -> "tool".equals(m.role()) && m.content() != null && m.content().contains("SKILL_GUIDE_PRIVATE_MARKER")).count();
        assertEquals(3, bodies);
    }
    @Test void shouldRetainPausedV1AfterV2PublicationAndNeverFallBackFromTamperedDefault() throws Exception {
        preparePublished(); String oldSkill = skill, oldVersion = published;
        var paused = new FakeLLMClient(List.of(calls(call("load-v1", "load_skill", Map.of("id", oldSkill, "version", oldVersion)))));
        assertEquals(1, cli(paused, 1).startSkillTask("修复业务边界", null, control, sourceArchive, false), text());
        String task = id("任务"), business = activeSource;
        Path held = base.resolve("paused-root");
        assertTrue(held.startsWith(base.toRealPath()) && root.startsWith(base.toRealPath()));
        // 仅夹具暂存已停止的普通任务，让独立发布流程在原项目身份下准备真实 v2 试用。
        Files.move(root, held, StandardCopyOption.ATOMIC_MOVE); Files.createDirectory(root);
        Files.copy(sourceArchive.resolve(".repair-lab-workspace.json"), root.resolve(".repair-lab-workspace.json"));
        writeFiles(files("dev-broken", "修复新的边界问题"));
        var revised = publishAnother(GUIDE.replace("SKILL_GUIDE_PRIVATE_MARKER", "SKILL_GUIDE_V2_MARKER"), oldVersion);
        assertEquals(oldSkill, revised.skill());
        Files.move(root, base.resolve("v2-result"), StandardCopyOption.ATOMIC_MOVE);
        Files.move(held, root, StandardCopyOption.ATOMIC_MOVE); activeSource = business;
        assertEquals(0, cli(null, 4).raiseTaskBudget(task, 4), text()); output.reset();
        var resumed = new FakeLLMClient(List.of(calls(call("edit", "write_file", Map.of("path", activeSource, "content", "dev-fixed"))), new LLMResponse("请验收", null, 1, 1)));
        assertEquals(0, cli(resumed, 4).resumeManagedTask(task), text());
        assertTrue(resumed.requests().get(0).toString().contains("SKILL_GUIDE_PRIVATE_MARKER"));
        assertFalse(resumed.requests().toString().contains("SKILL_GUIDE_V2_MARKER"));
        Files.writeString(root.resolve(activeSource), "dev-broken"); output.reset();
        var fresh = new FakeLLMClient(List.of(calls(call("load-v2", "load_skill", Map.of("id", oldSkill, "version", revised.version()))),
                calls(call("edit", "write_file", Map.of("path", activeSource, "content", "dev-fixed"))), new LLMResponse("请验收", null, 1, 1)));
        assertEquals(0, cli(fresh, "").startSkillTask("修复业务边界", null, control, sourceArchive, false), text());
        assertTrue(fresh.requests().get(0).toString().contains(revised.version()));
        assertTrue(fresh.requests().get(1).toString().contains("SKILL_GUIDE_V2_MARKER"));
        Path file = sourceArchive.resolve(".agent/skills/versions/" + revised.version() + ".json");
        var wrapper = (com.fasterxml.jackson.databind.node.ObjectNode) json.readTree(Files.readAllBytes(file));
        ((com.fasterxml.jackson.databind.node.ObjectNode) wrapper.path("value")).put("approvedAt", 1);
        wrapper.put("sha256", com.agent.task.WorkspaceSnapshot.hash(json.writeValueAsBytes(wrapper.path("value"))));
        json.writeValue(file.toFile(), wrapper);
        Files.writeString(root.resolve(activeSource), "dev-broken"); output.reset();
        var independent = new FakeLLMClient(List.of(calls(call("edit", "write_file", Map.of("path", activeSource, "content", "dev-fixed"))), new LLMResponse("请验收", null, 1, 1)));
        assertEquals(0, cli(independent, "").startSkillTask("修复业务边界", null, control, sourceArchive, false), text());
        assertFalse(independent.requests().get(0).toString().contains(oldVersion), "invalid latest must not select old default");
        assertTrue(text().contains("默认版本未知，不回退旧版本"), text());
    }
    void plainProject() throws Exception {
        root = Files.createDirectory(base.resolve("plain")); writeFiles(files("dev-broken", "修复边界"));
    }
    String plainPaused() throws Exception {
        plainProject();
        assertEquals(1, cli(new FakeLLMClient(List.of(new LLMResponse("尚未修复", null, 1, 1))), 1)
                .startManagedTask("修复边界", null, false, com.agent.task.KnowledgeMode.SKILL), text());
        return id("任务");
    }
    @ParameterizedTest @ValueSource(strings = {"missing", "null", "pin-null", "events", "first", "request", "audit"})
    void shouldRejectMalformedSkillStateBeforeAnyRecoveryRequest(String change) throws Exception {
        String task = plainPaused();
        assertEquals(0, cli(null, 4).raiseTaskBudget(task, 4), text());
        Path file = root.resolve(".agent/tasks/" + task + "/state.json");
        var record = (com.fasterxml.jackson.databind.node.ObjectNode) json.readTree(Files.readAllBytes(file));
        var state = (com.fasterxml.jackson.databind.node.ObjectNode) record.path("skills");
        switch (change) {
            case "missing" -> record.remove("skills");
            case "null" -> record.putNull("skills");
            case "pin-null" -> ((com.fasterxml.jackson.databind.node.ArrayNode) state.path("loaded")).addNull();
            case "events" -> state.remove("events");
            case "first" -> state.remove("firstRequest");
            case "request" -> ((com.fasterxml.jackson.databind.node.ObjectNode) record.path("progress").path("decisions").get(0)).remove("skillRequest");
            case "audit" -> ((com.fasterxml.jackson.databind.node.ObjectNode) record.path("progress").path("decisions").get(0).path("skillRequest").path("payload").path("messages").get(0)).put("content", "forged final context");
            default -> throw new AssertionError(change);
        }
        record.remove("checkpointSha256"); record.put("checkpointSha256", com.agent.task.WorkspaceSnapshot.hash(json.writeValueAsBytes(record)));
        json.writeValue(file.toFile(), record); output.reset();
        var never = new FakeLLMClient(List.of());
        assertEquals(4, cli(never, 4).resumeManagedTask(task), text());
        assertEquals(0, never.callCount());
    }
    @ParameterizedTest @ValueSource(strings = {"candidate", "wrong-version", "duplicate", "undeclared"})
    void shouldRefuseUnauthorizedLoadsAndDistinguishMissingDeclaration(String choice) throws Exception {
        preparePublished();
        var operations = new ArrayList<ToolCall>();
        operations.add(call("load", "load_skill", Map.of("id", choice.equals("candidate") ? otherCandidate : skill,
                "version", choice.equals("wrong-version") ? otherCandidate : published)));
        if (choice.equals("duplicate")) { operations.add(call("again", "load_skill", Map.of("id", skill, "version", published))); }
        operations.add(call("outside", "read_file", Map.of("path", sourceArchive.resolve(".agent/skills/candidates/" + otherCandidate + ".md").toString())));
        operations.add(call("edit", "write_file", Map.of("path", activeSource, "content", "dev-fixed")));
        var model = new FakeLLMClient(List.of(new LLMResponse(null, operations, 1, 1), new LLMResponse("请验收", null, 1, 1)));
        assertEquals(0, cli(model, "").startSkillTask("修复业务边界", null, control, sourceArchive, false), text());
        assertFalse(model.requests().toString().contains("OTHER_CANDIDATE_SECRET"));
        if (choice.equals("candidate") || choice.equals("wrong-version")) { assertTrue(text().contains("LOAD_DENIED") && text().contains("固定技能: []"), text()); }
        else { assertTrue(text().contains("未声明"), text()); }
        if (choice.equals("duplicate")) {
            assertTrue(text().contains("LOAD_DUPLICATE"), text());
            assertEquals(1, model.requests().get(1).stream().filter(m -> "tool".equals(m.role()) && m.content() != null && m.content().contains("SKILL_GUIDE_PRIVATE_MARKER")).count());
        }
    }
    @ParameterizedTest @ValueSource(strings = {"failed", "unknown", "persistence"})
    void shouldReserveAuditBeforeClientAndRetainUnknownWithoutExecutingUnrecordedTools(String choice) throws Exception {
        plainProject();
        var count = new java.util.concurrent.atomic.AtomicInteger();
        var taskId = new java.util.concurrent.atomic.AtomicReference<String>();
        LLMClient model = (messages, tools) -> {
            count.incrementAndGet(); taskId.set(id("任务"));
            assertEquals(0, cli(null, 1).showTask(taskId.get()), text());
            assertTrue(text().contains("RESERVED") && text().contains("最终请求审计 1"), text());
            if (choice.equals("failed")) { throw new java.io.IOException("offline transport failed"); }
            if (choice.equals("unknown")) { throw new AssertionError("fixture stops after saved reservation"); }
            Path state = root.resolve(".agent/tasks/" + taskId.get() + "/state.json");
            Files.delete(state); Files.createDirectory(state);
            try { return calls(call("must-not-run", "write_file", Map.of("path", activeSource, "content", "dev-fixed"))); }
            catch (Exception failure) { throw new java.io.IOException(failure); }
        };
        if (choice.equals("unknown")) { assertThrows(AssertionError.class, () -> cli(model, 1).startManagedTask("修复边界", null, false, com.agent.task.KnowledgeMode.SKILL)); }
        else { assertEquals(choice.equals("persistence") ? 4 : 1, cli(model, 1).startManagedTask("修复边界", null, false, com.agent.task.KnowledgeMode.SKILL), text()); }
        assertEquals(1, count.get()); assertEquals("dev-broken", Files.readString(root.resolve(activeSource)));
        if (!choice.equals("persistence")) {
            output.reset(); assertEquals(0, cli(null, 1).showTask(taskId.get()), text());
            assertTrue(text().contains("模型请求: 1/1") && text().contains("未知请求=1"), text());
        }
        if (choice.equals("unknown")) {
            var never = new FakeLLMClient(List.of());
            assertEquals(1, cli(never, 1).resumeManagedTask(taskId.get()), text()); assertEquals(0, never.callCount());
        }
    }
    @ParameterizedTest @ValueSource(strings = {"passed", "unusable"})
    void shouldNotDiscoverWithoutUsableFailedBaseline(String condition) throws Exception {
        preparePublished();
        if (condition.equals("passed")) { Files.writeString(root.resolve(activeSource), "dev-fixed"); }
        else { infrastructureFailure = true; }
        var model = new FakeLLMClient(condition.equals("passed") ? List.of(new LLMResponse("请验收", null, 1, 1)) : List.of());
        assertEquals(condition.equals("passed") ? 0 : 1, cli(model, "").startSkillTask("修复业务边界", null, control, sourceArchive, false), text());
        assertEquals(condition.equals("passed") ? 1 : 0, model.callCount());
        assertFalse(text().contains("type=CATALOG"), text());
        assertFalse(model.requests().toString().contains(skill) || model.requests().toString().contains("SKILL_GUIDE_PRIVATE_MARKER"));
    }
    @Test void shouldRejectLostPinsEvenWhenOldGuideRemainsInReliableHistory() throws Exception {
        preparePublished();
        var load = new FakeLLMClient(List.of(calls(call("load", "load_skill", Map.of("id", skill, "version", published)))));
        assertEquals(1, cli(load, 1).startSkillTask("修复边界", null, control, sourceArchive, false), text());
        String task = id("任务"); assertEquals(0, cli(null, 4).raiseTaskBudget(task, 4), text());
        Path file = root.resolve(".agent/tasks/" + task + "/state.json");
        var record = (com.fasterxml.jackson.databind.node.ObjectNode) json.readTree(Files.readAllBytes(file));
        var state = (com.fasterxml.jackson.databind.node.ObjectNode) record.path("skills");
        state.putArray("offered"); state.putArray("loaded");
        record.remove("checkpointSha256"); record.put("checkpointSha256", com.agent.task.WorkspaceSnapshot.hash(json.writeValueAsBytes(record)));
        json.writeValue(file.toFile(), record);
        Files.writeString(sourceArchive.resolve(".agent/skills/versions/" + published + ".md"), "corrupt approved guide after losing references");
        output.reset();
        var never = new FakeLLMClient(List.of(calls(call("edit", "write_file", Map.of("path", activeSource, "content", "dev-fixed"))), new LLMResponse("请验收", null, 1, 1)));
        assertEquals(4, cli(never, 4).resumeManagedTask(task), text());
        assertEquals(0, never.callCount()); assertEquals("dev-broken", Files.readString(root.resolve(activeSource)));
    }
    @Test void shouldLoadNewlyPublishedSkillAfterLaterFailedAcceptanceFromInitiallyEmptyLibrary() throws Exception {
        var choice = preparePair(); attest(choice); resetBusiness();
        var round = new java.util.concurrent.atomic.AtomicInteger();
        var offered = new java.util.concurrent.atomic.AtomicReference<Published>();
        var registered = new java.util.concurrent.atomic.AtomicBoolean();
        LLMClient model = (messages, tools) -> {
            try {
                if (round.incrementAndGet() == 1) {
                    assertEquals(0, cli(null, "approve " + candidate + " " + version + "\n").approveSkill(choice, true), text());
                    String result = id("技能版本");
                    assertEquals(0, cli(null, "").showSkillVersion(control, sourceArchive, result), text());
                    offered.set(new Published(id("技能"), result));
                    return new LLMResponse("请先独立验收", null, 1, 1);
                }
                if (round.get() == 2) {
                    registered.set(tools.stream().anyMatch(t -> t.name().equals("load_skill")));
                    return calls(call("late", "load_skill", Map.of("id", offered.get().skill(), "version", offered.get().version())),
                            call("edit", "write_file", Map.of("path", activeSource, "content", "dev-fixed")));
                }
                return new LLMResponse("请验收", null, 1, 1);
            } catch (java.io.IOException failure) { throw failure; }
            catch (Exception failure) { throw new java.io.IOException(failure); }
        };
        assertEquals(0, cli(model, "").startSkillTask("修复业务边界", null, control, sourceArchive, false), text());
        assertTrue(registered.get(), "offered directory must have a registered loader");
        assertTrue(text().contains("type=LOAD,"), text());
    }
    @Test void shouldRepeatUncertainReadForSamePinWithoutDuplicatingReliableGuide() throws Exception {
        preparePublished();
        assertEquals(1, cli(new FakeLLMClient(List.of(calls(call("load", "load_skill", Map.of("id", skill, "version", published))))), 1)
                .startSkillTask("修复边界", null, control, sourceArchive, false), text());
        String task = id("任务"); assertEquals(0, cli(null, 4).raiseTaskBudget(task, 4), text());
        Path file = root.resolve(".agent/tasks/" + task + "/state.json");
        var record = (com.fasterxml.jackson.databind.node.ObjectNode) json.readTree(Files.readAllBytes(file));
        var decision = (com.fasterxml.jackson.databind.node.ObjectNode) record.path("progress").path("decisions").get(0);
        decision.put("roundFinished", false);
        var operation = (com.fasterxml.jackson.databind.node.ObjectNode) decision.path("operations").get(0);
        operation.put("status", "RUNNING"); operation.put("observation", "");
        var messages = (com.fasterxml.jackson.databind.node.ArrayNode) record.path("checkpoint").path("messages");
        messages.remove(messages.size() - 1);
        record.remove("checkpointSha256"); record.put("checkpointSha256", com.agent.task.WorkspaceSnapshot.hash(json.writeValueAsBytes(record)));
        json.writeValue(file.toFile(), record); output.reset();
        var resumed = new FakeLLMClient(List.of(calls(call("edit", "write_file", Map.of("path", activeSource, "content", "dev-fixed"))), new LLMResponse("请验收", null, 1, 1)));
        assertEquals(0, cli(resumed, 4).resumeManagedTask(task), text());
        assertEquals(1, resumed.requests().get(0).stream().filter(m -> "tool".equals(m.role()) && m.content() != null && m.content().contains("SKILL_GUIDE_PRIVATE_MARKER")).count());
        assertTrue(text().contains(published) && text().contains("模型请求: 3/4"), text());
    }
}
