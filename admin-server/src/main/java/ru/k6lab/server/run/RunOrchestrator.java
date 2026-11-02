package ru.k6lab.server.run;

import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import ru.k6lab.core.k6config.ConfigInput;
import ru.k6lab.core.profile.StepWindow;
import ru.k6lab.core.protocol.Message;
import ru.k6lab.core.protocol.Messages;
import ru.k6lab.core.runstate.RunStatus;
import ru.k6lab.server.agent.AgentEventListener;
import ru.k6lab.server.agent.AgentLifecycleEvents;
import ru.k6lab.server.agent.AgentSession;
import ru.k6lab.server.agent.AgentSessionRegistry;
import ru.k6lab.server.common.Hashes;
import ru.k6lab.server.generator.Generator;
import ru.k6lab.server.scenario.SecretService;
import ru.k6lab.server.settings.SettingsService;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Исполнение run: PREPARE всем генераторам -> ожидание PREPARED (таймаут) -> проверка часов (расхождение > 500 мс
 * блокирует запуск) -> START с общим start_at = now + 10 с -> RUNNING -> EXIT всех -> FINISHED/FAILED/ABORTED.
 * Отвал генератора = DEGRADED + failure policy. Состояние восстанавливается из БД и сверкой с агентами после рестарта.
 * Управление тестами - только через WebSocket агентов (SSH тут не участвует).
 */
@Service
public class RunOrchestrator implements AgentEventListener {

    private static final Logger log = LoggerFactory.getLogger(RunOrchestrator.class);
    public static final long START_DELAY_MS = 10_000;
    public static final long MAX_CLOCK_SKEW_MS = 500;
    private static final long PERSIST_EVERY_MS = 10_000;

    /** Сколько ждать вернувшегося генератора после планового окончания теста, прежде чем объявить его потерянным. */
    @org.springframework.beans.factory.annotation.Value("${k6lab.run.lost-grace-sec:180}")
    private long lostGraceSec;

    /** Состояние генератора в run (в памяти; ключевые поля дублируются в run_generator). */
    static final class GenCtx {
        long rgId;
        long genId;
        String name;
        int index;
        RunGenerator.Status status;
        Integer exitCode;
        long vus;
        long vusMax;
        long iterations;
        long dropped;
        String k6Status = "";
        long lastPersist;
        boolean offline;
        Instant offlineSince;
        boolean exitSeen;
    }

    static final class Ctx {
        long runId;
        String scenarioName;
        Run.FailurePolicy policy;
        int gracefulStopSec;
        int totalSec;
        boolean externallyControlled;
        boolean startSent;
        boolean stopRequested;
        boolean abortRequested;
        boolean finalized;
        String stopReason;
        Instant plannedStart;
        Instant stopRequestedAt;
        final Map<Long, GenCtx> gens = new LinkedHashMap<>();
        ScheduledFuture<?> prepareTimeout;
        ScheduledFuture<?> startTimer;
        ScheduledFuture<?> escalation;
    }

    private record CmdRef(long runId, long generatorId, String kind) {
    }

    private final RunService runService;
    private final RunReservation reservation;
    private final RunArtifactService artifacts;
    private final AgentSessionRegistry registry;
    private final RunLogService logs;
    private final RunEventBroadcaster broadcaster;
    private final SettingsService settings;
    private final SecretService secrets;
    private final ApplicationEventPublisher publisher;

    private final Map<Long, Ctx> contexts = new ConcurrentHashMap<>();
    private final Map<String, CmdRef> pendingCmds = new ConcurrentHashMap<>();
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(4, r -> {
        Thread t = new Thread(r, "run-orchestrator");
        t.setDaemon(true);
        return t;
    });

    public RunOrchestrator(RunService runService, RunReservation reservation, RunArtifactService artifacts,
                           AgentSessionRegistry registry, RunLogService logs, RunEventBroadcaster broadcaster,
                           SettingsService settings, SecretService secrets, ApplicationEventPublisher publisher) {
        this.runService = runService;
        this.reservation = reservation;
        this.artifacts = artifacts;
        this.registry = registry;
        this.logs = logs;
        this.broadcaster = broadcaster;
        this.settings = settings;
        this.secrets = secrets;
        this.publisher = publisher;
    }

    @PreDestroy
    void shutdown() {
        scheduler.shutdownNow();
    }

    /** Результат завершения run - для анализа результатов и отчётов. */
    public record RunFinished(long runId, RunStatus status) {
    }

    // ================================================================== запуск

    /** Начало исполнения захваченного run (статус уже PREPARING). */
    public void begin(Long runId) {
        Run run = runService.get(runId);
        RunSnapshot snap = runService.snapshot(run);
        Ctx ctx = newCtx(run, snap);
        for (RunGenerator rg : runService.generatorsOf(runId)) {
            ctx.gens.put(rg.getGeneratorId(), genCtx(rg));
        }
        contexts.put(runId, ctx);
        MDC.put("run_id", String.valueOf(runId));
        try {
            logs.addSystem(runId, "INFO", "Run " + runId + ": подготовка на " + ctx.gens.size() + " генераторах");
            Map<String, String> secretValues = secrets.resolve(SecretService.referencedNames(snap.scenario().env()));
            RunArtifactService.Artifact artifact = artifacts.forVersion(runService.versionOf(run));
            for (RunGenerator rg : runService.generatorsOf(runId)) {
                sendPrepare(ctx, run, snap, rg, artifact, secretValues);
            }
            ctx.prepareTimeout = scheduler.schedule(() -> onPrepareTimeout(runId), run.getPrepareTimeoutSec(), TimeUnit.SECONDS);
        } catch (Exception e) {
            log.error("Run {}: не удалось отправить PREPARE", runId, e);
            fail(ctx, "Не удалось подготовить запуск: " + e.getMessage());
        } finally {
            MDC.remove("run_id");
        }
        broadcaster.publishStatus(runId, RunStatus.PREPARING.name());
    }

