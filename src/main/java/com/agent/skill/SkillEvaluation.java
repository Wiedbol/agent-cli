package com.agent.skill;

import com.agent.CodingAgentCli;
import com.agent.command.CommandRunner;
import com.agent.evaluation.EvaluationMatrix;
import com.agent.evaluation.EvaluationRunner;
import com.agent.llm.*;
import com.agent.session.Redactor;
import com.agent.task.*;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** 正式对照只调度普通技能任务；归档、额度和证据留在模型工作根之外。 */
public final class SkillEvaluation {
    public record Config(Path control, Path source, Path catalog, Path inputs, Path archive, String v1, String v2,
                         String model, String endpoint, int taskRequests, int batchRequests, int repeats, boolean simulated) { }
    @FunctionalInterface public interface Models { LLMClient create(EvaluationMatrix.SkillRun run) throws IOException; }
    public record Cost(String phase, String id, Integer requests, long knownPromptTokens, long knownCompletionTokens, int unknownRequests) { }
    public record Totals(Integer requests, Long promptTokens, Long completionTokens, Long totalTokens, int knownRequests,
                         long knownPromptTokens, long knownCompletionTokens, int unknownRecords) { }
    public record Freeze(Config config, String project, JsonNode marker, Map<String, SkillStore.Snapshot> versions,
                         JsonNode catalog, Map<String, EvaluationRunner.Scenario> inputs, List<EvaluationMatrix.SkillRun> order,
                         Map<String, String> files, List<Cost> preparation, Map<String, Object> configuration) { }
    public static final class Attempt {
        public String id = UUID.randomUUID().toString(), taskId, status = "RESERVED", reason = "";
        public int order, decision;
        public long reservedAt = System.currentTimeMillis();
        public Integer promptTokens, completionTokens;
        public SkillRequests.Payload payload;
    }
    public static final class Cell {
        public EvaluationMatrix.SkillRun run;
        public String state = "PLANNED", reason = "", taskId, misuse = "UNKNOWN";
        public int exitCode = 4, repairRounds, requests, tools, loads, interventions, unknownRequests;
        public long elapsedMillis, knownPromptTokens, knownCompletionTokens;
        public Long promptTokens, completionTokens, totalTokens;
        public Map<String, String> initial = Map.of();
        public Map<String, String> artifacts = Map.of();
        public List<SkillTask.Event> events = List.of();
        public List<TaskRecord.Operation> operations = List.of();
        public JsonNode counterexample;
    }
    public static final class Report {
        public int schemaVersion = 1;
        public String kind, freezeSha256;
        public Freeze frozen;
        public List<Cell> cells = new ArrayList<>();
        public List<Attempt> attempts = new ArrayList<>();
        public List<Cost> preparation = List.of();
        public Totals formalCost, wholeProcessCost;
        public String limitation = "SIMULATED 只验证工程机制，不证明模型迁移能力。真实六格也仅为探索性配对观察，不自动选择最好结果或修订技能。";
    }
    private final Path root;
    private final PrintStream out;
    public SkillEvaluation(Path root, PrintStream out) throws IOException { this.root = SkillTrials.canonical(root); this.out = out; }

