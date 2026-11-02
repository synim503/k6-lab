package ru.k6lab.server.result;

import org.springframework.stereotype.Service;
import ru.k6lab.core.profile.ProfileType;
import ru.k6lab.server.profile.ProfileService;
import ru.k6lab.server.profile.TestProfile;
import ru.k6lab.server.run.Run;
import ru.k6lab.server.run.RunService;
import ru.k6lab.server.run.RunSnapshot;
import ru.k6lab.server.scenario.Scenario;
import ru.k6lab.server.scenario.ScenarioRepository;
import ru.k6lab.server.scenario.ScenarioService;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/** «Создать подтверждение максимума»: копия сценария с профилем CONFIRMATION на уровне найденного кандидата. */
@Service
public class ConfirmationService {

    private final RunService runService;
    private final StepResultService results;
    private final ScenarioService scenarios;
    private final ScenarioRepository scenarioRepository;
    private final ProfileService profiles;

    public ConfirmationService(RunService runService, StepResultService results, ScenarioService scenarios,
                               ScenarioRepository scenarioRepository, ProfileService profiles) {
        this.runService = runService;
        this.results = results;
        this.scenarios = scenarios;
        this.scenarioRepository = scenarioRepository;
        this.profiles = profiles;
    }

    public Scenario create(long runId) {
        Run run = runService.get(runId);
        if (run.getScenarioId() == null) {
            throw new IllegalStateException("Исходный сценарий удалён");
        }
        ResultData data = results.load(runId).orElseThrow(() -> new IllegalStateException("Итоги run ещё не рассчитаны"));
        if (data.max() == null || data.max().candidateLevelPct() == null) {
            throw new IllegalStateException("В этом run нет кандидата максимума");
        }
        double level = data.max().candidateLevelPct();
        RunSnapshot snap = runService.snapshot(run);
        String levelText = String.format(Locale.ROOT, "%.1f", level).replaceAll("\\.0$", "");
        String base = run.getScenarioName() + " — подтверждение " + levelText + "% (run #" + runId + ")";
        String name = base;
        for (int i = 2; scenarioRepository.existsByNameIgnoreCase(name); i++) {
            name = base + " " + i;
        }
        Map<String, String> params = new LinkedHashMap<>();
        params.put("levelPct", levelText);
        params.put("rampSec", "120");
        params.put("holdSec", "900");
        TestProfile profile = profiles.save(new ProfileService.ProfileForm(null, name, ProfileType.CONFIRMATION, null, params,
                snap.profile().gracefulStopSec(), snap.profile().gracefulRampDownSec(), false));
        Scenario copy = scenarios.clone(run.getScenarioId(), name);
        copy.setProfileId(profile.getId());
        return scenarioRepository.save(copy);
    }
}
