package com.agent.task;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 任务状态独立于聊天保存，避免把模型文案当作执行证据。 */
public final class TaskRecord {
    public enum State { Created, Running, Verifying, Paused, Verified, Abandoned }

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

    public record EvidenceRef(String id, String sha256) { }
    public record Agreement(String version, String command, Map<String, String> files, List<EvidenceRef> checks) { }
}