    private Ctx newCtx(Run run, RunSnapshot snap) {
        Ctx ctx = new Ctx();
        ctx.runId = run.getId();
        ctx.scenarioName = run.getScenarioName();
        ctx.policy = run.getFailurePolicy();
        ctx.gracefulStopSec = snap.profile().gracefulStopSec();
        ctx.totalSec = snap.steps().stream().mapToInt(s -> s.rampSec() + s.holdSec()).sum();
        ctx.externallyControlled = run.getK6Config() != null && run.getK6Config().toString().contains("externally-controlled");
        ctx.plannedStart = run.getPlannedStartAt();
        ctx.startSent = run.getPlannedStartAt() != null;
        ctx.stopRequested = run.getStatus() == RunStatus.STOPPING;
        ctx.stopReason = run.getStopReason();
        return ctx;
    }

    private GenCtx genCtx(RunGenerator rg) {
        GenCtx g = new GenCtx();
        g.rgId = rg.getId();
        g.genId = rg.getGeneratorId();
        g.name = rg.getGeneratorName();
        g.index = rg.getSeqIndex();
        g.status = rg.getStatus();
        g.exitCode = rg.getExitCode();
        g.vus = rg.getLastVus() == null ? 0 : rg.getLastVus();
        g.iterations = rg.getLastIterations() == null ? 0 : rg.getLastIterations();
        g.dropped = rg.getLastDropped() == null ? 0 : rg.getLastDropped();
        return g;
    }

    private void sendPrepare(Ctx ctx, Run run, RunSnapshot snap, RunGenerator rg, RunArtifactService.Artifact artifact,
                             Map<String, String> secretValues) {
        Generator gen = runService.generator(rg.getGeneratorId());
        AgentSession session = registry.get(rg.getGeneratorId()).orElseThrow(
                () -> new IllegalStateException("Генератор " + rg.getGeneratorName() + " не подключён"));
        String token = Hashes.randomToken();
        runService.updateGenerator(rg.getId(), g -> {
            g.setArtifactTokenHash(Hashes.sha256Hex(token));
            g.setStatus(RunGenerator.Status.PREPARING);
        });
        ctx.gens.get(rg.getGeneratorId()).status = RunGenerator.Status.PREPARING;

        Map<String, String> tags = new LinkedHashMap<>();
        tags.put("test_run_id", String.valueOf(run.getId()));
        tags.put("generator", rg.getGeneratorName());
        tags.put("test_scenario", run.getScenarioName());
        List<Messages.DataRule> rules = snap.dataFiles().stream()
                .map(d -> new Messages.DataRule(d.path(), d.mode(), d.hasHeader())).toList();
        SettingsService.Prometheus prom = settings.prometheus();
        Messages.PrometheusParams promParams = prom.configured()
                ? new Messages.PrometheusParams(prom.rwUrl(), prom.authHeaders(), "5s", true) : null;
        String cmdId = UUID.randomUUID().toString();
        pendingCmds.put(cmdId, new CmdRef(run.getId(), rg.getGeneratorId(), "PREPARE"));
        session.send(new Messages.Prepare(cmdId, String.valueOf(run.getId()), artifactUrl(gen.getServerUrl(), run.getId(), token),
                artifact.sha256(), run.getK6Config(), snap.scenario().env(), secretValues, rg.segment(), snap.segmentSequence(),
                tags, rules, promParams, rg.getK6ApiPort(), ConfigInput.SYSTEM_TAGS, snap.script().entrypoint(),
                settings.logLinesPerSecond()));
    }

    /** ws://host:port/agent/ws -> http://host:port/agent/artifact/{run}/{token}: агент качает архив с тем же сервером. */
    static String artifactUrl(String serverUrl, long runId, String token) {
        URI u = URI.create(serverUrl);
        String scheme = "wss".equals(u.getScheme()) ? "https" : "http";
        return scheme + "://" + u.getHost() + ":" + u.getPort() + "/agent/artifact/" + runId + "/" + token;
    }

    private void onPrepareTimeout(long runId) {
        Ctx ctx = contexts.get(runId);
        if (ctx == null) {
            return;
        }
        synchronized (ctx) {
            if (ctx.finalized || ctx.startSent) {
                return;
            }
            List<String> waiting = ctx.gens.values().stream().filter(g -> g.status != RunGenerator.Status.PREPARED)
                    .map(g -> g.name).toList();
            fail(ctx, "Таймаут подготовки: не ответили генераторы " + String.join(", ", waiting));
        }
    }

    // ================================================================== события агентов

    @Override
    public void onEvent(long generatorId, Message message) {
        try {
            if (message instanceof Messages.CmdAck a) {
                onCmdAck(generatorId, a);
            } else if (message instanceof Messages.Prepared p) {
                withCtx(p.runId(), generatorId, (ctx, g) -> onPrepared(ctx, g));
            } else if (message instanceof Messages.PrepareFailed f) {
                withCtx(f.runId(), generatorId, (ctx, g) -> onPrepareFailed(ctx, g, f.reason()));
            } else if (message instanceof Messages.Status s) {
                withCtx(s.runId(), generatorId, (ctx, g) -> onAgentStatus(ctx, g, s));
            } else if (message instanceof Messages.K6Status k) {
                withCtx(k.runId(), generatorId, (ctx, g) -> onK6Status(ctx, g, k));
            } else if (message instanceof Messages.LogBatch b) {
                withCtx(b.runId(), generatorId, (ctx, g) -> onLogBatch(ctx, g, b));
            } else if (message instanceof Messages.Summary s) {
                withCtx(s.runId(), generatorId, (ctx, g) -> onSummary(ctx, g, s));
            } else if (message instanceof Messages.Exit e) {
                withCtx(e.runId(), generatorId, (ctx, g) -> onExit(ctx, g, e));
            }
        } catch (RuntimeException e) {
            log.error("Ошибка обработки события {} от генератора {}", message.getClass().getSimpleName(), generatorId, e);
        }
    }

