package ru.k6lab.core.k6config;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Перевод операции «по профилю» (оп/с, уровень, удержание) в обычный сценарий constant-arrival-rate, который описывается
 * конструктором. Нужен для данных, сохранённых до отказа от режима «по профилю», и для старых JSON-конфигов нагрузки.
 */
public final class ProfileConverter {

    /** Интервалы, в которых целое rate даёт интенсивность с ошибкой не более 1% (как у k6: rate - целое за timeUnit). */
    private static final int[] UNITS_SEC = {1, 2, 5, 10, 30, 60, 120, 300, 600, 3600};

    private ProfileConverter() {
    }

    /** @param scenario параметры сценария constant-arrival-rate; @param ratePerSec итоговая интенсивность в оп/с */
    public record Converted(Map<String, Object> scenario, double ratePerSec) {
    }

    public static Converted toConstantArrivalRate(double ratePerSec, double levelPct, int holdSec, double expectedDurationSec,
                                                  Map<String, Object> extras) {
        double r = Math.max(0, ratePerSec) * Math.max(0, levelPct) / 100.0;
        int unit = UNITS_SEC[UNITS_SEC.length - 1];
        long rate = Math.max(1, Math.round(r * unit));
        for (int u : UNITS_SEC) {
            double v = r * u;
            long rounded = Math.round(v);
            if (rounded >= 1 && Math.abs(v - rounded) <= 0.01 * v) {
                unit = u;
                rate = rounded;
                break;
            }
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
        return new Converted(sc, (double) rate / unit);
    }
}
