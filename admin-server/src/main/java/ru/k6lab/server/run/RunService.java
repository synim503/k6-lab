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

    public record CreateRequest(Long scriptId, List<GeneratorSel> generators, Run.FailurePolicy failurePolicy,
                                Integer prepareTimeoutSec, List<K6Flag> flags) {

        public CreateRequest(Long scriptId, List<GeneratorSel> generators, Run.FailurePolicy failurePolicy, Integer prepareTimeoutSec) {
            this(scriptId, generators, failurePolicy, prepareTimeoutSec, List.of());
        }
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

        RunPlan plan = planner.plan(script.getId(), count);
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
        run.setRequest(request);
        run.setK6Config(plan.config());
        run.setScriptVersionId(plan.version().getId());
        run.setSnapshot(MAPPER.convertValue(buildSnapshot(plan), new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {
        }));
        var auth = SecurityContextHolder.getContext().getAuthentication();
        run.setCreatedBy(auth == null ? "system" : auth.getName());
        Run saved = runs.save(run);
        audit.record("run.create", "run", saved.getId(), Map.of("script", script.getName(), "generators", count));
        return saved;
    }

    private RunSnapshot buildSnapshot(RunPlan plan) {
        ScriptVersion v = plan.version();
        Script script = plan.script();
        List<RunSnapshot.OpInfo> ops = new ArrayList<>();
        for (LoadOperation o : plan.operations()) {
            ops.add(new RunSnapshot.OpInfo(o.getName(), o.getExecFn(), o.getRatePerSec(), o.getExpectedDurationSec(),
                    o.getSlaPercentile(), o.getSlaThresholdMs(), o.getMaxErrorPct(), o.getFailureSource().name(), o.isEnabled()));
        }
        List<RunSnapshot.StepInfo> steps = plan.steps().stream()
                .map(st -> new RunSnapshot.StepInfo(st.no(), st.label(), st.levelPct(), st.rampSec(), st.holdSec())).toList();
        List<RunSnapshot.DataFileInfo> data = load.dataFiles(script.getId()).stream()
                .map(d -> new RunSnapshot.DataFileInfo(d.getPath(), d.getMode().name(), d.isHasHeader(), d.isConsumed())).toList();
        return new RunSnapshot(
                new RunSnapshot.ScriptInfo(script.getId(), script.getName(), v.getId(), v.getNumber(), v.getSha256(), v.getEntrypoint(),
                        v.getSource().name(), v.getCommitHash(), v.getGitBranch()),
                script.getEnv(), ops, steps, plan.gracefulStopSec(), plan.gracefulRampDownSec(), data,
                LoadCriteria.stopToMap(LoadCriteria.stop(script.getStopCriteria())),
                LoadCriteria.successToMap(LoadCriteria.success(script.getSuccessCriteria())), v.isUsesHelper(),
                List.of(), null, plan.warnings());
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