    private interface GenAction {
        void apply(Ctx ctx, GenCtx gen);
    }

    private void withCtx(String runIdText, long generatorId, GenAction action) {
        long runId;
        try {
            runId = Long.parseLong(runIdText);
        } catch (NumberFormatException e) {
            return;
        }
        Ctx ctx = contexts.get(runId);
        if (ctx == null) {
            ctx = recoverContext(runId);
        }
        if (ctx == null) {
            return;
        }
        GenCtx gen = ctx.gens.get(generatorId);
        if (gen == null) {
            return;
        }
        synchronized (ctx) {
            if (ctx.finalized) {
                return;
            }
            MDC.put("run_id", String.valueOf(runId));
            try {
                action.apply(ctx, gen);
            } finally {
                MDC.remove("run_id");
            }
        }
    }

    private void onCmdAck(long generatorId, Messages.CmdAck ack) {
        CmdRef ref = pendingCmds.remove(ack.cmdId());
        if (ref == null || ack.ok()) {
            return;
        }
        Ctx ctx = contexts.get(ref.runId());
        if (ctx == null) {
            return;
        }
        synchronized (ctx) {
            GenCtx g = ctx.gens.get(generatorId);
            String name = g == null ? String.valueOf(generatorId) : g.name;
            logs.addSystem(ctx.runId, "WARN", "Генератор " + name + " отклонил " + ref.kind() + ": " + ack.error());
            if ((ref.kind().equals("PREPARE") || ref.kind().equals("START")) && !ctx.finalized && !ctx.stopRequested
                    && runService.get(ctx.runId).getStatus() == RunStatus.PREPARING) {
                fail(ctx, "Генератор " + name + " отклонил " + ref.kind() + ": " + ack.error());
            }
        }
    }

    private void onPrepared(Ctx ctx, GenCtx g) {
        g.status = RunGenerator.Status.PREPARED;
        runService.updateGenerator(g.rgId, x -> x.setStatus(RunGenerator.Status.PREPARED));
        logs.addSystem(ctx.runId, "INFO", "Генератор " + g.name + " подготовлен");
        broadcaster.publishStatus(ctx.runId, RunStatus.PREPARING.name());
        if (!ctx.startSent && !ctx.stopRequested && ctx.gens.values().stream().allMatch(x -> x.status == RunGenerator.Status.PREPARED)) {
            startAll(ctx);
        }
    }

    private void onPrepareFailed(Ctx ctx, GenCtx g, String reason) {
        g.status = RunGenerator.Status.FAILED;
        runService.updateGenerator(g.rgId, x -> {
            x.setStatus(RunGenerator.Status.FAILED);
            x.setPrepareError(reason);
        });
        fail(ctx, "Генератор " + g.name + " не смог подготовиться: " + reason);
    }

    /** Все PREPARED: проверка часов, затем START всем с общим start_at. */
    private void startAll(Ctx ctx) {
        cancel(ctx.prepareTimeout);
        Map<Long, Long> offsets = new HashMap<>();
        for (GenCtx g : ctx.gens.values()) {
            AgentSession s = registry.get(g.genId).orElse(null);
            if (s == null) {
                fail(ctx, "Генератор " + g.name + " отключился перед стартом");
                return;
            }
            Long off = s.clockOffsetMs();
            if (off == null) {
                fail(ctx, "Не измерено расхождение часов генератора " + g.name + " - синхронный старт невозможен");
                return;
            }
            if (Math.abs(off) > MAX_CLOCK_SKEW_MS) {
                fail(ctx, "Часы генератора " + g.name + " расходятся с сервером на " + off + " мс (> " + MAX_CLOCK_SKEW_MS
                        + " мс): синхронный старт заблокирован. Настройте chrony/NTP на генераторе.");
                return;
            }
            offsets.put(g.genId, off);
        }
        long startAt = System.currentTimeMillis() + START_DELAY_MS;
        ctx.plannedStart = Instant.ofEpochMilli(startAt);
        for (GenCtx g : ctx.gens.values()) {
            String cmdId = UUID.randomUUID().toString();
            pendingCmds.put(cmdId, new CmdRef(ctx.runId, g.genId, "START"));
            try {
                // время старта переводим в шкалу часов конкретного агента
                registry.get(g.genId).orElseThrow().send(new Messages.Start(cmdId, String.valueOf(ctx.runId), startAt + offsets.get(g.genId)));
            } catch (RuntimeException e) {
                fail(ctx, "Не удалось отправить START генератору " + g.name + ": " + e.getMessage());
                return;
            }
        }
        ctx.startSent = true;
        Run run = runService.update(ctx.runId, r -> r.setPlannedStartAt(ctx.plannedStart));
        List<StepWindow> windows = StepWindow.of(runService.snapshot(run).steps().stream()
                .map(s -> new ru.k6lab.core.profile.StepPlan(s.no(), s.levelPct(), s.rampSec(), s.holdSec(), s.label())).toList());
        List<RunStep> steps = new ArrayList<>();
        for (StepWindow w : windows) {
            RunStep rs = new RunStep();
            rs.setRunId(ctx.runId);
            rs.setStepNo(w.no());
            rs.setLabel(w.label());
            rs.setLevelPct(w.levelPct());
            rs.setRampStartAt(ctx.plannedStart.plusSeconds(w.rampStartSec()));
            rs.setHoldStartAt(ctx.plannedStart.plusSeconds(w.holdStartSec()));
            rs.setHoldEndAt(ctx.plannedStart.plusSeconds(w.holdEndSec()));
            steps.add(rs);
        }
        runService.saveSteps(ctx.runId, steps);
        logs.addSystem(ctx.runId, "INFO", "START отправлен всем генераторам, общий старт через " + (START_DELAY_MS / 1000) + " с");
        ctx.startTimer = scheduler.schedule(() -> onStartTime(ctx.runId), START_DELAY_MS, TimeUnit.MILLISECONDS);
        broadcaster.publishStatus(ctx.runId, RunStatus.PREPARING.name());
    }

