package com.agent.evaluation;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class EvaluationMatrixTest {
    @Test void shouldFreezeSixAlternatingSkillCellsAndExplicitRepeats() {
        var runs = EvaluationMatrix.skillRuns(1);
        assertEquals(List.of("formal-bulk/v1", "formal-bulk/v2", "formal-remaining/v2", "formal-remaining/v1",
                "formal-grammar/v1", "formal-grammar/v2"), runs.stream().map(r -> r.scenario() + "/" + r.version()).toList());
        assertEquals(List.of(1, 2, 3, 4, 5, 6), runs.stream().map(EvaluationMatrix.SkillRun::order).toList());
        var repeated = EvaluationMatrix.skillRuns(2);
        assertEquals(12, repeated.size());
        assertEquals("v2", repeated.get(6).version());
        assertEquals(2, repeated.get(6).repetition());
        assertThrows(IllegalArgumentException.class, () -> EvaluationMatrix.skillRuns(0));
        assertEquals(18, EvaluationMatrix.formalRuns().size());
    }
    @Test void shouldScheduleEighteenInterleavedFormalRunsWithoutSourceOrPilots() {
        var runs = EvaluationMatrix.formalRuns();
        assertEquals(18, runs.size());
        for (String scenario : List.of("eval-coupon", "eval-stock", "eval-quantity")) {
            assertEquals(3, runs.stream().filter(run -> run.scenario().equals(scenario) && run.experiencesEnabled()).count());
            assertEquals(3, runs.stream().filter(run -> run.scenario().equals(scenario) && !run.experiencesEnabled()).count());
        }
        for (int i = 0; i < runs.size(); i += 2) {
            var first = runs.get(i);
            var second = runs.get(i + 1);
            assertEquals(i + 1, first.order());
            assertEquals(i + 2, second.order());
            assertEquals(first.scenario(), second.scenario());
            assertEquals(first.repetition(), second.repetition());
            assertNotEquals(first.experiencesEnabled(), second.experiencesEnabled());
        }
        assertEquals(runs, EvaluationMatrix.formalRuns());
    }
}
