package ru.k6lab.server.load;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.k6lab.core.model.OperationSpec;
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
    private final LoadProfileStore profiles;

    public LoadService(ScriptService scripts, ScriptRepository scriptRepo, LoadOperationRepository operations,
                       ScriptDataFileRepository dataFiles, SecretService secrets, AuditService audit, LoadProfileStore profiles) {
        this.profiles = profiles;
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
        return activeOf(scriptId, operations(scriptId));
    }

    /** Из переданных операций (сохранённых или взятых из профиля) - те, функции которых есть в последней версии скрипта. */
    @Transactional(readOnly = true)
    public List<LoadOperation> activeOf(Long scriptId, List<LoadOperation> all) {
        Set<String> available = scripts.latest(scriptId).map(v -> (Set<String>) new HashSet<>(v.operationNames())).orElse(null);
        return available == null ? all : all.stream().filter(o -> available.contains(o.getExecFn())).toList();
    }

    /** Операции нагрузки, функции которых пропали из последней версии скрипта. */
    @Transactional(readOnly = true)
    public List<LoadOperation> staleOperations(Long scriptId) {
        return staleOf(scriptId, operations(scriptId));
    }

    @Transactional(readOnly = true)
    public List<LoadOperation> staleOf(Long scriptId, List<LoadOperation> all) {
        Set<String> active = new HashSet<>();
        activeOf(scriptId, all).forEach(o -> active.add(o.getExecFn()));
        return all.stream().filter(o -> !active.contains(o.getExecFn())).toList();
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
        f.setRampSec(0); // разгона нет: нагрузка стартует сразу на целевой интенсивности
        f.setHoldMinutes(script.getHoldSec() / 60.0);
        f.setGeneratorCount(script.getGeneratorCount());
        f.setEnvText(LoadForm.formatKv(script.getEnv()));
        f.setStop(LoadForm.StopForm.of(script.getStopCriteria()));
        readBaseConfigIntoForm(script, f);
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
            of.setExecutor(o.getExecutor());
            of.setScenario(o.getScenario());
            of.setEnabled(true);
            f.getOps().add(of);
        }
        completeWithScriptFunctions(scriptId, f);
        applyBaseSlaToFormOps(script, f);
        f.setDataFiles(dataRows(scriptId, dataFiles(scriptId)));
        return f;
    }

    /** Заполняет в форме настройки базового профиля скрипта (приоритет разделов и общий прогрев из script.successCriteria). */
    public void readBaseConfigIntoForm(Script script, LoadForm f) {
        Map<String, Object> sc = script.getSuccessCriteria();
        if (sc == null) {
            return;
        }
        f.setBaseProfileActive(Boolean.TRUE.equals(sc.get("baseProfileActive")));
        f.setBaseOverrideEnv(!sc.containsKey("baseOverrideEnv") || Boolean.TRUE.equals(sc.get("baseOverrideEnv")));
        f.setBaseOverrideStop(!sc.containsKey("baseOverrideStop") || Boolean.TRUE.equals(sc.get("baseOverrideStop")));
        f.setBaseOverrideSla(!sc.containsKey("baseOverrideSla") || Boolean.TRUE.equals(sc.get("baseOverrideSla")));
        f.setBaseOverrideWarmup(!sc.containsKey("baseOverrideWarmup") || Boolean.TRUE.equals(sc.get("baseOverrideWarmup")));
        f.setBaseOverrideData(!sc.containsKey("baseOverrideData") || Boolean.TRUE.equals(sc.get("baseOverrideData")));
        f.setBaseOverrideGenerators(!sc.containsKey("baseOverrideGenerators") || Boolean.TRUE.equals(sc.get("baseOverrideGenerators")));
        Object wd = sc.get("baseWarmupDuration");
        f.setBaseWarmupDuration(wd instanceof String s ? s.strip() : "");
    }

    /** Применяет сохранённые в базовом профиле SLA к операциям формы (в том числе выключенным в базе, но используемым в 40 профилях). */
    @SuppressWarnings("unchecked")
    private void applyBaseSlaToFormOps(Script script, LoadForm f) {
        Map<String, Object> sc = script.getSuccessCriteria();
        if (sc == null || !(sc.get("baseOpSla") instanceof Map<?, ?> rawMap)) {
            return;
        }
        for (LoadForm.OpForm o : f.getOps()) {
            if (o == null || o.blank()) {
                continue;
            }
            Object entry = rawMap.get(o.getName() == null ? "" : o.getName().strip());
            if (!(entry instanceof Map<?, ?>) && o.getExecFn() != null) {
                entry = rawMap.get(o.getExecFn().strip());
            }
            if (entry instanceof Map<?, ?> m) {
                if (m.get("slaPercentile") instanceof Number n) {
                    o.setSlaPercentile(n.intValue());
                }
                if (m.get("slaThresholdMs") instanceof Number n) {
                    o.setSlaThresholdMs(n.doubleValue());
                }
                if (m.get("maxErrorPct") instanceof Number n) {
                    o.setMaxErrorPct(n.doubleValue());
                }
                if (m.get("failureSource") instanceof String fs && !fs.isBlank()) {
                    try {
                        o.setFailureSource(OperationSpec.FailureSource.valueOf(fs));
                    } catch (IllegalArgumentException ignored) {
                    }
                }
            }
        }
    }

    /** Функции последней версии скрипта, которых в форме ещё нет, добавляются выключенными строками (их можно включить и описать). */
    @Transactional(readOnly = true)
    public void completeWithScriptFunctions(Long scriptId, LoadForm f) {
        Set<String> have = new HashSet<>();
        f.getOps().forEach(o -> have.add(o.getExecFn()));
        scripts.latest(scriptId).ifPresent(v -> v.operationNames().stream().filter(n -> !have.contains(n)).forEach(n -> {
            LoadForm.OpForm of = new LoadForm.OpForm();
            of.setName(n);
            of.setExecFn(n);
            of.setExecutor("constant-arrival-rate");
            of.setEnabled(false);
            f.getOps().add(of);
        }));
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

    /** Нагрузка, собранная из формы или профиля, но не сохранённая в скрипт: операции, переменные, режимы файлов данных; profileName - у профиля. */
    public record BuiltLoad(List<LoadOperation> ops, Map<String, String> env, List<ScriptDataFile> dataFiles, String profileName,
                            Map<String, Object> stop) {

        public BuiltLoad(List<LoadOperation> ops, Map<String, String> env, List<ScriptDataFile> dataFiles, String profileName) {
            this(ops, env, dataFiles, profileName, null);
        }
    }

    @Transactional
    public void save(Long scriptId, LoadForm f) {
        Script script = scripts.get(scriptId);
        boolean allowEmptyOps = f.isBaseProfileActive() || !profiles.list(scriptId).isEmpty();
        BuiltLoad b = build(scriptId, f, allowEmptyOps);
        operations.deleteByScriptId(scriptId);
        operations.flush();
        operations.saveAll(b.ops());

        if (f.getGeneratorCount() != null) {
            if (f.getGeneratorCount() < 1 || f.getGeneratorCount() > MAX_GENERATORS) {
                throw new IllegalArgumentException("Число генераторов: от 1 до " + MAX_GENERATORS);
            }
            script.setGeneratorCount(f.getGeneratorCount());
        }
        script.setEnv(b.env());
        script.setStopCriteria(b.stop() == null ? new java.util.LinkedHashMap<>() : new java.util.LinkedHashMap<>(b.stop()));
        saveBaseConfigToScript(script, f);
        scriptRepo.save(script);

        // режимы тестовых данных: только файлы данных последней версии
        dataFiles.deleteByScriptId(scriptId);
        dataFiles.flush();
        b.dataFiles().forEach(dataFiles::save);

        if (f.isBaseProfileActive()) {
            syncBaseToAllProfiles(scriptId, "system");
        }
        audit.record("script.load", "script", scriptId, Map.of("operations", b.ops().size()));
    }

    /** Сохраняет метаданные базового профиля (приоритеты разделов, общий прогрев и SLA всех операций) в script.successCriteria. */
    private void saveBaseConfigToScript(Script script, LoadForm f) {
        Map<String, Object> sc = script.getSuccessCriteria() == null
                ? new java.util.LinkedHashMap<>()
                : new java.util.LinkedHashMap<>(script.getSuccessCriteria());
        sc.put("baseProfileActive", f.isBaseProfileActive());
        sc.put("baseOverrideEnv", f.isBaseOverrideEnv());
        sc.put("baseOverrideStop", f.isBaseOverrideStop());
        sc.put("baseOverrideSla", f.isBaseOverrideSla());
        sc.put("baseOverrideWarmup", f.isBaseOverrideWarmup());
        sc.put("baseOverrideData", f.isBaseOverrideData());
        sc.put("baseOverrideGenerators", f.isBaseOverrideGenerators());
        String warmDur = f.getBaseWarmupDuration() == null ? "" : f.getBaseWarmupDuration().strip();
        if (warmDur.isEmpty()) {
            warmDur = extractWarmupDurationFromOps(f.getOps());
        }
        if (!warmDur.isEmpty()) {
            double sec = ru.k6lab.core.k6config.ConfigDuration.parse(warmDur, -1);
            if (sec < 5 || sec > 3600) {
                throw new IllegalArgumentException("Общий прогрев: укажите длительность от 5 секунд до 1 часа (например, 2m или 30s)");
            }
            script.setWarmupSec((int) Math.round(sec));
        } else {
            script.setWarmupSec(0);
        }
        sc.put("baseWarmupDuration", warmDur);

        Map<String, Object> baseOpSla = sc.get("baseOpSla") instanceof Map<?, ?> prev
                ? new java.util.LinkedHashMap<>((Map<String, Object>) prev)
                : new java.util.LinkedHashMap<>();
        if (f.getOps() != null) {
            for (LoadForm.OpForm o : f.getOps()) {
                if (o == null || o.blank()) {
                    continue;
                }
                validateSlaOnly(o);
                Map<String, Object> slaEntry = new java.util.LinkedHashMap<>();
                slaEntry.put("slaPercentile", o.getSlaPercentile());
                slaEntry.put("slaThresholdMs", o.getSlaThresholdMs() == null ? 1000.0 : o.getSlaThresholdMs());
                slaEntry.put("maxErrorPct", o.getMaxErrorPct() == null ? 1.0 : o.getMaxErrorPct());
                slaEntry.put("failureSource", (o.getFailureSource() == null ? OperationSpec.FailureSource.HTTP : o.getFailureSource()).name());
                if (o.getName() != null && !o.getName().isBlank()) {
                    baseOpSla.put(o.getName().strip(), slaEntry);
                }
                if (o.getExecFn() != null && !o.getExecFn().isBlank()) {
                    baseOpSla.put(o.getExecFn().strip(), slaEntry);
                }
            }
        }
        sc.put("baseOpSla", baseOpSla);
        script.setSuccessCriteria(sc);
    }

    private static String extractWarmupDurationFromOps(List<LoadForm.OpForm> ops) {
        if (ops == null) {
            return "";
        }
        for (LoadForm.OpForm o : ops) {
            if (o == null || !o.isEnabled()) {
                continue;
            }
            Map<String, Object> sc = o.getScenario();
            if (sc.get("warmup") instanceof Map<?, ?> wm) {
                if (wm.get("duration") instanceof String d && !d.isBlank()) {
                    return d.strip();
                }
                if (wm.get("stages") instanceof Number n && n.intValue() > 0 && sc.get("stages") instanceof List<?> st && !st.isEmpty()) {
                    if (st.get(0) instanceof Map<?, ?> first && first.get("duration") instanceof String d && !d.isBlank()) {
                        return d.strip();
                    }
                }
            }
        }
        return "";
    }

    /** Применяет базовые параметры скрипта к форме профиля (согласно включённым флагам baseOverride*). */
    @Transactional(readOnly = true)
    public void applyBaseOverridesToProfileForm(Long scriptId, LoadForm profileForm) {
        applyBaseOverridesToProfileForm(scripts.get(scriptId), profileForm, false);
    }

    /**
     * Применяет базовые параметры скрипта к форме профиля.
     * @param force если true — применяет выбранные галочками разделы даже если baseProfileActive ещё не был сохранён.
     */
    @Transactional(readOnly = true)
    public void applyBaseOverridesToProfileForm(Script script, LoadForm profileForm, boolean force) {
        Map<String, Object> sc = script.getSuccessCriteria();
        boolean active = force || (sc != null && Boolean.TRUE.equals(sc.get("baseProfileActive")));
        readBaseConfigIntoForm(script, profileForm);
        // Кастомные метрики Prometheus из базового скрипта наследуются всеми профилями всегда (если в самом профиле список пуст или включён приоритет базы)
        List<ru.k6lab.core.analysis.StepAnalysis.CustomCheck> baseChecks = LoadCriteria.customChecks(script.getStopCriteria());
        if (!baseChecks.isEmpty()) {
            if (profileForm.getStop() == null) {
                profileForm.setStop(new LoadForm.StopForm());
            }
            boolean profileHasChecks = profileForm.getStop().getCustomChecks() != null
                    && profileForm.getStop().getCustomChecks().stream().anyMatch(c -> c != null && !c.blank());
            if (!profileHasChecks || (active && profileForm.isBaseOverrideStop())) {
                List<LoadForm.CustomCheckForm> inherited = new ArrayList<>(baseChecks.size());
                for (var cc : baseChecks) {
                    inherited.add(LoadForm.CustomCheckForm.of(cc));
                }
                profileForm.getStop().setCustomChecks(inherited);
            }
        }
        if (!active) {
            return;
        }
        if (profileForm.isBaseOverrideGenerators()) {
            profileForm.setGeneratorCount(script.getGeneratorCount());
        }
        if (profileForm.isBaseOverrideEnv()) {
            profileForm.setEnvText(LoadForm.formatKv(script.getEnv()));
        }
        if (profileForm.isBaseOverrideStop()) {
            profileForm.setStop(LoadForm.StopForm.of(script.getStopCriteria()));
        }
        if (profileForm.isBaseOverrideData()) {
            profileForm.setDataFiles(dataRows(script.getId(), dataFiles(script.getId())));
        }
        if (profileForm.isBaseOverrideSla()) {
            applyBaseSlaToProfileOps(script, profileForm);
        }
        if (profileForm.isBaseOverrideWarmup()) {
            applyBaseWarmupToProfileOps(profileForm.getBaseWarmupDuration(), profileForm.getOps());
        }
    }

    private void applyBaseSlaToProfileOps(Script script, LoadForm profileForm) {
        applyBaseSlaToFormOps(script, profileForm);
        List<LoadOperation> savedOps = activeOperations(script.getId());
        if (savedOps.isEmpty()) {
            return;
        }
        for (LoadForm.OpForm po : profileForm.getOps()) {
            if (po == null || po.blank()) {
                continue;
            }
            LoadOperation match = savedOps.stream()
                    .filter(bo -> bo.getName().equals(po.getName()) || bo.getExecFn().equals(po.getExecFn()))
                    .findFirst().orElse(null);
            if (match != null) {
                po.setSlaPercentile(match.getSlaPercentile());
                po.setSlaThresholdMs(match.getSlaThresholdMs());
                po.setMaxErrorPct(match.getMaxErrorPct());
                po.setFailureSource(match.getFailureSource());
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static void applyBaseWarmupToProfileOps(String warmDur, List<LoadForm.OpForm> ops) {
        if (ops == null) {
            return;
        }
        String dur = warmDur == null ? "" : warmDur.strip();
        for (LoadForm.OpForm o : ops) {
            if (o == null || o.blank()) {
                continue;
            }
            Map<String, Object> sc = new java.util.LinkedHashMap<>(o.parseScenario());
            String ex = o.getExecutor() == null ? "" : o.getExecutor().strip();
            boolean isRamp = ex.startsWith("ramping-");
            if (dur.isEmpty()) {
                // прогрев отключён в базе — убираем прогрев из сценария профиля
                if (isRamp && sc.get("warmup") instanceof Map<?, ?> wm && wm.get("stages") instanceof Number n && n.intValue() > 0) {
                    int wn = n.intValue();
                    if (sc.get("stages") instanceof List<?> rawStages && rawStages.size() > wn) {
                        sc.put("stages", new ArrayList<>(rawStages.subList(wn, rawStages.size())));
                    }
                }
                sc.remove("warmup");
            } else if (isRamp) {
                List<Map<String, Object>> stages = new ArrayList<>();
                if (sc.get("stages") instanceof List<?> rawList) {
                    for (Object item : rawList) {
                        if (item instanceof Map<?, ?> m) {
                            Map<String, Object> copy = new java.util.LinkedHashMap<>();
                            m.forEach((k, v) -> copy.put(String.valueOf(k), v));
                            stages.add(copy);
                        }
                    }
                }
                int existingWarm = (sc.get("warmup") instanceof Map<?, ?> wm && wm.get("stages") instanceof Number n) ? n.intValue() : 0;
                if (existingWarm > 0 && !stages.isEmpty()) {
                    stages.get(0).put("duration", dur);
                } else {
                    double firstTarget = 50;
                    for (Map<String, Object> st : stages) {
                        if (st.get("target") instanceof Number tn && tn.doubleValue() > 0) {
                            firstTarget = tn.doubleValue();
                            break;
                        }
                    }
                    Map<String, Object> warmStage = new java.util.LinkedHashMap<>();
                    warmStage.put("duration", dur);
                    warmStage.put("target", Math.max(1L, Math.round(firstTarget * 0.3)));
                    stages.add(0, warmStage);
                }
                sc.put("stages", stages);
                sc.put("warmup", Map.of("stages", 1));
            } else if ("constant-arrival-rate".equals(ex) || "constant-vus".equals(ex)) {
                sc.put("warmup", Map.of("duration", dur));
            }
            o.setScenario(sc);
        }
    }

    /** Прописывает базовые параметры скрипта напрямую во все сохранённые профили скрипта (чтобы и в БД, и при JSON-экспорте 40 профилей были актуальные данные). */
    @Transactional
    public int syncBaseToAllProfiles(Long scriptId, String user) {
        Script script = scripts.get(scriptId);
        int updated = 0;
        for (var p : profiles.list(scriptId)) {
            try {
                LoadForm pf = LoadJson.fromJson(p.data());
                applyBaseOverridesToProfileForm(script, pf, true);
                profiles.save(scriptId, p.name(), LoadJson.toJson(pf), user == null ? "system" : user);
                updated++;
            } catch (IllegalArgumentException ignored) {
            }
        }
        return updated;
    }

    /** Проверяет форму нагрузки и собирает из неё операции, переменные и режимы файлов данных, ничего не сохраняя (общая часть сохранения и запуска по профилю). */
    @Transactional(readOnly = true)
    public BuiltLoad build(Long scriptId, LoadForm f) {
        return build(scriptId, f, false);
    }

    @Transactional(readOnly = true)
    public BuiltLoad build(Long scriptId, LoadForm f, boolean allowEmptyOps) {
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
            if (o == null || o.blank() || !o.isEnabled()) {
                continue; // выключенная операция в нагрузке не участвует и не хранится
            }
            validateOp(o, names);
            String executor = o.getExecutor() == null || o.getExecutor().isBlank() ? OperationSpec.PROFILE_EXECUTOR : o.getExecutor().trim();
            Map<String, Object> scenario = o.parseScenario();
            double rate = o.getRatePerSec() == null ? 0 : o.getRatePerSec();
            if (OperationSpec.isProfile(executor, scenario)) {
                // режим «по профилю» больше не поддерживается: операция с оп/с становится constant-arrival-rate (уровень и удержание из формы/конфига)
                if (rate <= 0) {
                    continue;
                }
                var converted = ru.k6lab.core.k6config.ProfileConverter.toConstantArrivalRate(rate, f.getLevelPct(), holdSec,
                        o.getExpectedDurationSec() == null ? 0.3 : o.getExpectedDurationSec(), scenario);
                executor = "constant-arrival-rate";
                scenario = converted.scenario();
                rate = converted.ratePerSec();
            } else {
                rate = effectiveRate(executor, scenario);
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
            op.setExecutor(executor);
            op.setScenario(scenario);
            built.add(op);
        }
        for (LoadOperation b : built) {
            ru.k6lab.core.k6config.K6ConfigGenerator.validateExecutor(b.toSpec()); // поля сценария - сразу при сохранении
        }
        if (built.isEmpty() && !allowEmptyOps) {
            throw new IllegalArgumentException("Включите хотя бы одну операцию");
        }
        // режимы тестовых данных: только файлы данных последней версии
        List<ScriptDataFile> data = new ArrayList<>();
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
            data.add(df);
        }
        LoadForm.StopForm stopForm = f.getStop() == null ? new LoadForm.StopForm() : f.getStop();
        stopForm.validate();
        return new BuiltLoad(built, env, data, null, stopForm.toMap());
    }

    /** Нагрузка из сохранённого профиля скрипта для запуска (операции, переменные, файлы данных); ошибка понятным сообщением, если профиль удалён или не проходит проверку. */
    @Transactional(readOnly = true)
    public BuiltLoad fromProfile(Long scriptId, Long profileId) {
        var p = profiles.find(scriptId, profileId).orElseThrow(() -> new IllegalArgumentException("Профиль нагрузки не найден (возможно, удалён)"));
        try {
            LoadForm pf = LoadJson.fromJson(p.data());
            applyBaseOverridesToProfileForm(scriptId, pf);
            BuiltLoad b = build(scriptId, pf);
            return new BuiltLoad(b.ops(), b.env(), b.dataFiles(), p.name(), b.stop());
        } catch (IllegalArgumentException | IllegalStateException e) {
            throw new IllegalArgumentException("Профиль «" + p.name() + "»: " + e.getMessage());
        }
    }

    /** Названия включённых операций каждого профиля скрипта (для формы планирования: интенсивность задаётся по операциям выбранного профиля). */
    @Transactional(readOnly = true)
    public Map<Long, List<String>> profileOperationNames(Long scriptId) {
        Map<Long, List<String>> out = new java.util.LinkedHashMap<>();
        for (var p : profiles.list(scriptId)) {
            try {
                out.put(p.id(), LoadJson.fromJson(p.data()).getOps().stream().filter(LoadForm.OpForm::isEnabled)
                        .filter(o -> !o.blank()).map(LoadForm.OpForm::getName).toList());
            } catch (IllegalArgumentException e) {
                out.put(p.id(), List.of());
            }
        }
        return out;
    }

    /** Переменные каждого профиля текстом KEY=VALUE (для подстановки в форму планирования). */
    @Transactional(readOnly = true)
    public Map<Long, String> profileEnvText(Long scriptId) {
        Map<Long, String> out = new java.util.LinkedHashMap<>();
        Script script = scripts.get(scriptId);
        for (var p : profiles.list(scriptId)) {
            try {
                LoadForm pf = LoadJson.fromJson(p.data());
                applyBaseOverridesToProfileForm(script, pf, false);
                String t = pf.getEnvText();
                out.put(p.id(), t == null ? "" : t);
            } catch (IllegalArgumentException e) {
                out.put(p.id(), "");
            }
        }
        return out;
    }

    /** Интенсивность в оп/с для constant-arrival-rate (rate за timeUnit); для остальных исполнителей плана по оп/с нет. */
    static double effectiveRate(String executor, Map<String, Object> scenario) {
        if (!"constant-arrival-rate".equals(executor) || !(scenario.get("rate") instanceof Number n)) {
            return 0;
        }
        double unit = ru.k6lab.core.k6config.ConfigDuration.parse(String.valueOf(scenario.getOrDefault("timeUnit", "1s")), 1);
        return unit > 0 ? n.doubleValue() / unit : 0;
    }

    private static void validateSlaOnly(LoadForm.OpForm o) {
        String label = o.getName() == null ? (o.getExecFn() == null ? "?" : o.getExecFn()) : o.getName();
        if (o.getSlaPercentile() != 90 && o.getSlaPercentile() != 95 && o.getSlaPercentile() != 99) {
            throw new IllegalArgumentException("Операция «" + label + "»: перцентиль SLA - 90, 95 или 99");
        }
        if (o.getSlaThresholdMs() != null && o.getSlaThresholdMs() <= 0) {
            throw new IllegalArgumentException("Операция «" + label + "»: порог времени ответа должен быть больше 0");
        }
        if (o.getMaxErrorPct() != null && (o.getMaxErrorPct() < 0 || o.getMaxErrorPct() > 100)) {
            throw new IllegalArgumentException("Операция «" + label + "»: допустимый % ошибок - от 0 до 100");
        }
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
        String ex = o.getExecutor() == null || o.getExecutor().isBlank() ? OperationSpec.PROFILE_EXECUTOR : o.getExecutor().trim();
        if (!ru.k6lab.core.k6config.K6ConfigGenerator.executors().contains(ex)) {
            throw new IllegalArgumentException("Операция «" + label + "»: неизвестный исполнитель «" + ex + "». Допустимые: "
                    + String.join(", ", ru.k6lab.core.k6config.K6ConfigGenerator.executors()));
        }
        o.parseScenario(); // ошибка в JSON параметров - сразу понятным сообщением
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
