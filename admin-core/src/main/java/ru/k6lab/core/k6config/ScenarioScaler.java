package ru.k6lab.core.k6config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Масштабирование интенсивности сценария k6 на коэффициент (150% = 1.5): ставки arrival-rate, цели стадий, число VU и итераций.
 * Запас VU (preAllocatedVUs, maxVUs) растёт вместе с нагрузкой, но при снижении не уменьшается: запас дешевле нехватки.
 * Длительности, интервалы и пороги не меняются.
 */
public final class ScenarioScaler {

    private ScenarioScaler() {
    }

    /** Новое значение: округлённое до целого, но ненулевое значение не становится нулём. */
    static long scaled(double value, double factor) {
        long v = Math.round(value * factor);
        return value > 0 ? Math.max(1, v) : v;
    }

    /** Масштабирует сценарий на месте; возвращает true, если что-то изменилось. */
    public static boolean scale(ObjectNode sc, double factor) {
        if (sc == null || factor <= 0 || Math.abs(factor - 1) < 1e-9) {
            return false;
        }
        String ex = sc.path("executor").asText("");
        boolean changed = false;
        switch (ex) {
            case "constant-arrival-rate" -> changed |= num(sc, "rate", factor);
            case "ramping-arrival-rate" -> {
                changed |= num(sc, "startRate", factor);
                changed |= stages(sc, factor);
            }
            case "constant-vus", "externally-controlled", "per-vu-iterations" -> changed |= num(sc, "vus", factor);
            case "ramping-vus" -> {
                changed |= num(sc, "startVUs", factor);
                changed |= stages(sc, factor);
            }
            case "shared-iterations" -> changed |= num(sc, "iterations", factor);
            default -> {
            }
        }
        if (ex.endsWith("arrival-rate") || ex.equals("externally-controlled")) {
            double up = Math.max(1, factor);
            for (String k : new String[]{"preAllocatedVUs", "maxVUs"}) {
                if (sc.path(k).isNumber()) {
                    sc.put(k, (long) Math.ceil(sc.get(k).asDouble() * up - 1e-9));
                    changed |= up > 1;
                }
            }
        }
        return changed;
    }

    private static boolean num(ObjectNode sc, String key, double factor) {
        if (!sc.path(key).isNumber()) {
            return false;
        }
        long before = sc.get(key).asLong();
        long after = scaled(sc.get(key).asDouble(), factor);
        sc.put(key, after);
        return before != after;
    }

    private static boolean stages(ObjectNode sc, double factor) {
        JsonNode st = sc.path("stages");
        boolean changed = false;
        if (st instanceof ArrayNode arr) {
            for (JsonNode s : arr) {
                if (s instanceof ObjectNode o && o.path("target").isNumber()) {
                    long before = o.get("target").asLong();
                    long after = scaled(o.get("target").asDouble(), factor);
                    o.put("target", after);
                    changed |= before != after;
                }
            }
        }
        return changed;
    }
}
