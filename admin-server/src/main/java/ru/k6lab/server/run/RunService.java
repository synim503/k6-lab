package ru.k6lab.server.run;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import ru.k6lab.core.runstate.RunStatus;
import ru.k6lab.server.audit.AuditService;
import ru.k6lab.server.generator.Generator;
import ru.k6lab.server.generator.GeneratorRepository;
import ru.k6lab.server.load.LoadCriteria;
import ru.k6lab.server.load.LoadOperation;
import ru.k6lab.server.load.LoadService;
import ru.k6lab.server.load.RunPlan;
import ru.k6lab.server.load.RunPlanner;
import ru.k6lab.server.script.Script;
import ru.k6lab.server.script.ScriptService;
import ru.k6lab.server.script.ScriptVersion;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;

/** Запуски: постановка в очередь (валидация, snapshot), чтение, заключение инженера, эталон. */
@Service
public class RunService {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Выбранный генератор и его вес (доля нагрузки). */
    public record GeneratorSel(Long generatorId, int weight) {
    }

    /**
     * Особые условия одного запуска (запуск по расписанию): env - полный набор переменных вместо заданных в скрипте (null - как в скрипте),
     * intensityPct - интенсивность по операциям в процентах от заданной в нагрузке (100 - без изменений); scheduleId - откуда запуск.
     */
    public record Overrides(Map<String, String> env, Map<String, Double> intensityPct, Long scheduleId, String scheduledBy,
                            Long profileId, String profileName) {

        public Overrides(Map<String, String> env, Map<String, Double> intensityPct, Long scheduleId, String scheduledBy) {
            this(env, intensityPct, scheduleId, scheduledBy, null, null);
        }

        public boolean scalesLoad() {
            return intensityPct != null && intensityPct.values().stream().anyMatch(v -> v != null && Math.abs(v - 100) > 1e-9);
        }
    }

    public record CreateRequest(Long scriptId, List<GeneratorSel> generators, Run.FailurePolicy failurePolicy,
                                Integer prepareTimeoutSec, List<K6Flag> flags, Overrides overrides) {

        public CreateRequest(Long scriptId, List<GeneratorSel> generators, Run.FailurePolicy failurePolicy, Integer prepareTimeoutSec,
                             List<K6Flag> flags) {
            this(scriptId, generators, failurePolicy, prepareTimeoutSec, flags, null);
        }

        public CreateRequest(Long scriptId, List<GeneratorSel> generators, Run.FailurePolicy failurePolicy, Integer prepareTimeoutSec) {
            this(scriptId, generators, failurePolicy, prepareTimeoutSec, List.of(), null);
        }
    }

    /** Конфиг k6 с интенсивностью, умноженной по операциям на заданные проценты (исходный конфиг плана не меняется). */
    static com.fasterxml.jackson.databind.JsonNode scaledConfig(com.fasterxml.jackson.databind.JsonNode config, Map<String, Double> pct) {
        if (config == null || pct == null || pct.isEmpty()) {
            return config;
        }
        com.fasterxml.jackson.databind.node.ObjectNode copy = config.deepCopy();
        pct.forEach((op, p) -> {
            if (p != null && copy.path("scenarios") instanceof com.fasterxml.jackson.databind.node.ObjectNode scenarios
                    && scenarios.path(op) instanceof com.fasterxml.jackson.databind.node.ObjectNode sc) {
                if (p <= 0) {
                    scenarios.remove(op);
                    if (copy.path("thresholds") instanceof com.fasterxml.jackson.databind.node.ObjectNode thr) {
                        List<String> drop = new ArrayList<>();
                        thr.fieldNames().forEachRemaining(k -> {
                            if (k.contains("scenario:" + op + "}") || k.contains("scenario:" + op + ",")) {
                                drop.add(k);
                            }
                        });
                        drop.forEach(thr::remove);
                    }
                } else {
                    ru.k6lab.core.k6config.ScenarioScaler.scale(sc, p / 100.0);
                }
            }
        });
        return copy;
    }

    private final RunRepository runs;
    private final RunGeneratorRepository runGenerators;
    private final RunStepRepository runSteps;
    private final GeneratorRepository generators;
    private final RunPlanner planner;
    private final LoadService load;
    private final ScriptService scripts;
    private final List<RunGate> gates;
    private final AuditService audit;
    private final TransactionTemplate tx;

