package ru.k6lab.server.metrics;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import ru.k6lab.core.k6config.ConfigInput;
import ru.k6lab.core.protocol.Message;
import ru.k6lab.core.protocol.Messages;
import ru.k6lab.core.util.ZipUtil;
import ru.k6lab.server.agent.AgentEventListener;
import ru.k6lab.server.agent.AgentSession;
import ru.k6lab.server.agent.AgentSessionRegistry;
import ru.k6lab.server.common.Hashes;
import ru.k6lab.server.config.AppProperties;
import ru.k6lab.server.generator.GeneratorRepository;
import ru.k6lab.server.settings.SettingsService;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Диагностика связки k6 -> Prometheus: 10-секундный smoke-тест на выбранном генераторе (GET /actuator/health админки),
 * затем проверка, что в Prometheus пришли метрики и что histogram_quantile по native histograms возвращает значение.
 * Результат хранится в настройках вместе с отпечатком параметров Prometheus; пока диагностика не пройдена (или
 * параметры изменились) - запуск тестов заблокирован ({@link DiagnosticsGate}).
 */
@Service
public class PrometheusDiagnostics implements AgentEventListener {

    private static final Logger log = LoggerFactory.getLogger(PrometheusDiagnostics.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int RATE = 5;
    private static final int DURATION_SEC = 10;

    public record Step(String name, boolean ok, String detail) {
    }

    public record Result(boolean ok, Instant at, String fingerprint, String generator, List<Step> steps, String summary) {
    }

    private static final class Pending {
        final String token = Hashes.randomToken();
        final CompletableFuture<Void> prepared = new CompletableFuture<>();
        final CompletableFuture<Messages.Exit> exit = new CompletableFuture<>();
        final StringBuilder out = new StringBuilder();
        byte[] artifact;
        String sha;
        long generatorId;
    }

    private final SettingsService settings;
    private final PrometheusClient prom;
    private final MetricNamesService names;
    private final AgentSessionRegistry registry;
    private final AppProperties props;
    private final GeneratorRepository generators;
    @org.springframework.beans.factory.annotation.Value("${server.port:8080}")
    private int httpPort;
    private final Map<String, Pending> byRunId = new ConcurrentHashMap<>();
    private final Map<String, Pending> byToken = new ConcurrentHashMap<>();
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "prom-diagnostics");
        t.setDaemon(true);
        return t;
    });
    private final AtomicBoolean running = new AtomicBoolean();
    private volatile List<Step> progress = List.of();

    public PrometheusDiagnostics(SettingsService settings, PrometheusClient prom, MetricNamesService names,
                                 AgentSessionRegistry registry, AppProperties props, GeneratorRepository generators) {
        this.generators = generators;
        this.settings = settings;
        this.prom = prom;
        this.names = names;
        this.registry = registry;
        this.props = props;
    }

    @PreDestroy
    void shutdown() {
        worker.shutdownNow();
    }

    public boolean isRunning() {
        return running.get();
    }

    public List<Step> progress() {
        return progress;
    }

    /** Отпечаток параметров Prometheus: при изменении URL/аутентификации прежний успех недействителен. */
    public String fingerprint() {
        SettingsService.Prometheus p = settings.prometheus();
        return Hashes.sha256Hex(String.join("|", String.valueOf(p.rwUrl()), String.valueOf(p.queryUrl()), String.valueOf(p.authType()),
                String.valueOf(p.user()), p.secret() == null ? "" : Hashes.sha256Hex(p.secret()))).substring(0, 16);
    }

    public Optional<Result> last() {
        return settings.get(SettingsService.DIAGNOSTICS).filter(s -> !s.isBlank()).map(s -> {
            try {
                JsonNode n = MAPPER.readTree(s);
                List<Step> steps = new ArrayList<>();
                n.path("steps").forEach(x -> steps.add(new Step(x.path("name").asText(), x.path("ok").asBoolean(), x.path("detail").asText())));
                return new Result(n.path("ok").asBoolean(), Instant.parse(n.path("at").asText()), n.path("fingerprint").asText(),
                        n.path("generator").asText(), steps, n.path("summary").asText());
            } catch (Exception e) {
                return null;
            }
        });
    }

    /** Диагностика пройдена для текущих параметров. */
    public boolean passed() {
        return last().map(r -> r.ok() && r.fingerprint().equals(fingerprint())).orElse(false);
    }

    /** Запуск в фоне; один прогон одновременно. Возвращает false, если прогон уже идёт. */
    public boolean start(long generatorId) {
        if (!running.compareAndSet(false, true)) {
            return false;
        }
        progress = List.of(new Step("Старт", true, "Диагностика запущена"));
        worker.submit(() -> {
            try {
                Result r = run(generatorId);
                save(r);
            } catch (Throwable t) {
                log.error("Диагностика Prometheus завершилась ошибкой", t);
                save(new Result(false, Instant.now(), fingerprint(), "", List.of(new Step("Внутренняя ошибка", false, String.valueOf(t.getMessage()))),
                        "Внутренняя ошибка диагностики: " + t.getMessage()));
            } finally {
                running.set(false);
            }
        });
        return true;
    }

    /** Синхронный прогон (для тестов). */
    public Result runSync(long generatorId) {
        if (!running.compareAndSet(false, true)) {
            throw new IllegalStateException("Диагностика уже выполняется");
        }
        try {
            Result r = run(generatorId);
            save(r);
            return r;
        } finally {
            running.set(false);
        }
    }

    private void save(Result r) {
        ObjectNode n = MAPPER.createObjectNode();
        n.put("ok", r.ok());
        n.put("at", r.at().toString());
        n.put("fingerprint", r.fingerprint());
        n.put("generator", r.generator());
        n.put("summary", r.summary());
        ArrayNode a = n.putArray("steps");
        for (Step s : r.steps()) {
            a.addObject().put("name", s.name()).put("ok", s.ok()).put("detail", s.detail());
        }
        settings.set(SettingsService.DIAGNOSTICS, n.toString());
    }

    private Result run(long generatorId) {
        List<Step> steps = new ArrayList<>();
        String fp = fingerprint();
        SettingsService.Prometheus p = settings.prometheus();
        Instant started = Instant.now();
        String genName = "";
        try {
            if (!p.configured()) {
                return fail(steps, fp, genName, "Параметры Prometheus", "Не заданы URL remote write и/или URL запросов");
            }
            step(steps, "Параметры Prometheus", true, "remote write: " + p.rwUrl() + ", запросы: " + p.queryUrl());
            try {
                prom.ping(p);
                step(steps, "API запросов Prometheus", true, "доступен");
            } catch (IOException e) {
                return fail(steps, fp, genName, "API запросов Prometheus", e.getMessage());
            }
            String target = target(generatorId);
            if (target == null) {
                return fail(steps, fp, genName, "Цель smoke-теста",
                        "Не задан публичный URL админки (K6LAB_PUBLIC_URL): генератору нужна цель для запросов");
            }
            AgentSession session = registry.get(generatorId).orElse(null);
            if (session == null) {
                return fail(steps, fp, genName, "Генератор", "Генератор не подключён по WebSocket");
            }
            genName = session.generatorName();

            String runId = "diag-" + System.currentTimeMillis();
            Pending pend = new Pending();
            pend.generatorId = generatorId;
            pend.artifact = ZipUtil.singleFile("script.js", SCRIPT.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            pend.sha = java.util.HexFormat.of().formatHex(Hashes.sha256Digest().digest(pend.artifact));
            byRunId.put(runId, pend);
            byToken.put(pend.token, pend);
            try {
                runSmoke(steps, session, runId, pend, p, target);
                if (steps.stream().anyMatch(s -> !s.ok())) {
                    return new Result(false, Instant.now(), fp, genName, steps, summary(steps));
                }
                checkMetrics(steps, p, runId, started);
            } finally {
                byRunId.remove(runId);
                byToken.remove(pend.token);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            step(steps, "Прервано", false, "Диагностика прервана");
        } catch (Exception e) {
            step(steps, "Ошибка", false, String.valueOf(e.getMessage()));
        }
        return new Result(steps.stream().allMatch(Step::ok), Instant.now(), fp, genName, steps, summary(steps));
    }

    private void runSmoke(List<Step> steps, AgentSession session, String runId, Pending pend, SettingsService.Prometheus p,
                          String target) throws Exception {
        String url = props.getPublicUrl().replaceAll("/+$", "");
        // тот же хост:порт, что и у WebSocket агента, - гарантированно доступен с генератора
        java.net.URI ws = java.net.URI.create(generators.findById(pend.generatorId).orElseThrow().getServerUrl());
        String artifactUrl = ("wss".equals(ws.getScheme()) ? "https" : "http") + "://" + ws.getHost() + ":" + ws.getPort()
                + "/agent/diag/" + pend.token;
        ObjectNode cfg = MAPPER.createObjectNode();
        ObjectNode sc = cfg.putObject("scenarios").putObject("diag");
        sc.put("executor", "constant-arrival-rate");
        sc.put("rate", RATE);
        sc.put("timeUnit", "1s");
        sc.put("duration", DURATION_SEC + "s");
        sc.put("preAllocatedVUs", 2);
        sc.put("maxVUs", 10);
        Map<String, String> tags = new LinkedHashMap<>();
        tags.put("test_run_id", runId);
        tags.put("generator", session.generatorName());
        tags.put("test_scenario", "prometheus-diagnostics");
        Map<String, String> env = Map.of("TARGET", target);
        Messages.PrometheusParams promParams = new Messages.PrometheusParams(p.rwUrl(), p.authHeaders(), "2s", true, settings.classicHistograms());
        String cmdId = UUID.randomUUID().toString();
        session.send(new Messages.Prepare(cmdId, runId, artifactUrl, pend.sha, cfg, env, Map.of(), "0:1", null, tags, List.of(),
                promParams, 6565, ConfigInput.SYSTEM_TAGS, "script.js", 50));
        try {
            pend.prepared.get(45, TimeUnit.SECONDS);
        } catch (java.util.concurrent.TimeoutException e) {
            step(steps, "Подготовка на генераторе", false, "Генератор не подтвердил PREPARE за 45 с");
            return;
        } catch (java.util.concurrent.ExecutionException e) {
            step(steps, "Подготовка на генераторе", false, e.getCause().getMessage());
            return;
        }
        step(steps, "Подготовка на генераторе", true, "артефакт скачан, конфигурация k6 принята");
        long offset = session.clockOffsetMs() == null ? 0 : session.clockOffsetMs();
        session.send(new Messages.Start(UUID.randomUUID().toString(), runId, System.currentTimeMillis() + 2000 + offset));
        try {
            Messages.Exit exit = pend.exit.get(DURATION_SEC + 40, TimeUnit.SECONDS);
            if (exit.code() != 0) {
                step(steps, "Smoke-тест k6", false, "k6 завершился с кодом " + exit.code() + tail(pend));
                return;
            }
            step(steps, "Smoke-тест k6", true, DURATION_SEC + " с, " + RATE + " запросов/с на " + target);
        } catch (java.util.concurrent.TimeoutException e) {
            session.send(new Messages.Abort(UUID.randomUUID().toString(), runId));
            step(steps, "Smoke-тест k6", false, "k6 не завершился вовремя" + tail(pend));
        }
        if (url.isEmpty()) {
            log.debug("publicUrl не задан");
        }
    }

    private void checkMetrics(List<Step> steps, SettingsService.Prometheus p, String runId, Instant started) throws Exception {
        String configured = names.names().iterationDuration();
        boolean classic = settings.classicHistograms();
        String tag = "{test_run_id=\"" + runId + "\"}";
        String sel = configured + tag;
        // remote write приходит с задержкой: ждём до 40 с. Сначала ищем ЛЮБЫЕ серии с нашим тегом (а не одну конкретную метрику):
        // так видно и когда метрики идут, но имя гистограммы другое или гистограммы не принимаются.
        List<String> found = List.of();
        Double q = null;
        String lastErr = "";
        for (int i = 0; i < 20 && q == null; i++) {
            try {
                List<PrometheusClient.Sample> series = prom.query(p, "count by (__name__) ({test_run_id=\"" + runId + "\"})", Instant.now());
                found = series.stream().map(x -> x.labels().get("__name__")).filter(x -> x != null).sorted().toList();
                q = prom.single(p, quantileQuery(configured, tag, classic), Instant.now()).orElse(null);
            } catch (IOException e) {
                lastErr = e.getMessage();
            }
            if (q == null) {
                Thread.sleep(2000);
            }
        }
        if (found.isEmpty() && q == null) {
            step(steps, "Метрики k6 в Prometheus", false, "Ни одной серии с тегом test_run_id=" + runId
                    + " не появилось. Проверьте URL remote write, аутентификацию и что Prometheus запущен с "
                    + "--web.enable-remote-write-receiver. " + lastErr);
            return;
        }
        String sample = found.stream().limit(6).collect(java.util.stream.Collectors.joining(", "));
        step(steps, "Метрики k6 в Prometheus", true, "серий с тегом test_run_id: " + found.size()
                + (found.isEmpty() ? "" : " (" + sample + (found.size() > 6 ? ", …" : "") + ")"));
        if (q != null) {
            step(steps, classic ? "histogram_quantile (классические гистограммы)" : "histogram_quantile (native histograms)", true,
                    String.format(java.util.Locale.ROOT, "p95 = %.1f мс по " + configured + (classic ? "_bucket" : ""), q));
            return;
        }
        if (classic) {
            step(steps, "histogram_quantile (классические гистограммы)", false, "Метрики приходят (" + sample + "), но корзин " + configured
                    + "_bucket нет. Классические гистограммы формирует агент генератора: нужен агент на Go версии с поддержкой этого режима "
                    + "(обновите агента на генераторе). Либо переключите «Режим гистограмм» на native и включите в Prometheus --enable-feature=native-histograms.");
            return;
        }
        // метрики идут, а гистограммы по настроенному имени нет: ищем, под каким именем она приходит
        String other = found.stream().filter(x -> x.contains("iteration_duration") && !x.equals(configured)).findFirst().orElse(null);
        if (other != null) {
            Double alt = prom.single(p, quantileQuery(other, tag, false), Instant.now()).orElse(null);
            if (alt != null) {
                step(steps, "histogram_quantile (native histograms)", false, "Гистограмма приходит под именем " + other
                        + ", а в настройках указано " + configured + ". Исправьте «Имена метрик k6» (iterationDuration, opDuration, ...): имена зависят от версии k6 и remote write.");
                return;
            }
            step(steps, "histogram_quantile (native histograms)", false, "Приходят классические серии " + other
                    + " (не native histogram). Запустите Prometheus с --enable-feature=native-histograms (версия 2.40+; в 3.x флаг тоже нужен) и перезапустите его.");
            return;
        }
        step(steps, "histogram_quantile (native histograms)", false, "Метрики приходят (" + sample + "), но гистограммы времени итерации нет: "
                + "Prometheus не принимает native histograms. Включите --enable-feature=native-histograms; k6 передаёт "
                + "K6_PROMETHEUS_RW_TREND_AS_NATIVE_HISTOGRAM=true. Если имена метрик у вас другие - поправьте «Имена метрик k6».");
    }

    private static String quantileQuery(String metric, String tag, boolean classic) {
        return classic
                ? "histogram_quantile(0.95, sum by (le) (increase(" + metric + "_bucket" + tag + "[2m]))) * 1000"
                : "histogram_quantile(0.95, sum(increase(" + metric + tag + "[2m]))) * 1000";
    }

    private record PromNames(String iter) {
        PromNames(MetricNamesService s) {
            this(s.names().iterationDuration());
        }
    }

    /** Цель smoke-теста: K6LAB_PUBLIC_URL, иначе тот же хост, что в URL агента, и порт UI админки. */
    private String target(long generatorId) {
        String base = props.getPublicUrl();
        if (base == null || base.isBlank()) {
            String serverUrl = generators.findById(generatorId).map(g -> g.getServerUrl()).orElse(null);
            if (serverUrl == null) {
                return null;
            }
            base = "http://" + java.net.URI.create(serverUrl).getHost() + ":" + httpPort;
        }
        return base.replaceAll("/+$", "") + "/actuator/health";
    }

    private static final String SCRIPT = """
            import http from 'k6/http';
            export default function () {
              http.get(__ENV.TARGET);
            }
            """;

    private static String tail(Pending p) {
        String s;
        synchronized (p.out) {
            s = p.out.toString();
        }
        if (s.isBlank()) {
            return "";
        }
        String t = s.length() > 600 ? s.substring(s.length() - 600) : s;
        return ". Вывод k6: " + t.strip();
    }

    private void step(List<Step> steps, String name, boolean ok, String detail) {
        steps.add(new Step(name, ok, detail));
        progress = List.copyOf(steps);
    }

    private Result fail(List<Step> steps, String fp, String gen, String name, String detail) {
        step(steps, name, false, detail);
        return new Result(false, Instant.now(), fp, gen, steps, summary(steps));
    }

    private static String summary(List<Step> steps) {
        return steps.stream().filter(s -> !s.ok()).findFirst().map(s -> s.name() + ": " + s.detail()).orElse("Диагностика пройдена");
    }

    // ---- события агента и скачивание артефакта

    @Override
    public void onEvent(long generatorId, Message message) {
        String runId = null;
        if (message instanceof Messages.Prepared x) {
            runId = x.runId();
        } else if (message instanceof Messages.PrepareFailed x) {
            runId = x.runId();
        } else if (message instanceof Messages.Exit x) {
            runId = x.runId();
        } else if (message instanceof Messages.LogBatch x) {
            runId = x.runId();
        }
        if (runId == null || !runId.startsWith("diag-")) {
            return;
        }
        Pending p = byRunId.get(runId);
        if (p == null) {
            return;
        }
        if (message instanceof Messages.Prepared) {
            p.prepared.complete(null);
        } else if (message instanceof Messages.PrepareFailed x) {
            p.prepared.completeExceptionally(new IllegalStateException(x.reason()));
        } else if (message instanceof Messages.Exit x) {
            p.exit.complete(x);
        } else if (message instanceof Messages.LogBatch x) {
            synchronized (p.out) {
                x.lines().forEach(l -> p.out.append(l.line()).append('\n'));
            }
        }
    }

    /** Одноразовая выдача артефакта диагностики агенту. */
    public Optional<byte[]> takeArtifact(String token) {
        Pending p = byToken.remove(token);
        return p == null ? Optional.empty() : Optional.of(p.artifact);
    }
}
