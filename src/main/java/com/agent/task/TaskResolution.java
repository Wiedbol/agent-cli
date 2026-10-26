package com.agent.task;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.UUID;

/** 显式人工选择仅改变后续推进资格，不宣称未知操作已经成功。 */
public final class TaskResolution {
    private final Path root;
    private final BufferedReader input;
    private final PrintStream out;

    public TaskResolution(Path root, BufferedReader input, PrintStream out) {
        this.root = root;
        this.input = input;
        this.out = out;
    }

    public TaskRecord abandon(String id, boolean interactive) throws IOException {
        TaskStore store = new TaskStore(root);
        try (TaskStore.Writer writer = store.lock()) {
            TaskRecord task = store.read(id);
            if (task.state == TaskRecord.State.Abandoned) { return task; }
            out.println("放弃任务: " + id + "\n保留当前文件和全部轨迹，不回滚、不认定未知操作成功；不会自动终止旧进程。\n请输入 abandon " + id);
            out.flush();
            if (!interactive) { return pause(store, task, "非交互运行不能批准放弃；--yes 不适用"); }
            if (!("abandon " + id).equals(input.readLine())) { return pause(store, task, "未明确批准放弃，任务暂停"); }
            TaskRecord.Resolution resolution = new TaskRecord.Resolution();
            resolution.id = UUID.randomUUID().toString();
            resolution.action = TaskRecord.ResolutionAction.ABANDON;
            resolution.target = id;
            resolution.at = System.currentTimeMillis();
            resolution.expectedFiles = java.util.Map.copyOf(task.files);
            resolution.reviewedFiles = WorkspaceSnapshot.capture(root.toRealPath());
            task.resolutions.add(resolution);
            task.state = TaskRecord.State.Abandoned;
            task.reason = "已明确放弃，文件和结果不明的操作轨迹保留；旧进程须另行检查";
            store.save(task);
            return task;
        }
    }

