package ru.k6lab.server.load;

import ru.k6lab.core.analysis.StepAnalysis;
import ru.k6lab.core.analysis.StopCriteriaEvaluator;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Критерии остановки и успешности ступени: чтение из JSON скрипта с умолчаниями. */
public final class LoadCriteria {

    private LoadCriteria() {
    }

    public static StopCriteriaEvaluator.Config stop(Map<String, Object> m) {
        StopCriteriaEvaluator.Config d = StopCriteriaEvaluator.Config.defaults(false);
        if (m == null || m.isEmpty()) {
            return d;
        }
        return new StopCriteriaEvaluator.Config(
                bool(m, "percentileEnabled", d.percentileEnabled()), num(m, "slaMultiplier", d.slaMultiplier()),
                bool(m, "errorsEnabled", d.errorsEnabled()),
                bool(m, "rateEnabled", d.rateEnabled()), num(m, "minRatePct", d.minRatePct()),
                bool(m, "droppedEnabled", d.droppedEnabled()), num(m, "maxDroppedPct", d.maxDroppedPct()),
                (int) num(m, "consecutive", d.consecutive()), (int) num(m, "windowSec", d.windowSec()),
                bool(m, "extraStepOnMaxSearch", d.extraStepOnMaxSearch()));
    }

    public static Map<String, Object> stopToMap(StopCriteriaEvaluator.Config c) {
        return stopToMap(c, List.of());
    }

    public static Map<String, Object> stopToMap(StopCriteriaEvaluator.Config c, List<StepAnalysis.CustomCheck> customChecks) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("percentileEnabled", c.percentileEnabled());
        m.put("slaMultiplier", c.slaMultiplier());
        m.put("errorsEnabled", c.errorsEnabled());
        m.put("rateEnabled", c.rateEnabled());
        m.put("minRatePct", c.minRatePct());
        m.put("droppedEnabled", c.droppedEnabled());
        m.put("maxDroppedPct", c.maxDroppedPct());
        m.put("consecutive", c.consecutive());
        m.put("windowSec", c.windowSec());
        m.put("extraStepOnMaxSearch", c.extraStepOnMaxSearch());
        if (customChecks != null && !customChecks.isEmpty()) {
            java.util.List<Map<String, Object>> list = new java.util.ArrayList<>(customChecks.size());
            for (StepAnalysis.CustomCheck cc : customChecks) {
                Map<String, Object> cm = new LinkedHashMap<>();
                cm.put("name", cc.name());
                cm.put("promql", cc.promql());
                cm.put("cmp", cc.normalizedCmp());
                cm.put("threshold", cc.threshold());
                cm.put("unit", cc.unit() == null ? "" : cc.unit());
                cm.put("stepSla", cc.stepSla());
                cm.put("autoStop", cc.autoStop());
                list.add(cm);
            }
            m.put("customChecks", list);
        }
        return m;
    }

    /** Извлекает кастомные правила проверки метрик Prometheus из карты stopCriteria. */
    public static java.util.List<StepAnalysis.CustomCheck> customChecks(Map<String, Object> m) {
        if (m == null || !(m.get("customChecks") instanceof java.util.List<?> rawList)) {
            return java.util.List.of();
        }
        java.util.List<StepAnalysis.CustomCheck> out = new java.util.ArrayList<>();
        for (Object item : rawList) {
            if (item instanceof Map<?, ?> rawMap) {
                @SuppressWarnings("unchecked")
                Map<String, Object> cm = (Map<String, Object>) rawMap;
                String name = str(cm, "name");
                String promql = str(cm, "promql");
                if (name.isEmpty() || promql.isEmpty()) {
                    continue;
                }
                String cmp = str(cm, "cmp");
                double threshold = num(cm, "threshold", 80.0);
                String unit = str(cm, "unit");
                boolean stepSla = bool(cm, "stepSla", true);
                boolean autoStop = bool(cm, "autoStop", false);
                out.add(new StepAnalysis.CustomCheck(name, promql, cmp.isEmpty() ? "LTE" : cmp, threshold, unit, stepSla, autoStop));
            }
        }
        return out;
    }

    private static String str(Map<String, Object> m, String k) {
        Object o = m.get(k);
        return o == null ? "" : String.valueOf(o).strip();
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
