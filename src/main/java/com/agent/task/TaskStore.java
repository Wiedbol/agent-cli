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
        task.id = UUID.randomUUID().toString();
        task.project = root.toString().replace('\\', '/');
        task.goal = goal;
        task.command = command;
        task.sessionId = "task-" + task.id;
        task.files = WorkspaceSnapshot.capture(root);
        task.files.forEach((path, hash) -> {
            if (path.equals("pom.xml") || path.endsWith("/pom.xml") || path.contains("src/test/")
                    || path.startsWith(".mvn/")) { task.acceptanceFiles.put(path, hash); }
        });
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
        atomicWrite(state, JSON.writeValueAsBytes(persisted));
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
        TaskRecord task = JSON.treeToValue(node, TaskRecord.class);
        if (task.schemaVersion != 1 || !id.equals(task.id) || task.state == null || task.goal == null
                || task.command == null || task.files == null || task.checks == null || task.acceptanceFiles == null
                || task.sessionId == null || task.acceptanceVersion == null || task.reason == null) {
            throw new IOException("任务状态损坏或版本不支持");
        }
        if (!root.toString().replace('\\', '/').equals(task.project)) {
            throw new IOException("任务属于其他项目，移动或复制后必须新建任务");
        }
        for (TaskRecord.EvidenceRef ref : task.checks) { readEvidence(task, ref); }
        if (task.state == TaskRecord.State.Verified) {
            if (task.checks.size() < 2) { throw new IOException("验证通过任务缺少验收证据"); }
            TaskAcceptance.Evidence finalCheck = readEvidence(task, task.checks.get(task.checks.size() - 1));
            if (!finalCheck.kind().equals("final") || !finalCheck.verdict().equals("PASSED")
                    || !task.files.equals(finalCheck.files())) { throw new IOException("验证状态与证据不匹配"); }
        }
        return task;
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
        byte[] bytes = JSON.writeValueAsBytes(evidence);
        atomicWrite(evidenceDirectory(task.id, id).resolve("result.json"), bytes);
        task.checks.add(new TaskRecord.EvidenceRef(id, WorkspaceSnapshot.hash(bytes)));
    }

    public TaskAcceptance.Evidence readEvidence(TaskRecord task, TaskRecord.EvidenceRef ref) throws IOException {
        if (ref == null || ref.id() == null || !ref.id().matches("(baseline|final)-[0-9a-f-]{36}")) {
            throw new IOException("任务证据引用损坏");
        }
        Path dir = taskDirectory(task.id).resolve(ref.id());
        Path result = dir.resolve("result.json");
        checkPath(result);
        byte[] bytes = Files.readAllBytes(result);
        if (!WorkspaceSnapshot.hash(bytes).equals(ref.sha256())) { throw new IOException("任务证据校验失败"); }
        TaskAcceptance.Evidence evidence = JSON.readValue(bytes, TaskAcceptance.Evidence.class);
        if (!task.acceptanceVersion.equals(evidence.acceptanceVersion())) { throw new IOException("验收版本不匹配"); }
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
