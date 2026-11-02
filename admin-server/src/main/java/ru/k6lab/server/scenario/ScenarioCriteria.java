package ru.k6lab.server.scenario;

import ru.k6lab.core.analysis.StepAnalysis;
import ru.k6lab.core.analysis.StopCriteriaEvaluator;
import ru.k6lab.core.profile.ProfileType;

import java.util.LinkedHashMap;
import java.util.Map;

/** Критерии остановки и успешности ступени: чтение из JSON сценария с умолчаниями по типу профиля. */
public final class ScenarioCriteria {

    private ScenarioCriteria() {
    }

    public static StopCriteriaEvaluator.Config stop(Map<String, Object> m, ProfileType type) {
        StopCriteriaEvaluator.Config d = StopCriteriaEvaluator.Config.defaults(type.stopCriteriaDefaultOn());
        if (m == null || m.isEmpty()) {
            return d;
        }
        return new StopCriteriaEvaluator.Config(
                bool(m, "percentileEnabled", d.percentileEnabled()), num(m, "slaMultiplier", d.slaMultiplier()),
                bool(m, "errorsEnabled", d.errorsEnabled()),
                bool(m, "rateEnabled", d.rateEnabled()), num(m, "minRatePct", d.minRatePct()),
                bool(m, "droppedEnabled", d.droppedEnabled()),
                (int) num(m, "consecutive", d.consecutive()), (int) num(m, "windowSec", d.windowSec()));
    }

    public static Map<String, Object> stopToMap(StopCriteriaEvaluator.Config c) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("percentileEnabled", c.percentileEnabled());
        m.put("slaMultiplier", c.slaMultiplier());
        m.put("errorsEnabled", c.errorsEnabled());
        m.put("rateEnabled", c.rateEnabled());
        m.put("minRatePct", c.minRatePct());
        m.put("droppedEnabled", c.droppedEnabled());
        m.put("consecutive", c.consecutive());
        m.put("windowSec", c.windowSec());
        return m;
    }

    public static StepAnalysis.SuccessCriteria success(Map<String, Object> m) {
        StepAnalysis.SuccessCriteria d = StepAnalysis.SuccessCriteria.defaults();
        if (m == null || m.isEmpty()) {
            return d;
        }
        return new StepAnalysis.SuccessCriteria(num(m, "minRatePct", d.minRatePct()), num(m, "maxDropped", d.maxDropped()),
                num(m, "maxCpuPct", d.maxCpuPct()));
    }

    public static Map<String, Object> successToMap(StepAnalysis.SuccessCriteria c) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("minRatePct", c.minRatePct());
        m.put("maxDropped", c.maxDropped());
        m.put("maxCpuPct", c.maxCpuPct());
        return m;
    }

    private static boolean bool(Map<String, Object> m, String k, boolean def) {
        Object o = m.get(k);
        return o instanceof Boolean b ? b : o == null ? def : Boolean.parseBoolean(String.valueOf(o));
    }

    private static double num(Map<String, Object> m, String k, double def) {
        Object o = m.get(k);
        if (o instanceof Number n) {
            return n.doubleValue();
        }
        if (o == null || String.valueOf(o).isBlank()) {
            return def;
        }
        try {
            return Double.parseDouble(String.valueOf(o).replace(',', '.'));
        } catch (NumberFormatException e) {
            return def;
        }
    }
}
