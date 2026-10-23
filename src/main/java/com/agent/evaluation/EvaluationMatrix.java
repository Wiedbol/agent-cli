package com.agent.evaluation;

import java.util.ArrayList;
import java.util.List;

/** 固定并交错两组顺序，避免把来源或试跑混入十八次正式运行。 */
public final class EvaluationMatrix {
    private EvaluationMatrix() { }

    public record Run(int order, String scenario, int repetition, boolean experiencesEnabled) { }

    public static List<Run> formalRuns() {
        var runs = new ArrayList<Run>();
        for (int repetition = 1; repetition <= 3; repetition++) {
            for (String scenario : List.of("eval-coupon", "eval-stock", "eval-quantity")) {
                boolean firstEnabled = repetition % 2 == 1;
                runs.add(new Run(runs.size() + 1, scenario, repetition, firstEnabled));
                runs.add(new Run(runs.size() + 1, scenario, repetition, !firstEnabled));
            }
        }
        return List.copyOf(runs);
    }
}
