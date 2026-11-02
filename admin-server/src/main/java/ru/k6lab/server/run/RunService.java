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
import ru.k6lab.server.generator.GeneratorPool;
import ru.k6lab.server.generator.GeneratorPoolRepository;
import ru.k6lab.server.generator.GeneratorRepository;
import ru.k6lab.server.loadmodel.LoadOperation;
import ru.k6lab.server.scenario.Scenario;
import ru.k6lab.server.scenario.ScenarioCriteria;
import ru.k6lab.server.scenario.ScenarioPlan;
import ru.k6lab.server.scenario.ScenarioPlanner;
import ru.k6lab.server.scenario.ScenarioService;
import ru.k6lab.server.script.ScriptService;
import ru.k6lab.server.script.ScriptVersion;
import ru.k6lab.server.sut.Sut;
import ru.k6lab.server.sut.SutService;
import ru.k6lab.server.testdata.ScenarioDataFile;

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

    /** Выбор генераторов: список с весами либо пул и число генераторов. */
    public record GeneratorSel(Long generatorId, int weight) {
    }

    public record CreateRequest(Long scenarioId, List<GeneratorSel> generators, Long poolId, Integer poolCount,
                                Run.FailurePolicy failurePolicy, Integer prepareTimeoutSec) {
    }

    private final RunRepository runs;
    private final RunGeneratorRepository runGenerators;
    private final RunStepRepository runSteps;
    private final GeneratorRepository generators;
    private final GeneratorPoolRepository pools;
    private final ScenarioService scenarios;
    private final ScenarioPlanner planner;
    private final ScriptService scripts;
    private final SutService suts;
    private final List<RunGate> gates;
    private final AuditService audit;
    private final TransactionTemplate tx;

    public RunService(RunRepository runs, RunGeneratorRepository runGenerators, RunStepRepository runSteps,
                      GeneratorRepository generators, GeneratorPoolRepository pools, ScenarioService scenarios,
                      ScenarioPlanner planner, ScriptService scripts, SutService suts, List<RunGate> gates,
                      AuditService audit, PlatformTransactionManager txm) {
        this.runs = runs;
        this.runGenerators = runGenerators;
        this.runSteps = runSteps;
        this.generators = generators;
        this.pools = pools;
        this.scenarios = scenarios;
        this.planner = planner;
        this.scripts = scripts;
        this.suts = suts;
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
        Scenario scenario = scenarios.get(req.scenarioId());

        // выбор генераторов
        List<GeneratorSel> sel = req.generators() == null ? List.of() : req.generators();
        int count;
        if (req.poolId() != null) {
            GeneratorPool pool = pools.findById(req.poolId()).orElseThrow(() -> new IllegalArgumentException("Пул не найден"));
            count = req.poolCount() == null ? 0 : req.poolCount();
            long total = generators.findByPoolIdOrderByNameAsc(pool.getId()).size();
            if (count < 1) {
                throw new IllegalArgumentException("Укажите число генераторов из пула");
            }
            if (count > total) {
                throw new IllegalArgumentException("В пуле «" + pool.getName() + "» только " + total + " генераторов");
            }
        } else {
            if (sel.isEmpty()) {
                throw new IllegalArgumentException("Выберите генераторы или пул");
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
            count = sel.size();
        }

        // «последняя версия ветки Git»: коммит фиксируется на момент запуска
        Long forcedVersion = null;
        if (scenario.isFollowBranch()) {
            forcedVersion = scripts.importLatestOfBranch(scenario.getScriptId()).getId();
        }
        ScenarioPlan plan = planner.plan(scenario, count, forcedVersion);
        if (!plan.ok()) {
            throw new IllegalArgumentException("Сценарий нельзя запустить: " + String.join("; ", plan.errors()));
        }
        if (count > 1 && plan.config().toString().contains("externally-controlled")) {
            throw new IllegalArgumentException("Executor externally-controlled не поддерживает деление на execution segments: "
                    + "запускайте такой сценарий на одном генераторе");
        }

        Run run = new Run();
        run.setScenarioId(scenario.getId());
        run.setScenarioName(scenario.getName());
        run.setFailurePolicy(req.failurePolicy() == null ? Run.FailurePolicy.CONTINUE : req.failurePolicy());
        run.setPrepareTimeoutSec(req.prepareTimeoutSec() == null || req.prepareTimeoutSec() < 10 ? 120 : req.prepareTimeoutSec());
        Map<String, Object> request = new LinkedHashMap<>();
        if (req.poolId() != null) {
            request.put("poolId", req.poolId());
            request.put("poolCount", count);
        } else {
            request.put("generators", sel.stream().map(g -> Map.of("id", g.generatorId(), "weight", g.weight())).toList());
        }
        run.setRequest(request);
        run.setK6Config(plan.config());
        run.setScriptVersionId(plan.version().getId());
        run.setSnapshot(MAPPER.convertValue(buildSnapshot(scenario, plan), new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {
        }));
        var auth = SecurityContextHolder.getContext().getAuthentication();
        run.setCreatedBy(auth == null ? "system" : auth.getName());
        Run saved = runs.save(run);
        audit.record("run.create", "run", saved.getId(), Map.of("scenario", scenario.getName(), "generators", count));
        return saved;
    }

    private RunSnapshot buildSnapshot(Scenario s, ScenarioPlan plan) {
        ScriptVersion v = plan.version();
        var script = scripts.get(s.getScriptId());
        List<RunSnapshot.OpInfo> ops = new ArrayList<>();
        for (LoadOperation o : plan.model().getOperations()) {
            ops.add(new RunSnapshot.OpInfo(o.getName(), o.getExecFn(), o.getMode().name(), o.ratePerSec(), o.getExpectedDurationSec(),
                    o.getPacingMs(), o.getSlaPercentile(), o.getSlaThresholdMs(), o.getMaxErrorPct(), o.getFailureSource().name(),
                    o.isEnabled(), o.getFixedLevelPct()));
        }
        List<RunSnapshot.StepInfo> steps = plan.steps().stream()
                .map(st -> new RunSnapshot.StepInfo(st.no(), st.label(), st.levelPct(), st.rampSec(), st.holdSec())).toList();
        List<RunSnapshot.DataFileInfo> data = scenarios.dataFiles(s.getId()).stream()
                .map(d -> new RunSnapshot.DataFileInfo(d.getPath(), d.getMode().name(), d.isHasHeader(), d.isConsumed())).toList();
        RunSnapshot.SutInfo sut = null;
        if (s.getSutId() != null) {
            Sut x = suts.get(s.getSutId());
            sut = new RunSnapshot.SutInfo(x.getId(), x.getName(), x.getGrafanaUrlTpl(), x.getMetrics().stream()
                    .map(m -> new RunSnapshot.SutMetricInfo(m.getName(), m.getPromql(), m.getUnit(), m.getAggregation().name(),
                            m.getWarnThreshold(), m.getWarnDirection().name())).toList());
        }
        return new RunSnapshot(
                new RunSnapshot.ScenarioInfo(s.getId(), s.getName(), s.getDescription(), s.getEnv(), s.isExpertMode(), s.isFollowBranch()),
                new RunSnapshot.ScriptInfo(script.getId(), script.getName(), v.getId(), v.getNumber(), v.getSha256(), v.getEntrypoint(),
                        v.getSource().name(), v.getCommitHash(), v.getGitBranch()),
                new RunSnapshot.ModelInfo(plan.model().getId(), plan.model().getName(), ops),
                new RunSnapshot.ProfileInfo(plan.profile().getId(), plan.profile().getName(), plan.profile().getType().name(),
                        plan.profile().getParams(), plan.profile().getGracefulStopSec(), plan.profile().getGracefulRampDownSec()),
                steps, data, ScenarioCriteria.stopToMap(ScenarioCriteria.stop(s.getStopCriteria(), plan.profile().getType())),
                ScenarioCriteria.successToMap(ScenarioCriteria.success(s.getSuccessCriteria())), sut, v.isUsesHelper(),
                List.of(), null, plan.warnings());
    }

    // ------------------------------------------------------------------ чтение

    public Run get(Long id) {
        return runs.findById(id).orElseThrow(() -> new IllegalArgumentException("Запуск не найден"));
    }

    public List<Run> search(Long scenarioId, RunStatus status, int limit) {
        return runs.search(scenarioId, status, PageRequest.of(0, Math.max(1, Math.min(limit, 500))));
    }

    /** Run, занимающие генераторы (для восстановления после рестарта сервера). */
    public List<Run> activeRuns() {
        return runs.findByStatusIn(List.of(RunStatus.PREPARING, RunStatus.RUNNING, RunStatus.STOPPING));
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

    public Optional<Run> baselineOf(Long scenarioId) {
        return runs.findFirstByScenarioIdAndBaselineTrue(scenarioId);
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

    /** Помечает run эталонным для сценария (у сценария один эталон). */
    public void setBaseline(Long runId, boolean on) {
        Run run = get(runId);
        if (run.getScenarioId() != null && on) {
            runs.findByScenarioIdOrderByIdDesc(run.getScenarioId()).stream().filter(Run::isBaseline)
                    .forEach(r -> update(r.getId(), x -> x.setBaseline(false)));
        }
        update(runId, r -> r.setBaseline(on));
        audit.record(on ? "run.baseline_on" : "run.baseline_off", "run", runId, null);
    }

    public JsonNode config(Run run) {
        return run.getK6Config();
    }

    public Generator generator(Long id) {
        return generators.findById(id).orElse(null);
    }
}
