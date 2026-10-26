package com.agent.skill;

import com.agent.llm.LLMClient;
import com.agent.task.*;
import com.agent.tool.*;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/** 试用和已发布技能共用任务入口，目录之外的知识不能绕过加载工具。 */
public interface SkillKnowledge {
    default String trialId() { return null; }
    default SkillTask state() { return null; }
    void bind(TaskRecord task) throws IOException;
    default List<Tool> tools() { return List.of(new LoadSkillTool(this), new DeclareSkillTool(this)); }
    default boolean isProtected(Path path) { return false; }
    void check();
    String discover(TaskRecord task, TaskAcceptance.Evidence evidence);
    String load(ToolRequest request);
    String declare(ToolRequest request);
    LLMClient audit(LLMClient client);
}
