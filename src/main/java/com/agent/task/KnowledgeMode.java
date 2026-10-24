package com.agent.task;

/** 把无历史知识与尚无可用技能区分开，避免恢复时凭布尔开关猜测用户意图。 */
public enum KnowledgeMode {
    NONE, EXPERIENCE, SKILL
}
