package com.agent.experience;

import com.agent.task.TaskRecord;
import java.util.List;
import java.util.Map;

/** 建议与不可由模型指定的来源分开保存，便于独立核查。 */
public record Experience(int schemaVersion, String id, String project, Advice advice, String sourceTask,
                         String acceptanceVersion, TaskRecord.EvidenceRef baseline,
                         TaskRecord.EvidenceRef verification, List<Repair> repairs, long confirmedAt, boolean enabled) {
    public record Advice(String conditions, String errorFeatures, String recommendation, String limitations) { }
    public record Repair(int decision, String operationId, String callId, String tool,
                         Map<String, TaskRecord.Change> changes) { }
    public Experience disabled() {
        return new Experience(schemaVersion, id, project, advice, sourceTask, acceptanceVersion,
                baseline, verification, repairs, confirmedAt, false);
    }
}
