package com.agent;

import com.agent.evaluation.EvaluationRunner;
import com.agent.llm.*;
import com.agent.skill.*;
import com.agent.task.*;
import com.agent.tool.*;
import com.fasterxml.jackson.databind.node.*;
import org.junit.jupiter.api.Test;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class CodingAgentCliSkillEvaluationTest extends SkillPublicationFixture {
    String v1, v2, skill;
    Path formalCatalog, inputs, archive;

    void approvedPair() throws Exception {
        v1 = publish(preparePair());
        skill = json.readTree(sourceArchive.resolve(".agent/skills/versions/" + v1 + ".json").toFile()).path("value").path("skillId").asText();
        nextCase(false); output.reset();
        var use = new FakeLLMClient(List.of(calls(call("load", "load_skill", Map.of("id", skill, "version", v1))),
                calls(call("fix", "write_file", Map.of("path", SOURCE, "content", "dev-fixed"))), new LLMResponse("请验收", null, 1, 1)));
        assertEquals(0, cli(use, "").startSkillTask("业务边界失败", null, control, sourceArchive, false), text());
        String task = id("任务"); output.reset();
        assertEquals(0, cli(null, "").listSkillFeedback(null, null), text()); String feedback = id("技能反馈");
        nextCase(false);
        Path feedbackRoot;
        try (var history = Files.list(control.resolve("history"))) {
            feedbackRoot = history.map(p -> p.resolve("workspace")).filter(p -> Files.exists(p.resolve(".agent/tasks/" + task + "/state.json"))).findFirst().orElseThrow();
        }
        output.reset(); assertEquals(0, cli(null, "").startSkillBatch(control, sourceArchive, 7), text()); batch = id("技能批次");
        var revision = new FakeLLMClient(List.of(new LLMResponse(GUIDE.replace("SKILL_GUIDE_PRIVATE_MARKER", "REVISION_PRIVATE_MARKER"), null, 5, 8)));
        output.reset(); assertEquals(0, cli(revision, "").reviseSkillFromFeedback(control, sourceArchive, batch, v1, "根据开发任务的实际修复细化诊断",
                new SkillFeedback.Selection(List.of(feedback), feedbackRoot, catalog)), text());
        candidate = id("技能候选");
        var digest = java.util.regex.Pattern.compile("内容摘要: ([a-f0-9]{64})").matcher(text()); assertTrue(digest.find()); version = digest.group(1);
        String related = trial(false); nextCase(true);
        var value = json.readTree(catalog.toFile());
        for (var item : value.path("cases")) { if (item.path("id").asText().equals("dev-whitespace")) { json.writeValue(control.resolve("current.json").toFile(), item); } }
        v2 = publish(selection(related, trial(true)));
        var formal = (ObjectNode) value.deepCopy();
        var formalIds = json.createArrayNode();
        var scenarios = new LinkedHashMap<String, EvaluationRunner.Scenario>();
        for (String name : List.of("formal-bulk", "formal-remaining", "formal-grammar")) {
            var business = files(name + "-broken", "业务边界失败：" + name);
            var manifest = (ObjectNode) json.valueToTree(metadata(name, name.equals("formal-grammar") ? "exclusion" : "related", "业务边界失败：" + name, business));
            if (name.equals("formal-grammar")) { manifest.set("exclusion", json.valueToTree(Map.of("wrongStep", "放宽零守卫", "rejectionEvidence", "拒绝边界放宽",
                    "normalRepairEvidence", "独立修复解析并保留零约束", "observableConstraint", "checks", "wrongFailures", List.of("checks")))); }
            ((ArrayNode) formal.path("cases")).add(manifest); formalIds.add(name);
            scenarios.put(name, new EvaluationRunner.Scenario("业务边界失败：" + name, business));
        }
        ((ObjectNode) formal.path("stages")).set("formal", formalIds);
        formalCatalog = base.resolve("formal-catalog.json"); json.writeValue(formalCatalog.toFile(), formal);
        inputs = base.resolve("initials.json"); json.writeValue(inputs.toFile(), scenarios);
        archive = base.resolve("formal-results"); output.reset();
    }
    SkillEvaluation.Config evaluation(int taskLimit, int batchLimit) {
        return new SkillEvaluation.Config(control, sourceArchive, formalCatalog, inputs, archive, v1, v2, "offline-model", "injected-client", taskLimit, batchLimit, 1, true);
    }
    FakeLLMClient repair(String published, boolean counter) throws Exception {
        return new FakeLLMClient(List.of(calls(call("load", "load_skill", Map.of("id", skill, "version", published))),
                calls(call("declare", "declare_skill", Map.of("id", skill, "version", published, "decision", counter ? "REJECT" : "ACCEPT", "reason", counter ? "解析原因，保留零守卫" : "业务边界适用")),
                        call("fix", "write_file", Map.of("path", SOURCE, "content", "dev-fixed"))),
                new LLMResponse("请独立验收", null, 7, 11)));
    }
    @Test void shouldRunSixIsolatedCellsThroughNormalLoadingAndFinalAudit() throws Exception {
        approvedPair();
        var seen = new ArrayList<FakeLLMClient>();
        assertEquals(0, cli(null, "").startSkillEvaluation(evaluation(3, 18), run -> {
            assertFalse(Files.exists(root.resolve(".agent")));
            assertFalse(Files.exists(root.resolve("prior-answer.txt")));
            assertFalse(Files.exists(root.resolve("target")));
            try {
                var arguments = json.createObjectNode().put("path", "../control").put("keyword", "PRIVATE_MARKER");
                assertThrows(SecurityException.class, () -> new ReadFileTool().execute(new ToolRequest(root, arguments)));
                assertThrows(SecurityException.class, () -> new ListFilesTool().execute(new ToolRequest(root, arguments)));
                assertThrows(SecurityException.class, () -> new SearchCodeTool().execute(new ToolRequest(root, arguments)));
                var model = repair(run.version().equals("v1") ? v1 : v2, run.scenario().equals("formal-grammar"));
                seen.add(model); return model;
            } catch (Exception failure) { throw new java.io.IOException(failure); }
        }), text());
        var report = SkillEvaluation.read(archive);
        assertEquals("SIMULATED", report.kind);
        assertEquals(6, report.cells.size()); assertEquals(18, report.attempts.size());
        assertEquals(6, seen.size()); assertFalse(report.preparation.isEmpty());
        for (int i = 0; i < 6; i++) {
            var cell = report.cells.get(i); var model = seen.get(i);
            assertEquals("Verified", cell.state, cell.reason);
            assertEquals(1, cell.repairRounds); assertEquals(3, cell.requests); assertEquals(1, cell.loads);
            assertEquals(report.cells.get(i - i % 2).initial, cell.initial);
            String selected = cell.run.version().equals("v1") ? v1 : v2;
            String other = cell.run.version().equals("v1") ? v2 : v1;
            String first = model.requests().get(0).toString();
            assertTrue(first.contains(selected)); assertFalse(first.contains(other));
            assertFalse(first.contains("SKILL_GUIDE_PRIVATE_MARKER") || first.contains("REVISION_PRIVATE_MARKER"));
            assertTrue(model.requests().get(1).toString().contains(cell.run.version().equals("v1") ? "SKILL_GUIDE_PRIVATE_MARKER" : "REVISION_PRIVATE_MARKER"));
            var tasks = TaskStore.readOnlyArchive(archive.resolve("run-" + cell.run.order()), root);
            var task = tasks.read(cell.taskId);
            assertEquals(selected, task.skills.loaded.get(0).versionId());
            for (int n = 0; n < 3; n++) {
                var packet = report.attempts.get(i * 3 + n).payload;
                assertEquals(packet, task.progress.decisions.get(n).skillRequest.payload());
            }
        }
        byte[] before = Files.readAllBytes(archive.resolve("report.json"));
        output.reset(); assertEquals(0, cli(null, "").showSkillEvaluation(archive), text());
        assertTrue(text().contains("SIMULATED") && text().contains("准备与开发") && text().contains("UNKNOWN"), text());
        assertArrayEquals(before, Files.readAllBytes(archive.resolve("report.json")));
        assertFalse(Files.exists(root.resolve(".agent")));
        boundariesAndRetainedFailures();
    }

    void boundariesAndRetainedFailures() throws Exception {
        Path originalInputs = inputs, originalCatalog = formalCatalog;
        Path checks = Files.createDirectory(base.resolve("boundary-" + UUID.randomUUID()));
        var initial = WorkspaceSnapshot.capture(root);
        archive = checks.resolve("invalid-output");
        var normal = evaluation(3, 18);
        var missing = new SkillEvaluation.Config(null, sourceArchive, formalCatalog, inputs, archive, v1, v2, "offline-model", "injected-client", 3, 18, 1, true);
        assertEquals(4, cli(null, "").startSkillEvaluation(missing, run -> { fail("invalid paths must not create a model"); return null; }));
        byte[] original = Files.readAllBytes(inputs);
        Files.writeString(inputs, "{}");
        assertEquals(4, cli(null, "").startSkillEvaluation(normal, run -> null));
        Files.write(inputs, original);
        var unsafe = new SkillEvaluation.Config(control, sourceArchive, formalCatalog, inputs, root.resolve("unsafe"), v1, v2, "offline-model", "injected-client", 3, 18, 1, true);
        assertEquals(4, cli(null, "").startSkillEvaluation(unsafe, run -> null));
        Path markdown = sourceArchive.resolve(".agent/skills/versions/" + v1 + ".md"); byte[] guide = Files.readAllBytes(markdown);
        Files.writeString(markdown, "forged approved content");
        assertEquals(4, cli(null, "").startSkillEvaluation(normal, run -> null));
        Files.write(markdown, guide);
        assertEquals(initial, WorkspaceSnapshot.capture(root)); assertFalse(Files.exists(archive));

        var catalogValue = (ObjectNode) json.readTree(formalCatalog.toFile());
        var scenarios = json.readTree(inputs.toFile());
        var bulk = (ObjectNode) scenarios.path("formal-bulk"); bulk.put("goal", "ZZZ"); ((ObjectNode) bulk.path("files")).put("GOAL.md", "ZZZ\n");
        for (var item : catalogValue.path("cases")) {
            if (item.path("id").asText().equals("formal-bulk")) {
                ((ObjectNode) item).put("goal", "ZZZ");
                ((ObjectNode) item.path("initial")).put("GOAL.md", WorkspaceSnapshot.hash("ZZZ\n".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
            }
        }
        inputs = checks.resolve("edge-inputs.json"); formalCatalog = checks.resolve("edge-catalog.json");
        json.writeValue(inputs.toFile(), scenarios); json.writeValue(formalCatalog.toFile(), catalogValue);
        archive = checks.resolve("retained-results");
        var factories = new java.util.concurrent.atomic.AtomicInteger(); var failures = new java.util.concurrent.atomic.AtomicInteger();
        assertEquals(1, cli(null, "").startSkillEvaluation(evaluation(1, 4), run -> {
            factories.incrementAndGet();
            if (run.order() == 3) { throw new java.io.IOException("factory failure retained"); }
            if (run.order() == 2) { return (messages, tools) -> { failures.incrementAndGet(); throw new java.io.IOException("transport failure, no resend"); }; }
            return new FakeLLMClient(List.of(new LLMResponse("请独立验收", null, run.order() == 1 ? -1 : 2, run.order() == 1 ? 2 : 3)));
        }));
        var retained = SkillEvaluation.read(archive);
        assertEquals(6, retained.cells.size()); assertEquals(4, retained.attempts.size()); assertEquals(5, factories.get()); assertEquals(1, failures.get());
        assertEquals("ERROR", retained.cells.get(2).state); assertTrue(retained.cells.get(2).reason.contains("factory failure"));
        assertEquals("Paused", retained.cells.get(5).state); assertTrue(retained.cells.get(5).reason.contains("总额度"));
        assertTrue(retained.cells.get(0).events.stream().anyMatch(e -> e.type().equals("NO_MATCH")));
        assertTrue(retained.cells.get(3).events.stream().anyMatch(e -> e.type().equals("CATALOG"))); assertEquals(0, retained.cells.get(3).loads);
        assertEquals("FAILED", retained.attempts.get(1).status); assertNull(retained.attempts.get(1).promptTokens);
        assertEquals(4, retained.formalCost.requests()); assertNull(retained.formalCost.promptTokens()); assertNull(retained.wholeProcessCost.requests());
        assertEquals(4, retained.formalCost.knownPromptTokens()); assertEquals(8, retained.formalCost.knownCompletionTokens());
        assertTrue(json.valueToTree(retained.formalCost).has("totalTokens"), "report must include total tokens and retain unknown");
        byte[] bytes = Files.readAllBytes(archive.resolve("report.json"));
        var forged = json.readTree(bytes); ((ObjectNode) forged.path("value").path("cells").get(0)).put("state", "Verified"); json.writeValue(archive.resolve("report.json").toFile(), forged);
        assertEquals(4, cli(null, "").showSkillEvaluation(archive)); Files.write(archive.resolve("report.json"), bytes);
        Path evidence = archive.resolve("run-1").resolve(SOURCE); byte[] actual = Files.readAllBytes(evidence);
        try {
            Files.writeString(evidence, "tampered archived business input");
            assertEquals(4, cli(null, "").showSkillEvaluation(archive), "changed raw evidence must not remain a valid report");
        } finally { Files.write(evidence, actual); }
        inputs = originalInputs; formalCatalog = originalCatalog;
        restrictedOperations(checks);
        counterexampleHistory(checks);
        externalDependencyMutation(checks);
        archive = base.resolve("packaged-results");
        json.writeValue(base.resolve("packaged-config.json").toFile(), evaluation(3, 18));
    }
    void counterexampleHistory(Path checks) throws Exception {
        Path originalCatalog = formalCatalog;
        var value = (ObjectNode) json.readTree(formalCatalog.toFile());
        for (var item : value.path("cases")) {
            if (item.path("id").asText().equals("formal-grammar")) {
                ((ObjectNode) item).set("tests", json.valueToTree(List.of("parse", "zero")));
                ((ObjectNode) item).set("expectedFailures", json.valueToTree(List.of("parse")));
                var criteria = (ObjectNode) item.path("exclusion"); criteria.put("observableConstraint", "zero");
                criteria.set("wrongFailures", json.valueToTree(List.of("parse", "zero")));
            }
        }
        formalCatalog = checks.resolve("counter-metrics-catalog.json"); json.writeValue(formalCatalog.toFile(), value);
        com.agent.command.CommandRunner acceptance = (dir, command, timeout) -> {
            String source = Files.readString(root.resolve(SOURCE));
            boolean parse = source.equals("bad-zero") || source.equals("dev-fixed"), zero = !source.equals("bad-zero");
            Path reports = Files.createDirectories(root.resolve("target/surefire-reports"));
            Files.writeString(reports.resolve("TEST-demo.BusinessRulesTest.xml"), "<testsuite tests=\"2\" failures=\"" + ((!parse ? 1 : 0) + (!zero ? 1 : 0))
                    + "\" errors=\"0\" skipped=\"0\"><testcase classname=\"demo.BusinessRulesTest\" name=\"parse\">" + (parse ? "" : "<failure message=\"grammar defect\"/>")
                    + "</testcase><testcase classname=\"demo.BusinessRulesTest\" name=\"zero\">" + (zero ? "" : "<failure message=\"zero constraint broken\"/>") + "</testcase></testsuite>");
            return new com.agent.command.CommandResult(parse && zero ? 0 : 1, false, "offline criteria fixture", 1, com.agent.command.CommandResult.OutputCompleteness.COMPLETE);
        };
        var stream = new java.io.PrintStream(output, true, java.nio.charset.StandardCharsets.UTF_8);
        var managed = new CodingAgentCli(root, null, true, 5, new java.io.BufferedReader(new java.io.StringReader("")), stream, stream, null, acceptance);
        var model = new FakeLLMClient(List.of(calls(call("load", "load_skill", Map.of("id", skill, "version", v1))),
                calls(call("reject", "declare_skill", Map.of("id", skill, "version", v1, "decision", "REJECT", "reason", "解析故障保留零约束"))), new LLMResponse("请验收", null, 1, 1)));
        archive = checks.resolve("unresolved-grammar");
        assertEquals(1, managed.startSkillEvaluation(evaluation(3, 3), run -> { if (run.order() != 5) { throw new java.io.IOException("skip fixture cell"); } return model; }));
        String unresolved = SkillEvaluation.read(archive).cells.get(4).misuse;
        var repaired = new FakeLLMClient(List.of(calls(call("load", "load_skill", Map.of("id", skill, "version", v1))),
                calls(call("reject", "declare_skill", Map.of("id", skill, "version", v1, "decision", "REJECT", "reason", "解析原因仍需检查零约束")),
                        call("bad", "write_file", Map.of("path", SOURCE, "content", "bad-zero"))), new LLMResponse("请验收", null, 1, 1),
                calls(call("repair", "write_file", Map.of("path", SOURCE, "content", "dev-fixed"))), new LLMResponse("请验收", null, 1, 1)));
        archive = checks.resolve("earlier-constraint-break");
        assertEquals(1, managed.startSkillEvaluation(evaluation(5, 5), run -> { if (run.order() != 5) { throw new java.io.IOException("skip fixture cell"); } return repaired; }));
        var corrected = SkillEvaluation.read(archive).cells.get(4);
        assertEquals("Verified", corrected.state); assertEquals(2, corrected.repairRounds);
        assertAll(() -> assertEquals("UNKNOWN", unresolved, "unresolved original grammar is not misuse"),
                () -> assertEquals("OBSERVED_CONSTRAINT_FAILURE", corrected.misuse, "later success cannot erase earlier zero failure"));
        formalCatalog = originalCatalog;
    }
    void restrictedOperations(Path checks) throws Exception {
        archive = checks.resolve("restricted-results");
        assertEquals(1, cli(null, "").startSkillEvaluation(evaluation(1, 1), run -> {
            try { return new FakeLLMClient(List.of(calls(call("other-version", "load_skill", Map.of("id", skill, "version", v2)),
                    call("alter-acceptance", "write_file", Map.of("path", TEST, "content", "forged acceptance")),
                    call("arbitrary-command", "run_command", Map.of("command", "echo forbidden")),
                    call("read-history", "read_file", Map.of("path", "../control/owner.json"))))); }
            catch (Exception failed) { throw new java.io.IOException(failed); }
        }));
        var report = SkillEvaluation.read(archive);
        assertEquals(1, report.attempts.size()); assertEquals(6, report.cells.size());
        var operations = report.cells.get(0).operations;
        assertTrue(operations.stream().anyMatch(op -> op.callId.equals("other-version") && op.status != TaskRecord.OperationState.SUCCEEDED));
        assertTrue(operations.stream().noneMatch(op -> op.status == TaskRecord.OperationState.SUCCEEDED));
        assertEquals("frozen acceptance fixture", Files.readString(archive.resolve("run-1").resolve(TEST)));
    }
    void externalDependencyMutation(Path checks) throws Exception {
        Path dependency = base.resolve("catalog.json");
        archive = checks.resolve("tamper-results"); byte[] current = Files.readAllBytes(dependency);
        try {
            assertEquals(1, cli(null, "").startSkillEvaluation(evaluation(3, 18), run -> (messages, tools) -> {
                var changed = (ObjectNode) json.readTree(current); changed.put("frozenMutation", true); json.writeValue(dependency.toFile(), changed);
                try { return calls(call("forbidden-after-tamper", "write_file", Map.of("path", SOURCE, "content", "must-not-execute"))); }
                catch (Exception failed) { throw new java.io.IOException(failed); }
            }));
            var blocked = SkillEvaluation.read(archive);
            assertEquals(1, blocked.attempts.size()); assertEquals(5, blocked.cells.stream().filter(c -> c.state.equals("BLOCKED")).count());
            assertTrue(blocked.cells.get(0).operations.isEmpty());
            assertTrue(blocked.cells.get(0).reason.contains("冻结"));
        } finally { Files.write(dependency, current); }
    }
}
