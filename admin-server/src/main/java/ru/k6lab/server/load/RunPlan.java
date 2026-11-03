package ru.k6lab.server.load;

import com.fasterxml.jackson.databind.JsonNode;
import ru.k6lab.core.k6config.GeneratedConfig;
import ru.k6lab.core.profile.StepPlan;
import ru.k6lab.server.script.Script;
import ru.k6lab.server.script.ScriptVersion;

import java.util.List;

/**
 * Рассчитанный запуск скрипта: версия, операции, ступени и итоговый конфиг k6. errors блокируют запуск
 * (но не сохранение нагрузки); warnings - предупреждения.
 */
public record RunPlan(Script script, ScriptVersion version, List<LoadOperation> operations, List<StepPlan> steps,
                      int gracefulStopSec, int gracefulRampDownSec, GeneratedConfig generated, JsonNode config,
                      String configJson, List<String> errors, List<String> warnings, int generatorCount) {

    public boolean ok() {
        return errors.isEmpty() && config != null;
    }

    public int totalSec() {
        return steps == null ? 0 : steps.stream().mapToInt(StepPlan::totalSec).sum();
    }
}
