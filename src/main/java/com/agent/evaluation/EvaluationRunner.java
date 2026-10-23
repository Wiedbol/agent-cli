package com.agent.evaluation;

import com.agent.CodingAgentCli;
import com.agent.command.CommandRunner;
import com.agent.experience.Experience;
import com.agent.experience.ExperienceLibrary;
import com.agent.llm.LLMClient;
import com.agent.llm.Message;
import com.agent.session.Redactor;
import com.agent.task.TaskStore;
import com.agent.task.WorkspaceSnapshot;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.*;
import java.nio.file.*;
import java.util.*;

/** 可信控制层将所有旧现场归档至工作根之外，只让模型接触当前运行。 */
public final class EvaluationRunner {
    private static final ObjectMapper JSON = new ObjectMapper();
    public record Scenario(String goal, Map<String, String> files) { }
    public record Config(String model, String acceptance, int requests, boolean simulated) {
        public Config {
            if (model == null || model.isBlank() || acceptance == null || acceptance.isBlank() || requests < 1) {
                throw new IllegalArgumentException("模型、验收命令和正数请求预算必须冻结");
            }
        }
        public int commandTimeoutSeconds() { return 60; }
    }
    public record Result(EvaluationMatrix.Run run, int exitCode, String state, String reason,
                         Map<String, String> initial, int requests, int tools, Long promptTokens,
                         Long completionTokens, long elapsedMillis, int humanInterventions) { }
    @FunctionalInterface public interface Models { LLMClient create(EvaluationMatrix.Run run) throws IOException; }

