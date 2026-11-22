package ru.k6lab.core.k6config;

import com.fasterxml.jackson.databind.JsonNode;
import ru.k6lab.core.profile.StepPlan;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Определяет ступени нагрузки («поиск максимума») из стадий сценариев k6 (ramping-arrival-rate / ramping-vus).
 * Ступенью считается полка — стадия с постоянным ненулевым уровнем (target == предыдущее значение) длительностью от 10 с,
 * идущая после прогрева. Если полок меньше двух, возвращает пустой список (тест считается одноступенчатым).
 */
public final class StageStepExtractor {

    private static final int MIN_HOLD_SEC = 10;

    private record Plateau(int startSec, int endSec, double target) {
    }

    private StageStepExtractor() {
    }

    public static List<StepPlan> extract(JsonNode config, int warmupEndSec) {
        JsonNode scenarios = config == null ? null : config.path("scenarios");
        if (scenarios == null || !scenarios.isObject()) {
            return List.of();
        }
        List<Plateau> best = List.of();
        int bestSpan = 0;
        for (var it = scenarios.fields(); it.hasNext(); ) {
            JsonNode sc = it.next().getValue();
            String ex = sc.path("executor").asText("");
            if (!"ramping-arrival-rate".equals(ex) && !"ramping-vus".equals(ex)) {
                continue;
            }
            List<Plateau> found = plateaus(sc, warmupEndSec, "ramping-arrival-rate".equals(ex));
            if (found.size() >= 2) {
                int span = found.get(found.size() - 1).endSec();
                if (found.size() > best.size() || (found.size() == best.size() && span > bestSpan)) {
                    best = found;
                    bestSpan = span;
                }
            }
        }
        if (best.size() < 2) {
            return List.of();
        }
        double baseTarget = best.get(0).target();
        List<StepPlan> out = new ArrayList<>(best.size());
        int prevEnd = 0;
        for (int i = 0; i < best.size(); i++) {
            Plateau p = best.get(i);
            int ramp = Math.max(0, p.startSec() - prevEnd);
            int hold = Math.max(1, p.endSec() - p.startSec());
            double pct = Math.round((p.target() / baseTarget) * 1000.0) / 10.0;
            String lbl = (pct == Math.rint(pct) ? String.format(Locale.ROOT, "%.0f%%", pct) : String.format(Locale.ROOT, "%.1f%%", pct));
            out.add(new StepPlan(i + 1, pct, ramp, hold, lbl));
            prevEnd = p.endSec();
        }
        return out;
    }

    private static List<Plateau> plateaus(JsonNode sc, int warmupEndSec, boolean arrival) {
        JsonNode stages = sc.path("stages");
        if (!stages.isArray() || stages.isEmpty()) {
            return List.of();
        }
        double t = ConfigDuration.parse(sc.path("startTime").asText(""), 0);
        double prev = sc.path(arrival ? "startRate" : "startVUs").asDouble(0);
        int warm = Math.max(0, warmupEndSec);
        List<Plateau> out = new ArrayList<>();
        for (JsonNode st : stages) {
            double dur = st.path("duration").isNumber() ? st.path("duration").asDouble() / 1000.0
                    : ConfigDuration.parse(st.path("duration").asText(""), 0);
            double target = st.path("target").asDouble(0);
            double end = t + Math.max(0, dur);
            if (dur > 0 && target > 0 && Math.abs(target - prev) < 1e-9) {
                int s0 = (int) Math.round(Math.max(t, warm));
                int s1 = (int) Math.round(end);
                if (s1 - s0 >= MIN_HOLD_SEC) {
                    out.add(new Plateau(s0, s1, target));
                }
            }
            t = end;
            prev = target;
        }
        return out;
    }
}
