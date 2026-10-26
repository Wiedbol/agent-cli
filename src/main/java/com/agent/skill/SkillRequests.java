package com.agent.skill;

import com.agent.llm.*;
import com.agent.session.Redactor;
import java.io.IOException;
import java.util.*;

/** 在最终客户端边界取证；脱敏副本与实际请求分别计算摘要，不修改发给模型的内容。 */
public final class SkillRequests {
    private SkillRequests() { }
    public record Payload(String sha256, String auditSha256, List<Message> messages, List<LLMClient.ToolDef> tools) { }
    static String digest(List<Message> messages, List<LLMClient.ToolDef> tools) throws IOException {
        var payload = SkillStore.JSON.createObjectNode();
        payload.set("messages", SkillStore.JSON.valueToTree(messages)); payload.set("tools", SkillStore.JSON.valueToTree(tools));
        return SkillStore.digest(payload);
    }
    static Payload capture(List<Message> messages, List<LLMClient.ToolDef> tools) throws IOException {
        var arguments = new HashMap<String, String>();
        messages.stream().filter(m -> m.toolCalls() != null).flatMap(m -> m.toolCalls().stream()).forEach(c -> arguments.put(c.id(), c.arguments()));
        var safe = messages.stream().map(m -> new Message(m.role(), text(m.content(), arguments.get(m.toolCallId())),
                m.toolCalls() == null ? null : m.toolCalls().stream().map(c -> new ToolCall(c.id(), new ToolCall.Function(c.name(), text(c.arguments(), c.arguments())))).toList(), m.toolCallId())).toList();
        return new Payload(digest(messages, tools), digest(safe, tools), safe, List.copyOf(tools));
    }
    private static String text(String text, String arguments) {
        if (text == null) { return null; }
        if (Redactor.redact(text, arguments).redacted() || Redactor.redact(text.replace("\"", "").replace("'", ""), arguments).redacted()) { return Redactor.PLACEHOLDER; }
        return text;
    }
    public static void validate(Payload payload) throws IOException {
        if (payload == null || payload.messages() == null || payload.tools() == null || payload.sha256() == null
                || !payload.sha256().matches("[a-f0-9]{64}") || !digest(payload.messages(), payload.tools()).equals(payload.auditSha256())) {
            throw new IOException("最终技能请求审计损坏");
        }
    }
}
