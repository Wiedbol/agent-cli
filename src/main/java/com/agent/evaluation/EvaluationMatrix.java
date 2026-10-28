package com.agent.evaluation;

import java.util.ArrayList;
import java.util.List;

/** 固定并交错两组顺序，避免把来源或试跑混入十八次正式运行。 */
public final class EvaluationMatrix {
    private EvaluationMatrix() { }

    public record Run(int order, String scenario, int repetition, boolean experiencesEnabled) { }

    public record SkillRun(int order, String scenario, int repetition, String version) { }

    public static List<SkillRun> skillRuns(int repetitions) {
        if (repetitions < 1 || repetitions > 100) { throw new IllegalArgumentException("技能对照重复次数须为1至100"); }
        var runs = new ArrayList<SkillRun>();
        var scenarios = List.of("formal-bulk", "formal-remaining", "formal-grammar");
        for (int repetition = 1; repetition <= repetitions; repetition++) {
            for (int i = 0; i < scenarios.size(); i++) {
                String first = (repetition + i) % 2 == 1 ? "v1" : "v2";
                for (String version : List.of(first, first.equals("v1") ? "v2" : "v1")) {
                    runs.add(new SkillRun(runs.size() + 1, scenarios.get(i), repetition, version));
                }
            }
        }
        return List.copyOf(runs);
    }

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
