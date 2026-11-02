package ru.k6lab.core.profile;

import java.util.ArrayList;
import java.util.List;

/** Временные границы ступени относительно старта теста (в секундах). */
public record StepWindow(int no, double levelPct, int rampStartSec, int holdStartSec, int holdEndSec, String label) {

    public static List<StepWindow> of(List<StepPlan> steps) {
        List<StepWindow> out = new ArrayList<>();
        int t = 0;
        for (StepPlan s : steps) {
            out.add(new StepWindow(s.no(), s.levelPct(), t, t + s.rampSec(), t + s.totalSec(), s.label()));
            t += s.totalSec();
        }
        return out;
    }

    public static int totalSec(List<StepPlan> steps) {
        return steps.stream().mapToInt(StepPlan::totalSec).sum();
    }
}
