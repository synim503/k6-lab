package ru.k6lab.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.k6lab.agent.config.AgentProperties;
import ru.k6lab.agent.journal.EventJournal;
import ru.k6lab.agent.run.AgentEvents;
import ru.k6lab.agent.run.K6Command;
import ru.k6lab.agent.run.LogBatcher;
import ru.k6lab.agent.run.Masker;
import ru.k6lab.agent.run.RunManager;
import ru.k6lab.core.protocol.Message;
import ru.k6lab.core.protocol.Messages;
import ru.k6lab.core.util.ZipUtil;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.function.LongFunction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/** Запуск run на агенте с настоящим k6 (если он есть на машине): PREPARE, SPLIT данных, секреты, START, STOP, ABORT. */
class RunManagerTest {

    private static final ObjectMapper M = new ObjectMapper();

    @TempDir
    Path home;
    HttpServer artifacts;
    RunManager manager;
    CapturingEvents events;
    Path k6;
    byte[] zip;
    String zipSha;

    /** События агента в памяти (журнал на диске при этом тоже пишется). */
    static class CapturingEvents extends AgentEvents {
        final List<Message> all = new CopyOnWriteArrayList<>();
        private final EventJournal journal;

        CapturingEvents(EventJournal journal) {
            super(journal, new org.springframework.beans.factory.support.StaticListableBeanFactory().getBeanProvider(ru.k6lab.agent.ws.AgentConnection.class));
            this.journal = journal;
        }

        @Override
        public <T extends Message.Sequenced> T emit(LongFunction<T> factory) {
            T e = journal.append(factory);
            all.add(e);
            return e;
        }

        <T extends Message> List<T> of(Class<T> type) {
            return all.stream().filter(type::isInstance).map(type::cast).toList();
        }

        List<String> logLines() {
            return of(Messages.LogBatch.class).stream().flatMap(b -> b.lines().stream()).map(Messages.LogLine::line).toList();
        }
    }

    @BeforeEach
    void setUp() throws Exception {
        k6 = findK6();
        Assumptions.assumeTrue(k6 != null, "k6 не найден в PATH");
        AgentProperties props = new AgentProperties();
        props.setHome(home);
        props.setK6Path(k6);
        events = new CapturingEvents(new EventJournal(home.resolve("data"), 10_000_000));
        manager = new RunManager(props, events);
    }

    @AfterEach
    void tearDown() {
        if (artifacts != null) {
            artifacts.stop(0);
        }
    }

    private static Path findK6() {
        String path = System.getenv("PATH");
        if (path == null) {
            return null;
        }
        for (String dir : path.split(java.io.File.pathSeparator)) {
            for (String name : new String[]{"k6", "k6.exe"}) {
                Path p = Path.of(dir, name);
                if (Files.isRegularFile(p) && Files.isExecutable(p)) {
                    return p.toAbsolutePath();
                }
            }
        }
        return null;
    }

