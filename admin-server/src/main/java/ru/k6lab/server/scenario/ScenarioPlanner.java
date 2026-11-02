package ru.k6lab.server.scenario;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Service;
import ru.k6lab.core.k6config.ConfigInput;
import ru.k6lab.core.k6config.GeneratedConfig;
import ru.k6lab.core.k6config.IterationEstimator;
import ru.k6lab.core.k6config.K6ConfigGenerator;
import ru.k6lab.core.model.OperationSpec;
import ru.k6lab.core.profile.StepPlan;
import ru.k6lab.core.segment.Fraction;
import ru.k6lab.core.segment.DataSplitter;
import ru.k6lab.server.loadmodel.LoadModel;
import ru.k6lab.server.loadmodel.LoadModelService;
import ru.k6lab.server.loadmodel.LoadOperation;
import ru.k6lab.server.profile.ProfileService;
import ru.k6lab.server.profile.TestProfile;
import ru.k6lab.server.script.ScriptService;
import ru.k6lab.server.script.ScriptVersion;
import ru.k6lab.server.testdata.DataFileStats;
import ru.k6lab.server.testdata.ScenarioDataFile;
import ru.k6lab.server.testdata.ScenarioDataFileRepository;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Превращает сценарий в конфиг k6: версия скрипта + модель + профиль -> ступени -> {@link K6ConfigGenerator}.
 * Все проблемы собираются в errors/warnings, чтобы превью показывало их, а не падало.
 */
@Service
public class ScenarioPlanner {

    private final ScriptService scripts;
    private final LoadModelService models;
    private final ProfileService profiles;
    private final ScenarioDataFileRepository dataFiles;

    public ScenarioPlanner(ScriptService scripts, LoadModelService models, ProfileService profiles,
                           ScenarioDataFileRepository dataFiles) {
        this.scripts = scripts;
        this.models = models;
        this.profiles = profiles;
        this.dataFiles = dataFiles;
    }

    /** Версия, которая будет использована: явная, закреплённая либо последняя. */
    public ScriptVersion resolveVersion(Scenario s, Long forcedVersionId) {
        if (forcedVersionId != null) {
            return scripts.version(s.getScriptId(), forcedVersionId);
        }
        if (s.getScriptVersionId() != null) {
            return scripts.version(s.getScriptId(), s.getScriptVersionId());
        }
        return scripts.latest(s.getScriptId()).orElseThrow(() -> new IllegalStateException("У скрипта нет версий"));
    }

