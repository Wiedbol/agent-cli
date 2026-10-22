package com.agent.task;

import com.agent.llm.Message;
import com.agent.session.Redactor;
import com.fasterxml.jackson.databind.JsonNode;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;

/** 只验证可恢复边界，不从聊天日志、旧快照或当前文件推断未知副作用。 */
final class TaskCheckpoint {
    private TaskCheckpoint() { }

    static void validate(TaskRecord task, JsonNode node) throws IOException {
        for (String field : List.of("phase", "messages", "completionClaim", "acceptancePending", "acceptanceProcessed")) {
            if (!node.hasNonNull(field)) { throw new IOException("任务检查点不完整: " + field); }
        }
        if (!node.has("feedback") || !node.has("acceptanceResult") || task.checkpoint == null
                || task.checkpoint.phase == null || task.checkpoint.messages == null || task.checkpoint.acceptanceProcesses == null) {
            throw new IOException("任务检查点阶段损坏");
        }
        var pending = new LinkedHashMap<String, com.agent.llm.ToolCall>();
        for (Message message : task.checkpoint.messages) {
            if (message == null) { throw new IOException("任务消息缺失"); }
            if (message.role().equals("tool")) {
                if (pending.remove(message.toolCallId()) == null || message.content() == null) {
                    throw new IOException("任务工具结果关联损坏");
                }
            } else {
                if (!pending.isEmpty()) { throw new IOException("任务缺少工具结果"); }
                if (message.role().equals("assistant") && message.toolCalls() != null) {
                    for (var call : message.toolCalls()) {
                        if (call == null || call.id() == null || call.id().isBlank() || call.name() == null
                                || call.arguments() == null || pending.putIfAbsent(call.id(), call) != null) {
                            throw new IOException("任务工具调用关联损坏");
                        }
                    }
                } else if (!List.of("user", "assistant").contains(message.role())) {
                    throw new IOException("任务消息角色无效");
                }
            }
        }
        for (var decision : task.progress.decisions) {
            for (var op : decision.operations) {
                if (op.callId == null || op.tool == null || op.arguments == null || op.observation == null || op.processes == null) {
                    throw new IOException("任务操作不完整");
                }
                validateProcesses(op.processes);
            }
        }
        validateProcesses(task.checkpoint.acceptanceProcesses);
        if (!pending.isEmpty()) {
            if (task.progress.decisions.isEmpty()) { throw new IOException("任务缺少待执行决策"); }
            var last = task.progress.decisions.get(task.progress.decisions.size() - 1);
            var unfinished = last.operations.stream().filter(op -> !op.finished()).map(op -> op.callId).toList();
            if (last.roundFinished || !unfinished.equals(List.copyOf(pending.keySet()))) {
                throw new IOException("任务工具游标与消息不一致");
            }
        }
    }

    private static void validateProcesses(List<TaskRecord.ProcessRef> processes) throws IOException {
        if (processes.stream().anyMatch(process -> process == null || process.pid() <= 0 || process.startedAt() < 0)) {
            throw new IOException("任务进程身份记录损坏");
        }
    }

    static void requireResumable(TaskRecord task) {
        if (task.schemaVersion < 4) { throw new TaskProgress.Pause("旧任务缺少可靠恢复检查点，请新建任务"); }
        if (task.command.contains(Redactor.PLACEHOLDER) || task.goal.contains(Redactor.PLACEHOLDER)
                || task.checkpoint.messages.toString().contains(Redactor.PLACEHOLDER)
                || task.checkpoint.feedback != null && task.checkpoint.feedback.contains(Redactor.PLACEHOLDER)) {
            throw new TaskProgress.Pause("检查点必要内容已脱敏，不能自动恢复执行");
        }
        if (task.checkpoint.acceptancePending) { throw new TaskProgress.Pause("验收命令结果不明，不能自动重试；需要人工处置"); }
        if (task.checkpoint.phase == TaskRecord.Phase.ACCEPTANCE && task.checkpoint.completionClaim
                && (task.progress.decisions.isEmpty()
                || task.progress.decisions.get(task.progress.decisions.size() - 1).content == null
                || task.progress.decisions.get(task.progress.decisions.size() - 1).content.isBlank())) {
            throw new TaskProgress.Pause("模型没有给出完成声明，不能通过恢复进入验收");
        }
        for (var decision : task.progress.decisions) {
            for (var operation : decision.operations) {
                if (!decision.acceptanceVersion.equals(task.acceptanceVersion)
                        && !operation.finished()) {
                    throw new TaskProgress.Pause("待执行操作属于旧验收约定，不能继续；需要重新规划");
                }
                if (operation.status == TaskRecord.OperationState.RUNNING && !"READ".equals(operation.risk)) {
                    throw new TaskProgress.Pause("工具结果不明: " + operation.callId + " " + operation.tool + "；需要人工处置，未重放");
                }
            }
        }
        if (task.progress.errors.values().stream().anyMatch(count -> count >= 3)
                && task.checkpoint.acceptanceProcessed) {
            throw new TaskProgress.Pause("同一核心错误持续三个决策轮次，不能通过恢复重置");
        }
    }

    static boolean hasUncertainOperation(TaskRecord task) {
        return task.checkpoint.acceptancePending || task.progress.decisions.stream()
                .flatMap(decision -> decision.operations.stream()).anyMatch(operation ->
                        operation.status == TaskRecord.OperationState.RUNNING && !"READ".equals(operation.risk));
    }
}