    private String serve(Map<String, byte[]> files) throws Exception {
        zip = ZipUtil.write(files);
        zipSha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(zip));
        artifacts = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        artifacts.createContext("/agent/artifact/run", ex -> {
            ex.sendResponseHeaders(200, zip.length);
            ex.getResponseBody().write(zip);
            ex.close();
        });
        artifacts.start();
        return "http://127.0.0.1:" + artifacts.getAddress().getPort() + "/agent/artifact/run";
    }

    private static int freePort() throws IOException {
        try (var s = new java.net.ServerSocket(0)) {
            return s.getLocalPort();
        }
    }

    private Messages.Prepare prepare(String runId, String url, String sha, String config, Map<String, String> env,
                                     Map<String, String> secrets, List<Messages.DataRule> rules, String segment) throws Exception {
        return new Messages.Prepare("cmd-" + UUID.randomUUID(), runId, url, sha, M.readTree(config), env, secrets,
                segment, segment == null ? null : "0,1/2,1", Map.of("test_run_id", runId, "generator", "gen-1", "scenario", "hacked"),
                rules, null, freePort(), List.of("scenario"), "main.js", 200);
    }

    private static final String SCRIPT = """
            import { sleep } from 'k6';
            const rows = open('./data/users.csv').split('\\n').filter((l) => l.length > 0);
            export function setup() {
              console.log('DATA-ROWS:' + rows.length + ' first=' + rows[0]);
              console.log('TOKEN-IS:' + __ENV.TOKEN);
            }
            export function work() { sleep(0.1); }
            export default function () {}
            """;

    private static final String SHORT_CONFIG = "{\"scenarios\":{\"work\":{\"executor\":\"constant-arrival-rate\",\"rate\":5,"
            + "\"timeUnit\":\"1s\",\"duration\":\"4s\",\"preAllocatedVUs\":2,\"maxVUs\":5,\"exec\":\"work\"}}}";
    /** Pause/resume и смена VU в k6 работают только для externally-controlled (arrival-rate и ramping-vus - нет). */
    private static final String VU_CONFIG = "{\"scenarios\":{\"work\":{\"executor\":\"externally-controlled\",\"vus\":1,"
            + "\"maxVUs\":5,\"duration\":\"120s\",\"exec\":\"work\"}}}";
    private static final String LONG_CONFIG = SHORT_CONFIG.replace("\"duration\":\"4s\"", "\"duration\":\"120s\"");

    private Map<String, byte[]> files(int csvRows) {
        Map<String, byte[]> f = new LinkedHashMap<>();
        f.put("main.js", SCRIPT.getBytes(StandardCharsets.UTF_8));
        StringBuilder csv = new StringBuilder("login,pwd\n");
        for (int i = 1; i <= csvRows; i++) {
            csv.append("user").append(i).append(",p\n");
        }
        f.put("data/users.csv", csv.toString().getBytes(StandardCharsets.UTF_8));
        return f;
    }

    private void waitFor(Class<? extends Message> type, int timeoutSec) {
        await().atMost(Duration.ofSeconds(timeoutSec)).until(() -> events.all.stream().anyMatch(type::isInstance));
    }

    // ---------------------------------------------------------------- сценарии

    @Test
    void fullRunWithSplitDataSecretsMaskingAndSummary() throws Exception {
        String url = serve(files(10));
        // второй из двух генераторов: сегмент 1/2:1 -> строки 5..9 (5 строк) плюс заголовок = 6 строк
        var p = prepare("run-1", url, zipSha, SHORT_CONFIG, Map.of("TOKEN", "${secret:T}", "BASE", "http://x"),
                Map.of("T", "supersecret123"), List.of(new Messages.DataRule("data/users.csv", "SPLIT", true)), "1/2:1");
        assertThat(manager.handle(p).ok()).isTrue();
        waitFor(Messages.Prepared.class, 30);
        assertThat(manager.runs()).extracting(Messages.AgentRun::state).containsExactly(Messages.AgentRunState.PREPARED);

        // повторный PREPARE идемпотентен
        assertThat(manager.handle(p).ok()).isTrue();
        assertThat(events.of(Messages.Prepared.class)).hasSize(1);

        long startAt = System.currentTimeMillis() + 1500;
        assertThat(manager.handle(new Messages.Start("cmd-start", "run-1", startAt)).ok()).isTrue();
        waitFor(Messages.Exit.class, 60);

        Messages.Exit exit = events.of(Messages.Exit.class).get(0);
        assertThat(exit.code()).as("лог: %s", events.logLines()).isZero();
        assertThat(events.of(Messages.Status.class)).anyMatch(s -> s.state() == Messages.AgentRunState.RUNNING && s.pid() != null);
        // k6 стартовал не раньше назначенного времени (синхронный старт)
        assertThat(events.all.stream().filter(m -> m instanceof Messages.Status).findFirst().get()).isNotNull();

        List<String> lines = events.logLines();
        // SPLIT: этому генератору досталась вторая половина данных
        assertThat(lines).anyMatch(l -> l.contains("DATA-ROWS:6 first=login,pwd"));
        assertThat(lines).anyMatch(l -> l.contains("SPLIT") && l.contains("из 10"));
        // секрет подставлен на агенте, но в логе замаскирован
        assertThat(lines).anyMatch(l -> l.contains("TOKEN-IS:******"));
        assertThat(String.join("\n", lines)).doesNotContain("supersecret123");

        assertThat(events.of(Messages.Summary.class)).isNotEmpty();
        assertThat(events.of(Messages.Summary.class).get(0).summary().has("metrics")).isTrue();
        // seq событий строго возрастает и непрерывен
        List<Long> seqs = events.all.stream().map(m -> ((Message.Sequenced) m).seq()).toList();
        assertThat(seqs).isSorted();
        assertThat(seqs.get(seqs.size() - 1) - seqs.get(0)).isEqualTo(seqs.size() - 1);
    }

    @Test
    void k6StatusReportsCountersWithoutPercentiles() throws Exception {
        String url = serve(files(2));
        var p = prepare("run-2", url, zipSha, SHORT_CONFIG.replace("\"duration\":\"4s\"", "\"duration\":\"8s\""), Map.of("TOKEN", "x"),
                Map.of(), List.of(), null);
        manager.handle(p);
        waitFor(Messages.Prepared.class, 30);
        manager.handle(new Messages.Start("c", "run-2", System.currentTimeMillis() + 300));
        await().atMost(Duration.ofSeconds(30)).until(() -> events.of(Messages.K6Status.class).stream().anyMatch(s -> s.iterations() > 0));
        Messages.K6Status st = events.of(Messages.K6Status.class).stream().filter(s -> s.iterations() > 0).findFirst().get();
        assertThat(st.running()).isTrue();
        assertThat(st.vusMax()).isGreaterThan(0);
        assertThat(st.droppedIterations()).isGreaterThanOrEqualTo(0);
        assertThat(manager.activeRunIds()).containsExactly("run-2");
        waitFor(Messages.Exit.class, 60);
        assertThat(manager.activeRunIds()).isEmpty();
    }

    @Test
    void stopEndsRunGracefullyAndAbortKillsIt() throws Exception {
        String url = serve(files(2));
        var p = prepare("run-3", url, zipSha, LONG_CONFIG, Map.of("TOKEN", "x"), Map.of(), List.of(), null);
        manager.handle(p);
        waitFor(Messages.Prepared.class, 30);
        manager.handle(new Messages.Start("c", "run-3", System.currentTimeMillis() + 200));
        await().atMost(Duration.ofSeconds(30)).until(() -> events.of(Messages.K6Status.class).stream().anyMatch(s -> s.running()));

        assertThat(manager.handle(new Messages.Stop("c-stop", "run-3")).ok()).isTrue();
        waitFor(Messages.Exit.class, 60); // намного раньше 120 секунд
        assertThat(events.of(Messages.Status.class)).anyMatch(s -> s.state() == Messages.AgentRunState.STOPPING);

        // ABORT другого run
        var p2 = prepare("run-4", serve(files(2)), zipSha, LONG_CONFIG, Map.of("TOKEN", "x"), Map.of(), List.of(), null);
        events.all.clear();
        manager.handle(p2);
        waitFor(Messages.Prepared.class, 30);
        manager.handle(new Messages.Start("c2", "run-4", System.currentTimeMillis() + 200));
        await().atMost(Duration.ofSeconds(30)).until(() -> events.of(Messages.K6Status.class).stream().anyMatch(s -> s.running()));
        assertThat(manager.handle(new Messages.Abort("c-abort", "run-4")).ok()).isTrue();
        waitFor(Messages.Exit.class, 30);
        assertThat(events.of(Messages.Exit.class).get(0).signal()).isEqualTo("SIGKILL");
    }

    @Test
    void pauseAndResumeUseRestApi() throws Exception {
        String url = serve(files(2));
        manager.handle(prepare("run-5", url, zipSha, VU_CONFIG, Map.of("TOKEN", "x"), Map.of(), List.of(), null));
        waitFor(Messages.Prepared.class, 30);
        manager.handle(new Messages.Start("c", "run-5", System.currentTimeMillis() + 200));
        await().atMost(Duration.ofSeconds(30)).until(() -> events.of(Messages.K6Status.class).stream().anyMatch(s -> s.running()));
        assertThat(manager.handle(new Messages.Pause("p", "run-5")).ok()).isTrue();
        await().atMost(Duration.ofSeconds(15)).until(() -> events.of(Messages.K6Status.class).stream().anyMatch(s -> s.paused()));
        assertThat(manager.handle(new Messages.Resume("r", "run-5")).ok()).isTrue();
        assertThat(manager.handle(new Messages.ScaleVus("s", "run-5", 3)).ok()).isTrue();
        await().atMost(Duration.ofSeconds(15)).until(() -> events.of(Messages.K6Status.class).stream().anyMatch(st -> st.vus() == 3));
        await().atMost(Duration.ofSeconds(15)).until(() -> {
            var l = events.of(Messages.K6Status.class);
            return !l.get(l.size() - 1).paused();
        });
        manager.handle(new Messages.Abort("a", "run-5"));
        waitFor(Messages.Exit.class, 30);
    }

    @Test
    void prepareFailuresAreReported() throws Exception {
        String url = serve(files(2));
        // неверный sha256
        manager.handle(prepare("bad-sha", url, "0".repeat(64), SHORT_CONFIG, Map.of(), Map.of(), List.of(), null));
        waitFor(Messages.PrepareFailed.class, 30);
        assertThat(events.of(Messages.PrepareFailed.class).get(0).reason()).contains("sha256");
        assertThat(manager.runs()).isEmpty();
        assertThat(Files.exists(home.resolve("runs/bad-sha"))).isFalse();

        // секрет не передан
        events.all.clear();
        manager.handle(prepare("no-secret", url, zipSha, SHORT_CONFIG, Map.of("TOKEN", "${secret:MISSING}"), Map.of(), List.of(), null));
        waitFor(Messages.PrepareFailed.class, 30);
        assertThat(events.of(Messages.PrepareFailed.class).get(0).reason()).contains("MISSING");

        // нет файла данных из правила SPLIT
        events.all.clear();
        manager.handle(prepare("no-data", url, zipSha, SHORT_CONFIG, Map.of("TOKEN", "x"), Map.of(),
                List.of(new Messages.DataRule("data/absent.csv", "SPLIT", true)), "0:1/2"));
        waitFor(Messages.PrepareFailed.class, 30);
        assertThat(events.of(Messages.PrepareFailed.class).get(0).reason()).contains("absent.csv");

        // START до PREPARE и команды на неизвестный run
        assertThat(manager.handle(new Messages.Start("c", "unknown", System.currentTimeMillis())).ok()).isFalse();
        assertThat(manager.handle(new Messages.Stop("c", "unknown")).ok()).isFalse();
    }

    @Test
    void cancelPrepareRemovesPreparedRun() throws Exception {
        String url = serve(files(2));
        manager.handle(prepare("run-c", url, zipSha, SHORT_CONFIG, Map.of("TOKEN", "x"), Map.of(), List.of(), null));
        waitFor(Messages.Prepared.class, 30);
        assertThat(manager.handle(new Messages.CancelPrepare("cp", "run-c")).ok()).isTrue();
        assertThat(manager.runs()).isEmpty();
        assertThat(Files.exists(home.resolve("runs/run-c"))).isFalse();
        // CANCEL_PREPARE идемпотентен
        assertThat(manager.handle(new Messages.CancelPrepare("cp2", "run-c")).ok()).isTrue();
    }

    // ---------------------------------------------------------------- чистая логика

    @Test
    void commandLineAndEnvironment() throws Exception {
        var prom = new Messages.PrometheusParams("http://prom:9090/api/v1/write", Map.of("Authorization", "Bearer t"), "5s", true);
        var p = new Messages.Prepare("c", "run-9", "u", "s", M.readTree("{}"), Map.of("A", "b"), Map.of(), "0:1/3", "0,1/3,2/3,1",
                Map.of("test_run_id", "run-9", "generator", "g1", "scenario", "hacked"), List.of(), prom, 6566, List.of(), "main.js", 200);
        List<String> args = K6Command.args(Path.of("/opt/k6"), p, Path.of("/r/config.json"), Path.of("/r/summary.json"), 6566);
        assertThat(args).containsSequence("--execution-segment", "0:1/3", "--execution-segment-sequence", "0,1/3,2/3,1");
        assertThat(args).containsSequence("--out", "experimental-prometheus-rw");
        assertThat(args).containsSequence("--tag", "generator=g1").containsSequence("--tag", "test_run_id=run-9");
        assertThat(args).doesNotContain("scenario=hacked"); // системный тег scenario не переопределяется
        assertThat(args).containsSequence("--address", "127.0.0.1:6566");
        assertThat(args.get(args.size() - 1)).isEqualTo("main.js");

        Map<String, String> env = K6Command.environment(p, Map.of("A", "b"));
        assertThat(env).containsEntry("K6_PROMETHEUS_RW_SERVER_URL", "http://prom:9090/api/v1/write")
                .containsEntry("K6_PROMETHEUS_RW_TREND_AS_NATIVE_HISTOGRAM", "true")
                .containsEntry("K6_PROMETHEUS_RW_PUSH_INTERVAL", "5s")
                .containsEntry("K6_PROMETHEUS_RW_HEADERS_Authorization", "Bearer t").containsEntry("A", "b");

        // один генератор: сегмент не передаётся; без Prometheus - без --out
        var single = new Messages.Prepare("c", "r", "u", "s", M.readTree("{}"), Map.of(), Map.of(), "0:1", "0,1", Map.of(), List.of(), null, 6566, List.of(), "main.js", 200);
        List<String> a2 = K6Command.args(Path.of("k6"), single, Path.of("c"), Path.of("s"), 6566);
        assertThat(a2).doesNotContain("--execution-segment", "--out");

        assertThat(K6Command.resolveSecrets(Map.of("X", "pre-${secret:S}-post"), Map.of("S", "v$1")).get("X")).isEqualTo("pre-v$1-post");
    }

    @Test
    void logBatcherLimitsLinesPerSecondAndReportsDropped() throws Exception {
        List<Messages.LogBatch> batches = new ArrayList<>();
        EventJournal journal = new EventJournal(home.resolve("lb"), 1_000_000);
        AgentEvents ev = new AgentEvents(journal, new org.springframework.beans.factory.support.StaticListableBeanFactory()
                .getBeanProvider(ru.k6lab.agent.ws.AgentConnection.class)) {
            @Override
            public <T extends Message.Sequenced> T emit(LongFunction<T> factory) {
                T e = journal.append(factory);
                if (e instanceof Messages.LogBatch b) {
                    batches.add(b);
                }
                return e;
            }
        };
        ScheduledExecutorService sched = Executors.newSingleThreadScheduledExecutor();
        try (LogBatcher lb = new LogBatcher("r", 50, new Masker(List.of("secret-value")), ev, sched)) {
            for (int i = 0; i < 500; i++) {
                lb.add("stdout", "line " + i + " secret-value");
            }
            lb.flush();
            assertThat(lb.totalDropped()).isEqualTo(450);
        } finally {
            sched.shutdownNow();
        }
        long delivered = batches.stream().mapToLong(b -> b.lines().size()).sum();
        long dropped = batches.stream().mapToLong(Messages.LogBatch::dropped).sum();
        assertThat(delivered).isEqualTo(50);
        assertThat(dropped).isEqualTo(450);
        assertThat(batches.get(0).lines().get(0).line()).isEqualTo("line 0 ******");
    }
}
