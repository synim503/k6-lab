package ru.k6lab.core.analysis;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Серверные критерии остановки (вместо abortOnFail, который в распределённом режиме срабатывает на каждом
 * генераторе отдельно). Вызывается раз в 15 с по данным Prometheus за окно, только на участках удержания.
 * Срабатывание N раз подряд (по умолчанию 3) - сигнал STOP всем агентам.
 */
public class StopCriteriaEvaluator {

    /** Настройки критериев (включаются чекбоксами в сценарии). */
    public record Config(boolean percentileEnabled, double slaMultiplier,
                         boolean errorsEnabled,
                         boolean rateEnabled, double minRatePct,
                         boolean droppedEnabled, double maxDroppedPct,
                         int consecutive, int windowSec,
                         boolean extraStepOnMaxSearch) {

        public Config {
            if (!Double.isFinite(slaMultiplier) || slaMultiplier <= 0) {
                slaMultiplier = 1.5;
            }
            if (!Double.isFinite(minRatePct) || minRatePct < 0) {
                minRatePct = 0.0;
            }
            if (!Double.isFinite(maxDroppedPct) || maxDroppedPct < 0) {
                maxDroppedPct = 0.0;
            }
            if (consecutive < 1) {
                consecutive = 1;
            }
            if (windowSec < 1) {
                windowSec = 1;
            }
        }

        public Config(boolean percentileEnabled, double slaMultiplier,
                      boolean errorsEnabled,
                      boolean rateEnabled, double minRatePct,
                      boolean droppedEnabled, double maxDroppedPct,
                      int consecutive, int windowSec) {
            this(percentileEnabled, slaMultiplier, errorsEnabled, rateEnabled, minRatePct,
                    droppedEnabled, maxDroppedPct, consecutive, windowSec, true);
        }

        public static Config defaults(boolean enabled) {
            return new Config(enabled, 1.5, enabled, enabled, 90.0, enabled, 1.0, 3, 60, true);
        }

        public boolean anyEnabled() {
            return percentileEnabled || errorsEnabled || rateEnabled || droppedEnabled;
        }
    }

    /** Живая выборка по операции за окно (null - данных нет, проверка пропускается). */
    public record Sample(String operation, Double percentileMs, double slaThresholdMs, Double errorPct,
                         double maxErrorPct, Double actualRate, double targetRate, Double dropped, Double droppedPerSec) {
        public Sample(String operation, Double percentileMs, double slaThresholdMs, Double errorPct,
                      double maxErrorPct, Double actualRate, double targetRate, Double dropped) {
            this(operation, percentileMs, slaThresholdMs, errorPct, maxErrorPct, actualRate, targetRate, dropped, null);
        }
    }

    /** Живая выборка по кастомной метрике Prometheus с включённым автостопом (null value - данных пока нет). */
    public record CustomSample(String key, boolean violated, String violationMessage) {
    }

    private final Config config;
    private final Map<String, Integer> streaks = new HashMap<>();
    /** Сколько подряд тиков по ключу не было данных: 1 пропуск скрейпа прощаем, 2+ пропуска сбрасывают «залипшую» серию. */
    private final Map<String, Integer> missingTicks = new HashMap<>();
    private static final int MAX_CONSECUTIVE_MISSING = 2;

    public StopCriteriaEvaluator(Config config) {
        this.config = config;
    }

    public Config config() {
        return config;
    }

    /** Возвращает причину остановки, если какой-то критерий нарушен N раз подряд. */
    public Optional<String> evaluate(List<Sample> samples) {
        return evaluate(samples, List.of());
    }

    /** Возвращает причину остановки с учётом как операций k6, так и кастомных правил Prometheus с флагом autoStop. */
    public Optional<String> evaluate(List<Sample> samples, List<CustomSample> customSamples) {
        return evaluate(samples, customSamples, config.windowSec());
    }