    private void onStartTime(long runId) {
        Ctx ctx = contexts.get(runId);
        if (ctx == null) {
            return;
        }
        synchronized (ctx) {
            if (ctx.finalized || ctx.stopRequested) {
                return;
            }
            Run run = runService.get(runId);
            if (run.getStatus() == RunStatus.PREPARING) {
                runService.update(runId, r -> {
                    r.transitionTo(RunStatus.RUNNING);
                    r.setStartedAt(Instant.now());
                });
                logs.addSystem(runId, "INFO", "Тест запущен");
                broadcaster.publishStatus(runId, RunStatus.RUNNING.name());
            }
        }
    }

    private void onAgentStatus(Ctx ctx, GenCtx g, Messages.Status s) {
        RunGenerator.Status st = switch (s.state()) {
            case RUNNING -> RunGenerator.Status.RUNNING;
            case STOPPING -> RunGenerator.Status.STOPPING;
            case EXITED -> RunGenerator.Status.EXITED;
            case PREPARED -> RunGenerator.Status.PREPARED;
            case PREPARING -> RunGenerator.Status.PREPARING;
        };
        if (g.status.isDone()) {
            return;
        }
        g.status = st;
        runService.updateGenerator(g.rgId, x -> x.setStatus(st));
        broadcaster.publishStatus(ctx.runId, runStatusName(ctx));
    }

    private void onK6Status(Ctx ctx, GenCtx g, Messages.K6Status k) {
        g.vus = k.vus();
        g.vusMax = k.vusMax();
        g.iterations = k.iterations();
        g.dropped = k.droppedIterations();
        g.k6Status = k.status();
        long now = System.currentTimeMillis();
        if (now - g.lastPersist > PERSIST_EVERY_MS) {
            g.lastPersist = now;
            runService.updateGenerator(g.rgId, x -> {
                x.setLastVus(k.vus());
                x.setLastIterations(k.iterations());
                x.setLastDropped(k.droppedIterations());
            });
        }
        broadcaster.publishStatus(ctx.runId, runStatusName(ctx));
    }

    private void onLogBatch(Ctx ctx, GenCtx g, Messages.LogBatch b) {
        var rows = logs.add(ctx.runId, g.genId, b.lines());
        List<RunEventBroadcaster.Line> live = new ArrayList<>(rows.size());
        for (RunLogService.Row r : rows) {
            live.add(new RunEventBroadcaster.Line(r.ts().toEpochMilli(), g.genId, g.name, r.level(), r.line()));
        }
        if (b.dropped() > 0) {
            String msg = "Агент отбросил " + b.dropped() + " строк лога по лимиту строк/с";
            live.add(new RunEventBroadcaster.Line(System.currentTimeMillis(), g.genId, g.name, "WARN", msg));
            logs.addSystem(ctx.runId, "WARN", g.name + ": " + msg);
            runService.updateGenerator(g.rgId, x -> x.setDroppedLogLines(x.getDroppedLogLines() + b.dropped()));
        }
        broadcaster.publishLog(ctx.runId, live);
    }

    private void onSummary(Ctx ctx, GenCtx g, Messages.Summary s) {
        runService.updateGenerator(g.rgId, x -> x.setSummary(s.summary()));
    }

    private void onExit(Ctx ctx, GenCtx g, Messages.Exit e) {
        g.exitSeen = true;
        g.exitCode = e.code();
        g.status = RunGenerator.Status.EXITED;
        runService.updateGenerator(g.rgId, x -> {
            x.setStatus(RunGenerator.Status.EXITED);
            x.setExitCode(e.code());
        });
        String kind = e.code() == 0 ? "успешно" : e.code() == Messages.Exit.THRESHOLDS_FAILED ? "thresholds не выполнены (код 99, не авария)"
                : "код " + e.code() + (e.signal() == null ? "" : ", " + e.signal());
        logs.addSystem(ctx.runId, e.code() == 0 || e.code() == Messages.Exit.THRESHOLDS_FAILED ? "INFO" : "ERROR",
                "k6 на " + g.name + " завершился: " + kind);
        checkDone(ctx);
    }

    private void checkDone(Ctx ctx) {
        if (ctx.gens.values().stream().allMatch(x -> x.status.isDone())) {
            finalizeRun(ctx);
        } else {
            broadcaster.publishStatus(ctx.runId, runStatusName(ctx));
        }
    }

    // ================================================================== завершение

