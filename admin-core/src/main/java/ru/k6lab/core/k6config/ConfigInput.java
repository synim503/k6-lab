package ru.k6lab.core.k6config;

import ru.k6lab.core.model.OperationSpec;
import ru.k6lab.core.profile.StepPlan;

import java.util.List;

/** Вход генератора конфига k6: операции модели + ступени профиля + параметры. */
public record ConfigInput(
        List<OperationSpec> operations,
        List<StepPlan> steps,
        int gracefulStopSec,
        int gracefulRampDownSec,
        int generatorCount,
        double preAllocFactor,
        double maxVusFactor,
        boolean usesHelper,
        int warmupSec,
        int warmupPct) {

    public static final List<String> SYSTEM_TAGS = List.of("scenario", "status", "method", "name", "group", "check",
            "error_code", "expected_response");
    public static final List<String> TREND_STATS = List.of("avg", "min", "med", "max", "p(50)", "p(90)", "p(95)", "p(99)");

    public ConfigInput {
        if (generatorCount < 1) {
            generatorCount = 1;
        }
        if (preAllocFactor <= 0) {
            preAllocFactor = 1.5;
        }
        if (maxVusFactor < 1) {
            maxVusFactor = 3;
        }
        warmupSec = Math.max(0, warmupSec);
        warmupPct = Math.max(0, Math.min(100, warmupPct));
    }

    /** Без прогрева. */
    public ConfigInput(List<OperationSpec> operations, List<StepPlan> steps, int gracefulStopSec, int gracefulRampDownSec,
                       int generatorCount, double preAllocFactor, double maxVusFactor, boolean usesHelper) {
        this(operations, steps, gracefulStopSec, gracefulRampDownSec, generatorCount, preAllocFactor, maxVusFactor, usesHelper, 0, 0);
    }

    public static ConfigInput of(List<OperationSpec> ops, List<StepPlan> steps, boolean usesHelper) {
        return new ConfigInput(ops, steps, 30, 30, 1, 1.5, 3, usesHelper);
    }
}
