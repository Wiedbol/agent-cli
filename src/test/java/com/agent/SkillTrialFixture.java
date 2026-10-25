package com.agent;

import com.agent.command.CommandResult;
import com.agent.command.CommandRunner;
import com.agent.llm.*;
import com.agent.skill.SkillTrials;
import com.agent.task.WorkspaceSnapshot;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.io.TempDir;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

class SkillTrialFixture {
    @TempDir Path base;
    Path root, control, sourceArchive, catalog;
    String candidate, version, otherCandidate, batch;
    final ObjectMapper json = new ObjectMapper();
    final ByteArrayOutputStream output = new ByteArrayOutputStream();
    static final String SOURCE = "src/main/java/demo/BusinessRules.java";
    static final String TEST = "src/test/java/demo/BusinessRulesTest.java";
    static final String GUIDE = """
            {"title":"边界诊断","conditions":"业务边界失败","exclusions":"不适用于语法问题",
             "diagnosis":"SKILL_GUIDE_PRIVATE_MARKER","repair":"检查计算数量与业务边界","verification":"运行冻结验收"}
            """;
    String text() { return output.toString(StandardCharsets.UTF_8); }
    String id(String label) {
        var match = Pattern.compile(label + ": ([0-9a-f-]{36})").matcher(text());
        assertTrue(match.find(), text());
        return match.group(1);
    }
    CodingAgentCli cli(LLMClient model, String input) {
        var stream = new PrintStream(output, true, StandardCharsets.UTF_8);
        return new CodingAgentCli(root, model, true, 8, new BufferedReader(new StringReader(input)), stream, stream, null, commands());
    }
    CommandRunner commands() {
        return (dir, command, timeout) -> {
            assertEquals("mvn test", command);
            String content = Files.readString(root.resolve(SOURCE));
            boolean fixed = content.equals("fixed") || content.equals("dev-fixed");
            Path reports = Files.createDirectories(root.resolve("target/surefire-reports"));
            Files.writeString(reports.resolve("TEST-demo.BusinessRulesTest.xml"),
                    "<testsuite tests=\"1\" failures=\"" + (fixed ? 0 : 1) + "\" errors=\"0\" skipped=\"0\">"
                    + "<testcase classname=\"demo.BusinessRulesTest\" name=\"checks\">"
                    + (fixed ? "" : "<failure message=\"expected fixed\"/>") + "</testcase></testsuite>");
            return new CommandResult(fixed ? 0 : 1, false, "offline independent test fixture", 1, CommandResult.OutputCompleteness.COMPLETE);
        };
    }
    ToolCall call(String id, String tool, Map<String, String> arguments) throws Exception {
        return new ToolCall(id, new ToolCall.Function(tool, json.writeValueAsString(arguments)));
    }
    LLMResponse calls(ToolCall... calls) { return new LLMResponse(null, List.of(calls), 2, 3); }
    Map<String, String> files(String content, String goal) {
        return Map.of("pom.xml", "<project/>", SOURCE, content, TEST, "frozen acceptance fixture", "GOAL.md", goal + "\n");
    }
    Map<String, Object> metadata(String id, String kind, String goal, Map<String, String> files) {
        var hashes = new TreeMap<String, String>();
        files.forEach((name, content) -> hashes.put(name, WorkspaceSnapshot.hash(content.getBytes(StandardCharsets.UTF_8))));
        return Map.of("id", id, "kind", kind, "goal", goal, "initial", hashes, "acceptance",
                Map.of("pom.xml", hashes.get("pom.xml"), TEST, hashes.get(TEST)), "command", "mvn test",
                "tests", List.of("checks"), "expectedFailures", List.of("checks"));
    }
    void writeFiles(Map<String, String> files) throws Exception {
        for (var entry : files.entrySet()) {
            Path file = root.resolve(entry.getKey());
            Files.createDirectories(file.getParent());
            Files.writeString(file, entry.getValue());
        }
    }
    void prepare() throws Exception { prepare(GUIDE); }
    void prepare(String guide) throws Exception {
        root = Files.createDirectory(base.resolve("workspace"));
        control = Files.createDirectory(base.resolve("control"));
        catalog = base.resolve("catalog.json");
        var marker = Map.of("version", "repair-lab-2", "projectId", UUID.randomUUID().toString(), "root", root.toString());
        json.writeValue(root.resolve(".repair-lab-workspace.json").toFile(), marker);
        var sourceFiles = files("broken", "修复来源问题");
        writeFiles(sourceFiles);
        var model = new FakeLLMClient(List.of(calls(call("repair", "write_file", Map.of("path", SOURCE, "content", "fixed"))),
                new LLMResponse("请验收", null, 1, 1)));
        assertEquals(0, cli(model, "").startManagedTask("修复来源问题", null), text());
        String task = id("任务");
        var advice = new FakeLLMClient(List.of(new LLMResponse("""
                {"conditions":"业务边界","errorFeatures":"checks expected fixed","recommendation":"检查业务逻辑",
                 "limitations":"不适用于语法"}
                """, null, 1, 1)));
        assertEquals(0, cli(advice, "save " + task + "\n").extractExperience(task, true), text());
        String experience = id("已保存经验");
        output.reset();
        assertEquals(0, cli(null, "").startSkillBatch(8), text());
        batch = id("技能批次");
        var generator = new FakeLLMClient(List.of(new LLMResponse(guide, null, 1, 1),
                new LLMResponse(guide.replace("SKILL_GUIDE_PRIVATE_MARKER", "OTHER_CANDIDATE_SECRET"), null, 1, 1)));
        assertEquals(0, cli(generator, "").generateSkillCandidate(batch, List.of(experience), null, null), text());
        candidate = id("技能候选");
        var digest = Pattern.compile("内容摘要: ([a-f0-9]{64})").matcher(text());
        assertTrue(digest.find(), text());
        version = digest.group(1);
        output.reset();
        assertEquals(0, cli(generator, "").generateSkillCandidate(batch, List.of(experience), null, null), text());
        otherCandidate = id("技能候选");
        sourceArchive = control.resolve("history/" + UUID.randomUUID() + "/workspace");
        Files.createDirectories(sourceArchive.getParent());
        Files.move(root, sourceArchive, StandardCopyOption.ATOMIC_MOVE);
        Files.createDirectory(root);
        json.writeValue(root.resolve(".repair-lab-workspace.json").toFile(), marker);
        var devFiles = files("dev-broken", "修复新的边界问题");
        writeFiles(devFiles);
        var sourceMeta = metadata("source-delivery", "source", "修复来源问题", sourceFiles);
        var devMeta = metadata("dev-points", "related", "修复新的边界问题", devFiles);
        json.writeValue(control.resolve("owner.json").toFile(), Map.of("archive", control.toString(), "marker", marker));
        json.writeValue(control.resolve("current.json").toFile(), devMeta);
        json.writeValue(sourceArchive.getParent().resolve("manifest.json").toFile(), sourceMeta);
        json.writeValue(catalog.toFile(), Map.of("version", "repair-lab-2", "stages", Map.of("source", List.of("source-delivery"),
                "initial-development", List.of("dev-points"), "feedback-development", List.of(), "required-regression", List.of("dev-points"),
                "formal", List.of("formal-grammar")), "cases", List.of(sourceMeta, devMeta)));
        output.reset();
    }
    SkillTrials.Config config(int requests) {
        return new SkillTrials.Config(control, sourceArchive, catalog, candidate, batch, requests, "offline-model");
    }

}