    public RunService(RunRepository runs, RunGeneratorRepository runGenerators, RunStepRepository runSteps,
                      GeneratorRepository generators, RunPlanner planner, LoadService load, ScriptService scripts,
                      List<RunGate> gates,
                      AuditService audit, PlatformTransactionManager txm) {
        this.runs = runs;
        this.runGenerators = runGenerators;
        this.runSteps = runSteps;
        this.generators = generators;
        this.planner = planner;
        this.load = load;
        this.scripts = scripts;
        this.gates = gates;
        this.audit = audit;
        this.tx = new TransactionTemplate(txm);
    }

    // ------------------------------------------------------------------ создание

    /** Возможные причины, по которым запуск сейчас недоступен (диагностика Prometheus и т.п.). */
    public Optional<String> blockReason() {
        for (RunGate g : gates) {
            Optional<String> r = g.blockReason();
            if (r.isPresent()) {
                return r;
            }
        }
        return Optional.empty();
    }

    public Run create(CreateRequest req) throws IOException {
        blockReason().ifPresent(reason -> {
            throw new IllegalStateException(reason);
        });
        Script script = scripts.get(req.scriptId());

        // выбор генераторов
        List<GeneratorSel> sel = req.generators() == null ? List.of() : req.generators();
        if (sel.isEmpty()) {
            throw new IllegalArgumentException("Выберите генераторы");
        }
        Set<Long> seen = new HashSet<>();
        for (GeneratorSel g : sel) {
            if (g.weight() < 1) {
                throw new IllegalArgumentException("Вес генератора должен быть положительным целым");
            }
            if (!seen.add(g.generatorId())) {
                throw new IllegalArgumentException("Генератор выбран дважды");
            }
            if (!generators.existsById(g.generatorId())) {
                throw new IllegalArgumentException("Генератор не найден");
            }
        }
        int count = sel.size();

        // нагрузка из профиля (запуск по расписанию с профилем) вместо сохранённой в скрипте
        LoadService.BuiltLoad built = req.overrides() != null && req.overrides().profileId() != null
                ? load.fromProfile(script.getId(), req.overrides().profileId()) : null;
        RunPlan plan = planner.plan(script.getId(), count, built);
        if (!plan.ok()) {
            throw new IllegalArgumentException("Скрипт нельзя запустить: " + String.join("; ", plan.errors()));
        }
        if (count > 1 && plan.config().toString().contains("externally-controlled")) {
            throw new IllegalArgumentException("Executor externally-controlled не поддерживает деление на execution segments: "
                    + "запускайте такую нагрузку на одном генераторе");
        }

        Run run = new Run();
        run.setScriptId(script.getId());
        run.setScriptName(script.getName());
        run.setFailurePolicy(req.failurePolicy() == null ? Run.FailurePolicy.CONTINUE : req.failurePolicy());
        run.setPrepareTimeoutSec(req.prepareTimeoutSec() == null || req.prepareTimeoutSec() < 10 ? 120 : req.prepareTimeoutSec());
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("generators", sel.stream().map(g -> Map.of("id", g.generatorId(), "weight", g.weight())).toList());
        if (req.flags() != null && !req.flags().isEmpty()) {
            request.put("k6Flags", req.flags().stream().map(Enum::name).toList());
        }
        RunService.Overrides ov = req.overrides();
        if (ov != null && ov.scheduleId() != null) {
            request.put("scheduleId", ov.scheduleId());
        }
        if (ov != null && ov.profileId() != null) {
            request.put("loadProfileId", ov.profileId());
            if (ov.profileName() != null) {
                request.put("loadProfileName", ov.profileName());
            }
        }
        run.setRequest(request);
        if (ov != null && ov.intensityPct() != null) {
            Set<String> known = plan.operations().stream().map(LoadOperation::getName).collect(java.util.stream.Collectors.toSet());
            for (String op : ov.intensityPct().keySet()) {
                if (!known.contains(op)) {
                    throw new IllegalArgumentException("Интенсивность задана для операции «" + op + "», которой нет в нагрузке скрипта");
                }
            }
        }
        JsonNode effectiveConfig = ov != null && ov.scalesLoad() ? scaledConfig(plan.config(), ov.intensityPct()) : plan.config();
        if (effectiveConfig != null && effectiveConfig.path("scenarios").isEmpty()) {
            throw new IllegalArgumentException("Все операции имеют нулевую интенсивность — нагрузки не будет");
        }
        run.setK6Config(effectiveConfig);
        run.setScriptVersionId(plan.version().getId());
        run.setSnapshot(MAPPER.convertValue(buildSnapshot(plan, ov, built), new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {
        }));
        var auth = SecurityContextHolder.getContext().getAuthentication();
        run.setCreatedBy(auth == null ? "system" : auth.getName());
        Run saved = runs.save(run);
        audit.record("run.create", "run", saved.getId(), Map.of("script", script.getName(), "generators", count));
        return saved;
    }

