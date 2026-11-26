package ru.k6lab.core.k6config;

import com.fasterxml.jackson.databind.JsonNode;
import ru.k6lab.core.profile.StepPlan;

import java.util.List;

/** Оценка числа итераций за тест (интеграл интенсивности по ступеням или конфигу k6): нужна для предупреждений о данных. */
public final class IterationEstimator {

    private IterationEstimator() {
    }

    /** Итерации операции: на разгоне интенсивность линейна от предыдущей ступени (трапеция), на удержании постоянна. */
    public static double iterations(double[] ratesPerSec, List<StepPlan> steps) {
        if (ratesPerSec == null || steps == null || ratesPerSec.length == 0 || steps.isEmpty()) {
            return 0;
        }
        double total = 0;
        double prev = 0;
        int n = Math.min(ratesPerSec.length, steps.size());
        for (int i = 0; i < n; i++) {
            StepPlan s = steps.get(i);
            double r = ratesPerSec[i];
            double from = i == 0 && s.rampSec() == 0 ? r : prev;
            total += (from + r) / 2.0 * s.rampSec() + r * s.holdSec();
            prev = r;
        }
        return total;
    }

    /** Итерации всех операций вместе (по плану ступеней). */
    public static double total(GeneratedConfig cfg, List<StepPlan> steps) {
        if (cfg == null) {
            return 0;
        }
        double t = 0;
        for (GeneratedConfig.OpPlan p : cfg.operations()) {
            t += iterations(p.ratesPerSec(), steps);
        }
        return t;
    }

    /** Точная оценка итераций по итоговому конфигу k6 (интеграл по ломаной ScenarioPlan + *-iterations). */
    public static double totalFromConfig(JsonNode config, GeneratedConfig fallbackCfg, List<StepPlan> fallbackSteps) {
        JsonNode scenarios = config == null ? null : config.path("scenarios");
        if (scenarios == null || !scenarios.isObject()) {
            return total(fallbackCfg, fallbackSteps);
        }
        java.util.Map<String, GeneratedConfig.OpPlan> byName = new java.util.HashMap<>();
        if (fallbackCfg != null) {
            for (GeneratedConfig.OpPlan p : fallbackCfg.operations()) {
                byName.put(p.name(), p);
            }
        }
        double sum = 0;
        for (var it = scenarios.fields(); it.hasNext(); ) {
            var entry = it.next();
            JsonNode sc = entry.getValue();
            List<ScenarioPlan.Pt> poly = ScenarioPlan.polyline(sc);
            if (!poly.isEmpty()) {
                for (int i = 0; i + 1 < poly.size(); i++) {
                    ScenarioPlan.Pt a = poly.get(i), b = poly.get(i + 1);
                    if (b.sec() > a.sec()) {
                        sum += (a.rate() + b.rate()) / 2.0 * (b.sec() - a.sec());
                    }
                }
            } else {
                String ex = sc.path("executor").asText("");
                if ("shared-iterations".equals(ex)) {
                    sum += sc.path("iterations").asDouble(0);
                } else if ("per-vu-iterations".equals(ex)) {
                    sum += sc.path("vus").asDouble(0) * sc.path("iterations").asDouble(0);
                } else if (byName.containsKey(entry.getKey())) {
                    sum += iterations(byName.get(entry.getKey()).ratesPerSec(), fallbackSteps);
                }
            }
        }
        return sum > 0 ? sum : total(fallbackCfg, fallbackSteps);
    }
}
