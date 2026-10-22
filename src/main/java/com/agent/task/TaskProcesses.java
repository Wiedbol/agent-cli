package com.agent.task;

import com.agent.command.CommandRunner;

import java.io.IOException;
import java.util.List;
import java.util.function.Consumer;

/** 记录见到的 shell 与后代身份；短命或脱离树的后代仍须人工核查。 */
final class TaskProcesses implements Consumer<ProcessHandle> {
    private final TaskStore store;
    private final TaskRecord task;

    TaskProcesses(TaskStore store, TaskRecord task) { this.store = store; this.task = task; }

    @Override public void accept(ProcessHandle process) {
        List<TaskRecord.ProcessRef> processes;
        if (task.checkpoint.acceptancePending) { processes = task.checkpoint.acceptanceProcesses; }
        else {
            if (task.progress.decisions.isEmpty()) {
                throw new CommandRunner.TrackingFailure(new IOException("已启动进程缺少关联意图"));
            }
            var decision = task.progress.decisions.get(task.progress.decisions.size() - 1);
            var operation = decision.operations.stream().filter(op -> op.status == TaskRecord.OperationState.RUNNING
                    && "EXECUTE".equals(op.risk)).findFirst().orElseThrow(() ->
                    new CommandRunner.TrackingFailure(new IOException("已启动进程缺少关联命令意图")));
            processes = operation.processes;
        }
        boolean changed = add(processes, process);
        for (var descendant : process.descendants().toList()) { changed |= add(processes, descendant); }
        if (changed) {
            try { store.save(task); }
            catch (IOException failed) { throw new CommandRunner.TrackingFailure(failed); }
        }
    }

    private boolean add(List<TaskRecord.ProcessRef> processes, ProcessHandle process) {
        var identity = TaskRecord.ProcessRef.of(process);
        if (processes.contains(identity)) { return false; }
        processes.add(identity);
        return true;
    }
}
