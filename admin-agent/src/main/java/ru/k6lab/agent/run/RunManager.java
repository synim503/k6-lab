package ru.k6lab.agent.run;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;
import ru.k6lab.agent.config.AgentProperties;
import ru.k6lab.agent.ws.CommandHandler;
import ru.k6lab.agent.ws.RunStateProvider;
import ru.k6lab.core.protocol.Message;
import ru.k6lab.core.protocol.Messages;
import ru.k6lab.core.protocol.Messages.AgentRunState;
import ru.k6lab.core.segment.DataSplitter;
import ru.k6lab.core.segment.ExecutionSegments;
import ru.k6lab.core.segment.Fraction;
import ru.k6lab.core.util.ZipUtil;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Жизненный цикл run на агенте: PREPARE (скачать и проверить архив, нарезать данные, собрать окружение),
 * START в заданное время, логи, статус k6, STOP (SIGINT), ABORT (SIGKILL), PAUSE/RESUME/SCALE_VUS через REST API k6.
 * Остановка связи с сервером тест не прерывает: события копятся в дисковом журнале.
 */
@Component
@Primary
public class RunManager implements CommandHandler, RunStateProvider {

    private static final Logger log = LoggerFactory.getLogger(RunManager.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final AgentProperties props;
    private final AgentEvents events;
    private final Map<String, Run> runs = new ConcurrentHashMap<>();
    private final ExecutorService worker = Executors.newCachedThreadPool(daemon("agent-run-worker"));
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(4, daemon("agent-run-sched"));
    private final HttpClient http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(10)).build();

    public RunManager(AgentProperties props, AgentEvents events) {
        this.props = props;
        this.events = events;
    }

    private static java.util.concurrent.ThreadFactory daemon(String name) {
        return r -> {
            Thread t = new Thread(r, name);
            t.setDaemon(true);
            return t;
        };
    }

    @PreDestroy
    void shutdown() {
        runs.values().forEach(r -> r.killQuietly());
        worker.shutdownNow();
        scheduler.shutdownNow();
    }

    // ------------------------------------------------------------------ RunStateProvider

    @Override
    public List<Messages.AgentRun> runs() {
        return runs.values().stream().map(r -> new Messages.AgentRun(r.id, r.state, r.pid(), r.exitCode)).toList();
    }

    @Override
    public List<String> activeRunIds() {
        return runs.values().stream().filter(r -> r.state == AgentRunState.RUNNING || r.state == AgentRunState.STOPPING)
                .map(r -> r.id).toList();
    }

    // ------------------------------------------------------------------ команды

    @Override
    public Messages.CmdAck handle(Message.Command cmd) {
        try {
            if (cmd instanceof Messages.Prepare p) {
                return prepare(p);
            } else if (cmd instanceof Messages.Start s) {
                return start(s);
            } else if (cmd instanceof Messages.Stop s) {
                return control(s.cmdId(), s.runId(), Run::stop);
            } else if (cmd instanceof Messages.Abort a) {
                return control(a.cmdId(), a.runId(), Run::abort);
            } else if (cmd instanceof Messages.Pause p) {
                return control(p.cmdId(), p.runId(), r -> r.api().pause(true));
            } else if (cmd instanceof Messages.Resume r2) {
                return control(r2.cmdId(), r2.runId(), r -> r.api().pause(false));
            } else if (cmd instanceof Messages.ScaleVus s) {
                return control(s.cmdId(), s.runId(), r -> r.api().scaleVus(s.vus()));
            } else if (cmd instanceof Messages.CancelPrepare c) {
                return cancelPrepare(c);
            }
            return new Messages.CmdAck(cmd.cmdId(), false, "Неизвестная команда");
        } catch (Exception e) {
            return new Messages.CmdAck(cmd.cmdId(), false, e.getMessage() == null ? e.toString() : e.getMessage());
        }
    }