    public ScenarioPlan plan(Scenario s, int generatorCount, Long forcedVersionId) {
        List<String> errors = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        ScriptVersion version = null;
        LoadModel model = null;
        TestProfile profile = null;
        List<StepPlan> steps = null;
        GeneratedConfig gen = null;
        JsonNode config = null;
        String json = null;
        try {
            version = resolveVersion(s, forcedVersionId);
            model = models.get(s.getModelId());
            profile = profiles.get(s.getProfileId());
        } catch (RuntimeException e) {
            errors.add(e.getMessage());
            return new ScenarioPlan(s, version, model, profile, steps, gen, config, json, errors, warnings, generatorCount);
        }
        if (!model.getScriptId().equals(s.getScriptId())) {
            errors.add("Модель «" + model.getName() + "» относится к другому скрипту");
        }
        if (!version.isValidForModel()) {
            errors.add("Версия скрипта v" + version.getNumber() + " непригодна для запуска через модель: " + version.getInvalidReason());
        }
        try {
            steps = profiles.steps(profile);
        } catch (RuntimeException e) {
            errors.add("Профиль: " + e.getMessage());
        }
        // все операции модели должны быть в выбранной версии скрипта
        Set<String> available = scripts.operationNames(version);
        for (LoadOperation op : model.getOperations()) {
            if (op.isEnabled() && !available.contains(op.getExecFn())) {
                errors.add("Операция «" + op.getName() + "»: функция " + op.getExecFn() + " не найдена среди операций версии v"
                        + version.getNumber() + " (добавьте её на странице версии, если определение эвристикой неполное)");
            }
        }
        for (String w : version.getWarnings()) {
            warnings.add("Скрипт: " + w);
        }

        if (steps != null) {
            if (s.isExpertMode()) {
                warnings.add("Экспертный режим: конфиг не связан с моделью и профилем из UI - изменения модели на него не влияют. "
                        + "Ступени для анализа берутся из профиля: держите конфиг согласованным с ним.");
                config = s.getExpertConfig();
                if (config == null || !config.isObject() || !config.has("scenarios")) {
                    errors.add("Экспертный конфиг должен быть JSON-объектом со scenarios");
                    config = null;
                } else {
                    json = pretty(config);
                    if (config.has("thresholds")) {
                        warnings.add("В экспертном конфиге есть thresholds: убедитесь, что нет abortOnFail - критерии остановки серверные.");
                    }
                    if (json.contains("abortOnFail")) {
                        errors.add("В конфиге используется abortOnFail: в распределённом режиме он срабатывает на каждом генераторе отдельно");
                    }
                }
            } else {
                try {
                    List<OperationSpec> specs = model.getOperations().stream().map(LoadOperation::toSpec).toList();
                    gen = K6ConfigGenerator.generate(new ConfigInput(specs, steps, profile.getGracefulStopSec(),
                            profile.getGracefulRampDownSec(), generatorCount, 1.5, 3, version.isUsesHelper()));
                    config = gen.config();
                    json = K6ConfigGenerator.toJson(gen);
                    for (GeneratedConfig.Warning w : gen.warnings()) {
                        warnings.add((w.operation() == null ? "" : w.operation() + ": ") + w.message());
                    }
                } catch (IllegalArgumentException e) {
                    errors.add(e.getMessage());
                }
            }
        }
        if (gen != null) {
            dataWarnings(s, version, gen, steps, generatorCount, warnings);
        }
        return new ScenarioPlan(s, version, model, profile, steps, gen, config, json, errors, warnings, generatorCount);
    }

    /** Предупреждение: после нарезки на генератор приходится меньше строк, чем ожидаемых итераций. */
    private void dataWarnings(Scenario s, ScriptVersion v, GeneratedConfig gen, List<StepPlan> steps, int generators,
                              List<String> warnings) {
        List<ScenarioDataFile> files = dataFiles.findByScenarioIdOrderByPathAsc(s.getId());
        if (files.stream().noneMatch(f -> f.getMode() == ScenarioDataFile.Mode.SPLIT && f.isConsumed())) {
            return;
        }
        double total = IterationEstimator.total(gen, steps);
        double perGenerator = total / Math.max(1, generators);
        byte[] zip;
        try {
            zip = scripts.zipBytes(v);
        } catch (IOException e) {
            return;
        }
        for (ScenarioDataFile f : files) {
            if (f.getMode() != ScenarioDataFile.Mode.SPLIT || !f.isConsumed()) {
                continue;
            }
            try {
                long rows = DataFileStats.countRows(zip, f.getPath(), f.isHasHeader());
                if (rows < 0) {
                    warnings.add("Файл данных " + f.getPath() + " отсутствует в версии v" + v.getNumber());
                    continue;
                }
                long perGen = DataSplitter.range(rows, Fraction.ZERO, Fraction.of(1, Math.max(1, generators))).count();
                if (perGen < perGenerator) {
                    warnings.add(String.format(Locale.ROOT, "Данные %s: после нарезки на %d генераторов на каждый приходится ~%d строк, "
                            + "а ожидается ~%.0f итераций - данные закончатся (если они расходуются)", f.getPath(), generators,
                            perGen, perGenerator));
                }
            } catch (IOException e) {
                warnings.add("Не удалось прочитать файл данных " + f.getPath());
            }
        }
    }

    private static String pretty(JsonNode n) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(n);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            return n.toString();
        }
    }
}