    private void finalizeRun(Ctx ctx) {
        if (ctx.finalized) {
            return;
        }
        ctx.finalized = true;
        cancel(ctx.prepareTimeout);
        cancel(ctx.startTimer);
        cancel(ctx.escalation);
        Run run = runService.get(ctx.runId);
        boolean thresholds = ctx.gens.values().stream().anyMatch(g -> g.exitCode != null && g.exitCode == Messages.Exit.THRESHOLDS_FAILED);
        // аварией считается только падение k6 (код выхода не 0 и не 99); потерянный генератор - это DEGRADED, не авария
        List<String> crashed = ctx.gens.values().stream()
                .filter(g -> g.exitCode != null && g.exitCode != 0 && g.exitCode != Messages.Exit.THRESHOLDS_FAILED)
                .map(g -> g.name + " (код " + g.exitCode + ")").toList();
        RunStatus target;
        String error = null;
        if (run.getStatus() == RunStatus.FAILED || run.getStatus().isTerminal()) {
            target = run.getStatus();
        } else if (ctx.abortRequested) {
            target = RunStatus.ABORTED;
        } else if (!crashed.isEmpty() && !ctx.stopRequested) {
            target = RunStatus.FAILED;
            error = "k6 завершился аварийно на: " + String.join(", ", crashed);
        } else {
            target = RunStatus.FINISHED;
        }
        final RunStatus fTarget = target;
        final String fError = error;
        runService.update(ctx.runId, r -> {
            if (!r.getStatus().isTerminal()) {
                if (fTarget == RunStatus.FINISHED && r.getStatus() == RunStatus.PREPARING) {
                    r.transitionTo(RunStatus.RUNNING);
                }
                r.transitionTo(fTarget);
            }
            r.setThresholdsFailed(thresholds);
            if (fError != null) {
                r.setError(fError);
            }
            if (ctx.stopReason != null) {
                r.setStopReason(ctx.stopReason);
            }
        });
        reservation.release(ctx.gens.values().stream().map(g -> g.genId).toList());
        logs.addSystem(ctx.runId, target == RunStatus.FAILED ? "ERROR" : "INFO", "Run завершён: " + target
                + (ctx.stopReason != null ? " (" + ctx.stopReason + ")" : "") + (thresholds ? ", thresholds k6 не выполнены" : ""));
        contexts.remove(ctx.runId);
        pendingCmds.values().removeIf(c -> c.runId() == ctx.runId);
        broadcaster.publishStatus(ctx.runId, target.name());
        scheduler.schedule(() -> broadcaster.close(ctx.runId), 30, TimeUnit.SECONDS);
        publisher.publishEvent(new RunFinished(ctx.runId, target));
    }

    /** Ошибка до/во время старта: остановить всё, что поднялось, статус FAILED. */
    private void fail(Ctx ctx, String reason) {
        if (ctx.finalized) {
            return;
        }
        log.warn("Run {} FAILED: {}", ctx.runId, reason);
        logs.addSystem(ctx.runId, "ERROR", reason);
        runService.update(ctx.runId, r -> {
            r.setError(reason);
            if (r.getStatus() == RunStatus.PREPARING || r.getStatus() == RunStatus.QUEUED || r.getStatus() == RunStatus.RUNNING
                    || r.getStatus() == RunStatus.STOPPING) {
                r.transitionTo(RunStatus.FAILED);
            }
        });
        for (GenCtx g : ctx.gens.values()) {
            sendQuietly(g.genId, ctx.runId, g.status == RunGenerator.Status.RUNNING || g.status == RunGenerator.Status.STOPPING
                    ? "ABORT" : "CANCEL_PREPARE");
            if (!g.status.isDone()) {
                g.status = g.status == RunGenerator.Status.FAILED ? g.status : RunGenerator.Status.FAILED;
                runService.updateGenerator(g.rgId, x -> {
                    if (!x.getStatus().isDone()) {
                        x.setStatus(RunGenerator.Status.FAILED);
                    }
                });
            }
        }
        ctx.finalized = true;
        cancel(ctx.prepareTimeout);
        cancel(ctx.startTimer);
        cancel(ctx.escalation);
        reservation.release(ctx.gens.values().stream().map(g -> g.genId).toList());
        contexts.remove(ctx.runId);
        pendingCmds.values().removeIf(c -> c.runId() == ctx.runId);
        broadcaster.publishStatus(ctx.runId, RunStatus.FAILED.name());
        scheduler.schedule(() -> broadcaster.close(ctx.runId), 30, TimeUnit.SECONDS);
        publisher.publishEvent(new RunFinished(ctx.runId, RunStatus.FAILED));
    }

    /** Для диспетчера: run мог не дойти до контекста. */
    public void failQuietly(Long runId, String reason) {
        Ctx ctx = contexts.get(runId);
        if (ctx != null) {
            synchronized (ctx) {
                fail(ctx, reason);
            }
            return;
        }
        Run run = runService.get(runId);
        if (!run.getStatus().isTerminal()) {
            List<Long> genIds = runService.generatorsOf(runId).stream().map(RunGenerator::getGeneratorId).toList();
            runService.update(runId, r -> {
                r.setError(reason);
                r.transitionTo(RunStatus.FAILED);
            });
            if (run.getStatus() == RunStatus.PREPARING) {
                reservation.release(genIds);
            }
            publisher.publishEvent(new RunFinished(runId, RunStatus.FAILED));
        }
    }

    // ================================================================== управление пользователем и критериями