    private interface RunAction {
        void apply(Run r) throws Exception;
    }

    private Messages.CmdAck control(String cmdId, String runId, RunAction action) throws Exception {
        Run r = runs.get(runId);
        if (r == null) {
            return new Messages.CmdAck(cmdId, false, "run " + runId + " не найден на агенте");
        }
        action.apply(r);
        return new Messages.CmdAck(cmdId, true, null);
    }

    private Messages.CmdAck prepare(Messages.Prepare p) {
        Run existing = runs.get(p.runId());
        if (existing != null) {
            // повторная доставка PREPARE: не готовим заново
            return new Messages.CmdAck(p.cmdId(), true, null);
        }
        Run run = new Run(p);
        runs.put(p.runId(), run);
        worker.execute(() -> doPrepare(run));
        return new Messages.CmdAck(p.cmdId(), true, null);
    }

    private Messages.CmdAck start(Messages.Start s) {
        Run r = runs.get(s.runId());
        if (r == null) {
            return new Messages.CmdAck(s.cmdId(), false, "run " + s.runId() + " не подготовлен");
        }
        if (r.state == AgentRunState.RUNNING) {
            return new Messages.CmdAck(s.cmdId(), true, null);
        }
        if (r.state != AgentRunState.PREPARED) {
            return new Messages.CmdAck(s.cmdId(), false, "run в состоянии " + r.state + ", START возможен только после PREPARED");
        }
        long delay = s.startAt() - System.currentTimeMillis();
        if (delay < -2000) {
            return new Messages.CmdAck(s.cmdId(), false, "Время старта уже прошло на " + (-delay) + " мс");
        }
        r.startTask = scheduler.schedule(() -> launch(r), Math.max(0, delay), TimeUnit.MILLISECONDS);
        return new Messages.CmdAck(s.cmdId(), true, null);
    }

    private Messages.CmdAck cancelPrepare(Messages.CancelPrepare c) {
        Run r = runs.get(c.runId());
        if (r == null) {
            return new Messages.CmdAck(c.cmdId(), true, null);
        }
        if (r.state == AgentRunState.RUNNING || r.state == AgentRunState.STOPPING) {
            return new Messages.CmdAck(c.cmdId(), false, "run уже запущен - используйте STOP/ABORT");
        }
        r.cancelled = true;
        if (r.startTask != null) {
            r.startTask.cancel(false);
        }
        r.state = AgentRunState.EXITED;
        runs.remove(r.id);
        deleteTree(r.dir);
        return new Messages.CmdAck(c.cmdId(), true, null);
    }

    // ------------------------------------------------------------------ PREPARE

