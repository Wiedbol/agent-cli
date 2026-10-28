package com.agent.skill;

import com.agent.task.*;
import java.io.IOException;
import java.util.*;

/** 固定知识版本与请求事实随任务原子保存，消费仍由原任务账本统计。 */
public final class SkillTask {
    public String control, source;
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
    public Reference fixed;
    public int firstRequest = 1;
    public List<Reference> offered = new ArrayList<>(), loaded = new ArrayList<>();
    public List<Event> events = new ArrayList<>();
    public List<String> failures = new ArrayList<>();
    public record Reference(String skillId, String versionId, String versionSha256, String candidateSha256, String guideSha256) { }
    public record Event(String type, String callId, String skillId, String versionId, boolean success, String detail) { }
    public record Request(SkillRequests.Payload payload, List<Reference> included) { }
    public static void validate(TaskRecord task) throws IOException {
        var state = task.skills;
        if (state == null || task.knowledgeMode != KnowledgeMode.SKILL || task.skillTrialId != null
                || (state.control == null) != (state.source == null) || state.firstRequest <= 0
                || state.offered == null || state.loaded == null || state.events == null || state.failures == null
                || state.firstRequest > task.progress.requests + 1 || state.loaded.size() > 3) {
            throw new IOException("任务技能状态缺失或不合法");
        }
        for (var reference : state.offered) { validate(reference); }
        if (state.fixed != null) {
            validate(state.fixed);
            if (state.source == null || state.offered.stream().anyMatch(pin -> !pin.equals(state.fixed))) {
                throw new IOException("冻结评测只能发现指定的已批准版本");
            }
        }
        for (var reference : state.loaded) { validate(reference); if (!state.offered.contains(reference)) { throw new IOException("固定技能缺少目录授权"); } }
        if (state.loaded.stream().map(Reference::skillId).distinct().count() != state.loaded.size()) { throw new IOException("固定技能身份重复"); }
        if (state.control != null) {
            try {
                for (String path : List.of(state.control, state.source)) {
                    var value = java.nio.file.Path.of(path);
                    if (!value.isAbsolute() || !value.normalize().equals(value)) { throw new IOException("技能库路径不规范"); }
                }
            } catch (java.nio.file.InvalidPathException invalid) { throw new IOException("技能库路径不合法", invalid); }
        }
        for (var event : state.events) { if (event == null || event.type() == null || event.detail() == null) { throw new IOException("技能事件缺失"); } }
        for (var decision : task.progress.decisions) {
            for (var operation : decision.operations) {
                if (!"load_skill".equals(operation.tool) || operation.status != TaskRecord.OperationState.SUCCEEDED) { continue; }
                var arguments = SkillStore.JSON.readTree(operation.arguments);
                if (arguments == null || !arguments.path("id").isTextual() || !arguments.path("version").isTextual()
                        || state.loaded.stream().noneMatch(pin -> pin.skillId().equals(arguments.path("id").asText()) && pin.versionId().equals(arguments.path("version").asText()))) {
                    throw new IOException("已完成加载缺少固定技能引用");
                }
            }
            if (decision.skillRequest != null) {
                SkillRequests.validate(decision.skillRequest.payload());
                if (decision.skillRequest.included() == null || !state.loaded.containsAll(decision.skillRequest.included())) { throw new IOException("请求技能引用损坏"); }
            } else if (decision.number >= state.firstRequest && decision.status == TaskRecord.DecisionState.RESPONDED) { throw new IOException("技能响应缺少最终请求审计"); }
        }
    }
    private static void validate(Reference reference) throws IOException {
        if (reference == null) { throw new IOException("技能引用缺失"); }
        SkillStore.requireId(reference.skillId()); SkillStore.requireId(reference.versionId());
        for (String hash : Arrays.asList(reference.versionSha256(), reference.candidateSha256(), reference.guideSha256())) {
            if (hash == null || !hash.matches("[a-f0-9]{64}")) { throw new IOException("固定技能摘要不合法"); }
        }
    }
}
