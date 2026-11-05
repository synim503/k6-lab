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
        Double fixedLevelPct,
        String executor,
        Map<String, Object> scenario) {

    /** Исполнитель по умолчанию: интенсивность в оп/с по ступеням профиля (разгон, удержание, уровень %). */
    public static final String PROFILE_EXECUTOR = "ramping-arrival-rate";

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
        executor = executor == null || executor.isBlank() ? PROFILE_EXECUTOR : executor;
        scenario = scenario == null ? Map.of() : java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(scenario));
    }

    /** Прежний состав полей: исполнитель по умолчанию, без своих параметров сценария. */
    public OperationSpec(String name, String execFn, Mode mode, double ratePerSec, double expectedDurationSec, Integer pacingMs,
                         Integer vusOverride, Integer preAllocatedOverride, Integer maxVusOverride, int slaPercentile,
                         double slaThresholdMs, double maxErrorPct, FailureSource failureSource, Map<String, String> env,
                         Map<String, String> tags, boolean enabled, Double fixedLevelPct) {
        this(name, execFn, mode, ratePerSec, expectedDurationSec, pacingMs, vusOverride, preAllocatedOverride, maxVusOverride,
                slaPercentile, slaThresholdMs, maxErrorPct, failureSource, env, tags, enabled, fixedLevelPct, PROFILE_EXECUTOR, Map.of());
    }

    /** Сценарий строится по профилю (ступени, уровень %, оп/с); иначе - целиком по своим параметрам. */
    public boolean profileDriven() {
        return isProfile(executor, scenario);
    }

    /**
     * ramping-arrival-rate без своих stages/startRate/timeUnit строится по профилю нагрузки; со своими - работает по ним,
     * как обычный сценарий k6.
     */
    public static boolean isProfile(String executor, Map<String, Object> scenario) {
        return PROFILE_EXECUTOR.equals(executor)
                && (scenario == null || (!scenario.containsKey("stages") && !scenario.containsKey("startRate") && !scenario.containsKey("timeUnit")));
    }

    public double levelFor(double stepLevelPct) {
        return fixedLevelPct != null ? fixedLevelPct : stepLevelPct;
    }
}
