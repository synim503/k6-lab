package ru.k6lab.server.scenario;

import org.springframework.stereotype.Component;
import ru.k6lab.server.loadmodel.LoadModelRepository;
import ru.k6lab.server.script.ScriptService;

/** Скрипт нельзя удалять, пока на него ссылаются модели или сценарии. */
@Component
public class ScenarioScriptUsage implements ScriptService.ScriptUsage {

    private final ScenarioRepository scenarios;
    private final LoadModelRepository models;

    public ScenarioScriptUsage(ScenarioRepository scenarios, LoadModelRepository models) {
        this.scenarios = scenarios;
        this.models = models;
    }

    @Override
    public boolean isUsed(Long scriptId) {
        return scenarios.existsByScriptId(scriptId) || models.existsByScriptId(scriptId);
    }
}
