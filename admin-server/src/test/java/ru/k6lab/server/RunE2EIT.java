package ru.k6lab.server;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import ru.k6lab.core.runstate.RunStatus;
import ru.k6lab.core.util.ZipUtil;
import ru.k6lab.server.agent.AgentSessionRegistry;
import ru.k6lab.server.agent.TokenService;
import ru.k6lab.server.generator.Generator;
import ru.k6lab.server.generator.GeneratorRepository;
import ru.k6lab.server.load.LoadForm;
import ru.k6lab.server.load.LoadService;
import ru.k6lab.server.run.Run;
import ru.k6lab.server.run.RunGenerator;
import ru.k6lab.server.run.RunGeneratorRepository;
import ru.k6lab.server.run.RunOrchestrator;
import ru.k6lab.server.run.RunService;
import ru.k6lab.server.run.RunStep;
import ru.k6lab.server.script.Script;
import ru.k6lab.server.script.ScriptService;
import ru.k6lab.server.testdata.ScriptDataFile;
import ru.k6lab.server.script.ScriptVersion;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Сквозные тесты запуска: сервер + настоящие процессы агента (java -jar k6-agent.jar) + настоящий k6.
 * Проверяют PREPARE/START, синхронный старт, execution segments, нарезку данных, STOP/ABORT, очередь,
 * отказ генератора (DEGRADED), падение k6 и состояние БД после каждого сценария.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class RunE2EIT extends AbstractIT {

    static Path agentJar;
    /** Агент на Go (по умолчанию); Java-агент - при -Dk6lab.it.agent=java или если нет Go. */
    static Path goAgent;
    static Path k6;
    static final List<Process> processes = new ArrayList<>();

    @Autowired
    GeneratorRepository generators;
    @Autowired
    TokenService tokens;
    @Autowired
    AgentSessionRegistry registry;
    @Autowired
    ScriptService scripts;
    @Autowired
    LoadService load;
    @Autowired
    RunService runService;
    @Autowired
    RunOrchestrator orchestrator;
    @Autowired
    RunGeneratorRepository runGenerators;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    ru.k6lab.server.metrics.PrometheusDiagnostics diagnostics;
    @Autowired
    ru.k6lab.server.metrics.DiagnosticsGate diagnosticsGate;
    @Autowired
    ru.k6lab.server.settings.SettingsService settings;

    @BeforeAll
    static void prerequisites() {
        agentJar = Path.of("../admin-agent/target/k6-agent.jar").toAbsolutePath().normalize();
        goAgent = "java".equals(System.getProperty("k6lab.it.agent")) ? null : buildGoAgent();
        Assumptions.assumeTrue(goAgent != null || Files.isRegularFile(agentJar), "Не собран агент: " + agentJar);
        k6 = findK6();
        Assumptions.assumeTrue(k6 != null, "k6 не найден в PATH");
    }

    @AfterAll
    static void killAgents() {
        processes.forEach(Process::destroyForcibly);
    }

    /** Собирает агент на Go под текущую ОС (нужен Go в PATH); null, если Go нет или сборка не удалась. */
    private static Path buildGoAgent() {
        try {
            Path src = Path.of("../agent-go").toAbsolutePath().normalize();
            Path out = Path.of("target", System.getProperty("os.name").toLowerCase().contains("win") ? "k6-agent-go.exe" : "k6-agent-go").toAbsolutePath();
            Process p = new ProcessBuilder("go", "build", "-o", out.toString(), ".").directory(src.toFile()).redirectErrorStream(true).start();
            String log = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            if (p.waitFor() != 0) {
                System.err.println("Сборка Go-агента не удалась: " + log);
                return null;
            }
            return out;
        } catch (Exception e) {
            return null;
        }
    }

    private static Path findK6() {
        String path = System.getenv("PATH");
        if (path == null) {
            return null;
        }
        for (String dir : path.split(File.pathSeparator)) {
            for (String name : new String[]{"k6", "k6.exe"}) {
                Path p = Path.of(dir, name);
                if (Files.isRegularFile(p) && Files.isExecutable(p)) {
                    return p.toAbsolutePath();
                }
            }
        }
        return null;
    }

    // ---------------------------------------------------------------- инфраструктура теста

    record Agent(Generator generator, Process process) {
        long id() {
            return generator.getId();
        }
    }

    private Agent startAgent(String prefix, int maxRuns) throws IOException {
        Generator g = new Generator();
        g.setName(prefix + "-" + UUID.randomUUID().toString().substring(0, 6));
        g.setHost("127.0.0.1");
        g.setInstallDir("/opt/k6-agent");
        g.setServerUrl("ws://127.0.0.1:" + AGENT_PORT + "/agent/ws");
        g.setStatus(Generator.Status.INSTALLING);
        g.setMaxConcurrentRuns(maxRuns);
        g = generators.save(g);
        String token = tokens.issueOneTime(g.getId());
        Path home = Files.createTempDirectory("k6agent-" + g.getName());
        List<String> cmd = new ArrayList<>();
        if (goAgent != null) {
            cmd.add(goAgent.toString());
        } else {
            cmd.addAll(List.of(Path.of(System.getProperty("java.home"), "bin", "java").toString(), "-Xmx160m", "-jar", agentJar.toString()));
        }
        cmd.addAll(List.of("--agent.home=" + home, "--agent.server-url=ws://127.0.0.1:" + AGENT_PORT + "/agent/ws",
                "--agent.token=" + token, "--agent.agent-id=" + g.getName(), "--agent.k6-path=" + k6,
                "--agent.reconnect-min=500ms", "--agent.reconnect-max=2s"));
        ProcessBuilder pb = new ProcessBuilder(cmd)
                .redirectErrorStream(true).redirectOutput(home.resolve("agent.out").toFile());
        Process p = pb.start();
        processes.add(p);
        final long id = g.getId();
        await().atMost(Duration.ofSeconds(60)).until(() -> registry.isConnected(id)
                && generators.findById(id).orElseThrow().getOneTimeTokenHash() == null
                && registry.get(id).map(s -> s.clockOffsetMs() != null).orElse(false));
        return new Agent(generators.findById(id).orElseThrow(), p);
    }

    private static final String SCRIPT = """
            import { sleep } from 'k6';
            import { op } from './__admin/admin.js';
            const rows = open('./data/users.csv').split('\\n').filter((l) => l.length > 0);
            export function setup() { console.log('DATA-ROWS:' + rows.length + ' first=' + rows[0]); }
            export function work() { op(() => { sleep(0.05); }); }
            export default function () {}
            """;

    private ScriptVersion script(String name) throws Exception {
        Map<String, byte[]> files = new LinkedHashMap<>();
        files.put("main.js", SCRIPT.getBytes(StandardCharsets.UTF_8));
        StringBuilder csv = new StringBuilder("login,pwd\n");
        for (int i = 1; i <= 20; i++) {
            csv.append("u").append(i).append(",p\n");
        }
        files.put("data/users.csv", csv.toString().getBytes(StandardCharsets.UTF_8));
        return scripts.createFromUpload(name, "", "bundle.zip", new ByteArrayInputStream(ZipUtil.write(files)), "main.js", null);
    }

    /** Скрипт с описанной нагрузкой: одна операция work, 10 оп/с на 100% длительностью holdSec. */
    private Script scriptWithLoad(String name, int holdSec, boolean split, String execFn) throws Exception {
        ScriptVersion v = script("scr-" + name);
        if (!execFn.equals("work")) {
            scripts.addOperation(v.getScriptId(), v.getId(), execFn);
        }
        LoadForm f = new LoadForm();
        LoadForm.OpForm o = new LoadForm.OpForm();
        o.setName("work");
        o.setExecFn(execFn);
        o.setRatePerSec(10.0);
        o.setExpectedDurationSec(0.1);
        o.setSlaThresholdMs(500.0);
        f.getOps().add(o);
        f.setLevelPct(100);
        f.setRampSec(0);
        f.setHoldMinutes(holdSec / 60.0);
        f.setEnvText("BASE=http://localhost");
        if (split) {
            LoadForm.DataForm d = new LoadForm.DataForm();
            d.setPath("data/users.csv");
            d.setMode(ScriptDataFile.Mode.SPLIT);
            d.setHasHeader(true);
            f.getDataFiles().add(d);
        }
        load.save(v.getScriptId(), f);
        return scripts.get(v.getScriptId());
    }

    private Run startRun(Script s, Run.FailurePolicy policy, RunService.GeneratorSel... gens) throws Exception {
        return runService.create(new RunService.CreateRequest(s.getId(), List.of(gens), policy, 90));
    }

    private Run awaitStatus(long runId, Duration timeout, RunStatus... statuses) {
        try {
            await().atMost(timeout).pollInterval(Duration.ofMillis(500)).until(() -> {
                RunStatus s = runService.get(runId).getStatus();
                for (RunStatus x : statuses) {
                    if (s == x) {
                        return true;
                    }
                }
                return false;
            });
        } catch (org.awaitility.core.ConditionTimeoutException e) {
            throw new AssertionError("Run " + runId + " остался в статусе " + runService.get(runId).getStatus() + ", лог: " + dump(runId), e);
        }
        return runService.get(runId);
    }

    private static long finalIterations(RunGenerator g) {
        if (g.getSummary() != null && g.getSummary().path("metrics").path("iterations").has("count")) {
            return g.getSummary().path("metrics").path("iterations").path("count").asLong();
        }
        return g.getLastIterations() == null ? 0 : g.getLastIterations();
    }

    private String dump(long runId) {
        return jdbc.queryForList("select '[' || level || '] ' || line from run_log where run_id = ? order by ts, id", String.class, runId)
                .toString();
    }

    private List<String> logLines(long runId) {
        return jdbc.queryForList("select line from run_log where run_id = ? order by ts, id", String.class, runId);
    }

    // ---------------------------------------------------------------- сценарии

    @Test
    @Order(1)
    void singleGeneratorRunGoesThroughAllStatesAndProducesResults() throws Exception {
        Agent a = startAgent("e2e1", 1);
        Script s = scriptWithLoad("single", 12, false, "work");
        Run run = startRun(s, Run.FailurePolicy.CONTINUE, new RunService.GeneratorSel(a.id(), 1));
        assertThat(run.getStatus()).isEqualTo(RunStatus.QUEUED);
        long id = run.getId();

        awaitStatus(id, Duration.ofSeconds(30), RunStatus.PREPARING, RunStatus.RUNNING);
        // агент не занят чем-то ещё: генератор захвачен под run
        assertThat(generators.findById(a.id()).orElseThrow().getActiveRuns()).isEqualTo(1);
        awaitStatus(id, Duration.ofSeconds(40), RunStatus.RUNNING);
        Run finished = awaitStatus(id, Duration.ofSeconds(90), RunStatus.FINISHED, RunStatus.FAILED);
        assertThat(finished.getStatus()).as(dump(id)).isEqualTo(RunStatus.FINISHED);
        assertThat(finished.isThresholdsFailed()).isFalse();
        assertThat(finished.isDegraded()).isFalse();
        assertThat(finished.getPlannedStartAt()).isNotNull();
        assertThat(finished.getStartedAt()).isNotNull();
        assertThat(finished.getFinishedAt()).isAfter(finished.getStartedAt());

        // генератор освобождён
        await().atMost(Duration.ofSeconds(10)).until(() -> generators.findById(a.id()).orElseThrow().getActiveRuns() == 0);

        // run_generator: код 0, сегмент 0:1, summary получен
        RunGenerator rg = runService.generatorsOf(id).get(0);
        assertThat(rg.getExitCode()).isZero();
        assertThat(rg.segment()).isEqualTo("0:1");
        assertThat(rg.getStatus()).isEqualTo(RunGenerator.Status.EXITED);
        await().atMost(Duration.ofSeconds(10)).until(() -> runService.generatorsOf(id).get(0).getSummary() != null);
        assertThat(runService.generatorsOf(id).get(0).getSummary().has("metrics")).isTrue();

        // ступени с интервалами удержания
        List<RunStep> steps = runService.stepsOf(id);
        assertThat(steps).hasSize(1);
        assertThat(steps.get(0).getHoldEndAt()).isEqualTo(steps.get(0).getHoldStartAt().plusSeconds(12));
        assertThat(steps.get(0).getRampStartAt()).isEqualTo(finished.getPlannedStartAt());

        // snapshot сохранён целиком, конфиг тоже
        var snap = runService.snapshot(finished);
        assertThat(snap.operations()).extracting(o -> o.name()).containsExactly("work");
        assertThat(snap.steps()).hasSize(1);
        assertThat(snap.generators()).hasSize(1);
        assertThat(snap.segmentSequence()).isEqualTo("0,1");
        assertThat(finished.getK6Config().path("scenarios").path("work").path("exec").asText()).isEqualTo("work");

        // логи записаны батчами в БД (в т.ч. строка из setup() скрипта)
        await().atMost(Duration.ofSeconds(15)).until(() -> logLines(id).stream().anyMatch(l -> l.contains("DATA-ROWS:21 first=login,pwd")));
        await().atMost(Duration.ofSeconds(15)).until(() -> logLines(id).stream().anyMatch(l -> l.contains("Run завершён: FINISHED")));
        assertThat(logLines(id)).anyMatch(l -> l.contains("Тест запущен"));
        assertThat(orchestrator.isActive(id)).isFalse();
    }

    @Test
    @Order(9)
    void k6FlagsFromRunRequestReachK6OnTheAgent() throws Exception {
        Agent a = startAgent("e2eflags", 1);
        Script s = scriptWithLoad("flags", 8, false, "work");
        Run run = runService.create(new RunService.CreateRequest(s.getId(), List.of(new RunService.GeneratorSel(a.id(), 1)),
                Run.FailurePolicy.CONTINUE, 90, List.of(ru.k6lab.server.run.K6Flag.VERBOSE, ru.k6lab.server.run.K6Flag.NO_THRESHOLDS)));
        long id = run.getId();
        assertThat(run.getRequest().get("k6Flags")).isEqualTo(List.of("VERBOSE", "NO_THRESHOLDS"));
        Run finished = awaitStatus(id, Duration.ofSeconds(120), RunStatus.FINISHED, RunStatus.FAILED);
        assertThat(finished.getStatus()).as(dump(id)).isEqualTo(RunStatus.FINISHED);
        // --verbose включил debug-уровень k6: такие строки есть в логе запуска только с этим флагом
        await().atMost(Duration.ofSeconds(15)).until(() -> logLines(id).stream().anyMatch(l -> l.contains("level=debug")));
    }

    @Test
    @Order(10)
    void defaultFunctionRunsAsOperationWithoutExecField() throws Exception {
        Agent a = startAgent("e2edefault", 1);
        Script s = scriptWithLoad("default-op", 8, false, "default");
        Run run = startRun(s, Run.FailurePolicy.CONTINUE, new RunService.GeneratorSel(a.id(), 1));
        assertThat(run.getK6Config().path("scenarios").path("work").has("exec")).isFalse();
        Run finished = awaitStatus(run.getId(), Duration.ofSeconds(120), RunStatus.FINISHED, RunStatus.FAILED);
        assertThat(finished.getStatus()).as(dump(run.getId())).isEqualTo(RunStatus.FINISHED);
        assertThat(runService.generatorsOf(run.getId()).get(0).getExitCode()).isZero();
    }

    @Test
    @Order(11)
    void scenariosWithOtherExecutorsRunInRealK6NextToTheProfileOne() throws Exception {
        Agent a = startAgent("e2eexec", 1);
        Script s = scriptWithLoad("executors", 10, false, "work");
        LoadForm f = load.toForm(s.getId());
        LoadForm.OpForm soak = new LoadForm.OpForm();
        soak.setName("soak");
        soak.setExecFn("work");
        soak.setExecutor("constant-vus");
        soak.setScenarioJson("{\"vus\": 1, \"duration\": \"6s\", \"gracefulStop\": \"2s\"}");
        soak.setSlaThresholdMs(500.0);
        LoadForm.OpForm once = new LoadForm.OpForm();
        once.setName("once");
        once.setExecFn("work");
        once.setExecutor("shared-iterations");
        once.setScenarioJson("{\"vus\": 1, \"iterations\": 3, \"maxDuration\": \"20s\"}");
        once.setSlaThresholdMs(500.0);
        f.getOps().removeIf(o -> !o.getName().equals("work"));
        f.getOps().add(soak);
        f.getOps().add(once);
        load.save(s.getId(), f);
        Run run = startRun(s, Run.FailurePolicy.CONTINUE, new RunService.GeneratorSel(a.id(), 1));
        var scenarios = run.getK6Config().path("scenarios");
        assertThat(scenarios.path("soak").path("executor").asText()).isEqualTo("constant-vus");
        assertThat(scenarios.path("once").path("executor").asText()).isEqualTo("shared-iterations");
        assertThat(scenarios.path("work").path("executor").asText()).isEqualTo("ramping-arrival-rate");
        Run finished = awaitStatus(run.getId(), Duration.ofSeconds(120), RunStatus.FINISHED, RunStatus.FAILED);
        assertThat(finished.getStatus()).as(dump(run.getId())).isEqualTo(RunStatus.FINISHED);
        var g = runService.generatorsOf(run.getId()).get(0);
        assertThat(g.getExitCode()).isZero();
        // все три сценария выполнились: по каждому есть свой подмножество метрик в итоговой сводке k6
        var metrics = g.getSummary().path("metrics");
        for (String sc : new String[]{"work", "soak", "once"}) {
            assertThat(metrics.has("op_duration{scenario:" + sc + "}"))
                    .as("сценарий " + sc + " в сводке: " + metrics.properties().stream().map(java.util.Map.Entry::getKey).toList()).isTrue();
        }
    }

    @Test
    @Order(2)
    void twoGeneratorsShareLoadByWeightsAndSplitData() throws Exception {
        Agent a = startAgent("e2e2a", 1);
        Agent b = startAgent("e2e2b", 1);
        Script s = scriptWithLoad("two", 14, true, "work");
        Run run = startRun(s, Run.FailurePolicy.CONTINUE, new RunService.GeneratorSel(a.id(), 1), new RunService.GeneratorSel(b.id(), 3));
        long id = run.getId();
        Run finished = awaitStatus(id, Duration.ofSeconds(120), RunStatus.FINISHED, RunStatus.FAILED);
        assertThat(finished.getStatus()).as(dump(id)).isEqualTo(RunStatus.FINISHED);

        List<RunGenerator> rgs = runService.generatorsOf(id);
        assertThat(rgs).extracting(RunGenerator::segment).containsExactly("0:1/4", "1/4:1");
        assertThat(runService.snapshot(finished).segmentSequence()).isEqualTo("0,1/4,1");
        assertThat(rgs).allMatch(x -> x.getExitCode() != null && x.getExitCode() == 0);

        // данные нарезаны: 20 строк -> 5 и 15 (плюс заголовок), без пересечений
        await().atMost(Duration.ofSeconds(45)).until(() -> logLines(id).stream().filter(l -> l.contains("DATA-ROWS:")).count() >= 2);
        List<String> dataLines = logLines(id).stream().filter(l -> l.contains("DATA-ROWS:")).toList();
        assertThat(dataLines).anyMatch(l -> l.contains("DATA-ROWS:6 first=login,pwd"));   // 5 строк + заголовок
        assertThat(dataLines).anyMatch(l -> l.contains("DATA-ROWS:16 first=login,pwd"));  // 15 строк + заголовок

        // интенсивность поделена по весам 1:3: итерации ~ 25% и 75% от 10 оп/с * 14 с = 140
        await().atMost(Duration.ofSeconds(15)).until(() -> runService.generatorsOf(id).stream().allMatch(x -> x.getLastIterations() != null && x.getLastIterations() > 0));
        // итоговые итерации - из summary k6 (последний опрос REST API отстаёт на разное время у разных генераторов)
        long it1 = finalIterations(runService.generatorsOf(id).get(0));
        long it2 = finalIterations(runService.generatorsOf(id).get(1));
        assertThat(it1 + it2).isBetween(100L, 160L);
        // точные доли проверяют юнит-тесты сегментов; здесь важно, что нагрузка делится в сторону большего веса (на коротком прогоне есть разброс старта)
        assertThat((double) it2 / Math.max(1, it1)).isBetween(1.5, 4.5);
        assertThat(finished.isDegraded()).isFalse();
    }

    @Test
    @Order(3)
    void stopIsGracefulAbortIsForcedAndBothReleaseGenerators() throws Exception {
        Agent a = startAgent("e2e3", 1);
        Script s = scriptWithLoad("stop", 300, false, "work");
        var sel = new RunService.GeneratorSel(a.id(), 1);

        Run r1 = startRun(s, Run.FailurePolicy.CONTINUE, sel);
        awaitStatus(r1.getId(), Duration.ofSeconds(60), RunStatus.RUNNING);
        await().atMost(Duration.ofSeconds(20)).until(() -> runService.generatorsOf(r1.getId()).get(0).getStatus() == RunGenerator.Status.RUNNING);
        orchestrator.stop(r1.getId(), "остановлено пользователем");
        assertThat(runService.get(r1.getId()).getStatus()).isEqualTo(RunStatus.STOPPING);
        Run stopped = awaitStatus(r1.getId(), Duration.ofSeconds(90), RunStatus.FINISHED, RunStatus.FAILED, RunStatus.ABORTED);
        assertThat(stopped.getStatus()).as(dump(r1.getId())).isEqualTo(RunStatus.FINISHED);
        assertThat(stopped.getStopReason()).isEqualTo("остановлено пользователем");
        assertThat(stopped.getFinishedAt()).isBefore(stopped.getStartedAt().plusSeconds(120));
        await().atMost(Duration.ofSeconds(10)).until(() -> generators.findById(a.id()).orElseThrow().getActiveRuns() == 0);

        Run r2 = startRun(s, Run.FailurePolicy.CONTINUE, sel);
        awaitStatus(r2.getId(), Duration.ofSeconds(60), RunStatus.RUNNING);
        await().atMost(Duration.ofSeconds(20)).until(() -> runService.generatorsOf(r2.getId()).get(0).getStatus() == RunGenerator.Status.RUNNING);
        orchestrator.abort(r2.getId());
        Run aborted = awaitStatus(r2.getId(), Duration.ofSeconds(60), RunStatus.ABORTED, RunStatus.FINISHED, RunStatus.FAILED);
        assertThat(aborted.getStatus()).as(dump(r2.getId())).isEqualTo(RunStatus.ABORTED);
        await().atMost(Duration.ofSeconds(10)).until(() -> generators.findById(a.id()).orElseThrow().getActiveRuns() == 0);
    }

    @Test
    @Order(4)
    void runWaitsInQueueUntilGeneratorIsFree() throws Exception {
        Agent a = startAgent("e2e4", 1);
        Script s = scriptWithLoad("queue", 300, false, "work");
        var sel = new RunService.GeneratorSel(a.id(), 1);
        Run first = startRun(s, Run.FailurePolicy.CONTINUE, sel);
        awaitStatus(first.getId(), Duration.ofSeconds(60), RunStatus.RUNNING);

        Run second = startRun(s, Run.FailurePolicy.CONTINUE, sel);
        Thread.sleep(6000); // несколько тактов диспетчера
        assertThat(runService.get(second.getId()).getStatus()).isEqualTo(RunStatus.QUEUED);
        assertThat(generators.findById(a.id()).orElseThrow().getActiveRuns()).isEqualTo(1);

        orchestrator.abort(first.getId());
        awaitStatus(first.getId(), Duration.ofSeconds(60), RunStatus.ABORTED, RunStatus.FINISHED);
        // освободившийся генератор берёт следующий run из очереди
        awaitStatus(second.getId(), Duration.ofSeconds(60), RunStatus.PREPARING, RunStatus.RUNNING);
        assertThat(runService.generatorsOf(second.getId())).hasSize(1);
        orchestrator.abort(second.getId());
        awaitStatus(second.getId(), Duration.ofSeconds(60), RunStatus.ABORTED, RunStatus.FINISHED);

        // отмена run в очереди без запуска
        Run third = startRun(s, Run.FailurePolicy.CONTINUE, sel);
        Run fourth = startRun(s, Run.FailurePolicy.CONTINUE, sel);
        awaitStatus(third.getId(), Duration.ofSeconds(60), RunStatus.RUNNING);
        orchestrator.stop(fourth.getId(), "отменён в очереди");
        assertThat(runService.get(fourth.getId()).getStatus()).isEqualTo(RunStatus.ABORTED);
        orchestrator.abort(third.getId());
        awaitStatus(third.getId(), Duration.ofSeconds(60), RunStatus.ABORTED, RunStatus.FINISHED);
    }

    @Test
    @Order(5)
    void lostGeneratorMakesRunDegradedAndPolicyIsApplied() throws Exception {
        Agent a = startAgent("e2e5a", 1);
        Agent b = startAgent("e2e5b", 1);
        Script s = scriptWithLoad("degraded", 25, false, "work");

        // политика CONTINUE: отвал одного генератора -> DEGRADED, тест продолжается на оставшемся
        Run run = startRun(s, Run.FailurePolicy.CONTINUE, new RunService.GeneratorSel(a.id(), 1), new RunService.GeneratorSel(b.id(), 1));
        awaitStatus(run.getId(), Duration.ofSeconds(60), RunStatus.RUNNING);
        await().atMost(Duration.ofSeconds(20)).until(() -> runService.generatorsOf(run.getId()).stream().allMatch(x -> x.getStatus() == RunGenerator.Status.RUNNING));
        b.process().destroyForcibly();
        await().atMost(Duration.ofSeconds(30)).until(() -> runService.get(run.getId()).isDegraded());
        assertThat(runService.get(run.getId()).getStatus()).isEqualTo(RunStatus.RUNNING);
        List<RunGenerator.Interval> offline = runService.generatorsOf(run.getId()).get(1).getOfflineIntervals();
        assertThat(offline).hasSize(1);
        assertThat(offline.get(0).to()).isNull(); // не вернулся
        Run done = awaitStatus(run.getId(), Duration.ofSeconds(150), RunStatus.FINISHED, RunStatus.FAILED, RunStatus.ABORTED);
        assertThat(done.getStatus()).as(dump(run.getId())).isEqualTo(RunStatus.FINISHED);
        assertThat(done.isDegraded()).isTrue();
        assertThat(runService.generatorsOf(run.getId()).get(0).getExitCode()).isZero();
        assertThat(runService.generatorsOf(run.getId()).get(1).getStatus()).isEqualTo(RunGenerator.Status.LOST);
        assertThat(logLines(run.getId())).anyMatch(l -> l.contains("отвалился (DEGRADED)"));

        // политика STOP_ALL: тот же отвал останавливает всех
        Agent c = startAgent("e2e5c", 1);
        Agent d = startAgent("e2e5d", 1);
        Script long1 = scriptWithLoad("degraded2", 300, false, "work");
        Run r2 = startRun(long1, Run.FailurePolicy.STOP_ALL, new RunService.GeneratorSel(c.id(), 1), new RunService.GeneratorSel(d.id(), 1));
        awaitStatus(r2.getId(), Duration.ofSeconds(60), RunStatus.RUNNING);
        await().atMost(Duration.ofSeconds(20)).until(() -> runService.generatorsOf(r2.getId()).stream().allMatch(x -> x.getStatus() == RunGenerator.Status.RUNNING));
        d.process().destroyForcibly();
        Run stopped = awaitStatus(r2.getId(), Duration.ofSeconds(150), RunStatus.FINISHED, RunStatus.FAILED, RunStatus.ABORTED);
        assertThat(stopped.getStatus()).as(dump(r2.getId())).isEqualTo(RunStatus.FINISHED);
        assertThat(stopped.getStopReason()).contains("отвал генератора").contains("остановить все");
        assertThat(stopped.isDegraded()).isTrue();
        assertThat(runService.generatorsOf(r2.getId()).get(0).getExitCode()).isNotNull();
    }

    @Test
    @Order(6)
    void k6CrashMakesRunFailedAndUnknownFunctionIsReported() throws Exception {
        Agent a = startAgent("e2e6", 1);
        // exec-функция есть в модели (добавлена вручную), но в скрипте её нет: k6 завершится с ошибкой
        Script s = scriptWithLoad("crash", 10, false, "noSuchFunction");
        Run run = startRun(s, Run.FailurePolicy.CONTINUE, new RunService.GeneratorSel(a.id(), 1));
        Run done = awaitStatus(run.getId(), Duration.ofSeconds(90), RunStatus.FINISHED, RunStatus.FAILED);
        assertThat(done.getStatus()).as(dump(run.getId())).isEqualTo(RunStatus.FAILED);
        assertThat(done.getError()).contains("аварийно");
        assertThat(runService.generatorsOf(run.getId()).get(0).getExitCode()).isNotZero().isNotEqualTo(99);
        await().atMost(Duration.ofSeconds(10)).until(() -> generators.findById(a.id()).orElseThrow().getActiveRuns() == 0);
    }

    @Test
    @Order(7)
    void sendingStartWithoutGeneratorsOrWithBadSelectionIsRejected() throws Exception {
        Script s = scriptWithLoad("reject", 5, false, "work");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> runService.create(new RunService.CreateRequest(s.getId(), List.of(), null, null)))
                .hasMessageContaining("генераторы");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> runService.create(new RunService.CreateRequest(s.getId(),
                        List.of(new RunService.GeneratorSel(999999L, 1)), null, null)))
                .hasMessageContaining("не найден");
        Agent a = startAgent("e2e7", 1);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> runService.create(new RunService.CreateRequest(s.getId(),
                        List.of(new RunService.GeneratorSel(a.id(), 1), new RunService.GeneratorSel(a.id(), 1)), null, null)))
                .hasMessageContaining("дважды");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> runService.create(new RunService.CreateRequest(s.getId(),
                        List.of(new RunService.GeneratorSel(a.id(), 0)), null, null)))
                .hasMessageContaining("Вес");
    }

    @Test
    @Order(7)
    void prometheusDiagnosticsRunsSmokeTestOnGeneratorAndGatesRuns() throws Exception {
        Agent a = startAgent("e2e7", 1);
        try (FakePrometheus prom = new FakePrometheus()) {
            // Prometheus принимает remote write: серии с тегом есть, histogram_quantile по настроенному имени отвечает
            prom.respond((q, t) -> {
                if (q.startsWith("count by (__name__)")) {
                    return Map.of("k6_iterations_total", 1.0, "k6_iteration_duration_seconds", 1.0);
                }
                return q.contains("histogram_quantile") && q.contains("k6_iteration_duration_seconds") ? Map.of("", 12.0) : Map.of();
            });
            settings.savePrometheus(prom.rwUrl(), prom.baseUrl(), ru.k6lab.server.settings.SettingsService.AuthType.NONE, null, null, "");
            assertThat(diagnostics.passed()).isFalse();
            var res = diagnostics.runSync(a.id());
            assertThat(res.steps()).as(res.summary()).allMatch(ru.k6lab.server.metrics.PrometheusDiagnostics.Step::ok);
            assertThat(res.ok()).isTrue();
            assertThat(diagnostics.passed()).isTrue();
            assertThat(prom.queries).anyMatch(q -> q.startsWith("histogram_quantile(0.95"));

            // смена параметров делает прежний успех недействительным
            settings.savePrometheus(prom.rwUrl(), prom.baseUrl() + "/", ru.k6lab.server.settings.SettingsService.AuthType.NONE, null, null, "");
            assertThat(diagnostics.passed()).isFalse();

            // режим native histograms: метрики приходят, но гистограмм нет (Prometheus без флага): понятная причина, а не «метрик нет»
            settings.set(ru.k6lab.server.settings.SettingsService.HISTOGRAM_MODE, "NATIVE");
            prom.respond((q, t) -> q.startsWith("count by (__name__)") ? Map.of("k6_iterations_total", 1.0, "k6_http_reqs_total", 1.0) : Map.of());
            var bad = diagnostics.runSync(a.id());
            assertThat(bad.ok()).isFalse();
            assertThat(bad.summary()).contains("native-histograms");
            assertThat(bad.steps().get(bad.steps().size() - 2).ok()).as("шаг про метрики должен быть успешным").isTrue();
            assertThat(diagnostics.passed()).isFalse();

            // гистограмма приходит под другим именем: подсказываем исправить имена метрик
            prom.respond((q, t) -> {
                if (q.startsWith("count by (__name__)")) {
                    return Map.of("k6_iterations_total", 1.0, "k6_iteration_duration", 1.0);
                }
                return q.contains("histogram_quantile") && q.contains("k6_iteration_duration{") ? Map.of("", 7.0) : Map.of();
            });
            var renamed = diagnostics.runSync(a.id());
            assertThat(renamed.ok()).isFalse();
            assertThat(renamed.summary()).contains("k6_iteration_duration").contains("Имена метрик");

            // классический режим: метрики приходят, корзин нет (агент без поддержки режима) - подсказка про агента
            settings.set(ru.k6lab.server.settings.SettingsService.HISTOGRAM_MODE, "CLASSIC");
            prom.respond((q, t) -> q.startsWith("count by (__name__)") ? Map.of("k6_iterations_total", 1.0) : Map.of());
            var noBuckets = diagnostics.runSync(a.id());
            assertThat(noBuckets.ok()).isFalse();
            assertThat(noBuckets.summary()).contains("_bucket").contains("агент");

            // ни одной серии: метрики не доходят вовсе
            prom.respond((q, t) -> Map.of());
            var none = diagnostics.runSync(a.id());
            assertThat(none.ok()).isFalse();
            assertThat(none.summary()).contains("Ни одной серии");
        }
    }
}
