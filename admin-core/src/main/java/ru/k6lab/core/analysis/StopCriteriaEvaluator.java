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
                         boolean droppedEnabled,
                         int consecutive, int windowSec) {

        public static Config defaults(boolean enabled) {
            return new Config(enabled, 1.5, enabled, enabled, 90.0, enabled, 3, 60);
        }

        public boolean anyEnabled() {
            return percentileEnabled || errorsEnabled || rateEnabled || droppedEnabled;
        }
    }

    /** Живая выборка по операции за окно (null - данных нет, проверка пропускается). */
    public record Sample(String operation, Double percentileMs, double slaThresholdMs, Double errorPct,
                         double maxErrorPct, Double actualRate, double targetRate, Double dropped) {
    }

    private final Config config;
    private final Map<String, Integer> streaks = new HashMap<>();

    public StopCriteriaEvaluator(Config config) {
        this.config = config;
    }

    public Config config() {
        return config;
    }

    /** Возвращает причину остановки, если какой-то критерий нарушен N раз подряд. */
    public Optional<String> evaluate(List<Sample> samples) {
        Optional<String> stop = Optional.empty();
        for (Sample s : samples) {
            if (config.percentileEnabled() && s.percentileMs() != null) {
                double limit = s.slaThresholdMs() * config.slaMultiplier();
                Optional<String> r = track(s.operation() + "/p", s.percentileMs() > limit,
                        String.format(java.util.Locale.ROOT, "%s: перцентиль %.0f мс > SLA × %.2f (%.0f мс)", s.operation(),
                                s.percentileMs(), config.slaMultiplier(), limit));
                if (r.isPresent() && stop.isEmpty()) {
                    stop = r;
                }
            }
            if (config.errorsEnabled() && s.errorPct() != null) {
                Optional<String> r = track(s.operation() + "/e", s.errorPct() > s.maxErrorPct(),
                        String.format(java.util.Locale.ROOT, "%s: ошибки %.2f%% > %.2f%%", s.operation(), s.errorPct(), s.maxErrorPct()));
                if (r.isPresent() && stop.isEmpty()) {
                    stop = r;
                }
            }
            if (config.rateEnabled() && s.actualRate() != null && s.targetRate() > 0) {
                double pct = s.actualRate() / s.targetRate() * 100.0;
                Optional<String> r = track(s.operation() + "/r", pct < config.minRatePct(),
                        String.format(java.util.Locale.ROOT, "%s: фактическая интенсивность %.1f%% от цели (< %.0f%%)", s.operation(),
                                pct, config.minRatePct()));
                if (r.isPresent() && stop.isEmpty()) {
                    stop = r;
                }
            }
            if (config.droppedEnabled() && s.dropped() != null) {
                Optional<String> r = track(s.operation() + "/d", s.dropped() > 0,
                        s.operation() + ": dropped_iterations > 0 (" + s.dropped().longValue() + ")");
                if (r.isPresent() && stop.isEmpty()) {
                    stop = r;
                }
            }
        }
        return stop;
    }

    /** Сбрасывает счётчики (например, при переходе на новую ступень или в разгоне). */
    public void reset() {
        streaks.clear();
    }

    private Optional<String> track(String key, boolean violated, String message) {
        if (!violated) {
            streaks.remove(key);
            return Optional.empty();
        }
        int n = streaks.merge(key, 1, Integer::sum);
        return n >= config.consecutive()
                ? Optional.of(message + " (" + n + " проверки подряд)") : Optional.empty();
    }
}
