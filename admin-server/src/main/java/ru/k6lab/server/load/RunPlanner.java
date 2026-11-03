package ru.k6lab.server.load;

import org.springframework.stereotype.Service;
import ru.k6lab.core.k6config.ConfigInput;
import ru.k6lab.core.k6config.GeneratedConfig;
import ru.k6lab.core.k6config.IterationEstimator;
import ru.k6lab.core.k6config.K6ConfigGenerator;
import ru.k6lab.core.model.OperationSpec;
import ru.k6lab.core.profile.StepPlan;
import ru.k6lab.core.segment.DataSplitter;
import ru.k6lab.core.segment.Fraction;
import ru.k6lab.server.script.Script;
import ru.k6lab.server.script.ScriptService;
import ru.k6lab.server.script.ScriptVersion;
import ru.k6lab.server.testdata.DataFileStats;
import ru.k6lab.server.testdata.ScriptDataFile;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Превращает скрипт с описанной нагрузкой в конфиг k6: последняя версия + операции + длительность -> ступень ->
 * {@link K6ConfigGenerator}. Все проблемы собираются в errors/warnings, чтобы превью показывало их, а не падало.
 */
@Service
public class RunPlanner {

    static final int GRACEFUL_STOP_SEC = 30;
    static final int GRACEFUL_RAMP_DOWN_SEC = 30;

    private final ScriptService scripts;
    private final LoadService load;

    public RunPlanner(ScriptService scripts, LoadService load) {
        this.scripts = scripts;
        this.load = load;
    }

    public RunPlan plan(Long scriptId, int generatorCount) {
        Script script = scripts.get(scriptId);
        List<String> errors = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        List<LoadOperation> ops = load.operations(scriptId).stream().filter(LoadOperation::isEnabled).toList();
        ScriptVersion version = scripts.latest(scriptId).orElse(null);
        List<StepPlan> steps = null;
        GeneratedConfig gen = null;
        String json = null;
        if (version == null) {
            errors.add("У скрипта нет версий");
        } else if (!version.isValidForModel()) {
            errors.add("Версия скрипта v" + version.getNumber() + " непригодна для запуска: " + version.getInvalidReason());
        }
        if (ops.isEmpty()) {
            errors.add("Не описана нагрузка: укажите интенсивность хотя бы для одной операции");
        }
        if (version != null && !ops.isEmpty()) {
            Set<String> available = scripts.operationNames(version);
            for (LoadOperation op : ops) {
                if (!available.contains(op.getExecFn())) {
                    errors.add("Операция «" + op.getName() + "»: функция " + op.getExecFn() + " не найдена в версии v" + version.getNumber()
                            + " (добавьте её на странице версии, если определение эвристикой неполное)");
                }
            }
            version.getWarnings().forEach(w -> warnings.add("Скрипт: " + w));
        }
        try {
            steps = List.of(new StepPlan(1, script.getLevelPct(), script.getRampSec(), script.getHoldSec(),
                    String.format(Locale.ROOT, "%.0f%%", script.getLevelPct())));
        } catch (IllegalArgumentException e) {
            errors.add(e.getMessage());
        }
        if (errors.isEmpty()) {
            try {
                List<OperationSpec> specs = ops.stream().map(LoadOperation::toSpec).toList();
                gen = K6ConfigGenerator.generate(new ConfigInput(specs, steps, GRACEFUL_STOP_SEC, GRACEFUL_RAMP_DOWN_SEC,
                        generatorCount, 1.5, 3, version.isUsesHelper()));
                json = K6ConfigGenerator.toJson(gen);
                for (GeneratedConfig.Warning w : gen.warnings()) {
                    warnings.add((w.operation() == null ? "" : w.operation() + ": ") + w.message());
                }
                dataWarnings(script, version, gen, steps, generatorCount, warnings);
            } catch (IllegalArgumentException e) {
                errors.add(e.getMessage());
            }
        }
        return new RunPlan(script, version, ops, steps, GRACEFUL_STOP_SEC, GRACEFUL_RAMP_DOWN_SEC, gen, gen == null ? null : gen.config(),
                json, errors, warnings, generatorCount);
    }

    /** Предупреждение: после нарезки на генератор приходится меньше строк, чем ожидаемых итераций. */
    private void dataWarnings(Script script, ScriptVersion v, GeneratedConfig gen, List<StepPlan> steps, int generators,
                              List<String> warnings) {
        List<ScriptDataFile> files = load.dataFiles(script.getId());
        if (files.stream().noneMatch(f -> f.getMode() == ScriptDataFile.Mode.SPLIT && f.isConsumed())) {
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
        for (ScriptDataFile f : files) {
            if (f.getMode() != ScriptDataFile.Mode.SPLIT || !f.isConsumed()) {
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
}
