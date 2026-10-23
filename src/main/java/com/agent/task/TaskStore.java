package com.agent.task;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.agent.session.Redactor;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** 收敛任务的锁和可靠写入；查询不创建目录，也不修复或重放任务。 */
public final class TaskStore {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final Path root;
    private final Path directory;

    public TaskStore(Path root) throws IOException {
        this.root = root.toRealPath();
        this.directory = this.root.resolve(".agent/tasks");
        checkPath(directory);
    }

    private void checkPath(Path path) throws IOException {
        for (Path part = path; part != null && part.startsWith(root); part = part.getParent()) {
            if (Files.exists(part)) {
                WorkspaceSnapshot.requireInside(root, part);
                if (Files.isSymbolicLink(part)) {
                    throw new IOException("任务目录不支持符号链接");
                }
            }
        }
    }

    public Writer lock() throws IOException {
        checkPath(directory);
        Files.createDirectories(directory);
        checkPath(directory.resolve("writer.lock"));
        FileChannel channel = FileChannel.open(directory.resolve("writer.lock"),
                StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        try {
            FileLock lock = channel.tryLock();
            if (lock == null) {
                throw new IOException("本项目已有管理任务正在写入");
            }
            return new Writer(channel, lock);
        } catch (IOException | OverlappingFileLockException failed) {
            channel.close();
            throw new IOException("本项目已有管理任务正在写入或无法加锁", failed);
        }
    }

    public final class Writer implements AutoCloseable {
        private final FileChannel channel;
        private final FileLock lock;
        private Writer(FileChannel channel, FileLock lock) { this.channel = channel; this.lock = lock; }
        @Override public void close() throws IOException {
            try { lock.release(); } finally { channel.close(); }
        }
    }

    private Path taskDirectory(String id) throws IOException {
        if (id == null || !id.matches("[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}")) {
            throw new IOException("任务标识不合法");
        }
        Path task = directory.resolve(id);
        checkPath(task);
        return task;
    }

    public TaskRecord create(String goal, String command) throws IOException {
        TaskRecord task = new TaskRecord();
        task.schemaVersion = 7;
        task.id = UUID.randomUUID().toString();
        task.project = root.toString().replace('\\', '/');
        task.goal = goal;
        task.command = command;
        task.sessionId = "task-" + task.id;
        task.files = WorkspaceSnapshot.capture(root);
        task.acceptanceFiles = TaskProtection.freeze(task.files);
        task.acceptanceVersion = WorkspaceSnapshot.hash(JSON.writeValueAsBytes(List.of(command, task.acceptanceFiles)));
        save(task);
        return task;
    }

    public void save(TaskRecord task) throws IOException {
        Path dir = taskDirectory(task.id);
        Files.createDirectories(dir);
        Path state = dir.resolve("state.json");
        if (Files.exists(state)) {
            atomicWrite(dir.resolve("state.previous.json"), Files.readAllBytes(state));
        }
        var persisted = JSON.valueToTree(task);
        ((com.fasterxml.jackson.databind.node.ObjectNode) persisted).put("goal", Redactor.redact(task.goal).text());
        ((com.fasterxml.jackson.databind.node.ObjectNode) persisted).put("command", Redactor.redact(task.command).text());
        ((com.fasterxml.jackson.databind.node.ObjectNode) persisted).put("reason", Redactor.redact(task.reason).text());
        for (var agreement : persisted.path("agreementHistory")) {
            ((com.fasterxml.jackson.databind.node.ObjectNode) agreement).put("command",
                    Redactor.redact(agreement.path("command").asText()).text());
        }
        for (var decision : persisted.path("progress").path("decisions")) {
            for (var operation : decision.path("operations")) {
                String arguments = operation.path("arguments").asText(null);
                var object = (com.fasterxml.jackson.databind.node.ObjectNode) operation;
                object.put("observation", Redactor.redact(operation.path("observation").asText(), arguments).text());
                object.put("arguments", Redactor.redact(arguments, arguments).text());
            }
        }
        redactText(persisted.path("progress"));
        var argumentsByCall = new java.util.HashMap<String, String>();
        for (var message : persisted.path("checkpoint").path("messages")) {
            for (var call : message.path("toolCalls")) {
                String arguments = call.path("function").path("arguments").asText();
                argumentsByCall.put(call.path("id").asText(), arguments);
                ((com.fasterxml.jackson.databind.node.ObjectNode) call.path("function"))
                        .put("arguments", Redactor.redact(arguments, arguments).text());
            }
            if (message.path("role").asText().equals("tool")) {
                ((com.fasterxml.jackson.databind.node.ObjectNode) message).put("content", Redactor.redact(
                        message.path("content").asText(), argumentsByCall.get(message.path("toolCallId").asText())).text());
            }
        }
        redactText(persisted.path("checkpoint"));
        for (var resolution : persisted.path("resolutions")) {
            var original = resolution.path("originalOperation");
            if (original.isObject()) {
                String arguments = original.path("arguments").asText(null);
                ((com.fasterxml.jackson.databind.node.ObjectNode) original)
                        .put("arguments", Redactor.redact(arguments, arguments).text());
                ((com.fasterxml.jackson.databind.node.ObjectNode) original).put("observation",
                        Redactor.redact(original.path("observation").asText(), arguments).text());
            }
        }
        redactText(persisted.path("resolutions"));
        // 摘要覆盖脱敏后落盘的全部状态；不使用 previous 快照猜测执行是否成功。
        if (task.schemaVersion >= 4) {
            ((com.fasterxml.jackson.databind.node.ObjectNode) persisted).put("checkpointSha256",
                    WorkspaceSnapshot.hash(JSON.writeValueAsBytes(persisted)));
        }
        atomicWrite(state, JSON.writeValueAsBytes(persisted));
    }

    private void redactText(com.fasterxml.jackson.databind.JsonNode node) {
        if (node.isObject()) {
            var object = (com.fasterxml.jackson.databind.node.ObjectNode) node;
            var fields = object.properties();
            for (var entry : fields) {
                if (entry.getValue().isTextual()) { object.put(entry.getKey(), Redactor.redact(entry.getValue().asText()).text()); }
                else { redactText(entry.getValue()); }
            }
        } else if (node.isArray()) { node.forEach(this::redactText); }
    }

    private void atomicWrite(Path path, byte[] bytes) throws IOException {
        checkPath(path);
        Path temporary = Files.createTempFile(path.getParent(), "snapshot-", ".tmp");
        try {
            try (FileChannel file = FileChannel.open(temporary, StandardOpenOption.WRITE)) {
                ByteBuffer buffer = ByteBuffer.wrap(bytes);
                while (buffer.hasRemaining()) { file.write(buffer); }
                file.force(true);
            }
            Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    public TaskRecord read(String id) throws IOException {
        Path state = taskDirectory(id).resolve("state.json");
        checkPath(state);
        var node = JSON.readTree(Files.readAllBytes(state));
        if (node == null || !node.hasNonNull("schemaVersion") || !node.hasNonNull("acceptanceVersion")
                || !node.hasNonNull("acceptanceFiles") || !node.hasNonNull("files") || !node.hasNonNull("checks")) {
            throw new IOException("任务状态缺少必要字段");
        }
        String digest = node.path("checkpointSha256").asText();
        ((com.fasterxml.jackson.databind.node.ObjectNode) node).remove("checkpointSha256");
        if (node.path("schemaVersion").asInt() >= 4) {
            if (!WorkspaceSnapshot.hash(JSON.writeValueAsBytes(node)).equals(digest)) {
                throw new IOException("任务检查点损坏，完整性校验失败");
            }
        }
        TaskRecord task = JSON.treeToValue(node, TaskRecord.class);
        if ((task.schemaVersion < 1 || task.schemaVersion > 7) || !id.equals(task.id) || task.state == null || task.goal == null
                || task.command == null || task.files == null || task.checks == null || task.acceptanceFiles == null
                || task.sessionId == null || task.acceptanceVersion == null || task.reason == null
                || task.agreementHistory == null || task.schemaVersion >= 2 && !node.hasNonNull("agreementHistory")
                || task.progress == null || task.schemaVersion >= 3 && !node.hasNonNull("progress")) {
            throw new IOException("任务状态损坏或版本不支持");
        }
        if (!root.toString().replace('\\', '/').equals(task.project)) {
            throw new IOException("任务属于其他项目，移动或复制后必须新建任务");
        }
        if (task.schemaVersion >= 3) {
            for (String field : List.of("maxRequests", "requests", "tools", "promptTokens", "completionTokens",
                    "unknownRequests", "elapsedMillis", "errors", "decisions", "budgetChanges",
                    "acceptanceErrors", "toolErrors", "errorDescriptions")) {
                if (!node.path("progress").hasNonNull(field)) { throw new IOException("任务消耗记录损坏: " + field); }
            }
            validateProgress(task, task.schemaVersion >= 4);
        }
        if (task.schemaVersion >= 4) { TaskCheckpoint.validate(task, node.path("checkpoint")); }
        if (task.experienceFailures == null || task.experienceAdviceIds == null || task.schemaVersion >= 7
                && (!node.hasNonNull("experiencesEnabled") || !node.hasNonNull("experienceFailures") || !node.hasNonNull("experienceAdviceIds"))) {
            throw new IOException("任务经验选项或失败指纹记录缺失");
        }
        if (task.schemaVersion >= 5 && !node.hasNonNull("resolutions")) { throw new IOException("任务缺少人工处置记录"); }
        for (TaskRecord.EvidenceRef ref : task.checks) { readEvidence(task, ref); }
        for (TaskRecord.Agreement agreement : task.agreementHistory) {
            if (agreement == null || agreement.version() == null || agreement.command() == null
                    || agreement.files() == null || agreement.checks() == null) {
                throw new IOException("历史验收约定损坏");
            }
            for (TaskRecord.EvidenceRef ref : agreement.checks()) { readEvidence(task.id, agreement.version(), ref); }
        }
        if (task.state == TaskRecord.State.Verified) {
            if (task.checks.size() < 2) { throw new IOException("验证通过任务缺少验收证据"); }
            TaskAcceptance.Evidence finalCheck = readEvidence(task, task.checks.get(task.checks.size() - 1));
            if (!finalCheck.kind().equals("final") || !finalCheck.verdict().equals("PASSED")
                    || !task.files.equals(finalCheck.files())) { throw new IOException("验证状态与证据不匹配"); }
        }
        return task;
    }

    private void validateProgress(TaskRecord task, boolean checkpointed) throws IOException {
        TaskRecord.Progress progress = task.progress;
        if (progress.maxRequests <= 0 || progress.requests < 0 || progress.requests > progress.maxRequests
                || progress.tools < 0 || progress.promptTokens < 0 || progress.completionTokens < 0
                || progress.unknownRequests < 0 || progress.elapsedMillis < 0 || progress.decisions == null
                || progress.decisions.size() != progress.requests || progress.errors == null
                || progress.acceptanceErrors == null || progress.toolErrors == null
                || progress.errorDescriptions == null || progress.budgetChanges == null) {
            throw new IOException("任务累计消耗记录损坏");
        }
        long prompt = 0, completion = 0;
        int unknown = 0, tools = 0, number = 0;
        for (var decision : progress.decisions) {
            if (decision == null || decision.number != ++number || decision.acceptanceVersion == null
                    || decision.failureSource == null || decision.status == null || decision.operations == null
                    || decision.promptTokens != null && decision.promptTokens < 0
                    || decision.completionTokens != null && decision.completionTokens < 0) {
                throw new IOException("模型决策消耗记录损坏");
            }
            prompt += decision.promptTokens == null ? 0 : decision.promptTokens;
            completion += decision.completionTokens == null ? 0 : decision.completionTokens;
            if (decision.promptTokens == null || decision.completionTokens == null) { unknown++; }
            for (var operation : decision.operations) {
                if (operation == null || operation.status == null || operation.changes == null) {
                    throw new IOException("工具操作记录损坏");
                }
                if (checkpointed) {
                    if (operation.attempts < 0 || operation.status != TaskRecord.OperationState.CANCELLED
                            && (operation.status == TaskRecord.OperationState.REQUESTED) != (operation.attempts == 0)) {
                        throw new IOException("工具尝试计数损坏");
                    }
                    tools += operation.attempts;
                } else if (operation.status != TaskRecord.OperationState.REQUESTED) { tools++; }
            }
        }
        if (task.resolutions == null) { throw new IOException("人工处置记录损坏"); }
        for (var resolution : task.resolutions) {
            if (resolution == null || resolution.id == null || resolution.action == null || resolution.target == null
                    || resolution.at <= 0 || resolution.reviewedFiles == null || resolution.expectedFiles == null
                    || resolution.originalProcesses == null
                    || resolution.cancelledOperations == null
                    || resolution.action == TaskRecord.ResolutionAction.RETRY && (resolution.originalOperation == null || resolution.replacementId == null)) {
                throw new IOException("人工处置记录损坏");
            }
            if (resolution.originalOperation != null) { tools += resolution.originalOperation.attempts; }
        }
        if (prompt != progress.promptTokens || completion != progress.completionTokens
                || unknown != progress.unknownRequests || tools != progress.tools
                || progress.errors.values().stream().anyMatch(count -> count == null || count < 0)) {
            throw new IOException("任务累计消耗与执行证据不匹配");
        }
    }

    public Path evidenceDirectory(String taskId, String checkId) throws IOException {
        if (!checkId.matches("(baseline|final)-[0-9a-f-]{36}")) { throw new IOException("证据标识不合法"); }
        Path dir = taskDirectory(taskId).resolve(checkId);
        checkPath(dir);
        Files.createDirectories(dir);
        return dir;
    }

    public void writeEvidenceFile(String taskId, String checkId, String relative, byte[] bytes) throws IOException {
        Path dir = evidenceDirectory(taskId, checkId).resolve("reports");
        Path file = dir.resolve(relative).normalize();
        if (!file.startsWith(dir)) { throw new IOException("证据路径越界"); }
        checkPath(file);
        Files.createDirectories(file.getParent());
        atomicWrite(file, bytes);
    }

    public void saveEvidence(TaskRecord task, String id, TaskAcceptance.Evidence evidence) throws IOException {
        var process = evidence.process();
        var safeProcess = process == null ? null : new com.agent.command.CommandResult(process.exitCode(),
                process.timedOut(), Redactor.redact(process.output()).text(), process.durationMillis(), process.outputCompleteness());
        var safe = new TaskAcceptance.Evidence(evidence.kind(), evidence.verdict(), Redactor.redact(evidence.reason()).text(),
                evidence.acceptanceVersion(), evidence.files(), safeProcess, evidence.tests(), evidence.identities(),
                evidence.reports(), evidence.codingAllowed(), evidence.failures() == null ? List.of()
                : evidence.failures().stream().map(value -> Redactor.redact(value).text()).toList());
        byte[] bytes = JSON.writeValueAsBytes(safe);
        atomicWrite(evidenceDirectory(task.id, id).resolve("result.json"), bytes);
        task.checks.add(new TaskRecord.EvidenceRef(id, WorkspaceSnapshot.hash(bytes)));
    }

    public TaskAcceptance.Evidence readEvidence(TaskRecord task, TaskRecord.EvidenceRef ref) throws IOException {
        return readEvidence(task.id, task.acceptanceVersion, ref);
    }

    private TaskAcceptance.Evidence readEvidence(String taskId, String version, TaskRecord.EvidenceRef ref) throws IOException {
        if (ref == null || ref.id() == null || !ref.id().matches("(baseline|final)-[0-9a-f-]{36}")) {
            throw new IOException("任务证据引用损坏");
        }
        Path dir = taskDirectory(taskId).resolve(ref.id());
        Path result = dir.resolve("result.json");
        checkPath(result);
        byte[] bytes = Files.readAllBytes(result);
        if (!WorkspaceSnapshot.hash(bytes).equals(ref.sha256())) { throw new IOException("任务证据校验失败"); }
        TaskAcceptance.Evidence evidence = JSON.readValue(bytes, TaskAcceptance.Evidence.class);
        if (!version.equals(evidence.acceptanceVersion())) { throw new IOException("验收版本不匹配"); }
        for (var report : evidence.reports().entrySet()) {
            Path file = dir.resolve("reports").resolve(report.getKey()).normalize();
            if (!file.startsWith(dir.resolve("reports"))) { throw new IOException("报告引用越界"); }
            checkPath(file);
            if (!WorkspaceSnapshot.hash(Files.readAllBytes(file)).equals(report.getValue())) {
                throw new IOException("任务报告证据损坏");
            }
        }
        return evidence;
    }

    /** 历史通过不能成为当前成功或未来经验保存的凭据。 */
    public boolean isCurrentVerified(TaskRecord task) throws IOException {
        TaskRecord current = read(task.id);
        return current.state == TaskRecord.State.Verified && task.acceptanceVersion.equals(current.acceptanceVersion)
                && current.files.equals(WorkspaceSnapshot.capture(root))
                && new TaskProtection(root, current.acceptanceFiles).changes(current.files).isEmpty();
    }

    public void replaceAgreement(TaskRecord task, String command, java.util.Map<String, String> proposal)
            throws IOException {
        task.agreementHistory.add(new TaskRecord.Agreement(task.acceptanceVersion, task.command,
                java.util.Map.copyOf(task.acceptanceFiles), List.copyOf(task.checks)));
        task.schemaVersion = Math.max(2, task.schemaVersion);
        task.command = command;
        task.files = java.util.Map.copyOf(proposal);
        task.acceptanceFiles = TaskProtection.freeze(proposal);
        task.acceptanceVersion = WorkspaceSnapshot.hash(JSON.writeValueAsBytes(List.of(
                UUID.randomUUID().toString(), command, task.acceptanceFiles)));
        task.checks = new ArrayList<>();
        task.checkpoint.restartAgreement();
        task.state = TaskRecord.State.Paused;
        task.reason = "新验收约定已批准，旧验证已失效；等待初始验收证据";
        save(task);
    }

    public List<TaskRecord> list() throws IOException {
        if (!Files.exists(directory)) { return List.of(); }
        List<TaskRecord> tasks = new ArrayList<>();
        try (var entries = Files.list(directory)) {
            for (Path entry : entries.sorted().toList()) {
                if (Files.isDirectory(entry)) { tasks.add(read(entry.getFileName().toString())); }
            }
        }
        return tasks;
    }
}
