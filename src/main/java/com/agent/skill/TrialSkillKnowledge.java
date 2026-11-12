package com.agent.skill;

import com.agent.llm.LLMClient;
import com.agent.task.*;
import com.agent.tool.ToolRequest;
import java.io.IOException;
import java.nio.file.Path;
import java.util.*;

/** 显式开发审阅复用普通任务循环及加载工具；客户端包装仅审计，不修改请求消息。 */
final class TrialSkillKnowledge implements SkillKnowledge {
    private static final String DIRECTORY_HEADER = "\n修复技能试用目录（显式候选审阅；未启用候选，仅本次可加载）";
    private static final String DIRECTORY_END = "若不适用，应明确拒绝，不得套用；继续独立修复并完成冻结验收。";
    private final SkillTrials trials;
    private final SkillTrial record;
    private final SkillStore source;
    private final SkillCandidate candidate;
    private TaskRecord task;
    private String catalog = "";
    TrialSkillKnowledge(SkillTrials trials, SkillTrial record, SkillStore source) throws IOException {
        this.trials = trials; this.record = record; this.source = source; candidate = source.candidate(record.candidateId);
        if (record.taskId != null) { catalog = previousDirectory(); }
    }
    public String trialId() { return record.id; }
    @Override public String frozenCommand() { return record.command; }
    public void bind(TaskRecord task) throws IOException {
        this.task = task; record.taskId = task.id; record.status = "RUNNING"; trials.save(record);
    }
    public boolean isProtected(Path path) {
        Path project = Path.of(record.project);
        return path.equals(project.resolve(".repair-lab-workspace.json")) || path.equals(project.resolve("GOAL.md"));
    }
    public void check() {
        try { trials.check(record, source); }
        catch (IOException failure) { throw new TaskProgress.Pause("技能试用前提失效: " + failure.getMessage()); }
    }
    public String discover(TaskRecord task, TaskAcceptance.Evidence evidence) {
        // 定期复核可能在模型完成声明前通过；它不是新的失败基线，也不应阻断后续完成声明。
        if ("final".equals(evidence.kind()) && "PASSED".equals(evidence.verdict()) && evidence.codingAllowed()) {
            check();
            return "";
        }
        if (!evidence.verdict().equals("FAILED") || !evidence.codingAllowed()) { throw new TaskProgress.Pause("试用需要有效的独立失败基线"); }
        check();
        if (!catalog.isEmpty()) { return ""; }
        String failure = SkillMatching.conditionFailure(candidate.guide().conditions(), task.files);
        if (failure != null) { event("CATALOG_EXCLUDED", false, failure); return ""; }
        catalog = directory();
        event("CATALOG", true, "目录已组装，等待请求结果");
        return catalog;
    }
    private String directory() {
        return DIRECTORY_HEADER
                + "\nload_skill 参数: " + selectionArguments()
                + "\n摘要: " + shortText(candidate.guide().title()) + "\n适用: " + shortText(candidate.guide().conditions())
                + "\n不适用: " + shortText(candidate.guide().exclusions())
                + "\n匹配原因: 操作者指定的开发候选；来源与项目已核验。"
                + "\n审阅协议: 请先用上述两个独立字段调用 load_skill，再用相同的 id 和 version 调用 declare_skill，"
                + "根据新任务目标和失败证据声明 ACCEPT 或 REJECT 并说明原因。"
                + DIRECTORY_END;
    }
    private String previousDirectory() {
        // 恢复时用实际发送过的目录核对后续请求，兼容旧版 ID@摘要 的目录格式。
        for (var request : record.requests) {
            if (!request.catalogIncluded) { continue; }
            for (var message : request.messages) {
                String content = message.content();
                if (!"user".equals(message.role()) || content == null) { continue; }
                int start = content.indexOf(DIRECTORY_HEADER);
                if (start < 0) { continue; }
                int end = content.indexOf(DIRECTORY_END, start);
                if (end >= 0) { return content.substring(start, end + DIRECTORY_END.length()); }
            }
        }
        return directory();
    }
    private String selectionArguments() {
        return "{\"id\":\"" + record.candidateId + "\",\"version\":\"" + record.candidateSha256 + "\"}";
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
            throw new SecurityException(catalog.isEmpty() ? "本次目录不允许该候选或版本"
                    : "本次目录不允许该候选或版本。请分别填写 id 和 version，使用 " + selectionArguments());
        }
    }
    public String load(ToolRequest request) {
        selection(request, 2);
        if (record.loaded) { event("LOAD_DUPLICATE", true, "不重复加入指南"); return "同一快照已加载，不重复加入上下文"; }
        record.loaded = true;
        event("LOAD", true, record.candidateId + "@" + record.candidateSha256);
        return "修复技能试用快照，declare_skill 使用以下 id 和 version：" + selectionArguments()
                + "\n" + candidate.guide().markdown();
    }
    public String declare(ToolRequest request) {
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
