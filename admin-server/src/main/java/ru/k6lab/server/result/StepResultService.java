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
import ru.k6lab.core.analysis.MaxFinder;
import ru.k6lab.core.analysis.StepAnalysis;
import ru.k6lab.core.profile.ProfileType;
import ru.k6lab.core.prom.PromQueries;
import ru.k6lab.core.util.PromQlTemplate;
import ru.k6lab.server.metrics.MetricNamesService;
import ru.k6lab.server.metrics.PrometheusClient;
import ru.k6lab.server.run.Run;
import ru.k6lab.server.run.RunGenerator;
import ru.k6lab.server.run.RunOrchestrator;
import ru.k6lab.server.run.RunService;
import ru.k6lab.server.run.RunSnapshot;
import ru.k6lab.server.run.RunStep;
import ru.k6lab.server.scenario.ScenarioCriteria;
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
    static final long WAIT_BEFORE_COMPUTE_SEC = 12;

    private final RunService runService;
    private final PrometheusClient prom;
    private final SettingsService settings;
    private final MetricNamesService names;
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final ExecutorService worker = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "step-results");
        t.setDaemon(true);
        return t;
    });

    public StepResultService(RunService runService, PrometheusClient prom, SettingsService settings, MetricNamesService names,
                             JdbcTemplate jdbc, ObjectMapper mapper) {
        this.runService = runService;
        this.prom = prom;
        this.settings = settings;
        this.names = names;
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
            return new ResultData(List.of(), null, false, "Prometheus не настроен: итоги по ступеням недоступны", now, genRows);
        }
        try {
            prom.ping(p);
        } catch (IOException e) {
            return new ResultData(List.of(), null, false, "Prometheus недоступен: " + e.getMessage(), now, genRows);
        }
        StepAnalysis.SuccessCriteria criteria = ScenarioCriteria.success(snap.successCriteria());
        Map<String, StepAnalysis.OpSla> sla = new HashMap<>();
        for (RunSnapshot.OpInfo o : snap.model().operations()) {
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
        for (RunStep step : runService.stepsOf(run.getId())) {
            levels.put(step.getStepNo(), step.getLevelPct());
            int holdSec = (int) Duration.between(step.getHoldStartAt(), step.getHoldEndAt()).getSeconds();
            Instant winStart = step.getHoldStartAt().plusSeconds(Math.min(SETTLE_SEC, holdSec / 4));
            Instant winEnd = step.getHoldEndAt().isAfter(finished) ? finished : step.getHoldEndAt();
            long winSec = Duration.between(winStart, winEnd).getSeconds();
            String label = step.getLabel();
            if (winSec < MIN_WINDOW_SEC) {
                rows.add(new ResultData.StepRow(step.getStepNo(), label, step.getLevelPct(), "NOT_RUN", step.getHoldStartAt(),
                        step.getHoldEndAt(), winStart, winEnd, true, null, false,
                        List.of("ступень не была выполнена (тест завершён раньше)"), List.of(), List.of()));
                continue;
            }
            try {
                boolean reliable = reliable(gens, step.getHoldStartAt(), winEnd);
                Double cpuMax = cpuMax(gens, winStart, winEnd);
                boolean overloaded = cpuMax != null && cpuMax > criteria.maxCpuPct();
                List<StepAnalysis.OpMetrics> metrics = opMetrics(snap, n, run.getId(), step.getLevelPct(), winSec, winEnd, p);
                StepAnalysis.StepVerdict v = StepAnalysis.analyze(step.getStepNo(), reliable, overloaded, metrics, sla, criteria);
                verdicts.add(v);
                List<ResultData.OpRow> ops = new ArrayList<>();
                for (StepAnalysis.OpMetrics m : metrics) {
                    StepAnalysis.OpSla s = sla.get(m.operation());
                    StepAnalysis.OpVerdict ov = v.operations().stream().filter(x -> x.operation().equals(m.operation())).findFirst().orElseThrow();
                    ops.add(new ResultData.OpRow(m.operation(), s.percentile(), s.thresholdMs(), s.maxErrorPct(), m.targetRatePerSec(),
                            m.actualRatePerSec(), m.p50(), m.p90(), m.p95(), m.p99(), m.errorPct(), m.dropped(), m.deviationPct(),
                            ov.ok(), ov.dataMissing()));
                }
                rows.add(new ResultData.StepRow(step.getStepNo(), label, step.getLevelPct(), v.status().name(), step.getHoldStartAt(),
                        step.getHoldEndAt(), winStart, winEnd, reliable, cpuMax, overloaded, v.reasons(), ops,
                        sutRows(snap, winStart, winEnd, p)));
            } catch (IOException e) {
                error = "Ошибка запроса к Prometheus: " + e.getMessage();
                log.warn("Run {}: ступень {}: {}", run.getId(), step.getStepNo(), error);
                break;
            }
        }
        MaxFinder.Result mf = MaxFinder.find(verdicts);
        boolean searchesMax = isSearchProfile(snap);
        ResultData.MaxRow max = searchesMax && error == null
                ? new ResultData.MaxRow(mf.kind().name(), mf.candidateStep(), mf.firstProblemStep(), mf.note(),
                        mf.candidateStep() == null ? null : levels.get(mf.candidateStep()))
                : null;
        return new ResultData(rows, max, error == null, error, now, genRows);
    }

    private static boolean isSearchProfile(RunSnapshot snap) {
        try {
            ProfileType t = ProfileType.valueOf(snap.profile().type());
            return t == ProfileType.MAX_SEARCH || t == ProfileType.STRESS || t == ProfileType.CUSTOM || t == ProfileType.TEMPLATE;
        } catch (IllegalArgumentException e) {
            return true;
        }
    }

    private List<StepAnalysis.OpMetrics> opMetrics(RunSnapshot snap, PromQueries.Names n, long runId, double stepLevel, long winSec,
                                                   Instant at, SettingsService.Prometheus p) throws IOException {
        boolean helper = snap.usesHelper();
        Map<Integer, Map<String, Double>> pct = new HashMap<>();
        for (int q : new int[]{50, 90, 95, 99}) {
            pct.put(q, byScenario(prom.query(p, PromQueries.percentileMs(n, helper, q, runId, winSec), at)));
        }
        Map<String, Double> rate = byScenario(prom.query(p, PromQueries.ratePerSec(n, runId, winSec), at));
        Map<String, Double> dropped = byScenario(prom.query(p, PromQueries.dropped(n, runId, winSec), at));
        Map<String, Double> errHelper = helper ? byScenario(prom.query(p, PromQueries.errorPct(n, PromQueries.FailureSource.HELPER, runId, winSec), at)) : Map.of();
        boolean anyHttp = !helper && snap.model().operations().stream().anyMatch(o -> o.enabled() && !"CHECKS".equals(o.failureSource()));
        boolean anyChecks = !helper && snap.model().operations().stream().anyMatch(o -> o.enabled() && "CHECKS".equals(o.failureSource()));
        Map<String, Double> errHttp = anyHttp ? byScenario(prom.query(p, PromQueries.errorPct(n, PromQueries.FailureSource.HTTP, runId, winSec), at)) : Map.of();
        Map<String, Double> errChecks = anyChecks ? byScenario(prom.query(p, PromQueries.errorPct(n, PromQueries.FailureSource.CHECKS, runId, winSec), at)) : Map.of();

        List<StepAnalysis.OpMetrics> out = new ArrayList<>();
        for (RunSnapshot.OpInfo o : snap.model().operations()) {
            if (!o.enabled()) {
                continue;
            }
            double level = o.fixedLevelPct() != null ? o.fixedLevelPct() : stepLevel;
            double target = o.ratePerSec() * level / 100.0;
            Double err = helper ? errHelper.get(o.name()) : "CHECKS".equals(o.failureSource()) ? errChecks.get(o.name()) : errHttp.get(o.name());
            Double drop = dropped.get(o.name());
            // нет серии dropped = ни одной пропущенной итерации (k6 не создаёт счётчик до первого события)
            out.add(new StepAnalysis.OpMetrics(o.name(), target, rate.get(o.name()), pct.get(50).get(o.name()), pct.get(90).get(o.name()),
                    pct.get(95).get(o.name()), pct.get(99).get(o.name()), err, drop == null ? 0.0 : drop));
        }
        return out;
    }

    private List<ResultData.SutRow> sutRows(RunSnapshot snap, Instant from, Instant to, SettingsService.Prometheus p) {
        if (snap.sut() == null) {
            return List.of();
        }
        List<ResultData.SutRow> out = new ArrayList<>();
        long range = Math.max(1, Duration.between(from, to).getSeconds());
        for (RunSnapshot.SutMetricInfo m : snap.sut().metrics()) {
            try {
                String q = PromQlTemplate.render(m.promql(), from, to);
                List<Double> vals = prom.queryRange(p, q, from, to, (int) Math.max(5, range / 60));
                if (vals.isEmpty()) {
                    out.add(new ResultData.SutRow(m.name(), m.unit(), null, null, null, false));
                    continue;
                }
                double avg = vals.stream().mapToDouble(Double::doubleValue).average().orElse(0);
                double max = vals.stream().mapToDouble(Double::doubleValue).max().orElse(0);
                double last = vals.get(vals.size() - 1);
                double basis = switch (m.aggregation() == null ? "AVG" : m.aggregation().toUpperCase()) {
                    case "MAX" -> max;
                    case "LAST" -> last;
                    default -> avg;
                };
                boolean warn = m.warnThreshold() != null && ("BELOW".equals(m.warnDirection()) ? basis < m.warnThreshold() : basis > m.warnThreshold());
                out.add(new ResultData.SutRow(m.name(), m.unit(), avg, max, last, warn));
            } catch (IOException | RuntimeException e) {
                log.debug("SUT-метрика {}: {}", m.name(), e.getMessage());
                out.add(new ResultData.SutRow(m.name(), m.unit(), null, null, null, false));
            }
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
}
