package ru.k6lab.core.analysis;

import java.util.List;
import java.util.Map;

/** Типы анализа результатов по ступеням. */
public final class StepAnalysis {

    private StepAnalysis() {
    }

    /** SLA операции: перцентиль, порог времени (мс), допустимый процент ошибок. */
    public record OpSla(String operation, int percentile, double thresholdMs, double maxErrorPct) {
    }

    /** Измеренные значения операции на участке удержания (перцентили - только из native histograms). */
    public record OpMetrics(String operation, double targetRatePerSec, Double actualRatePerSec,
                            Double p50, Double p90, Double p95, Double p99, Double errorPct, Double dropped) {

        public Double percentile(int p) {
            return switch (p) {
                case 50 -> p50;
                case 90 -> p90;
                case 95 -> p95;
                case 99 -> p99;
                default -> throw new IllegalArgumentException("Неподдерживаемый перцентиль " + p);
            };
        }

        /** Отклонение фактической интенсивности от целевой, %. */
        public Double deviationPct() {
            if (actualRatePerSec == null || targetRatePerSec <= 0) {
                return null;
            }
            return (actualRatePerSec - targetRatePerSec) / targetRatePerSec * 100.0;
        }
    }

    /** Пороги успешности ступени (по умолчанию - из ТЗ; настраиваются в сценарии). */
    public record SuccessCriteria(double minRatePct, double maxDropped, double maxCpuPct) {
        public static SuccessCriteria defaults() {
            return new SuccessCriteria(95.0, 0.0, 80.0);
        }
    }

    public record OpVerdict(String operation, boolean slaOk, boolean errorsOk, boolean rateOk, boolean droppedOk,
                            boolean dataMissing, Double deviationPct) {
        public boolean ok() {
            return slaOk && errorsOk && rateOk && droppedOk && !dataMissing;
        }
    }

    /** Кастомное правило проверки метрики из Prometheus наравне с SLA (например, CPU сервера <= 80%, RAM <= 85%, пул соединений БД <= 90). */
    public record CustomCheck(String name, String promql, String cmp, double threshold, String unit,
                              boolean stepSla, boolean autoStop) {

        /** Нормализованный оператор сравнения: LTE (<=), LT (<), GTE (>=), GT (>). По умолчанию LTE. */
        public String normalizedCmp() {
            if (cmp == null) {
                return "LTE";
            }
            return switch (cmp.trim().toUpperCase(java.util.Locale.ROOT)) {
                case "LT", "<" -> "LT";
                case "GTE", ">=", "≥" -> "GTE";
                case "GT", ">" -> "GT";
                default -> "LTE";
            };
        }

        public String cmpSymbol() {
            return switch (normalizedCmp()) {
                case "LT" -> "<";
                case "GTE" -> "≥";
                case "GT" -> ">";
                default -> "≤";
            };
        }

        public String violationSymbol() {
            return switch (normalizedCmp()) {
                case "LT" -> "≥";
                case "GTE" -> "<";
                case "GT" -> "≤";
                default -> ">";
            };
        }

        /** Проверяет, укладывается ли измеренное значение в допустимый порог правила. */
        public boolean passes(double value) {
            return switch (normalizedCmp()) {
                case "LT" -> value < threshold;
                case "GTE" -> value >= threshold;
                case "GT" -> value > threshold;
                default -> value <= threshold;
            };
        }

        public String formatThreshold() {
            String num = threshold == Math.rint(threshold)
                    ? String.format(java.util.Locale.ROOT, "%.0f", threshold)
                    : String.format(java.util.Locale.ROOT, "%.2f", threshold);
            String u = unit == null || unit.isBlank() ? "" : " " + unit.trim();
            return cmpSymbol() + " " + num + u;
        }

        public String formatViolation(double actual, String seriesLabel) {
            String actStr = actual == Math.rint(actual)
                    ? String.format(java.util.Locale.ROOT, "%.0f", actual)
                    : String.format(java.util.Locale.ROOT, "%.1f", actual);
            String thrStr = threshold == Math.rint(threshold)
                    ? String.format(java.util.Locale.ROOT, "%.0f", threshold)
                    : String.format(java.util.Locale.ROOT, "%.1f", threshold);
            String u = unit == null || unit.isBlank() ? "" : " " + unit.trim();
            String labelSuffix = seriesLabel == null || seriesLabel.isBlank() ? "" : " (" + seriesLabel + ")";
            return name + labelSuffix + ": " + actStr + u + " " + violationSymbol() + " " + thrStr + u;
        }
    }