    private RunSnapshot buildSnapshot(RunPlan plan, RunService.Overrides ov, LoadService.BuiltLoad built) {
        ScriptVersion v = plan.version();
        Script script = plan.script();
        List<RunSnapshot.OpInfo> ops = new ArrayList<>();
        for (LoadOperation o : plan.operations()) {
            // цель интенсивности в итогах - с учётом процента запуска по расписанию
            double f = ov == null || ov.intensityPct() == null || ov.intensityPct().get(o.getName()) == null ? 1 : ov.intensityPct().get(o.getName()) / 100.0;
            ops.add(new RunSnapshot.OpInfo(o.getName(), o.getExecFn(), o.getRatePerSec() * f, o.getExpectedDurationSec(),
                    o.getSlaPercentile(), o.getSlaThresholdMs(), o.getMaxErrorPct(), o.getFailureSource().name(), o.isEnabled() && f > 0,
                    ru.k6lab.core.k6config.K6ConfigGenerator.warmupRatePerSec(o.toSpec()) * f));
        }
        List<RunSnapshot.StepInfo> steps = plan.steps().stream()
                .map(st -> new RunSnapshot.StepInfo(st.no(), st.label(), st.levelPct(), st.rampSec(), st.holdSec())).toList();
        List<RunSnapshot.DataFileInfo> data = (built != null ? built.dataFiles() : load.dataFiles(script.getId())).stream()
                .map(d -> new RunSnapshot.DataFileInfo(d.getPath(), d.getMode().name(), d.isHasHeader(), d.isConsumed())).toList();
        Map<String, Object> rawStop = new LinkedHashMap<>();
        if (script.getStopCriteria() != null) {
            rawStop.putAll(script.getStopCriteria());
        }
        if (built != null && built.stop() != null) {
            rawStop.putAll(built.stop());
            // Если в профиле нет customChecks, но они настроены в базовом скрипте — всегда наследуем их!
            if ((!built.stop().containsKey("customChecks") || ((List<?>) built.stop().get("customChecks")).isEmpty())
                    && script.getStopCriteria() != null && script.getStopCriteria().containsKey("customChecks")) {
                rawStop.put("customChecks", script.getStopCriteria().get("customChecks"));
            }
        }
        return new RunSnapshot(
                new RunSnapshot.ScriptInfo(script.getId(), script.getName(), v.getId(), v.getNumber(), v.getSha256(), v.getEntrypoint(),
                        v.getSource().name(), v.getCommitHash(), v.getGitBranch()),
                ov != null && ov.env() != null ? new java.util.LinkedHashMap<>(ov.env()) : built != null ? new java.util.LinkedHashMap<>(built.env()) : script.getEnv(), ops, steps, plan.gracefulStopSec(), plan.gracefulRampDownSec(), data,
                LoadCriteria.stopToMap(LoadCriteria.stop(rawStop), LoadCriteria.customChecks(rawStop)),
                LoadCriteria.successToMap(LoadCriteria.success(script.getSuccessCriteria())), false,
                List.of(), null, withScheduleNote(plan.warnings(), ov),
                plan.steps().isEmpty() || plan.steps().get(0).rampSec() <= 0 ? null
                        : new RunSnapshot.Warmup(plan.steps().get(0).rampSec(), 0));
    }

    /** Предупреждения плана плюс пометка, что запуск шёл по расписанию с изменённой нагрузкой или переменными. */
    private static List<String> withScheduleNote(List<String> warnings, Overrides ov) {
        if (ov == null || (ov.scheduleId() == null && ov.profileName() == null)) {
            return warnings;
        }
        List<String> out = new ArrayList<>(warnings);
        StringBuilder sb = new StringBuilder(ov.scheduleId() == null ? "Запуск с профилем нагрузки «" + ov.profileName() + "»"
                : "Запуск по расписанию (#" + ov.scheduleId() + ")");
        if (ov.scheduleId() != null && ov.profileName() != null) {
            sb.append(", профиль нагрузки «").append(ov.profileName()).append('»');
        }
        if (ov.scheduledBy() != null) {
            sb.append(", назначил ").append(ov.scheduledBy());
        }
        if (ov.scalesLoad()) {
            sb.append(". Интенсивность: ");
            ov.intensityPct().entrySet().stream().filter(e -> e.getValue() != null && Math.abs(e.getValue() - 100) > 1e-9)
                    .forEach(e -> sb.append(e.getKey()).append(' ').append(java.math.BigDecimal.valueOf(e.getValue()).stripTrailingZeros().toPlainString()).append("% "));
        }
        if (ov.env() != null) {
            sb.append(". Переменные заданы при планировании");
        }
        out.add(0, sb.toString().trim());
        return out;
    }

