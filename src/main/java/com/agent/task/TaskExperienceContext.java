package com.agent.task;

import com.agent.experience.ExperienceLibrary;
import com.agent.llm.Message;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.regex.Pattern;

/** 只替换用户消息中的已知经验块，不增删工具消息或改变恢复游标。 */
final class TaskExperienceContext {
    private static final Pattern ADVICE = Pattern.compile(
            "\\n参考经验 [0-9a-f-]{36}；来源任务 [0-9a-f-]{36}；匹配原因：.*仅供参考，仍须遵守当前目标、守卫和独立验收。", Pattern.DOTALL);

    static void refresh(Path root, TaskStore store, TaskRecord task, PrintStream out) throws IOException {
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
        if (!task.experiencesEnabled || task.checkpoint.phase == TaskRecord.Phase.BASELINE) { return; }
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

    private static String strip(String text) { return text == null ? null : ADVICE.matcher(text).replaceAll(""); }
    private static String stripPreservingGoal(String text, String goal) {
        return text != null && text.startsWith(goal) ? goal + strip(text.substring(goal.length())) : strip(text);
    }
}
