package com.agent.skill;

import com.agent.llm.LLMClient;
import com.agent.llm.Message;
import java.util.*;

/** 试用事实与模型声明分开，预留请求不代表模型已经收到或使用知识。 */
public final class SkillTrial {
    public int schemaVersion = 1;
    public String id, project, projectId, control, source, catalog, catalogSha256, markerSha256;
    public String candidateId, candidateSha256, batchId, model, goal, taskId;
    public String caseId, caseKind, currentManifestSha256, sourceManifestSha256;
    public Map<String, String> configuration = new TreeMap<>();
    public int maxRequests;
    public String command = "mvn test";
    public String status = "PREPARED", reason = "";
    public boolean loaded;
    public String declaration, declarationReason;
    public Map<String, String> initial = new TreeMap<>(), acceptance = new TreeMap<>();
    public List<Event> events = new ArrayList<>();
    public List<Request> requests = new ArrayList<>();
    public record Event(String type, String callId, boolean success, String detail) { }
    public static final class Request {
        public String id, sha256, status = "RESERVED";
        public int decision;
        public boolean catalogIncluded, guideIncluded;
        public Integer promptTokens, completionTokens;
        public List<Message> messages;
        public List<LLMClient.ToolDef> tools;
    }
}
