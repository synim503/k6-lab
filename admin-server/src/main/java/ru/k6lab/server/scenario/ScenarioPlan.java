package ru.k6lab.server.scenario;

import com.fasterxml.jackson.databind.JsonNode;
import ru.k6lab.core.k6config.GeneratedConfig;
import ru.k6lab.core.profile.StepPlan;
import ru.k6lab.server.loadmodel.LoadModel;
import ru.k6lab.server.profile.TestProfile;
import ru.k6lab.server.script.ScriptVersion;

import java.util.List;

/**
 * Рассчитанный сценарий: версия скрипта, модель, профиль, ступени и итоговый конфиг k6. errors блокируют запуск
 * (но не сохранение); warnings - предупреждения.
 */
public record ScenarioPlan(Scenario scenario, ScriptVersion version, LoadModel model, TestProfile profile,
                           List<StepPlan> steps, GeneratedConfig generated, JsonNode config, String configJson,
                           List<String> errors, List<String> warnings, int generatorCount) {

    public boolean ok() {
        return errors.isEmpty() && config != null;
    }

    public int totalSec() {
        return steps == null ? 0 : steps.stream().mapToInt(StepPlan::totalSec).sum();
    }
}
