package com.agent.skill;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SkillMatchingTest {
    @Test void shouldLeaveNaturalLanguageJudgmentToAgent() {
        assertNull(SkillMatching.conditionFailure("业务边界；匹配证据: 临界值触发", java.util.Map.of()));
    }

    @Test void shouldStillRequireFileAndVersionPrerequisites() {
        String hash = "a".repeat(64);
        String conditions = "必须存在: src/main/java/App.java\n文件版本 " + hash;
        assertEquals("文件前提不满足", SkillMatching.conditionFailure(conditions, java.util.Map.of()));
        assertEquals("版本前提不满足", SkillMatching.conditionFailure(conditions,
                java.util.Map.of("src/main/java/App.java", "b".repeat(64))));
        assertNull(SkillMatching.conditionFailure(conditions, java.util.Map.of("src/main/java/App.java", hash)));
    }
}
