package com.agent.skill;

import com.agent.task.*;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.*;
import java.nio.file.*;
import java.util.*;

/** 审核复用实际归档证据；操作者判断声明和动作，不把成功验收当作迁移收益。 */
public final class SkillReviews {
    public record Selection(Path control, Path source, String candidate, String sha256, String related, String counterexample,
                            @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL) List<String> regressions) {
        public Selection(Path control, Path source, String candidate, String sha256, String related, String counterexample) {
            this(control, source, candidate, sha256, related, counterexample, null);
        }
    }
    private final Path root;
    private final PrintStream out;
    private final SkillTrials trials;
    public SkillReviews(Path root, PrintStream out) throws IOException {
        this.root = root.toRealPath(); this.out = out; trials = new SkillTrials(this.root, out);
    }
    private record Evidence(SkillTrial trial, TaskRecord task, JsonNode criteria, String sha256) { }
    private record Review(SkillCandidate candidate, SkillStore store, Evidence related, Evidence counterexample, List<Evidence> regressions) { }
    public record Attestation(int schemaVersion, String id, String project, String candidateSha256,
                              String evidenceSha256, String note, long at) { }
    public record Publication(int schemaVersion, String id, String project, Selection selection, String versionId,
                              String relatedSha256, String counterexampleSha256, Attestation attestation, long at,
                              @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL) Map<String, String> regressions) {
        public Publication(int schemaVersion, String id, String project, Selection selection, String versionId,
                           String relatedSha256, String counterexampleSha256, Attestation attestation, long at) {
            this(schemaVersion, id, project, selection, versionId, relatedSha256, counterexampleSha256, attestation, at, null);
        }
    }
    public record Rejection(int schemaVersion, String id, String project, String candidateSha256, String reason, long at) { }
    private Evidence evidence(Selection selection, String id, String kind, SkillCandidate candidate) throws IOException {
        var trial = trials.readTrial(selection.control(), id);
        if (!selection.source().toString().equals(trial.source) || !selection.candidate().equals(trial.candidateId)
                || !selection.sha256().equals(trial.candidateSha256) || !kind.equals(trial.caseKind)
                || !"VERIFIED".equals(trial.status) || !trial.loaded || trial.requests.isEmpty()
                || trial.requests.stream().anyMatch(r -> !"RESPONDED".equals(r.status))
                || trial.requests.stream().noneMatch(r -> r.guideIncluded && r.catalogIncluded)) {
            throw new IOException("试用未完整验证、未实际发送指南或与候选不符");
        }
        var catalog = SkillTrials.read(Path.of(trial.catalog));
        if (!SkillStore.digest(catalog).equals(trial.catalogSha256)) { throw new IOException("冻结开发清单已改变"); }
        JsonNode scenario = null;
        for (var entry : catalog.path("cases")) { if (trial.caseId.equals(entry.path("id").asText())) { scenario = entry; } }
        if (scenario == null || !SkillStore.digest(scenario).equals(trial.currentManifestSha256)
                || !scenario.path("kind").asText().equals(kind) || trial.caseId.startsWith("formal-")
                || !SkillStore.digest(SkillTrials.read(selection.source().getParent().resolve("manifest.json"))).equals(trial.sourceManifestSha256)) {
            throw new IOException("试用冻结输入或来源已改变");
        }
        Path result = selection.control().resolve("skill-trials").resolve(id).resolve("result-root");
        var tasks = TaskStore.readOnlyArchive(SkillTrials.canonical(result), root);
        var task = tasks.read(trial.taskId);
        if (!tasks.isCurrentVerified(task) || task.progress.requests != trial.requests.size()
                || task.progress.decisions.stream().anyMatch(d -> !d.roundFinished && !d.operations.isEmpty() || d.status != TaskRecord.DecisionState.RESPONDED
                || d.operations.stream().anyMatch(op -> op.status == TaskRecord.OperationState.RUNNING || op.status == TaskRecord.OperationState.REQUESTED))) {
            throw new IOException("试用任务或操作记录不完整");
        }
        var operations = task.progress.decisions.stream().flatMap(d -> d.operations.stream()).toList();
        var loads = new HashSet<String>();
        for (var operation : operations) {
            if (!"load_skill".equals(operation.tool) || operation.status != TaskRecord.OperationState.SUCCEEDED) { continue; }
            var arguments = SkillStore.JSON.readTree(operation.arguments);
            if (candidate.id().equals(arguments.path("id").asText()) && trial.candidateSha256.equals(arguments.path("version").asText())) { loads.add(operation.callId); }
        }
        boolean actualGuide = trial.requests.stream().anyMatch(r -> r.messages.stream().anyMatch(m -> "tool".equals(m.role())
                && loads.contains(m.toolCallId()) && m.content() != null && m.content().contains(candidate.guide().markdown())));
        if (loads.isEmpty() || !actualGuide) { throw new IOException("缺少实际加载工具与指南请求证据"); }
        var baseline = tasks.readEvidence(task, task.checks.get(0));
        var finalCheck = tasks.readEvidence(task, task.checks.get(task.checks.size() - 1));
        var initial = new TreeMap<>(baseline.files()); initial.remove(".repair-lab-workspace.json");
        if (!"FAILED".equals(baseline.verdict()) || !baseline.codingAllowed() || !"PASSED".equals(finalCheck.verdict())
                || !initial.equals(trial.initial) || !task.acceptanceFiles.equals(trial.acceptance)
                || !scenario.path("initial").equals(SkillStore.JSON.valueToTree(trial.initial))
                || !scenario.path("acceptance").equals(SkillStore.JSON.valueToTree(trial.acceptance))
                || !task.goal.equals(trial.goal) || !task.command.equals(trial.command)
                || !trial.projectId.equals(SkillTrials.read(selection.control().resolve("owner.json")).path("marker").path("projectId").asText())) {
            throw new IOException("试用初始失败、输入、验收或项目身份绑定不一致");
        }
        JsonNode criteria = scenario.path("exclusion");
        if (kind.equals("exclusion")) {
            for (String field : List.of("wrongStep", "rejectionEvidence", "normalRepairEvidence", "observableConstraint")) {
                if (!criteria.path(field).isTextual() || criteria.path(field).asText().isBlank()) { throw new IOException("缺少试用前冻结的反例判据"); }
            }
            if (!"REJECT".equals(trial.declaration) || trial.declarationReason == null || trial.declarationReason.isBlank()) {
                throw new IOException("反例缺少明确拒绝声明，不能自动通过");
            }
            boolean declaration = false;
            for (var operation : operations) {
                if (!"declare_skill".equals(operation.tool) || operation.status != TaskRecord.OperationState.SUCCEEDED) { continue; }
                var arguments = SkillStore.JSON.readTree(operation.arguments);
                if (candidate.id().equals(arguments.path("id").asText()) && trial.candidateSha256.equals(arguments.path("version").asText())
                        && "REJECT".equals(arguments.path("decision").asText()) && trial.declarationReason.equals(arguments.path("reason").asText())) { declaration = true; }
            }
            String constraint = criteria.path("observableConstraint").asText();
            if (!declaration || finalCheck.identities().stream().noneMatch(test -> test.endsWith("#" + constraint))) {
                throw new IOException("反例缺少真实拒绝操作或冻结约束的独立验收");
            }
        }
        var payload = SkillStore.JSON.createObjectNode();
        payload.set("trial", SkillStore.JSON.valueToTree(trial));
        payload.set("task", SkillStore.JSON.valueToTree(task)); payload.set("criteria", criteria);
        return new Evidence(trial, task, criteria, SkillStore.digest(payload));
    }
    private Review inspect(Selection selection) throws IOException {
        var store = trials.library(selection.control(), selection.source());
        var candidate = store.candidate(selection.candidate());
        return inspectTrials(selection, store, candidate);
    }
    private Review inspectTrials(Selection selection, SkillStore store, SkillCandidate candidate) throws IOException {
        return inspectTrials(selection, store, candidate, true);
    }
    private Review inspectTrials(Selection selection, SkillStore store, SkillCandidate candidate, boolean requireRegressions) throws IOException {
        if (!candidate.id().equals(selection.candidate()) || !SkillStore.digest(candidate).equals(selection.sha256())) { throw new IOException("待审核候选摘要不符"); }
        if (store.exists("rejections", candidate.id())) { throw new IOException("候选已拒绝，不能发布"); }
        var related = evidence(selection, selection.related(), "related", candidate);
        var counter = evidence(selection, selection.counterexample(), "exclusion", candidate);
        if (!related.trial().configuration.equals(counter.trial().configuration)
                || !related.trial().catalogSha256.equals(counter.trial().catalogSha256)
                || !related.trial().projectId.equals(counter.trial().projectId)) { throw new IOException("正反例模型/调用配置或开发清单不一致"); }
        var regressions = new ArrayList<Evidence>();
        if (candidate.parent() != null && requireRegressions) {
            if (candidate.feedback() != null && candidate.feedback().catalogSha256() != null
                    && !candidate.feedback().catalogSha256().equals(related.trial().catalogSha256)) { throw new IOException("试用清单不等于修订冻结的开发清单"); }
            var available = new TreeMap<String, Evidence>();
            available.put(related.trial().caseId, related); available.put(counter.trial().caseId, counter);
            var ids = selection.regressions() == null ? List.<String>of() : selection.regressions();
            if (ids.size() > 10 || new HashSet<>(ids).size() != ids.size()) { throw new IOException("旧开发回归试用重复或过多"); }
            for (String id : ids) {
                if (id.equals(selection.related()) || id.equals(selection.counterexample())) { continue; }
                var trial = trials.readTrial(selection.control(), id);
                var regression = evidence(selection, id, trial.caseKind, candidate);
                if (!related.trial().configuration.equals(trial.configuration) || !related.trial().catalogSha256.equals(trial.catalogSha256)
                        || !related.trial().projectId.equals(trial.projectId)) { throw new IOException("旧开发回归冻结配置不符"); }
                available.put(trial.caseId, regression);
            }
            var catalog = SkillTrials.read(Path.of(related.trial().catalog));
            for (var required : catalog.path("stages").path("required-regression")) {
                var regression = available.get(required.asText());
                if (regression == null) { throw new IOException("缺少必要旧开发回归: " + required.asText()); }
                regressions.add(regression);
            }
        }
        return new Review(candidate, store, related, counter, List.copyOf(regressions));
    }
    public void review(Selection selection) throws IOException {
        var store = trials.library(selection.control(), selection.source());
        var candidate = store.candidate(selection.candidate());
        if (candidate.feedback() != null) { out.println("选定反馈快照: " + SkillStore.JSON.writeValueAsString(candidate.feedback())); }
        out.println("技能审核: " + candidate.id() + "@" + SkillStore.digest(candidate) + "\n" + candidate.guide().markdown()
                + "\n父版本: " + candidate.parent() + "\n修订原因: " + candidate.revisionReason());
        var library = com.agent.experience.ExperienceLibrary.readOnlyArchive(selection.source(), root);
        for (var source : candidate.sources()) { out.println("来源摘要: " + source.sha256()); library.print(source.experience(), out); }
        if (candidate.parent() != null) {
            var before = SkillStore.JSON.valueToTree(store.parentGuide(candidate.parent().versionId()));
            var after = SkillStore.JSON.valueToTree(candidate.guide());
            for (var field : after.properties()) {
                if (!field.getValue().equals(before.path(field.getKey()))) {
                    out.println("内容差异 " + field.getKey() + "\n- " + before.path(field.getKey()).asText() + "\n+ " + field.getValue().asText());
                }
            }
        }
        if (store.exists("rejections", candidate.id())) { out.println("已拒绝: " + store.read("rejections", candidate.id(), Rejection.class)); }
        try {
            var review = inspect(selection);
            for (var evidence : List.of(review.related(), review.counterexample())) {
                printEvidence(evidence); trials.show(selection.control(), evidence.trial().id);
            }
            for (var regression : review.regressions()) { out.println("旧开发回归: " + regression.trial().caseId); printEvidence(regression); }
            out.println("结构/来源和正反例验收门槛已通过；仍须有效人工核验及交互批准，查看不会发布。");
        } catch (IOException blocked) {
            out.println("发布门槛未满足: " + blocked.getMessage());
            for (String id : Arrays.asList(selection.related(), selection.counterexample())) {
                if (id == null) { continue; }
                try { trials.show(selection.control(), id); } catch (IOException unavailable) { out.println("试用不可用: " + unavailable.getMessage()); }
            }
        }
    }
    public boolean reject(Path control, Path source, String id, String sha256, String reason, BufferedReader input, boolean interactive) throws IOException {
        if (!interactive) { out.println("拒绝候选需要交互确认，--yes 不适用"); return false; }
        if (reason == null || reason.isBlank() || reason.length() > 2000) { throw new IOException("拒绝需要具体原因"); }
        SkillStore.requireSafe(reason);
        trials.library(control, source);
        try (var writer = new TaskStore(source).lock()) {
            var store = trials.library(control, source);
            if (!SkillStore.digest(store.candidate(id)).equals(sha256)) { throw new IOException("待拒绝候选摘要不符"); }
            for (String version : store.ids("versions")) {
                if (store.historicalSnapshot(version).version().candidateId().equals(id)) { throw new IOException("已发布候选不能通过拒绝改写版本历史"); }
            }
            out.println("拒绝原因: " + reason + "\n请输入 reject " + id + " " + sha256); out.flush();
            if (!("reject " + id + " " + sha256).equals(input.readLine())) { out.println("未确认拒绝"); return false; }
            if (!SkillStore.digest(store.candidate(id)).equals(sha256)) { throw new IOException("确认期间候选变化"); }
            store.writeNew("rejections", id, new Rejection(1, id, store.project(), sha256, reason, System.currentTimeMillis()));
            out.println("候选已拒绝，来源任务成功状态不变"); return true;
        }
    }
    public boolean approve(Selection selection, BufferedReader input, boolean interactive) throws IOException {
        if (!interactive) { out.println("发布需要真实交互确认；--yes、管道和非交互输入不适用"); return false; }
        trials.library(selection.control(), selection.source());
        try (var writer = new TaskStore(selection.source()).lock()) {
            var review = inspect(selection);
            if (!review.store().exists("attestations", selection.counterexample())) { throw new IOException("缺少反例人工核验"); }
            var attestation = attestation(review);
            for (String id : review.store().ids("versions")) {
                if (review.store().historicalSnapshot(id).version().candidateId().equals(selection.candidate())) { throw new IOException("该候选已经发布，拒绝重复批准"); }
            }
            review(selection);
            out.println("以上包含全部轨迹和限制；批准不代表技能因果收益。请输入 approve " + selection.candidate() + " " + selection.sha256());
            out.flush();
            if (!("approve " + selection.candidate() + " " + selection.sha256()).equals(input.readLine())) {
                out.println("未明确批准，未发布"); return false;
            }
            var current = inspect(selection);
            if (!current.related().sha256().equals(review.related().sha256()) || !current.counterexample().sha256().equals(review.counterexample().sha256())
                    || !attestation.equals(attestation(current))) { throw new IOException("确认期间审核证据变化，未发布"); }
            long at = System.currentTimeMillis();
            for (String id : current.store().ids("versions")) { at = Math.max(at, current.store().historicalSnapshot(id).version().approvedAt() + 1); }
            String versionId = UUID.randomUUID().toString(), reviewId = UUID.randomUUID().toString();
            var publication = new Publication(current.candidate().parent() == null ? 1 : 2, reviewId, current.store().project(), selection, versionId,
                    current.related().sha256(), current.counterexample().sha256(), attestation, at,
                    current.candidate().parent() == null ? null : regressionDigests(current));
            current.store().versionMarkdown(versionId, current.candidate().guide().markdown());
            current.store().writeNew("reviews", reviewId, publication);
            var version = new SkillStore.Version(2, versionId, current.candidate().skillId(), current.store().project(),
                    selection.candidate(), selection.sha256(), at, true, reviewId, SkillStore.digest(publication));
            current.store().writeNew("versions", versionId, version);
            out.println("技能版本: " + versionId + "\n已发布不可变版本；旧版本保持可用");
            return true;
        }
    }
    private Attestation attestation(Review review) throws IOException {
        var evidence = review.counterexample();
        var value = review.store().read("attestations", evidence.trial().id, Attestation.class);
        if (value.schemaVersion() != 1 || !evidence.trial().id.equals(value.id()) || !review.store().project().equals(value.project())
                || !evidence.trial().candidateSha256.equals(value.candidateSha256()) || !evidence.sha256().equals(value.evidenceSha256())
                || value.at() <= 0 || value.note() == null || value.note().isBlank()) { throw new IOException("反例人工核验已失效"); }
        SkillStore.requireSafe(value.note());
        return value;
    }
    public boolean attest(Path control, String id, String note, BufferedReader input, boolean interactive) throws IOException {
        if (!interactive) { out.println("反例人工核验需要交互确认，--yes 不适用"); return false; }
        if (note == null || note.isBlank() || note.length() > 2000) { throw new IOException("人工核验需要具体轨迹说明"); }
        SkillStore.requireSafe(note);
        var record = trials.readTrial(SkillTrials.canonical(control), id);
        var selection = new Selection(control, Path.of(record.source), record.candidateId, record.candidateSha256, null, id);
        trials.library(control, selection.source());
        try (var writer = new TaskStore(selection.source()).lock()) {
            var store = trials.library(control, selection.source());
            var candidate = store.candidate(record.candidateId);
            if (!SkillStore.digest(candidate).equals(record.candidateSha256)) { throw new IOException("反例候选内容已改变"); }
            var evidence = evidence(selection, id, "exclusion", candidate);
            trials.show(control, id);
            printEvidence(evidence);
            String confirmation = "attest " + id + " " + record.candidateSha256 + " " + evidence.sha256();
            out.println("须确认声明符合冻结判据、实际操作未执行错误步骤且正常修复通过。说明: " + note + "\n请输入 " + confirmation);
            out.flush();
            if (!confirmation.equals(input.readLine())) { out.println("未明确核验，未保存"); return false; }
            var after = evidence(selection, id, "exclusion", candidate);
            if (!evidence.sha256().equals(after.sha256()) || !SkillStore.digest(store.candidate(record.candidateId)).equals(record.candidateSha256)) {
                throw new IOException("确认期间反例证据改变");
            }
            store.writeNew("attestations", id, new Attestation(1, id, store.project(), record.candidateSha256, evidence.sha256(), note, System.currentTimeMillis()));
            out.println("已保存反例人工核验: " + id);
            return true;
        }
    }
    private void printEvidence(Evidence evidence) {
        out.println("试用: " + evidence.trial().id + " " + evidence.trial().caseKind + " " + evidence.trial().status
                + "\n证据摘要: " + evidence.sha256() + "\n冻结反例判据: " + evidence.criteria());
        for (var decision : evidence.task().progress.decisions) {
            for (var operation : decision.operations) { out.println("实际操作: " + operation.tool + " " + operation.arguments + " " + operation.status + " " + operation.changes); }
        }
    }
    void checkPublication(Publication publication, SkillCandidate candidate) throws IOException {
        if ((publication.schemaVersion() != 1 && publication.schemaVersion() != 2) || !root.toString().replace('\\', '/').equals(publication.project()) || publication.at() <= 0) {
            throw new IOException("批准记录属于其他项目或损坏");
        }
        var selection = publication.selection();
        // 调用者刚核验过候选及父链；这里核验其批准证据，避免递归重复读取同一父链。
        var current = inspectTrials(selection, trials.library(selection.control(), selection.source()), candidate, publication.schemaVersion() >= 2);
        if (!current.related().sha256().equals(publication.relatedSha256()) || !current.counterexample().sha256().equals(publication.counterexampleSha256())
                || !attestation(current).equals(publication.attestation())
                || publication.schemaVersion() >= 2 && !regressionDigests(current).equals(publication.regressions())) { throw new IOException("批准所依据的试用或人工核验已改变"); }
    }
    private static Map<String, String> regressionDigests(Review review) {
        var digests = new TreeMap<String, String>();
        for (var evidence : review.regressions()) { digests.put(evidence.trial().id, evidence.sha256()); }
        return digests;
    }
    /** 可用性变更另存历史；恢复只认可同一批准内容，不能借此换版或补造证据。 */
    public boolean setEnabled(Path control, Path source, String id, String sha256, boolean enabled, String reason,
                              BufferedReader input, boolean interactive) throws IOException {
        if (!interactive) { out.println("技能可用性变更需要交互确认；--yes 不适用"); return false; }
        if (reason == null || reason.isBlank() || reason.length() > 2000) { throw new IOException("技能可用性变更需要具体原因"); }
        SkillStore.requireSafe(reason);
        trials.library(control, source);
        try (var writer = new TaskStore(source).lock()) {
            var store = trials.library(control, source);
            var version = store.metadata(id).version();
            if (!SkillStore.digest(version).equals(sha256)) { throw new IOException("待变更版本摘要不符"); }
            var previous = store.availability(version);
            if (enabled) { store.historicalSnapshot(id); }
            String action = enabled ? "enable" : "disable";
            out.println("技能版本: " + id + "\n版本摘要: " + sha256 + "\n原因: " + reason + "\n请输入 " + action + " " + id + " " + sha256);
            out.flush();
            if (!(action + " " + id + " " + sha256).equals(input.readLine())) { out.println("未确认可用性变更"); return false; }
            version = store.metadata(id).version();
            if (!SkillStore.digest(version).equals(sha256) || !previous.equals(store.availability(version))) {
                throw new IOException("确认期间版本或可用性历史变化");
            }
            if (enabled) { store.historicalSnapshot(id); }
            store.availability(version, enabled, reason);
            out.println(enabled ? "原版已启用；任务仍需显式恢复并检查适用性" : "版本已停用；保留批准内容、来源和历史，不自动切换旧版");
            return true;
        }
    }
    public void versions(Path control, Path source, String selected) throws IOException {
        var store = trials.library(control, source);
        var versions = new ArrayList<SkillStore.Version>();
        boolean unavailable = false;
        for (String id : store.ids("versions")) {
            try {
                var version = store.historicalSnapshot(id).version();
                store.availability(version);
                versions.add(version);
            }
            catch (IOException invalid) {
                if (id.equals(selected)) { throw invalid; }
                unavailable = true;
                out.println("其他版本不可用: " + id + "；" + invalid.getMessage());
            }
        }
        if (selected != null && versions.stream().noneMatch(v -> selected.equals(v.id()))) { throw new IOException("技能版本不存在或未完成发布"); }
        if (versions.isEmpty() && !unavailable) { out.println("暂无已发布技能版本。"); }
        for (var version : versions) {
            if (selected != null && !selected.equals(version.id())) { continue; }
            boolean latest = versions.stream().filter(v -> v.skillId().equals(version.skillId())).noneMatch(v -> v.approvedAt() > version.approvedAt());
            var availability = store.availability(version);
            String defaultState = latest && unavailable ? "未知（其他版本不可用，不自动回退默认）" : Boolean.toString(latest && availability.enabled());
            out.println("技能版本: " + version.id() + "\n技能: " + version.skillId() + "\n默认: " + defaultState
                    + "\n版本摘要: " + SkillStore.digest(version) + "\n可用性: " + (availability.enabled() ? "已启用" : "已停用")
                    + "\n可用性历史: " + SkillStore.JSON.writeValueAsString(availability.changes())
                    + "\n候选: " + version.candidateId() + "@" + version.candidateSha256() + "\n批准记录: "
                    + SkillStore.JSON.writeValueAsString(store.read("reviews", version.reviewId(), Publication.class)));
            var candidate = store.candidate(version.candidateId());
            out.println("父版本: " + candidate.parent() + "\n修订原因: " + candidate.revisionReason() + "\n" + candidate.guide().markdown());
            if (candidate.feedback() != null) { out.println("选定反馈快照: " + SkillStore.JSON.writeValueAsString(candidate.feedback())); }
        }
        if (selected == null && unavailable) { throw new IOException("部分版本不可用，已显示其他有效版本"); }
    }
}
