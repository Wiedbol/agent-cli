package com.agent.skill;

import com.agent.tool.*;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;

/** 将模型判断另列为声明，不能把声明当作遵循步骤或独立验收事实。 */
final class DeclareSkillTool implements Tool {
    private final SkillKnowledge knowledge;
    DeclareSkillTool(SkillKnowledge knowledge) { this.knowledge = knowledge; }
    @Override public String name() { return "declare_skill"; }
    @Override public String description() { return "加载后声明 ACCEPT 或 REJECT 及原因；不改变候选启用状态"; }
    @Override public JsonNode parameters() { return ToolSchemas.object(ToolSchemas.Param.required("id", "string", "候选ID"), ToolSchemas.Param.required("version", "string", "完整内容摘要"), ToolSchemas.Param.required("decision", "string", "ACCEPT或REJECT"), ToolSchemas.Param.required("reason", "string", "采纳或拒绝原因")); }
    @Override public Risk risk() { return Risk.READ; }
    @Override public List<String> pathArguments() { return List.of(); }
    @Override public ToolResult execute(ToolRequest request) { return ToolResult.ok(name(), knowledge.declare(request), 0); }
}