    public Report execute(Config config, Models models, CommandRunner commands) throws IOException {
        Freeze frozen = prepare(config);
        try (var writer = new TaskStore(config.source()).lock()) {
            checkFrozen(frozen);
            Files.createDirectory(config.archive());
            var report = new Report(); report.kind = config.simulated() ? "SIMULATED" : "REAL EXPLORATORY";
            report.frozen = frozen; report.freezeSha256 = SkillStore.digest(frozen); report.preparation = frozen.preparation();
            for (var run : frozen.order()) {
                var cell = new Cell(); cell.run = run; cell.counterexample = manifest(frozen.catalog(), run.scenario()).path("exclusion");
                report.cells.add(cell);
            }
            save(report);
            moveWorkspace(config.archive().resolve("preparation-root"));
            restoreMarker(frozen);
            String blocked = null;
            for (var cell : report.cells) {
                if (blocked != null) { cell.state = "BLOCKED"; cell.reason = blocked; save(report); continue; }
                long started = System.nanoTime();
                var logBytes = new ByteArrayOutputStream();
                try (var log = new PrintStream(logBytes, true, StandardCharsets.UTF_8)) {
                    cell.state = "RUNNING"; save(report);
                    checkFrozen(frozen);
                    var scenario = frozen.inputs().get(cell.run.scenario());
                    for (var file : scenario.files().entrySet()) {
                        Path path = root.resolve(file.getKey()); Files.createDirectories(path.getParent()); Files.writeString(path, file.getValue());
                    }
                    cell.initial = Map.copyOf(WorkspaceSnapshot.capture(root));
                    checkWorkspace(frozen, cell);
                    int remaining = config.batchRequests() - report.attempts.size();
                    if (remaining <= 0) { cell.state = "Paused"; cell.exitCode = 1; cell.reason = "批次总额度耗尽，未发送、未自动重跑"; }
                    else {
                        LLMClient delegate = models.create(cell.run);
                        if (delegate == null) { throw new IOException("模型入口不可用"); }
                        LLMClient accounted = (messages, tools) -> request(report, cell, delegate, messages, tools);
                        var knowledge = PublishedSkills.frozen(root, log, config.control(), config.source(), frozen.versions().get(cell.run.version()), () -> checkFrozen(frozen));
                        CommandRunner restricted = (dir, command, timeout) -> {
                            if (!root.equals(dir.toRealPath()) || !"mvn test".equals(command)) { throw new IOException("正式对照只允许冻结验收命令 mvn test"); }
                            checkFrozen(frozen); checkWorkspace(frozen, cell);
                            return commands.run(dir, command, 60);
                        };
                        var cli = new CodingAgentCli(root, accounted, true, Math.min(config.taskRequests(), remaining),
                                new BufferedReader(new StringReader("")), log, log, null, restricted);
                        cell.exitCode = cli.startManagedTask(scenario.goal(), "mvn test", false, KnowledgeMode.SKILL, knowledge);
                    }
                } catch (Exception failed) {
                    cell.state = "ERROR"; cell.reason = safe(failed.getClass().getSimpleName() + ": " + failed.getMessage());
                } finally {
                    try { metrics(cell); }
                    catch (IOException invalid) { cell.state = "ERROR"; cell.reason = safe("任务证据不完整: " + invalid.getMessage()); }
                    cell.elapsedMillis = (System.nanoTime() - started) / 1_000_000;
                    Files.writeString(config.archive().resolve("run-" + cell.run.order() + ".log"), safe(logBytes.toString(StandardCharsets.UTF_8)));
                    save(report);
                    try { checkFrozen(frozen); }
                    catch (IOException changed) { blocked = safe(changed.getMessage()); cell.state = "ERROR"; cell.reason = blocked; }
                    try {
                        Path destination = config.archive().resolve("run-" + cell.run.order());
                        moveWorkspace(destination); cell.artifacts = Map.copyOf(tree(destination)); restoreMarker(frozen);
                    }
                    catch (IOException unsafe) { blocked = safe("现场归档失败，保留当前根: " + unsafe.getMessage()); cell.state = "ERROR"; cell.reason = blocked; }
                    save(report);
                }
            }
            print(report, out);
            return report;
        }
    }

