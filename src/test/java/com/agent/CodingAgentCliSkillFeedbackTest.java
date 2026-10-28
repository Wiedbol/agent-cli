package com.agent;

import com.agent.llm.*;
import org.junit.jupiter.api.Test;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class CodingAgentCliSkillFeedbackTest extends SkillPublicationFixture {
    String usedVersion, usedSkill, usedFeedback;
    String acceptanceFailure;
    @Override com.agent.command.CommandRunner commands() {
        var delegate = super.commands();
        return (dir, command, timeout) -> {
            if (acceptanceFailure != null && Files.readString(root.resolve(SOURCE)).equals("dev-fixed")) {
                if (acceptanceFailure.equals("interrupt")) { throw new InterruptedException("fixture final acceptance interrupted"); }
                throw new java.io.IOException("fixture final acceptance unavailable");
            }
            return delegate.run(dir, command, timeout);
        };
    }
    void publishedUse() throws Exception {
        usedVersion = publish(preparePair());
        usedSkill = json.readTree(Files.readAllBytes(sourceArchive.resolve(".agent/skills/versions/" + usedVersion + ".json"))).path("value").path("skillId").asText();
        nextCase(false); output.reset();
        var model = new FakeLLMClient(List.of(calls(call("load", "load_skill", Map.of("id", usedSkill, "version", usedVersion))),
                calls(call("repair", "write_file", Map.of("path", SOURCE, "content", "dev-fixed"))), new LLMResponse("请独立验收", null, 7, 11)));
        assertEquals(0, cli(model, "").startSkillTask("修复业务边界", null, control, sourceArchive, false), text());
        output.reset(); assertEquals(0, cli(null, "").listSkillFeedback(null, null), text()); usedFeedback = id("技能反馈");
    }
    void plain() throws Exception {
        root = Files.createDirectory(base.resolve("plain"));
        writeFiles(files("broken", "修复业务边界"));
    }
    @Test void shouldRegisterOnlyConfirmedEligibleExperienceAfterVerifiedAndKeepQueriesReadOnly() throws Exception {
        plain();
        var repair = new FakeLLMClient(List.of(calls(call("repair", "write_file", Map.of("path", SOURCE, "content", "fixed"))),
                new LLMResponse("请独立验收", null, 1, 1)));
        assertEquals(0, cli(repair, "").startManagedTask("修复业务边界", null), text());
        String task = id("任务");
        var unconfirmed = new FakeLLMClient(List.of());
        assertEquals(1, cli(unconfirmed, "").extractExperience(task, false), text()); assertEquals(0, unconfirmed.callCount());
        output.reset(); assertEquals(0, cli(null, "").listSkillFeedback(null, null), text());
        assertTrue(text().contains("暂无"), text());
        var advice = new FakeLLMClient(List.of(new LLMResponse("""
                {"conditions":"业务边界","errorFeatures":"checks expected fixed","recommendation":"检查边界逻辑","limitations":"不适用于语法"}
                """, null, 1, 1)));
        assertEquals(0, cli(advice, "save " + task + "\n").extractExperience(task, true), text());
        String experience = id("已保存经验");
        output.reset(); assertEquals(0, cli(null, "").listSkillFeedback(null, null), text());
        assertTrue(text().contains("待提炼") && text().contains(experience) && text().contains(task), text());
        String feedback = id("技能反馈");
        Path file = root.resolve(".agent/skills/feedback/" + feedback + ".json");
        byte[] before = Files.readAllBytes(file);
        output.reset(); assertEquals(0, cli(null, "").listSkillFeedback(null, null), text());
        assertArrayEquals(before, Files.readAllBytes(file));
        try (var entries = Files.list(file.getParent())) { assertEquals(1, entries.count()); }
        assertEquals(1, advice.callCount());
    }
    @Test void shouldDeduplicateLoadedTaskFactsAcrossPauseResumeAndSeparateDeclarationFromOutcome() throws Exception {
        String published = publish(preparePair());
        var value = json.readTree(Files.readAllBytes(sourceArchive.resolve(".agent/skills/versions/" + published + ".json"))).path("value");
        String skill = value.path("skillId").asText(); nextCase(false); output.reset();
        var load = new FakeLLMClient(List.of(calls(call("load", "load_skill", Map.of("id", skill, "version", published)))));
        var stream = new java.io.PrintStream(output, true, java.nio.charset.StandardCharsets.UTF_8);
        var first = new CodingAgentCli(root, load, true, 1, new java.io.BufferedReader(new java.io.StringReader("")), stream, stream, null, commands());
        assertEquals(1, first.startSkillTask("修复业务边界", null, control, sourceArchive, false), text());
        String task = id("任务");
        output.reset(); assertEquals(0, cli(null, "").listSkillFeedback(null, null), text());
        assertTrue(text().contains("未声明") && text().contains(published) && text().contains(task), text());
        String item = id("技能反馈");
        output.reset(); assertEquals(0, cli(null, "").startSkillBatch(control, sourceArchive, 1), text());
        String proposalBatch = id("技能批次");
        var proposal = new FakeLLMClient(List.of(new LLMResponse(GUIDE.replace("SKILL_GUIDE_PRIVATE_MARKER", "FROZEN_PAUSED_FACTS"), null, 1, 1)));
        assertEquals(0, cli(proposal, "").reviseSkillFromFeedback(control, sourceArchive, proposalBatch, published, "保留暂停时观察", new com.agent.skill.SkillFeedback.Selection(List.of(item), null, catalog)), text());
        String frozenCandidate = id("技能候选");
        assertEquals(0, cli(null, "").raiseTaskBudget(task, 4), text());
        var finish = new FakeLLMClient(List.of(calls(call("reject", "declare_skill", Map.of("id", skill, "version", published,
                "decision", "REJECT", "reason", "选择独立修复")), call("repair", "write_file", Map.of("path", SOURCE, "content", "dev-fixed"))),
                new LLMResponse("请独立验收", null, 7, 11)));
        assertEquals(0, cli(finish, "").resumeManagedTask(task), text());
        output.reset(); assertEquals(0, cli(null, "").listSkillFeedback(null, null), text());
        assertTrue(text().contains("REJECT") && text().contains("Verified") && text().contains("PASSED") && text().contains("SUCCEEDED"), text());
        assertEquals(item, id("技能反馈"));
        Path file = root.resolve(".agent/skills/feedback/" + item + ".json"); byte[] before = Files.readAllBytes(file);
        var never = new FakeLLMClient(List.of());
        assertEquals(0, cli(never, "").resumeManagedTask(task), text());
        assertArrayEquals(before, Files.readAllBytes(file)); assertEquals(0, never.callCount());
        try (var entries = Files.list(file.getParent())) { assertEquals(1, entries.count()); }
        output.reset(); assertEquals(0, cli(null, "").showSkillCandidate(control, sourceArchive, frozenCandidate), text());
        assertTrue(text().contains("Paused") && text().contains("未声明") && text().contains("FROZEN_PAUSED_FACTS"), text());
    }
    @Test void shouldExplicitlyFreezeSelectedDevelopmentFeedbackAndProduceAnInactiveRevision() throws Exception {
        publishedUse();
        output.reset(); assertEquals(0, cli(null, "").startSkillBatch(control, sourceArchive, 1), text());
        String selectedBatch = id("技能批次");
        var model = new FakeLLMClient(List.of(new LLMResponse(GUIDE.replace("SKILL_GUIDE_PRIVATE_MARKER", "REVISED_FROM_SELECTED_FACTS"), null, 7, 11)));
        var selected = new com.agent.skill.SkillFeedback.Selection(List.of(usedFeedback), null, catalog);
        output.reset(); assertEquals(0, cli(model, "").reviseSkillFromFeedback(control, sourceArchive, selectedBatch, usedVersion, "根据未声明使用记录补充检查", selected), text());
        String revised = id("技能候选");
        assertTrue(model.requests().get(0).toString().contains(usedFeedback) && model.requests().get(0).toString().contains("未声明"));
        output.reset(); assertEquals(0, cli(null, "").showSkillCandidate(control, sourceArchive, revised), text());
        for (String part : List.of(usedFeedback, usedVersion, "内容差异", "REVISED_FROM_SELECTED_FACTS", "启用: false")) { assertTrue(text().contains(part), text()); }
        assertEquals(1, model.callCount());
        assertEquals(1, cli(model, "").reviseSkillFromFeedback(control, sourceArchive, selectedBatch, usedVersion, "再次请求", selected), text());
        assertEquals(1, model.callCount());
    }
    @Test void shouldCompleteFeedbackRevisionLoopOnlyAfterRequiredEarlierDevelopmentRegression() throws Exception {
        publishedUse();
        byte[] oldVersion = Files.readAllBytes(sourceArchive.resolve(".agent/skills/versions/" + usedVersion + ".json"));
        var use = json.readTree(Files.readAllBytes(root.resolve(".agent/skills/feedback/" + usedFeedback + ".json"))).path("value");
        String useTask = use.path("taskId").asText();
        var advice = new FakeLLMClient(List.of(new LLMResponse("""
                {"conditions":"业务边界","errorFeatures":"checks expected fixed","recommendation":"检查边界并独立验收","limitations":"不适用于语法"}
                """, null, 1, 1)));
        assertEquals(0, cli(advice, "save " + useTask + "\n").extractExperience(useTask, true), text());
        String sourceFeedback = null;
        try (var items = Files.list(root.resolve(".agent/skills/feedback"))) {
            for (var file : items.toList()) {
                var item = json.readTree(Files.readAllBytes(file)).path("value");
                if (item.path("kind").asText().equals("SOURCE")) { sourceFeedback = item.path("id").asText(); }
            }
        }
        assertNotNull(sourceFeedback);
        output.reset(); assertEquals(0, cli(null, "").startSkillBatch(control, sourceArchive, 1), text()); String generation = id("技能批次");
        var never = new FakeLLMClient(List.of());
        assertEquals(4, cli(never, "").reviseSkillFromFeedback(control, sourceArchive, generation, usedVersion, "Lab来源先归档",
                new com.agent.skill.SkillFeedback.Selection(List.of(sourceFeedback), null, catalog)), text());
        assertEquals(0, never.callCount());
        nextCase(false);
        Path feedbackArchive = null;
        try (var archives = Files.list(control.resolve("history"))) {
            for (var archive : archives.toList()) {
                if (Files.exists(archive.resolve("workspace/.agent/tasks/" + useTask + "/state.json"))) { feedbackArchive = archive.resolve("workspace"); }
            }
        }
        assertNotNull(feedbackArchive);
        var nextCatalog = (com.fasterxml.jackson.databind.node.ObjectNode) json.readTree(Files.readAllBytes(catalog));
        var feedbackFiles = files("dev-broken", "修复新的反馈边界");
        var feedbackCase = json.valueToTree(metadata("feedback-derived-points", "related", "修复新的反馈边界", feedbackFiles));
        ((com.fasterxml.jackson.databind.node.ArrayNode) nextCatalog.path("cases")).add(feedbackCase);
        ((com.fasterxml.jackson.databind.node.ArrayNode) nextCatalog.path("stages").path("feedback-development")).add("feedback-derived-points");
        catalog = base.resolve("catalog-v2.json"); json.writeValue(catalog.toFile(), nextCatalog);
        var model = new FakeLLMClient(List.of(new LLMResponse(GUIDE.replace("SKILL_GUIDE_PRIVATE_MARKER", "FEEDBACK_REVISION_GUIDE"), null, 7, 11)));
        var inputs = new com.agent.skill.SkillFeedback.Selection(List.of(usedFeedback, sourceFeedback), feedbackArchive, catalog);
        assertEquals(0, cli(model, "").reviseSkillFromFeedback(control, sourceArchive, generation, usedVersion, "根据开发反馈补充诊断", inputs), text());
        candidate = id("技能候选");
        var digest = java.util.regex.Pattern.compile("内容摘要: ([a-f0-9]{64})").matcher(text()); assertTrue(digest.find()); version = digest.group(1);
        output.reset(); assertEquals(0, cli(null, "").startSkillBatch(control, sourceArchive, 9), text()); batch = id("技能批次");
        nextCase(false); writeFiles(feedbackFiles); json.writeValue(control.resolve("current.json").toFile(), feedbackCase);
        String related = trial(false);
        nextCase(true);
        for (var scenario : nextCatalog.path("cases")) { if (scenario.path("kind").asText().equals("exclusion")) { json.writeValue(control.resolve("current.json").toFile(), scenario); } }
        String counter = trial(true);
        var missing = selection(related, counter);
        output.reset(); assertEquals(0, cli(null, "").reviewSkill(missing), text());
        assertTrue(text().contains("旧开发回归"), text());
        nextCase(false); String regression = trial(false);
        var choice = new com.agent.skill.SkillReviews.Selection(control, sourceArchive, candidate, version, related, counter, List.of(regression));
        String revised = publish(choice);
        assertNotEquals(usedVersion, revised);
        assertArrayEquals(oldVersion, Files.readAllBytes(sourceArchive.resolve(".agent/skills/versions/" + usedVersion + ".json")));
        output.reset(); assertEquals(0, cli(null, "").showSkillVersion(control, sourceArchive, revised), text());
        assertTrue(text().contains("FEEDBACK_REVISION_GUIDE") && text().contains(usedFeedback) && text().contains(regression), text());
    }
    @Test void shouldRejectInvalidOrFormalFeedbackBeforeReservationAndRetainNoRevisionFailedAndUnknownUsage() throws Exception {
        publishedUse();
        nextCase(false); output.reset();
        byte[] marker = Files.readAllBytes(root.resolve(".repair-lab-workspace.json"));
        var steps = new java.util.concurrent.atomic.AtomicInteger();
        LLMClient interruptedContext = (messages, tools) -> {
            if (steps.incrementAndGet() == 1) {
                try { return calls(call("load", "load_skill", Map.of("id", usedSkill, "version", usedVersion))); }
                catch (Exception failure) { throw new java.io.IOException(failure); }
            }
            Files.delete(root.resolve(".repair-lab-workspace.json"));
            return new LLMResponse("请独立验收", null, 7, 11);
        };
        try { assertEquals(1, cli(interruptedContext, "").startSkillTask("修复业务边界", null, control, sourceArchive, false), text()); }
        finally { Files.write(root.resolve(".repair-lab-workspace.json"), marker); }
        output.reset(); assertEquals(0, cli(null, "").listSkillFeedback(null, null), text()); usedFeedback = id("技能反馈");
        output.reset(); assertEquals(0, cli(null, "").startSkillBatch(control, sourceArchive, 3), text()); String revisions = id("技能批次");
        var never = new FakeLLMClient(List.of());
        assertEquals(4, cli(never, "").reviseSkillFromFeedback(control, sourceArchive, revisions, usedVersion, "不能混入未知场景",
                new com.agent.skill.SkillFeedback.Selection(List.of(usedFeedback), null, null)), text());
        var formal = (com.fasterxml.jackson.databind.node.ObjectNode) json.readTree(Files.readAllBytes(catalog));
        ((com.fasterxml.jackson.databind.node.ArrayNode) formal.path("stages").path("formal")).add("dev-points");
        Path formalCatalog = base.resolve("formal-catalog.json"); json.writeValue(formalCatalog.toFile(), formal);
        assertEquals(4, cli(never, "").reviseSkillFromFeedback(control, sourceArchive, revisions, usedVersion, "正式结果不能修订",
                new com.agent.skill.SkillFeedback.Selection(List.of(usedFeedback), null, formalCatalog)), text());
        assertEquals(0, never.callCount(), "移走并恢复标记不能把Lab历史降格为普通反馈");
        Path feedback = root.resolve(".agent/skills/feedback/" + usedFeedback + ".json"); byte[] original = Files.readAllBytes(feedback);
        var selection = new com.agent.skill.SkillFeedback.Selection(List.of(usedFeedback), null, catalog);
        for (String change : List.of("skill", "facts", "kind")) {
            var node = (com.fasterxml.jackson.databind.node.ObjectNode) json.readTree(original);
            var value = (com.fasterxml.jackson.databind.node.ObjectNode) node.path("value");
            if (change.equals("skill")) { ((com.fasterxml.jackson.databind.node.ObjectNode) value.path("version")).put("skillId", UUID.randomUUID().toString()); }
            else if (change.equals("facts")) { ((com.fasterxml.jackson.databind.node.ObjectNode) value.path("facts")).remove("requests"); }
            else { value.remove("kind"); }
            node.put("sha256", com.agent.task.WorkspaceSnapshot.hash(json.writeValueAsBytes(value))); json.writeValue(feedback.toFile(), node);
            try { assertEquals(4, cli(never, "").reviseSkillFromFeedback(control, sourceArchive, revisions, usedVersion, "坏引用必须停止", selection), text()); }
            finally { Files.write(feedback, original); }
        }
        assertEquals(0, never.callCount()); output.reset(); assertEquals(0, cli(null, "").showSkillBatch(control, sourceArchive, revisions), text());
        assertTrue(text().contains("累计请求: 0/3"), text());
        var count = new java.util.concurrent.atomic.AtomicInteger();
        LLMClient generator = (messages, tools) -> {
            int call = count.incrementAndGet();
            if (call == 1) { return new LLMResponse("{\"noRevision\":\"现有证据不足以改进\"}", null, 7, -1); }
            if (call == 2) { return new LLMResponse(GUIDE, null, 7, 11); }
            throw new java.io.IOException("offline transport failed");
        };
        assertEquals(1, cli(generator, "").reviseSkillFromFeedback(control, sourceArchive, revisions, usedVersion, "允许无修订", selection), text());
        assertEquals(1, cli(generator, "").reviseSkillFromFeedback(control, sourceArchive, revisions, usedVersion, "相同内容不强造版本", selection), text());
        assertEquals(4, cli(generator, "").reviseSkillFromFeedback(control, sourceArchive, revisions, usedVersion, "失败仍计数", selection), text());
        assertEquals(1, cli(never, "").reviseSkillFromFeedback(control, sourceArchive, revisions, usedVersion, "耗尽不重试", selection), text());
        assertEquals(3, count.get()); output.reset(); assertEquals(0, cli(null, "").showSkillBatch(control, sourceArchive, revisions), text());
        assertTrue(text().contains("NO_REVISION") && text().contains("FAILED") && text().contains("累计请求: 3/3"), text());
        output.reset(); assertEquals(0, cli(null, "").startSkillBatch(control, sourceArchive, 1), text()); String unknown = id("技能批次");
        LLMClient interrupted = (messages, tools) -> { throw new AssertionError("fixture stops after reservation"); };
        assertThrows(AssertionError.class, () -> cli(interrupted, "").reviseSkillFromFeedback(control, sourceArchive, unknown, usedVersion, "保留未知用量", selection));
        output.reset(); assertEquals(0, cli(null, "").showSkillBatch(control, sourceArchive, unknown), text());
        assertTrue(text().contains("RESERVED") && text().contains("输出用量未知请求: 1") && text().contains(usedFeedback), text());
    }
    @Test void shouldStopWhenFeedbackCannotPersistAndIdempotentlyRepairRegistrationWithoutModelOrAcceptance() throws Exception {
        plain(); var count = new java.util.concurrent.atomic.AtomicInteger();
        LLMClient model = (messages, tools) -> {
            if (count.incrementAndGet() == 1) {
                Files.createDirectories(root.resolve(".agent/skills")); Files.writeString(root.resolve(".agent/skills/feedback"), "block registration");
                try { return calls(call("repair", "write_file", Map.of("path", SOURCE, "content", "fixed"))); }
                catch (Exception failure) { throw new java.io.IOException(failure); }
            }
            return new LLMResponse("请独立验收", null, 7, 11);
        };
        assertEquals(4, cli(model, "").startManagedTask("修复边界", null, false, com.agent.task.KnowledgeMode.SKILL), text());
        String task = id("任务"); output.reset(); assertEquals(0, cli(null, "").showTask(task), text());
        assertTrue(text().contains("状态: Verified"), text()); assertEquals(2, count.get());
        Files.delete(root.resolve(".agent/skills/feedback"));
        var never = new FakeLLMClient(List.of()); assertEquals(0, cli(never, "").resumeManagedTask(task), text());
        output.reset(); assertEquals(0, cli(null, "").listSkillFeedback(null, null), text()); String item = id("技能反馈");
        byte[] before = Files.readAllBytes(root.resolve(".agent/skills/feedback/" + item + ".json"));
        assertEquals(0, cli(null, "").registerSkillFeedback(), text());
        assertArrayEquals(before, Files.readAllBytes(root.resolve(".agent/skills/feedback/" + item + ".json")));
        assertEquals(0, never.callCount());
    }
    @Test void shouldRefreshEndedFactsAfterExceptionalAcceptanceWithoutLosingOriginalFailure() throws Exception {
        publishedUse();
        for (String failure : List.of("io", "interrupt")) {
            nextCase(false); output.reset();
            var stream = new java.io.PrintStream(output, true, java.nio.charset.StandardCharsets.UTF_8);
            var load = new FakeLLMClient(List.of(calls(call("load", "load_skill", Map.of("id", usedSkill, "version", usedVersion)))));
            var start = new CodingAgentCli(root, load, true, 1, new java.io.BufferedReader(new java.io.StringReader("")), stream, stream, null, commands());
            assertEquals(1, start.startSkillTask("修复业务边界", null, control, sourceArchive, false), text()); String task = id("任务");
            output.reset(); assertEquals(0, cli(null, "").listSkillFeedback(null, null), text()); String item = id("技能反馈");
            assertEquals(0, cli(null, "").raiseTaskBudget(task, 4), text());
            var repair = new FakeLLMClient(List.of(calls(call("repair", "write_file", Map.of("path", SOURCE, "content", "dev-fixed"))),
                    new LLMResponse("请独立验收", null, 7, 11)));
            acceptanceFailure = failure;
            try { assertEquals(4, cli(repair, "").resumeManagedTask(task), text()); }
            finally { acceptanceFailure = null; Thread.interrupted(); }
            assertEquals(2, repair.callCount());
            output.reset(); assertEquals(0, cli(null, "").listSkillFeedback(null, null), text());
            assertEquals(item, id("技能反馈"));
            assertTrue(text().contains("fixture final acceptance") && text().contains("SUCCEEDED") && text().contains("Paused"), text());
            var facts = json.readTree(Files.readAllBytes(root.resolve(".agent/skills/feedback/" + item + ".json"))).path("value").path("facts");
            assertEquals(3, facts.path("requests").asInt()); assertEquals("dev-fixed", Files.readString(root.resolve(SOURCE)));
        }
    }
}
