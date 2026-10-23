package com.agent.experience;

import com.agent.experience.Experience;
import com.agent.experience.ExperienceLibrary;
import com.agent.task.TaskRecord;
import org.junit.jupiter.api.Test;
import java.io.OutputStream;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class ExperienceVersionMatchTest {
    @Test void shouldMatchConfirmedVersionConditionOnlyAtThatVersion() {
        String version = "93e4eb6c27299ad0b0d2618f01b77ad6384b210a7f486abec640544027e03fd5";
        String path = "src/main/java/demo/ShopRules.java";
        var advice = new Experience.Advice("满10000分免运费，文件版本哈希" + version,
                "ShopRules.shipping shouldShipFreeAtThreshold", "检查边界", "仅限此版本");
        var candidate = new Experience(1, "candidate", "fixture", advice, "source", "v1", null, null,
                List.of(new Experience.Repair(1, "op", "call", "replace_text",
                        Map.of(path, new TaskRecord.Change(version, "after")))), 1, true);
        var sink = new PrintStream(OutputStream.nullOutputStream());
        String failure = "满10000分免运费 ShopRules.shipping shouldShipFreeAtThreshold";
        assertFalse(ExperienceLibrary.adviceFromValidated(List.of(candidate), failure,
                Map.of(path, version), new ArrayList<>(), sink).isEmpty());
        assertTrue(ExperienceLibrary.adviceFromValidated(List.of(candidate), failure,
                Map.of(path, "a".repeat(64)), new ArrayList<>(), sink).isEmpty());
        assertTrue(ExperienceLibrary.adviceFromValidated(List.of(candidate), failure,
                Map.of(path, "83" + version.substring(2)), new ArrayList<>(), sink).isEmpty());
    }
}
