package com.agent.task;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 任务状态独立于聊天保存，避免把模型文案当作执行证据。 */
public final class TaskRecord {
    public enum State { Created, Running, Verifying, Paused, Verified, Abandoned }
    public enum DecisionState { RESERVED, RESPONDED, MODEL_FAILED }
    public enum OperationState { REQUESTED, RUNNING, SUCCEEDED, FAILED, BLOCKED }

    public int schemaVersion = 1;
    public String id;
    public String project;
    public String goal;
    public String sessionId;
    public String command;
    public String acceptanceVersion;
    public State state = State.Created;
    public String reason = "";
    public Map<String, String> files = new LinkedHashMap<>();
    public Map<String, String> acceptanceFiles = new LinkedHashMap<>();
    public List<EvidenceRef> checks = new ArrayList<>();
    public List<Agreement> agreementHistory = new ArrayList<>();
    public Progress progress = new Progress();

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
    }

    public static final class Operation {
        public String callId;
        public String tool;
        public String arguments;
        public OperationState status = OperationState.REQUESTED;
        public String observation = "";
        public Map<String, Change> changes = new LinkedHashMap<>();
    }

    public record Change(String before, String after) { }
    public record BudgetChange(int previous, int current, long at) { }

    public record EvidenceRef(String id, String sha256) { }
    public record Agreement(String version, String command, Map<String, String> files, List<EvidenceRef> checks) { }
}
