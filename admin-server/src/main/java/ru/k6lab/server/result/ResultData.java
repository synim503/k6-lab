package ru.k6lab.server.result;

import com.fasterxml.jackson.annotation.JsonIgnore;
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
                          List<String> reasons, List<OpRow> ops, List<CustomMetricRow> customChecks) {

        public StepRow(int stepNo, String label, double levelPct, String status, Instant holdStart, Instant holdEnd,
                       Instant windowStart, Instant windowEnd, boolean reliable, Double genCpuMax, boolean genOverloaded,
                       List<String> reasons, List<OpRow> ops) {
            this(stepNo, label, levelPct, status, holdStart, holdEnd, windowStart, windowEnd, reliable, genCpuMax, genOverloaded,
                    reasons, ops, List.of());
        }

        public List<CustomMetricRow> customChecks() {
            return customChecks == null ? List.of() : customChecks;
        }

        public double totalTargetRate() {
            return ops == null ? 0 : ops.stream().mapToDouble(OpRow::targetRate).sum();
        }

        public Double totalActualRate() {
            if (ops == null || ops.isEmpty()) {
                return null;
            }
            double sum = 0;
            boolean any = false;
            for (OpRow o : ops) {
                if (o.actualRate() != null && Double.isFinite(o.actualRate())) {
                    sum += o.actualRate();
                    any = true;
                }
            }
            return any ? sum : null;
        }

        public Double maxErrorPct() {
            if (ops == null) {
                return null;
            }
            Double best = null;
            for (OpRow o : ops) {
                if (o.errorPct() != null && Double.isFinite(o.errorPct()) && (best == null || o.errorPct() > best)) {
                    best = o.errorPct();
                }
            }
            return best;
        }

        public String worstSlaText() {
            if (ops == null || ops.isEmpty()) {
                return "—";
            }
            OpRow worst = null;
            double ratio = -1;
            for (OpRow o : ops) {
                Double v = o.slaValue();
                if (v != null && o.slaMs() > 0) {
                    double r = v / o.slaMs();
                    if (r > ratio) {
                        ratio = r;
                        worst = o;
                    }
                }
            }
            if (worst == null) {
                return "—";
            }
            return String.format(java.util.Locale.ROOT, "%s: p%d %.0f / %.0f мс",
                    worst.operation(), worst.slaPercentile(), worst.slaValue(), worst.slaMs());
        }
    }

    /** Строка результата кастомной проверки Prometheus на ступени (например, CPU сервера <= 80%). */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record CustomMetricRow(String name, String promql, String cmp, double threshold, String unit,
                                  boolean stepSla, boolean autoStop, Double value, String worstSeries,
                                  boolean ok, boolean dataMissing) {

        public String thresholdText() {
            return new ru.k6lab.core.analysis.StepAnalysis.CustomCheck(name, promql, cmp, threshold, unit, stepSla, autoStop)
                    .formatThreshold();
        }
    }

    /** Итог поиска максимума по ступеням (заполняется, когда ступеней ≥ 2). */
    public record MaxSummary(int totalSteps, int executedSteps, StepRow confirmedStep, StepRow firstFailedStep,
                             boolean allPassed, double confirmedTargetRps, Double confirmedActualRps) {

        public String css() {
            return confirmedStep == null ? "bad" : (allPassed ? "ok" : "warn");
        }

        public String headline() {
            if (confirmedStep == null) {
                if (firstFailedStep == null) {
                    return "Ни одна ступень не выполнена";
                }
                return "Максимум не подтверждён: нарушения уже на ступени " + firstFailedStep.stepNo()
                        + " (" + fmtPct(firstFailedStep.levelPct()) + ")";
            }
            String rps = confirmedActualRps != null
                    ? String.format(java.util.Locale.ROOT, ", %.1f оп/с", confirmedActualRps)
                    : (confirmedTargetRps > 0 ? String.format(java.util.Locale.ROOT, ", план %.1f оп/с", confirmedTargetRps) : "");
            if (allPassed) {
                return "Потолок не достигнут: успешно пройдены все " + executedSteps + " ступ. (до "
                        + fmtPct(confirmedStep.levelPct()) + rps + ")";
            }
            return "Подтверждённый максимум: ступень " + confirmedStep.stepNo()
                    + " (" + fmtPct(confirmedStep.levelPct()) + rps + ")";
        }

        public String detail() {
            if (allPassed) {
                return "Нарушений SLA, ошибок и деградации не зафиксировано — нагрузку можно повышать дальше.";
            }
            if (firstFailedStep != null) {
                String why = firstFailedStep.reasons() == null || firstFailedStep.reasons().isEmpty()
                        ? firstFailedStep.status() : String.join("; ", firstFailedStep.reasons());
                return "Точка деградации — ступень " + firstFailedStep.stepNo()
                        + " (" + fmtPct(firstFailedStep.levelPct()) + "): " + why;
            }
            return "";
        }

        private static String fmtPct(double v) {
            return v == Math.rint(v) ? String.format(java.util.Locale.ROOT, "%.0f%%", v)
                    : String.format(java.util.Locale.ROOT, "%.1f%%", v);
        }
    }

    /** Признак многоступенчатого теста (поиск максимума). */
    @JsonIgnore
    public boolean isStepTest() {
        return steps != null && steps.size() >= 2;
    }

    /** Расчёт подтверждённого максимума и точки деградации (null для одноступенчатого теста). */
    public MaxSummary maxCapacity() {
        if (!isStepTest()) {
            return null;
        }
        List<StepRow> executed = steps.stream().filter(s -> !"NOT_RUN".equals(s.status())).toList();
        StepRow confirmed = null;
        StepRow firstBad = null;
        for (StepRow s : executed) {
            if ("SUCCESS".equals(s.status())) {
                if (firstBad == null) {
                    confirmed = s;
                }
            } else {
                if (firstBad == null) {
                    firstBad = s;
                }
            }
        }
        boolean allOk = !executed.isEmpty() && firstBad == null && executed.size() == steps.size();
        double target = confirmed == null ? 0 : confirmed.totalTargetRate();
        Double actual = confirmed == null ? null : confirmed.totalActualRate();
        return new MaxSummary(steps.size(), executed.size(), confirmed, firstBad, allOk, target, actual);
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
