package ru.k6lab.server.metrics;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import ru.k6lab.core.analysis.StopCriteriaEvaluator;
import ru.k6lab.core.prom.PromQueries;
import ru.k6lab.core.runstate.RunStatus;
import ru.k6lab.server.run.Run;
import ru.k6lab.server.run.RunEventBroadcaster;
import ru.k6lab.server.run.RunLogService;
import ru.k6lab.server.run.RunOrchestrator;
import ru.k6lab.server.run.RunService;
import ru.k6lab.server.run.RunSnapshot;
import ru.k6lab.server.run.RunStep;
import ru.k6lab.server.load.LoadCriteria;
import ru.k6lab.server.settings.SettingsService;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Live-метрики активных run из Prometheus (раз в 5 с, один опрос на run независимо от числа зрителей) и серверные
 * критерии остановки (раз в 15 с, только на участках удержания). Все перцентили - из native histograms через
 * histogram_quantile за окно; abortOnFail k6 не используется.
 */
@Component
public class RunMonitor {

    private static final Logger log = LoggerFactory.getLogger(RunMonitor.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    /** Задержка после разгона: первые секунды удержания не оцениваются (система выходит на режим). */
    public static final int SETTLE_SEC = 20;
    public static final int MIN_WINDOW_SEC = 15;
    /** Задержка метрик, если измерить её не удалось (данных ещё нет): интервал отправки k6 + приём Prometheus. */
    static final long DEFAULT_LAG_SEC = 15;
    static final long MAX_LAG_SEC = 120;

    private static final class State {
        StopCriteriaEvaluator evaluator;
        int tick;
        int lastStep = -1;
    }

    /** Строка live-таблицы по операции. */
    public record LiveRow(String operation, double targetRatePerSec, Double actualRatePerSec, Double percentileMs,
                          int slaPercentile, double slaThresholdMs, Double errorPct, double maxErrorPct, Double dropped,
                          boolean violation, List<String> reasons) {
    }

    private final RunService runService;
    private final RunOrchestrator orchestrator;
    private final PrometheusClient prom;
    private final SettingsService settings;
    private final MetricNamesService names;
    private final RunEventBroadcaster broadcaster;
    private final RunLogService logs;
    private final Map<Long, State> states = new ConcurrentHashMap<>();

    public RunMonitor(RunService runService, RunOrchestrator orchestrator, PrometheusClient prom, SettingsService settings,
                      MetricNamesService names, RunEventBroadcaster broadcaster, RunLogService logs) {
        this.runService = runService;
        this.orchestrator = orchestrator;
        this.prom = prom;
        this.settings = settings;
        this.names = names;
        this.broadcaster = broadcaster;
        this.logs = logs;
    }

    @Scheduled(fixedDelay = 5000, initialDelay = 20_000)
    public void tick() {
        SettingsService.Prometheus p = settings.prometheus();
        if (!p.configured()) {
            return;
        }
        for (Run run : runService.activeRuns()) {
            if (run.getStatus() != RunStatus.RUNNING || run.getPlannedStartAt() == null) {
                continue;
            }
            MDC.put("run_id", String.valueOf(run.getId()));
            try {
                monitor(run, p);
            } catch (IOException | RuntimeException e) {
                log.debug("Live-метрики run {}: {}", run.getId(), e.toString());
            } finally {
                MDC.remove("run_id");
            }
        }
        states.keySet().removeIf(id -> !orchestrator.isActive(id));
    }

    private void monitor(Run run, SettingsService.Prometheus p) throws IOException {
        RunSnapshot snap = runService.snapshot(run);
        Instant wall = Instant.now();
        // метрики приходят в Prometheus с задержкой: всё считаем на момент последних полученных данных, а не на «сейчас»,
        // иначе окно «удержания» захватывает ещё разгон и интенсивность выглядит заниженной
        long lag = measureLag(run, p, wall);
        Instant now = wall.minusSeconds(lag);
        List<RunStep> steps = runService.stepsOf(run.getId());
        RunStep current = null;
        for (RunStep s : steps) {
            if (!now.isBefore(s.getRampStartAt()) && now.isBefore(s.getHoldEndAt())) {
                current = s;
            }
        }
        long elapsed = Math.max(0, now.getEpochSecond() - run.getPlannedStartAt().getEpochSecond());
        boolean hold = current != null && !now.isBefore(current.getHoldStartAt());
        long intoHold = hold ? now.getEpochSecond() - current.getHoldStartAt().getEpochSecond() : 0;
        // в окне rate/increase должно быть минимум два-три сэмпла: при редкой отправке метрик окно не может быть короче
        long minWindow = minWindowSec();
        long window = Math.max(minWindow, Math.min(Math.max(60, minWindow), hold ? Math.max(minWindow, intoHold) : Math.max(minWindow, elapsed)));

        State st = states.computeIfAbsent(run.getId(), k -> new State());
        if (st.evaluator == null) {
            st.evaluator = new StopCriteriaEvaluator(LoadCriteria.stop(snap.stopCriteria()));
        }
        int stepNo = current == null ? 0 : current.getStepNo();
        if (stepNo != st.lastStep) {
            st.lastStep = stepNo;
            st.evaluator.reset(); // новая ступень - счётчики нарушений начинаем заново
        }

        // сверку с целью делаем, только когда окно целиком лежит после стабилизации ступени (как и в итогах по ступеням):
        // иначе в окно попадает хвост разгона/переключения уровня и факт выходит заниженным
        boolean settled = hold && intoHold - SETTLE_SEC >= window;
        List<LiveRow> rows = liveRows(snap, current, settled ? current.getLevelPct() : null, run.getId(), window, now, p);
        ObjectNode json = MAPPER.createObjectNode();
        json.put("ts", wall.toEpochMilli());
        json.put("dataTs", now.toEpochMilli());
        json.put("lagSec", lag);
        json.put("step", stepNo);
        json.put("phase", current == null ? "" : hold ? "hold" : (snap.warmup() != null ? "warmup" : "ramp"));
        json.put("elapsedSec", elapsed);
        json.put("windowSec", window);
        ArrayNode ra = json.putArray("rows");
        for (LiveRow r : rows) {
            ObjectNode o = ra.addObject();
            o.put("op", r.operation());
            o.put("target", r.targetRatePerSec());
            putD(o, "actual", r.actualRatePerSec());
            putD(o, "pct", r.percentileMs());
            o.put("pctN", r.slaPercentile());
            o.put("sla", r.slaThresholdMs());
            putD(o, "err", r.errorPct());
            o.put("maxErr", r.maxErrorPct());
            putD(o, "dropped", r.dropped());
            o.put("violation", r.violation());
            ArrayNode rs = o.putArray("reasons");
            r.reasons().forEach(rs::add);
        }
        broadcaster.publishMetrics(run.getId(), json.toString());

        // серверные критерии остановки: раз в 15 с, только на удержании после settle
        st.tick++;
        StopCriteriaEvaluator.Config cfg = st.evaluator.config();
        if (cfg.anyEnabled() && st.tick % 3 == 0 && hold && intoHold - SETTLE_SEC >= minWindow && !run.getStatus().isTerminal()) {
            long w = Math.max(minWindow, Math.min(cfg.windowSec(), intoHold - SETTLE_SEC));
            List<LiveRow> evalRows = w == window ? rows : liveRows(snap, current, current.getLevelPct(), run.getId(), w, now, p);
            List<StopCriteriaEvaluator.Sample> samples = new ArrayList<>();
            for (LiveRow r : evalRows) {
                samples.add(new StopCriteriaEvaluator.Sample(r.operation(), r.percentileMs(), r.slaThresholdMs(), r.errorPct(),
                        r.maxErrorPct(), r.actualRatePerSec(), r.targetRatePerSec(), r.dropped()));
            }
            Optional<String> reason = st.evaluator.evaluate(samples);
            if (reason.isPresent()) {
                String text = "критерий остановки: " + reason.get() + " (ступень " + stepNo + ")";
                log.warn("Run {}: {}", run.getId(), text);
                logs.addSystem(run.getId(), "ERROR", "Сработал " + text);
                try {
                    orchestrator.stop(run.getId(), text);
                } catch (IllegalStateException e) {
                    log.debug("Остановка по критерию не выполнена: {}", e.getMessage());
                }
            }
        }
    }

    /** Минимальное окно live-расчётов: не меньше 15 с и не меньше трёх интервалов отправки метрик. */
    public long minWindowSec() {
        return Math.max(MIN_WINDOW_SEC, 3L * settings.pushIntervalSec());
    }

    /** Сколько секунд назад Prometheus получил последний сэмпл run; нет данных - значение по умолчанию. */
    private long measureLag(Run run, SettingsService.Prometheus p, Instant wall) {
        try {
            List<PrometheusClient.Sample> s = prom.query(p, PromQueries.lastSampleTime(names.names(), run.getId()), wall);
            if (!s.isEmpty() && Double.isFinite(s.get(0).value())) {
                long lag = Math.round(wall.getEpochSecond() - s.get(0).value());
                return Math.max(0, Math.min(MAX_LAG_SEC, lag));
            }
        } catch (IOException | RuntimeException e) {
            log.debug("Задержка метрик run {}: {}", run.getId(), e.toString());
        }
        return Math.max(DEFAULT_LAG_SEC, settings.pushIntervalSec() + 10L);
    }

    /** Доля планового уровня в момент at: на разгоне нагрузка растёт линейно с нуля, а не стоит на целевом значении. */
    static double rampFraction(RunStep step, Instant at) {
        if (step == null || step.getStepNo() != 1 || !step.getHoldStartAt().isAfter(step.getRampStartAt())) {
            return 1;
        }
        double total = java.time.Duration.between(step.getRampStartAt(), step.getHoldStartAt()).toMillis();
        double done = java.time.Duration.between(step.getRampStartAt(), at).toMillis();
        return Math.max(0, Math.min(1, done / total));
    }

    /** Значения по операциям за окно, заканчивающееся at. Перцентили - histogram_quantile по native histograms. */
    List<LiveRow> liveRows(RunSnapshot snap, RunStep step, Double levelOrNull, long runId, long windowSec, Instant at,
                           SettingsService.Prometheus p) throws IOException {
        PromQueries.Names n = names.names();
        boolean helper = snap.usesHelper();
        Map<Integer, Map<String, Double>> pct = new HashMap<>();
        for (int q : snap.operations().stream().filter(RunSnapshot.OpInfo::enabled).map(RunSnapshot.OpInfo::slaPercentile).distinct().toList()) {
            pct.put(q, byScenario(prom.query(p, PromQueries.percentileMs(n, helper, q, runId, windowSec), at)));
        }
        Map<String, Double> rate = byScenario(prom.query(p, PromQueries.ratePerSec(n, runId, windowSec), at));
        Map<String, Double> dropped = byScenario(prom.query(p, PromQueries.dropped(n, runId, windowSec), at));
        Map<String, Double> errHelper = helper ? byScenario(prom.query(p, PromQueries.errorPct(n, PromQueries.FailureSource.HELPER, runId, windowSec), at)) : Map.of();
        Map<String, Double> errHttp = Map.of();
        Map<String, Double> errChecks = Map.of();
        boolean anyHttp = !helper && snap.operations().stream().anyMatch(o -> o.enabled() && o.failureSource().equals("HTTP"));
        boolean anyChecks = !helper && snap.operations().stream().anyMatch(o -> o.enabled() && o.failureSource().equals("CHECKS"));
        if (anyHttp) {
            errHttp = byScenario(prom.query(p, PromQueries.errorPct(n, PromQueries.FailureSource.HTTP, runId, windowSec), at));
        }
        if (anyChecks) {
            errChecks = byScenario(prom.query(p, PromQueries.errorPct(n, PromQueries.FailureSource.CHECKS, runId, windowSec), at));
        }

        // прогрев: пока он идёт, показываем факт со сценария-спутника, цель - доля нагрузки, нарушения не отмечаем
        boolean warm = snap.warmup() != null && step != null && at.isBefore(step.getHoldStartAt());
        List<LiveRow> rows = new ArrayList<>();
        for (RunSnapshot.OpInfo o : snap.operations()) {
            if (!o.enabled()) {
                continue;
            }
            double level = levelOrNull != null ? levelOrNull : step == null ? 0 : step.getLevelPct();
            double target = o.ratePerSec() * level / 100.0 * (levelOrNull != null ? 1 : warm ? snap.warmup().pct() / 100.0 : rampFraction(step, at));
            Double actual = pick(rate, o.name());
            Double p95 = pick(pct.getOrDefault(o.slaPercentile(), Map.of()), o.name());
            Double err = pick(helper ? errHelper : o.failureSource().equals("CHECKS") ? errChecks : errHttp, o.name());
            Double drop = pick(dropped, o.name());
            List<String> reasons = new ArrayList<>();
            if (!warm) {
                if (p95 != null && p95 > o.slaThresholdMs()) {
                    reasons.add("p" + o.slaPercentile() + " > SLA");
                }
                if (err != null && err > o.maxErrorPct()) {
                    reasons.add("ошибки > порога");
                }
                if (actual != null && target > 0 && levelOrNull != null && actual < target * 0.95) {
                    reasons.add("интенсивность < 95% цели");
                }
                if (drop != null && drop > 0) {
                    reasons.add("dropped_iterations");
                }
            }
            rows.add(new LiveRow(o.name(), target, actual, p95, o.slaPercentile(), o.slaThresholdMs(), err, o.maxErrorPct(), drop,
                    !reasons.isEmpty(), reasons));
        }
        return rows;
    }

    /** Значение операции: основной сценарий, а пока он не стартовал (прогрев) - его спутник «имя__warmup». */
    private static Double pick(Map<String, Double> m, String name) {
        Double v = m.get(name);
        return v != null ? v : m.get(name + ru.k6lab.core.k6config.K6ConfigGenerator.WARMUP_SUFFIX);
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

    private static void putD(ObjectNode o, String k, Double v) {
        if (v != null && Double.isFinite(v)) {
            o.put(k, v);
        }
    }
}