    private Freeze prepare(Config config) throws IOException {
        if (config == null || config.control() == null || config.source() == null || config.catalog() == null || config.inputs() == null || config.archive() == null
                || config.taskRequests() < 1 || config.batchRequests() < 1 || config.model() == null || config.model().isBlank()
                || config.endpoint() == null || config.endpoint().isBlank()) { throw new IOException("必须冻结模型、端点及明确的任务/批次正数额度"); }
        SkillStore.requireSafe(config.model()); SkillStore.requireSafe(config.endpoint());
        var order = EvaluationMatrix.skillRuns(config.repeats());
        Path control = SkillTrials.canonical(config.control()), source = SkillTrials.canonical(config.source());
        Path catalog = SkillTrials.canonical(config.catalog()), inputs = SkillTrials.canonical(config.inputs());
        Path archive = config.archive();
        if (!archive.isAbsolute() || !archive.equals(archive.normalize()) || Files.exists(archive, LinkOption.NOFOLLOW_LINKS)
                || archive.getParent() == null || !SkillTrials.canonical(archive.getParent()).equals(archive.getParent())
                || archive.startsWith(root) || root.startsWith(archive) || archive.startsWith(control) || control.startsWith(archive)
                || catalog.startsWith(root) || inputs.startsWith(root) || Files.exists(root.resolve(".git"))
                || !Files.getFileStore(root).equals(Files.getFileStore(archive.getParent()))) { throw new IOException("正式对照需要独立规范外部归档/清单/输入，不能使用仓库根或路径别名"); }
        var store = new SkillTrials(root, out).library(control, source);
        var first = store.snapshot(config.v1()); var second = store.snapshot(config.v2());
        var parent = second.candidate().parent();
        if (config.v1().equals(config.v2()) || first.candidate().parent() != null || parent == null || second.candidate().feedback() == null
                || !first.version().skillId().equals(second.version().skillId()) || !config.v1().equals(parent.versionId())
                || !SkillStore.digest(first.version()).equals(parent.sha256())
                || second.candidate().feedback().items().stream().noneMatch(item -> "USE".equals(item.entry().kind())
                        && item.entry().version() != null && config.v1().equals(item.entry().version().versionId()))) {
            throw new IOException("需要同一技能的初始批准 v1 和基于其开发使用反馈审核修订的 v2");
        }
        var catalogValue = SkillTrials.read(catalog);
        var expected = Set.of("formal-bulk", "formal-remaining", "formal-grammar");
        var formalIds = new HashSet<String>(); catalogValue.path("stages").path("formal").forEach(v -> formalIds.add(v.asText()));
        var scenarios = SkillStore.JSON.readValue(Files.readAllBytes(inputs), new TypeReference<Map<String, EvaluationRunner.Scenario>>() { });
        if (!"repair-lab-2".equals(catalogValue.path("version").asText()) || !expected.equals(formalIds) || !expected.equals(scenarios.keySet())) {
            throw new IOException("首轮必须冻结两类迁移与一类反例的三个正式样例");
        }
        var frozenInputs = new TreeMap<String, EvaluationRunner.Scenario>();
        var names = Set.of("pom.xml", "src/main/java/demo/BusinessRules.java", "src/test/java/demo/BusinessRulesTest.java", "GOAL.md");
        for (String id : expected) {
            JsonNode item = manifest(catalogValue, id); var scenario = scenarios.get(id);
            if (!"mvn test".equals(item.path("command").asText()) || !id.equals(item.path("id").asText())
                    || !(id.equals("formal-grammar") ? "exclusion" : "related").equals(item.path("kind").asText())
                    || scenario == null || scenario.files() == null || !names.equals(scenario.files().keySet()) || !item.path("goal").asText().equals(scenario.goal())) {
                throw new IOException("正式样例类型、目标或可见文件清单不符");
            }
            var hashes = new TreeMap<String, String>();
            for (var file : scenario.files().entrySet()) {
                if (file.getValue() == null) { throw new IOException("正式输入缺失"); }
                hashes.put(file.getKey(), WorkspaceSnapshot.hash(file.getValue().getBytes(StandardCharsets.UTF_8)));
            }
            if (!SkillStore.JSON.valueToTree(hashes).equals(item.path("initial"))
                    || !SkillStore.JSON.valueToTree(Map.of("pom.xml", hashes.get("pom.xml"), "src/test/java/demo/BusinessRulesTest.java",
                            hashes.get("src/test/java/demo/BusinessRulesTest.java"))).equals(item.path("acceptance"))
                    || !item.path("tests").isArray() || item.path("tests").isEmpty() || !item.path("expectedFailures").isArray()) {
                throw new IOException("正式输入/验收摘要或测试清单不符");
            }
            if (id.equals("formal-grammar") && (!item.path("exclusion").isObject() || item.path("exclusion").path("observableConstraint").asText().isBlank()
                    || !item.path("exclusion").path("wrongFailures").isArray())) { throw new IOException("正式反例须预先冻结可观察判据"); }
            frozenInputs.put(id, new EvaluationRunner.Scenario(scenario.goal(), Map.copyOf(scenario.files())));
        }
        validateTree(root);
        var snapshots = Map.of("v1", first, "v2", second);
        var files = tree(control); files.put(catalog.toString().replace('\\', '/'), hash(catalog)); files.put(inputs.toString().replace('\\', '/'), hash(inputs));
        for (var snapshot : snapshots.values()) {
            var selection = store.metadata(snapshot.version().id()).publication().selection();
            if (!selection.control().equals(control) || !selection.source().equals(source)) { throw new IOException("批准证据必须属于同一冻结控制归档与技能库"); }
            var trials = new HashSet<>(List.of(selection.related(), selection.counterexample()));
            if (selection.regressions() != null) { trials.addAll(selection.regressions()); }
            for (String id : trials) {
                SkillStore.requireId(id);
                var trial = SkillTrials.read(control.resolve("skill-trials").resolve(id).resolve("state.json")).path("value");
                Path dependency = SkillTrials.canonical(Path.of(trial.path("catalog").asText()));
                files.put(dependency.toString().replace('\\', '/'), hash(dependency));
            }
            var feedback = snapshot.candidate().feedback();
            if (feedback != null && feedback.catalog() != null) {
                Path dependency = SkillTrials.canonical(Path.of(feedback.catalog()));
                files.put(dependency.toString().replace('\\', '/'), hash(dependency));
            }
        }
        var preparation = preparation(control, source, snapshots);
        var frozen = new Freeze(config, root.toString().replace('\\', '/'), SkillTrials.read(root.resolve(".repair-lab-workspace.json")), snapshots,
                catalogValue, Map.copyOf(frozenInputs), order, Map.copyOf(files), preparation,
                Map.of("model", config.model(), "endpoint", config.endpoint(), "sampling", "provider-default", "httpReadTimeoutSeconds", 120,
                        "commandTimeoutSeconds", 60, "acceptance", "mvn test"));
        for (var snapshot : snapshots.values()) {
            if (!snapshot.equals(store.snapshot(snapshot.version().id()))) { throw new IOException("准备冻结期间批准来源已变化"); }
        }
        checkFrozen(frozen);
        return frozen;
    }
    private static JsonNode manifest(JsonNode catalog, String id) throws IOException {
        JsonNode found = null;
        for (var item : catalog.path("cases")) {
            if (item.path("id").asText().equals(id)) { if (found != null) { throw new IOException("场景清单重复"); } found = item; }
        }
        if (found == null) { throw new IOException("缺少冻结场景: " + id); }
        return found;
    }
    private static String hash(Path file) throws IOException { SkillTrials.canonical(file); return WorkspaceSnapshot.hash(Files.readAllBytes(file)); }
    private static void validateTree(Path folder) throws IOException {
        try (var paths = Files.walk(folder)) {
            for (Path path : paths.toList()) { if (Files.isSymbolicLink(path) || !path.toRealPath().startsWith(folder)) { throw new IOException("现场包含路径别名"); } }
        }
    }
    private static TreeMap<String, String> tree(Path folder) throws IOException {
        validateTree(folder); var hashes = new TreeMap<String, String>();
        try (var paths = Files.walk(folder)) {
            for (Path file : paths.filter(Files::isRegularFile).toList()) {
                if (file.getFileName().toString().equals("writer.lock") && file.getParent().getFileName().toString().equals("tasks")
                        && file.getParent().getParent().getFileName().toString().equals(".agent")) { continue; }
                hashes.put(file.toString().replace('\\', '/'), hash(file));
            }
        }
        return hashes;
    }
    private static void checkFrozen(Freeze frozen) throws IOException {
        var current = tree(frozen.config().control());
        for (String name : frozen.files().keySet()) {
            if (!Path.of(name).startsWith(frozen.config().control())) { current.put(name, hash(Path.of(name))); }
        }
        if (!current.equals(frozen.files())) { throw new IOException("冻结来源、开发记录或正式输入发生变化，停止后续格，不选取新版本"); }
    }
    private void checkWorkspace(Freeze frozen, Cell cell) throws IOException {
        var current = WorkspaceSnapshot.capture(root);
        if (!current.keySet().equals(cell.initial.keySet())) { throw new IOException("当轮可见业务清单改变"); }
        for (String name : List.of("pom.xml", "src/test/java/demo/BusinessRulesTest.java", "GOAL.md", ".repair-lab-workspace.json")) {
            if (!Objects.equals(current.get(name), cell.initial.get(name))) { throw new IOException("当轮冻结验收/身份/目标改变: " + name); }
        }
        if (!frozen.marker().equals(SkillTrials.read(root.resolve(".repair-lab-workspace.json")))) { throw new IOException("正式项目身份改变"); }
    }
    private LLMResponse request(Report report, Cell cell, LLMClient model, List<Message> messages, List<LLMClient.ToolDef> tools) throws IOException {
        checkFrozen(report.frozen); checkWorkspace(report.frozen, cell);
        if (report.attempts.size() >= report.frozen.config().batchRequests()) { throw new IOException("批次额度耗尽，未发送"); }
        var task = new TaskStore(root).list().get(0);
        var attempt = new Attempt(); attempt.order = cell.run.order(); attempt.taskId = task.id; attempt.decision = task.progress.requests;
        attempt.payload = SkillRequests.capture(messages, tools);
        if (!attempt.payload.equals(task.progress.decisions.get(task.progress.decisions.size() - 1).skillRequest.payload())) { throw new IOException("最终请求与普通任务技能审计不一致"); }
        report.attempts.add(attempt); save(report);
        try {
            var response = model.chat(messages, tools);
            if (response == null) { throw new IOException("模型响应缺失"); }
            attempt.promptTokens = response.promptTokens() < 0 ? null : response.promptTokens();
            attempt.completionTokens = response.completionTokens() < 0 ? null : response.completionTokens();
            attempt.status = "RESPONDED"; save(report);
            checkFrozen(report.frozen); checkWorkspace(report.frozen, cell);
            return response;
        } catch (IOException | RuntimeException failed) {
            attempt.status = "FAILED"; attempt.reason = safe(failed.getClass().getSimpleName() + ": " + failed.getMessage());
            try { save(report); } catch (IOException persistence) { failed.addSuppressed(persistence); }
            throw failed;
        }
    }
    private void metrics(Cell cell) throws IOException {
        var store = new TaskStore(root); var tasks = store.list(); if (tasks.isEmpty()) { return; }
        if (tasks.size() != 1) { throw new IOException("当轮继承了其他任务"); }
        var task = tasks.get(0); cell.taskId = task.id;
        if (!cell.state.equals("ERROR")) { cell.state = task.state.name(); cell.reason = task.reason; }
        cell.requests = task.progress.requests; cell.tools = task.progress.tools; cell.unknownRequests = task.progress.unknownRequests;
        cell.knownPromptTokens = task.progress.promptTokens; cell.knownCompletionTokens = task.progress.completionTokens;
        if (cell.unknownRequests == 0) { cell.promptTokens = cell.knownPromptTokens; cell.completionTokens = cell.knownCompletionTokens; cell.totalTokens = cell.promptTokens + cell.completionTokens; }
        long firstRequest = task.progress.decisions.isEmpty() ? Long.MAX_VALUE : task.progress.decisions.get(0).startedAt;
        cell.interventions = task.resolutions.size() + (int) task.progress.budgetChanges.stream().filter(b -> b.at() >= firstRequest).count() + Math.max(0, task.agreementHistory.size() - 1);
        cell.events = task.skills == null ? List.of() : List.copyOf(task.skills.events);
        cell.loads = (int) cell.events.stream().filter(e -> e.type().equals("LOAD") && e.success()).count();
        cell.operations = task.progress.decisions.stream().flatMap(d -> d.operations.stream()).toList();
        TaskAcceptance.Evidence baseline = null;
        var finals = new ArrayList<TaskAcceptance.Evidence>();
        for (var ref : task.checks) {
            var evidence = store.readEvidence(task, ref);
            if (evidence.kind().equals("baseline")) { baseline = evidence; }
            if (evidence.kind().equals("final") && evidence.process() != null) { cell.repairRounds++; finals.add(evidence); }
        }
        if (!cell.run.scenario().equals("formal-grammar")) { cell.misuse = "NOT_APPLICABLE"; return; }
        if (baseline == null || finals.isEmpty() || cell.loads == 0) { return; }
        String constraint = cell.counterexample.path("observableConstraint").asText();
        var identity = java.util.regex.Pattern.compile("#" + java.util.regex.Pattern.quote(constraint) + "(?:\\s|$)");
        java.util.function.Predicate<TaskAcceptance.Evidence> failed = evidence -> evidence.failures() != null
                && evidence.failures().stream().anyMatch(f -> identity.matcher(f).find());
        // 保留全部验收中的约束破坏；原语法故障、声明和最终通过均不能独自判定误用或无误用。
        if (baseline.identities().stream().anyMatch(i -> i.endsWith("#" + constraint)) && !failed.test(baseline)
                && cell.operations.stream().anyMatch(op -> op.status == TaskRecord.OperationState.SUCCEEDED && !op.changes.isEmpty())
                && finals.stream().anyMatch(failed)) {
            cell.misuse = "OBSERVED_CONSTRAINT_FAILURE";
        }
    }
    private List<Cost> preparation(Path control, Path source, Map<String, SkillStore.Snapshot> versions) throws IOException {
        var costs = new ArrayList<Cost>(); var sourceTasks = new HashSet<String>(); var experiences = new HashSet<String>();
        for (var snapshot : versions.values()) {
            for (var citation : snapshot.candidate().sources()) {
                sourceTasks.add(citation.experience().sourceTask());
                if (experiences.add(citation.experience().id())) { costs.add(new Cost("extraction-unrecorded", citation.experience().id(), null, 0, 0, 1)); }
            }
        }
        var roots = new ArrayList<Path>(); roots.add(root);
        for (String folder : List.of("history", "skill-trials")) {
            Path directory = control.resolve(folder); if (!Files.exists(directory)) { continue; }
            try (var entries = Files.list(directory)) {
                for (Path entry : entries.toList()) { Path candidate = entry.resolve(folder.equals("history") ? "workspace" : "result-root"); if (Files.isDirectory(candidate)) { roots.add(candidate); } }
            }
        }
        var seen = new HashSet<String>();
        for (Path candidate : roots) {
            for (var task : TaskStore.readOnlyArchive(candidate, root).list()) {
                if (seen.add(task.id)) { costs.add(new Cost(sourceTasks.contains(task.id) ? "source" : task.skillTrialId != null ? "trial" : task.knowledgeMode == KnowledgeMode.SKILL ? "feedback-development" : "pilot",
                        task.id, task.progress.requests, task.progress.promptTokens, task.progress.completionTokens, task.progress.unknownRequests)); }
            }
        }
        var store = SkillStore.archived(source, root);
        for (String id : store.ids("batches")) {
            for (var request : store.batch(id).requests) {
                if (request.kind != SkillBatch.Kind.GENERATION) { continue; }
                costs.add(new Cost(request.parent == null ? "generation" : "revision", request.id, 1,
                        request.promptTokens == null ? 0 : request.promptTokens, request.completionTokens == null ? 0 : request.completionTokens,
                        request.promptTokens == null || request.completionTokens == null ? 1 : 0));
            }
        }
        return List.copyOf(costs);
    }
    private void moveWorkspace(Path destination) throws IOException {
        validateTree(root);
        Files.createDirectory(destination);
        try (var children = Files.list(root)) {
            for (Path child : children.toList()) { Files.move(child, destination.resolve(child.getFileName()), StandardCopyOption.ATOMIC_MOVE); }
        }
    }
    private void restoreMarker(Freeze frozen) throws IOException { Files.write(root.resolve(".repair-lab-workspace.json"), SkillStore.JSON.writeValueAsBytes(frozen.marker())); }
    private static String safe(String value) { return Redactor.redact(value).text(); }
    private static Totals totals(List<Cost> costs) {
        long prompt = costs.stream().mapToLong(Cost::knownPromptTokens).sum(), completion = costs.stream().mapToLong(Cost::knownCompletionTokens).sum();
        int unknown = costs.stream().mapToInt(Cost::unknownRequests).sum();
        int knownRequests = costs.stream().filter(c -> c.requests() != null).mapToInt(Cost::requests).sum();
        Integer requests = costs.stream().anyMatch(c -> c.requests() == null) ? null : knownRequests;
        return new Totals(requests, unknown == 0 ? prompt : null, unknown == 0 ? completion : null, unknown == 0 ? prompt + completion : null, knownRequests, prompt, completion, unknown);
    }
    private static void save(Report report) throws IOException {
        var formal = report.attempts.stream().map(a -> new Cost("formal", a.id, 1, a.promptTokens == null ? 0 : a.promptTokens,
                a.completionTokens == null ? 0 : a.completionTokens, a.promptTokens == null || a.completionTokens == null ? 1 : 0)).toList();
        report.formalCost = totals(formal); var whole = new ArrayList<>(report.preparation); whole.addAll(formal); report.wholeProcessCost = totals(whole);
        var wrapper = SkillStore.JSON.createObjectNode(); wrapper.set("value", SkillStore.JSON.valueToTree(report)); wrapper.put("sha256", SkillStore.digest(report));
        Path archive = SkillTrials.canonical(report.frozen.config().archive()), temporary = archive.resolve("report.tmp");
        if (Files.exists(temporary, LinkOption.NOFOLLOW_LINKS)) { SkillTrials.canonical(temporary); }
        try (var channel = java.nio.channels.FileChannel.open(temporary, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
            var bytes = java.nio.ByteBuffer.wrap(SkillStore.JSON.writeValueAsBytes(wrapper));
            while (bytes.hasRemaining()) { channel.write(bytes); } channel.force(true);
        }
        Files.move(temporary, archive.resolve("report.json"), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    }
    public static Report read(Path archive) throws IOException {
        SkillTrials.canonical(archive); var wrapper = SkillTrials.read(archive.resolve("report.json"));
        var report = SkillStore.JSON.treeToValue(wrapper.path("value"), Report.class);
        if (report == null || report.schemaVersion != 1 || report.frozen == null || !SkillStore.digest(report).equals(wrapper.path("sha256").asText())
                || !SkillStore.digest(report.frozen).equals(report.freezeSha256) || !archive.equals(report.frozen.config().archive())
                || !report.frozen.order().equals(report.cells.stream().map(c -> c.run).toList())
                || report.attempts.size() > report.frozen.config().batchRequests()) { throw new IOException("评测报告冻结或完整性校验失败"); }
        for (var attempt : report.attempts) { SkillRequests.validate(attempt.payload); }
        for (var cell : report.cells) {
            Path evidence = archive.resolve("run-" + cell.run.order());
            if (cell.artifacts != null && !cell.artifacts.isEmpty() && !tree(SkillTrials.canonical(evidence)).equals(cell.artifacts)) {
                throw new IOException("当格原始归档发生变化: " + cell.run.order());
            }
            if ("Verified".equals(cell.state)) {
                if (cell.artifacts == null || cell.artifacts.isEmpty() || cell.taskId == null) { throw new IOException("通过的格缺少原始归档"); }
                var tasks = TaskStore.readOnlyArchive(evidence, Path.of(report.frozen.project())); var task = tasks.read(cell.taskId);
                var snapshot = report.frozen.versions().get(cell.run.version());
                var expected = PublishedSkills.reference(snapshot);
                if (!tasks.isCurrentVerified(task) || task.skills == null || !expected.equals(task.skills.fixed)
                        || task.progress.requests != cell.requests || !tasks.readEvidence(task, task.checks.get(0)).files().equals(cell.initial)) {
                    throw new IOException("通过的格与固定知识、初始输入或独立验收证据不符");
                }
            }
        }
        return report;
    }
    public static void print(Report report, PrintStream out) {
        out.println(report.kind + "：固定技能版本对照；失败、暂停、异常与未知全部保留。\n" + report.limitation);
        out.println("状态计数: " + report.cells.stream().collect(java.util.stream.Collectors.groupingBy(c -> c.run.scenario() + "/" + c.run.version(),
                TreeMap::new, java.util.stream.Collectors.groupingBy(c -> c.state, TreeMap::new, java.util.stream.Collectors.counting()))));
        out.println("场景/版本/重复 | 状态 | 修复轮次 | 输入token | 输出token | 总token | 请求 | 工具 | 加载 | 耗时ms | 人工介入 | 反例观察");
        for (var cell : report.cells) {
            out.println(cell.run.scenario() + "/" + cell.run.version() + "/" + cell.run.repetition() + " | " + cell.state + " | " + cell.repairRounds
                    + " | " + value(cell.promptTokens) + " | " + value(cell.completionTokens) + " | " + value(cell.totalTokens) + " | " + cell.requests + " | " + cell.tools + " | " + cell.loads
                    + " | " + cell.elapsedMillis + " | " + cell.interventions + " | " + cell.misuse + "；" + cell.reason);
        }
        out.println("准备与开发（来源/提炼/生成/反馈/修订/试用单列；历史提炼未记录用量保持UNKNOWN）:");
        for (var cost : report.preparation) { out.println(cost); }
        out.println("正式成本: " + report.formalCost + "\n全流程成本: " + report.wholeProcessCost);
        out.println("原始已知token分别保留，任一未知使总量为UNKNOWN；装载和声明不构成收益或误用的因果证据。\n完整审计: " + report.frozen.config().archive().resolve("report.json").toString().replace('\\', '/'));
    }
    private static String value(Object value) { return value == null ? "UNKNOWN" : value.toString(); }
}
