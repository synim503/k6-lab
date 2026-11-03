package ru.k6lab.server.result;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.Instant;
import java.util.List;

/**
 * Итоги run: ступени x операции. Хранится одним JSON в run_result - для отчётов и
 * сравнения, когда Prometheus уже может не отдавать значения.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ResultData(List<StepRow> steps, boolean prometheusOk, String error, Instant computedAt,
                         List<GenRow> generators) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record OpRow(String operation, int slaPercentile, double slaMs, double maxErrorPct, double targetRate, Double actualRate,
                        Double p50, Double p90, Double p95, Double p99, Double errorPct, Double dropped, Double deviationPct,
                        boolean ok, boolean dataMissing) {

        public Double slaValue() {
            return switch (slaPercentile) {
                case 50 -> p50;
                case 90 -> p90;
                case 95 -> p95;
                case 99 -> p99;
                default -> null;
            };
        }
    }

    /** status: SUCCESS, FAILED, UNRELIABLE, NOT_RUN (ступень не дошла до удержания). */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record StepRow(int stepNo, String label, double levelPct, String status, Instant holdStart, Instant holdEnd,
                          Instant windowStart, Instant windowEnd, boolean reliable, Double genCpuMax, boolean genOverloaded,
                          List<String> reasons, List<OpRow> ops) {
    }

    /** Справочная сводка k6 по генератору: накопленные перцентили за весь тест, НЕ для принятия решений. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record GenRow(String generator, Integer exitCode, String status, Double cpuMax, Double iterations, Double dropped,
                         Double httpReqs, Double httpFailedRate) {
    }

    public StepRow step(int no) {
        return steps.stream().filter(s -> s.stepNo() == no).findFirst().orElse(null);
    }
}
