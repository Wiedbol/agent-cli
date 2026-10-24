package com.agent.skill;

import com.agent.CodingAgentCli;
import com.agent.command.CommandRunner;
import com.agent.llm.*;
import com.agent.session.Redactor;
import com.agent.task.*;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.util.*;

/** 可信控制层核对物理归档与原项目身份，不向模型工作根复制来源或其他候选。 */
public final class SkillTrials {
    public record Config(Path control, Path source, Path catalog, String candidate, String batch, int requests, String model, String endpoint) {
        public Config(Path control, Path source, Path catalog, String candidate, String batch, int requests, String model) {
            this(control, source, catalog, candidate, batch, requests, model, "injected-client");
        }
    }
    private final Path root;
    private final PrintStream out;
    public SkillTrials(Path root, PrintStream out) throws IOException { this.root = root.toRealPath(); this.out = out; }

    static Path canonical(Path path) throws IOException {
        Path actual = path.toRealPath();
        if (!path.isAbsolute() || !path.normalize().equals(actual) || Files.isSymbolicLink(path)) { throw new IOException("试用路径必须为独立规范绝对路径"); }
        return actual;
    }
    static JsonNode read(Path path) throws IOException {
        canonical(path);
        var value = SkillStore.JSON.readTree(Files.readAllBytes(path));
        if (value == null || !value.isObject()) { throw new IOException("试用记录必须为JSON对象: " + path.getFileName()); }
        return value;
    }
    private JsonNode identity(Path control) throws IOException {
        canonical(root); canonical(control);
        if (root.startsWith(control) || control.startsWith(root) || Files.exists(root.resolve(".git"))) {
            throw new IOException("试用工作根与控制归档必须独立，不能使用 Git 仓库根");
        }
        var marker = read(root.resolve(".repair-lab-workspace.json"));
        var owner = read(control.resolve("owner.json"));
        if (marker == null || marker.size() != 3 || !"repair-lab-2".equals(marker.path("version").asText())
                || !root.toString().equals(marker.path("root").asText()) || !marker.equals(owner.path("marker"))
                || !control.toString().equals(owner.path("archive").asText()) || Files.exists(control.resolve("pending.json"))) {
            throw new IOException("试用项目/归档身份不一致，或存在待处理重置");
        }
        SkillStore.requireId(marker.path("projectId").asText());
        return marker;
    }
    private SkillTrial prepare(Config config) throws IOException {
        if (config.requests() <= 0 || config.model() == null || config.model().isBlank()
                || config.endpoint() == null || config.endpoint().isBlank()) { throw new IOException("试用必须冻结模型与明确正数任务额度"); }
        SkillStore.requireSafe(config.endpoint());
        Path control = canonical(config.control()), source = canonical(config.source()), catalogPath = canonical(config.catalog());
        var marker = identity(control);
        if (!source.getFileName().toString().equals("workspace") || source.getParent() == null
                || source.getParent().getParent() == null || !source.getParent().getParent().equals(control.resolve("history"))
                || catalogPath.startsWith(root) || !read(source.resolve(".repair-lab-workspace.json")).equals(marker)) {
            throw new IOException("来源必须是同项目可信历史归档，清单不能位于模型工作根内");
        }
        SkillStore.requireId(source.getParent().getFileName().toString());
        var catalog = read(catalogPath);
        if (!"repair-lab-2".equals(catalog.path("version").asText())) { throw new IOException("不支持的开发清单版本"); }
        var current = read(control.resolve("current.json"));
        var sourceManifest = read(source.getParent().resolve("manifest.json"));
        var cases = new HashMap<String, JsonNode>();
        for (var entry : catalog.path("cases")) {
            if (cases.put(entry.path("id").asText(), entry) != null) { throw new IOException("清单场景重复"); }
        }
        var development = new HashSet<String>();
        for (String stage : List.of("initial-development", "feedback-development", "required-regression")) {
            catalog.path("stages").path(stage).forEach(value -> development.add(value.asText()));
        }
        var formal = new HashSet<String>();
        catalog.path("stages").path("formal").forEach(value -> formal.add(value.asText()));
        if (!development.contains(current.path("id").asText()) || formal.contains(current.path("id").asText())
                || formal.contains(sourceManifest.path("id").asText())
                || current.path("id").asText().startsWith("formal-") || sourceManifest.path("id").asText().startsWith("formal-")
                || !current.equals(cases.get(current.path("id").asText()))
                || !sourceManifest.equals(cases.get(sourceManifest.path("id").asText()))
                || !"mvn test".equals(current.path("command").asText())) {
            throw new IOException("只能试用冻结的开发样例，不能使用正式或未约定输入");
        }
        if (Files.exists(root.resolve(".agent")) || Files.exists(root.resolve("target"))) { throw new IOException("试用需要重置后的干净工作根，不继承旧知识或构建产物"); }
        var initial = stringMap(current.path("initial"));
        var actual = new TreeMap<>(WorkspaceSnapshot.capture(root));
        actual.remove(".repair-lab-workspace.json");
        if (!actual.equals(initial)) { throw new IOException("试用输入与冻结开发摘要不一致"); }
        var store = SkillStore.archived(source, root);
        var candidate = store.candidate(config.candidate());
        var batch = store.batch(config.batch());
        if (config.requests() > batch.limit - batch.requests.size()) { throw new IOException("任务额度不能超过剩余批次额度"); }
        var record = new SkillTrial();
        record.id = UUID.randomUUID().toString(); record.project = root.toString().replace('\\', '/');
        record.projectId = marker.path("projectId").asText(); record.control = control.toString(); record.source = source.toString();
        record.catalog = catalogPath.toString(); record.catalogSha256 = SkillStore.digest(catalog);
        record.markerSha256 = WorkspaceSnapshot.hash(Files.readAllBytes(root.resolve(".repair-lab-workspace.json")));
        record.candidateId = candidate.id(); record.candidateSha256 = SkillStore.digest(candidate); record.batchId = batch.id;
        record.model = config.model(); record.maxRequests = config.requests(); record.goal = current.path("goal").asText();
        record.caseId = current.path("id").asText(); record.caseKind = current.path("kind").asText();
        record.currentManifestSha256 = SkillStore.digest(current); record.sourceManifestSha256 = SkillStore.digest(sourceManifest);
        record.configuration.putAll(Map.of("model", config.model(), "endpoint", config.endpoint(), "sampling", "provider-default",
                "commandTimeoutSeconds", "60", "httpReadTimeoutSeconds", "120", "toolResultChars", "32000"));
        if (record.goal.isBlank() || !initial.containsKey("GOAL.md")) { throw new IOException("开发目标缺失"); }
        record.initial = initial; record.acceptance = stringMap(current.path("acceptance"));
        if (!record.acceptance.containsKey("pom.xml") || !record.acceptance.keySet().stream().anyMatch(p -> p.startsWith("src/test/"))) {
            throw new IOException("冻结验收文件缺失");
        }
        for (var entry : record.acceptance.entrySet()) {
            if (!Objects.equals(record.initial.get(entry.getKey()), entry.getValue())) { throw new IOException("验收摘要不属于初始输入"); }
        }
        return record;
    }
    private static Map<String, String> stringMap(JsonNode node) throws IOException {
        if (!node.isObject() || node.isEmpty()) { throw new IOException("冻结文件摘要缺失"); }
        var values = new TreeMap<String, String>();
        for (var field : node.properties()) {
            Path path = Path.of(field.getKey());
            if (path.isAbsolute() || !path.normalize().equals(path) || path.startsWith("..")
                    || path.startsWith(".agent") || !field.getValue().isTextual() || !field.getValue().asText().matches("[a-f0-9]{64}")) {
                throw new IOException("冻结路径或摘要不合法");
            }
            values.put(field.getKey(), field.getValue().asText());
        }
        return values;
    }
    public int start(Config config, LLMClient model, CommandRunner commands) throws IOException {
        if (model == null) { throw new IOException("试用需要模型配置"); }
        SkillTrial record = prepare(config);
        try (var lock = new TaskStore(config.source()).lock()) {
            // 锁内重新核对批次剩余额度，不能由并发操作者超额启动。
            var sourceStore = SkillStore.archived(config.source(), root);
            var batch = sourceStore.batch(record.batchId);
            if (record.maxRequests > batch.limit - batch.requests.size()) { throw new IOException("批次剩余额度已改变"); }
            save(record);
            out.println("技能试用: " + record.id);
            var knowledge = new SkillKnowledge(this, record, sourceStore);
            CommandRunner restricted = (dir, command, timeout) -> {
                if (!root.equals(dir.toRealPath()) || !record.command.equals(command)) { throw new SecurityException("试用只允许冻结验收命令"); }
                return commands.run(dir, command, 60);
            };
            boolean finished = false;
            try {
                var cli = new CodingAgentCli(root, knowledge.audit(model), true, record.maxRequests,
                        new BufferedReader(new StringReader("")), out, out, null, restricted);
                int code = cli.startManagedTask(record.goal, record.command, false, KnowledgeMode.SKILL, knowledge);
                record.status = code == 0 ? "VERIFIED" : code == 1 ? "PAUSED" : "ERROR";
                finished = true;
                return code;
            } finally {
                if (!finished) { record.status = "UNKNOWN"; }
                archiveResult(record);
                save(record);
                print(record);
            }
        }
    }
    void check(SkillTrial record, SkillStore sourceStore) throws IOException {
        var marker = identity(Path.of(record.control));
        if (!record.projectId.equals(marker.path("projectId").asText())
                || !record.markerSha256.equals(WorkspaceSnapshot.hash(Files.readAllBytes(root.resolve(".repair-lab-workspace.json"))))) {
            throw new IOException("试用项目标记已改变");
        }
        if (!record.catalogSha256.equals(SkillStore.digest(read(Path.of(record.catalog))))
                || !record.currentManifestSha256.equals(SkillStore.digest(read(Path.of(record.control).resolve("current.json"))))
                || !record.sourceManifestSha256.equals(SkillStore.digest(read(Path.of(record.source).getParent().resolve("manifest.json"))))
                || !record.candidateSha256.equals(SkillStore.digest(sourceStore.candidate(record.candidateId)))) {
            throw new IOException("试用候选、来源或开发清单已改变");
        }
        for (String name : record.acceptance.keySet()) {
            if (!record.acceptance.get(name).equals(WorkspaceSnapshot.hash(Files.readAllBytes(root.resolve(name))))) {
                throw new IOException("冻结验收输入已改变");
            }
        }
    }
    private Path directory(Path control, String id) throws IOException {
        SkillStore.requireId(id); canonical(control);
        Path folder = control.resolve("skill-trials").resolve(id);
        for (Path path = folder; path != null && path.startsWith(control); path = path.getParent()) {
            if (Files.exists(path) && !canonical(path).startsWith(control)) { throw new IOException("试用存储越出控制归档"); }
        }
        return folder;
    }
    void save(SkillTrial record) throws IOException {
        var wrapper = SkillStore.JSON.createObjectNode();
        wrapper.set("value", SkillStore.JSON.valueToTree(record)); wrapper.put("sha256", SkillStore.digest(record));
        atomic(directory(Path.of(record.control), record.id).resolve("state.json"), SkillStore.JSON.writeValueAsBytes(wrapper));
    }
    private static void atomic(Path file, byte[] bytes) throws IOException {
        if (Files.exists(file) && (Files.isSymbolicLink(file) || !file.toRealPath().equals(file))) { throw new IOException("试用文件存在路径别名"); }
        Files.createDirectories(file.getParent());
        Path temporary = Files.createTempFile(file.getParent(), "trial-", ".tmp");
        try {
            try (var channel = FileChannel.open(temporary, StandardOpenOption.WRITE)) {
                var buffer = ByteBuffer.wrap(bytes); while (buffer.hasRemaining()) { channel.write(buffer); } channel.force(true);
            }
            Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally { Files.deleteIfExists(temporary); }
    }
    private void archiveResult(SkillTrial record) throws IOException {
        if (record.taskId == null) { return; }
        Path folder = directory(Path.of(record.control), record.id);
        Path destination = folder.resolve("result-root");
        if (Files.exists(destination, LinkOption.NOFOLLOW_LINKS)) { throw new IOException("试用结果归档已经存在，拒绝覆盖"); }
        Path staging = Files.createDirectory(folder.resolve("result-staging-" + UUID.randomUUID()));
        try (var paths = Files.walk(root)) {
            for (Path path : paths.toList()) {
                canonical(path);
                Path relative = root.relativize(path);
                if (relative.getNameCount() > 0 && relative.getName(0).toString().equals("target")) { continue; }
                if (Files.isRegularFile(path)) { atomic(staging.resolve(relative), Files.readAllBytes(path)); }
            }
        }
        var evidence = TaskStore.readOnlyArchive(staging, root);
        var task = evidence.read(record.taskId);
        if (record.status.equals("VERIFIED") && !evidence.isCurrentVerified(task)) { throw new IOException("归档现场与独立验收证据不一致"); }
        Files.move(staging, destination, StandardCopyOption.ATOMIC_MOVE);
        record.reason = task.reason;
    }
    private SkillTrial readTrial(Path control, String id) throws IOException {
        var wrapper = read(directory(control, id).resolve("state.json"));
        if (wrapper == null || !wrapper.hasNonNull("value") || !SkillStore.digest(wrapper.get("value")).equals(wrapper.path("sha256").asText())) {
            throw new IOException("试用审计完整性校验失败");
        }
        var record = SkillStore.JSON.treeToValue(wrapper.get("value"), SkillTrial.class);
        if (record.schemaVersion != 1 || !id.equals(record.id) || !root.toString().replace('\\', '/').equals(record.project)
                || !control.toString().equals(record.control) || record.requests == null || record.events == null
                || record.maxRequests <= 0 || record.requests.size() > record.maxRequests) { throw new IOException("试用记录损坏或属于其他项目"); }
        var batch = SkillStore.archived(Path.of(record.source), root).batch(record.batchId);
        for (var request : record.requests) {
            var entry = batch.requests.stream().filter(r -> r.id.equals(request.id)).findFirst()
                    .orElseThrow(() -> new IOException("试用请求缺少批次预留"));
            if (!id.equals(entry.trialId) || !Objects.equals(record.taskId, entry.taskId)
                    || !Objects.equals(request.sha256, entry.requestSha256) || !record.candidateId.equals(entry.candidateId)
                    || !record.candidateSha256.equals(entry.candidateSha256)
                    || !Objects.equals(entry.auditSha256, requestDigest(request.messages, request.tools))) { throw new IOException("试用请求关联或最终消息审计损坏"); }
        }
        if (record.taskId != null) {
            var evidence = evidenceStore(record);
            if (evidence != null) {
                var task = evidence.read(record.taskId);
                if (!id.equals(task.skillTrialId) || record.status.equals("VERIFIED") && !evidence.isCurrentVerified(task)) {
                    throw new IOException("试用任务绑定或验收状态损坏");
                }
            } else if (record.status.equals("VERIFIED")) { throw new IOException("已验证试用缺少任务证据"); }
        }
        return record;
    }
    public void show(Path control, String id) throws IOException {
        var record = readTrial(canonical(control), id);
        print(record);
        for (var request : record.requests) {
            out.println("最终请求审计 " + request.id + ": " + SkillStore.JSON.writeValueAsString(Map.of("messages", request.messages, "tools", request.tools)));
        }
    }
    public void list(Path control) throws IOException {
        canonical(control);
        Path folder = control.resolve("skill-trials");
        if (!Files.exists(folder)) { out.println("暂无技能试用。"); return; }
        canonical(folder);
        try (var children = Files.list(folder)) { for (Path child : children.sorted().toList()) { print(readTrial(control, child.getFileName().toString())); } }
    }
    private void print(SkillTrial record) throws IOException {
        out.println("技能试用: " + record.id + "\n状态: " + record.status + "\n候选: " + record.candidateId + "@" + record.candidateSha256
                + "\n任务: " + record.taskId + "\n已加载: " + record.loaded + "\n声明: " + record.declaration + "；" + record.declarationReason
                + "\n试用请求: " + record.requests.size() + "\n原因: " + record.reason);
        out.println("开发样例: " + record.caseId + "（" + record.caseKind + "）\n冻结配置: " + record.configuration + "\n冻结输入摘要: " + record.initial);
        if (Set.of("PREPARED", "RUNNING").contains(record.status)) { out.println("尚无最终完成记录，结果未知；不自动重发请求"); }
        SkillCandidates.printBatch(SkillStore.archived(Path.of(record.source), root).batch(record.batchId), out);
        for (var event : record.events) { out.println("事件: " + event); }
        for (var request : record.requests) {
            out.println("请求: " + request.id + "；状态: " + request.status + "；摘要: " + request.sha256
                    + "；目录已组装=" + request.catalogIncluded + "；指南已组装=" + request.guideIncluded
                    + "；输入token=" + (request.promptTokens == null ? "未知" : request.promptTokens)
                    + "；输出token=" + (request.completionTokens == null ? "未知" : request.completionTokens));
        }
        if (record.taskId != null) {
            var tasks = evidenceStore(record);
            if (tasks == null) { out.println("任务证据尚未归档且当前根中不可用，操作结果未知"); return; }
            var task = tasks.read(record.taskId);
            out.println("任务已保存状态: " + task.state + "\n任务累计请求: " + task.progress.requests + "/" + task.progress.maxRequests
                    + "\n工具调用: " + task.progress.tools + "\n任务输入token: " + task.progress.promptTokens
                    + "\n任务输出token: " + task.progress.completionTokens + "\n任务用量未知请求: " + task.progress.unknownRequests);
            out.println("任务耗时ms: " + task.progress.elapsedMillis);
            for (var check : task.checks) {
                var evidence = tasks.readEvidence(task, check);
                out.println("验收: " + evidence.kind() + " " + evidence.verdict() + "；证据=" + check.id());
            }
            for (var decision : task.progress.decisions) {
                for (var operation : decision.operations) {
                    out.println("操作: " + operation.callId + " " + operation.tool + " " + operation.status + "；文件变化=" + operation.changes.keySet());
                }
            }
        }
    }
    private TaskStore evidenceStore(SkillTrial record) throws IOException {
        Path archived = directory(Path.of(record.control), record.id).resolve("result-root");
        if (Files.exists(archived)) {
            try {
                var evidence = TaskStore.readOnlyArchive(canonical(archived), root);
                evidence.read(record.taskId);
                return evidence;
            } catch (IOException incomplete) {
                if (record.status.equals("VERIFIED")) { throw incomplete; }
                // 未完成发布的旧现场不遮蔽仍可核查的当前任务；不把回退解释为验证通过。
            }
        }
        if (record.taskId != null && Files.isRegularFile(root.resolve(".agent/tasks/" + record.taskId + "/state.json"))) {
            var marker = read(root.resolve(".repair-lab-workspace.json"));
            if (!record.projectId.equals(marker.path("projectId").asText())) { throw new IOException("当前任务现场属于其他项目身份"); }
            return TaskStore.readOnlyArchive(root, root);
        }
        return null;
    }
    LLMResponse request(SkillTrial record, SkillStore store, com.agent.task.TaskRecord task,
                        LLMClient model, List<Message> messages, List<LLMClient.ToolDef> tools, String directory, String guide) throws IOException {
        check(record, store);
        var batch = store.batch(record.batchId);
        if (batch.requests.size() >= batch.limit) { throw new IOException("试用批次额度耗尽，未发送"); }
        var entry = new SkillBatch.Request(); entry.kind = SkillBatch.Kind.TRIAL;
        entry.id = UUID.randomUUID().toString(); entry.candidateId = record.candidateId; entry.candidateSha256 = record.candidateSha256;
        entry.sources = store.candidate(record.candidateId).sources(); entry.trialId = record.id; entry.taskId = task.id;
        entry.requestSha256 = requestDigest(messages, tools); entry.reservedAt = System.currentTimeMillis();
        var packet = new SkillTrial.Request(); packet.id = entry.id; packet.sha256 = entry.requestSha256; packet.decision = task.progress.requests;
        var arguments = new HashMap<String, String>();
        messages.stream().filter(m -> m.toolCalls() != null).flatMap(m -> m.toolCalls().stream()).forEach(c -> arguments.put(c.id(), c.arguments()));
        packet.messages = messages.stream().map(m -> new Message(m.role(), auditText(m.content(), arguments.get(m.toolCallId())),
                m.toolCalls() == null ? null : m.toolCalls().stream().map(c -> new ToolCall(c.id(), new ToolCall.Function(c.name(), auditText(c.arguments(), c.arguments())))).toList(), m.toolCallId())).toList();
        packet.tools = List.copyOf(tools);
        entry.auditSha256 = requestDigest(packet.messages, packet.tools);
        batch.schemaVersion = 2; batch.requests.add(entry); store.write("batches", batch.id, batch);
        packet.catalogIncluded = !directory.isEmpty() && messages.stream().anyMatch(m -> m.content() != null && m.content().contains(directory));
        packet.guideIncluded = record.loaded && messages.stream().anyMatch(m -> m.content() != null && m.content().contains(guide));
        record.requests.add(packet); save(record);
        try {
            var response = model.chat(messages, tools);
            if (response == null) { throw new IOException("模型未返回响应"); }
            entry.promptTokens = response.promptTokens() < 0 ? null : response.promptTokens();
            entry.completionTokens = response.completionTokens() < 0 ? null : response.completionTokens();
            entry.state = SkillBatch.State.RESPONDED; store.write("batches", batch.id, batch);
            packet.promptTokens = entry.promptTokens; packet.completionTokens = entry.completionTokens;
            packet.status = "RESPONDED"; save(record);
            return response;
        } catch (IOException failure) {
            entry.state = SkillBatch.State.FAILED; entry.failure = "试用请求或审计失败，未自动重试";
            packet.status = "FAILED";
            packet.promptTokens = entry.promptTokens; packet.completionTokens = entry.completionTokens;
            try { store.write("batches", batch.id, batch); save(record); } catch (IOException failedSave) { failure.addSuppressed(failedSave); }
            throw failure;
        }
    }
    private static String requestDigest(List<Message> messages, List<LLMClient.ToolDef> tools) throws IOException {
        var payload = SkillStore.JSON.createObjectNode();
        payload.set("messages", SkillStore.JSON.valueToTree(messages));
        payload.set("tools", SkillStore.JSON.valueToTree(tools));
        return SkillStore.digest(payload);
    }
    private static String auditText(String text, String arguments) {
        if (text == null) { return null; }
        if (Redactor.redact(text, arguments).redacted()
                || Redactor.redact(text.replace("\"", "").replace("'", ""), arguments).redacted()) { return Redactor.PLACEHOLDER; }
        return text;
    }
}
