package ru.k6lab.core.profile;

import com.fasterxml.jackson.databind.JsonNode;
import ru.k6lab.core.util.ExprEval;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Раскрывает профиль теста в список ступеней. Параметры профиля задаются Map: числа, а для CUSTOM -
 * список ступеней {@code steps: [{levelPct, rampSec, holdSec}]}.
 */
public final class ProfileExpander {

    /** Потолок уровня для «Поиска максимума»; у «Стресса» верхней границы нет (кроме защиты от опечаток). */
    public static final double MAX_SEARCH_CAP_PCT = 1000;
    public static final double STRESS_CAP_PCT = 100_000;
    public static final int MAX_STEPS = 500;

    private ProfileExpander() {
    }

    public static List<StepPlan> expand(ProfileType type, Map<String, Object> p, JsonNode templateDefinition) {
        List<StepPlan> steps = switch (type) {
            case MAX_SEARCH -> climb(p, MAX_SEARCH_CAP_PCT, "Поиск максимума");
            case STRESS -> climb(p, STRESS_CAP_PCT, "Стресс");
            case CONFIRMATION -> List.of(new StepPlan(1, level(p, "levelPct"), sec(p, "rampSec", 0), sec(p, "holdSec", -1), "Подтверждение"));
            case RELIABILITY -> List.of(new StepPlan(1, level(p, "levelPct"), sec(p, "rampSec", 0), sec(p, "durationSec", -1), "Надёжность"));
            case SPIKE -> spike(p);
            case CUSTOM -> custom(p);
            case TEMPLATE -> template(p, templateDefinition);
        };
        if (steps.isEmpty()) {
            throw new IllegalArgumentException("Профиль не содержит ступеней");
        }
        if (steps.size() > MAX_STEPS) {
            throw new IllegalArgumentException("Слишком много ступеней (" + steps.size() + " > " + MAX_STEPS + ")");
        }
        return steps;
    }

