package ru.k6lab.server.run;

import org.springframework.stereotype.Service;
import ru.k6lab.core.runstate.RunStatus;
import ru.k6lab.server.generator.GeneratorMetricsService;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/** Представление run для UI: прогресс, текущая ступень, генераторы (БД + живые счётчики + ресурсы). */
@Service
public class RunViewService {

    public record GenView(RunGenerator rg, RunOrchestrator.LiveGen live, Double cpu, Double memPct, boolean cpuHot,
                          boolean offline) {
    }

    public record RunView(Run run, RunSnapshot snapshot, List<GenView> generators, List<RunStep> steps, Integer currentStep,
                          String currentPhase, long elapsedSec, long totalSec, int progressPct, boolean canStop,
                          boolean canControl, boolean live) {
    }

    private final RunService runService;
    private final RunOrchestrator orchestrator;
    private final GeneratorMetricsService metrics;

    public RunViewService(RunService runService, RunOrchestrator orchestrator, GeneratorMetricsService metrics) {
        this.runService = runService;
        this.orchestrator = orchestrator;
        this.metrics = metrics;
    }

    public RunView view(Long runId) {
        Run run = runService.get(runId);
        RunSnapshot snap = runService.snapshot(run);
        List<RunStep> steps = runService.stepsOf(runId);
        var live = orchestrator.liveGenerators(runId);
        List<GenView> gens = runService.generatorsOf(runId).stream().map(rg -> {
            var lg = live.stream().filter(l -> l.generatorId() == (rg.getGeneratorId() == null ? -1 : rg.getGeneratorId())).findFirst().orElse(null);
            var m = rg.getGeneratorId() == null ? null : metrics.latest(rg.getGeneratorId());
            Double cpu = m == null ? null : m.hb().cpuPct();
            Double mem = m == null || m.hb().ramTotalBytes() == 0 ? null : 100.0 * m.hb().ramUsedBytes() / m.hb().ramTotalBytes();
            return new GenView(rg, lg, cpu, mem, cpu != null && cpu > 80.0, lg != null && lg.offline());
        }).toList();

        long total = snap.steps().stream().mapToLong(s -> s.rampSec() + s.holdSec()).sum();
        long elapsed = 0;
        Integer current = null;
        String phase = null;
        if (run.getPlannedStartAt() != null && !run.getStatus().isTerminal() && run.getStatus() != RunStatus.QUEUED) {
            Instant now = Instant.now();
            elapsed = Math.max(0, Duration.between(run.getPlannedStartAt(), now).getSeconds());
            for (RunStep s : steps) {
                if (!now.isBefore(s.getRampStartAt()) && now.isBefore(s.getHoldEndAt())) {
                    current = s.getStepNo();
                    phase = now.isBefore(s.getHoldStartAt()) ? "разгон" : "удержание";
                }
            }
        } else if (run.getStartedAt() != null && run.getFinishedAt() != null) {
            elapsed = Duration.between(run.getPlannedStartAt() != null ? run.getPlannedStartAt() : run.getStartedAt(), run.getFinishedAt()).getSeconds();
        }
        int pct = total <= 0 ? 0 : (int) Math.min(100, Math.round(100.0 * elapsed / total));
        if (run.getStatus() == RunStatus.FINISHED) {
            pct = 100;
        }
        boolean active = run.getStatus().isActive() || run.getStatus() == RunStatus.QUEUED;
        return new RunView(run, snap, gens, steps, current, phase, elapsed, total, pct, active,
                run.getStatus() == RunStatus.RUNNING && orchestrator.supportsControl(runId), orchestrator.isActive(runId));
    }
}
