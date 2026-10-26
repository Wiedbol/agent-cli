package com.agent.skill;

import com.agent.tool.*;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;

/** 只接受当前目录授权的固定版本，不提供任意磁盘路径读取能力。 */
final class LoadSkillTool implements Tool {
    private final SkillKnowledge knowledge;
    LoadSkillTool(SkillKnowledge knowledge) { this.knowledge = knowledge; }
    @Override public String name() { return "load_skill"; }
    @Override public String description() { return "按目录中的 id/version 读取完整修复流程，可不加载"; }
    @Override public JsonNode parameters() { return ToolSchemas.object(ToolSchemas.Param.required("id", "string", "技能ID；显式试用为候选ID"), ToolSchemas.Param.required("version", "string", "目录中的固定版本；显式试用为完整SHA-256")); }
    @Override public Risk risk() { return Risk.READ; }
    @Override public List<String> pathArguments() { return List.of(); }
    @Override public ToolResult execute(ToolRequest request) { return ToolResult.ok(name(), knowledge.load(request), 0); }
}
