package ru.k6lab.server.api;

import org.springframework.data.domain.Page;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import ru.k6lab.core.k6config.ConfigDuration;
import ru.k6lab.core.profile.StepPlan;
import ru.k6lab.core.runstate.RunStatus;
import ru.k6lab.server.generator.Generator;
import ru.k6lab.server.generator.GeneratorRepository;
import ru.k6lab.server.git.GitRepoService;
import ru.k6lab.server.load.LoadForm;
import ru.k6lab.server.load.LoadProfileStore;
import ru.k6lab.server.load.LoadService;
import ru.k6lab.server.load.RunPlan;
import ru.k6lab.server.load.RunPlanner;
import ru.k6lab.server.result.ChartRenderer;
import ru.k6lab.server.result.CustomChartService;
import ru.k6lab.server.result.ResultData;
import ru.k6lab.server.result.StepResultService;
import ru.k6lab.server.run.K6Flag;
import ru.k6lab.server.run.Run;
import ru.k6lab.server.run.RunLogService;
import ru.k6lab.server.run.RunOrchestrator;
import ru.k6lab.server.run.RunService;
import ru.k6lab.server.run.RunSnapshot;
import ru.k6lab.server.run.RunViewService;
import ru.k6lab.server.schedule.ScheduledRunService;
import ru.k6lab.server.script.Script;
import ru.k6lab.server.script.ScriptService;

import java.io.IOException;
import java.security.Principal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** REST API запуска тестов, планирования со страницы скрипта, просмотра активных прогонов и итогов. */
@RestController
@RequestMapping("/api/v1")
public class RunApiController {

    private static final List<String> CONCLUSIONS = List.of(
            "Нагрузка выдерживается",
            "Есть нарушения SLA",
            "Деградация",
            "Результат недостоверен"
    );

    private final RunService runs;
    private final RunViewService runViews;
    private final RunOrchestrator orchestrator;
    private final RunLogService runLogs;
    private final RunPlanner planner;
    private final ScriptService scripts;
    private final GitRepoService gitRepos;
    private final GeneratorRepository generators;
    private final ScheduledRunService schedule;
    private final LoadService load;
    private final LoadProfileStore profileStore;
    private final StepResultService results;
    private final CustomChartService customCharts;
    private final ru.k6lab.server.result.ReportService reports;
    private final ru.k6lab.server.settings.SettingsService settings;

    public RunApiController(RunService runs, RunViewService runViews, RunOrchestrator orchestrator,
                            RunLogService runLogs, RunPlanner planner, ScriptService scripts,
                            GitRepoService gitRepos, GeneratorRepository generators,
                            ScheduledRunService schedule, LoadService load,
                            LoadProfileStore profileStore, StepResultService results,
                            CustomChartService customCharts,
                            ru.k6lab.server.result.ReportService reports,
                            ru.k6lab.server.settings.SettingsService settings) {
        this.runs = runs;
        this.runViews = runViews;
        this.orchestrator = orchestrator;
        this.runLogs = runLogs;
        this.planner = planner;
        this.scripts = scripts;
        this.gitRepos = gitRepos;
        this.generators = generators;
        this.schedule = schedule;
        this.load = load;
        this.profileStore = profileStore;
        this.results = results;
        this.customCharts = customCharts;
        this.reports = reports;
        this.settings = settings;
    }

    public record StartRunPayload(Long profileId, Integer count, List<Long> generatorIds,
                                  String httpDebug, List<String> flags) {
    }

    public record ScheduleRunPayload(Long profileId, String runAt, String queuePosition,
                                     Integer count, List<Long> generatorIds, Boolean shift,
                                     Map<String, Double> intensityPct, Map<String, String> env) {
    }

    public record ControlPayload(String action, Integer vus) {
    }

    public record ConclusionPayload(String conclusion, String comment, Map<String, Object> reportConfig) {
    }