    public List<Result> execute(Path workspace, Path archive, Map<String, Scenario> scenarios, Config config,
                                Models models, CommandRunner commands) throws IOException {
        Path root = workspace.toRealPath();
        archive = archive.toAbsolutePath().normalize();
        if (!workspace.isAbsolute() || !workspace.normalize().equals(root) || archive.toAbsolutePath().normalize().startsWith(root)
                || root.startsWith(archive.toAbsolutePath().normalize()) || Files.exists(archive)) {
            throw new IOException("评测需要固定规范工作根和不存在的外部归档目录");
        }
        if (root.getParent() == null || Files.exists(root.resolve(".git"))
                || !archive.getParent().toRealPath().equals(archive.getParent())
                || !Files.getFileStore(root).equals(Files.getFileStore(archive.getParent()))
                || Files.isSymbolicLink(root.resolve(".repair-lab-workspace.json"))) {
            throw new IOException("评测不能使用仓库根、文件系统根或路径别名");
        }
        byte[] markerBytes = Files.readAllBytes(root.resolve(".repair-lab-workspace.json"));
        var marker = JSON.readTree(markerBytes);
        if (!root.toString().equals(marker.path("root").asText()) || !"repair-lab-1".equals(marker.path("version").asText())) {
            throw new IOException("不是已声明的示例工作区");
        }
        for (var scenario : scenarios.values()) {
            if (!scenario.files().keySet().equals(Set.of("pom.xml", "src/main/java/demo/ShopRules.java", "src/test/java/demo/ShopRulesTest.java"))) {
                throw new IOException("场景输入必须仅包含声明的pom、源码和验收");
            }
        }
        for (var run : EvaluationMatrix.formalRuns()) {
            if (!scenarios.containsKey(run.scenario())) { throw new IOException("缺少评测场景"); }
        }
        var inputs = new LinkedHashMap<String, Scenario>();
        for (var entry : scenarios.entrySet()) {
            inputs.put(entry.getKey(), new Scenario(entry.getValue().goal(), Map.copyOf(entry.getValue().files())));
        }
        var acceptanceFiles = inputs.get("eval-coupon").files();
        for (var scenario : inputs.values()) {
            if (!Objects.equals(acceptanceFiles.get("pom.xml"), scenario.files().get("pom.xml"))
                    || !Objects.equals(acceptanceFiles.get("src/test/java/demo/ShopRulesTest.java"),
                    scenario.files().get("src/test/java/demo/ShopRulesTest.java"))) {
                throw new IOException("三个场景必须使用相同的冻结构建配置和验收测试");
            }
        }
        var library = new ExperienceLibrary(root);
        var frozen = library.list().stream().filter(Experience::enabled).toList();
        if (frozen.isEmpty() || frozen.stream().anyMatch(experience -> !library.sourceValid(experience))) {
            throw new IOException("必须先确认有效来源经验");
        }
        Files.createDirectory(archive);
        Path control = archive.toRealPath();
        if (!control.equals(archive.toAbsolutePath().normalize())) { throw new IOException("归档目录包含别名"); }
        JSON.writeValue(control.resolve("frozen.json").toFile(), frozen);
        JSON.writeValue(control.resolve("config.json").toFile(), Map.of("model", config.model(),
                "acceptance", config.acceptance(), "requests", config.requests(), "simulated", config.simulated(),
                "commandTimeoutSeconds", config.commandTimeoutSeconds(), "httpReadTimeoutSeconds", 120,
                "sampling", "provider-default", "endpoint", config.simulated() ? "FAKE" : com.agent.llm.DeepSeekClient.defaultEndpoint()));
        var sourceIds = frozen.stream().map(Experience::sourceTask).collect(java.util.stream.Collectors.toSet());
        JSON.writeValue(control.resolve("preparation.json").toFile(), new TaskStore(root).list().stream()
                .map(task -> Map.of("taskId", task.id, "phase", sourceIds.contains(task.id) ? "source" : "pilot",
                        "state", task.state.name(), "progress", task.progress)).toList());
        archive(root, control.resolve("source"));
        Map<String, String> frozenHashes = WorkspaceSnapshot.capture(control);
        var results = new ArrayList<Result>();
        for (var run : EvaluationMatrix.formalRuns()) {
            var currentHashes = WorkspaceSnapshot.capture(control);
            for (var entry : frozenHashes.entrySet()) {
                if (!entry.getValue().equals(currentHashes.get(entry.getKey()))) { throw new IOException("冻结来源发生变化"); }
            }
            var scenario = inputs.get(run.scenario());
            Files.deleteIfExists(root.resolve(".repair-lab-workspace.json"));
            Files.write(root.resolve(".repair-lab-workspace.json"), markerBytes);
            for (var file : scenario.files().entrySet()) {
                Path target = root.resolve(file.getKey());
                Files.createDirectories(target.getParent());
                Files.writeString(target, file.getValue());
            }
            Map<String, String> initial = WorkspaceSnapshot.capture(root);
            long started = System.nanoTime();
            int exit = 4;
            String state = "ERROR", reason = "";
            int requests = 0, tools = 0;
            Long prompt = null, completion = null;
            var buffer = new ByteArrayOutputStream();
            try (var log = new PrintStream(buffer, true, java.nio.charset.StandardCharsets.UTF_8)) {
                LLMClient base = models.create(run);
                LLMClient model = (messages, definitions) -> {
                    if (!run.experiencesEnabled()) { return base.chat(messages, definitions); }
                    var copy = new ArrayList<>(messages);
                    for (int i = copy.size() - 1; i >= 0; i--) {
                        var message = copy.get(i);
                        if (!"user".equals(message.role())) { continue; }
                        String advice = ExperienceLibrary.adviceFromValidated(frozen, message.content(),
                                WorkspaceSnapshot.capture(root), new ArrayList<>(), log);
                        copy.set(i, Message.user(message.content() + advice));
                        break;
                    }
                    return base.chat(copy, definitions);
                };
                CommandRunner restricted = (dir, command, timeout) -> {
                    if (!config.acceptance().equals(command)) { throw new IOException("正式评测仅允许冻结验收命令"); }
                    return commands.run(dir, command, config.commandTimeoutSeconds());
                };
                var cli = new CodingAgentCli(root, model, true, config.requests(), new BufferedReader(new StringReader("")), log, log, null, restricted);
                exit = cli.startManagedTask(scenario.goal(), config.acceptance(), false, run.experiencesEnabled());
                var tasks = new TaskStore(root).list();
                if (!tasks.isEmpty()) {
                    var task = tasks.get(0);
                    state = task.state.name(); reason = task.reason;
                    requests = task.progress.requests; tools = task.progress.tools;
                    if (task.progress.unknownRequests == 0) { prompt = task.progress.promptTokens; completion = task.progress.completionTokens; }
                }
            } catch (Exception failed) {
                reason = Redactor.redact(failed.getClass().getSimpleName() + ": " + failed.getMessage()).text();
            }
            Files.writeString(control.resolve("run-" + run.order() + ".log"),
                    Redactor.redact(buffer.toString(java.nio.charset.StandardCharsets.UTF_8)).text());
            var result = new Result(run, exit, state, reason, initial, requests, tools, prompt, completion,
                    (System.nanoTime() - started) / 1_000_000, 0);
            results.add(result);
            Path temporary = control.resolve("results.tmp");
            JSON.writeValue(temporary.toFile(), results);
            Files.move(temporary, control.resolve("results.json"), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            archive(root, control.resolve("run-" + run.order()));
        }
        var finalHashes = WorkspaceSnapshot.capture(control);
        for (var entry : frozenHashes.entrySet()) {
            if (!entry.getValue().equals(finalHashes.get(entry.getKey()))) { throw new IOException("冻结来源发生变化"); }
        }
        Files.deleteIfExists(root.resolve(".repair-lab-workspace.json"));
        Files.write(root.resolve(".repair-lab-workspace.json"), markerBytes);
        JSON.writeValue(control.resolve("summary.json").toFile(), results.stream().collect(
                java.util.stream.Collectors.groupingBy(result -> result.run().scenario() + "/"
                        + (result.run().experiencesEnabled() ? "on" : "off"),
                        java.util.stream.Collectors.groupingBy(Result::state, java.util.stream.Collectors.counting()))));
        return List.copyOf(results);
    }

    private static void archive(Path root, Path destination) throws IOException {
        Files.createDirectory(destination);
        try (var children = Files.list(root)) {
            var paths = children.sorted().toList();
            for (Path child : paths) {
                if (Files.isSymbolicLink(child) || !child.toRealPath().startsWith(root)) { throw new IOException("现场存在路径别名"); }
            }
            for (Path child : paths) {
                if (child.getFileName().toString().equals(".repair-lab-workspace.json")) { continue; }
                Files.move(child, destination.resolve(child.getFileName()), StandardCopyOption.ATOMIC_MOVE);
            }
        }
    }
}