    private void doPrepare(Run run) {
        Messages.Prepare p = run.prepare;
        try {
            Files.createDirectories(run.dir);
            Path archive = run.dir.resolve("script.zip");
            download(p.artifactUrl(), archive);
            String sha = sha256(archive);
            if (p.artifactSha256() != null && !p.artifactSha256().equalsIgnoreCase(sha)) {
                throw new IOException("sha256 архива не совпадает (получен " + sha + ", ожидался " + p.artifactSha256() + ")");
            }
            Path work = run.dir.resolve("work");
            try (InputStream in = Files.newInputStream(archive)) {
                ZipUtil.extract(in, work);
            }
            Files.deleteIfExists(archive);
            if (!Files.isRegularFile(work.resolve(p.entrypoint()))) {
                throw new IOException("Entrypoint " + p.entrypoint() + " не найден в архиве");
            }
            run.work = work;
            Files.writeString(run.dir.resolve("config.json"), MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(
                    p.k6Config() == null ? MAPPER.createObjectNode() : p.k6Config()), StandardCharsets.UTF_8);

            // нарезка данных (SPLIT) пропорционально своему execution segment
            List<String> notes = new ArrayList<>();
            if (p.dataRules() != null) {
                Fraction[] seg = p.executionSegment() == null || p.executionSegment().isBlank()
                        ? new Fraction[]{Fraction.ZERO, Fraction.ONE} : ExecutionSegments.parseSegment(p.executionSegment());
                for (Messages.DataRule rule : p.dataRules()) {
                    if (!"SPLIT".equalsIgnoreCase(rule.mode())) {
                        continue;
                    }
                    Path file = work.resolve(rule.path()).normalize();
                    if (!file.startsWith(work) || !Files.isRegularFile(file)) {
                        throw new IOException("Файл данных " + rule.path() + " не найден в архиве");
                    }
                    DataSplitter.Result r = DataSplitter.split(file, file, seg[0], seg[1], rule.hasHeader());
                    notes.add("Данные " + rule.path() + ": SPLIT, строки " + r.range().from() + ".." + (r.range().toExclusive() - 1)
                            + " из " + r.totalRows());
                }
            }

            Map<String, String> env = K6Command.resolveSecrets(p.env(), p.secrets());
            run.env = K6Command.environment(p, env);
            List<String> secretValues = new ArrayList<>(p.secrets() == null ? List.of() : p.secrets().values());
            if (p.prometheus() != null && p.prometheus().headers() != null) {
                secretValues.addAll(p.prometheus().headers().values());
            }
            run.masker = new Masker(secretValues);
            if (!Files.isExecutable(props.getK6Path())) {
                throw new IOException("k6 не найден или не исполняемый: " + props.getK6Path());
            }
            run.preNotes = notes;
            if (run.cancelled) {
                return;
            }
            run.state = AgentRunState.PREPARED;
            events.emit(seq -> new Messages.Prepared(seq, System.currentTimeMillis(), p.runId()));
            log.info("run {} подготовлен", p.runId());
        } catch (Exception e) {
            log.warn("PREPARE run {} не удался: {}", p.runId(), e.toString());
            run.state = AgentRunState.EXITED;
            runs.remove(run.id);
            deleteTree(run.dir);
            if (!run.cancelled) {
                String reason = e.getMessage() == null ? e.toString() : e.getMessage();
                events.emit(seq -> new Messages.PrepareFailed(seq, System.currentTimeMillis(), p.runId(), reason));
            }
        }
    }

