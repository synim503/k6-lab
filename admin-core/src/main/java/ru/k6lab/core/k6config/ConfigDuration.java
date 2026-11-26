package ru.k6lab.core.k6config;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Плановая длительность теста по итоговому конфигу k6: самый поздний конец среди сценариев (startTime + длительность)
 * и самый долгий gracefulStop. Профиль нагрузки описывает только сценарии «по профилю», а у остальных исполнителей
 * (constant-vus, stages, maxDuration, startTime) своя длительность - без этого расчёта сторож считал бы такой тест зависшим.
 */
public final class ConfigDuration {

    /** По умолчанию k6: gracefulStop сценария 30 с, maxDuration у *-iterations 10 мин, gracefulRampDown у ramping-vus 30 с. */
    private static final double DEFAULT_GRACEFUL_STOP_SEC = 30;
    private static final double DEFAULT_MAX_DURATION_SEC = 600;
    private static final double DEFAULT_RAMP_DOWN_SEC = 30;

    private static final Pattern PART = Pattern.compile("([0-9]*\\.?[0-9]+)\\s*(ms|ns|us|µs|μs|s|m|h|d)");

    private ConfigDuration() {
    }

    /** @param totalSec конец самого долгого сценария от старта теста; @param gracefulStopSec максимальный gracefulStop сценариев */
    public record Span(long totalSec, long gracefulStopSec) {
    }

    public static Span of(JsonNode config) {
        double total = 0;
        double graceful = 0;
        JsonNode scenarios = config == null ? null : config.path("scenarios");
        if (scenarios == null || !scenarios.isObject()) {
            return new Span(0, 0);
        }
        for (var it = scenarios.fields(); it.hasNext(); ) {
            JsonNode sc = it.next().getValue();
            String executor = sc.path("executor").asText("");
            double duration;
            double scenarioGraceful = seconds(sc.path("gracefulStop"), DEFAULT_GRACEFUL_STOP_SEC);
            switch (executor) {
                case "ramping-vus" -> {
                    duration = stages(sc);
                    // gracefulRampDown действует во время спада стадий, а не удлиняет плановую полку; учитываем его в запасе сторожа
                    scenarioGraceful = Math.max(scenarioGraceful, seconds(sc.path("gracefulRampDown"), DEFAULT_RAMP_DOWN_SEC));
                }
                case "ramping-arrival-rate" -> duration = stages(sc);
                case "per-vu-iterations", "shared-iterations" -> duration = seconds(sc.path("maxDuration"), DEFAULT_MAX_DURATION_SEC);
                default -> duration = seconds(sc.path("duration"), 0); // constant-vus, constant-arrival-rate, externally-controlled
            }
            total = Math.max(total, seconds(sc.path("startTime"), 0) + duration);
            graceful = Math.max(graceful, scenarioGraceful);
        }
        return new Span((long) Math.ceil(total), (long) Math.ceil(graceful));
    }

    private static double stages(JsonNode sc) {
        double sum = 0;
        for (JsonNode st : sc.path("stages")) {
            sum += seconds(st.path("duration"), 0);
        }
        return sum;
    }

    /** Единый разбор длительности k6 из JsonNode: число трактуется как миллисекунды, строка — через {@link #parse}. */
    public static double seconds(JsonNode n, double def) {
        if (n == null || n.isMissingNode() || n.isNull()) {
            return def;
        }
        if (n.isNumber()) {
            return n.asDouble() / 1000.0; // число в k6 - миллисекунды
        }
        return parse(n.asText(), def);
    }

    /** Единый разбор длительности k6 из Java-объекта (Map/JSON): Number — миллисекунды, String — через {@link #parse}. */
    public static double seconds(Object v, double def) {
        if (v == null) {
            return def;
        }
        if (v instanceof JsonNode n) {
            return seconds(n, def);
        }
        if (v instanceof Number num) {
            return num.doubleValue() / 1000.0;
        }
        return parse(String.valueOf(v), def);
    }

    /** «1h30m», «90s», «500ms», «1.5m», «1d» -> секунды; нераспознанное - значение по умолчанию. */
    public static double parse(String text, double def) {
        if (text == null || text.isBlank()) {
            return def;
        }
        String trimmed = text.trim();
        Matcher m = PART.matcher(trimmed);
        double sum = 0;
        int matched = 0;
        int end = 0;
        while (m.find()) {
            if (m.start() != end && !trimmed.substring(end, m.start()).isBlank()) {
                return def;
            }
            double v = Double.parseDouble(m.group(1));
            sum += switch (m.group(2)) {
                case "ns" -> v / 1_000_000_000.0;
                case "us", "µs", "μs" -> v / 1_000_000.0;
                case "ms" -> v / 1000.0;
                case "m" -> v * 60;
                case "h" -> v * 3600;
                case "d" -> v * 86400;
                default -> v;
            };
            matched++;
            end = m.end();
        }
        return matched == 0 || !trimmed.substring(end).isBlank() ? def : sum;
    }
}