    /** Graceful stop: SIGINT на агентах. Причина фиксируется в run (для критериев остановки - их описание). */
    public void stop(Long runId, String reason) {
        Ctx ctx = contexts.get(runId);
        if (ctx == null) {
            // run в очереди: просто отменяем
            Run run = runService.get(runId);
            if (run.getStatus() == RunStatus.QUEUED) {
                runService.update(runId, r -> {
                    r.setStopReason(reason);
                    r.transitionTo(RunStatus.ABORTED);
                });
                publisher.publishEvent(new RunFinished(runId, RunStatus.ABORTED));
                return;
            }
            throw new IllegalStateException("Run " + runId + " не выполняется (статус " + run.getStatus() + ")");
        }
        synchronized (ctx) {
            if (ctx.finalized || ctx.stopRequested) {
                return;
            }
            ctx.stopRequested = true;
            ctx.stopReason = reason;
            ctx.stopRequestedAt = Instant.now();
            logs.addSystem(runId, "WARN", "Остановка: " + reason);
            Run run = runService.get(runId);
            if (run.getStatus() == RunStatus.PREPARING && !ctx.startSent) {
                // тест ещё не стартовал: отменяем подготовку
                for (GenCtx g : ctx.gens.values()) {
                    sendQuietly(g.genId, runId, "CANCEL_PREPARE");
                    g.status = RunGenerator.Status.EXITED;
                    runService.updateGenerator(g.rgId, x -> x.setStatus(RunGenerator.Status.EXITED));
                }
                runService.update(runId, r -> {
                    r.setStopReason(reason);
                    r.transitionTo(RunStatus.ABORTED);
                });
                ctx.finalized = true;
                cancel(ctx.prepareTimeout);
                reservation.release(ctx.gens.values().stream().map(g -> g.genId).toList());
                contexts.remove(runId);
                broadcaster.publishStatus(runId, RunStatus.ABORTED.name());
                scheduler.schedule(() -> broadcaster.close(runId), 30, TimeUnit.SECONDS);
                publisher.publishEvent(new RunFinished(runId, RunStatus.ABORTED));
                return;
            }
            runService.update(runId, r -> {
                r.setStopReason(reason);
                if (r.getStatus() == RunStatus.PREPARING || r.getStatus() == RunStatus.RUNNING) {
                    r.transitionTo(RunStatus.STOPPING);
                }
            });
            for (GenCtx g : ctx.gens.values()) {
                if (!g.status.isDone()) {
                    sendQuietly(g.genId, runId, "STOP");
                }
            }
            // если генераторы не уложились в graceful stop + запас - убиваем
            ctx.escalation = scheduler.schedule(() -> escalate(runId), ctx.gracefulStopSec + 60L, TimeUnit.SECONDS);
            broadcaster.publishStatus(runId, RunStatus.STOPPING.name());
        }
    }

    private void escalate(long runId) {
        Ctx ctx = contexts.get(runId);
        if (ctx == null) {
            return;
        }
        synchronized (ctx) {
            if (ctx.finalized) {
                return;
            }
            boolean onlineLeft = ctx.gens.values().stream().anyMatch(g -> !g.status.isDone() && !g.offline);
            if (!onlineLeft) {
                // остановить некого: оставшиеся генераторы недоступны, ABORT им не отправить - закрываем run без пометки abort
                logs.addSystem(runId, "ERROR", "Остановка: недоступные генераторы не подтвердили остановку - объявляются потерянными");
                markLost(runId);
                return;
            }
            logs.addSystem(runId, "ERROR", "Генераторы не остановились за graceful stop + 60 с - принудительное завершение (ABORT)");
            ctx.abortRequested = true;
            for (GenCtx g : ctx.gens.values()) {
                if (!g.status.isDone()) {
                    sendQuietly(g.genId, runId, "ABORT");
                }
            }
            ctx.escalation = scheduler.schedule(() -> markLost(runId), 20, TimeUnit.SECONDS);
        }
    }

    /** Генераторы, так и не ответившие после ABORT, считаются потерянными: run закрывается. */
    private void markLost(long runId) {
        Ctx ctx = contexts.get(runId);
        if (ctx == null) {
            return;
        }
        synchronized (ctx) {
            for (GenCtx g : ctx.gens.values()) {
                if (!g.status.isDone()) {
                    g.status = RunGenerator.Status.LOST;
                    runService.updateGenerator(g.rgId, x -> x.setStatus(RunGenerator.Status.LOST));
                    logs.addSystem(runId, "ERROR", "Генератор " + g.name + " потерян: нет ответа агента");
                }
            }
            checkDone(ctx);
        }
    }

    public void abort(Long runId) {
        Ctx ctx = contexts.get(runId);
        if (ctx == null) {
            stop(runId, "прервано пользователем");
            return;
        }
        synchronized (ctx) {
            if (ctx.finalized) {
                return;
            }
            ctx.abortRequested = true;
            if (ctx.stopReason == null) {
                ctx.stopReason = "прервано пользователем (Abort)";
            }
            logs.addSystem(runId, "ERROR", "ABORT: принудительное завершение (SIGKILL)");
            runService.update(runId, r -> {
                r.setStopReason(ctx.stopReason);
                if (r.getStatus() == RunStatus.PREPARING || r.getStatus() == RunStatus.RUNNING) {
                    r.transitionTo(RunStatus.STOPPING);
                }
            });
            for (GenCtx g : ctx.gens.values()) {
                if (!g.status.isDone()) {
                    sendQuietly(g.genId, runId, g.status == RunGenerator.Status.PREPARED || g.status == RunGenerator.Status.PREPARING
                            ? "CANCEL_PREPARE" : "ABORT");
                    if (g.status == RunGenerator.Status.PREPARED || g.status == RunGenerator.Status.PREPARING) {
                        g.status = RunGenerator.Status.EXITED;
                        runService.updateGenerator(g.rgId, x -> x.setStatus(RunGenerator.Status.EXITED));
                    }
                }
            }
            cancel(ctx.escalation);
            ctx.escalation = scheduler.schedule(() -> markLost(runId), 15, TimeUnit.SECONDS);
            checkDone(ctx);
        }
    }

    /** Pause/Resume/Scale: k6 поддерживает их только для externally-controlled (на одном генераторе). */
    public void control(Long runId, String action, Integer vus) {
        Ctx ctx = contexts.get(runId);
        if (ctx == null) {
            throw new IllegalStateException("Run не выполняется");
        }
        if (!ctx.externallyControlled) {
            throw new IllegalStateException("k6 поддерживает pause/resume и смену VU только для executor externally-controlled "
                    + "(в экспертном режиме); для arrival-rate и ramping-vus - нет");
        }
        for (GenCtx g : ctx.gens.values()) {
            if (g.status != RunGenerator.Status.RUNNING) {
                continue;
            }
            String cmdId = UUID.randomUUID().toString();
            pendingCmds.put(cmdId, new CmdRef(runId, g.genId, action));
            Message cmd = switch (action) {
                case "PAUSE" -> new Messages.Pause(cmdId, String.valueOf(runId));
                case "RESUME" -> new Messages.Resume(cmdId, String.valueOf(runId));
                case "SCALE_VUS" -> new Messages.ScaleVus(cmdId, String.valueOf(runId), vus == null ? 1 : vus);
                default -> throw new IllegalArgumentException("Неизвестное действие " + action);
            };
            registry.get(g.genId).orElseThrow(() -> new IllegalStateException("Генератор не подключён")).send(cmd);
        }
        logs.addSystem(runId, "INFO", "Команда " + action + (vus != null ? " " + vus : "") + " отправлена");
    }

