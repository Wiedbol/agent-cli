package com.agent.skill;

import com.agent.llm.LLMClient;
import com.agent.task.*;
import com.agent.tool.Tool;
import com.agent.tool.ToolRequest;
import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Pattern;

/** 普通管理任务循环中的发现与加载；客户端包装仅审计，不修改请求消息。 */
public final class SkillKnowledge {
    private final SkillTrials trials;
    private final SkillTrial record;
    private final SkillStore source;
    private final SkillCandidate candidate;
    private TaskRecord task;
    private String catalog = "";
    SkillKnowledge(SkillTrials trials, SkillTrial record, SkillStore source) throws IOException {
        this.trials = trials; this.record = record; this.source = source; candidate = source.candidate(record.candidateId);
    }
    public String trialId() { return record.id; }
    public void bind(TaskRecord task) throws IOException {
        this.task = task; record.taskId = task.id; record.status = "RUNNING"; trials.save(record);
    }
    public List<Tool> tools() { return List.of(new LoadSkillTool(this), new DeclareSkillTool(this)); }
    public boolean isProtected(Path path) {
        Path project = Path.of(record.project);
        return path.equals(project.resolve(".repair-lab-workspace.json")) || path.equals(project.resolve("GOAL.md"));
    }
    public void check() {
        try { trials.check(record, source); }
        catch (IOException failure) { throw new TaskProgress.Pause("技能试用前提失效: " + failure.getMessage()); }
    }
    public String discover(TaskRecord task, TaskAcceptance.Evidence evidence) {
        if (!evidence.verdict().equals("FAILED") || !evidence.codingAllowed()) { throw new TaskProgress.Pause("试用需要有效的独立失败基线"); }
        check();
        if (!catalog.isEmpty()) { return ""; }
        var required = Pattern.compile("(?im)(?:requires|必须存在)\\s*[:：]\\s*([^\\r\\n;；]+)").matcher(candidate.guide().conditions());
        while (required.find()) {
            if (!task.files.containsKey(required.group(1).trim())) { event("CATALOG_EXCLUDED", false, "文件前提不满足"); return ""; }
        }
        var versions = Pattern.compile("(?i)(?<![a-f0-9])[a-f0-9]{64}(?![a-f0-9])").matcher(candidate.guide().conditions());
        while (versions.find()) {
            if (!task.files.containsValue(versions.group().toLowerCase(Locale.ROOT))) { event("CATALOG_EXCLUDED", false, "版本前提不满足"); return ""; }
        }
        catalog = "\n修复技能试用目录（可不选；未启用候选，仅本次可加载）: " + record.candidateId + "@" + record.candidateSha256
                + "\n摘要: " + shortText(candidate.guide().title()) + "\n适用: " + shortText(candidate.guide().conditions())
                + "\n不适用: " + shortText(candidate.guide().exclusions())
                + "\n匹配原因: 操作者指定的开发候选；来源与项目已核验。可用 load_skill 按 id/version 读取；读取后可用 declare_skill 声明 ACCEPT/REJECT 和原因。";
        event("CATALOG", true, "目录已组装，等待请求结果");
        return catalog;
    }
    private static String shortText(String text) { return text.length() <= 240 ? text : text.substring(0, 240) + "…"; }
    private void selection(ToolRequest request, int fields) {
        check();
        if (!request.arguments().isObject() || request.arguments().size() != fields) { throw new IllegalArgumentException("技能工具不接受额外路径或参数"); }
        for (var field : request.arguments().properties()) {
            if (!field.getValue().isTextual()) { throw new IllegalArgumentException("技能工具参数必须为文本"); }
        }
        if (catalog.isEmpty() || !record.candidateId.equals(request.requireString("id"))
                || !record.candidateSha256.equals(request.requireString("version"))) {
            event("LOAD_DENIED", false, "选择与本次授权快照不符");
            throw new SecurityException("本次目录不允许该候选或版本");
        }
    }
    String load(ToolRequest request) {
        selection(request, 2);
        if (record.loaded) { event("LOAD_DUPLICATE", true, "不重复加入指南"); return "同一快照已加载，不重复加入上下文"; }
        record.loaded = true;
        event("LOAD", true, record.candidateId + "@" + record.candidateSha256);
        return "修复技能试用快照 " + record.candidateId + "@" + record.candidateSha256 + "\n" + candidate.guide().markdown();
    }
    String declare(ToolRequest request) {
        selection(request, 4);
        if (!record.loaded) { throw new IllegalArgumentException("尚未加载，不能声明采纳或拒绝"); }
        String decision = request.requireString("decision"), reason = request.requireString("reason");
        if (!Set.of("ACCEPT", "REJECT").contains(decision) || reason.length() > 2000) { throw new IllegalArgumentException("声明需要 ACCEPT/REJECT 与有限长度原因"); }
        try { SkillStore.requireSafe(reason); } catch (IOException bad) { throw new IllegalArgumentException(bad.getMessage()); }
        record.declaration = decision; record.declarationReason = reason;
        event("DECLARATION", true, decision + ": " + reason);
        return "已记录模型声明；声明不代表已遵循流程或产生收益";
    }
    private void event(String type, boolean success, String detail) {
        String callId = task == null ? null : task.progress.decisions.stream().flatMap(d -> d.operations.stream())
                .filter(op -> op.status == TaskRecord.OperationState.RUNNING).map(op -> op.callId).findFirst().orElse(null);
        record.events.add(new SkillTrial.Event(type, callId, success, detail));
        try { trials.save(record); } catch (IOException failed) { throw new TaskProgress.Pause("试用审计保存失败，已停止: " + failed.getMessage()); }
    }
    public LLMClient audit(LLMClient delegate) {
        return (messages, tools) -> trials.request(record, source, task, delegate, messages, tools, catalog, candidate.guide().markdown());
    }
}
