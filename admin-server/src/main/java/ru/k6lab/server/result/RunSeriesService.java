package ru.k6lab.server.result;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import ru.k6lab.core.prom.PromQueries;
import ru.k6lab.server.metrics.MetricNamesService;
import ru.k6lab.server.metrics.PrometheusClient;
import ru.k6lab.server.run.Run;
import ru.k6lab.server.run.RunEventBroadcaster;
import ru.k6lab.server.run.RunSnapshot;
import ru.k6lab.server.settings.SettingsService;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Временные ряды run для графиков отчёта: от начала теста до конца, по операциям. Основной источник - range-запросы к
 * Prometheus (те же формулы, что у live-метрик); если Prometheus недоступен - точки, накопленные сервером во время теста.
 */
@Service
public class RunSeriesService {

    private static final Logger log = LoggerFactory.getLogger(RunSeriesService.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    /** Не больше стольких точек на ряд: график читаем, запрос лёгкий. */
    static final int MAX_POINTS = 150;

    /** Точка ряда: минуты от начала теста и значение. */
    public record Pt(double min, double v) {
    }

    /** Ряды одной операции: факт и план интенсивности, перцентиль SLA, ошибки + пороги из snapshot. */
    public record OpSeries(String op, int pctN, double slaMs, double maxErrPct, List<Pt> actual, List<Pt> plan, List<Pt> pct,
                           List<Pt> err, double warmupMin) {

        /** Без прогрева. */
        public OpSeries(String op, int pctN, double slaMs, double maxErrPct, List<Pt> actual, List<Pt> plan, List<Pt> pct, List<Pt> err) {
            this(op, pctN, slaMs, maxErrPct, actual, plan, pct, err, 0);
        }

        public boolean hasData() {
            return !actual.isEmpty() || !pct.isEmpty() || !err.isEmpty();
        }
    }

    private final PrometheusClient prom;
    private final SettingsService settings;
    private final MetricNamesService names;
    private final RunEventBroadcaster broadcaster;

    public RunSeriesService(PrometheusClient prom, SettingsService settings, MetricNamesService names, RunEventBroadcaster broadcaster) {
        this.prom = prom;
        this.settings = settings;
        this.names = names;
        this.broadcaster = broadcaster;
    }

    public List<OpSeries> series(Run run, RunSnapshot snap) {
        Instant start = run.getPlannedStartAt() != null ? run.getPlannedStartAt() : run.getStartedAt();
        if (start == null) {
            return List.of();
        }
        Instant end = run.getFinishedAt() != null ? run.getFinishedAt() : Instant.now();
        List<OpSeries> out = List.of();
        SettingsService.Prometheus p = settings.prometheus();
        if (p.configured()) {
            try {
                out = fromPrometheus(run.getId(), snap, p, start, end);
            } catch (IOException | RuntimeException e) {
                log.debug("Run {}: графики отчёта из Prometheus не получены: {}", run.getId(), e.toString());
            }
        }
        if (out.stream().noneMatch(OpSeries::hasData)) {
            out = fromHistory(run.getId(), snap, start);
        }
        return out.stream().anyMatch(OpSeries::hasData) ? out : List.of();
    }

    // ------------------------------------------------------------------ Prometheus

    private List<OpSeries> fromPrometheus(long runId, RunSnapshot snap, SettingsService.Prometheus p, Instant start, Instant end)
            throws IOException {
        PromQueries.Names n = names.names();
        long totalSec = Math.max(1, end.getEpochSecond() - start.getEpochSecond());
        int push = settings.pushIntervalSec();
        // окно: несколько интервалов отправки, чтобы в нём были сэмплы; шаг - так, чтобы точек было не больше MAX_POINTS
        long window = Math.max(15, 3L * push);
        int step = (int) Math.max(push, (long) Math.ceil(totalSec / (double) MAX_POINTS));
        boolean helper = snap.usesHelper();
        Map<Integer, Map<String, List<PrometheusClient.Point>>> pct = new HashMap<>();
        for (int q : snap.operations().stream().filter(RunSnapshot.OpInfo::enabled).map(RunSnapshot.OpInfo::slaPercentile).distinct().toList()) {
            pct.put(q, prom.rangeByScenario(p, PromQueries.percentileMs(n, helper, q, runId, window), start, end, step));
        }
        Map<String, List<PrometheusClient.Point>> rate = prom.rangeByScenario(p, PromQueries.ratePerSec(n, runId, window), start, end, step);
        Map<String, List<PrometheusClient.Point>> errHelper = helper
                ? prom.rangeByScenario(p, PromQueries.errorPct(n, PromQueries.FailureSource.HELPER, runId, window), start, end, step) : Map.of();
        Map<String, List<PrometheusClient.Point>> errHttp = Map.of();
        Map<String, List<PrometheusClient.Point>> errChecks = Map.of();
        if (!helper && snap.operations().stream().anyMatch(o -> o.enabled() && o.failureSource().equals("HTTP"))) {
            errHttp = prom.rangeByScenario(p, PromQueries.errorPct(n, PromQueries.FailureSource.HTTP, runId, window), start, end, step);
        }
        if (!helper && snap.operations().stream().anyMatch(o -> o.enabled() && o.failureSource().equals("CHECKS"))) {
            errChecks = prom.rangeByScenario(p, PromQueries.errorPct(n, PromQueries.FailureSource.CHECKS, runId, window), start, end, step);
        }
        long t0 = start.getEpochSecond();
        List<OpSeries> out = new ArrayList<>();
        for (RunSnapshot.OpInfo o : snap.operations()) {
            if (!o.enabled()) {
                continue;
            }
            var errSrc = helper ? errHelper : o.failureSource().equals("CHECKS") ? errChecks : errHttp;
            // в первые секунды окно захватывает время до старта: интенсивность делим на реально прошедшее время
            List<Pt> actual = new ArrayList<>();
            for (PrometheusClient.Point pt : withWarmup(rate, o.name())) {
                double elapsed = pt.ts() - t0;
                if (elapsed <= 0) {
                    continue;
                }
                actual.add(new Pt(elapsed / 60.0, elapsed < window ? pt.value() * window / elapsed : pt.value()));
            }
            out.add(new OpSeries(o.name(), o.slaPercentile(), o.slaThresholdMs(), o.maxErrorPct(), actual, planOf(snap, o),
                    toPts(withWarmup(pct.getOrDefault(o.slaPercentile(), Map.of()), o.name()), t0),
                    toPts(withWarmup(errSrc, o.name()), t0), warmupMin(snap)));
        }
        return out;
    }

    /** Минуты от начала теста, когда заканчивается прогрев; 0 - прогрева не было. */
    static double warmupMin(RunSnapshot snap) {
        return snap.warmup() == null ? 0 : snap.warmup().sec() / 60.0;
    }

    /** Точки основного сценария операции вместе с точками её спутника прогрева (он идёт раньше). */
    private static List<PrometheusClient.Point> withWarmup(Map<String, List<PrometheusClient.Point>> byScenario, String op) {
        List<PrometheusClient.Point> main = byScenario.get(op);
        List<PrometheusClient.Point> warm = byScenario.get(op + ru.k6lab.core.k6config.K6ConfigGenerator.WARMUP_SUFFIX);
        if (warm == null || warm.isEmpty()) {
            return main == null ? List.of() : main;
        }
        List<PrometheusClient.Point> all = new ArrayList<>(warm);
        if (main != null) {
            all.addAll(main);
        }
        all.sort(java.util.Comparator.comparingDouble(PrometheusClient.Point::ts));
        return all;
    }

    private static List<Pt> toPts(List<PrometheusClient.Point> src, long t0) {
        List<Pt> out = new ArrayList<>();
        if (src != null) {
            for (PrometheusClient.Point pt : src) {
                if (pt.ts() > t0) {
                    out.add(new Pt((pt.ts() - t0) / 60.0, pt.value()));
                }
            }
        }
        return out;
    }

    /** План интенсивности: линейный разгон с нуля до цели и удержание (по первой ступени snapshot). */
    static List<Pt> planOf(RunSnapshot snap, RunSnapshot.OpInfo o) {
        if (snap.steps().isEmpty()) {
            return List.of();
        }
        List<Pt> plan = new ArrayList<>();
        double t = 0;
        double prev = 0;
        plan.add(new Pt(0, 0));
        for (RunSnapshot.StepInfo s : snap.steps()) {
            double target = o.ratePerSec() * s.levelPct() / 100.0;
            if (s.rampSec() > 0 && snap.warmup() != null) {
                double warm = target * snap.warmup().pct() / 100.0;
                plan.set(0, new Pt(0, warm));
                t += s.rampSec();
                plan.add(new Pt(t / 60.0, warm));
                plan.add(new Pt(t / 60.0, target));
            } else if (s.rampSec() > 0) {
                t += s.rampSec();
                plan.add(new Pt(t / 60.0, target));
            } else {
                plan.add(new Pt(t / 60.0, target));
            }
            t += s.holdSec();
            plan.add(new Pt(t / 60.0, target));
            prev = target;
        }
        return plan;
    }

    // ------------------------------------------------------------------ точки, накопленные во время теста

    private List<OpSeries> fromHistory(long runId, RunSnapshot snap, Instant start) {
        List<String> raw = broadcaster.metricsHistory(runId);
        if (raw.isEmpty()) {
            return List.of();
        }
        Map<String, List<Pt>> actual = new HashMap<>();
        Map<String, List<Pt>> pct = new HashMap<>();
        Map<String, List<Pt>> err = new HashMap<>();
        for (String json : raw) {
            try {
                JsonNode m = MAPPER.readTree(json);
                long ts = m.path("dataTs").asLong(m.path("ts").asLong(0));
                double min = (ts / 1000.0 - start.getEpochSecond()) / 60.0;
                if (min <= 0) {
                    continue;
                }
                for (JsonNode r : m.path("rows")) {
                    String op = r.path("op").asText();
                    if (r.has("actual")) {
                        actual.computeIfAbsent(op, k -> new ArrayList<>()).add(new Pt(min, r.path("actual").asDouble()));
                    }
                    if (r.has("pct")) {
                        pct.computeIfAbsent(op, k -> new ArrayList<>()).add(new Pt(min, r.path("pct").asDouble()));
                    }
                    if (r.has("err")) {
                        err.computeIfAbsent(op, k -> new ArrayList<>()).add(new Pt(min, r.path("err").asDouble()));
                    }
                }
            } catch (IOException e) {
                // битая точка в истории - пропускаем
            }
        }
        List<OpSeries> out = new ArrayList<>();
        for (RunSnapshot.OpInfo o : snap.operations()) {
            if (o.enabled()) {
                out.add(new OpSeries(o.name(), o.slaPercentile(), o.slaThresholdMs(), o.maxErrorPct(),
                        actual.getOrDefault(o.name(), List.of()), planOf(snap, o), pct.getOrDefault(o.name(), List.of()),
                        err.getOrDefault(o.name(), List.of()), warmupMin(snap)));
            }
        }
        return out;
    }
}
