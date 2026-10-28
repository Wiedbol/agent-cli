package com.agent.skill;

import com.agent.experience.Experience;
import com.agent.session.Redactor;
import com.agent.task.*;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** 登记程序观察到的事实；待审查理由不代表技能好坏，也不授权自动提炼或发布。 */
public final class SkillFeedback {
    public record Entry(int schemaVersion, String id, String project, String kind, String taskId,
                        SkillTask.Reference version, Experience experience, JsonNode facts, List<String> reviewReasons) { }
    public record Selection(List<String> ids, Path source, Path catalog) { }
    public record Input(Entry entry, String sha256, String origin, String control) { }
    public record Inputs(List<Input> items, String catalog, String catalogSha256) { }
    private final Path root;
    private final SkillStore store;
    public SkillFeedback(Path root, Path control, Path source) throws IOException {
        this.root = root.toRealPath();
        if ((control == null) != (source == null)) { throw new IOException("反馈归档需要成对的控制与来源路径"); }
        store = source == null ? new SkillStore(root) : new SkillTrials(root, new PrintStream(OutputStream.nullOutputStream())).library(control, source);
    }
    private static String id(String project, String kind, String task, String subject) {
        return UUID.nameUUIDFromBytes((project + "\n" + kind + "\n" + task + "\n" + subject).getBytes(StandardCharsets.UTF_8)).toString();
    }
    private void save(Entry entry) throws IOException {
        if (store.exists("feedback", entry.id()) && entry.equals(read(entry.id()))) { return; }
        store.write("feedback", entry.id(), entry);
    }
    public Entry read(String id) throws IOException {
        var entry = store.read("feedback", id, Entry.class);
        validate(entry, store.project());
        if (!id.equals(entry.id())) { throw new IOException("反馈标识不符"); }
        return entry;
    }
    static void validate(Entry entry, String project) throws IOException {
        if (entry == null || entry.schemaVersion() != 1 || !project.equals(entry.project())
                || entry.kind() == null || !Set.of("SOURCE", "USE").contains(entry.kind()) || entry.facts() == null || !entry.facts().isObject()
                || entry.reviewReasons() == null || !entry.facts().path("state").isTextual()
                || !entry.facts().path("operations").isArray() || !entry.facts().path("checks").isArray()
                || !entry.facts().path("initialFiles").isObject() || !entry.facts().path("requests").isIntegralNumber()
                || !entry.facts().path("unknownRequests").isIntegralNumber() || !entry.facts().path("lab").isBoolean()
                || !entry.facts().has("manifest") || !entry.facts().path("declarations").isArray()
                || entry.facts().path("requests").asLong() < 0 || entry.facts().path("unknownRequests").asLong() < 0
                || entry.facts().path("unknownRequests").asLong() > entry.facts().path("requests").asLong()
                || entry.facts().path("initialFiles").has(".repair-lab-workspace.json") && !entry.facts().path("lab").asBoolean()
                || !Set.of("Paused", "Verified", "Abandoned").contains(entry.facts().path("state").asText())) { throw new IOException("反馈事实字段缺失或项目不符"); }
        SkillStore.requireId(entry.taskId());
        if (entry.version() != null) {
            SkillStore.requireId(entry.version().skillId()); SkillStore.requireId(entry.version().versionId());
            for (String hash : Arrays.asList(entry.version().versionSha256(), entry.version().candidateSha256(), entry.version().guideSha256())) {
                if (hash == null || !hash.matches("[a-f0-9]{64}")) { throw new IOException("反馈版本摘要损坏"); }
            }
        }
        String subject = entry.version() == null ? "unloaded" : entry.version().versionId();
        if (entry.kind().equals("SOURCE")) {
            if (entry.experience() == null || entry.version() != null || !project.equals(entry.experience().project())
                    || !entry.taskId().equals(entry.experience().sourceTask()) || !entry.experience().enabled()
                    || entry.experience().confirmedAt() <= 0) { throw new IOException("待提炼项不是已确认经验"); }
            subject = entry.experience().id();
        } else if (entry.experience() != null) { throw new IOException("使用事实不能冒充已确认经验"); }
        if (!id(project, entry.kind(), entry.taskId(), subject).equals(entry.id())) { throw new IOException("反馈去重身份损坏"); }
    }
    static Inputs freeze(Path root, Path control, Selection selected, SkillCandidate.Parent parent) throws IOException {
        if (selected == null || selected.ids() == null || selected.ids().isEmpty() || selected.ids().size() > 10
                || new HashSet<>(selected.ids()).size() != selected.ids().size()) { throw new IOException("需显式选择一至十条不同反馈"); }
        var origin = selected.source() == null ? root.toRealPath() : SkillTrials.canonical(selected.source());
        var feedback = new SkillFeedback(root, selected.source() == null ? null : control, selected.source());
        var items = new ArrayList<Input>();
        for (String id : selected.ids()) {
            var entry = feedback.read(id);
            items.add(new Input(entry, SkillStore.digest(entry), origin.toString().replace('\\', '/'), control == null ? null : control.toRealPath().toString().replace('\\', '/')));
        }
        Path catalog = selected.catalog() == null ? null : SkillTrials.canonical(selected.catalog());
        var inputs = new Inputs(List.copyOf(items), catalog == null ? null : catalog.toString().replace('\\', '/'), catalog == null ? null : SkillStore.digest(SkillTrials.read(catalog)));
        validateInputs(root, inputs, parent);
        return inputs;
    }
    /** 选择事实在请求与候选中固定；后续任务更新同一待办不会改写已选材料。 */
    static void validateInputs(Path root, Inputs inputs, SkillCandidate.Parent parent) throws IOException {
        try { checkInputs(root, inputs, parent); }
        catch (InvalidPathException invalid) { throw new IOException("反馈快照路径不合法", invalid); }
    }
    private static void checkInputs(Path root, Inputs inputs, SkillCandidate.Parent parent) throws IOException {
        String project = root.toRealPath().toString().replace('\\', '/');
        if (inputs == null || inputs.items() == null || inputs.items().isEmpty() || inputs.items().size() > 10
                || (inputs.catalog() == null) != (inputs.catalogSha256() == null)) { throw new IOException("修订反馈快照缺失"); }
        JsonNode catalog = null;
        if (inputs.catalog() != null) {
            catalog = SkillTrials.read(Path.of(inputs.catalog()));
            if (!"repair-lab-2".equals(catalog.path("version").asText()) || !SkillStore.digest(catalog).equals(inputs.catalogSha256())) {
                throw new IOException("反馈开发清单改变");
            }
        }
        var seen = new HashSet<String>();
        for (var input : inputs.items()) {
            if (input == null || input.origin() == null || input.entry() == null || !seen.add(input.entry().id())) { throw new IOException("反馈输入重复或缺失"); }
            var entry = input.entry(); validate(entry, project);
            if (!SkillStore.digest(entry).equals(input.sha256())) { throw new IOException("选定反馈摘要改变"); }
            if (entry.kind().equals("USE") && (parent == null || entry.version() == null
                    || !entry.version().skillId().equals(parent.skillId()) || !entry.version().versionId().equals(parent.versionId())
                    || !entry.version().versionSha256().equals(parent.sha256()))) { throw new IOException("不能把其他技能/版本的反馈合并到目标父版本"); }
            Path origin = Path.of(input.origin());
            if (entry.kind().equals("SOURCE")) {
                com.agent.experience.ExperienceLibrary library;
                if (origin.equals(root.toRealPath())) {
                    if (entry.facts().path("lab").asBoolean()) { throw new IOException("Lab确认经验需先保留可信来源归档，再选择反馈修订"); }
                    library = new com.agent.experience.ExperienceLibrary(root);
                }
                else {
                    if (input.control() == null) { throw new IOException("归档反馈缺少控制来源"); }
                    new SkillTrials(root, new PrintStream(OutputStream.nullOutputStream())).library(Path.of(input.control()), origin);
                    library = com.agent.experience.ExperienceLibrary.readOnlyArchive(origin, root);
                }
                var current = library.read(entry.experience().id());
                if (!current.equals(entry.experience()) || !current.enabled() || !library.sourceValid(current)) { throw new IOException("反馈经验未确认、停用或来源失效"); }
            }
            if (entry.facts().path("lab").asBoolean()) {
                if (catalog == null) { throw new IOException("Lab反馈需要显式冻结开发清单"); }
                JsonNode manifest = entry.facts().path("manifest");
                if (manifest.isNull() && !origin.equals(root.toRealPath())) { manifest = SkillTrials.read(origin.getParent().resolve("manifest.json")); }
                String scenario = manifest.path("id").asText();
                var allowed = new HashSet<String>(); var formal = new HashSet<String>();
                for (String stage : entry.kind().equals("SOURCE") ? List.of("source", "initial-development", "feedback-development", "required-regression")
                        : List.of("initial-development", "feedback-development", "required-regression")) {
                    catalog.path("stages").path(stage).forEach(value -> allowed.add(value.asText()));
                }
                catalog.path("stages").path("formal").forEach(value -> formal.add(value.asText()));
                boolean exact = false;
                for (var item : catalog.path("cases")) { if (item.equals(manifest)) { exact = true; } }
                if (!exact || !allowed.contains(scenario) || formal.contains(scenario) || scenario.startsWith("formal-")
                        || !manifest.path("initial").equals(businessInitial(entry.facts().path("initialFiles")))) {
                    throw new IOException("正式、未知或输入不符的场景不能用于修订");
                }
            }
        }
    }
    private JsonNode facts(TaskRecord task) throws IOException {
        var facts = SkillStore.JSON.createObjectNode();
        facts.put("state", task.state.name()); facts.put("goal", task.goal); facts.put("reason", task.reason);
        facts.put("requests", task.progress.requests); facts.put("unknownRequests", task.progress.unknownRequests);
        facts.put("promptTokens", task.progress.promptTokens); facts.put("completionTokens", task.progress.completionTokens);
        facts.put("lab", Files.exists(root.resolve(".repair-lab-workspace.json")));
        var operations = facts.putArray("operations");
        for (var decision : task.progress.decisions) {
            for (var operation : decision.operations) {
                var observed = operations.addObject(); observed.put("id", operation.reference(decision.number));
                observed.put("tool", operation.tool); observed.put("arguments", Redactor.redact(operation.arguments, operation.arguments).text()); observed.put("status", operation.status.name());
                observed.set("changes", SkillStore.JSON.valueToTree(operation.changes));
            }
        }
        var checks = facts.putArray("checks");
        var tasks = new TaskStore(root);
        facts.set("initialFiles", SkillStore.JSON.createObjectNode());
        for (var reference : task.checks) {
            var evidence = tasks.readEvidence(task, reference);
            var checked = checks.addObject(); checked.put("id", reference.id()); checked.put("sha256", reference.sha256());
            checked.put("kind", evidence.kind()); checked.put("verdict", evidence.verdict());
            if (checks.size() == 1) {
                facts.set("initialFiles", SkillStore.JSON.valueToTree(evidence.files()));
                if (evidence.files().containsKey(".repair-lab-workspace.json")) { facts.put("lab", true); }
            }
        }
        facts.set("declarations", SkillStore.JSON.valueToTree(task.skills == null ? List.of() : task.skills.events.stream()
                .filter(event -> event.type().equals("DECLARATION")).toList()));
        facts.set("finalFiles", SkillStore.JSON.valueToTree(task.files));
        facts.set("interventions", SkillStore.JSON.valueToTree(task.resolutions.stream().map(resolution -> Map.of(
                "action", resolution.action.name(), "target", resolution.target, "cancelledOperations", resolution.cancelledOperations)).toList()));
        facts.putNull("manifest");
        if (task.skills != null && task.skills.control != null) {
            try {
                var manifest = SkillTrials.read(Path.of(task.skills.control).resolve("current.json"));
                if (manifest.path("initial").equals(businessInitial(facts.path("initialFiles")))) { facts.set("manifest", manifest); }
            } catch (IOException unavailable) { /* 无法证明开发场景时保留未知，选择修订输入将拒绝。 */ }
        }
        return SkillStore.JSON.readTree(Redactor.redact(SkillStore.JSON.writeValueAsString(facts)).text());
    }
    private static JsonNode businessInitial(JsonNode files) {
        var initial = files.deepCopy();
        if (initial.isObject()) { ((com.fasterxml.jackson.databind.node.ObjectNode) initial).remove(".repair-lab-workspace.json"); }
        return initial;
    }
    /** 调用者已持有任务写锁并完成确认；不重复提炼或隐式获取模型。 */
    public static void confirmed(Path root, Experience experience, TaskRecord task) throws IOException {
        var feedback = new SkillFeedback(root, null, null);
        var entry = new Entry(1, id(feedback.store.project(), "SOURCE", task.id, experience.id()), feedback.store.project(),
                "SOURCE", task.id, null, experience, feedback.facts(task), List.of("已确认的合格经验，待用户选择提炼"));
        validate(entry, feedback.store.project()); feedback.save(entry);
    }
    /** 沿用调用方已持有的写锁，任务事实先持久化再登记；登记失败不篡改独立验收结果。 */
    public static void ended(Path root, TaskRecord task) throws IOException {
        if (task.knowledgeMode != KnowledgeMode.SKILL || task.skillTrialId != null || task.skills == null
                || !Set.of(TaskRecord.State.Verified, TaskRecord.State.Paused, TaskRecord.State.Abandoned).contains(task.state)) { return; }
        var feedback = new SkillFeedback(root, null, null);
        var facts = feedback.facts(task);
        var versions = new ArrayList<SkillTask.Reference>(task.skills.loaded);
        if (versions.isEmpty()) { versions.add(null); }
        for (var version : versions) {
            var reasons = new ArrayList<String>();
            if (version == null) { reasons.add("未加载技能，不能归因技能效果"); }
            else {
                var declarations = task.skills.events.stream().filter(event -> event.type().equals("DECLARATION")
                        && version.versionId().equals(event.versionId())).toList();
                if (declarations.isEmpty()) { reasons.add("已加载但未声明，待审查"); }
                if (declarations.stream().anyMatch(event -> event.detail().startsWith("REJECT:"))) { reasons.add("模型声明拒绝，需人工审查原因"); }
            }
            if (task.state != TaskRecord.State.Verified) { reasons.add("未验证完成，不能判定技能有害"); }
            if (task.progress.decisions.stream().flatMap(decision -> decision.operations.stream()).anyMatch(operation -> !operation.changes.isEmpty())) {
                reasons.add("实际修改路径待与技能流程对照，不自动判定收益");
            }
            var entry = new Entry(1, id(feedback.store.project(), "USE", task.id, version == null ? "unloaded" : version.versionId()),
                    feedback.store.project(), "USE", task.id, version, null, facts, List.copyOf(reasons));
            validate(entry, feedback.store.project()); feedback.save(entry);
        }
    }
    /** 升级或登记中断后显式补齐；读取列表仍然完全只读，不在查询时隐式补写。 */
    public static void registerExisting(Path root) throws IOException {
        var tasks = new TaskStore(root);
        try (var writer = tasks.lock()) {
            for (var task : tasks.list()) { ended(root, task); }
            var library = new com.agent.experience.ExperienceLibrary(root);
            for (var experience : library.list()) {
                if (experience.enabled() && library.sourceValid(experience)) { confirmed(root, experience, tasks.read(experience.sourceTask())); }
            }
        }
    }
    public void list(PrintStream out) throws IOException {
        var ids = store.ids("feedback");
        if (ids.isEmpty()) { out.println("暂无技能反馈事项。"); }
        for (String id : ids) {
            var entry = read(id);
            out.println("技能反馈: " + id + "\n类别: " + (entry.kind().equals("SOURCE") ? "待提炼" : "待审查")
                    + "\n来源任务: " + entry.taskId() + "\n加载版本: " + entry.version()
                    + "\n经验: " + (entry.experience() == null ? "未确认/未提炼" : entry.experience().id())
                    + "\n观察事实: " + SkillStore.JSON.writeValueAsString(entry.facts())
                    + "\n审查提示（非因果结论）: " + entry.reviewReasons());
        }
    }
}
