package ru.k6lab.server.api;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ru.k6lab.core.runstate.RunStatus;
import ru.k6lab.server.deploy.Deployment;
import ru.k6lab.server.deploy.DeploymentRepository;
import ru.k6lab.server.generator.Generator;
import ru.k6lab.server.generator.GeneratorView;
import ru.k6lab.server.generator.GeneratorViewService;
import ru.k6lab.server.run.Run;
import ru.k6lab.server.run.RunService;
import ru.k6lab.server.run.RunViewService;
import ru.k6lab.server.script.ScriptService;
import ru.k6lab.server.script.StorageHealth;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Сводные данные главной панели для React SPA. */
@RestController
@RequestMapping("/api/v1/dashboard")
public class DashboardApiController {

    private final GeneratorViewService generatorViews;
    private final DeploymentRepository deployments;
    private final RunService runs;
    private final RunViewService runViews;
    private final ScriptService scripts;
    private final StorageHealth storageHealth;

    public DashboardApiController(GeneratorViewService generatorViews,
                                  DeploymentRepository deployments,
                                  RunService runs,
                                  RunViewService runViews,
                                  ScriptService scripts,
                                  StorageHealth storageHealth) {
        this.generatorViews = generatorViews;
        this.deployments = deployments;
        this.runs = runs;
        this.runViews = runViews;
        this.scripts = scripts;
        this.storageHealth = storageHealth;
    }

    @GetMapping
    public Map<String, Object> dashboard() {
        List<GeneratorView> all = generatorViews.all();
        List<GeneratorView> shown = all.stream()
                .sorted(Comparator.comparingInt(DashboardApiController::priority)
                        .thenComparing(v -> v.g().getName(), String.CASE_INSENSITIVE_ORDER))
                .limit(6)
                .toList();

        long online = all.stream()
                .filter(v -> v.g().getStatus() == Generator.Status.ONLINE && !v.g().isMaintenance())
                .count();
        long busy = all.stream().filter(v -> v.g().isBusy()).count();
        List<Map<String, Object>> problems = all.stream()
                .filter(v -> v.g().getStatus() == Generator.Status.ERROR || v.g().getStatus() == Generator.Status.OFFLINE)
                .map(DashboardApiController::toGenDto)
                .toList();
        List<Map<String, Object>> hot = all.stream()
                .filter(GeneratorView::cpuHot)
                .map(DashboardApiController::toGenDto)
                .toList();
        List<Map<String, Object>> drift = all.stream()
                .filter(GeneratorView::hasDrift)
                .map(DashboardApiController::toGenDto)
                .toList();

        List<Run> active = new ArrayList<>(runs.activeRuns());
        active.addAll(runs.search(null, RunStatus.QUEUED, 20));
        List<Map<String, Object>> runningDtos = new ArrayList<>();
        for (Run r : active) {
            try {
                runningDtos.add(toRunViewDto(runViews.view(r.getId())));
            } catch (RuntimeException ignored) {
                // run мог завершиться между запросами
            }
        }
        runningDtos.sort((a, b) -> Long.compare(((Number) b.get("id")).longValue(), ((Number) a.get("id")).longValue()));

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("totalGenerators", all.size());
        summary.put("onlineGenerators", online);
        summary.put("busyGenerators", busy);
        summary.put("problemsCount", problems.size());
        summary.put("hotCount", hot.size());
        summary.put("driftCount", drift.size());
        List<ru.k6lab.server.script.Script> allScripts = scripts.list();
        java.util.Set<String> missingNames = new java.util.HashSet<>(storageHealth.scriptsWithoutFiles());
        summary.put("runsCount", runs.count());
        summary.put("scriptsCount", allScripts.size());
        summary.put("scriptsWithoutFiles", allScripts.stream()
                .filter(s -> missingNames.contains(s.getName()))
                .map(s -> Map.of("id", s.getId(), "name", s.getName()))
                .toList());
        summary.put("storagePath", storageHealth.storagePath() != null ? storageHealth.storagePath() : "");

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("summary", summary);
        out.put("running", runningDtos);
        out.put("generators", shown.stream().map(DashboardApiController::toGenDto).toList());
        out.put("problems", problems);
        out.put("hot", hot);
        out.put("drift", drift);
        out.put("recentDeployments", deployments.findTop50ByOrderByStartedAtDesc().stream()
                .limit(8)
                .map(DashboardApiController::toDeploymentDto)
                .toList());
        out.put("lastRuns", runs.search(null, null, 8).stream()
                .map(DashboardApiController::toRunSummaryDto)
                .toList());
        return out;
    }

