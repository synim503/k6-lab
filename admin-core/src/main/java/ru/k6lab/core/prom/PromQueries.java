package ru.k6lab.core.prom;

/**
 * Построение PromQL-запросов к метрикам k6 (remote write; гистограммы - native либо классические _bucket{le}).
 * <p>
 * Все перцентили считаются ТОЛЬКО через {@code histogram_quantile} по native histograms за окно: сначала
 * суммирование гистограмм по всем генераторам (sum), затем квантиль - это единственный корректный способ агрегации.
 * Перцентили из summary и REST API k6 накопительные, считаются на одном генераторе и в анализе не используются.
 * Имена метрик не зашиты в код: они задаются {@link Names} (настройка, проверяется диагностикой).
 */
public final class PromQueries {

    private PromQueries() {
    }

    /** Имена метрик в Prometheus (с суффиксами единиц). По умолчанию - по документации k6 для native histograms. */
    public record Names(String opDuration, String iterationDuration, String iterations, String dropped, String opCount,
                        String opErrors, String httpReqFailed, String checks, String vus, boolean classic) {

        /** native histograms (по умолчанию в этом конструкторе). */
        public Names(String opDuration, String iterationDuration, String iterations, String dropped, String opCount,
                     String opErrors, String httpReqFailed, String checks, String vus) {
            this(opDuration, iterationDuration, iterations, dropped, opCount, opErrors, httpReqFailed, checks, vus, false);
        }

        /** Максимум VU (vus_max): к имени метрики vus добавляется суффикс _max. */
        public String vusMax() {
            return vus + "_max";
        }

        /** Время HTTP-запросов (http_req_duration): имя берётся из имени iteration_duration, чтобы совпадали суффиксы единиц и формат. */
        public String httpReqDuration() {
            return iterationDuration.contains("iteration_duration") ? iterationDuration.replace("iteration_duration", "http_req_duration")
                    : "k6_http_req_duration_seconds";
        }

        public static Names defaults() {
            return new Names("k6_op_duration_seconds", "k6_iteration_duration_seconds", "k6_iterations_total",
                    "k6_dropped_iterations_total", "k6_op_count_total", "k6_op_errors_total", "k6_http_req_failed_rate",
                    "k6_checks_rate", "k6_vus");
        }

        /** Те же имена, но гистограммы - классические (_bucket{le}), которые агент формирует из native histograms. */
        public Names withClassic(boolean classic) {
            return new Names(opDuration, iterationDuration, iterations, dropped, opCount, opErrors, httpReqFailed, checks, vus, classic);
        }
    }

    /** Источник метрик SLA: helper (op_duration/op_errors) либо iteration_duration + http_req_failed/checks. */
    public enum FailureSource { HELPER, HTTP, CHECKS }

    private static String selector(String metric, long runId, String scenarioFilter) {
        StringBuilder sb = new StringBuilder(metric).append("{test_run_id=\"").append(runId).append('"');
        if (scenarioFilter != null) {
            sb.append(",scenario=\"").append(escape(scenarioFilter)).append('"');
        }
        return sb.append('}').toString();
    }

    static String escape(String v) {
        return v.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static String range(long seconds) {
        return Math.max(1, seconds) + "s";
    }

    /**
     * Перцентиль времени отклика, мс, по каждой операции (scenario) за окно: время HTTP-запросов (http_req_duration);
     * при helper - время операции op() (op_duration, для старых запусков).
     */
    public static String percentileMs(Names n, boolean helper, int percentile, long runId, long rangeSec) {
        return quantile(helper ? n.opDuration() : n.httpReqDuration(), n.classic(), percentile, runId, rangeSec);
    }

    /** Перцентиль времени метода целиком (итерации: все запросы, паузы и работа генератора), мс, по сценариям. Показатель здоровья генератора, не SLA. */
    public static String iterationPercentileMs(Names n, int percentile, long runId, long rangeSec) {
        return quantile(n.iterationDuration(), n.classic(), percentile, runId, rangeSec);
    }

    private static String quantile(String metric, boolean classicHistogram, int percentile, long runId, long rangeSec) {
        if (classicHistogram) {
            // классические корзины: суммируем по всем генераторам с сохранением le, затем квантиль
            return "histogram_quantile(" + (percentile / 100.0) + ", sum by (scenario, le) (increase(" + selector(metric + "_bucket", runId, null)
                    + "[" + range(rangeSec) + "]))) * 1000";
        }
        return "histogram_quantile(" + (percentile / 100.0) + ", sum by (scenario) (increase(" + selector(metric, runId, null)
                + "[" + range(rangeSec) + "]))) * 1000";
    }

    /** Фактическая интенсивность, оп/с, по операциям. */
    public static String ratePerSec(Names n, long runId, long rangeSec) {
        return "sum by (scenario) (increase(" + selector(n.iterations(), runId, null) + "[" + range(rangeSec) + "])) / "
                + Math.max(1, rangeSec);
    }

    /** Процент ошибок по операциям. С helper - по счётчикам (корректная агрегация между генераторами). */
    public static String errorPct(Names n, FailureSource source, long runId, long rangeSec) {
        String r = range(rangeSec);
        return switch (source) {
            case HELPER -> "100 * sum by (scenario) (increase(" + selector(n.opErrors(), runId, null) + "[" + r
                    + "])) / sum by (scenario) (increase(" + selector(n.opCount(), runId, null) + "[" + r + "]))";
            case HTTP -> "100 * avg by (scenario) (avg_over_time(" + selector(n.httpReqFailed(), runId, null) + "[" + r + "]))";
            case CHECKS -> "100 * (1 - avg by (scenario) (avg_over_time(" + selector(n.checks(), runId, null) + "[" + r + "])))";
        };
    }

    /** dropped_iterations за окно по операциям. */
    public static String dropped(Names n, long runId, long rangeSec) {
        return "sum by (scenario) (increase(" + selector(n.dropped(), runId, null) + "[" + range(rangeSec) + "]))";
    }

    /** Суммарные VU run (метрика vus без разбивки по scenario). */
    public static String totalVus(Names n, long runId) {
        return "sum(" + selector(n.vus(), runId, null) + ")";
    }

    /** Суммарный максимум VU run (метрика vus_max без разбивки по scenario). */
    public static String totalVusMax(Names n, long runId) {
        return "sum(" + selector(n.vusMax(), runId, null) + ")";
    }

    /**
     * Время (unix, секунды) последнего принятого Prometheus сэмпла run. По нему считается задержка метрик:
     * remote write + интервал отправки k6, из-за чего данные отстают от реального хода теста.
     */
    public static String lastSampleTime(Names n, long runId) {
        return "max(timestamp(" + selector(n.iterations(), runId, null) + "))";
    }

    /** Любой ряд run - проверка, что данные пришли. */
    public static String anySeries(String metric, long runId, long rangeSec) {
        return "sum(increase(" + selector(metric, runId, null) + "[" + range(rangeSec) + "]))";
    }
}
