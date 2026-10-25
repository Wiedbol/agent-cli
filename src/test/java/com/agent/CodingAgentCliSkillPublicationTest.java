package com.agent;

import com.agent.llm.*;
import com.agent.skill.SkillReviews;
import org.junit.jupiter.api.Test;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class CodingAgentCliSkillPublicationTest extends SkillTrialFixture {
    private String trial(boolean exclusion) throws Exception {
        var model = new FakeLLMClient(List.of(calls(call("load", "load_skill", Map.of("id", candidate, "version", version))),
                calls(call("declare", "declare_skill", Map.of("id", candidate, "version", version,
                        "decision", exclusion ? "REJECT" : "ACCEPT", "reason", exclusion ? "解析故障不能放宽零数量守卫" : "边界适用")),
                        call("edit", "write_file", Map.of("path", SOURCE, "content", "dev-fixed"))),
                new LLMResponse("请验收", null, 7, 11)));
        output.reset();
        assertEquals(0, cli(model, "").startSkillTrial(config(3)), text());
        return id("技能试用");
    }
    private void nextCase(boolean exclusion) throws Exception {
        Path history = control.resolve("history/" + UUID.randomUUID());
        Files.createDirectories(history);
        Files.move(root, history.resolve("workspace"), StandardCopyOption.ATOMIC_MOVE);
        Files.createDirectory(root);
        Files.copy(sourceArchive.resolve(".repair-lab-workspace.json"), root.resolve(".repair-lab-workspace.json"));
        var files = files("dev-broken", exclusion ? "修复数量解析，不能放宽零约束" : "修复新的边界问题");
        writeFiles(files);
        var old = json.readTree(Files.readAllBytes(control.resolve("current.json")));
        json.writeValue(history.resolve("manifest.json").toFile(), old);
        var current = metadata(exclusion ? "dev-whitespace" : "dev-points", exclusion ? "exclusion" : "related",
                exclusion ? "修复数量解析，不能放宽零约束" : "修复新的边界问题", files);
        json.writeValue(control.resolve("current.json").toFile(), current);
    }
    private SkillReviews.Selection selection(String related, String counter) {
        return new SkillReviews.Selection(control, sourceArchive, candidate, version, related, counter);
    }
    private SkillReviews.Selection preparePair() throws Exception { return preparePair(true); }
    private SkillReviews.Selection preparePair(boolean loadRelated) throws Exception {
        prepare();
        var catalogValue = (com.fasterxml.jackson.databind.node.ObjectNode) json.readTree(Files.readAllBytes(catalog));
        var counter = metadata("dev-whitespace", "exclusion", "修复数量解析，不能放宽零约束", files("dev-broken", "修复数量解析，不能放宽零约束"));
        var counterNode = json.valueToTree(counter);
        ((com.fasterxml.jackson.databind.node.ObjectNode) counterNode).set("exclusion", json.valueToTree(Map.of(
                "wrongStep", "放宽零数量守卫", "rejectionEvidence", "明确拒绝边界放宽并说明解析原因",
                "normalRepairEvidence", "正常修复且零数量仍非法", "observableConstraint", "checks", "wrongFailures", List.of("checks"))));
        ((com.fasterxml.jackson.databind.node.ArrayNode) catalogValue.path("cases")).add(counterNode);
        ((com.fasterxml.jackson.databind.node.ArrayNode) catalogValue.path("stages").path("initial-development")).add("dev-whitespace");
        json.writeValue(catalog.toFile(), catalogValue);
        String related;
        if (loadRelated) { related = trial(false); }
        else {
            output.reset();
            var model = new FakeLLMClient(List.of(calls(call("independent", "write_file", Map.of("path", SOURCE, "content", "dev-fixed"))), new LLMResponse("请验收", null, 1, 1)));
            assertEquals(0, cli(model, "").startSkillTrial(config(3)), text());
            related = id("技能试用");
        }
        nextCase(true);
        json.writeValue(control.resolve("current.json").toFile(), counterNode);
        String exclusion = trial(true);
        return selection(related, exclusion);
    }
    @Test void shouldRequireHumanCounterexampleReviewBeforeExactPublication() throws Exception {
        var choice = preparePair();
        output.reset();
        assertEquals(4, cli(null, "approve " + candidate + " " + version + "\n").approveSkill(choice, true), text());
        assertTrue(text().contains("缺少反例人工核验"), text());
        output.reset();
        assertEquals(0, cli(null, "").reviewSkill(choice), text());
        assertTrue(text().contains("放宽零数量守卫") && text().contains("write_file") && text().contains("dev-fixed"), text());
        assertFalse(Files.exists(sourceArchive.resolve(".agent/skills/versions")));
    }
    private void attest(SkillReviews.Selection choice) throws Exception {
        output.reset();
        assertEquals(0, cli(null, "").reviewSkill(choice), text());
        var hashes = java.util.regex.Pattern.compile("证据摘要: ([a-f0-9]{64})").matcher(text()).results().map(m -> m.group(1)).toList();
        assertEquals(2, hashes.size());
        output.reset();
        String confirmation = "attest " + choice.counterexample() + " " + version + " " + hashes.get(1) + "\n";
        assertEquals(0, cli(null, confirmation).attestSkillTrial(control, choice.counterexample(), "已逐项检查声明与修改：未执行错误放宽，正常解析修复通过", true), text());
    }
    private String publish(SkillReviews.Selection choice) throws Exception {
        attest(choice);
        output.reset();
        assertEquals(0, cli(null, "approve " + candidate + " " + version + "\n").approveSkill(choice, true), text());
        return id("技能版本");
    }
    @Test void shouldPublishOnlyAfterAttestationAndKeepImmutableApprovalAfterRestart() throws Exception {
        var choice = preparePair();
        String published = publish(choice);
        output.reset();
        assertEquals(0, cli(null, "").showSkillVersion(control, sourceArchive, published), text());
        for (String expected : List.of("默认: true", "批准记录", candidate, version, "已逐项检查", "边界诊断")) {
            assertTrue(text().contains(expected), text());
        }
        assertEquals(0, cli(null, "").listSkillVersions(control, sourceArchive), text());
        byte[] before = Files.readAllBytes(sourceArchive.resolve(".agent/skills/versions/" + published + ".json"));
        output.reset();
        assertEquals(4, cli(null, "approve " + candidate + " " + version + "\n").approveSkill(choice, true), text());
        assertArrayEquals(before, Files.readAllBytes(sourceArchive.resolve(".agent/skills/versions/" + published + ".json")));
    }
    @Test void shouldRejectCandidateWithoutChangingVerifiedSource() throws Exception {
        prepare();
        var sourceTasks = com.agent.task.TaskStore.readOnlyArchive(sourceArchive, root);
        var task = sourceTasks.list().get(0);
        byte[] before = Files.readAllBytes(sourceArchive.resolve(".agent/tasks/" + task.id + "/state.json"));
        assertEquals(1, cli(null, "reject " + candidate + " " + version + "\n").rejectSkill(control, sourceArchive, candidate, version, "流程不够明确", false), text());
        assertEquals(0, cli(null, "reject " + candidate + " " + version + "\n").rejectSkill(control, sourceArchive, candidate, version, "流程不够明确", true), text());
        output.reset();
        assertEquals(0, cli(null, "").reviewSkill(selection(null, null)), text());
        assertTrue(text().contains("已拒绝") && text().contains("流程不够明确"), text());
        assertEquals(4, cli(null, "approve " + candidate + " " + version + "\n").approveSkill(selection(null, null), true), text());
        assertArrayEquals(before, Files.readAllBytes(sourceArchive.resolve(".agent/tasks/" + task.id + "/state.json")));
        assertEquals(com.agent.task.TaskRecord.State.Verified, sourceTasks.read(task.id).state);
    }
    @Test void shouldReviseApprovedParentAndPreserveV1WhenPublishingV2() throws Exception {
        var firstChoice = preparePair();
        String firstCandidate = candidate;
        String firstVersion = publish(firstChoice);
        Path firstFile = sourceArchive.resolve(".agent/skills/versions/" + firstVersion + ".json");
        byte[] firstBytes = Files.readAllBytes(firstFile);
        output.reset();
        assertEquals(0, cli(null, "").startSkillBatch(control, sourceArchive, 12), text());
        batch = id("技能批次");
        String experience = com.agent.experience.ExperienceLibrary.readOnlyArchive(sourceArchive, root).list().get(0).id();
        var generator = new FakeLLMClient(List.of(new LLMResponse(GUIDE.replace("边界诊断", "修订边界诊断").replace("检查计算数量与业务边界", "先识别解析故障，禁止放宽零约束"), null, 3, 4)));
        output.reset();
        assertEquals(0, cli(generator, "").generateSkillCandidate(control, sourceArchive, batch, List.of(experience), firstVersion, "开发反馈补充解析排除"), text());
        candidate = id("技能候选");
        var hash = java.util.regex.Pattern.compile("内容摘要: ([a-f0-9]{64})").matcher(text());
        assertTrue(hash.find(), text()); version = hash.group(1);
        assertTrue(generator.requests().get(0).toString().contains("父内容"));
        output.reset();
        assertEquals(0, cli(null, "").reviewSkill(selection(null, null)), text());
        assertTrue(text().contains("内容差异 repair") && text().contains("开发反馈补充解析排除"), text());
        nextCase(false);
        String related = trial(false);
        nextCase(true);
        for (var scenario : json.readTree(Files.readAllBytes(catalog)).path("cases")) {
            if (scenario.path("kind").asText().equals("exclusion")) { json.writeValue(control.resolve("current.json").toFile(), scenario); }
        }
        String counter = trial(true);
        String secondVersion = publish(selection(related, counter));
        assertNotEquals(firstVersion, secondVersion);
        assertArrayEquals(firstBytes, Files.readAllBytes(firstFile));
        output.reset();
        assertEquals(0, cli(null, "").showSkillVersion(control, sourceArchive, firstVersion), text());
        assertTrue(text().contains("默认: false") && text().contains(firstCandidate), text());
        output.reset();
        assertEquals(0, cli(null, "").showSkillVersion(control, sourceArchive, secondVersion), text());
        assertTrue(text().contains("默认: true") && text().contains(firstVersion), text());
        Files.writeString(sourceArchive.resolve(".agent/skills/versions/" + secondVersion + ".md"), "corrupt only v2");
        output.reset();
        assertEquals(0, cli(null, "").showSkillVersion(control, sourceArchive, firstVersion), text());
        assertTrue(text().contains("其他版本不可用") && text().contains(firstCandidate), text());
        assertEquals(4, cli(null, "").showSkillVersion(control, sourceArchive, secondVersion), text());
    }
    @Test void shouldBlockNoninteractiveWrongConfirmationAndChangedEvidence() throws Exception {
        var choice = preparePair();
        assertEquals(1, cli(null, "attest\n").attestSkillTrial(control, choice.counterexample(), "未核验", false), text());
        attest(choice);
        for (String confirmation : List.of("", "approve " + candidate + " wrong-digest\n", "y\n")) {
            assertEquals(1, cli(null, confirmation).approveSkill(choice, true), text());
        }
        assertEquals(1, cli(null, "approve " + candidate + " " + version + "\n").approveSkill(choice, false), text());
        Path file = control.resolve("skill-trials/" + choice.counterexample() + "/state.json");
        byte[] original = Files.readAllBytes(file);
        var wrapper = (com.fasterxml.jackson.databind.node.ObjectNode) json.readTree(original);
        ((com.fasterxml.jackson.databind.node.ObjectNode) wrapper.path("value")).put("reason", "试用后改写的状态说明");
        wrapper.put("sha256", com.agent.task.WorkspaceSnapshot.hash(json.writeValueAsBytes(wrapper.path("value"))));
        json.writeValue(file.toFile(), wrapper);
        assertEquals(4, cli(null, "approve " + candidate + " " + version + "\n").approveSkill(choice, true), text());
        assertTrue(text().contains("人工核验已失效"), text());
        Files.write(file, original);
        Path source = sourceArchive.resolve(".agent/skills/candidates/" + candidate + ".md");
        String guide = Files.readString(source);
        Files.writeString(source, guide + "篡改内容");
        assertEquals(4, cli(null, "approve " + candidate + " " + version + "\n").approveSkill(choice, true), text());
        Files.writeString(source, guide);
        var experience = com.agent.experience.ExperienceLibrary.readOnlyArchive(sourceArchive, root).list().get(0);
        Path experienceFile = sourceArchive.resolve(".agent/experiences/" + experience.id() + ".json");
        byte[] experienceBytes = Files.readAllBytes(experienceFile);
        var disabled = (com.fasterxml.jackson.databind.node.ObjectNode) json.readTree(experienceBytes);
        ((com.fasterxml.jackson.databind.node.ObjectNode) disabled.path("experience")).put("enabled", false);
        disabled.put("sha256", com.agent.task.WorkspaceSnapshot.hash(json.writeValueAsBytes(disabled.path("experience"))));
        json.writeValue(experienceFile.toFile(), disabled);
        assertEquals(4, cli(null, "approve " + candidate + " " + version + "\n").approveSkill(choice, true), text());
        Files.write(experienceFile, experienceBytes);
        output.reset();
        assertEquals(0, cli(null, "approve " + candidate + " " + version + "\n").approveSkill(choice, true), text());
        String published = id("技能版本");
        Files.writeString(sourceArchive.resolve(".agent/skills/versions/" + published + ".md"), "直接编辑批准内容");
        assertEquals(4, cli(null, "").showSkillVersion(control, sourceArchive, published), text());
    }
    @Test void shouldNotPublishWhenFinalPersistenceFails() throws Exception {
        var choice = preparePair();
        attest(choice);
        Path group = sourceArchive.resolve(".agent/skills/reviews");
        Files.writeString(group, "block review persistence after version Markdown is written");
        output.reset();
        assertEquals(4, cli(null, "approve " + candidate + " " + version + "\n").approveSkill(choice, true), text());
        assertFalse(text().contains("已发布不可变版本"), text());
        try (var files = Files.list(sourceArchive.resolve(".agent/skills/versions"))) {
            assertTrue(files.anyMatch(path -> path.toString().endsWith(".md")));
        }
        Files.delete(group);
        output.reset();
        assertEquals(0, cli(null, "").listSkillVersions(control, sourceArchive), text());
        assertTrue(text().contains("暂无已发布"), text());
    }
    @Test void shouldRejectForgedLoadedFlagsWithoutActualToolAndGuideEvidence() throws Exception {
        var choice = preparePair(false);
        Path file = control.resolve("skill-trials/" + choice.related() + "/state.json");
        var wrapper = (com.fasterxml.jackson.databind.node.ObjectNode) json.readTree(Files.readAllBytes(file));
        ((com.fasterxml.jackson.databind.node.ObjectNode) wrapper.path("value")).put("loaded", true);
        var requests = wrapper.path("value").path("requests");
        ((com.fasterxml.jackson.databind.node.ObjectNode) requests.get(requests.size() - 1)).put("guideIncluded", true);
        wrapper.put("sha256", com.agent.task.WorkspaceSnapshot.hash(json.writeValueAsBytes(wrapper.path("value"))));
        json.writeValue(file.toFile(), wrapper);
        output.reset();
        assertEquals(0, cli(null, "").reviewSkill(choice), text());
        assertTrue(text().contains("缺少实际加载工具与指南请求证据"), text());
        assertFalse(text().contains("结构/来源和正反例验收门槛已通过"), text());
    }
}
