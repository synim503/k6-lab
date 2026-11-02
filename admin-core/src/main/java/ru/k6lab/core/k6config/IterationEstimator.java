package ru.k6lab.core.k6config;

import ru.k6lab.core.profile.StepPlan;

import java.util.List;

/** Оценка числа итераций за тест (интеграл интенсивности по ступеням): нужна для предупреждений о данных. */
public final class IterationEstimator {

    private IterationEstimator() {
    }

    /** Итерации операции: на разгоне интенсивность линейна от предыдущей ступени (трапеция), на удержании постоянна. */
    public static double iterations(double[] ratesPerSec, List<StepPlan> steps) {
        double total = 0;
        double prev = 0;
        for (int i = 0; i < steps.size(); i++) {
            StepPlan s = steps.get(i);
            double r = ratesPerSec[i];
            double from = i == 0 && s.rampSec() == 0 ? r : prev;
            total += (from + r) / 2.0 * s.rampSec() + r * s.holdSec();
            prev = r;
        }
        return total;
    }

    /** Итерации всех операций вместе. */
    public static double total(GeneratedConfig cfg, List<StepPlan> steps) {
        double t = 0;
        for (GeneratedConfig.OpPlan p : cfg.operations()) {
            t += iterations(p.ratesPerSec(), steps);
        }
        return t;
    }
}