    @GetMapping("/scripts/{id}/run-init")
    public Map<String, Object> runInit(@PathVariable Long id, @RequestParam(required = false) Long profile) {
        Script s = scripts.get(id);
        List<Generator> allGens = generators.findAll().stream().sorted(Comparator.comparing(Generator::getName)).toList();
        RunPlan basePlan = planner.plan(id, s.getGeneratorCount());
        var profList = profileStore.list(id);

        Map<String, Object> detailsByProfile = new LinkedHashMap<>();
        detailsByProfile.put("", summaryOf("Базовая нагрузка скрипта", basePlan, s.getEnv(), load.profileOperationNames(id).getOrDefault(0L, basePlan.operations().stream().map(o -> o.getName()).toList())));

        Long firstValidProfileId = null;
        for (var p : profList) {
            try {
                var built = load.fromProfile(id, p.id());
                RunPlan pp = planner.plan(id, s.getGeneratorCount(), built);
                detailsByProfile.put(String.valueOf(p.id()), summaryOf(p.name(), pp, built.env(), pp.operations().stream().map(o -> o.getName()).toList()));
                if (firstValidProfileId == null && pp.ok()) {
                    firstValidProfileId = p.id();
                }
            } catch (RuntimeException e) {
                Map<String, Object> bad = new LinkedHashMap<>();
                bad.put("title", p.name());
                bad.put("durationSec", 0);
                bad.put("env", Map.of());
                bad.put("errors", List.of(e.getMessage() != null ? e.getMessage() : "Профиль повреждён"));
                bad.put("ops", List.of());
                detailsByProfile.put(String.valueOf(p.id()), bad);
            }
        }

        Long selected = profile;
        boolean autoSelected = false;
        if (selected == null && !basePlan.ok() && firstValidProfileId != null) {
            selected = firstValidProfileId;
            autoSelected = true;
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("script", ScriptApiController.toScriptDto(s, s.getGitRepoId() != null ? safeRepo(s.getGitRepoId()) : null));
        out.put("defaultCount", s.getGeneratorCount());
        out.put("freeCount", allGens.stream().filter(Generator::isAvailableForRuns).count());
        out.put("baseOk", basePlan.ok());
        out.put("selectedProfileId", selected);
        out.put("autoSelectedProfile", autoSelected);
        out.put("blockReason", runs.blockReason().orElse(null));
        out.put("profiles", profList.stream().map(p -> Map.of(
                "id", p.id(),
                "name", p.name(),
                "folder", p.folder() != null ? p.folder() : ""
        )).toList());
        out.put("profileDetails", detailsByProfile);
        out.put("pendingScheduleCount", schedule.pendingOf(id).size());
        out.put("generators", allGens.stream().map(g -> {
            Map<String, Object> gm = new LinkedHashMap<>();
            gm.put("id", g.getId());
            gm.put("name", g.getName());
            gm.put("host", g.getHost());
            gm.put("status", g.getStatus().name());
            gm.put("statusTitle", g.getStatus().getTitle());
            gm.put("busy", g.isBusy());
            gm.put("availableForRuns", g.isAvailableForRuns());
            return gm;
        }).toList());
        out.put("k6Flags", Arrays.stream(K6Flag.values()).filter(f -> !f.isHttpDebug()).map(f -> Map.of(
                "name", f.name(),
                "arg", f.getArg(),
                "title", f.getTitle(),
                "description", f.getDescription(),
                "changesLoad", f.isChangesLoad()
        )).toList());
        return out;
    }

    @PostMapping("/scripts/{id}/run")
    public Map<String, Object> startRun(@PathVariable Long id, @RequestBody StartRunPayload req) throws IOException {
        List<String> flagIds = new ArrayList<>(req.flags() != null ? req.flags() : List.of());
        if ("headers".equals(req.httpDebug())) {
            flagIds.add(K6Flag.HTTP_DEBUG.name());
        } else if ("full".equals(req.httpDebug())) {
            flagIds.add(K6Flag.HTTP_DEBUG_FULL.name());
        }
        List<K6Flag> flags = K6Flag.parse(flagIds);

        List<Long> chosen;
        if (req.generatorIds() != null && !req.generatorIds().isEmpty()) {
            chosen = req.generatorIds().stream().sorted().toList();
        } else {
            int n = req.count() == null ? scripts.get(id).getGeneratorCount() : req.count();
            if (n < 1) {
                throw new IllegalArgumentException("Число генераторов должно быть не меньше 1");
            }
            List<Long> free = generators.findAll().stream()
                    .filter(Generator::isAvailableForRuns)
                    .sorted(Comparator.comparing(Generator::getName))
                    .map(Generator::getId)
                    .toList();
            if (free.size() < n) {
                throw new IllegalArgumentException("Свободных генераторов: " + free.size() + ", а нужно " + n);
            }
            chosen = free.subList(0, n);
        }
        List<RunService.GeneratorSel> sel = chosen.stream()
                .map(gid -> new RunService.GeneratorSel(gid, 1))
                .toList();
        RunService.Overrides ov = null;
        if (req.profileId() != null) {
            String pName = load.fromProfile(id, req.profileId()).profileName();
            ov = new RunService.Overrides(null, Map.of(), null, null, req.profileId(), pName);
        }
        Run run = runs.create(new RunService.CreateRequest(id, sel, Run.FailurePolicy.CONTINUE, 120, flags, ov));
        if ((req.generatorIds() == null || req.generatorIds().isEmpty()) && req.count() != null && req.count() >= 1) {
            scripts.rememberGeneratorCount(id, req.count());
        }
        return Map.of("ok", true, "runId", run.getId());
    }

    @PostMapping("/scripts/{id}/schedule")
    public Map<String, Object> scheduleRun(@PathVariable Long id, @RequestBody ScheduleRunPayload req, Principal user) {
        String by = user != null ? user.getName() : "system";
        List<Long> gids = req.generatorIds() != null ? req.generatorIds() : List.of();
        Map<String, String> env = req.env() != null ? req.env() : Map.of();
        Map<String, Double> pct = req.intensityPct() != null ? req.intensityPct() : Map.of();

        if ("front".equalsIgnoreCase(req.queuePosition()) || "back".equalsIgnoreCase(req.queuePosition())) {
            ScheduledRunService.QueuePosition pos = "front".equalsIgnoreCase(req.queuePosition())
                    ? ScheduledRunService.QueuePosition.FRONT
                    : ScheduledRunService.QueuePosition.BACK;
            var enq = schedule.enqueueParams(id, req.profileId(), gids, req.count(), env, pct, pos, by);
            return Map.of(
                    "ok", true,
                    "scheduleId", enq.scheduleId(),
                    "runAt", enq.runAt(),
                    "message", "Добавлено в очередь на " + enq.runAt()
            );
        }

        Instant runAt = (req.runAt() != null && !req.runAt().isBlank()) ? Instant.parse(req.runAt().trim()) : null;
        boolean shift = Boolean.TRUE.equals(req.shift());
        long sid = schedule.schedule(id, runAt, gids, req.count(), env, pct, req.profileId(), by, shift);
        return Map.of("ok", true, "scheduleId", sid, "message", "Запуск поставлен в расписание (#" + sid + ")");
    }

    @GetMapping("/runs")
    public Map<String, Object> listRuns(@RequestParam(required = false) Long scriptId,
                                        @RequestParam(required = false) RunStatus status,
                                        @RequestParam(required = false) String group,
                                        @RequestParam(required = false) String q,
                                        @RequestParam(defaultValue = "0") int page,
                                        @RequestParam(defaultValue = "30") int size) {
        Page<Run> p = runs.searchFiltered(scriptId, status, group, q, Math.max(0, page), Math.min(100, Math.max(1, size)));
        List<Map<String, Object>> activeDtos = runs.activeAndQueuedRuns().stream()
                .filter(r -> scriptId == null || scriptId.equals(r.getScriptId()))
                .sorted(Comparator.comparing(Run::getId).reversed())
                .map(r -> DashboardApiController.toRunViewDto(runViews.view(r.getId())))
                .toList();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("items", p.getContent().stream().map(DashboardApiController::toRunSummaryDto).toList());
        out.put("activeRuns", activeDtos);
        out.put("counts", runs.groupCounts(scriptId));
        out.put("page", p.getNumber());
        out.put("size", p.getSize());
        out.put("totalElements", p.getTotalElements());
        out.put("totalPages", p.getTotalPages());
        out.put("scripts", scripts.list().stream().map(s -> Map.of("id", s.getId(), "name", s.getName())).toList());
        return out;
    }

    @GetMapping("/runs/{id}")
    public Map<String, Object> runDetail(@PathVariable Long id) {
        RunViewService.RunView rv = runViews.view(id);
        Run r = rv.run();
        Map<String, Object> runDto = DashboardApiController.toRunViewDto(rv);
        runDto.put("initSec", r.getInitSec());
        runDto.put("k6Flags", r.getRequest() != null ? r.getRequest().get("k6Flags") : null);

        String grafanaUrl = null;
        String tpl = settings.get(ru.k6lab.server.settings.SettingsService.GRAFANA_URL_TPL, "");
        if (!tpl.isBlank() && r.getStartedAt() != null) {
            Instant end = r.getFinishedAt() != null ? r.getFinishedAt() : Instant.now().plusSeconds(60);
            grafanaUrl = ru.k6lab.core.util.PromQlTemplate.renderUrl(tpl, r.getStartedAt(), end, r.getId());
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("run", runDto);
        out.put("snapshot", rv.snapshot());
        out.put("steps", rv.steps());
        out.put("k6Config", r.getK6Config());
        out.put("conclusion", r.getConclusion());
        out.put("conclusionComment", r.getConclusionComment());
        out.put("failurePolicyTitle", r.getFailurePolicy().getTitle());
        out.put("grafanaUrl", grafanaUrl);
        out.put("envRows", ru.k6lab.server.result.ReportFormat.envRows(rv.snapshot().env(), r.getK6Config()));
        out.put("customChecksConfig", results.getEffectiveCustomChecks(r, rv.snapshot()).stream().map(cc -> {
            Map<String, Object> cm = new LinkedHashMap<>();
            cm.put("name", cc.name());
            cm.put("promql", cc.promql());
            cm.put("thresholdText", cc.formatThreshold());
            cm.put("stepSla", cc.stepSla());
            cm.put("autoStop", cc.autoStop());
            return cm;
        }).toList());
        out.put("generators", rv.generators().stream().map(gv -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", gv.rg().getId());
            m.put("generatorId", gv.rg().getGeneratorId());
            m.put("generatorName", gv.rg().getGeneratorName());
            m.put("status", gv.rg().getStatus().name());
            m.put("k6Status", gv.live() != null ? gv.live().k6Status() : null);
            m.put("exitCode", gv.rg().getExitCode());
            m.put("segment", gv.rg().segment());
            m.put("weight", gv.rg().getWeight());
            m.put("droppedLogLines", gv.rg().getDroppedLogLines());
            m.put("cpu", gv.cpu());
            m.put("memPct", gv.memPct());
            m.put("cpuHot", gv.cpuHot());
            m.put("offline", gv.offline());
            m.put("vus", gv.live() != null ? gv.live().vus() : gv.rg().getLastVus());
            m.put("vusMax", gv.live() != null ? gv.live().vusMax() : null);
            m.put("iterations", gv.live() != null ? gv.live().iterations() : gv.rg().getLastIterations());
            m.put("dropped", gv.live() != null ? gv.live().dropped() : gv.rg().getLastDropped());
            m.put("prepareError", gv.rg().getPrepareError());
            return m;
        }).toList());
        return out;
    }

    @PostMapping("/runs/{id}/requeue")
    public Map<String, Object> requeueRun(@PathVariable Long id,
                                          @RequestBody(required = false) Map<String, String> body,
                                          Principal user) {
        String posStr = body != null ? body.getOrDefault("position", "back") : "back";
        ScheduledRunService.QueuePosition pos = "front".equalsIgnoreCase(posStr)
                ? ScheduledRunService.QueuePosition.FRONT
                : ScheduledRunService.QueuePosition.BACK;
        var res = schedule.requeueRun(id, pos, user != null ? user.getName() : "system");
        String where = pos == ScheduledRunService.QueuePosition.FRONT
                ? "в начало очереди (после текущего запуска)"
                : "в конец очереди";
        String shifted = res.shiftedCount() > 0 ? ", сдвинуто ожидающих записей: " + res.shiftedCount() : "";
        String msg = "Прогон #" + id + " («" + res.title() + "») добавлен " + where
                + " (#" + res.scheduleId() + " · старт " + new ru.k6lab.server.result.ReportFormat().dt(res.runAt()) + shifted + ")";
        return Map.of(
                "ok", true,
                "scheduleId", res.scheduleId(),
                "runAt", res.runAt(),
                "shiftedCount", res.shiftedCount(),
                "message", msg
        );
    }

    @GetMapping("/runs/{id}/logs")
    public Map<String, Object> runLogs(@PathVariable Long id,
                                       @RequestParam(defaultValue = "400") int limit,
                                       @RequestParam(required = false) Long generatorId) {
        List<RunLogService.Row> rows = runLogs.tail(id, Math.min(2000, Math.max(50, limit)));
        var filtered = rows.stream()
                .filter(r -> generatorId == null || generatorId.equals(r.generatorId()))
                .map(r -> Map.of(
                        "ts", r.ts(),
                        "generatorId", r.generatorId() != null ? r.generatorId() : 0L,
                        "level", r.level() != null ? r.level() : "INFO",
                        "line", r.line() != null ? r.line() : ""
                ))
                .toList();
        return Map.of("lines", filtered);
    }

    @PostMapping("/runs/{id}/stop")
    public Map<String, Object> stop(@PathVariable Long id) {
        orchestrator.stop(id, "остановлено пользователем");
        return Map.of("ok", true);
    }

    @PostMapping("/runs/{id}/abort")
    public Map<String, Object> abort(@PathVariable Long id) {
        orchestrator.abort(id);
        return Map.of("ok", true);
    }

    @PostMapping("/runs/{id}/control")
    public Map<String, Object> control(@PathVariable Long id, @RequestBody ControlPayload req) {
        orchestrator.control(id, req.action(), req.vus());
        return Map.of("ok", true);
    }

    @GetMapping("/runs/{id}/results")
    public Map<String, Object> runResults(@PathVariable Long id,
                                          @RequestParam(defaultValue = "false") boolean refresh,
                                          @RequestParam(required = false) Boolean skipWarmup,
                                          @RequestParam(required = false) String warmup,
                                          @RequestParam(required = false) String time,
                                          @RequestParam(required = false) String sort,
                                          @RequestParam(required = false) String dir) {
        Run run = runs.get(id);
        boolean terminal = run.getStatus().isTerminal();
        boolean livePreview = !terminal && (run.getStatus() == RunStatus.RUNNING || run.getStatus() == RunStatus.STOPPING);
        ResultData data;
        if (terminal) {
            data = refresh ? results.compute(id) : results.getOrCompute(id);
        } else if (livePreview) {
            data = results.preview(id, refresh);
        } else {
            data = results.load(id).orElse(null);
        }

        RunSnapshot snap = runs.snapshot(run);
        long warmSec = ru.k6lab.server.result.ReportService.warmupSec(snap);
        Boolean effAbs = time == null || time.isBlank() ? null
                : ("abs".equalsIgnoreCase(time.trim()) ? Boolean.TRUE : ("rel".equalsIgnoreCase(time.trim()) ? Boolean.FALSE : null));
        Boolean effSkip = parseWarmupParam(warmup, skipWarmup);
        ru.k6lab.server.result.ChartStyle.SortBy effSort = parseSortByParam(sort);
        ru.k6lab.server.result.ChartStyle.SortDir effDir = parseSortDirParam(dir);

        List<Map<String, Object>> charts = List.of();
        if (data != null && data.prometheusOk()) {
            try {
                var imgs = reports.allChartsForEditor(run, snap, data, effAbs, effSort, effDir, effSkip);
                charts = imgs.stream().map(im -> {
                    Map<String, Object> cm = new LinkedHashMap<>();
                    cm.put("category", im.category());
                    cm.put("title", im.title());
                    cm.put("dataUri", im.dataUri());
                    cm.put("included", im.included());
                    return cm;
                }).toList();
            } catch (RuntimeException ignored) {
            }
        }

        Map<String, Object> maxMeta = null;
        if (data != null) {
            var mc = data.maxCapacity();
            if (mc != null) {
                Map<String, Object> mm = new LinkedHashMap<>();
                mm.put("headline", mc.headline());
                mm.put("detail", mc.detail());
                mm.put("css", mc.css());
                mm.put("allPassed", mc.allPassed());
                mm.put("confirmedStepNo", mc.confirmedStep() != null ? mc.confirmedStep().stepNo() : null);
                mm.put("firstFailedStepNo", mc.firstFailedStep() != null ? mc.firstFailedStep().stepNo() : null);
                maxMeta = mm;
            }
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("terminal", terminal);
        out.put("livePreview", livePreview);
        out.put("defaultTitle", ru.k6lab.server.result.ReportService.defaultTitle(run));
        out.put("reportConfig", ru.k6lab.server.result.ReportConfig.fromRun(run).toMap());
        out.put("warmupSec", warmSec);
        out.put("conclusions", CONCLUSIONS);
        out.put("conclusion", run.getConclusion());
        out.put("conclusionComment", run.getConclusionComment());
        out.put("data", data);
        out.put("maxSummary", maxMeta);
        out.put("charts", charts);
        return out;
    }

    @PostMapping("/runs/{id}/conclusion")
    public Map<String, Object> saveConclusion(@PathVariable Long id, @RequestBody ConclusionPayload req) {
        runs.setReportDetails(id, req.conclusion(), req.comment(), req.reportConfig());
        return Map.of("ok", true, "reportConfig", ru.k6lab.server.result.ReportConfig.fromRun(runs.get(id)).toMap());
    }

    @PostMapping("/runs/{id}/report-config")
    public Map<String, Object> saveReportConfig(@PathVariable Long id, @RequestBody Map<String, Object> req) {
        runs.setReportConfigOnly(id, req);
        return Map.of("ok", true, "reportConfig", ru.k6lab.server.result.ReportConfig.fromRun(runs.get(id)).toMap());
    }

    private static Boolean parseWarmupParam(String warmup, Boolean legacySkipWarmup) {
        if (warmup != null && !warmup.isBlank()) {
            String v = warmup.trim().toLowerCase(java.util.Locale.ROOT);
            if ("skip".equals(v) || "after".equals(v) || "true".equals(v) || "1".equals(v)) return Boolean.TRUE;
            if ("include".equals(v) || "all".equals(v) || "false".equals(v) || "0".equals(v)) return Boolean.FALSE;
        }
        return legacySkipWarmup;
    }

    private static ru.k6lab.server.result.ChartStyle.SortBy parseSortByParam(String s) {
        if (s == null || s.isBlank()) return null;
        try {
            return ru.k6lab.server.result.ChartStyle.SortBy.valueOf(s.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static ru.k6lab.server.result.ChartStyle.SortDir parseSortDirParam(String s) {
        if (s == null || s.isBlank()) return null;
        try {
            return ru.k6lab.server.result.ChartStyle.SortDir.valueOf(s.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static Map<String, Object> summaryOf(String title, RunPlan p, Map<String, String> env, List<String> opNames) {
        int dur = 0;
        if (p.config() != null) {
            long span = ConfigDuration.of(p.config()).totalSec();
            if (span > 0) dur = (int) Math.min(Integer.MAX_VALUE, span);
        }
        if (dur == 0 && p.steps() != null) {
            dur = p.steps().stream().mapToInt(StepPlan::totalSec).sum();
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("title", title);
        m.put("durationSec", dur);
        m.put("env", env != null ? env : Map.of());
        m.put("envText", LoadForm.formatKv(env != null ? env : Map.of()));
        m.put("opNames", opNames);
        m.put("errors", p.errors());
        m.put("ops", p.operations().stream().map(o -> Map.of(
                "name", o.getName(),
                "execFn", o.getExecFn(),
                "ratePerSec", o.getRatePerSec(),
                "executor", o.getExecutor(),
                "slaPercentile", o.getSlaPercentile(),
                "slaThresholdMs", o.getSlaThresholdMs()
        )).toList());
        return m;
    }

    private ru.k6lab.server.git.GitRepo safeRepo(Long repoId) {
        try {
            return gitRepos.get(repoId);
        } catch (RuntimeException e) {
            return null;
        }
    }
}
