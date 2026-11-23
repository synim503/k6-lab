package ru.k6lab.server.result;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import ru.k6lab.core.analysis.StepAnalysis;
import ru.k6lab.core.prom.PromQueries;
import ru.k6lab.server.metrics.MetricNamesService;
import ru.k6lab.server.metrics.PrometheusClient;
import ru.k6lab.server.run.Run;
import ru.k6lab.server.run.RunGenerator;
import ru.k6lab.server.run.RunOrchestrator;
import ru.k6lab.server.run.RunService;
import ru.k6lab.server.run.RunSnapshot;
import ru.k6lab.server.run.RunStep;
import ru.k6lab.server.load.LoadCriteria;
import ru.k6lab.server.settings.SettingsService;

import java.io.IOException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Итоги по ступеням после завершения run. Окно ступени: удержание без первых {@link #settleSec} секунд (выход на режим);
 * все значения - один instant-запрос на конце окна по диапазону окна (перцентили - только histogram_quantile по native
 * histograms, суммарно по всем генераторам; усреднения перцентилей нет). Результат сохраняется в run_result.
 */
@Service
public class StepResultService {

    private static final Logger log = LoggerFactory.getLogger(StepResultService.class);
    public static final int SETTLE_SEC = 20;
    /** Минимальная длина окна, при которой ступень вообще оценивается. */
    public static final int MIN_WINDOW_SEC = 10;
    /** Метрики приходят в Prometheus через remote write с задержкой: перед расчётом ждём. */
    static final long WAIT_BEFORE_COMPUTE_SEC = 5;
    /** Дольше этого не ждём, пока Prometheus получит последние сэмплы run. */
    static final long MAX_WAIT_FOR_METRICS_SEC = 90;

    private final RunService runService;
    private final PrometheusClient prom;
    private final SettingsService settings;
    private final MetricNamesService names;
    private final ChartVariableStore chartVariables;
    private final ChartTemplateStore chartTemplates;
    private final ru.k6lab.server.script.ScriptService scripts;
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final ExecutorService worker = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "step-results");
        t.setDaemon(true);
        return t;
    });

    public StepResultService(RunService runService, PrometheusClient prom, SettingsService settings, MetricNamesService names,
                             ChartVariableStore chartVariables, ChartTemplateStore chartTemplates,
                             ru.k6lab.server.script.ScriptService scripts,
                             JdbcTemplate jdbc, ObjectMapper mapper) {
        this.runService = runService;
        this.prom = prom;
        this.settings = settings;
        this.names = names;
        this.chartVariables = chartVariables;
        this.chartTemplates = chartTemplates;
        this.scripts = scripts;
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    @PreDestroy
    void shutdown() {
        worker.shutdownNow();
    }

    @EventListener
    public void onFinished(RunOrchestrator.RunFinished ev) {
        long runId = ev.runId();
        worker.submit(() -> {
            try {
                TimeUnit.SECONDS.sleep(WAIT_BEFORE_COMPUTE_SEC);
                awaitMetrics(runId);
                MDC.put("run_id", String.valueOf(runId));
                compute(runId);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (RuntimeException e) {
                log.error("Run {}: не удалось рассчитать итоги", runId, e);
            } finally {
                MDC.remove("run_id");
            }
        });
    }

    /**
     * Ждёт, пока Prometheus получит сэмплы до конца теста: задержка remote write бывает больше фиксированной паузы,
     * и тогда хвост окна ступени пуст, а интенсивность выходит заниженной. Не дольше {@link #MAX_WAIT_FOR_METRICS_SEC}.
     */
    private void awaitMetrics(long runId) throws InterruptedException {
        SettingsService.Prometheus p = settings.prometheus();
        if (!p.configured()) {
            return;
        }
        Run run = runService.get(runId);
        Instant finished = run.getFinishedAt();
        if (finished == null) {
            return;
        }
        long deadline = System.currentTimeMillis() + MAX_WAIT_FOR_METRICS_SEC * 1000;
        int empty = 0;
        while (System.currentTimeMillis() < deadline) {
            try {
                List<PrometheusClient.Sample> s = prom.query(p, PromQueries.lastSampleTime(names.names(), runId), Instant.now());
                // последний сэмпл не раньше конца теста (минус интервал отправки k6) - данные дошли
                if (!s.isEmpty() && Double.isFinite(s.get(0).value()) && s.get(0).value() >= finished.getEpochSecond() - settings.pushIntervalSec()) {
                    return;
                }
                // данных по run нет совсем (метрики не доходят): итоги покажут это сами, ждать 90 с незачем
                if (s.isEmpty() && ++empty >= 10) {
                    return;
                }
            } catch (IOException | RuntimeException e) {
                return;
            }
            TimeUnit.SECONDS.sleep(3);
        }
    }

    public Optional<ResultData> load(long runId) {
        List<String> rows = jdbc.queryForList("select data::text from run_result where run_id = ?", String.class, runId);
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        try {
            return Optional.of(mapper.readValue(rows.get(0), ResultData.class));
        } catch (JsonProcessingException e) {
            log.warn("Run {}: итоги не читаются: {}", runId, e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Возвращает сохранённые итоги либо пересчитывает их.
     * Если в скрипте/снимке добавлены или изменены кастомные метрики Prometheus, автоматически пересчитывает итоги.
     */
    public ResultData getOrCompute(long runId) {
        Run run = runService.get(runId);
        RunSnapshot snap = runService.snapshot(run);
        List<StepAnalysis.CustomCheck> expected = getEffectiveCustomChecks(run, snap);
        Optional<ResultData> opt = load(runId);
        if (opt.isPresent()) {
            ResultData d = opt.get();
            if (!needsCustomChecksRecompute(d, expected)) {
                return d;
            }
        }
        return compute(runId);
    }

    private static boolean needsCustomChecksRecompute(ResultData d, List<StepAnalysis.CustomCheck> expected) {
        if (expected.isEmpty()) {
            return false;
        }
        if (d.steps() == null || d.steps().isEmpty()) {
            return true;
        }
        List<String> expSig = expected.stream()
                .map(c -> c.name() + "|" + c.promql() + "|" + c.normalizedCmp() + "|" + c.threshold())
                .toList();
        for (ResultData.StepRow s : d.steps()) {
            List<ResultData.CustomMetricRow> actual = s.customChecks();
            if (actual.size() != expSig.size()) {
                return true;
            }
            for (int i = 0; i < actual.size(); i++) {
                ResultData.CustomMetricRow a = actual.get(i);
                String actSig = a.name() + "|" + a.promql() + "|" + a.cmp() + "|" + a.threshold();
                if (!expSig.get(i).equals(actSig)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Извлекает кастомные правила проверки метрик: объединяет правила из снимка запуска (snap.stopCriteria)
     * и текущей конфигурации скрипта (новые или обновлённые по имени правила из скрипта сразу подхватываются в отчёте).
     */
    public List<StepAnalysis.CustomCheck> getEffectiveCustomChecks(Run run, RunSnapshot snap) {
        Map<String, StepAnalysis.CustomCheck> merged = new java.util.LinkedHashMap<>();
        if (snap != null && snap.stopCriteria() != null) {
            for (StepAnalysis.CustomCheck cc : LoadCriteria.customChecks(snap.stopCriteria())) {
                merged.put(cc.name(), cc);
            }
        }
        if (run != null && run.getScriptId() != null && scripts != null) {
            try {
                ru.k6lab.server.script.Script s = scripts.get(run.getScriptId());
                if (s != null && s.getStopCriteria() != null) {
                    for (StepAnalysis.CustomCheck cc : LoadCriteria.customChecks(s.getStopCriteria())) {
                        merged.put(cc.name(), cc);
                    }
                }
            } catch (RuntimeException ignored) {
            }
        }
        return List.copyOf(merged.values());
    }

    /** Переменные для подстановки в PromQL кастомных метрик: переменные графиков скрипта, локальные переменные шаблонов графиков и env запуска. */
    public Map<String, String> varsForRun(Run run, RunSnapshot snap) {
        Map<String, String> vars = new java.util.LinkedHashMap<>();
        if (run != null && run.getScriptId() != null) {
            if (chartTemplates != null) {
                for (ChartTemplateStore.Template t : chartTemplates.list(run.getScriptId())) {
                    if (t.variables() != null) {
                        t.variables().forEach(vars::putIfAbsent);
                    }
                }
            }
            if (chartVariables != null) {
                vars.putAll(chartVariables.map(run.getScriptId()));
            }
        }
        if (snap != null && snap.env() != null) {
            snap.env().forEach(vars::putIfAbsent);
        }
        return vars;
    }

    /** Пересчёт итогов (кнопка «Пересчитать» и автоматически после завершения). */
    public ResultData compute(long runId) {
        Run run = runService.get(runId);
        RunSnapshot snap = runService.snapshot(run);
        ResultData data = build(run, snap);
        try {
            jdbc.update("insert into run_result (run_id, data, computed_at) values (?, ?::jsonb, now()) "
                    + "on conflict (run_id) do update set data = excluded.data, computed_at = now()", runId, mapper.writeValueAsString(data));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
        return data;
    }

    ResultData build(Run run, RunSnapshot snap) {
        Instant now = Instant.now();
        List<RunGenerator> gens = runService.generatorsOf(run.getId());
        List<ResultData.GenRow> genRows = genRows(run, gens);
        SettingsService.Prometheus p = settings.prometheus();
        if (!p.configured()) {
            return new ResultData(List.of(), false, "Prometheus не настроен: итоги по ступеням недоступны", now, genRows);
        }
        try {
            prom.ping(p);
        } catch (IOException e) {
            return new ResultData(List.of(), false, "Prometheus недоступен: " + e.getMessage(), now, genRows);
        }
        StepAnalysis.SuccessCriteria criteria = LoadCriteria.success(snap.successCriteria());
        List<StepAnalysis.CustomCheck> customRules = getEffectiveCustomChecks(run, snap);
        Map<String, String> vars = varsForRun(run, snap);
        Map<String, StepAnalysis.OpSla> sla = new HashMap<>();
        for (RunSnapshot.OpInfo o : snap.operations()) {
            if (o.enabled()) {
                sla.put(o.name(), new StepAnalysis.OpSla(o.name(), o.slaPercentile(), o.slaThresholdMs(), o.maxErrorPct()));
            }
        }
        PromQueries.Names n = names.names();
        List<ResultData.StepRow> rows = new ArrayList<>();
        List<StepAnalysis.StepVerdict> verdicts = new ArrayList<>();
        Map<Integer, Double> levels = new HashMap<>();
        Instant finished = run.getFinishedAt() == null ? now : run.getFinishedAt();
        String error = null;
        List<RunStep> runSteps = effectiveSteps(run, snap);
        for (RunStep step : runSteps) {
            levels.put(step.getStepNo(), step.getLevelPct());
            int holdSec = (int) Duration.between(step.getHoldStartAt(), step.getHoldEndAt()).getSeconds();
            Instant winStart = step.getHoldStartAt().plusSeconds(Math.min(SETTLE_SEC, holdSec / 4));
            Instant winEnd = step.getHoldEndAt().isAfter(finished) ? finished : step.getHoldEndAt();
            // шкала привязана к первой точке метрик, а она появляется позже реального начала нагрузки (до интервала отправки k6): на столько же
            // позже реального конца оказался бы конец окна. Хвост без нагрузки занижал бы среднюю интенсивность, поэтому окно короче на интервал
            long trim = run.getInitSec() == null ? 0 : settings.pushIntervalSec();
            if (Duration.between(winStart, winEnd).getSeconds() - trim >= 2L * MIN_WINDOW_SEC) {
                winEnd = winEnd.minusSeconds(trim);
            }
            long winSec = Duration.between(winStart, winEnd).getSeconds();
            String label = step.getLabel();
            if (winSec < MIN_WINDOW_SEC) {
                List<ResultData.CustomMetricRow> notRunCustom = customRules.stream()
                        .map(cr -> new ResultData.CustomMetricRow(
                                CustomChartService.title(cr.name(), vars), cr.promql(), cr.normalizedCmp(),
                                cr.threshold(), cr.unit(), cr.stepSla(), cr.autoStop(),
                                null, "", true, true))
                        .toList();
                rows.add(new ResultData.StepRow(step.getStepNo(), label, step.getLevelPct(), "NOT_RUN", step.getHoldStartAt(),
                        step.getHoldEndAt(), winStart, winEnd, true, null, false,
                        List.of("ступень не была выполнена (тест завершён раньше)"), List.of(), notRunCustom));
                continue;
            }
            try {
                boolean reliable = reliable(gens, step.getHoldStartAt(), winEnd);
                Double cpuMax = cpuMax(gens, winStart, winEnd);
                boolean overloaded = cpuMax != null && cpuMax > criteria.maxCpuPct();
                List<StepAnalysis.OpMetrics> metrics = opMetrics(snap, n, run, step.getLevelPct(), winSec, winEnd, p);
                List<StepAnalysis.CustomCheckResult> evaluatedCustom = evaluateCustomChecks(customRules, run.getId(), winStart, winEnd, winSec, vars, p);
                StepAnalysis.StepVerdict v = StepAnalysis.analyze(step.getStepNo(), reliable, overloaded, metrics, sla, criteria, evaluatedCustom);
                verdicts.add(v);
                List<ResultData.OpRow> ops = new ArrayList<>();
                for (StepAnalysis.OpMetrics m : metrics) {
                    StepAnalysis.OpSla s = sla.get(m.operation());
                    StepAnalysis.OpVerdict ov = v.operations().stream().filter(x -> x.operation().equals(m.operation())).findFirst().orElseThrow();
                    ops.add(new ResultData.OpRow(m.operation(), s.percentile(), s.thresholdMs(), s.maxErrorPct(), m.targetRatePerSec(),
                            m.actualRatePerSec(), m.p50(), m.p90(), m.p95(), m.p99(), m.errorPct(), m.dropped(), m.deviationPct(),
                            ov.ok(), ov.dataMissing()));
                }
                List<ResultData.CustomMetricRow> customRows = evaluatedCustom.stream()
                        .map(cr -> new ResultData.CustomMetricRow(
                                cr.check().name(), cr.check().promql(), cr.check().normalizedCmp(),
                                cr.check().threshold(), cr.check().unit(), cr.check().stepSla(), cr.check().autoStop(),
                                cr.value(), cr.worstSeries(), cr.ok(), cr.dataMissing()))
                        .toList();
                rows.add(new ResultData.StepRow(step.getStepNo(), label, step.getLevelPct(), v.status().name(), step.getHoldStartAt(),
                        step.getHoldEndAt(), winStart, winEnd, reliable, cpuMax, overloaded, v.reasons(), ops, customRows));
            } catch (IOException e) {
                error = "Ошибка запроса к Prometheus: " + e.getMessage();
                log.warn("Run {}: ступень {}: {}", run.getId(), step.getStepNo(), error);
                break;
            }
        }
        return new ResultData(rows, error == null, error, now, genRows);
    }

    /**
     * Ступени для расчёта итогов: если в БД одна сводная ступень (например, старый запуск до авторазбиения полок),
     * но в конфиге k6 есть ≥2 полок (поиск максимума), строим окна ступеней прямо из конфига.
     */
    private List<RunStep> effectiveSteps(Run run, RunSnapshot snap) {
        List<RunStep> saved = runService.stepsOf(run.getId());
        Instant base = run.getPlannedStartAt() != null ? run.getPlannedStartAt() : run.getStartedAt();
        if (saved.size() == 1 && run.getK6Config() != null && base != null) {
            int warmEnd = snap.warmup() != null ? snap.warmup().sec() : 0;
            var plans = ru.k6lab.core.k6config.StageStepExtractor.extract(run.getK6Config(), warmEnd);
            if (plans.size() >= 2) {
                List<RunStep> out = new ArrayList<>(plans.size());
                for (var w : ru.k6lab.core.profile.StepWindow.of(plans)) {
                    RunStep rs = new RunStep();
                    rs.setRunId(run.getId());
                    rs.setStepNo(w.no());
                    rs.setLabel(w.label());
                    rs.setLevelPct(w.levelPct());
                    rs.setRampStartAt(base.plusSeconds(w.rampStartSec()));
                    rs.setHoldStartAt(base.plusSeconds(w.holdStartSec()));
                    rs.setHoldEndAt(base.plusSeconds(w.holdEndSec()));
                    out.add(rs);
                }
                return out;
            }
        }
        return saved;
    }

    private List<StepAnalysis.OpMetrics> opMetrics(RunSnapshot snap, PromQueries.Names n, Run run, double stepLevel, long winSec,
                                                   Instant at, SettingsService.Prometheus p) throws IOException {
        long runId = run.getId();
        boolean helper = snap.usesHelper();
        Map<Integer, Map<String, Double>> pct = new HashMap<>();
        for (int q : new int[]{50, 90, 95, 99}) {
            pct.put(q, byScenario(prom.query(p, PromQueries.percentileMs(n, helper, q, runId, winSec), at)));
        }
        Map<String, Double> rate = byScenario(prom.query(p, PromQueries.ratePerSec(n, runId, winSec), at));
        Map<String, Double> dropped = byScenario(prom.query(p, PromQueries.dropped(n, runId, winSec), at));
        Map<String, Double> errHelper = helper ? byScenario(prom.query(p, PromQueries.errorPct(n, PromQueries.FailureSource.HELPER, runId, winSec), at)) : Map.of();
        boolean anyHttp = !helper && snap.operations().stream().anyMatch(o -> o.enabled() && !"CHECKS".equals(o.failureSource()));
        boolean anyChecks = !helper && snap.operations().stream().anyMatch(o -> o.enabled() && "CHECKS".equals(o.failureSource()));
        Map<String, Double> errHttp = anyHttp ? byScenario(prom.query(p, PromQueries.errorPct(n, PromQueries.FailureSource.HTTP, runId, winSec), at)) : Map.of();
        Map<String, Double> errChecks = anyChecks ? byScenario(prom.query(p, PromQueries.errorPct(n, PromQueries.FailureSource.CHECKS, runId, winSec), at)) : Map.of();

        List<StepAnalysis.OpMetrics> out = new ArrayList<>();
        for (RunSnapshot.OpInfo o : snap.operations()) {
            if (!o.enabled()) {
                continue;
            }
            double level = stepLevel;
            double target = o.ratePerSec() * level / 100.0;
            // план по конфигу k6 (стадии сценария): средняя плановая интенсивность за то же окно, что и факт. Без этого у ramping-arrival-rate
            // цели не было вовсе и интенсивность в итогах не сверялась
            if (run.getK6Config() != null && run.getPlannedStartAt() != null) {
                double toSec = Duration.between(run.getPlannedStartAt(), at).toMillis() / 1000.0;
                Double planned = ru.k6lab.core.k6config.ScenarioPlan.averageRate(run.getK6Config().path("scenarios").path(o.name()), toSec - winSec, toSec);
                if (planned != null) {
                    target = planned;
                }
            }
            Double err = helper ? errHelper.get(o.name()) : "CHECKS".equals(o.failureSource()) ? errChecks.get(o.name()) : errHttp.get(o.name());
            Double drop = dropped.get(o.name());
            // нет серии dropped = ни одной пропущенной итерации (k6 не создаёт счётчик до первого события)
            out.add(new StepAnalysis.OpMetrics(o.name(), target, rate.get(o.name()), pct.get(50).get(o.name()), pct.get(90).get(o.name()),
                    pct.get(95).get(o.name()), pct.get(99).get(o.name()), err, drop == null ? 0.0 : drop));
        }
        return out;
    }

    /** Ступень достоверна, если ни один генератор не был офлайн во время удержания (до конца окна). */
    static boolean reliable(List<RunGenerator> gens, Instant from, Instant to) {
        for (RunGenerator g : gens) {
            for (RunGenerator.Interval i : g.getOfflineIntervals()) {
                Instant end = i.to() == null ? Instant.MAX : i.to();
                if (i.from().isBefore(to) && end.isAfter(from)) {
                    return false;
                }
            }
        }
        return true;
    }

    private Double cpuMax(List<RunGenerator> gens, Instant from, Instant to) {
        Double max = null;
        for (RunGenerator g : gens) {
            if (g.getGeneratorId() == null) {
                continue;
            }
            Double v = jdbc.queryForObject("select max(cpu) from generator_metrics where generator_id = ? and ts between ? and ?",
                    Double.class, g.getGeneratorId(), Timestamp.from(from), Timestamp.from(to));
            if (v != null && (max == null || v > max)) {
                max = v;
            }
        }
        return max;
    }

    private List<ResultData.GenRow> genRows(Run run, List<RunGenerator> gens) {
        List<ResultData.GenRow> out = new ArrayList<>();
        Instant from = run.getStartedAt() != null ? run.getStartedAt() : run.getQueuedAt();
        Instant to = run.getFinishedAt() != null ? run.getFinishedAt() : Instant.now();
        for (RunGenerator g : gens) {
            JsonNode s = g.getSummary();
            Double cpu = g.getGeneratorId() == null || from == null ? null : cpuMax(List.of(g), from, to);
            out.add(new ResultData.GenRow(g.getGeneratorName(), g.getExitCode(), g.getStatus().name(), cpu,
                    metric(s, "iterations", "count"), metric(s, "dropped_iterations", "count"), metric(s, "http_reqs", "count"),
                    metric(s, "http_req_failed", "value")));
        }
        return out;
    }

    /** Значение из --summary-export: поля лежат либо прямо в метрике, либо в values (зависит от версии k6). */
    private static Double metric(JsonNode summary, String metric, String field) {
        if (summary == null) {
            return null;
        }
        JsonNode m = summary.path("metrics").path(metric);
        JsonNode v = m.has(field) ? m.get(field) : m.path("values").get(field);
        return v != null && v.isNumber() ? v.asDouble() : null;
    }

    private static Map<String, Double> byScenario(List<PrometheusClient.Sample> samples) {
        Map<String, Double> m = new HashMap<>();
        for (PrometheusClient.Sample s : samples) {
            String sc = s.labels().get("scenario");
            if (sc != null) {
                m.put(sc, s.value());
            }
        }
        return m;
    }

    /**
     * Вычисляет кастомные правила Prometheus на окне ступени [winStart..winEnd].
     * Для верхних порогов (LTE / LT) берётся максимальное (наихудшее) значение за окно ступени;
     * для нижних порогов (GTE / GT) — минимальное значение за окно ступени.
     */
    public List<StepAnalysis.CustomCheckResult> evaluateCustomChecks(List<StepAnalysis.CustomCheck> rules, long runId,
                                                                     Instant winStart, Instant winEnd, long winSec,
                                                                     Map<String, String> vars, SettingsService.Prometheus p) {
        if (rules == null || rules.isEmpty()) {
            return List.of();
        }
        List<StepAnalysis.CustomCheckResult> out = new ArrayList<>(rules.size());
        for (StepAnalysis.CustomCheck rule : rules) {
            String q = CustomChartService.expand(rule.promql(), runId, Math.max(15, winSec), vars);
            String renderedName = CustomChartService.title(rule.name(), vars);
            StepAnalysis.CustomCheck effectiveRule = new StepAnalysis.CustomCheck(
                    renderedName, rule.promql(), rule.normalizedCmp(), rule.threshold(), rule.unit(), rule.stepSla(), rule.autoStop());
            boolean upperBound = "LTE".equals(effectiveRule.normalizedCmp()) || "LT".equals(effectiveRule.normalizedCmp());
            Double worstVal = null;
            String worstLabel = "";
            String queryError = null;
            try {
                List<PrometheusClient.Sample> samples = prom.query(p, q, winEnd);
                for (PrometheusClient.Sample s : samples) {
                    if (!Double.isFinite(s.value())) {
                        continue;
                    }
                    if (worstVal == null || (upperBound ? s.value() > worstVal : s.value() < worstVal)) {
                        worstVal = s.value();
                        worstLabel = formatSampleLabels(s.labels());
                    }
                }
                if (worstVal == null && winStart != null && winEnd != null && winEnd.isAfter(winStart)) {
                    int push = Math.max(5, settings.pushIntervalSec());
                    long rangeWin = Math.max(30, 3L * push);
                    int step = (int) Math.max(push, Math.max(1, winSec / 60));
                    String qRange = CustomChartService.expand(rule.promql(), runId, rangeWin, vars);
                    for (PrometheusClient.Series sr : prom.rangeSeries(p, qRange, winStart, winEnd, step)) {
                        for (PrometheusClient.Point pt : sr.points()) {
                            if (!Double.isFinite(pt.value())) {
                                continue;
                            }
                            if (worstVal == null || (upperBound ? pt.value() > worstVal : pt.value() < worstVal)) {
                                worstVal = pt.value();
                                worstLabel = formatSampleLabels(sr.labels());
                            }
                        }
                    }
                }
            } catch (IOException | RuntimeException e) {
                queryError = e.getMessage() == null ? e.toString() : e.getMessage();
                log.warn("Run {}: ошибка запроса кастомной метрики «{}» ({}): {}", runId, rule.name(), q, queryError);
            }
            if (worstVal == null) {
                String errDesc = queryError != null ? "ошибка PromQL" : "";
                out.add(new StepAnalysis.CustomCheckResult(effectiveRule, null, errDesc, true, true));
            } else {
                out.add(new StepAnalysis.CustomCheckResult(effectiveRule, worstVal, worstLabel, effectiveRule.passes(worstVal), false));
            }
        }
        return out;
    }

    private static String formatSampleLabels(Map<String, String> labels) {
        if (labels == null || labels.isEmpty()) {
            return "";
        }
        for (String preferred : List.of("instance", "host", "node", "pod", "service", "job", "db")) {
            String v = labels.get(preferred);
            if (v != null && !v.isBlank()) {
                return v;
            }
        }
        var first = labels.entrySet().iterator().next();
        return first.getKey() + "=" + first.getValue();
    }
}
