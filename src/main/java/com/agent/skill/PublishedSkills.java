package com.agent.skill;

import com.agent.llm.*;
import com.agent.task.*;
import com.agent.tool.*;
import java.io.*;
import java.nio.file.Path;
import java.util.*;

/** 普通任务发现本地默认版本和已共享版本，实际加载后固定来源和摘要。 */
public final class PublishedSkills implements SkillKnowledge {
    private final Path root;
    private final PrintStream out;
    private SkillTask state;
    private TaskRecord task;
    private SkillStore.Snapshot frozenSnapshot;
    private FrozenCheck frozenCheck;
    private record Offered(SkillStore.Snapshot snapshot, SharedSkills.Origin origin) { }
    @FunctionalInterface interface FrozenCheck { void check() throws IOException; }
    public PublishedSkills(Path root, PrintStream out, Path control, Path source) throws IOException {
        this.root = root.toRealPath(); this.out = out; state = new SkillTask();
        if ((control == null) != (source == null)) { throw new IOException("技能库控制与来源必须成对指定"); }
        if (source != null) { state.control = SkillTrials.canonical(control).toString(); state.source = SkillTrials.canonical(source).toString(); }
    }
    static PublishedSkills frozen(Path root, PrintStream out, Path control, Path source, SkillStore.Snapshot snapshot, FrozenCheck check) throws IOException {
        var knowledge = new PublishedSkills(root, out, control, source);
        knowledge.state.fixed = reference(snapshot);
        knowledge.frozenSnapshot = snapshot; knowledge.frozenCheck = check;
        return knowledge;
    }
    /** 先恢复已校验的绑定；外部目录失效由任务循环保存暂停原因，不能在进入检查点前丢失现场。 */
    public static PublishedSkills restore(Path root, PrintStream out, SkillTask saved) throws IOException {
        var knowledge = new PublishedSkills(root, out, null, null);
        if (saved != null) { knowledge.state = saved; }
        return knowledge;
    }
    private SkillStore store() throws IOException {
        return state.source == null ? new SkillStore(root) : new SkillTrials(root, out).library(Path.of(state.control), Path.of(state.source));
    }
    @Override public SkillTask state() { return state; }
    @Override public void bind(TaskRecord task) throws IOException {
        if (task.skills == null) { state.firstRequest = task.progress.requests + 1; task.skills = state; task.schemaVersion = 10; }
        else {
            if (!Objects.equals(state.control, task.skills.control) || !Objects.equals(state.source, task.skills.source)) { throw new IOException("恢复不能替换已固定的技能库"); }
            state = task.skills;
        }
        this.task = task; SkillTask.validate(task); new TaskStore(root).save(task);
    }
    @Override public boolean isProtected(Path path) {
        return state.source != null && (path.equals(root.resolve(".repair-lab-workspace.json"))
                || state.fixed != null && path.equals(root.resolve("GOAL.md")));
    }
    static SkillTask.Reference reference(SkillStore.Snapshot snapshot) throws IOException {
        var version = snapshot.version();
        return new SkillTask.Reference(version.skillId(), version.id(), SkillStore.digest(version), version.candidateSha256(),
                WorkspaceSnapshot.hash(snapshot.candidate().guide().markdown().getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    }
    private SkillStore.Snapshot resolve(SkillTask.Reference reference) throws IOException {
        SkillStore.Snapshot snapshot;
        var origin = state.shared.get(reference.versionId());
        if (frozenSnapshot != null && reference.equals(state.fixed)) {
            // 评测已核验真实来源链；仅在整个冻结归档逐文件仍一致时复用，普通恢复仍走完整核验。
            store(); frozenCheck.check(); snapshot = frozenSnapshot;
        } else if (origin != null) {
            snapshot = new SharedSkills().resolve(reference.versionId(), origin, reference.versionSha256(), out).snapshot();
        } else { snapshot = store().snapshot(reference.versionId()); }
        if (!reference.equals(reference(snapshot))) { throw new IOException("固定技能版本或摘要已改变"); }
        return snapshot;
    }
    private Map<SkillTask.Reference, SkillStore.Snapshot> checkedSnapshots() {
        try {
            store();
            var snapshots = new LinkedHashMap<SkillTask.Reference, SkillStore.Snapshot>();
            SkillStore.Snapshot fixed = state.fixed == null ? null : resolve(state.fixed);
            for (var pin : state.loaded) {
                var snapshot = pin.equals(state.fixed) ? fixed : resolve(pin);
                String failure = SkillMatching.conditionFailure(snapshot.candidate().guide().conditions(), task.files);
                if (failure != null) { throw new IOException(failure); }
                snapshots.put(pin, snapshot);
            }
            return snapshots;
        } catch (IOException invalid) { throw new TaskProgress.Pause("固定技能不可用，保留现场: " + invalid.getMessage()); }
    }
    @Override public void check() { checkedSnapshots(); }
    private List<SkillStore.Snapshot> defaults() throws IOException {
        if (state.fixed != null) { return List.of(resolve(state.fixed)); }
        var store = store(); var latest = new TreeMap<String, SkillStore.Version>();
        for (String id : store.ids("versions")) {
            SkillStore.Version version;
            try {
                version = store.metadata(id).version();
            } catch (IOException corrupt) { event("CATALOG_UNAVAILABLE", null, false, "默认版本未知，不回退旧版本: " + id); return List.of(); }
            var previous = latest.get(version.skillId());
            if (previous == null || version.approvedAt() > previous.approvedAt()) { latest.put(version.skillId(), version); }
        }
        var snapshots = new ArrayList<SkillStore.Snapshot>();
        for (var version : latest.values()) {
            try { snapshots.add(store.snapshot(version.id())); }
            catch (IOException unavailable) { event("CATALOG_UNAVAILABLE", null, false, version.id() + ": " + unavailable.getMessage()); }
        }
        return snapshots;
    }

    private List<Offered> catalog() throws IOException {
        var entries = new LinkedHashMap<String, Offered>();
        for (var snapshot : defaults()) { entries.put(snapshot.version().id(), new Offered(snapshot, null)); }
        if (state.fixed == null) {
            try {
                for (var shared : new SharedSkills().available(out)) {
                    entries.putIfAbsent(shared.entry().versionId(), new Offered(shared.snapshot(), shared.entry().origin()));
                }
            } catch (IOException unavailable) {
                event("CATALOG_UNAVAILABLE", null, false, "共享技能索引不可用，继续检查本地技能");
            }
        }
        return List.copyOf(entries.values());
    }

    @Override public String discover(TaskRecord task, TaskAcceptance.Evidence evidence) {
        if (!"FAILED".equals(evidence.verdict()) || !evidence.codingAllowed()) { return ""; }
        check();
        String failure = task.checks.get(task.checks.size() - 1).id();
        if (state.failures.contains(failure)) { return ""; }
        state.failures.add(failure);
        try {
            var directory = new StringBuilder();
            for (var offered : catalog()) {
                var snapshot = offered.snapshot();
                if (state.loaded.stream().anyMatch(pin -> pin.skillId().equals(snapshot.version().skillId()))) { continue; }
                var guide = snapshot.candidate().guide();
                String condition = SkillMatching.conditionFailure(guide.conditions(), task.files);
                if (condition != null) {
                    event("CATALOG_EXCLUDED", reference(snapshot), true, condition);
                    continue;
                }
                var pin = reference(snapshot);
                var previous = state.offered.stream().filter(p -> p.versionId().equals(pin.versionId())).findFirst();
                if (previous.isPresent() && (!previous.get().equals(pin)
                        || !Objects.equals(state.shared.get(pin.versionId()), offered.origin()))) { continue; }
                if (previous.isEmpty()) {
                    state.offered.add(pin);
                    if (offered.origin() != null) { state.shared.put(pin.versionId(), offered.origin()); }
                }
                var source = snapshot.candidate().sources().get(0).experience();
                directory.append("\n修复技能目录\nload_skill 参数: ").append(selectionArguments(pin))
                        .append("\n摘要: ").append(shortText(guide.title())).append("\n适用: ").append(shortText(guide.conditions()))
                        .append("\n限制: ").append(shortText(guide.exclusions())).append("\n内容摘要: ").append(pin.candidateSha256())
                        .append("\n来源案例: ").append(shortText(source.advice().errorFeatures()))
                        .append("；来源任务 ").append(source.sourceTask())
                        .append("\n资格检查通过，适用性待判断。结合当前目标、失败证据、适用范围和排除条件自主选读；")
                        .append("无适用项时继续独立诊断和修复。同任务最多加载三种技能。")
                        .append("按示例的两个独立字段加载，随后用相同 id 和 version 声明 ACCEPT/REJECT。");
                event("CATALOG", pin, true, "来源、版本和文件前提已核验；由 Agent 判断适用性");
            }
            if (directory.isEmpty()) { event("NO_MATCH", null, true, "无可供选读的合格技能，继续独立修复"); }
            return directory.toString();
        } catch (IOException invalid) { throw new TaskProgress.Pause("技能发现或审计失败: " + invalid.getMessage()); }
    }
    private static String shortText(String text) { return text.length() <= 240 ? text : text.substring(0, 240) + "…"; }
    private static String selectionArguments(SkillTask.Reference pin) {
        return "{\"id\":\"" + pin.skillId() + "\",\"version\":\"" + pin.versionId() + "\"}";
    }
    private SkillTask.Reference selection(ToolRequest request, int fields) {
        check();
        if (!request.arguments().isObject() || request.arguments().size() != fields || request.arguments().properties().stream().anyMatch(p -> !p.getValue().isTextual())) {
            throw new IllegalArgumentException("技能工具只接受目录中规定的文本参数");
        }
        String id = request.requireString("id"), version = request.requireString("version");
        var pin = state.offered.stream().filter(p -> p.skillId().equals(id) && p.versionId().equals(version)).findFirst();
        if (pin.isEmpty()) {
            event("LOAD_DENIED", null, false, id + "@" + version);
            String hint = state.offered.size() == 1 ? "，使用 " + selectionArguments(state.offered.get(0)) : "，参考目录中的参数示例";
            throw new SecurityException("本任务目录不允许该技能或未批准版本。请分别填写 id 和 version" + hint);
        }
        return pin.get();
    }
    @Override public String load(ToolRequest request) {
        var pin = selection(request, 2);
        try {
            var snapshot = resolve(pin); var guide = snapshot.candidate().guide();
            String failure = SkillMatching.conditionFailure(guide.conditions(), task.files);
            if (failure != null) { throw new SecurityException(failure); }
            if (state.loaded.contains(pin)) {
                boolean reliable = task.checkpoint.messages.stream().anyMatch(m -> "tool".equals(m.role()) && m.content() != null && m.content().contains(body(pin, guide)));
                if (reliable) { event("LOAD_DUPLICATE", pin, true, "已有可靠结果，不重复加入指南"); return "固定技能已加载，不重复加入上下文"; }
            } else {
                if (state.loaded.size() >= 3 || state.loaded.stream().anyMatch(p -> p.skillId().equals(pin.skillId()))) { throw new SecurityException("同任务最多三种技能，且不能替换固定版本"); }
                state.loaded.add(pin);
            }
            event("LOAD", pin, true, "完整指南由工具返回");
            return body(pin, guide) + "\ndeclare_skill 使用以下 id 和 version：" + selectionArguments(pin);
        } catch (IOException unavailable) {
            if (state.shared.containsKey(pin.versionId()) && !state.loaded.contains(pin)) {
                event(SkillTask.SHARED_LOAD_UNAVAILABLE, pin, false, "共享技能已撤销或来源不可用");
                throw new SecurityException("共享技能已撤销或来源不可用，可继续独立处理");
            }
            throw new TaskProgress.Pause("技能加载前提失效: " + unavailable.getMessage());
        }
    }
    private static String body(SkillTask.Reference pin, SkillCandidate.Guide guide) {
        return "修复技能 " + pin.skillId() + "@" + pin.versionId() + "\n内容摘要: " + pin.candidateSha256() + "\n" + guide.markdown();
    }
    @Override public String declare(ToolRequest request) {
        var pin = selection(request, 4);
        if (!state.loaded.contains(pin)) { throw new IllegalArgumentException("尚未加载不能声明"); }
        String decision = request.requireString("decision"), reason = request.requireString("reason");
        if (!Set.of("ACCEPT", "REJECT").contains(decision) || reason.length() > 2000) { throw new IllegalArgumentException("声明需要 ACCEPT/REJECT 与有限长度原因"); }
        try { SkillStore.requireSafe(reason); } catch (IOException invalid) { throw new IllegalArgumentException(invalid.getMessage()); }
        event("DECLARATION", pin, true, decision + ": " + reason);
        return "已记录模型声明，仍需检查实际修改与独立验收";
    }
    private void event(String type, SkillTask.Reference pin, boolean success, String detail) {
        String call = task.progress.decisions.stream().flatMap(d -> d.operations.stream()).filter(op -> op.status == TaskRecord.OperationState.RUNNING).map(op -> op.callId).findFirst().orElse(null);
        state.events.add(new SkillTask.Event(type, call, pin == null ? null : pin.skillId(), pin == null ? null : pin.versionId(), success, detail));
        save();
    }
    private void save() {
        try { new TaskStore(root).save(task); }
        catch (IOException failed) { throw new TaskProgress.Pause("技能审计保存失败: " + failed.getMessage()); }
    }
    @Override public LLMClient audit(LLMClient model) {
        return (messages, tools) -> {
            var snapshots = checkedSnapshots();
            var payload = SkillRequests.capture(messages, tools);
            var included = new ArrayList<SkillTask.Reference>();
            for (var pin : state.loaded) {
                String guide = body(pin, snapshots.get(pin).candidate().guide());
                if (messages.stream().anyMatch(m -> "tool".equals(m.role()) && m.content() != null && m.content().contains(guide))) { included.add(pin); }
            }
            task.progress.decisions.get(task.progress.decisions.size() - 1).skillRequest = new SkillTask.Request(payload, List.copyOf(included));
            save();
            return model.chat(messages, tools);
        };
    }
}
