package ru.k6lab.core.util;

import java.time.Duration;
import java.time.Instant;

/**
 * Подстановка плейсхолдеров в PromQL-запросы метрик SUT: {@code {{start}}} и {@code {{end}}} - unix-секунды,
 * {@code {{range}}} - длительность окна в формате Prometheus (например, 300s).
 */
public final class PromQlTemplate {

    private PromQlTemplate() {
    }

    public static String render(String query, Instant start, Instant end) {
        long seconds = Math.max(1, Duration.between(start, end).getSeconds());
        return query
                .replace("{{start}}", Long.toString(start.getEpochSecond()))
                .replace("{{end}}", Long.toString(end.getEpochSecond()))
                .replace("{{range}}", seconds + "s");
    }

    /** Подстановка в шаблон ссылки Grafana: время в миллисекундах, идентификатор run. */
    public static String renderUrl(String template, Instant start, Instant end, Object runId) {
        return template
                .replace("{{start}}", Long.toString(start.toEpochMilli()))
                .replace("{{end}}", Long.toString(end.toEpochMilli()))
                .replace("{{run_id}}", String.valueOf(runId));
    }
}
