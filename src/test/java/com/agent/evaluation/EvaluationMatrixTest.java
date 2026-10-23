package com.agent.evaluation;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class EvaluationMatrixTest {
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
