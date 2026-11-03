package ru.k6lab.core.model;

import java.util.Map;

/**
 * Операция нагрузки для генерации конфига k6. Интенсивность хранится в операциях в секунду.
 */
public record OperationSpec(
        String name,
        String execFn,
        Mode mode,
        double ratePerSec,
        double expectedDurationSec,
        Integer pacingMs,
        Integer vusOverride,
        Integer preAllocatedOverride,
        Integer maxVusOverride,
        int slaPercentile,
        double slaThresholdMs,
        double maxErrorPct,
        FailureSource failureSource,
        Map<String, String> env,
        Map<String, String> tags,
        boolean enabled,
        Double fixedLevelPct) {

    /** Открытая модель - arrival rate; закрытая - VU + pacing. */
    public enum Mode { OPEN, CLOSED }

    /** Источник признака неуспеха, если скрипт не использует admin.js. */
    public enum FailureSource { HTTP, CHECKS }

    public OperationSpec {
        env = env == null ? Map.of() : Map.copyOf(env);
        tags = tags == null ? Map.of() : Map.copyOf(tags);
        if (failureSource == null) {
            failureSource = FailureSource.HTTP;
        }
    }

    public double levelFor(double stepLevelPct) {
        return fixedLevelPct != null ? fixedLevelPct : stepLevelPct;
    }
}
