package com.agent.skill;

import com.agent.experience.Experience;
import java.util.List;

/** 来源由程序绑定，模型只能提出指南；候选本身不具备启用权限。 */
public record SkillCandidate(int schemaVersion, String id, String skillId, String project,
                             String batchId, String requestId, List<Source> sources, Parent parent,
                             String revisionReason, Guide guide, long createdAt,
                             @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
                             SkillFeedback.Inputs feedback) {
    public SkillCandidate(int schemaVersion, String id, String skillId, String project, String batchId, String requestId,
                          List<Source> sources, Parent parent, String revisionReason, Guide guide, long createdAt) {
        this(schemaVersion, id, skillId, project, batchId, requestId, sources, parent, revisionReason, guide, createdAt, null);
    }
    public record Source(Experience experience, String sha256) { }
    public record Parent(String skillId, String versionId, String sha256) { }
    public record Guide(String title, String conditions, String exclusions, String diagnosis,
                        String repair, String verification) {
        public String markdown() {
            return "# " + title + "\n\n## 适用条件\n" + conditions + "\n\n## 不适用情形\n" + exclusions
                    + "\n\n## 诊断\n" + diagnosis + "\n\n## 修复\n" + repair + "\n\n## 验证\n" + verification + "\n";
        }
    }
}