    private void sendQuietly(long generatorId, long runId, String kind) {
        String cmdId = UUID.randomUUID().toString();
        String rid = String.valueOf(runId);
        Message m = switch (kind) {
            case "STOP" -> new Messages.Stop(cmdId, rid);
            case "ABORT" -> new Messages.Abort(cmdId, rid);
            default -> new Messages.CancelPrepare(cmdId, rid);
        };
        pendingCmds.put(cmdId, new CmdRef(runId, generatorId, kind));
        registry.get(generatorId).ifPresent(s -> {
            try {
                s.send(m);
            } catch (RuntimeException e) {
                log.debug("{} не отправлен генератору {}: {}", kind, generatorId, e.getMessage());
            }
        });
    }

    // ================================================================== отвал и возврат генератора

    @EventListener
    public void onDisconnected(AgentLifecycleEvents.Disconnected ev) {
        for (Ctx ctx : List.copyOf(contexts.values())) {
            GenCtx g = ctx.gens.get(ev.generatorId());
            if (g == null) {
                continue;
            }
            synchronized (ctx) {
                if (ctx.finalized || g.status.isDone()) {
                    continue;
                }
                MDC.put("run_id", String.valueOf(ctx.runId));
                try {
                    if (!ctx.startSent) {
                        fail(ctx, "Потеряна связь с генератором " + g.name + " на этапе подготовки");
                        continue;
                    }
                    g.offline = true;
                    g.offlineSince = Instant.now();
                    runService.update(ctx.runId, r -> r.setDegraded(true));
                    runService.updateGenerator(g.rgId, x -> {
                        List<RunGenerator.Interval> l = new ArrayList<>(x.getOfflineIntervals());
                        l.add(new RunGenerator.Interval(g.offlineSince, null));
                        x.setOfflineIntervals(l);
                    });
                    logs.addSystem(ctx.runId, "ERROR", "Генератор " + g.name + " отвалился (DEGRADED). Политика: " + ctx.policy.getTitle()
                            + ". Ступени без этого генератора будут помечены недостоверными.");
                    broadcaster.publishStatus(ctx.runId, runStatusName(ctx));
                    if (ctx.policy == Run.FailurePolicy.STOP_ALL && !ctx.stopRequested) {
                        stop(ctx.runId, "отвал генератора " + g.name + " (политика: остановить все)");
                    }
                } finally {
                    MDC.remove("run_id");
                }
            }
        }
    }

    @EventListener
    public void onConnected(AgentLifecycleEvents.Connected ev) {
        for (Ctx ctx : List.copyOf(contexts.values())) {
            GenCtx g = ctx.gens.get(ev.generatorId());
            if (g == null) {
                continue;
            }
            synchronized (ctx) {
                if (ctx.finalized || g.status.isDone()) {
                    continue;
                }
                if (g.offline) {
                    g.offline = false;
                    Instant since = g.offlineSince;
                    runService.updateGenerator(g.rgId, x -> {
                        List<RunGenerator.Interval> l = new ArrayList<>(x.getOfflineIntervals());
                        if (!l.isEmpty() && l.get(l.size() - 1).to() == null) {
                            l.set(l.size() - 1, new RunGenerator.Interval(l.get(l.size() - 1).from(), Instant.now()));
                        }
                        x.setOfflineIntervals(l);
                    });
                    logs.addSystem(ctx.runId, "WARN", "Генератор " + g.name + " вернулся (был недоступен с " + since + ")");
                    if (ctx.stopRequested && !g.status.isDone()) {
                        // остановка была запрошена, пока генератор не отвечал: повторяем команду
                        sendQuietly(g.genId, ctx.runId, ctx.abortRequested ? "ABORT" : "STOP");
                    }
                }
                // сверка: агент должен знать этот run, иначе k6 погиб вместе с ним (рестарт агента)
                boolean known = ev.hello().runs() != null && ev.hello().runs().stream()
                        .anyMatch(r -> r.runId().equals(String.valueOf(ctx.runId)));
                if (!known && (g.status == RunGenerator.Status.RUNNING || g.status == RunGenerator.Status.STOPPING
                        || g.status == RunGenerator.Status.PREPARED || g.status == RunGenerator.Status.PREPARING)) {
                    g.status = RunGenerator.Status.LOST;
                    runService.updateGenerator(g.rgId, x -> x.setStatus(RunGenerator.Status.LOST));
                    logs.addSystem(ctx.runId, "ERROR", "Агент " + g.name + " не знает этот run (был перезапущен?): генератор потерян");
                    runService.update(ctx.runId, r -> r.setDegraded(true));
                    if (ctx.policy == Run.FailurePolicy.STOP_ALL && !ctx.stopRequested) {
                        stop(ctx.runId, "потеря генератора " + g.name + " (политика: остановить все)");
                    }
                    checkDone(ctx);
                }
            }
        }
    }