    /** Результат проверки одного кастомного правила Prometheus за окно. */
    public record CustomCheckResult(CustomCheck check, Double value, String worstSeries, boolean ok, boolean dataMissing) {
    }

    public enum Status { SUCCESS, FAILED, UNRELIABLE }

    public record StepVerdict(int stepNo, Status status, List<OpVerdict> operations, List<String> reasons) {
        public boolean success() {
            return status == Status.SUCCESS;
        }
    }

    public static StepVerdict analyze(int stepNo, boolean reliable, boolean generatorsOverloaded,
                                      List<OpMetrics> metrics, Map<String, OpSla> sla, SuccessCriteria c) {
        return analyze(stepNo, reliable, generatorsOverloaded, metrics, sla, c, List.of());
    }

    public static StepVerdict analyze(int stepNo, boolean reliable, boolean generatorsOverloaded,
                                      List<OpMetrics> metrics, Map<String, OpSla> sla, SuccessCriteria c,
                                      List<CustomCheckResult> customChecks) {
        java.util.ArrayList<OpVerdict> ops = new java.util.ArrayList<>();
        java.util.ArrayList<String> reasons = new java.util.ArrayList<>();
        for (OpMetrics m : metrics) {
            OpSla s = sla.get(m.operation());
            if (s == null) {
                throw new IllegalArgumentException("Нет SLA для операции " + m.operation());
            }
            Double p = m.percentile(s.percentile());
            boolean missing = p == null || m.errorPct() == null || m.actualRatePerSec() == null;
            boolean slaOk = p != null && p <= s.thresholdMs();
            boolean errorsOk = m.errorPct() != null && m.errorPct() <= s.maxErrorPct();
            Double dev = m.deviationPct();
            boolean rateOk = m.targetRatePerSec() <= 0 || (m.actualRatePerSec() != null
                    && m.actualRatePerSec() >= m.targetRatePerSec() * c.minRatePct() / 100.0);
            boolean droppedOk = m.dropped() == null || m.dropped() <= c.maxDropped();
            OpVerdict v = new OpVerdict(m.operation(), slaOk, errorsOk, rateOk, droppedOk, missing, dev);
            ops.add(v);
            if (missing) {
                reasons.add(m.operation() + ": нет данных");
            }
            if (p != null && !slaOk) {
                reasons.add(String.format(java.util.Locale.ROOT, "%s: p%d = %.0f мс > SLA %.0f мс", m.operation(),
                        s.percentile(), p, s.thresholdMs()));
            }
            if (m.errorPct() != null && !errorsOk) {
                reasons.add(String.format(java.util.Locale.ROOT, "%s: ошибки %.2f%% > %.2f%%", m.operation(), m.errorPct(), s.maxErrorPct()));
            }
            if (!rateOk && m.actualRatePerSec() != null) {
                reasons.add(String.format(java.util.Locale.ROOT, "%s: интенсивность %.1f%% от цели (< %.0f%%)", m.operation(),
                        m.actualRatePerSec() / m.targetRatePerSec() * 100.0, c.minRatePct()));
            }
            if (!droppedOk) {
                reasons.add(m.operation() + ": dropped_iterations = " + m.dropped().longValue());
            }
        }
        boolean customFailed = false;
        if (customChecks != null) {
            for (CustomCheckResult cr : customChecks) {
                if (cr.check() != null && cr.check().stepSla() && !cr.ok() && cr.value() != null) {
                    customFailed = true;
                    reasons.add(cr.check().formatViolation(cr.value(), cr.worstSeries()));
                }
            }
        }
        if (generatorsOverloaded) {
            reasons.add("генераторы перегружены (CPU > " + (int) c.maxCpuPct() + "%) - результат не отражает возможности системы");
        }
        Status status;
        if (!reliable) {
            status = Status.UNRELIABLE;
            reasons.add(0, "ступень недостоверна: генератор отсутствовал во время ступени");
        } else if (generatorsOverloaded || customFailed || ops.stream().anyMatch(o -> !o.ok())) {
            status = Status.FAILED;
        } else {
            status = Status.SUCCESS;
        }
        return new StepVerdict(stepNo, status, ops, reasons);
    }
}