    private static List<StepPlan> climb(Map<String, Object> p, double cap, String label) {
        double start = level(p, "startPct");
        double step = num(p, "stepPct", Double.NaN);
        if (Double.isNaN(step) || step <= 0) {
            throw new IllegalArgumentException("Шаг должен быть больше нуля");
        }
        int count = (int) num(p, "steps", Double.NaN);
        if (count < 1) {
            throw new IllegalArgumentException("Число ступеней должно быть не меньше 1");
        }
        int ramp = sec(p, "rampSec", 0);
        int hold = sec(p, "holdSec", -1);
        List<StepPlan> out = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            double lvl = start + i * step;
            if (lvl > cap) {
                throw new IllegalArgumentException("Уровень ступени " + (i + 1) + " (" + lvl + "%) превышает допустимый максимум " + cap + "%");
            }
            out.add(new StepPlan(i + 1, lvl, ramp, hold, label));
        }
        return out;
    }

    private static List<StepPlan> spike(Map<String, Object> p) {
        double base = level(p, "basePct");
        double peak = level(p, "peakPct");
        if (peak <= base) {
            throw new IllegalArgumentException("Уровень пика должен быть выше базового");
        }
        int peakDur = sec(p, "peakDurationSec", -1);
        int peaks = (int) num(p, "peaks", Double.NaN);
        if (peaks < 1) {
            throw new IllegalArgumentException("Число пиков должно быть не меньше 1");
        }
        int interval = sec(p, "intervalSec", -1);
        int ramp = sec(p, "spikeRampSec", 5);
        int baseRamp = sec(p, "rampSec", 0);
        List<StepPlan> out = new ArrayList<>();
        int no = 1;
        out.add(new StepPlan(no++, base, baseRamp, interval, "Базовый уровень"));
        for (int i = 1; i <= peaks; i++) {
            out.add(new StepPlan(no++, peak, ramp, peakDur, "Пик " + i));
            out.add(new StepPlan(no++, base, ramp, interval, "База после пика " + i));
        }
        return out;
    }

    private static List<StepPlan> custom(Map<String, Object> p) {
        Object raw = p.get("steps");
        if (!(raw instanceof List<?> list) || list.isEmpty()) {
            throw new IllegalArgumentException("Добавьте хотя бы одну ступень");
        }
        List<StepPlan> out = new ArrayList<>();
        int no = 1;
        for (Object o : list) {
            if (!(o instanceof Map<?, ?> m)) {
                throw new IllegalArgumentException("Некорректная ступень");
            }
            @SuppressWarnings("unchecked") Map<String, Object> sm = (Map<String, Object>) m;
            String label = sm.get("label") == null ? "Ступень " + no : String.valueOf(sm.get("label"));
            out.add(new StepPlan(no, level(sm, "levelPct"), sec(sm, "rampSec", 0), sec(sm, "holdSec", -1), label));
            no++;
        }
        return out;
    }

    /**
     * Шаблон: {@code {"params":[{"name","label","default"}], "blocks":[{"repeat":{"count":"n","var":"i"},
     * "steps":[{"level":"start+i*inc","ramp":"ramp","hold":"hold","label":"..."}]}]}}.
     * Выражения вычисляются безопасным вычислителем {@link ExprEval}.
     */
    private static List<StepPlan> template(Map<String, Object> p, JsonNode def) {
        if (def == null || !def.has("blocks")) {
            throw new IllegalArgumentException("У шаблона профиля нет блоков ступеней");
        }
        Map<String, Double> vars = new LinkedHashMap<>();
        for (JsonNode param : def.path("params")) {
            String name = param.path("name").asText();
            Object given = p.get(name);
            double v = given instanceof Number n ? n.doubleValue()
                    : given != null && !String.valueOf(given).isBlank() ? Double.parseDouble(String.valueOf(given).replace(',', '.'))
                    : param.has("default") ? param.get("default").asDouble() : Double.NaN;
            if (Double.isNaN(v)) {
                throw new IllegalArgumentException("Не задан параметр «" + name + "»");
            }
            vars.put(name, v);
        }
        List<StepPlan> out = new ArrayList<>();
        for (JsonNode block : def.get("blocks")) {
            int count = 1;
            String var = null;
            if (block.has("repeat")) {
                count = (int) Math.round(ExprEval.eval(block.get("repeat").path("count").asText("1"), vars));
                var = block.get("repeat").path("var").asText("i");
                if (count < 0 || count > MAX_STEPS) {
                    throw new IllegalArgumentException("Недопустимое число повторов: " + count);
                }
            }
            for (int i = 0; i < count; i++) {
                Map<String, Double> local = new LinkedHashMap<>(vars);
                if (var != null) {
                    local.put(var, (double) i);
                }
                for (JsonNode s : block.path("steps")) {
                    double lvl = ExprEval.eval(s.path("level").asText(), local);
                    int ramp = (int) Math.round(ExprEval.eval(s.path("ramp").asText("0"), local));
                    int hold = (int) Math.round(ExprEval.eval(s.path("hold").asText(), local));
                    String label = s.has("label") ? s.get("label").asText() : "Ступень " + (out.size() + 1);
                    out.add(new StepPlan(out.size() + 1, lvl, ramp, hold, label));
                    if (out.size() > MAX_STEPS) {
                        throw new IllegalArgumentException("Слишком много ступеней");
                    }
                }
            }
        }
        return out;
    }

    // ---- параметры

    private static double level(Map<String, Object> p, String key) {
        double v = num(p, key, Double.NaN);
        if (Double.isNaN(v) || v < 0) {
            throw new IllegalArgumentException("Не задан уровень «" + key + "» (процент от модели, не меньше 0)");
        }
        return v;
    }

    private static int sec(Map<String, Object> p, String key, int def) {
        double v = num(p, key, def < 0 ? Double.NaN : def);
        if (Double.isNaN(v) || v < 0) {
            throw new IllegalArgumentException("Не задана длительность «" + key + "» (секунды)");
        }
        return (int) Math.round(v);
    }

    static double num(Map<String, Object> p, String key, double def) {
        Object o = p.get(key);
        if (o == null || (o instanceof String s && s.isBlank())) {
            return def;
        }
        if (o instanceof Number n) {
            return n.doubleValue();
        }
        try {
            return Double.parseDouble(String.valueOf(o).trim().replace(',', '.'));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Параметр «" + key + "» должен быть числом");
        }
    }
}
