package com.agent.skill;

import com.agent.llm.*;
import com.agent.task.*;
import com.agent.tool.*;
import java.io.*;
import java.nio.file.Path;
import java.util.*;

/** 普通任务只发现已批准默认版本；已读引用固定，不由后续发布替换。 */
public final class PublishedSkills implements SkillKnowledge {
    private final Path root;
    private final PrintStream out;
    private SkillTask state;
    private TaskRecord task;
    public PublishedSkills(Path root, PrintStream out, Path control, Path source) throws IOException {
        this.root = root.toRealPath(); this.out = out; state = new SkillTask();
        if ((control == null) != (source == null)) { throw new IOException("技能库控制与来源必须成对指定"); }
        if (source != null) { state.control = SkillTrials.canonical(control).toString(); state.source = SkillTrials.canonical(source).toString(); }
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
    @Override public boolean isProtected(Path path) { return state.source != null && path.equals(root.resolve(".repair-lab-workspace.json")); }
    private static SkillTask.Reference reference(SkillStore.Snapshot snapshot) throws IOException {
        var version = snapshot.version();
        return new SkillTask.Reference(version.skillId(), version.id(), SkillStore.digest(version), version.candidateSha256(),
                WorkspaceSnapshot.hash(snapshot.candidate().guide().markdown().getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    }
    private SkillStore.Snapshot resolve(SkillTask.Reference reference) throws IOException {
        var snapshot = store().snapshot(reference.versionId());
        if (!reference.equals(reference(snapshot))) { throw new IOException("固定技能版本或摘要已改变"); }
        return snapshot;
    }
    private Map<SkillTask.Reference, SkillStore.Snapshot> checkedSnapshots() {
        try {
            store();
            var snapshots = new LinkedHashMap<SkillTask.Reference, SkillStore.Snapshot>();
            for (var pin : state.loaded) {
                var snapshot = resolve(pin);
                String failure = SkillMatching.conditionFailure(snapshot.candidate().guide().conditions(), task.files);
                if (failure != null) { throw new IOException(failure); }
                snapshots.put(pin, snapshot);
            }
            return snapshots;
        } catch (IOException invalid) { throw new TaskProgress.Pause("固定技能不可用，保留现场: " + invalid.getMessage()); }
    }
    @Override public void check() { checkedSnapshots(); }
    private List<SkillStore.Snapshot> defaults() throws IOException {
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
    @Override public String discover(TaskRecord task, TaskAcceptance.Evidence evidence) {
        if (!"FAILED".equals(evidence.verdict()) || !evidence.codingAllowed()) { return ""; }
        check();
        String failure = task.checks.get(task.checks.size() - 1).id();
        if (state.failures.contains(failure)) { return ""; }
        state.failures.add(failure);
        record Match(SkillStore.Snapshot snapshot, Set<String> reasons) { }
        var matches = new ArrayList<Match>();
        try {
            for (var snapshot : defaults()) {
                if (state.loaded.stream().anyMatch(pin -> pin.skillId().equals(snapshot.version().skillId()))) { continue; }
                if (SkillMatching.conditionFailure(snapshot.candidate().guide().conditions(), task.files) != null) { continue; }
                var reasons = SkillMatching.matches(snapshot.candidate().guide(), task.goal + " " + evidence.details());
                if (!reasons.isEmpty()) { matches.add(new Match(snapshot, reasons)); }
            }
            var selected = matches.stream().sorted(Comparator.<Match>comparingInt(m -> m.reasons().size()).reversed()
                    .thenComparing(m -> m.snapshot().version().skillId())).limit(3).toList();
            var directory = new StringBuilder();
            for (var match : selected) {
                var pin = reference(match.snapshot());
                if (!state.offered.contains(pin)) { state.offered.add(pin); }
                var guide = match.snapshot().candidate().guide();
                directory.append("\n修复技能目录 ").append(pin.skillId()).append('@').append(pin.versionId())
                        .append("\n摘要: ").append(shortText(guide.title())).append("\n适用: ").append(shortText(guide.conditions()))
                        .append("\n限制: ").append(shortText(guide.exclusions())).append("\n内容摘要: ").append(pin.candidateSha256())
                        .append("\n匹配原因: 失败/目标关键词 ").append(match.reasons()).append("；来源已核验，可不选。用 load_skill(id,version) 加载后再声明 ACCEPT/REJECT。");
                event("CATALOG", pin, true, match.reasons().toString());
            }
            if (selected.isEmpty()) { event("NO_MATCH", null, true, "无相关可用技能，继续独立修复"); }
            return directory.toString();
        } catch (IOException invalid) { throw new TaskProgress.Pause("技能发现或审计失败: " + invalid.getMessage()); }
    }
    private static String shortText(String text) { return text.length() <= 240 ? text : text.substring(0, 240) + "…"; }
    private SkillTask.Reference selection(ToolRequest request, int fields) {
        check();
        if (!request.arguments().isObject() || request.arguments().size() != fields || request.arguments().properties().stream().anyMatch(p -> !p.getValue().isTextual())) {
            throw new IllegalArgumentException("技能工具只接受目录中规定的文本参数");
        }
        String id = request.requireString("id"), version = request.requireString("version");
        var pin = state.offered.stream().filter(p -> p.skillId().equals(id) && p.versionId().equals(version)).findFirst();
        if (pin.isEmpty()) { event("LOAD_DENIED", null, false, id + "@" + version); throw new SecurityException("本任务目录不允许该技能或未批准版本"); }
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
            return body(pin, guide);
        } catch (IOException unavailable) { throw new TaskProgress.Pause("技能加载前提失效: " + unavailable.getMessage()); }
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
