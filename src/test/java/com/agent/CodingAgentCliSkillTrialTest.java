package com.agent;

import com.agent.command.CommandRunner;
import com.agent.llm.*;
import com.agent.task.WorkspaceSnapshot;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class CodingAgentCliSkillTrialTest extends SkillTrialFixture {
    @Test void shouldLoadOnlySelectedCandidateInNormalLoopAndAuditAfterRestartWithoutKey() throws Exception {
        prepare();
        var model = new FakeLLMClient(List.of(calls(call("load", "load_skill", Map.of("id", candidate, "version", version))),
                calls(call("accept", "declare_skill", Map.of("id", candidate, "version", version, "decision", "ACCEPT", "reason", "边界诊断适用")),
                        call("edit", "write_file", Map.of("path", SOURCE, "content", "dev-fixed"))),
                new LLMResponse("请独立验收", null, 7, 11)));
        assertEquals(0, cli(model, "").startSkillTrial(config(4)), text());
        String trial = id("技能试用");
        assertFalse(model.requests().get(0).toString().contains("SKILL_GUIDE_PRIVATE_MARKER"));
        assertTrue(model.requests().get(1).toString().contains("SKILL_GUIDE_PRIVATE_MARKER"));
        assertFalse(model.requests().toString().contains("OTHER_CANDIDATE_SECRET"));
        assertFalse(model.requests().toString().contains("参考经验 "));
        assertFalse(model.requests().toString().contains(otherCandidate));
        output.reset();
        assertEquals(0, cli(null, "").showSkillTrial(control, trial), text());
        for (String expected : List.of("Verified", "已加载: true", "ACCEPT", "试用请求: 3", "RESPONDED", candidate, version)) {
            assertTrue(text().contains(expected), text());
        }
        assertEquals("dev-fixed", Files.readString(root.resolve(SOURCE)));
        assertFalse(Files.exists(root.resolve(".agent/skills/candidates")));
    }

    @ParameterizedTest @ValueSource(strings = {"skip", "reject", "duplicate", "wrong-id", "wrong-version", "undeclared"})
    void shouldKeepSelectionLoadingAndDeclarationSeparate(String choice) throws Exception {
        prepare();
        var responses = new ArrayList<LLMResponse>();
        if (!choice.equals("skip")) {
            responses.add(calls(call("load", "load_skill", Map.of("id", choice.equals("wrong-id") ? otherCandidate : candidate,
                    "version", choice.equals("wrong-version") ? "0".repeat(64) : version))));
        }
        var edits = new ArrayList<ToolCall>();
        if (choice.equals("reject")) { edits.add(call("reject", "declare_skill", Map.of("id", candidate, "version", version,
                "decision", "REJECT", "reason", "当前不适用，继续独立修复"))); }
        if (choice.equals("duplicate")) { edits.add(call("again", "load_skill", Map.of("id", candidate, "version", version))); }
        edits.add(call("edit", "write_file", Map.of("path", SOURCE, "content", "dev-fixed")));
        if (choice.startsWith("wrong")) {
            responses.set(0, calls(responses.get(0).toolCalls().get(0), edits.get(0)));
        } else { responses.add(calls(edits.toArray(ToolCall[]::new))); }
        responses.add(new LLMResponse("请验收", null, 1, 1));
        var model = new FakeLLMClient(responses);
        assertEquals(0, cli(model, "").startSkillTrial(config(4)), text());
        String trial = id("技能试用");
        output.reset();
        assertEquals(0, cli(null, "").showSkillTrial(control, trial), text());
        boolean loaded = Set.of("reject", "duplicate", "undeclared").contains(choice);
        assertTrue(text().contains("已加载: " + loaded), text());
        assertTrue(text().contains("声明: " + (choice.equals("reject") ? "REJECT" : "null")), text());
        if (!loaded) { assertFalse(model.requests().toString().contains("SKILL_GUIDE_PRIVATE_MARKER")); }
        if (choice.equals("duplicate")) { assertTrue(text().contains("LOAD_DUPLICATE"), text()); }
        if (choice.startsWith("wrong")) { assertTrue(text().contains("LOAD_DENIED"), text()); }
    }

    @Test void shouldStopAtTaskBudgetAndPreventOrdinaryResumeOrExpansion() throws Exception {
        prepare();
        var model = new FakeLLMClient(List.of(calls(call("load", "load_skill", Map.of("id", candidate, "version", version)))));
        assertEquals(1, cli(model, "").startSkillTrial(config(1)), text());
        String trial = id("技能试用"), task = id("任务");
        assertEquals(1, model.callCount());
        output.reset();
        assertEquals(0, cli(null, "").showSkillTrial(control, trial), text());
        assertTrue(text().contains("已加载: true"), text());
        assertTrue(text().contains("指南已组装=false"), text());
        assertTrue(text().contains("试用请求: 1"), text());
        var never = new FakeLLMClient(List.of());
        assertEquals(4, cli(never, "").resumeManagedTask(task, false), text());
        assertEquals(4, cli(null, "").raiseTaskBudget(task, 5), text());
        assertEquals(0, never.callCount());
    }

    @ParameterizedTest @ValueSource(strings = {"failure", "interruption"})
    void shouldRetainFailedOrUnknownRequestWithoutReplay(String failure) throws Exception {
        prepare();
        LLMClient model = (messages, tools) -> {
            if (failure.equals("interruption")) { throw new AssertionError("simulated process interruption"); }
            throw new IOException("offline network failure");
        };
        if (failure.equals("interruption")) { assertThrows(AssertionError.class, () -> cli(model, "").startSkillTrial(config(2))); }
        else { assertEquals(1, cli(model, "").startSkillTrial(config(2)), text()); }
        String trial = id("技能试用");
        output.reset();
        assertEquals(0, cli(null, "").showSkillTrial(control, trial), text());
        assertTrue(text().contains(failure.equals("interruption") ? "UNKNOWN" : "FAILED"), text());
        assertTrue(text().contains("试用请求: 1"), text());
        assertTrue(text().contains("输入token=未知"), text());
        assertTrue(text().contains("已加载: false"), text());
    }

    @Test void shouldRejectExternalArchiveReadsOtherCommandsAndAcceptanceWritesInNormalLoop() throws Exception {
        prepare();
        String path = sourceArchive.resolve(".agent/skills/candidates/" + otherCandidate + ".json").toString();
        var model = new FakeLLMClient(List.of(calls(call("read", "read_file", Map.of("path", path)),
                call("list", "list_files", Map.of("path", sourceArchive.toString())),
                call("search", "search_code", Map.of("path", sourceArchive.toString(), "keyword", "SECRET")),
                call("command", "run_command", Map.of("command", "mvn help:effective-pom")),
                call("protected", "write_file", Map.of("path", TEST, "content", "tamper")),
                call("edit", "write_file", Map.of("path", SOURCE, "content", "dev-fixed"))),
                new LLMResponse("请验收", null, 1, 1)));
        assertEquals(0, cli(model, "").startSkillTrial(config(3)), text());
        assertFalse(model.requests().toString().contains("OTHER_CANDIDATE_SECRET"));
        assertEquals("frozen acceptance fixture", Files.readString(root.resolve(TEST)));
        var observations = model.requests().get(1).stream().filter(m -> "tool".equals(m.role())).toList();
        assertTrue(observations.stream().filter(m -> !m.toolCallId().equals("edit")).allMatch(m ->
                m.content().contains("拒绝") || m.content().contains("失败") || m.content().contains("SecurityException")), observations.toString());
    }

    @Test void shouldPauseBeforeToolsIfRealSourceChangesDuringRequest() throws Exception {
        prepare();
        LLMClient model = (messages, tools) -> {
            disableArchivedExperience();
            try { return calls(call("load", "load_skill", Map.of("id", candidate, "version", version)),
                    call("edit", "write_file", Map.of("path", SOURCE, "content", "dev-fixed"))); }
            catch (Exception failure) { throw new IOException(failure); }
        };
        assertEquals(1, cli(model, "").startSkillTrial(config(3)), text());
        String trial = id("技能试用");
        assertEquals("dev-broken", Files.readString(root.resolve(SOURCE)));
        output.reset();
        assertEquals(0, cli(null, "").showSkillTrial(control, trial), text());
        assertTrue(text().contains("已加载: false"), text());
        assertTrue(text().contains("试用请求: 1"), text());
    }
    private void disableArchivedExperience() throws IOException {
        Path file;
        try (var paths = Files.list(sourceArchive.resolve(".agent/experiences"))) { file = paths.findFirst().orElseThrow(); }
        var wrapper = (com.fasterxml.jackson.databind.node.ObjectNode) json.readTree(Files.readAllBytes(file));
        ((com.fasterxml.jackson.databind.node.ObjectNode) wrapper.path("experience")).put("enabled", false);
        wrapper.put("sha256", WorkspaceSnapshot.hash(json.writeValueAsBytes(wrapper.get("experience"))));
        Files.write(file, json.writeValueAsBytes(wrapper));
    }

    @ParameterizedTest @ValueSource(strings = {"completion", "acceptance"})
    void shouldPauseIfSourceBecomesInvalidAtFinalization(String boundary) throws Exception {
        prepare();
        var count = new java.util.concurrent.atomic.AtomicInteger();
        LLMClient model = (messages, tools) -> {
            if (count.incrementAndGet() == 1) {
                try { return calls(call("edit", "write_file", Map.of("path", SOURCE, "content", "dev-fixed"))); }
                catch (Exception failure) { throw new IOException(failure); }
            }
            if (boundary.equals("completion")) { disableArchivedExperience(); }
            return new LLMResponse("请验收", null, 1, 1);
        };
        var stream = new PrintStream(output, true, StandardCharsets.UTF_8);
        CommandRunner runner = (dir, command, timeout) -> {
            var result = commands().run(dir, command, timeout);
            if (boundary.equals("acceptance") && Files.readString(root.resolve(SOURCE)).equals("dev-fixed")) { disableArchivedExperience(); }
            return result;
        };
        var cli = new CodingAgentCli(root, model, true, 4, new BufferedReader(new StringReader("")), stream, stream, null, runner);
        assertEquals(1, cli.startSkillTrial(config(3)), text());
        String trial = id("技能试用");
        output.reset();
        assertEquals(0, cli(null, "").showSkillTrial(control, trial), text());
        assertTrue(text().contains("状态: PAUSED"), text());
        assertTrue(text().contains("技能试用前提失效"), text());
    }

    @Test void shouldAuditActualSerializedHttpRequestsWithoutHiddenGuideInjection() throws Exception {
        prepare();
        try (var server = new okhttp3.mockwebserver.MockWebServer()) {
            for (var response : List.of(calls(call("load", "load_skill", Map.of("id", candidate, "version", version))),
                    calls(call("edit", "write_file", Map.of("path", SOURCE, "content", "dev-fixed"))),
                    new LLMResponse("请验收", null, 1, 1))) {
                var message = json.createObjectNode().put("role", "assistant");
                if (response.content() != null) { message.put("content", response.content()); }
                if (response.hasToolCalls()) {
                    var array = message.putArray("tool_calls");
                    for (var call : response.toolCalls()) {
                        var item = array.addObject().put("id", call.id()).put("type", "function");
                        item.putObject("function").put("name", call.name()).put("arguments", call.arguments());
                    }
                }
                var body = json.createObjectNode(); body.putArray("choices").addObject().set("message", message);
                body.putObject("usage").put("prompt_tokens", 2).put("completion_tokens", 3);
                server.enqueue(new okhttp3.mockwebserver.MockResponse().setHeader("Content-Type", "application/json").setBody(body.toString()));
            }
            server.start();
            var model = new DeepSeekClient("offline-fixture-only", server.url("/chat/completions").toString(), "offline-model");
            assertEquals(0, cli(model, "").startSkillTrial(config(4)), text());
            String first = server.takeRequest(2, java.util.concurrent.TimeUnit.SECONDS).getBody().readUtf8();
            String second = server.takeRequest(2, java.util.concurrent.TimeUnit.SECONDS).getBody().readUtf8();
            String third = server.takeRequest(2, java.util.concurrent.TimeUnit.SECONDS).getBody().readUtf8();
            assertTrue(first.contains(candidate) && first.contains(version));
            assertFalse(first.contains("SKILL_GUIDE_PRIVATE_MARKER"));
            assertTrue(second.contains("SKILL_GUIDE_PRIVATE_MARKER") && third.contains("SKILL_GUIDE_PRIVATE_MARKER"));
            for (String request : List.of(first, second, third)) {
                assertFalse(request.contains(otherCandidate) || request.contains("OTHER_CANDIDATE_SECRET") || request.contains("参考经验 "));
            }
            String trial = id("技能试用");
            output.reset();
            assertEquals(0, cli(null, "").showSkillTrial(control, trial), text());
            assertTrue(text().contains("最终请求审计"), text());
            assertTrue(text().contains("SKILL_GUIDE_PRIVATE_MARKER"), text());
            assertTrue(text().contains("指南已组装=false") && text().contains("指南已组装=true"), text());
        }
    }

    @Test void shouldReturnCompleteLongGuideThroughToolWithoutTruncation() throws Exception {
        var guide = (com.fasterxml.jackson.databind.node.ObjectNode) json.readTree(GUIDE);
        guide.put("diagnosis", "SKILL_GUIDE_PRIVATE_MARKER" + "D".repeat(3500));
        guide.put("repair", "R".repeat(3500));
        guide.put("verification", "V".repeat(3500) + "END_COMPLETE_GUIDE");
        prepare(guide.toString());
        var model = new FakeLLMClient(List.of(calls(call("load", "load_skill", Map.of("id", candidate, "version", version)),
                call("edit", "write_file", Map.of("path", SOURCE, "content", "dev-fixed"))), new LLMResponse("请验收", null, 1, 1)));
        assertEquals(0, cli(model, "").startSkillTrial(config(3)), text());
        assertTrue(model.requests().get(1).toString().contains("END_COMPLETE_GUIDE"));
        assertFalse(model.requests().get(1).toString().contains("结果已截断"));
    }

    @Test void shouldKeepArchivedIdentityViewsReadOnly() throws Exception {
        prepare();
        var view = com.agent.task.TaskStore.readOnlyArchive(sourceArchive, root);
        var task = view.list().get(0);
        assertEquals(root.toString().replace('\\', '/'), task.project);
        assertThrows(IOException.class, view::lock);
        assertThrows(IOException.class, () -> view.save(task));
        assertThrows(IOException.class, () -> view.create("unauthorized", "mvn test"));
        var library = com.agent.experience.ExperienceLibrary.readOnlyArchive(sourceArchive, root);
        var experience = library.list().get(0);
        assertTrue(library.sourceValid(experience));
        assertThrows(IOException.class, () -> library.disable(experience.id()));
        assertThrows(IOException.class, () -> new com.agent.experience.ExperienceLibrary(sourceArchive).read(experience.id()));
    }

    @Test void shouldRejectFormalOrChangedInputsBeforeAnyRequest() throws Exception {
        prepare();
        var original = (com.fasterxml.jackson.databind.node.ObjectNode) json.readTree(Files.readAllBytes(control.resolve("current.json")));
        var formal = original.deepCopy(); formal.put("id", "formal-grammar"); formal.put("kind", "exclusion");
        var frozen = (com.fasterxml.jackson.databind.node.ObjectNode) json.readTree(Files.readAllBytes(catalog));
        ((com.fasterxml.jackson.databind.node.ArrayNode) frozen.path("cases")).add(formal);
        Files.write(catalog, json.writeValueAsBytes(frozen));
        Files.write(control.resolve("current.json"), json.writeValueAsBytes(formal));
        var never = new FakeLLMClient(List.of());
        assertEquals(4, cli(never, "").startSkillTrial(config(3)), text());
        Files.write(control.resolve("current.json"), json.writeValueAsBytes(original));
        Files.writeString(root.resolve(SOURCE), "unfrozen input");
        assertEquals(4, cli(never, "").startSkillTrial(config(3)), text());
        assertEquals(0, never.callCount());
        assertEquals(0, cli(null, "").listSkillTrials(control), text());
        assertTrue(text().contains("暂无技能试用"), text());
    }

    @Test void shouldDetectFinalMessageTamperingEvenIfLocalStateDigestIsRecomputed() throws Exception {
        prepare();
        var model = new FakeLLMClient(List.of(calls(call("edit", "write_file", Map.of("path", SOURCE, "content", "dev-fixed"))),
                new LLMResponse("请验收", null, 1, 1)));
        assertEquals(0, cli(model, "").startSkillTrial(config(3)), text());
        String trial = id("技能试用");
        Path file = control.resolve("skill-trials/" + trial + "/state.json");
        var wrapper = (com.fasterxml.jackson.databind.node.ObjectNode) json.readTree(Files.readAllBytes(file));
        var first = (com.fasterxml.jackson.databind.node.ObjectNode) wrapper.path("value").path("requests").get(0).path("messages").get(0);
        first.put("content", "FORGED FINAL REQUEST");
        wrapper.put("sha256", WorkspaceSnapshot.hash(json.writeValueAsBytes(wrapper.get("value"))));
        Files.write(file, json.writeValueAsBytes(wrapper));
        output.reset();
        assertEquals(4, cli(null, "").showSkillTrial(control, trial), text());
        assertTrue(text().contains("最终消息审计损坏"), text());
    }

    @Test void shouldStopBeforeToolsWhenRequiredResponseAuditCannotPersist() throws Exception {
        prepare();
        var count = new java.util.concurrent.atomic.AtomicInteger();
        LLMClient model = (messages, tools) -> {
            count.incrementAndGet();
            Path file = control.resolve("skill-trials/" + id("技能试用") + "/state.json");
            Files.delete(file); Files.createDirectory(file);
            try { return calls(call("load", "load_skill", Map.of("id", candidate, "version", version)),
                    call("edit", "write_file", Map.of("path", SOURCE, "content", "dev-fixed"))); }
            catch (Exception failure) { throw new IOException(failure); }
        };
        assertEquals(4, cli(model, "").startSkillTrial(config(3)), text());
        assertEquals(1, count.get());
        assertEquals("dev-broken", Files.readString(root.resolve(SOURCE)));
        assertFalse(text().contains("任务已保存状态: Verified"), text());
    }

    @Test void shouldInspectReservedRequestAfterRealProcessHaltWithoutFinally() throws Exception {
        prepare();
        var builder = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-Djava.io.tmpdir=" + base, "-cp", System.getProperty("surefire.test.class.path", System.getProperty("java.class.path")),
                getClass().getName(), root.toString(), control.toString(), sourceArchive.toString(), catalog.toString(), candidate, batch);
        builder.redirectErrorStream(true).redirectOutput(base.resolve("halt.log").toFile());
        var process = builder.start();
        try {
            assertTrue(process.waitFor(60, java.util.concurrent.TimeUnit.SECONDS));
            assertEquals(17, process.exitValue(), Files.readString(base.resolve("halt.log")));
        } finally { if (process.isAlive()) { process.destroyForcibly().waitFor(); } }
        output.reset();
        assertEquals(0, cli(null, "").listSkillTrials(control), text());
        String trial = id("技能试用");
        output.reset();
        assertEquals(0, cli(null, "").showSkillTrial(control, trial), text());
        assertTrue(text().contains("结果未知") && text().contains("RESERVED"), text());
        assertTrue(text().contains("试用请求: 1"), text());
        assertTrue(text().contains("输入token=未知"), text());
        Path incomplete = Files.createDirectories(control.resolve("skill-trials/" + trial + "/result-root"));
        Files.writeString(incomplete.resolve("partial.txt"), "archive copy interrupted before task evidence");
        output.reset();
        assertEquals(0, cli(null, "").showSkillTrial(control, trial), text());
        assertTrue(text().contains("结果未知") && text().contains("RESERVED"), text());
    }

    public static void main(String[] args) throws Exception {
        var fixture = new CodingAgentCliSkillTrialTest();
        fixture.root = Path.of(args[0]); fixture.control = Path.of(args[1]); fixture.sourceArchive = Path.of(args[2]);
        fixture.catalog = Path.of(args[3]); fixture.candidate = args[4]; fixture.batch = args[5];
        fixture.cli((messages, tools) -> { Runtime.getRuntime().halt(17); return null; }, "").startSkillTrial(fixture.config(2));
    }
}