    /** Сторож: зависшие run закрываем, отсутствующие слишком долго генераторы объявляем потерянными. */
    @Scheduled(fixedDelay = 10_000, initialDelay = 20_000)
    public void watchdog() {
        Instant now = Instant.now();
        for (Ctx ctx : List.copyOf(contexts.values())) {
            synchronized (ctx) {
                if (ctx.finalized) {
                    continue;
                }
                Instant deadline = ctx.stopRequestedAt != null
                        ? ctx.stopRequestedAt.plusSeconds(ctx.gracefulStopSec + 120L)
                        : ctx.plannedStart != null ? ctx.plannedStart.plusSeconds(ctx.totalSec + ctx.gracefulStopSec + lostGraceSec) : null;
                if (deadline == null || now.isBefore(deadline)) {
                    continue;
                }
                boolean anyOffline = ctx.gens.values().stream().anyMatch(g -> g.offline && !g.status.isDone());
                if (anyOffline) {
                    for (GenCtx g : ctx.gens.values()) {
                        if (g.offline && !g.status.isDone()) {
                            g.status = RunGenerator.Status.LOST;
                            runService.updateGenerator(g.rgId, x -> x.setStatus(RunGenerator.Status.LOST));
                            logs.addSystem(ctx.runId, "ERROR", "Генератор " + g.name + " не вернулся к плановому окончанию теста - потерян");
                        }
                    }
                    checkDone(ctx);
                } else if (!ctx.abortRequested) {
                    logs.addSystem(ctx.runId, "ERROR", "Тест не завершился к плановому времени + запас - принудительное завершение");
                    ctx.abortRequested = true;
                    for (GenCtx g : ctx.gens.values()) {
                        if (!g.status.isDone()) {
                            sendQuietly(g.genId, ctx.runId, "ABORT");
                        }
                    }
                }
            }
        }
    }

    // ================================================================== восстановление после рестарта

    /** После рестарта сервера восстанавливаем контексты активных run: агенты переподключатся и досылают события. */
    @EventListener(ApplicationReadyEvent.class)
    public void recoverAll() {
        List<Run> active = runService.activeRuns();
        for (Run r : active) {
            if (r.getStatus() == RunStatus.QUEUED) {
                continue;
            }
            Ctx ctx = recoverContext(r.getId());
            if (ctx != null) {
                log.info("Run {} ({}) восстановлен после рестарта сервера", r.getId(), r.getStatus());
                logs.addSystem(r.getId(), "WARN", "Сервер перезапущен: ожидаем переподключения агентов");
                scheduler.schedule(() -> recoveryCheck(r.getId()), 90, TimeUnit.SECONDS);
            }
        }
    }

    private Ctx recoverContext(long runId) {
        synchronized (contexts) {
            Ctx existing = contexts.get(runId);
            if (existing != null) {
                return existing;
            }
            Run run;
            try {
                run = runService.get(runId);
            } catch (IllegalArgumentException e) {
                return null;
            }
            if (run.getStatus() == RunStatus.QUEUED || run.getStatus().isTerminal()) {
                return null;
            }
            Ctx ctx = newCtx(run, runService.snapshot(run));
            for (RunGenerator rg : runService.generatorsOf(runId)) {
                if (rg.getGeneratorId() != null) {
                    GenCtx g = genCtx(rg);
                    g.offline = !registry.isConnected(rg.getGeneratorId());
                    g.offlineSince = g.offline ? Instant.now() : null;
                    ctx.gens.put(rg.getGeneratorId(), g);
                }
            }
            contexts.put(runId, ctx);
            if (run.getStatus() == RunStatus.PREPARING && !ctx.startSent) {
                // подготовка была прервана рестартом: безопаснее провалить, чем гадать
                ctx.prepareTimeout = scheduler.schedule(() -> onPrepareTimeout(runId), 60, TimeUnit.SECONDS);
            } else if (run.getStatus() == RunStatus.PREPARING) {
                ctx.startTimer = scheduler.schedule(() -> onStartTime(runId), Math.max(0, Duration.between(Instant.now(), run.getPlannedStartAt()).toMillis()), TimeUnit.MILLISECONDS);
            }
            return ctx;
        }
    }

    /** Через 90 с после рестарта: генераторы, так и не вернувшиеся, считаем отвалившимися (DEGRADED). */
    private void recoveryCheck(long runId) {
        Ctx ctx = contexts.get(runId);
        if (ctx == null) {
            return;
        }
        synchronized (ctx) {
            for (GenCtx g : ctx.gens.values()) {
                if (!g.status.isDone() && !registry.isConnected(g.genId)) {
                    runService.update(runId, r -> r.setDegraded(true));
                    logs.addSystem(runId, "ERROR", "Генератор " + g.name + " не переподключился после рестарта сервера");
                    if (ctx.policy == Run.FailurePolicy.STOP_ALL && !ctx.stopRequested) {
                        stop(runId, "генератор " + g.name + " не вернулся после рестарта сервера");
                    }
                }
            }
        }
    }

    // ================================================================== вспомогательное

    private String runStatusName(Ctx ctx) {
        try {
            return runService.get(ctx.runId).getStatus().name();
        } catch (RuntimeException e) {
            return "?";
        }
    }

    private static void cancel(ScheduledFuture<?> f) {
        if (f != null) {
            f.cancel(false);
        }
    }

    // ================================================================== чтение для UI

    /** Живое состояние генератора в run (счётчики из K6_STATUS). */
    public record LiveGen(long generatorId, String name, String status, long vus, long vusMax, long iterations, long dropped,
                          String k6Status, boolean offline) {
    }

    public List<LiveGen> liveGenerators(long runId) {
        Ctx ctx = contexts.get(runId);
        if (ctx == null) {
            return List.of();
        }
        List<LiveGen> out = new ArrayList<>();
        for (GenCtx g : ctx.gens.values()) {
            out.add(new LiveGen(g.genId, g.name, g.status.name(), g.vus, g.vusMax, g.iterations, g.dropped, g.k6Status, g.offline));
        }
        return out;
    }

    public boolean isActive(long runId) {
        return contexts.containsKey(runId);
    }

    public boolean supportsControl(long runId) {
        Ctx ctx = contexts.get(runId);
        return ctx != null && ctx.externallyControlled;
    }

    Set<Long> activeRunIds() {
        return Set.copyOf(contexts.keySet());
    }
}
