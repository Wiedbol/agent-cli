package com.agent.skill;

import java.util.ArrayList;
import java.util.List;

/** 请求预留即计入额度；未保存响应的条目保留未知用量，不能靠重启清零。 */
public final class SkillBatch {
    public int schemaVersion = 1;
    public String id;
    public String project;
    public int limit;
    public List<Request> requests = new ArrayList<>();

    public enum State { RESERVED, RESPONDED, FAILED, COMPLETED, NO_REVISION }
    public enum Kind { GENERATION, TRIAL }
    public static final class Request {
        public Kind kind = Kind.GENERATION;
        public String id;
        public String candidateId;
        public String candidateSha256;
        public List<SkillCandidate.Source> sources;
        public SkillCandidate.Parent parent;
        public String revisionReason;
        public long reservedAt;
        public State state = State.RESERVED;
        public Integer promptTokens;
        public Integer completionTokens;
        public String failure;
        public String trialId;
        public String taskId;
        public String requestSha256;
        public String auditSha256;
        @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
        public SkillFeedback.Inputs feedback;
    }
}