    public TaskRecord retry(String id, String operationId, boolean interactive) throws IOException {
        TaskStore store = new TaskStore(root);
        try (TaskStore.Writer writer = store.lock()) {
            TaskRecord task = store.read(id);
            if (task.schemaVersion < 4 || task.state == TaskRecord.State.Abandoned || task.state == TaskRecord.State.Verified) {
                throw new IOException("任务状态不支持未知操作重试");
            }
            var decision = task.progress.decisions.isEmpty() ? null
                    : task.progress.decisions.get(task.progress.decisions.size() - 1);
            boolean acceptance = task.checkpoint.acceptancePending
                    && acceptanceReference(task).equals(operationId);
            if (!acceptance && decision == null) { throw new IOException("未找到结果不明的操作"); }
            var original = acceptance ? acceptanceIntent(task) : decision.operations.stream()
                    .filter(op -> op.reference(decision.number).equals(operationId))
                    .findFirst().orElseThrow(() -> new IOException("未找到指定活动操作"));
            if (original.status != TaskRecord.OperationState.RUNNING || !("WRITE".equals(original.risk) || "EXECUTE".equals(original.risk))
                    || !acceptance && (decision.roundFinished || !decision.acceptanceVersion.equals(task.acceptanceVersion))) {
                throw new IOException("该操作不是可处置的结果不明写入或命令");
            }
            if (original.arguments.contains(com.agent.session.Redactor.PLACEHOLDER)) {
                return pause(store, task, "必要操作参数已脱敏，不能忠实重试");
            }
            var current = WorkspaceSnapshot.capture(root.toRealPath());
            var protectedChanges = new TaskProtection(root, task.acceptanceFiles).changes(current);
            if (!protectedChanges.isEmpty()) { return pause(store, task,
                    "验收输入变化且操作结果不明；请保留轨迹并放弃后新建任务，不能重新约定绕过未知状态: " + protectedChanges); }
            var changed = new java.util.TreeSet<>(task.files.keySet());
            changed.addAll(current.keySet());
            changed.removeIf(path -> java.util.Objects.equals(task.files.get(path), current.get(path)));
            out.println("任务: " + id + "\n操作ID: " + operationId + "\n结果不明: " + original.tool + " " + original.arguments
                    + "\n现场版本差异: " + changed + "\n重试可能重复已发生的副作用，不能据人工确认认定验收通过。");
            for (String path : changed) { out.println(path + ": " + task.files.get(path) + " -> " + current.get(path)); }
            boolean command = "EXECUTE".equals(original.risk);
            if (command && original.processes.stream().anyMatch(TaskRecord.ProcessRef::alive)) {
                return pause(store, task, "已记录的旧进程仍在运行，不能批准重试: " + original.processes);
            }
            if (command) { out.println("已记录进程: " + original.processes
                    + "\n旧 Agent 退出不证明子进程停止；请检查所有后代后输入 stopped " + id + " " + operationId); }
            else { out.println("请输入 retry " + id + " " + operationId); }
            out.flush();
            if (!interactive) { return pause(store, task, "非交互运行不能批准重试；--yes 不适用"); }
            if (command) {
                if (!("stopped " + id + " " + operationId).equals(input.readLine())) {
                    return pause(store, task, "未明确确认旧进程树已停止，原命令仍结果不明");
                }
                out.println("请输入 retry " + id + " " + operationId);
                out.flush();
            }
            if (!("retry " + id + " " + operationId).equals(input.readLine())) {
                return pause(store, task, "未明确批准重试，原操作仍结果不明");
            }
            if (!current.equals(WorkspaceSnapshot.capture(root.toRealPath()))) {
                return pause(store, task, "确认期间现场版本变化，请重新审阅");
            }
            if (command && original.processes.stream().anyMatch(TaskRecord.ProcessRef::alive)) {
                return pause(store, task, "确认期间旧进程仍在运行，不能批准重试");
            }
            TaskRecord.Operation retry = new TaskRecord.Operation();
            retry.operationId = UUID.randomUUID().toString();
            retry.callId = original.callId;
            retry.tool = original.tool;
            retry.arguments = original.arguments;
            retry.risk = original.risk;
            TaskRecord.Resolution resolution = new TaskRecord.Resolution();
            resolution.id = UUID.randomUUID().toString();
            resolution.action = TaskRecord.ResolutionAction.RETRY;
            resolution.target = operationId;
            resolution.replacementId = retry.operationId;
            resolution.originalOperation = original;
            resolution.stoppedConfirmed = command;
            resolution.originalProcesses = java.util.List.copyOf(original.processes);
            resolution.at = System.currentTimeMillis();
            resolution.expectedFiles = java.util.Map.copyOf(task.files);
            resolution.reviewedFiles = java.util.Map.copyOf(current);
            task.resolutions.add(resolution);
            if (acceptance) {
                task.checkpoint.acceptanceOperationId = retry.operationId;
                task.checkpoint.acceptanceProcesses = new java.util.ArrayList<>();
                task.checkpoint.acceptancePending = false;
                task.checkpoint.acceptanceResult = null;
                task.checkpoint.acceptanceProcessed = false;
            } else { decision.operations.set(decision.operations.indexOf(original), retry); }
            task.files = current;
            task.schemaVersion = Math.max(task.schemaVersion, 5);
            return pause(store, task, "已批准新操作 " + retry.operationId + "，原操作结果不明；请显式 --resume-task 执行，之后重新验收");
        }
    }

    public static String acceptanceReference(TaskRecord task) {
        return task.checkpoint.acceptanceOperationId == null ? "acceptance" : task.checkpoint.acceptanceOperationId;
    }

    private TaskRecord.Operation acceptanceIntent(TaskRecord task) {
        var operation = new TaskRecord.Operation();
        operation.operationId = acceptanceReference(task);
        operation.callId = "acceptance";
        operation.tool = "acceptance";
        operation.risk = "EXECUTE";
        operation.arguments = task.command;
        operation.status = TaskRecord.OperationState.RUNNING;
        operation.processes = java.util.List.copyOf(task.checkpoint.acceptanceProcesses);
        return operation;
    }

    private TaskRecord pause(TaskStore store, TaskRecord task, String reason) throws IOException {
        if (task.state == TaskRecord.State.Verified) { out.println(reason + "；原验证状态保持不变"); return task; }
        task.state = TaskRecord.State.Paused;
        task.reason = reason;
        store.save(task);
        return task;
    }
}
