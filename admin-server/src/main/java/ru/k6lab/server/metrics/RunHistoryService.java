package ru.k6lab.server.metrics;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import ru.k6lab.core.k6config.ScenarioPlan;
import ru.k6lab.core.prom.PromQueries;
import ru.k6lab.server.run.Run;
import ru.k6lab.server.run.RunService;
import ru.k6lab.server.run.RunSnapshot;
import ru.k6lab.server.run.RunStep;
import ru.k6lab.server.settings.SettingsService;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;

/**
 * История метрик запуска из Prometheus: те же точки, что live-поток показывает во время теста (интенсивность, время ответа, ошибки,
 * dropped, VU), но по диапазону запроса. Нужна, когда в памяти сервера истории нет: запуск завершён давно или сервер перезапускался -
 * иначе вкладка «Метрики» старых запусков пуста, хотя данные в Prometheus есть (пока он их хранит).
 */
@Service
public class RunHistoryService {

    private static final Logger log = LoggerFactory.getLogger(RunHistoryService.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    /** Не больше стольких точек на график: хватает для картинки, запрос остаётся дешёвым. */
    static final int MAX_POINTS = 1500;
    /** Завершённый запуск кэшируется, когда прошло достаточно времени, чтобы Prometheus принял последние сэмплы. */
    private static final Duration SETTLED = Duration.ofMinutes(3);
    private static final int CACHE_RUNS = 20;

    private final PrometheusClient prom;
    private final SettingsService settings;
    private final RunMonitor monitor;
    private final RunService runService;
    private final Map<Long, List<String>> cache = new ConcurrentHashMap<>();

    public RunHistoryService(PrometheusClient prom, SettingsService settings, RunMonitor monitor, RunService runService) {
        this.prom = prom;
        this.settings = settings;
        this.monitor = monitor;
        this.runService = runService;
    }

    /** Точки метрик запуска в формате live-сообщений (JSON-строки по времени); пусто, если Prometheus не настроен или данных нет. */
    public List<String> rebuild(Run run) {
        SettingsService.Prometheus p = settings.prometheus();
        if (!p.configured() || run.getPlannedStartAt() == null) {
            return List.of();
        }
        boolean settled = run.getStatus().isTerminal() && run.getFinishedAt() != null
                && Duration.between(run.getFinishedAt(), Instant.now()).compareTo(SETTLED) > 0;
        if (settled && cache.containsKey(run.getId())) {
            return cache.get(run.getId());
        }
        List<String> out;
        try {
            out = build(run, p);
        } catch (IOException | RuntimeException e) {
            log.debug("История метрик run {} из Prometheus не восстановлена: {}", run.getId(), e.toString());
            return List.of();
        }
        if (settled && !out.isEmpty()) {
            if (cache.size() >= CACHE_RUNS) {
                cache.keySet().stream().min(Long::compare).ifPresent(cache::remove);
            }
            cache.put(run.getId(), out);
        }
        return out;
    }

    /**
     * Полная история для страницы: точки из памяти сервера, а если они начинаются заметно позже старта теста (сервер перезапускали
     * посреди теста), недостающее начало восстанавливается из Prometheus и стыкуется с ними.
     */
    public List<String> complete(Run run, List<String> memory) {
        if (memory.isEmpty()) {
            return withLatestCustomChecks(run, rebuild(run));
        }
        if (run.getPlannedStartAt() == null || !settings.prometheus().configured()) {
            return memory;
        }
        long first = tsOf(memory.get(0));
        long gapMs = first - run.getPlannedStartAt().toEpochMilli();
        if (first <= 0 || gapMs <= Math.max(60, 4L * settings.pushIntervalSec()) * 1000) {
            return withLatestCustomChecks(run, memory);
        }
        List<String> restored = rebuild(run);
        List<String> out = new ArrayList<>();
        for (String s : restored) {
            if (tsOf(s) < first) {
                out.add(s);
            }
        }
        if (out.isEmpty()) {
            return withLatestCustomChecks(run, memory);
        }
        out.addAll(memory);
        return withLatestCustomChecks(run, out);
    }

    /** Дополняет последнюю точку истории актуальными кастомными метриками Prometheus (в том числе добавленными в скрипт после завершения прогона). */
    private List<String> withLatestCustomChecks(Run run, List<String> points) {
        if (points == null || points.isEmpty()) {
            return List.of();
        }
        SettingsService.Prometheus p = settings.prometheus();
        if (!p.configured()) {
            return points;
        }
        try {
            ObjectNode last = (ObjectNode) MAPPER.readTree(points.get(points.size() - 1));
            RunSnapshot snap = runService.snapshot(run);
            Instant start = run.getPlannedStartAt() != null ? run.getPlannedStartAt() : run.getStartedAt();
            Instant end = run.getFinishedAt() != null ? run.getFinishedAt() : Instant.now();
            if (start != null && end != null && end.isAfter(start)) {
                long winSec = Math.max(15, end.getEpochSecond() - start.getEpochSecond());
                monitor.evaluateAndPutCustomChecks(last, run, snap, start, end, winSec, false, p);
                List<String> copy = new ArrayList<>(points);
                copy.set(copy.size() - 1, last.toString());
                return copy;
            }
        } catch (IOException | RuntimeException e) {
            log.debug("Кастомные метрики в истории run {}: {}", run.getId(), e.toString());
        }
        return points;
    }

    private static long tsOf(String json) {
        try {
            return MAPPER.readTree(json).path("ts").asLong(0);
        } catch (IOException e) {
            return 0;
        }
    }

    private List<String> build(Run run, SettingsService.Prometheus p) throws IOException {
        RunSnapshot snap = runService.snapshot(run);
        PromQueries.Names n = monitor.metricNames();
        Instant start = run.getPlannedStartAt();
        Instant end = run.getFinishedAt() != null ? run.getFinishedAt().plusSeconds(settings.pushIntervalSec()) : Instant.now();
        long totalSec = Math.max(1, end.getEpochSecond() - start.getEpochSecond());
        int push = settings.pushIntervalSec();
        long window = Math.max(15, 3L * push);
        int step = (int) Math.max(push, (long) Math.ceil(totalSec / (double) MAX_POINTS));
        long runId = run.getId();
        boolean helper = snap.usesHelper();
        List<RunSnapshot.OpInfo> ops = snap.operations().stream().filter(RunSnapshot.OpInfo::enabled).toList();

        Map<Integer, Map<String, List<PrometheusClient.Point>>> pct = new HashMap<>();
        for (int q : ops.stream().map(RunSnapshot.OpInfo::slaPercentile).distinct().toList()) {
            pct.put(q, prom.rangeByScenario(p, PromQueries.percentileMs(n, helper, q, runId, window), start, end, step));
        }
        Map<Integer, Map<String, List<PrometheusClient.Point>>> iter = new HashMap<>();
        for (int q : ops.stream().map(RunSnapshot.OpInfo::slaPercentile).distinct().toList()) {
            iter.put(q, prom.rangeByScenario(p, PromQueries.iterationPercentileMs(n, q, runId, window), start, end, step));
        }
        Map<String, List<PrometheusClient.Point>> rate = prom.rangeByScenario(p, PromQueries.ratePerSec(n, runId, window), start, end, step);
        Map<String, List<PrometheusClient.Point>> dropped = prom.rangeByScenario(p, PromQueries.dropped(n, runId, window), start, end, step);
        Map<String, List<PrometheusClient.Point>> errHelper = helper
                ? prom.rangeByScenario(p, PromQueries.errorPct(n, PromQueries.FailureSource.HELPER, runId, window), start, end, step) : Map.of();
        Map<String, List<PrometheusClient.Point>> errHttp = ops.stream().anyMatch(o -> !helper && !"CHECKS".equals(o.failureSource()))
                ? prom.rangeByScenario(p, PromQueries.errorPct(n, PromQueries.FailureSource.HTTP, runId, window), start, end, step) : Map.of();
        Map<String, List<PrometheusClient.Point>> errChecks = ops.stream().anyMatch(o -> !helper && "CHECKS".equals(o.failureSource()))
                ? prom.rangeByScenario(p, PromQueries.errorPct(n, PromQueries.FailureSource.CHECKS, runId, window), start, end, step) : Map.of();
        Map<Long, Double> vus = byTime(prom.rangePoints(p, PromQueries.totalVus(n, runId), start, end, step));
        Map<Long, Double> vusMax = byTime(prom.rangePoints(p, PromQueries.totalVusMax(n, runId), start, end, step));

        // моменты времени - все, где есть хоть какие-то данные
        TreeSet<Long> times = new TreeSet<>();
        collect(times, rate);
        pct.values().forEach(m -> collect(times, m));
        collect(times, errHttp);
        collect(times, errChecks);
        collect(times, errHelper);
        times.addAll(vus.keySet());
        if (times.isEmpty()) {
            return List.of();
        }
        Map<String, Map<Long, Double>> rateAt = index(rate), droppedAt = index(dropped), errAt = new HashMap<>();
        Map<String, Map<Long, Double>> pctAt = new HashMap<>(), iterAt = new HashMap<>();
        for (RunSnapshot.OpInfo o : ops) {
            Map<String, List<PrometheusClient.Point>> errSrc = helper ? errHelper : "CHECKS".equals(o.failureSource()) ? errChecks : errHttp;
            errAt.put(o.name(), index(errSrc).getOrDefault(o.name(), Map.of()));
            pctAt.put(o.name(), index(pct.getOrDefault(o.slaPercentile(), Map.of())).getOrDefault(o.name(), Map.of()));
            iterAt.put(o.name(), index(iter.getOrDefault(o.slaPercentile(), Map.of())).getOrDefault(o.name(), Map.of()));
        }
        List<RunStep> steps = runService.stepsOf(runId);
        Instant holdStart = steps.isEmpty() ? start : steps.get(0).getHoldStartAt();
        JsonNode cfg = run.getK6Config();
        long t0 = start.getEpochSecond();

        List<String> out = new ArrayList<>();
        for (long t : times) {
            double elapsed = t - t0;
            if (elapsed <= 0) {
                continue;
            }
            ObjectNode m = MAPPER.createObjectNode();
            m.put("ts", t * 1000);
            m.put("dataTs", t * 1000);
            m.put("step", 1);
            m.put("phase", snap.warmup() != null && t < holdStart.getEpochSecond() ? "warmup" : "hold");
            m.put("elapsedSec", (long) elapsed);
            m.put("windowSec", window);
            RunMonitor.putD(m, "vus", vus.get(t));
            RunMonitor.putD(m, "vusMax", vusMax.get(t));
            ArrayNode rows = m.putArray("rows");
            for (RunSnapshot.OpInfo o : ops) {
                ObjectNode r = rows.addObject();
                r.put("op", o.name());
                Double planned = cfg == null ? null : ScenarioPlan.ratePerSec(cfg.path("scenarios").path(o.name()), elapsed);
                r.put("target", planned != null ? planned : o.ratePerSec());
                Double actual = rateAt.getOrDefault(o.name(), Map.of()).get(t);
                // в первые секунды окно захватывает время до старта: интенсивность делим на реально прошедшее время
                RunMonitor.putD(r, "actual", actual == null ? null : elapsed < window ? actual * window / elapsed : actual);
                RunMonitor.putD(r, "pct", pctAt.get(o.name()).get(t));
                RunMonitor.putD(r, "iterPct", iterAt.get(o.name()).get(t));
                r.put("pctN", o.slaPercentile());
                r.put("sla", o.slaThresholdMs());
                RunMonitor.putD(r, "err", errAt.get(o.name()).get(t));
                r.put("maxErr", o.maxErrorPct());
                RunMonitor.putD(r, "dropped", droppedAt.getOrDefault(o.name(), Map.of()).get(t));
                r.put("violation", false);
                r.putArray("reasons");
            }
            out.add(m.toString());
        }
        addWholeTest(out, run, snap, steps, holdStart, p, end);
        return out;
    }

    /** Таблица «Весь тест» для последней точки: те же показатели за период от конца прогрева до конца теста. */
    private void addWholeTest(List<String> out, Run run, RunSnapshot snap, List<RunStep> steps, Instant holdStart,
                              SettingsService.Prometheus p, Instant end) {
        if (out.isEmpty() || steps.isEmpty()) {
            return;
        }
        Instant at = run.getFinishedAt() != null ? run.getFinishedAt() : end;
        long whole = at.getEpochSecond() - holdStart.getEpochSecond();
        if (whole < monitor.minWindowSec()) {
            return;
        }
        try {
            List<RunMonitor.LiveRow> rows = monitor.liveRows(snap, steps.get(0), steps.get(0).getLevelPct(), run.getId(), whole, at, p,
                    run.getK6Config(), run.getPlannedStartAt(), true);
            ObjectNode last = (ObjectNode) MAPPER.readTree(out.get(out.size() - 1));
            last.put("wholeSec", whole);
            RunMonitor.putRows(last.putArray("rowsAll"), rows);
            out.set(out.size() - 1, last.toString());
        } catch (IOException | RuntimeException e) {
            log.debug("Показатели за весь тест run {}: {}", run.getId(), e.toString());
        }
    }

    private static void collect(TreeSet<Long> times, Map<String, List<PrometheusClient.Point>> byScenario) {
        byScenario.values().forEach(l -> l.forEach(pt -> times.add((long) pt.ts())));
    }

    private static Map<String, Map<Long, Double>> index(Map<String, List<PrometheusClient.Point>> byScenario) {
        Map<String, Map<Long, Double>> out = new HashMap<>();
        byScenario.forEach((op, pts) -> out.put(op, byTime(pts)));
        return out;
    }

    private static Map<Long, Double> byTime(List<PrometheusClient.Point> pts) {
        Map<Long, Double> m = new HashMap<>();
        pts.forEach(pt -> m.put((long) pt.ts(), pt.value()));
        return m;
    }
}