    private static int priority(GeneratorView v) {
        Generator.Status st = v.g().getStatus();
        if (st == Generator.Status.ERROR || st == Generator.Status.OFFLINE) {
            return 0;
        }
        return v.g().isBusy() ? 1 : 2;
    }

    public static Map<String, Object> toGenDto(GeneratorView v) {
        Generator g = v.g();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", g.getId());
        m.put("name", g.getName());
        m.put("host", g.getHost());
        m.put("sshPort", g.getSshPort());
        m.put("status", g.getStatus().name());
        m.put("statusLabel", v.statusLabel());
        m.put("maintenance", g.isMaintenance());
        m.put("busy", g.isBusy());
        m.put("connected", v.connected());
        m.put("availableForRuns", g.isAvailableForRuns());
        m.put("cpu", v.cpu());
        m.put("memPct", v.memPct());
        m.put("netRx", v.netRx());
        m.put("netTx", v.netTx());
        m.put("load1", v.load1());
        m.put("diskFreeGb", v.diskFreeGb());
        m.put("sockets", v.sockets());
        m.put("cpuHot", v.cpuHot());
        m.put("clockOffsetMs", v.clockOffsetMs());
        m.put("clockWarn", v.clockWarn());
        m.put("clockBad", v.clockBad());
        m.put("agentVersion", g.getAgentVersion());
        m.put("k6Version", g.getK6Version());
        m.put("agentDrift", v.agentDrift());
        m.put("k6Drift", v.k6Drift());
        m.put("hasDrift", v.hasDrift());
        m.put("defaultAgent", v.defaultAgent());
        m.put("defaultK6", v.defaultK6());
        m.put("activeRuns", g.getActiveRuns());
        m.put("maxConcurrentRuns", g.getMaxConcurrentRuns());
        m.put("lastHeartbeatAt", g.getLastHeartbeatAt());
        return m;
    }

    public static Map<String, Object> toRunViewDto(RunViewService.RunView rv) {
        Run r = rv.run();
        Map<String, Object> m = toRunSummaryDto(r);
        m.put("currentStep", rv.currentStep());
        m.put("totalSteps", rv.steps().size());
        m.put("currentPhase", rv.currentPhase());
        m.put("elapsedSec", rv.elapsedSec());
        m.put("totalSec", rv.totalSec());
        m.put("progressPct", rv.progressPct());
        m.put("canStop", rv.canStop());
        m.put("canControl", rv.canControl());
        m.put("live", rv.live());
        m.put("initializing", rv.initializing());
        m.put("initElapsedSec", rv.initElapsedSec());
        m.put("maxCpu", rv.maxCpu());
        m.put("offlineCount", rv.offlineCount());
        m.put("hotCount", rv.hotCount());
        m.put("generatorsCount", rv.generators().size());
        return m;
    }

    public static Map<String, Object> toRunSummaryDto(Run r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", r.getId());
        m.put("scriptId", r.getScriptId());
        m.put("scriptName", r.getScriptName());
        m.put("scriptVersionId", r.getScriptVersionId());
        m.put("status", r.getStatus().name());
        m.put("degraded", r.isDegraded());
        m.put("thresholdsFailed", r.isThresholdsFailed());
        m.put("conclusion", r.getConclusion());
        m.put("stopReason", r.getStopReason());
        m.put("error", r.getError());
        m.put("queuedAt", r.getQueuedAt());
        m.put("startedAt", r.getStartedAt());
        m.put("finishedAt", r.getFinishedAt());
        m.put("createdBy", r.getCreatedBy());
        Object profName = r.getRequest() != null ? r.getRequest().get("loadProfileName") : null;
        Object profId = r.getRequest() != null ? r.getRequest().get("loadProfileId") : null;
        Object schedId = r.getRequest() != null ? r.getRequest().get("scheduleId") : null;
        m.put("loadProfileId", profId);
        m.put("loadProfileName", profName);
        m.put("scheduleId", schedId);
        return m;
    }

    public static Map<String, Object> toDeploymentDto(Deployment d) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", d.getId());
        m.put("generatorId", d.getGeneratorId());
        m.put("targetHost", d.getTargetHost());
        m.put("operation", d.getOperation().name());
        m.put("status", d.getStatus().name());
        m.put("error", d.getError());
        m.put("startedBy", d.getStartedBy());
        m.put("startedAt", d.getStartedAt());
        m.put("finishedAt", d.getFinishedAt());
        return m;
    }
}