    private void download(String url, Path target) throws IOException {
        try {
            HttpResponse<Path> r = http.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofMinutes(10)).GET().build(),
                    HttpResponse.BodyHandlers.ofFile(target));
            if (r.statusCode() != 200) {
                throw new IOException("Не удалось скачать архив скрипта: HTTP " + r.statusCode());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Загрузка прервана", e);
        }
    }

    // ------------------------------------------------------------------ запуск k6

    private void launch(Run run) {
        if (run.cancelled || run.state != AgentRunState.PREPARED) {
            return;
        }
        Messages.Prepare p = run.prepare;
        try {
            Path summary = run.dir.resolve("summary.json");
            // запрошенный порт REST API может быть занят (другой run, чужой процесс) - берём ближайший свободный
            run.apiPort = freePort(p.k6ApiPort());
            List<String> cmd = K6Command.args(props.getK6Path(), p, run.dir.resolve("config.json"), summary, run.apiPort);
            ProcessBuilder pb = new ProcessBuilder(cmd).directory(run.work.toFile());
            pb.environment().putAll(run.env);
            run.logs = new LogBatcher(p.runId(), p.logLinesPerSecond() > 0 ? p.logLinesPerSecond() : 200, run.masker, events, scheduler);
            for (String note : run.preNotes) {
                run.logs.add("agent", note);
            }
            Process proc = pb.start();
            run.process = proc;
            run.state = AgentRunState.RUNNING;
            run.startedAt = System.currentTimeMillis();
            events.emit(seq -> new Messages.Status(seq, System.currentTimeMillis(), p.runId(), AgentRunState.RUNNING, proc.pid()));
            log.info("run {}: k6 запущен, pid {}", p.runId(), proc.pid());

            worker.execute(() -> pump(run, proc.getInputStream(), "stdout"));
            worker.execute(() -> pump(run, proc.getErrorStream(), "stderr"));
            run.poller = scheduler.scheduleWithFixedDelay(() -> pollStatus(run), 2, 2, TimeUnit.SECONDS);
            worker.execute(() -> awaitExit(run, summary));
        } catch (Exception e) {
            log.error("Не удалось запустить k6 для run {}", p.runId(), e);
            run.state = AgentRunState.EXITED;
            run.exitCode = 127;
            events.emit(seq -> new Messages.Exit(seq, System.currentTimeMillis(), p.runId(), 127, null));
            if (run.logs != null) {
                run.logs.add("agent", "Не удалось запустить k6: " + e);
                run.logs.close();
            }
            scheduleCleanup(run);
        }
    }

    private void pump(Run run, InputStream in, String stream) {
        try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                run.logs.add(stream, line);
            }
        } catch (IOException ignored) {
            // процесс завершён
        }
    }

    private void pollStatus(Run run) {
        if (run.state != AgentRunState.RUNNING && run.state != AgentRunState.STOPPING) {
            return;
        }
        try {
            K6Api.Status st = run.api().status();
            long iterations = run.api().counter("iterations");
            long dropped = run.api().counter("dropped_iterations");
            String text = !st.running() ? "stopped" : st.paused() ? "paused" : "running";
            events.emit(seq -> new Messages.K6Status(seq, System.currentTimeMillis(), run.id, st.vus(), st.vusMax(), iterations,
                    dropped, text, st.paused(), st.running()));
        } catch (IOException e) {
            // API ещё не поднялось или k6 завершается - пропускаем такт
        }
    }

    private void awaitExit(Run run, Path summaryFile) {
        int code;
        try {
            code = run.process.waitFor();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        }
        if (run.poller != null) {
            run.poller.cancel(false);
        }
        try {
            Thread.sleep(300); // дать pump-потокам дочитать вывод
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
        run.exitCode = code;
        if (run.logs != null) {
            run.logs.close();
            long dropped = run.logs.totalDropped();
            if (dropped > 0) {
                log.info("run {}: отброшено {} строк лога по лимиту", run.id, dropped);
            }
        }
        // summary (--summary-export): кумулятивные перцентили, только справочно
        try {
            if (Files.isRegularFile(summaryFile)) {
                JsonNode summary = MAPPER.readTree(Files.readAllBytes(summaryFile));
                events.emit(seq -> new Messages.Summary(seq, System.currentTimeMillis(), run.id, summary));
            }
        } catch (IOException e) {
            log.warn("Не удалось прочитать summary run {}: {}", run.id, e.toString());
        }
        String signal = run.aborted ? "SIGKILL" : code > 128 && !run.stopRequested ? "SIG" + (code - 128) : null;
        run.state = AgentRunState.EXITED;
        events.emit(seq -> new Messages.Exit(seq, System.currentTimeMillis(), run.id, code, signal));
        log.info("run {}: k6 завершился с кодом {}", run.id, code);
        scheduleCleanup(run);
    }

    private void scheduleCleanup(Run run) {
        // каталог run чистим через 10 минут, затем забываем run
        scheduler.schedule(() -> {
            runs.remove(run.id, run);
            deleteTree(run.dir);
        }, 10, TimeUnit.MINUTES);
    }

    // ------------------------------------------------------------------ утилиты

    /** Ближайший свободный порт на loopback, начиная с желаемого. */
    static int freePort(int preferred) throws IOException {
        // случайное смещение: несколько агентов на одном хосте (стенд) одновременно проверяют порты и не должны выбрать один и тот же
        int base = Math.max(1024, preferred) + java.util.concurrent.ThreadLocalRandom.current().nextInt(200);
        for (int port = base; port < base + 500; port++) {
            try (java.net.ServerSocket s = new java.net.ServerSocket()) {
                s.setReuseAddress(false);
                s.bind(new java.net.InetSocketAddress(java.net.InetAddress.getLoopbackAddress(), port));
                return port;
            } catch (IOException busy) {
                // занят - пробуем следующий
            }
        }
        throw new IOException("Не нашёлся свободный порт для REST API k6 начиная с " + preferred);
    }

    private static String sha256(Path file) throws IOException {
        try (InputStream in = Files.newInputStream(file)) {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) {
                md.update(buf, 0, n);
            }
            return HexFormat.of().formatHex(md.digest());
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void deleteTree(Path dir) {
        if (dir == null || !Files.exists(dir)) {
            return;
        }
        try (var walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        } catch (IOException ignored) {
            // best-effort
        }
    }

    /** Состояние одного run. */
    private final class Run {
        final String id;
        final Messages.Prepare prepare;
        final Path dir;
        volatile Path work;
        volatile AgentRunState state = AgentRunState.PREPARING;
        volatile Process process;
        volatile Map<String, String> env = Map.of();
        volatile Masker masker = new Masker(List.of());
        volatile List<String> preNotes = List.of();
        volatile LogBatcher logs;
        volatile ScheduledFuture<?> startTask;
        volatile ScheduledFuture<?> poller;
        volatile boolean cancelled;
        volatile boolean stopRequested;
        volatile boolean aborted;
        volatile Integer exitCode;
        volatile long startedAt;
        volatile int apiPort;
        private K6Api api;

        Run(Messages.Prepare p) {
            this.id = p.runId();
            this.prepare = p;
            this.dir = props.getHome().resolve("runs").resolve(p.runId()).toAbsolutePath();
        }

        K6Api api() {
            if (state != AgentRunState.RUNNING && state != AgentRunState.STOPPING) {
                throw new IllegalStateException("k6 не запущен (состояние " + state + ")");
            }
            if (api == null) {
                api = new K6Api(apiPort);
            }
            return api;
        }

        Long pid() {
            Process p = process;
            return p == null ? null : p.pid();
        }

        /** Graceful stop: SIGINT (k6 корректно завершает итерации и пушит метрики). */
        void stop() throws IOException {
            Process p = process;
            if (p == null || !p.isAlive()) {
                if (state == AgentRunState.PREPARED) {
                    // остановка до старта: просто отменяем
                    cancelled = true;
                    if (startTask != null) {
                        startTask.cancel(false);
                    }
                    state = AgentRunState.EXITED;
                    runs.remove(id);
                    deleteTree(dir);
                    // сервер ждёт EXIT каждого генератора: подтверждаем отмену до старта
                    events.emit(seq -> new Messages.Exit(seq, System.currentTimeMillis(), id, 0, "CANCELLED"));
                }
                return;
            }
            stopRequested = true;
            state = AgentRunState.STOPPING;
            events.emit(seq -> new Messages.Status(seq, System.currentTimeMillis(), id, AgentRunState.STOPPING, p.pid()));
            if (System.getProperty("os.name", "").toLowerCase().contains("win")) {
                p.destroy(); // dev-окружение на Windows: сигналов нет
                return;
            }
            try {
                Process kill = new ProcessBuilder("kill", "-INT", String.valueOf(p.pid())).start();
                if (!kill.waitFor(5, TimeUnit.SECONDS) || kill.exitValue() != 0) {
                    p.destroy();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (IOException e) {
                p.destroy(); // нет утилиты kill: SIGTERM тоже останавливает k6 корректно
            }
        }

        /** Abort: SIGKILL. */
        void abort() {
            aborted = true;
            killQuietly();
        }

        void killQuietly() {
            Process p = process;
            if (p != null && p.isAlive()) {
                p.destroyForcibly();
            }
        }
    }
}