    // ------------------------------------------------------------------ чтение

    public long count() {
        return runs.count();
    }

    public Run get(Long id) {
        return runs.findById(id).orElseThrow(() -> new IllegalArgumentException("Запуск не найден"));
    }

    public List<Run> search(Long scriptId, RunStatus status, int limit) {
        return runs.search(scriptId, status, PageRequest.of(0, Math.max(1, Math.min(limit, 500))));
    }

    /** Постраничный список запусков (для пагинации запусков на странице скрипта). */
    public org.springframework.data.domain.Page<Run> searchPage(Long scriptId, RunStatus status, int page, int size) {
        int safeSize = Math.max(5, Math.min(size, 100));
        int safePage = Math.max(0, page);
        var res = runs.searchPage(scriptId, status, PageRequest.of(safePage, safeSize));
        if (res.getTotalPages() > 0 && safePage >= res.getTotalPages()) {
            res = runs.searchPage(scriptId, status, PageRequest.of(res.getTotalPages() - 1, safeSize));
        }
        return res;
    }

    /** Run, занимающие генераторы (для восстановления после рестарта сервера). */
    public List<Run> activeRuns() {
        return runs.findByStatusIn(List.of(RunStatus.PREPARING, RunStatus.RUNNING, RunStatus.STOPPING));
    }

    /** Run, уже занимающие или ожидающие выдачи генераторов (включая QUEUED, чтобы планировщик не бронировал те же хосты поверх них). */
    public List<Run> activeAndQueuedRuns() {
        return runs.findByStatusIn(List.of(RunStatus.QUEUED, RunStatus.PREPARING, RunStatus.RUNNING, RunStatus.STOPPING));
    }

    public List<RunGenerator> generatorsOf(Long runId) {
        return runGenerators.findByRunIdOrderBySeqIndexAsc(runId);
    }

    public List<RunStep> stepsOf(Long runId) {
        return runSteps.findByRunIdOrderByStepNoAsc(runId);
    }

    public RunSnapshot snapshot(Run run) {
        return MAPPER.convertValue(run.getSnapshot(), RunSnapshot.class);
    }

    public ScriptVersion versionOf(Run run) {
        return scripts.version(run.getScriptVersionId());
    }

    // ------------------------------------------------------------------ изменение

    /** Изменение run в короткой транзакции с повтором при конфликте версий. */
    public Run update(Long id, Consumer<Run> change) {
        for (int attempt = 0; ; attempt++) {
            try {
                return tx.execute(st -> {
                    Run r = runs.findById(id).orElseThrow();
                    change.accept(r);
                    return runs.saveAndFlush(r);
                });
            } catch (OptimisticLockingFailureException e) {
                if (attempt >= 4) {
                    throw e;
                }
            }
        }
    }

    public RunGenerator updateGenerator(Long id, Consumer<RunGenerator> change) {
        return tx.execute(st -> {
            RunGenerator g = runGenerators.findById(id).orElseThrow();
            change.accept(g);
            return runGenerators.save(g);
        });
    }

    public boolean consumeArtifact(Long runGeneratorId, Instant now) {
        return Boolean.TRUE.equals(tx.execute(st -> {
            RunGenerator g = runGenerators.findById(runGeneratorId).orElse(null);
            if (g == null || g.getArtifactConsumedAt() != null) {
                return false;
            }
            g.setArtifactConsumedAt(now);
            return true;
        }));
    }

    public void saveSteps(Long runId, List<RunStep> steps) {
        tx.executeWithoutResult(st -> {
            runSteps.deleteByRunId(runId);
            runSteps.flush();
            runSteps.saveAll(steps);
        });
    }

    public void setConclusion(Long runId, String conclusion, String comment) {
        update(runId, r -> {
            r.setConclusion(conclusion == null || conclusion.isBlank() ? null : conclusion.trim());
            r.setConclusionComment(comment == null || comment.isBlank() ? null : comment.trim());
        });
        audit.record("run.conclusion", "run", runId, null);
    }

    public JsonNode config(Run run) {
        return run.getK6Config();
    }

    public Generator generator(Long id) {
        return generators.findById(id).orElse(null);
    }
}
