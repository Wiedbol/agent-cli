package com.agent;

import com.agent.llm.*;
import com.agent.skill.SkillReviews;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class SkillPublicationFixture extends SkillTrialFixture {
    String trial(boolean exclusion) throws Exception {
        var model = new FakeLLMClient(List.of(calls(call("load", "load_skill", Map.of("id", candidate, "version", version))),
                calls(call("declare", "declare_skill", Map.of("id", candidate, "version", version,
                        "decision", exclusion ? "REJECT" : "ACCEPT", "reason", exclusion ? "解析故障不能放宽零数量守卫" : "边界适用")),
                        call("edit", "write_file", Map.of("path", SOURCE, "content", "dev-fixed"))),
                new LLMResponse("请验收", null, 7, 11)));
        output.reset();
        assertEquals(0, cli(model, "").startSkillTrial(config(3)), text());
        return id("技能试用");
    }
    void nextCase(boolean exclusion) throws Exception {
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
    SkillReviews.Selection selection(String related, String counter) {
        return new SkillReviews.Selection(control, sourceArchive, candidate, version, related, counter);
    }
    SkillReviews.Selection preparePair() throws Exception { return preparePair(true); }
    SkillReviews.Selection preparePair(boolean loadRelated) throws Exception {
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
    void attest(SkillReviews.Selection choice) throws Exception {
        output.reset();
        assertEquals(0, cli(null, "").reviewSkill(choice), text());
        var hashes = java.util.regex.Pattern.compile("证据摘要: ([a-f0-9]{64})").matcher(text()).results().map(m -> m.group(1)).toList();
        assertEquals(2, hashes.size());
        output.reset();
        String confirmation = "attest " + choice.counterexample() + " " + version + " " + hashes.get(1) + "\n";
        assertEquals(0, cli(null, confirmation).attestSkillTrial(control, choice.counterexample(), "已逐项检查声明与修改：未执行错误放宽，正常解析修复通过", true), text());
    }
    String publish(SkillReviews.Selection choice) throws Exception {
        attest(choice);
        output.reset();
        assertEquals(0, cli(null, "approve " + candidate + " " + version + "\n").approveSkill(choice, true), text());
        return id("技能版本");
    }
}
