package com.agent.task;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 任务状态独立于聊天保存，避免把模型文案当作执行证据。 */
public final class TaskRecord {
    public enum State { Created, Running, Verifying, Paused, Verified, Abandoned }
    public enum DecisionState { RESERVED, RESPONDED, MODEL_FAILED }
    public enum OperationState { REQUESTED, RUNNING, SUCCEEDED, FAILED, BLOCKED, CANCELLED }
    public enum ResolutionAction { RETRY, ABANDON, REPLAN }

    public int schemaVersion = 1;
    public String id;
    public String project;
    public String goal;
    public String sessionId;
    public String command;
    public String acceptanceVersion;
    public State state = State.Created;
    public String reason = "";
    public boolean experiencesEnabled = true;
    public KnowledgeMode knowledgeMode = KnowledgeMode.EXPERIENCE;
    public String skillTrialId;
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
    public com.agent.skill.SkillTask skills;
    public List<String> experienceFailures = new ArrayList<>();
    public List<String> experienceAdviceIds = new ArrayList<>();
    public Map<String, String> files = new LinkedHashMap<>();
    public Map<String, String> acceptanceFiles = new LinkedHashMap<>();
    public List<EvidenceRef> checks = new ArrayList<>();
    public List<Agreement> agreementHistory = new ArrayList<>();
    public Progress progress = new Progress();
    public Checkpoint checkpoint = new Checkpoint();
    public List<Resolution> resolutions = new ArrayList<>();

    /** 人工选择保留原未知操作，不能把确认解释为执行成功。 */
    public static final class Resolution {
        public String id;
        public ResolutionAction action;
        public String target;
        public String replacementId;
        public long at;
        public boolean stoppedConfirmed;
        public Operation originalOperation;
        public Map<String, String> expectedFiles = new LinkedHashMap<>();
        public Map<String, String> reviewedFiles = new LinkedHashMap<>();
        public List<ProcessRef> originalProcesses = new ArrayList<>();
        public List<String> cancelledOperations = new ArrayList<>();
    }

    public enum Phase { BASELINE, CODING, ACCEPTANCE, COMPLETE }

    /** 消息与操作结果同一快照提交，聊天日志缺行不改变恢复位置。 */
    public static final class Checkpoint {
        public Phase phase = Phase.BASELINE;
        public List<com.agent.llm.Message> messages = new ArrayList<>();
        public String feedback;
        public boolean completionClaim;
        public boolean acceptancePending;
        public String acceptanceResult;
        public boolean acceptanceProcessed;
        public String acceptanceOperationId;
        public List<ProcessRef> acceptanceProcesses = new ArrayList<>();
        public String baselineEvidenceId;
        public String replanContext;

        /** 统一清除上一轮验收关联，避免恢复误用旧证据。 */
        public void beginAcceptance(boolean claim) {
            phase = Phase.ACCEPTANCE;
            acceptanceOperationId = null;
            acceptanceProcesses.clear();
            completionClaim = claim;
            reserveAcceptance();
            acceptancePending = false;
        }

        public void reserveAcceptance() {
            if (acceptanceOperationId == null) { acceptanceOperationId = java.util.UUID.randomUUID().toString(); }
            acceptancePending = true;
            acceptanceResult = null;
            acceptanceProcessed = false;
        }

        public void restartAgreement() {
            beginAcceptance(false);
            phase = Phase.BASELINE;
            feedback = null;
            baselineEvidenceId = null;
            replanContext = null;
        }
    }

    /** 累计消耗随任务落盘，不随一次控制循环重新开始。 */
    public static final class Progress {
        public int maxRequests = 12;
        public int requests;
        public int tools;
        public long promptTokens;
        public long completionTokens;
        public int unknownRequests;
        public long elapsedMillis;
        public Map<String, Integer> errors = new LinkedHashMap<>();
        public List<String> acceptanceErrors = new ArrayList<>();
        public Map<String, String> toolErrors = new LinkedHashMap<>();
        public Map<String, String> errorDescriptions = new LinkedHashMap<>();
        public List<Decision> decisions = new ArrayList<>();
        public List<BudgetChange> budgetChanges = new ArrayList<>();
    }

    public static final class Decision {
        @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
        public com.agent.skill.SkillTask.Request skillRequest;
        public int number;
        public String acceptanceVersion;
        public String failureSource;
        public DecisionState status = DecisionState.RESERVED;
        public String failure = "";
        public long startedAt;
        public long durationMillis;
        public Integer promptTokens;
        public Integer completionTokens;
        public List<Operation> operations = new ArrayList<>();
        public String content;
        public boolean roundFinished;
    }

    public static final class Operation {
        public String operationId;
        public String callId;
        public String tool;
        public String arguments;
        public OperationState status = OperationState.REQUESTED;
        public String observation = "";
        public Map<String, Change> changes = new LinkedHashMap<>();
        public String risk;
        public int attempts;
        public List<ProcessRef> processes = new ArrayList<>();

        public String reference(int decisionNumber) {
            return operationId == null ? decisionNumber + ":" + callId : operationId;
        }

        public boolean finished() {
            return status == OperationState.SUCCEEDED || status == OperationState.FAILED || status == OperationState.CANCELLED;
        }
    }

    public record ProcessRef(long pid, long startedAt) {
        public static ProcessRef of(ProcessHandle process) {
            return new ProcessRef(process.pid(), process.info().startInstant().map(java.time.Instant::toEpochMilli).orElse(0L));
        }
        public boolean alive() {
            return ProcessHandle.of(pid).filter(ProcessHandle::isAlive).filter(process -> startedAt == 0
                    || process.info().startInstant().map(java.time.Instant::toEpochMilli).orElse(startedAt) == startedAt).isPresent();
        }
    }

    public record Change(String before, String after) { }
    public record BudgetChange(int previous, int current, long at) { }

    public record EvidenceRef(String id, String sha256) { }
    public record Agreement(String version, String command, Map<String, String> files, List<EvidenceRef> checks) { }
}
