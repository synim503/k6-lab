package ru.k6lab.core.k6config;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Перевод операции «по профилю» (оп/с, уровень, удержание) в обычный сценарий constant-arrival-rate, который описывается
 * конструктором. Нужен для данных, сохранённых до отказа от режима «по профилю», и для старых JSON-конфигов нагрузки.
 */
public final class ProfileConverter {

    private ProfileConverter() {
    }

    /** @param scenario параметры сценария constant-arrival-rate; @param ratePerSec итоговая интенсивность в оп/с */
    public record Converted(Map<String, Object> scenario, double ratePerSec) {
    }

    public static Converted toConstantArrivalRate(double ratePerSec, double levelPct, int holdSec, double expectedDurationSec,
                                                  Map<String, Object> extras) {
        double r = Math.max(0, ratePerSec) * Math.max(0, levelPct) / 100.0;
        int unit;
        long rate;
        if (r <= 0) {
            unit = 1;
            rate = 0;
        } else {
            TimeUnitSelector.Selection sel = TimeUnitSelector.select(new double[]{r});
            unit = sel.unitSec();
            rate = Math.max(1, sel.targets()[0]);
        }
        long pre = Math.max(1, (long) Math.ceil(r * Math.max(0.001, expectedDurationSec) * 1.5 - 1e-9));
        Map<String, Object> sc = new LinkedHashMap<>();
        sc.put("rate", rate);
        sc.put("timeUnit", unit + "s");
        sc.put("duration", Math.max(1, holdSec) + "s");
        sc.put("preAllocatedVUs", pre);
        sc.put("maxVUs", pre * 3);
        if (extras != null) {
            sc.putAll(extras); // свои значения (VU, startTime, env, теги) важнее расчётных
        }
        if (sc.get("preAllocatedVUs") instanceof Number pr && sc.get("maxVUs") instanceof Number mx
                && mx.longValue() < pr.longValue()) {
            sc.put("maxVUs", pr.longValue());
        }
        return new Converted(sc, (double) rate / unit);
    }
}
