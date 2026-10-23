package com.agent.task;

import com.agent.session.Redactor;
import com.agent.tool.ReadFileTool;
import com.agent.tool.ToolRequest;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.UUID;

/** 人工批准只认可现场版本与新规划，不执行旧动作或认定修复成功。 */
public final class TaskReplan {
    private final Path root;
    private final BufferedReader input;
    private final PrintStream out;

    public TaskReplan(Path root, BufferedReader input, PrintStream out) {
        this.root = root;
        this.input = input;
        this.out = out;
    }

    public TaskRecord approve(String id, boolean interactive) throws IOException {
        Path project = root.toRealPath();
        TaskStore store = new TaskStore(project);
        try (TaskStore.Writer writer = store.lock()) {
            TaskRecord task = store.read(id);
            if (task.state == TaskRecord.State.Abandoned || task.schemaVersion < 4) {
                throw new IOException("已放弃或缺少可靠检查点的任务不能重新规划");
            }
            if (TaskCheckpoint.hasUncertainOperation(task)) {
                return pause(store, task, "存在结果不明的写入或命令，须先显式处置，不能用重新规划绕过");
            }
            Map<String, String> current = WorkspaceSnapshot.capture(project);
            var changes = TaskProtection.fileChanges(task.files, current);
            out.println("重新规划任务: " + id + "\n现场变化: " + changes
                    + "\n保留当前文件、原目标与累计预算；取消旧待执行调用，旧成功不能证明当前完成。\n可另行 --abandon-task " + id);
            var protectedChanges = new TaskProtection(project, task.acceptanceFiles).changes(current);
            if (!protectedChanges.isEmpty()) {
                return pause(store, task, "验收输入变化，须先 --reagree-acceptance 明确重新约定: " + protectedChanges);
            }
            String context = context(project, task, current);
            out.println("请输入 replan " + id);
            out.flush();
            if (!interactive) { return pause(store, task, "非交互运行不能批准重新规划；--yes 不适用"); }
            if (!("replan " + id).equals(input.readLine())) { return pause(store, task, "未明确批准重新规划"); }
            if (!current.equals(WorkspaceSnapshot.capture(project))) {
                return pause(store, task, "确认期间现场版本变化，请重新审阅规划");
            }
            var resolution = new TaskRecord.Resolution();
            resolution.id = UUID.randomUUID().toString();
            resolution.action = TaskRecord.ResolutionAction.REPLAN;
            resolution.target = task.id;
            resolution.at = System.currentTimeMillis();
            resolution.expectedFiles = Map.copyOf(task.files);
            resolution.reviewedFiles = Map.copyOf(current);
            for (var decision : task.progress.decisions) {
                for (var operation : decision.operations) {
                    if (!operation.finished()) {
                        operation.status = TaskRecord.OperationState.CANCELLED;
                        operation.observation = "该调用因用户重新规划而取消，不再执行；没有可确认的完成结果，不代表成功。";
                        task.checkpoint.messages.add(com.agent.llm.Message.tool(operation.callId, operation.observation));
                        resolution.cancelledOperations.add(operation.reference(decision.number));
                    }
                }
                if (!decision.operations.isEmpty()) { decision.roundFinished = true; }
            }
            task.resolutions.add(resolution);
            String baseline = task.checkpoint.baselineEvidenceId;
            // schema 4/5 尚无显式基线引用，也不能在重规划后丢弃既有必需测试身份。
            if (baseline == null && !task.checks.isEmpty()) { baseline = task.checks.get(0).id(); }
            task.checkpoint.restartAgreement();
            task.checkpoint.baselineEvidenceId = baseline;
            task.checkpoint.replanContext = context;
            task.files = current;
            task.schemaVersion = Math.max(task.schemaVersion, 6);
            task.state = TaskRecord.State.Paused;
            task.reason = "已明确批准基于当前文件重新规划；旧成功失效，请 --resume-task 重新验收并继续";
            store.save(task);
            return task;
        }
    }

    private String context(Path project, TaskRecord task, Map<String, String> current) throws IOException {
        StringBuilder result = new StringBuilder("用户已明确基于当前文件重新规划。旧意图不再适用，请保留人工修改。\n版本差异: ")
                .append(TaskProtection.fileChanges(task.files, current)).append('\n');
        var paths = new LinkedHashSet<String>();
        current.keySet().stream().filter(path -> !java.util.Objects.equals(task.files.get(path), current.get(path))).forEach(paths::add);
        current.keySet().stream().filter(path -> path.startsWith("src/main/") || path.contains("/src/main/")).forEach(paths::add);
        for (String path : paths) {
            if (result.length() >= 24000) { result.append("上下文已截断；其余文件请先通过 read_file 读取当前版本。\n"); break; }
            var arguments = new ObjectMapper().createObjectNode().put("path", path);
            if (Redactor.touchesSensitivePath(arguments.toString())) { continue; }
            result.append("当前文件 ").append(path).append(" SHA-256: ").append(current.get(path)).append('\n');
            if (Files.size(project.resolve(path)) > 128 * 1024) { result.append("大文件未附加全文，请先读取所需内容。\n"); continue; }
            try {
                String text = new ReadFileTool().execute(new ToolRequest(project, arguments)).output();
                if (!text.startsWith("文件版本 SHA-256: " + current.get(path))) { throw new IOException("重新读取时文件版本变化: " + path); }
                var safe = Redactor.redact(text, arguments.toString());
                if (safe.redacted()) { result.append("敏感内容未自动附加，请明确选择安全上下文。\n"); }
                else { result.append(text, 0, Math.min(text.length(), 8000)).append(text.length() > 8000 ? "\n[内容截断，请重新读取剩余部分]" : "").append('\n'); }
            } catch (java.nio.charset.CharacterCodingException invalidText) {
                result.append("文本编码不明确，未猜测内容；请用正确 encoding 重新读取。\n");
            } catch (IllegalArgumentException nonText) {
                result.append("文件不适合作为自动文本上下文，未猜测或修改内容。\n");
            }
        }
        if (result.length() > 24000) {
            result.setLength(24000);
            result.append("\n[上下文截断；请通过 read_file 读取剩余当前文件，不能猜测原文。]");
        }
        return result.toString();
    }

    private TaskRecord pause(TaskStore store, TaskRecord task, String reason) throws IOException {
        if (task.state != TaskRecord.State.Verified) {
            task.state = TaskRecord.State.Paused;
            task.reason = reason;
            store.save(task);
        }
        out.println(reason);
        return task;
    }
}
