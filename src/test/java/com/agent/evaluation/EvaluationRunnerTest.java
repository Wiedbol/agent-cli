package com.agent.evaluation;

import com.agent.CodingAgentCli;
import com.agent.command.*;
import com.agent.llm.*;
import com.agent.task.TaskStore;
import com.agent.tool.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class EvaluationRunnerTest {
    @TempDir Path temp;
    @Test void shouldSimulateAllEighteenRunsWithIsolatedArtifactsAndUnknownTokens() throws Exception {
        Path root = Files.createDirectory(temp.resolve("workspace")).toRealPath();
        var json = new ObjectMapper();
        json.writeValue(root.resolve(".repair-lab-workspace.json").toFile(), Map.of("root", root.toString(), "version", "repair-lab-1"));
        Files.writeString(root.resolve("pom.xml"), "<project/>");
        Path source = root.resolve("src/main/java/demo/ShopRules.java");
        Files.createDirectories(source.getParent()); Files.writeString(source, "broken");
        Files.createDirectories(root.resolve("src/test/java/demo"));
        Files.writeString(root.resolve("src/test/java/demo/ShopRulesTest.java"), "frozen test");
        CommandRunner commands = (dir, command, timeout) -> {
            boolean fixed = Files.readString(source).equals("fixed");
            Path reports = Files.createDirectories(root.resolve("target/surefire-reports"));
            Files.writeString(reports.resolve("TEST-Shop.xml"), "<testsuite tests=\"1\" failures=\"" + (fixed ? 0 : 1)
                    + "\" errors=\"0\" skipped=\"0\"><testcase classname=\"ShopTest\" name=\"checks\">"
                    + (fixed ? "" : "<failure message=\"boundary failure\"/>") + "</testcase></testsuite>");
            return new CommandResult(fixed ? 0 : 1, false, "simulated command", 1, CommandResult.OutputCompleteness.COMPLETE);
        };
        var sink = new PrintStream(OutputStream.nullOutputStream());
        var sourceModel = new FakeLLMClient(List.of(new LLMResponse(null, List.of(new ToolCall("fix", new ToolCall.Function("write_file",
                "{\"path\":\"src/main/java/demo/ShopRules.java\",\"content\":\"fixed\"}"))), 1, 1), new LLMResponse("done", null, 1, 1)));
        var cli = new CodingAgentCli(root, sourceModel, true, 4, new BufferedReader(new StringReader("")), sink, sink, null, commands);
        assertEquals(0, cli.startManagedTask("source", null));
        String task = new TaskStore(root).list().get(0).id;
        var extraction = new FakeLLMClient(List.of(new LLMResponse("{\"conditions\":\"Shop\",\"errorFeatures\":\"ShopTest checks boundary failure\","
                + "\"recommendation\":\"inspect boundaries\",\"limitations\":\"not universal\"}", null, 1, 1)));
        assertEquals(0, new CodingAgentCli(root, extraction, true, 4, new BufferedReader(new StringReader("save " + task + "\n")), sink, sink, null, commands).extractExperience(task, true));
        var scenarios = new LinkedHashMap<String, EvaluationRunner.Scenario>();
        for (String name : List.of("eval-coupon", "eval-stock", "eval-quantity")) {
            scenarios.put(name, new EvaluationRunner.Scenario("repair", Map.of("pom.xml", "<project/>",
                    "src/main/java/demo/ShopRules.java", "broken " + name, "src/test/java/demo/ShopRulesTest.java", "frozen test")));
        }
        Path control = temp.resolve("archive");
        String originalMarker = Files.readString(root.resolve(".repair-lab-workspace.json"));
        var models = new ArrayList<FakeLLMClient>();
        var results = new EvaluationRunner().execute(root, control, scenarios,
                new EvaluationRunner.Config("FAKE", "mvn test", 1, true), run -> {
                    assertFalse(Files.exists(root.resolve(".agent/experiences")));
                    assertFalse(Files.exists(root.resolve(".agent/sessions")));
                    assertFalse(Files.exists(root.resolve("old-artifact.txt")));
                    Files.writeString(root.resolve("old-artifact.txt"), "run artifact");
                    assertEquals(originalMarker, Files.readString(root.resolve(".repair-lab-workspace.json")));
                    for (Tool tool : List.of(new ReadFileTool(), new ListFilesTool(), new SearchCodeTool())) {
                        var args = json.createObjectNode().put("path", "../archive/source").put("keyword", "fixed");
                        assertThrows(SecurityException.class, () -> tool.execute(new ToolRequest(root, args)));
                    }
                    assertFalse(new ListFilesTool().execute(new ToolRequest(root, json.createObjectNode().put("path", ".")))
                            .toString().contains("source/.agent"));
                    if (run.order() == 1) { Files.writeString(root.resolve(".repair-lab-workspace.json"), "changed"); }
                    if (run.order() == 18) { throw new IllegalStateException("simulated transport failure"); }
                    var model = new FakeLLMClient(List.of(new LLMResponse("password=private-value", null,
                            run.order() == 2 ? 2 : -1, run.order() == 2 ? 3 : -1)));
                    models.add(model); return model;
                }, commands);
        assertEquals(18, results.size());
        for (int i = 0; i < 17; i++) {
            String scenario = results.get(i).run().scenario();
            var sameScenario = results.stream().filter(result -> result.run().scenario().equals(scenario)).findFirst().orElseThrow();
            assertEquals(sameScenario.initial(), results.get(i).initial());
            if (i == 1) { assertEquals(2L, results.get(i).promptTokens()); }
            else { assertNull(results.get(i).promptTokens()); }
            assertEquals(1, results.get(i).exitCode());
            assertEquals(results.get(i).run().experiencesEnabled(), models.get(i).requests().get(0).toString().contains("参考经验"));
        }
        assertTrue(Files.exists(control.resolve("source/.agent/tasks/" + task + "/state.json")));
        assertFalse(Files.exists(root.resolve(".agent")));
        assertTrue(json.readTree(control.resolve("config.json").toFile()).path("simulated").asBoolean());
        assertEquals(18, json.readTree(control.resolve("results.json").toFile()).size());
        assertEquals("ERROR", results.get(17).state());
        assertTrue(results.get(17).reason().contains("simulated transport failure"));
        assertEquals(originalMarker, Files.readString(root.resolve(".repair-lab-workspace.json")));
        assertFalse(Files.readString(control.resolve("run-1.log")).contains("private-value"));
        assertTrue(Files.readString(control.resolve("run-1.log")).contains("已过滤"));
        assertEquals(6, json.readTree(control.resolve("summary.json").toFile()).size());
    }

    @Test void shouldRejectUnsafeLocationsBeforeChangingWorkspace() throws Exception {
        Path root = Files.createDirectory(temp.resolve("root")).toRealPath();
        Files.writeString(root.resolve("keep.txt"), "keep");
        var runner = new EvaluationRunner();
        var config = new EvaluationRunner.Config("fake", "mvn test", 1, true);
        assertThrows(IOException.class, () -> runner.execute(root, root.resolve("archive"), Map.of(), config, run -> null, null));
        Files.createDirectory(root.resolve(".git"));
        assertThrows(IOException.class, () -> runner.execute(root, temp.resolve("outside"), Map.of(), config, run -> null, null));
        assertEquals("keep", Files.readString(root.resolve("keep.txt")));
        assertFalse(Files.exists(temp.resolve("outside")));
    }
}
