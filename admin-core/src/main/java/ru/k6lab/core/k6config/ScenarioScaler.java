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

    /** Новое значение: округлённое до целого, но ненулевое значение не становится ниже floor. */
    static long scaled(double value, double factor) {
        return scaled(value, factor, 1);
    }

    static long scaled(double value, double factor, int floor) {
        long v = Math.round(value * factor);
        return value > 0 ? Math.max(Math.max(1, floor), v) : v;
    }

    /** Масштабирует сценарий на месте; возвращает true, если что-то изменилось. */
    public static boolean scale(ObjectNode sc, double factor) {
        return scale(sc, factor, 1);
    }

    /** Масштабирует сценарий на месте с учётом минимума VU (= числу генераторов) и переподбором timeUnit при потере точности. */
    public static boolean scale(ObjectNode sc, double factor, int generatorCount) {
        if (sc == null || factor <= 0 || Math.abs(factor - 1) < 1e-9) {
            return false;
        }
        int minVus = Math.max(1, generatorCount);
        String ex = sc.path("executor").asText("");
        boolean changed = false;
        switch (ex) {
            case "constant-arrival-rate" -> changed |= scaleConstantArrivalRate(sc, factor);
            case "ramping-arrival-rate" -> changed |= scaleRampingArrivalRate(sc, factor);
            case "constant-vus", "externally-controlled", "per-vu-iterations" -> changed |= num(sc, "vus", factor, minVus);
            case "ramping-vus" -> {
                changed |= num(sc, "startVUs", factor, minVus);
                changed |= stages(sc, factor, minVus);
            }
            case "shared-iterations" -> {
                changed |= num(sc, "iterations", factor, 1);
                if (sc.path("iterations").isNumber() && sc.path("vus").isNumber()
                        && sc.get("vus").asLong() > sc.get("iterations").asLong()) {
                    sc.put("vus", sc.get("iterations").asLong());
                    changed = true;
                }
            }
            default -> {
            }
        }
        if (ex.endsWith("arrival-rate") || ex.equals("externally-controlled")) {
            double up = Math.max(1, factor);
            for (String k : new String[]{"preAllocatedVUs", "maxVUs"}) {
                if (sc.path(k).isNumber()) {
                    long before = sc.get(k).asLong();
                    long after = (long) Math.ceil(sc.get(k).asDouble() * up - 1e-9);
                    sc.put(k, after);
                    changed |= before != after;
                }
            }
        }
        return changed;
    }

    private static boolean scaleConstantArrivalRate(ObjectNode sc, double factor) {
        if (!sc.path("rate").isNumber()) {
            return false;
        }
        double oldRate = sc.get("rate").asDouble();
        double unit = ConfigDuration.seconds(sc.path("timeUnit"), 1);
        if (unit <= 0) {
            unit = 1;
        }
        double targetPerSec = (oldRate / unit) * factor;
        long direct = scaled(oldRate, factor, 1);
        if (targetPerSec > 0 && Math.abs((direct / unit) - targetPerSec) > 0.01 * targetPerSec) {
            TimeUnitSelector.Selection sel = TimeUnitSelector.select(new double[]{targetPerSec});
            long newRate = Math.max(1, sel.targets()[0]);
            String newUnit = sel.unitSec() + "s";
            boolean ch = sc.get("rate").asLong() != newRate || !newUnit.equals(sc.path("timeUnit").asText("1s"));
            sc.put("rate", newRate);
            sc.put("timeUnit", newUnit);
            return ch;
        }
        return num(sc, "rate", factor, 1);
    }

    private static boolean scaleRampingArrivalRate(ObjectNode sc, double factor) {
        double unit = ConfigDuration.seconds(sc.path("timeUnit"), 1);
        if (unit <= 0) {
            unit = 1;
        }
        // Проверяем, не теряется ли точность > 1% при прямом округлении ставок
        boolean needsUnitChange = false;
        java.util.List<Double> rates = new java.util.ArrayList<>();
        if (sc.path("startRate").isNumber()) {
            double v = sc.get("startRate").asDouble();
            double rps = (v / unit) * factor;
            rates.add(rps);
            if (rps > 0 && Math.abs((scaled(v, factor, 1) / unit) - rps) > 0.01 * rps) {
                needsUnitChange = true;
            }
        }
        JsonNode st = sc.path("stages");
        if (st instanceof ArrayNode arr) {
            for (JsonNode s : arr) {
                if (s instanceof ObjectNode o && o.path("target").isNumber()) {
                    double v = o.get("target").asDouble();
                    double rps = (v / unit) * factor;
                    rates.add(rps);
                    if (rps > 0 && Math.abs((scaled(v, factor, 1) / unit) - rps) > 0.01 * rps) {
                        needsUnitChange = true;
                    }
                }
            }
        }
        if (needsUnitChange && !rates.isEmpty()) {
            double[] arr = rates.stream().mapToDouble(Double::doubleValue).toArray();
            TimeUnitSelector.Selection sel = TimeUnitSelector.select(arr);
            int idx = 0;
            boolean changed = !(sel.unitSec() + "s").equals(sc.path("timeUnit").asText("1s"));
            sc.put("timeUnit", sel.unitSec() + "s");
            if (sc.path("startRate").isNumber()) {
                long before = sc.get("startRate").asLong();
                long after = sel.targets()[idx++];
                sc.put("startRate", after);
                changed |= before != after;
            }
            if (st instanceof ArrayNode sArr) {
                for (JsonNode s : sArr) {
                    if (s instanceof ObjectNode o && o.path("target").isNumber()) {
                        long before = o.get("target").asLong();
                        long after = sel.targets()[idx++];
                        o.put("target", after);
                        changed |= before != after;
                    }
                }
            }
            return changed;
        }
        boolean changed = false;
        changed |= num(sc, "startRate", factor, 1);
        changed |= stages(sc, factor, 1);
        return changed;
    }

    private static boolean num(ObjectNode sc, String key, double factor, int floor) {
        if (!sc.path(key).isNumber()) {
            return false;
        }
        long before = sc.get(key).asLong();
        long after = scaled(sc.get(key).asDouble(), factor, floor);
        sc.put(key, after);
        return before != after;
    }

    private static boolean stages(ObjectNode sc, double factor, int floor) {
        JsonNode st = sc.path("stages");
        boolean changed = false;
        if (st instanceof ArrayNode arr) {
            for (JsonNode s : arr) {
                if (s instanceof ObjectNode o && o.path("target").isNumber()) {
                    long before = o.get("target").asLong();
                    long after = scaled(o.get("target").asDouble(), factor, floor);
                    o.put("target", after);
                    changed |= before != after;
                }
            }
        }
        return changed;
    }
}
