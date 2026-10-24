package com.agent.task;

import com.agent.experience.ExperienceLibrary;
import com.agent.llm.Message;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.regex.Pattern;

/** 只替换用户消息中的已知经验块，不增删工具消息或改变恢复游标。 */
final class TaskExperienceContext {
    private static final Pattern ADVICE = Pattern.compile(
            "\\n参考经验 [0-9a-f-]{36}；来源任务 [0-9a-f-]{36}；匹配原因：.*仅供参考，仍须遵守当前目标、守卫和独立验收。", Pattern.DOTALL);

    static void refresh(Path root, TaskStore store, TaskRecord task, PrintStream out) throws IOException {
        requireVisibleLegacyHistory(root, task);
        for (int i = 0; i < task.checkpoint.messages.size(); i++) {
            var message = task.checkpoint.messages.get(i);
            if ("user".equals(message.role()) && message.content() != null) {
                String content = message.content();
                task.checkpoint.messages.set(i, Message.user(stripPreservingGoal(content, task.goal)));
            }
        }
        task.checkpoint.feedback = stripPreservingGoal(task.checkpoint.feedback, task.goal);
        task.experienceAdviceIds.clear();
        task.experienceFailures.clear();
        out.println("[经验] 恢复时撤下旧建议，按当前启用状态、来源及适用条件重新检查；经验开关=" + task.experiencesEnabled);
        if (task.knowledgeMode != KnowledgeMode.EXPERIENCE || task.checkpoint.phase == TaskRecord.Phase.BASELINE) { return; }
        String advice = "";
        for (int i = task.checks.size() - 1; i >= 0; i--) {
            var evidence = store.readEvidence(task, task.checks.get(i));
            if (!"FAILED".equals(evidence.verdict())) { break; }
            task.experienceFailures.add(CoreError.key(evidence.details()));
            try { advice = new ExperienceLibrary(root).adviceFor(evidence.details(), task.files, task.experienceAdviceIds, out); }
            catch (IOException unavailable) { out.println("[经验] 当前经验不可用，继续独立修复与验收"); }
            break;
        }
        if (advice.isEmpty()) { return; }
        if (task.checkpoint.feedback != null) { task.checkpoint.feedback += advice; return; }
        for (int i = task.checkpoint.messages.size() - 1; i >= 0; i--) {
            var message = task.checkpoint.messages.get(i);
            if ("user".equals(message.role())) {
                task.checkpoint.messages.set(i, Message.user(message.content() + advice));
                return;
            }
        }
    }

    /** 旧工具没有逐项可见性记录，保守暂停可能已带入历史的上下文，不破坏消息配对和恢复游标。 */
    private static void requireVisibleLegacyHistory(Path root, TaskRecord task) throws IOException {
        if (task.schemaVersion >= 8) { return; }
        var visibleCalls = task.checkpoint.messages.stream().filter(message -> "tool".equals(message.role()))
                .map(Message::toolCallId).collect(java.util.stream.Collectors.toSet());
        var protection = new TaskProtection(root, task.acceptanceFiles);
        var json = new ObjectMapper();
        for (var decision : task.progress.decisions) {
            for (var operation : decision.operations) {
                if (operation.status != TaskRecord.OperationState.SUCCEEDED || !visibleCalls.contains(operation.callId)
                        || !java.util.List.of("read_file", "list_files", "search_code").contains(operation.tool)) { continue; }
                Path path = root.resolve(json.readTree(operation.arguments).path("path").asText("."))
                        .toAbsolutePath().normalize();
                if (!protection.isReadable(path) || !operation.tool.equals("read_file")
                        && root.resolve(".agent").startsWith(path.toRealPath())) {
                    throw new TaskProgress.Pause("旧任务上下文可能包含知识元数据，不能继续传给模型；请新建任务，原模式与轨迹保留");
                }
            }
        }
    }

    private static String strip(String text) { return text == null ? null : ADVICE.matcher(text).replaceAll(""); }
    private static String stripPreservingGoal(String text, String goal) {
        return text != null && text.startsWith(goal) ? goal + strip(text.substring(goal.length())) : strip(text);
    }
}
