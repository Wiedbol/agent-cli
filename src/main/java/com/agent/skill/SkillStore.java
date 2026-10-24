package com.agent.skill;

import com.agent.experience.ExperienceLibrary;
import com.agent.session.Redactor;
import com.agent.task.WorkspaceSnapshot;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** 技能元数据按项目隔离并校验摘要；查询不创建文件，不把半写候选当作已审计结果。 */
public final class SkillStore {
    static final ObjectMapper JSON = new ObjectMapper()
            .enable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
    private final Path root;
    private final Path directory;
    private final Path originalProject;

    /** 后续发布操作的持久化契约；此处只有读取能力，不提供批准或启用入口。 */
    public record Version(int schemaVersion, String id, String skillId, String project,
                          String candidateId, String candidateSha256, long approvedAt, boolean enabled) { }

    public SkillStore(Path root) throws IOException {
        this(root, null);
    }
    static SkillStore archived(Path archive, Path originalProject) throws IOException {
        if (!archive.isAbsolute() || !archive.normalize().equals(archive.toRealPath()) || Files.isSymbolicLink(archive)) {
            throw new IOException("来源归档需要规范绝对路径");
        }
        return new SkillStore(archive, originalProject.toRealPath());
    }
    private SkillStore(Path root, Path originalProject) throws IOException {
        this.root = root.toRealPath();
        this.originalProject = originalProject;
        directory = this.root.resolve(".agent/skills");
        check(directory);
    }
    String project() { return (originalProject == null ? root : originalProject).toString().replace('\\', '/'); }
    static void requireId(String id) throws IOException {
        if (id == null || !id.matches("[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}")) {
            throw new IOException("技能标识不合法");
        }
    }
    private Path path(String group, String id, String suffix) throws IOException {
        requireId(id);
        Path path = directory.resolve(group).resolve(id + suffix);
        check(path);
        return path;
    }
    private void check(Path path) throws IOException {
        for (Path part = path; part != null && part.startsWith(root); part = part.getParent()) {
            if (Files.exists(part, LinkOption.NOFOLLOW_LINKS)) {
                WorkspaceSnapshot.requireInside(root, part);
                if (Files.isSymbolicLink(part)) { throw new IOException("技能目录不支持符号链接"); }
            }
        }
    }
    static String digest(Object value) throws IOException { return WorkspaceSnapshot.hash(JSON.writeValueAsBytes(value)); }
    static void requireSafe(String text) throws IOException {
        if (text == null || text.contains(Redactor.PLACEHOLDER) || Redactor.redact(text).redacted()
                || Redactor.redact(text.replace("\"", "").replace("'", "")).redacted()) {
            throw new IOException("技能必要内容含敏感信息或已脱敏");
        }
    }
    static void validateGuide(SkillCandidate.Guide guide) throws IOException {
        if (guide == null) { throw new IOException("缺少技能指南"); }
        for (String text : Arrays.asList(guide.title(), guide.conditions(), guide.exclusions(), guide.diagnosis(),
                guide.repair(), guide.verification())) {
            if (text == null || text.isBlank() || text.length() > 4000) { throw new IOException("技能指南字段缺失或过长"); }
            requireSafe(text);
        }
    }
    private <T> T read(String group, String id, Class<T> type) throws IOException {
        var wrapper = JSON.readTree(Files.readAllBytes(path(group, id, ".json")));
        if (wrapper == null || !wrapper.hasNonNull("value")
                || !digest(wrapper.get("value")).equals(wrapper.path("sha256").asText())) {
            throw new IOException("技能完整性校验失败");
        }
        var value = wrapper.get("value");
        if (!value.path("schemaVersion").isInt() || !value.path("id").isTextual() || !value.path("project").isTextual()
                || type == SkillBatch.class && (!value.path("requests").isArray() || !value.path("limit").isInt())
                || type == Version.class && !value.path("enabled").isBoolean()) {
            throw new IOException("技能状态字段缺失或类型不合法");
        }
        if (type == SkillBatch.class) {
            for (var request : value.path("requests")) {
                if (!request.path("state").isTextual() || !request.path("sources").isArray()
                        || !request.has("promptTokens") || !request.has("completionTokens")
                        || !request.has("parent") || !request.has("revisionReason")) {
                    throw new IOException("技能请求状态字段缺失");
                }
                if (value.path("schemaVersion").asInt() >= 2 && (!request.path("kind").isTextual()
                        || !request.has("trialId") || !request.has("taskId") || !request.has("requestSha256") || !request.has("auditSha256"))) {
                    throw new IOException("试用批次请求字段缺失");
                }
            }
        }
        return JSON.treeToValue(wrapper.get("value"), type);
    }
    static SkillCandidate.Guide parseGuide(String content) throws IOException {
        var node = JSON.readTree(content);
        if (node == null || !node.isObject() || node.size() != 6) { throw new IOException("技能响应必须仅含六项指南文本"); }
        for (String field : List.of("title", "conditions", "exclusions", "diagnosis", "repair", "verification")) {
            if (!node.path(field).isTextual()) { throw new IOException("技能指南字段必须是文本: " + field); }
        }
        return JSON.treeToValue(node, SkillCandidate.Guide.class);
    }
    void write(String group, String id, Object value) throws IOException {
        var wrapper = JSON.createObjectNode();
        wrapper.set("value", JSON.valueToTree(value));
        wrapper.put("sha256", digest(value));
        atomic(path(group, id, ".json"), JSON.writeValueAsBytes(wrapper));
    }
    private void atomic(Path path, byte[] bytes) throws IOException {
        if (originalProject != null && !path.getParent().equals(directory.resolve("batches"))) {
            throw new IOException("来源归档只允许追加受控批次账本，不写知识或任务证据");
        }
        check(path);
        Files.createDirectories(path.getParent());
        check(path);
        Path temporary = Files.createTempFile(path.getParent(), "skill-", ".tmp");
        try {
            try (var channel = FileChannel.open(temporary, StandardOpenOption.WRITE)) {
                ByteBuffer buffer = ByteBuffer.wrap(bytes);
                while (buffer.hasRemaining()) { channel.write(buffer); }
                channel.force(true);
            }
            Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally { Files.deleteIfExists(temporary); }
    }
    List<String> ids(String group) throws IOException {
        Path folder = directory.resolve(group);
        check(folder);
        if (!Files.exists(folder)) { return List.of(); }
        try (var paths = Files.list(folder)) {
            return paths.filter(p -> p.getFileName().toString().endsWith(".json"))
                    .map(p -> p.getFileName().toString().replaceFirst("\\.json$", "")).sorted().toList();
        }
    }
    public SkillBatch batch(String id) throws IOException {
        var batch = read("batches", id, SkillBatch.class);
        if ((batch.schemaVersion != 1 && batch.schemaVersion != 2) || !id.equals(batch.id) || !project().equals(batch.project)
                || batch.limit <= 0 || batch.requests == null || batch.requests.size() > batch.limit) {
            throw new IOException("技能批次记录损坏或不属于本项目");
        }
        var seen = new HashSet<String>();
        for (var request : batch.requests) {
            if (request == null) { throw new IOException("技能请求记录缺失"); }
            requireId(request.id);
            requireId(request.candidateId);
            if (!seen.add(request.id) || request.reservedAt <= 0 || request.state == null
                    || request.sources == null || request.sources.isEmpty()
                    || request.promptTokens != null && request.promptTokens < 0
                    || request.completionTokens != null && request.completionTokens < 0
                    || request.state == SkillBatch.State.COMPLETED && (request.candidateSha256 == null
                    || !request.candidateSha256.matches("[a-f0-9]{64}"))) {
                throw new IOException("技能请求记录不完整");
            }
            if (request.kind == null || request.kind == SkillBatch.Kind.GENERATION
                    && (request.trialId != null || request.taskId != null || request.requestSha256 != null || request.auditSha256 != null)) {
                throw new IOException("请求类型与试用引用不一致");
            }
            if (request.kind == SkillBatch.Kind.TRIAL) {
                requireId(request.trialId);
                requireId(request.taskId);
                if (batch.schemaVersion < 2 || request.requestSha256 == null || !request.requestSha256.matches("[a-f0-9]{64}")
                        || request.auditSha256 == null || !request.auditSha256.matches("[a-f0-9]{64}")) {
                    throw new IOException("试用请求记录损坏");
                }
            }
            for (var source : request.sources) {
                if (source == null || source.experience() == null || source.sha256() == null
                        || !digest(source.experience()).equals(source.sha256())
                        || !project().equals(source.experience().project())) { throw new IOException("请求来源记录损坏"); }
            }
        }
        return batch;
    }
    List<SkillCandidate.Source> sources(List<String> ids) throws IOException {
        if (ids == null || ids.isEmpty() || ids.size() > 10 || new HashSet<>(ids).size() != ids.size()) {
            throw new IOException("需要一至十条不重复的来源经验");
        }
        var library = originalProject == null ? new ExperienceLibrary(root) : ExperienceLibrary.readOnlyArchive(root, originalProject);
        var result = new ArrayList<SkillCandidate.Source>();
        for (String id : ids.stream().sorted().toList()) {
            var experience = library.read(id);
            if (!experience.enabled() || !library.sourceValid(experience)) { throw new IOException("来源经验不可用: " + id); }
            result.add(new SkillCandidate.Source(experience, digest(experience)));
        }
        return List.copyOf(result);
    }
    public SkillCandidate candidate(String id) throws IOException {
        var candidate = read("candidates", id, SkillCandidate.class);
        if (candidate.schemaVersion() != 1 || !id.equals(candidate.id()) || !project().equals(candidate.project())
                || candidate.createdAt() <= 0 || candidate.sources() == null || candidate.sources().isEmpty()) {
            throw new IOException("候选记录损坏或不属于本项目");
        }
        requireId(candidate.skillId());
        validateGuide(candidate.guide());
        if (candidate.sources().stream().anyMatch(s -> s == null || s.experience() == null)) { throw new IOException("候选来源引用缺失"); }
        if (!candidate.sources().equals(sources(candidate.sources().stream().map(s -> s.experience().id()).toList()))) {
            throw new IOException("候选来源摘要已改变");
        }
        if (!candidate.guide().markdown().equals(Files.readString(path("candidates", id, ".md")))) {
            throw new IOException("候选 Markdown 已被改写");
        }
        if (candidate.parent() != null) {
            var parent = parent(candidate.parent().versionId());
            if (!candidate.parent().equals(parent) || !candidate.skillId().equals(parent.skillId())
                    || candidate.revisionReason() == null || candidate.revisionReason().isBlank()) {
                throw new IOException("候选父版本关联已改变");
            }
        } else if (candidate.revisionReason() != null) { throw new IOException("新增候选不能携带修订目标"); }
        var request = batch(candidate.batchId()).requests.stream().filter(r -> r.id.equals(candidate.requestId()))
                .findFirst().orElseThrow(() -> new IOException("候选缺少请求账本"));
        if (request.state != SkillBatch.State.COMPLETED || !id.equals(request.candidateId)
                || !digest(candidate).equals(request.candidateSha256) || !candidate.sources().equals(request.sources)
                || !Objects.equals(candidate.parent(), request.parent)
                || !Objects.equals(candidate.revisionReason(), request.revisionReason)) { throw new IOException("候选尚未完整审计或摘要不符"); }
        return candidate;
    }
    SkillCandidate.Parent parent(String id) throws IOException {
        var version = read("versions", id, Version.class);
        if (version.schemaVersion() != 1 || !id.equals(version.id()) || !project().equals(version.project())
                || version.approvedAt() <= 0 || !version.enabled()) { throw new IOException("父技能版本未批准、不可用或跨项目"); }
        var candidate = candidate(version.candidateId());
        if (!candidate.skillId().equals(version.skillId()) || !digest(candidate).equals(version.candidateSha256())) {
            throw new IOException("父版本与批准内容不一致");
        }
        return new SkillCandidate.Parent(version.skillId(), id, digest(version));
    }
    SkillCandidate.Guide parentGuide(String id) throws IOException {
        parent(id);
        return candidate(read("versions", id, Version.class).candidateId()).guide();
    }
    void saveCandidate(SkillCandidate candidate) throws IOException {
        validateGuide(candidate.guide());
        atomic(path("candidates", candidate.id(), ".md"), candidate.guide().markdown().getBytes(StandardCharsets.UTF_8));
        write("candidates", candidate.id(), candidate);
    }
}
