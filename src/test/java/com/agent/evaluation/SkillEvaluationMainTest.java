package com.agent.evaluation;

import com.agent.skill.SkillEvaluation;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.*;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;

class SkillEvaluationMainTest {
    @TempDir Path temp;
    @Test void shouldRequireExplicitRealOrLocalSimulationAndKeepReportKeyless() throws Exception {
        var output = new ByteArrayOutputStream(); var stream = new PrintStream(output);
        Path config = temp.resolve("config.json");
        var value = new SkillEvaluation.Config(temp, temp, temp, temp, temp, "v1", "v2", "offline-model",
                "https://api.deepseek.com/chat/completions", 3, 18, 1, true);
        var json = new ObjectMapper(); json.writeValue(config.toFile(), value);
        assertEquals(2, SkillEvaluationMain.run(new String[]{"--simulate", temp.toString(), config.toString()}, stream, stream));
        assertEquals(2, SkillEvaluationMain.run(new String[]{"--real", temp.toString(), config.toString()}, stream, stream));
        assertEquals(2, SkillEvaluationMain.run(new String[]{"--simulate", config.toString()}, stream, stream));
        assertEquals(2, SkillEvaluationMain.run(new String[]{"--unknown"}, stream, stream));
        assertEquals(4, SkillEvaluationMain.run(new String[]{"--report", temp.resolve("missing").toString()}, stream, stream));
        assertTrue(output.size() > 0);
    }
}