    /**
     * Возвращает причину остановки с учётом фактической длины окна {@code actualWindowSec} для расчёта доли dropped_iterations.
     * Серии нарушений, отсутствующие в текущем тике (или при {@code null}-сэмпле), сбрасываются, чтобы не «залипать»
     * и не срабатывать на одиночный всплеск спустя минуты.
     */
    public Optional<String> evaluate(List<Sample> samples, List<CustomSample> customSamples, double actualWindowSec) {
        double winSec = actualWindowSec > 0 ? actualWindowSec : config.windowSec();
        Optional<String> stop = Optional.empty();
        java.util.Set<String> seenK6 = new java.util.HashSet<>();
        java.util.Set<String> seenCustom = new java.util.HashSet<>();
        if (samples != null && !samples.isEmpty()) {
            for (Sample s : samples) {
                String pKey = s.operation() + "/p";
                if (config.percentileEnabled() && s.percentileMs() != null && Double.isFinite(s.percentileMs())) {
                    if (seenK6.add(pKey)) {
                        double limit = s.slaThresholdMs() * config.slaMultiplier();
                        Optional<String> r = track(pKey, s.percentileMs() > limit,
                                String.format(java.util.Locale.ROOT, "%s: перцентиль %.0f мс > SLA × %.2f (%.0f мс)", s.operation(),
                                        s.percentileMs(), config.slaMultiplier(), limit));
                        if (r.isPresent() && stop.isEmpty()) {
                            stop = r;
                        }
                    }
                } else {
                    markMissing(pKey);
                }
                String eKey = s.operation() + "/e";
                if (config.errorsEnabled() && s.errorPct() != null && Double.isFinite(s.errorPct())) {
                    if (seenK6.add(eKey)) {
                        Optional<String> r = track(eKey, s.errorPct() > s.maxErrorPct(),
                                String.format(java.util.Locale.ROOT, "%s: ошибки %.2f%% > %.2f%%", s.operation(), s.errorPct(), s.maxErrorPct()));
                        if (r.isPresent() && stop.isEmpty()) {
                            stop = r;
                        }
                    }
                } else {
                    markMissing(eKey);
                }
                String rKey = s.operation() + "/r";
                if (config.rateEnabled() && s.actualRate() != null && Double.isFinite(s.actualRate()) && s.targetRate() > 0) {
                    if (seenK6.add(rKey)) {
                        double pct = s.actualRate() / s.targetRate() * 100.0;
                        Optional<String> r = track(rKey, pct < config.minRatePct(),
                                String.format(java.util.Locale.ROOT, "%s: фактическая интенсивность %.1f%% от цели (< %.0f%%)", s.operation(),
                                        pct, config.minRatePct()));
                        if (r.isPresent() && stop.isEmpty()) {
                            stop = r;
                        }
                    }
                } else {
                    markMissing(rKey);
                }
                String dKey = s.operation() + "/d";
                boolean hasInstantDrop = s.droppedPerSec() != null && Double.isFinite(s.droppedPerSec());
                boolean hasWindowDrop = s.dropped() != null && Double.isFinite(s.dropped());
                if (config.droppedEnabled() && (hasInstantDrop || hasWindowDrop) && s.targetRate() > 0) {
                    if (seenK6.add(dKey)) {
                        // смотрим дроп операций в моменте (droppedPerSec / targetRate), чтобы одиночный прошлый всплеск не копился в окне
                        double dropRate = hasInstantDrop ? s.droppedPerSec() : (s.dropped() / winSec);
                        double pct = (dropRate / s.targetRate()) * 100.0;
                        String msg = hasInstantDrop
                                ? String.format(java.util.Locale.ROOT, "%s: дроп операций в моменте %.1f%% от плана (> %.1f%%, %.2f оп/с)",
                                s.operation(), pct, config.maxDroppedPct(), dropRate)
                                : String.format(java.util.Locale.ROOT, "%s: dropped_iterations %.1f%% от плана (> %.1f%%, %d шт)",
                                s.operation(), pct, config.maxDroppedPct(), s.dropped().longValue());
                        Optional<String> r = track(dKey, pct > config.maxDroppedPct(), msg);
                        if (r.isPresent() && stop.isEmpty()) {
                            stop = r;
                        }
                    }
                } else {
                    markMissing(dKey);
                }
            }
            for (String k : List.copyOf(streaks.keySet())) {
                if (!k.startsWith("custom/") && !seenK6.contains(k) && !missingTicks.containsKey(k)) {
                    markMissing(k);
                }
            }
        }
        if (customSamples != null && !customSamples.isEmpty()) {
            for (CustomSample cs : customSamples) {
                String cKey = "custom/" + cs.key();
                if (!seenCustom.add(cKey)) {
                    continue;
                }
                Optional<String> r = track(cKey, cs.violated(), cs.violationMessage());
                if (r.isPresent() && stop.isEmpty()) {
                    stop = r;
                }
            }
            for (String k : List.copyOf(streaks.keySet())) {
                if (k.startsWith("custom/") && !seenCustom.contains(k)) {
                    markMissing(k);
                }
            }
        }
        return stop;
    }

    /** Сбрасывает счётчики (например, при переходе на новую ступень или в разгоне). */
    public void reset() {
        streaks.clear();
        missingTicks.clear();
    }

    private void markMissing(String key) {
        if (!streaks.containsKey(key)) {
            missingTicks.remove(key);
            return;
        }
        int m = missingTicks.merge(key, 1, Integer::sum);
        if (m >= MAX_CONSECUTIVE_MISSING) {
            streaks.remove(key);
            missingTicks.remove(key);
        }
    }

    private Optional<String> track(String key, boolean violated, String message) {
        missingTicks.remove(key);
        if (!violated) {
            streaks.remove(key);
            return Optional.empty();
        }
        int n = streaks.merge(key, 1, Integer::sum);
        return n >= config.consecutive()
                ? Optional.of(message + " (" + n + " проверки подряд)") : Optional.empty();
    }
}
