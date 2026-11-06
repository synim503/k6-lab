package ru.k6lab.server.load;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;
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

    private static final ObjectWriter OVERRIDE_WRITER = new ObjectMapper().writerWithDefaultPrettyPrinter();

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
        List<LoadOperation> ops = load.activeOperations(scriptId).stream().filter(LoadOperation::isEnabled).toList();
        // функции, которых нет в последней версии (скрипт обновили), в запуск не попадают
        List<String> stale = load.staleOperations(scriptId).stream().map(LoadOperation::getName).toList();
        ScriptVersion version = scripts.latest(scriptId).orElse(null);
        List<StepPlan> steps = null;
        GeneratedConfig gen = null;
        String json = null;
        JsonNode finalConfig = null;
        List<String> overridePaths = List.of();
        if (version == null) {
            errors.add("У скрипта нет версий");
        } else if (!scripts.hasFiles(version)) {
            errors.add("Файлы версии v" + version.getNumber() + " отсутствуют в хранилище сервера (K6LAB_STORAGE_DIR, по умолчанию "
                    + "data/storage). Обычно так бывает после переустановки админки без переноса каталогов config/ и data/ "
                    + "прежней установки. Загрузите скрипт заново новой версией или верните каталог хранилища.");
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
            // информационное «из options вырезано» остаётся на странице версии: при запуске оно только шумит
            version.getWarnings().stream().filter(w -> !w.startsWith("Из options")).forEach(w -> warnings.add("Скрипт: " + w));
        }
        if (version != null && !stale.isEmpty()) {
            warnings.add("Не участвуют в запуске: в версии v" + version.getNumber() + " нет функций операций " + String.join(", ", stale));
        }
        try {
            steps = List.of(new StepPlan(1, script.getLevelPct(), 0, script.getHoldSec(),
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
                // ручные правки конфига k6 (maxVUs и т.п.) накладываются поверх расчётного конфига
                var applied = K6ConfigOverrides.apply(gen.config(), script.getK6Overrides());
                finalConfig = applied.config();
                // единственная «ступень» запуска - весь тест: длительность по самому долгому сценарию конфига, уровень 100%.
                // Планы по оп/с (constant-arrival-rate) уже учитывают всё нужное, профиль нагрузки больше не задаётся отдельно.
                long spanSec = ru.k6lab.core.k6config.ConfigDuration.of(finalConfig).totalSec();
                if (spanSec > 0) {
                    // прогрев - фаза «разгона» ступени: оценка, SLA и окна итогов начинаются после неё. Граница - самый поздний
                    // конец прогрева среди сценариев (у сценариев без прогрева эти минуты тоже не оцениваются)
                    int warmEnd = specs.stream().mapToInt(K6ConfigGenerator::warmupEndSec).max().orElse(0);
                    int ramp = (int) Math.min(warmEnd, Math.max(0, spanSec - 1));
                    steps = List.of(new StepPlan(1, 100, ramp, (int) Math.max(1, Math.min(Integer.MAX_VALUE, spanSec - ramp)), "100%"));
                }
                overridePaths = K6ConfigOverrides.paths(script.getK6Overrides());
                if (!overridePaths.isEmpty()) {
                    json = OVERRIDE_WRITER.writeValueAsString(finalConfig);
                    warnings.add("Применены ручные правки конфига k6: " + String.join(", ", overridePaths));
                    warnings.addAll(applied.notes());
                }
                int gens = Math.max(1, generatorCount);
                finalConfig.path("scenarios").fields().forEachRemaining(e -> {
                    long maxVus = e.getValue().path("maxVUs").asLong(Long.MAX_VALUE);
                    long preVus = e.getValue().path("preAllocatedVUs").asLong(Long.MAX_VALUE);
                    long fixedVus = e.getValue().path("vus").asLong(Long.MAX_VALUE);
                    if (maxVus < gens || preVus < gens || fixedVus < gens) {
                        warnings.add("Сценарий «" + e.getKey() + "»: VU (" + (fixedVus == Long.MAX_VALUE ? "" : "vus " + fixedVus + ", ")
                                + "preAllocated " + (preVus == Long.MAX_VALUE ? "—" : preVus)
                                + ", max " + (maxVus == Long.MAX_VALUE ? "—" : maxVus) + ") меньше числа генераторов (" + gens
                                + "). k6 делит VU между генераторами целыми числами: часть генераторов не получит VU и ничего не выполнит. "
                                + "Увеличьте preAllocatedVUs и maxVUs хотя бы до числа генераторов.");
                    }
                });
                for (GeneratedConfig.Warning w : gen.warnings()) {
                    warnings.add((w.operation() == null ? "" : w.operation() + ": ") + w.message());
                }
                dataWarnings(script, version, gen, steps, generatorCount, warnings);
            } catch (IllegalArgumentException | JsonProcessingException e) {
                errors.add(e.getMessage());
            }
        }
        return new RunPlan(script, version, ops, steps, GRACEFUL_STOP_SEC, GRACEFUL_RAMP_DOWN_SEC, gen, finalConfig,
                json, errors, warnings, generatorCount, gen == null ? null : gen.config(), overridePaths);
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
