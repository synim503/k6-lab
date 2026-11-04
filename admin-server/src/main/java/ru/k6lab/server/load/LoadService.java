package ru.k6lab.server.load;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.k6lab.server.audit.AuditService;
import ru.k6lab.server.script.Script;
import ru.k6lab.server.script.ScriptRepository;
import ru.k6lab.server.script.ScriptService;
import ru.k6lab.server.script.ScriptVersion;
import ru.k6lab.server.secret.SecretService;
import ru.k6lab.server.testdata.ScriptDataFile;
import ru.k6lab.server.testdata.ScriptDataFileRepository;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/** Нагрузка скрипта: операции с интенсивностью и SLA, длительность, переменные окружения и режимы файлов данных. */
@Service
public class LoadService {

    private static final Pattern OP_NAME = Pattern.compile("^[A-Za-z0-9_-]{1,64}$");
    private static final Pattern FN_NAME = Pattern.compile("^[A-Za-z_$][A-Za-z0-9_$]*$");

    private final ScriptService scripts;
    private final ScriptRepository scriptRepo;
    private final LoadOperationRepository operations;
    private final ScriptDataFileRepository dataFiles;
    private final SecretService secrets;
    private final AuditService audit;

    public LoadService(ScriptService scripts, ScriptRepository scriptRepo, LoadOperationRepository operations,
                       ScriptDataFileRepository dataFiles, SecretService secrets, AuditService audit) {
        this.scripts = scripts;
        this.scriptRepo = scriptRepo;
        this.operations = operations;
        this.dataFiles = dataFiles;
        this.secrets = secrets;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public List<LoadOperation> operations(Long scriptId) {
        return operations.findByScriptIdOrderByPositionAsc(scriptId);
    }

    /**
     * Операции нагрузки, функции которых есть в последней версии скрипта. После загрузки версии без старой функции
     * её операция не показывается и не запускается (строка в базе остаётся до сохранения формы: при откате она вернётся).
     */
    @Transactional(readOnly = true)
    public List<LoadOperation> activeOperations(Long scriptId) {
        Set<String> available = scripts.latest(scriptId).map(v -> (Set<String>) new HashSet<>(v.operationNames())).orElse(null);
        List<LoadOperation> all = operations(scriptId);
        return available == null ? all : all.stream().filter(o -> available.contains(o.getExecFn())).toList();
    }

    /** Операции нагрузки, функции которых пропали из последней версии скрипта. */
    @Transactional(readOnly = true)
    public List<LoadOperation> staleOperations(Long scriptId) {
        Set<String> active = new HashSet<>();
        activeOperations(scriptId).forEach(o -> active.add(o.getExecFn()));
        return operations(scriptId).stream().filter(o -> !active.contains(o.getExecFn())).toList();
    }

    @Transactional(readOnly = true)
    public List<ScriptDataFile> dataFiles(Long scriptId) {
        return dataFiles.findByScriptIdOrderByPathAsc(scriptId);
    }

    /** Нагрузка описана, если есть хотя бы одна включённая операция. */
    @Transactional(readOnly = true)
    public boolean isDefined(Long scriptId) {
        return activeOperations(scriptId).stream().anyMatch(LoadOperation::isEnabled);
    }

    /** Данные формы для редактирования; операции последней версии, которых в нагрузке ещё нет, добавляются пустыми строками. */
    @Transactional(readOnly = true)
    public LoadForm toForm(Long scriptId) {
        Script script = scripts.get(scriptId);
        LoadForm f = new LoadForm();
        f.setLevelPct(script.getLevelPct());
        f.setRampSec(script.getRampSec());
        f.setHoldMinutes(script.getHoldSec() / 60.0);
        f.setGeneratorCount(script.getGeneratorCount());
        f.setEnvText(LoadForm.formatKv(script.getEnv()));
        List<LoadOperation> saved = activeOperations(scriptId);
        for (LoadOperation o : saved) {
            LoadForm.OpForm of = new LoadForm.OpForm();
            of.setName(o.getName());
            of.setExecFn(o.getExecFn());
            of.setRatePerSec(o.isEnabled() ? o.getRatePerSec() : null);
            of.setSlaPercentile(o.getSlaPercentile());
            of.setSlaThresholdMs(o.getSlaThresholdMs());
            of.setMaxErrorPct(o.getMaxErrorPct());
            of.setExpectedDurationSec(o.getExpectedDurationSec());
            of.setFailureSource(o.getFailureSource());
            f.getOps().add(of);
        }
        Set<String> have = new HashSet<>();
        saved.forEach(o -> have.add(o.getExecFn()));
        scripts.latest(scriptId).ifPresent(v -> v.operationNames().stream().filter(n -> !have.contains(n)).forEach(n -> {
            LoadForm.OpForm of = new LoadForm.OpForm();
            of.setName(n);
            of.setExecFn(n);
            f.getOps().add(of);
        }));
        f.setDataFiles(dataRows(scriptId, dataFiles(scriptId)));
        return f;
    }

    /** Файлы данных последней версии скрипта с уже заданными режимами. */
    public List<LoadForm.DataForm> dataRows(Long scriptId, List<ScriptDataFile> existing) {
        List<LoadForm.DataForm> out = new ArrayList<>();
        Optional<ScriptVersion> v = scripts.latest(scriptId);
        if (v.isEmpty()) {
            return out;
        }
        for (ScriptVersion.FileEntry fe : v.get().getFiles()) {
            if (!fe.kind().equals("DATA")) {
                continue;
            }
            LoadForm.DataForm row = new LoadForm.DataForm();
            row.setPath(fe.path());
            ScriptDataFile ex = existing.stream().filter(d -> fe.path().equals(d.getPath())).findFirst().orElse(null);
            if (ex != null) {
                row.setMode(ex.getMode());
                row.setHasHeader(ex.isHasHeader());
                row.setConsumed(ex.isConsumed());
            } else {
                row.setHasHeader(fe.path().toLowerCase().endsWith(".csv"));
            }
            out.add(row);
        }
        return out;
    }

    /** Сохраняет ручные правки конфига k6 (пустая карта - сброс к расчётному конфигу). */
    @Transactional
    public void saveK6Overrides(Long scriptId, java.util.Map<String, Object> overrides) {
        Script script = scripts.get(scriptId);
        script.setK6Overrides(overrides == null ? new java.util.LinkedHashMap<>() : overrides);
        scriptRepo.save(script);
        audit.record(overrides == null || overrides.isEmpty() ? "script.k6config_reset" : "script.k6config_edit", "script", scriptId,
                java.util.Map.of("paths", K6ConfigOverrides.paths(overrides).size()));
    }

    public static final int MAX_GENERATORS = 200;

    @Transactional
    public void save(Long scriptId, LoadForm f) {
        Script script = scripts.get(scriptId);
        if (f.getLevelPct() <= 0) {
            throw new IllegalArgumentException("Уровень нагрузки должен быть больше 0");
        }
        if (f.getRampSec() < 0) {
            throw new IllegalArgumentException("Разгон не может быть отрицательным");
        }
        int holdSec = (int) Math.round(f.getHoldMinutes() * 60);
        if (holdSec < 1) {
            throw new IllegalArgumentException("Длительность должна быть не меньше секунды");
        }
        Map<String, String> env = LoadForm.parseKv(f.getEnvText());
        secrets.requireExist(SecretService.referencedNames(env));

        Set<String> names = new HashSet<>();
        List<LoadOperation> built = new ArrayList<>();
        for (LoadForm.OpForm o : f.getOps()) {
            if (o == null || o.blank()) {
                continue;
            }
            validateOp(o, names);
            double rate = o.getRatePerSec() == null ? 0 : o.getRatePerSec();
            if (rate <= 0) {
                continue; // операция без интенсивности в нагрузке не участвует и не хранится
            }
            LoadOperation op = new LoadOperation();
            op.setScriptId(scriptId);
            op.setPosition(built.size());
            op.setName(o.getName().trim());
            op.setExecFn(o.getExecFn().trim());
            op.setRatePerSec(rate);
            op.setExpectedDurationSec(o.getExpectedDurationSec() == null ? 0.3 : o.getExpectedDurationSec());
            op.setSlaPercentile(o.getSlaPercentile());
            op.setSlaThresholdMs(o.getSlaThresholdMs() == null ? 1000 : o.getSlaThresholdMs());
            op.setMaxErrorPct(o.getMaxErrorPct() == null ? 1 : o.getMaxErrorPct());
            op.setFailureSource(o.getFailureSource());
            built.add(op);
        }
        if (built.isEmpty()) {
            throw new IllegalArgumentException("Укажите интенсивность (оп/с) хотя бы для одной операции");
        }

        operations.deleteByScriptId(scriptId);
        operations.flush();
        operations.saveAll(built);

        script.setLevelPct(f.getLevelPct());
        script.setRampSec(f.getRampSec());
        script.setHoldSec(holdSec);
        if (f.getGeneratorCount() < 1 || f.getGeneratorCount() > MAX_GENERATORS) {
            throw new IllegalArgumentException("Число генераторов: от 1 до " + MAX_GENERATORS);
        }
        script.setGeneratorCount(f.getGeneratorCount());
        script.setEnv(env);
        scriptRepo.save(script);

        // режимы тестовых данных: только файлы данных последней версии
        dataFiles.deleteByScriptId(scriptId);
        dataFiles.flush();
        Set<String> dataPaths = new HashSet<>();
        scripts.latest(scriptId).ifPresent(v -> v.getFiles().stream().filter(x -> x.kind().equals("DATA")).forEach(x -> dataPaths.add(x.path())));
        for (LoadForm.DataForm d : f.getDataFiles()) {
            if (d == null || d.getPath() == null || !dataPaths.contains(d.getPath())) {
                continue;
            }
            ScriptDataFile df = new ScriptDataFile();
            df.setScriptId(scriptId);
            df.setPath(d.getPath());
            df.setMode(d.getMode());
            df.setHasHeader(d.isHasHeader());
            df.setConsumed(d.isConsumed());
            dataFiles.save(df);
        }
        audit.record("script.load", "script", scriptId, Map.of("operations", built.size()));
    }

    private static void validateOp(LoadForm.OpForm o, Set<String> names) {
        String label = o.getName() == null ? "?" : o.getName();
        if (o.getName() == null || !OP_NAME.matcher(o.getName().trim()).matches()) {
            throw new IllegalArgumentException("Операция «" + label + "»: имя - латиница, цифры, _ и - (до 64 символов)");
        }
        if (!names.add(o.getName().trim())) {
            throw new IllegalArgumentException("Имя операции «" + label + "» повторяется");
        }
        if (o.getExecFn() == null || !FN_NAME.matcher(o.getExecFn().trim()).matches()) {
            throw new IllegalArgumentException("Операция «" + label + "»: укажите функцию скрипта (имя экспортируемой функции)");
        }
        if (o.getSlaPercentile() != 90 && o.getSlaPercentile() != 95 && o.getSlaPercentile() != 99) {
            throw new IllegalArgumentException("Операция «" + label + "»: перцентиль SLA - 90, 95 или 99");
        }
        if (o.getSlaThresholdMs() != null && o.getSlaThresholdMs() <= 0) {
            throw new IllegalArgumentException("Операция «" + label + "»: порог времени ответа должен быть больше 0");
        }
        if (o.getMaxErrorPct() != null && (o.getMaxErrorPct() < 0 || o.getMaxErrorPct() > 100)) {
            throw new IllegalArgumentException("Операция «" + label + "»: допустимый % ошибок - от 0 до 100");
        }
        if (o.getExpectedDurationSec() != null && o.getExpectedDurationSec() <= 0) {
            throw new IllegalArgumentException("Операция «" + label + "»: ожидаемая длительность должна быть больше 0");
        }
        if (o.getRatePerSec() != null && o.getRatePerSec() < 0) {
            throw new IllegalArgumentException("Операция «" + label + "»: интенсивность не может быть отрицательной");
        }
    }
}
