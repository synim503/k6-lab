package ru.k6lab.server.run;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import ru.k6lab.core.runstate.RunStatus;
import ru.k6lab.server.generator.GeneratorService;
import ru.k6lab.server.generator.GeneratorViewService;
import ru.k6lab.server.scenario.ScenarioPlan;
import ru.k6lab.server.scenario.ScenarioPlanner;
import ru.k6lab.server.scenario.ScenarioService;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Controller
@RequestMapping("/runs")
public class RunController {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final RunService runService;
    private final RunViewService views;
    private final RunOrchestrator orchestrator;
    private final RunEventBroadcaster broadcaster;
    private final RunLogService logs;
    private final ScenarioService scenarios;
    private final ScenarioPlanner planner;
    private final GeneratorViewService generatorViews;
    private final GeneratorService generatorService;
    private final ru.k6lab.server.settings.SettingsService settings;

    public RunController(RunService runService, RunViewService views, RunOrchestrator orchestrator, RunEventBroadcaster broadcaster,
                         RunLogService logs, ScenarioService scenarios, ScenarioPlanner planner,
                         GeneratorViewService generatorViews, GeneratorService generatorService,
                         ru.k6lab.server.settings.SettingsService settings) {
        this.settings = settings;
        this.runService = runService;
        this.views = views;
        this.orchestrator = orchestrator;
        this.broadcaster = broadcaster;
        this.logs = logs;
        this.scenarios = scenarios;
        this.planner = planner;
        this.generatorViews = generatorViews;
        this.generatorService = generatorService;
    }

    // ------------------------------------------------------------------ история

    @GetMapping
    public String history(@RequestParam(required = false) Long scenarioId, @RequestParam(required = false) RunStatus status, Model model) {
        List<Run> runs = runService.search(scenarioId, status, 200);
        model.addAttribute("runs", runs);
        model.addAttribute("scenarios", scenarios.list());
        model.addAttribute("scenarioId", scenarioId);
        model.addAttribute("status", status);
        model.addAttribute("statuses", RunStatus.values());
        return "runs/list";
    }

    // ------------------------------------------------------------------ мастер запуска

    @GetMapping("/new")
    public String wizard(@RequestParam(required = false) Long scenarioId, Model model) {
        model.addAttribute("scenarios", scenarios.list());
        model.addAttribute("scenarioId", scenarioId);
        model.addAttribute("generators", generatorViews.all());
        model.addAttribute("pools", generatorService.pools());
        model.addAttribute("blockReason", runService.blockReason().orElse(null));
        model.addAttribute("policies", Run.FailurePolicy.values());
        return "runs/new";
    }

    /** Подтверждение: итоговая нагрузка, VU и сегменты при выбранном составе (htmx). */
    @PostMapping("/preview")
    public String preview(@RequestParam Long scenarioId, @RequestParam Map<String, String> all, Model model) {
        try {
            RunService.CreateRequest req = parse(scenarioId, all);
            int n = req.poolId() != null ? req.poolCount() : req.generators().size();
            ScenarioPlan plan = planner.plan(scenarios.get(scenarioId), Math.max(1, n), null);
            model.addAttribute("plan", plan);
            model.addAttribute("n", Math.max(1, n));
            List<Map<String, Object>> shares = new ArrayList<>();
            if (req.poolId() == null) {
                int total = req.generators().stream().mapToInt(RunService.GeneratorSel::weight).sum();
                for (RunService.GeneratorSel g : req.generators()) {
                    var gv = generatorViews.one(g.generatorId());
                    shares.add(Map.of("name", gv.g().getName(), "weight", g.weight(), "pct", Math.round(1000.0 * g.weight() / total) / 10.0,
                            "busy", !gv.g().isAvailableForRuns()));
                }
            }
            model.addAttribute("shares", shares);
            if (plan.generated() != null) {
                double rate = plan.generated().totalPeakRatePerSec();
                long vus = plan.generated().totalPeakVus();
                long pre = plan.generated().operations().stream().mapToLong(o -> o.preAllocatedVus()).sum();
                long max = plan.generated().operations().stream().mapToLong(o -> o.maxVus()).sum();
                model.addAttribute("peakRate", rate);
                model.addAttribute("peakVus", vus);
                model.addAttribute("preAlloc", pre);
                model.addAttribute("maxVus", max);
                model.addAttribute("perGenRate", rate / Math.max(1, n));
                model.addAttribute("perGenVus", Math.ceil(vus / (double) Math.max(1, n)));
                model.addAttribute("perGenPre", Math.ceil(pre / (double) Math.max(1, n)));
                model.addAttribute("perGenMax", Math.ceil(max / (double) Math.max(1, n)));
            }
            boolean multi = req.generators() != null && req.generators().size() > 1 || req.poolId() != null;
            model.addAttribute("multi", multi);
            // несколько run на одном генераторе конкурируют за ресурсы
            List<String> contention = new ArrayList<>();
            if (req.generators() != null) {
                for (RunService.GeneratorSel g : req.generators()) {
                    var gv = generatorViews.one(g.generatorId());
                    if (gv.g().getMaxConcurrentRuns() > 1) {
                        contention.add(gv.g().getName() + ": разрешено " + gv.g().getMaxConcurrentRuns()
                                + " одновременных run - тесты будут конкурировать за ресурсы генератора");
                    }
                }
            }
            model.addAttribute("contention", contention);
        } catch (IllegalArgumentException | IllegalStateException e) {
            model.addAttribute("error", e.getMessage());
        }
        return "runs/_preview :: preview";
    }

    @PostMapping
    public String create(@RequestParam Long scenarioId, @RequestParam Map<String, String> all, RedirectAttributes ra) {
        try {
            Run run = runService.create(parse(scenarioId, all));
            ra.addFlashAttribute("info", "Запуск поставлен в очередь");
            return "redirect:/runs/" + run.getId();
        } catch (IllegalArgumentException | IllegalStateException | IOException e) {
            ra.addFlashAttribute("error", e.getMessage());
            return "redirect:/runs/new?scenarioId=" + scenarioId;
        }
    }

    /** Разбор формы мастера: чекбоксы g.{id}, веса w.{id}, либо пул. */
    static RunService.CreateRequest parse(Long scenarioId, Map<String, String> all) {
        Run.FailurePolicy policy = all.get("failurePolicy") == null ? Run.FailurePolicy.CONTINUE : Run.FailurePolicy.valueOf(all.get("failurePolicy"));
        Integer timeout = all.get("prepareTimeoutSec") == null || all.get("prepareTimeoutSec").isBlank() ? null : Integer.parseInt(all.get("prepareTimeoutSec").trim());
        if ("POOL".equals(all.get("mode"))) {
            if (all.get("poolId") == null || all.get("poolId").isBlank()) {
                throw new IllegalArgumentException("Выберите пул");
            }
            return new RunService.CreateRequest(scenarioId, List.of(), Long.parseLong(all.get("poolId")),
                    all.get("poolCount") == null || all.get("poolCount").isBlank() ? 1 : Integer.parseInt(all.get("poolCount").trim()), policy, timeout);
        }
        List<RunService.GeneratorSel> sel = new ArrayList<>();
        for (Map.Entry<String, String> e : all.entrySet()) {
            if (e.getKey().startsWith("g.") && !"false".equals(e.getValue())) {
                long id = Long.parseLong(e.getKey().substring(2));
                String w = all.get("w." + id);
                int weight = w == null || w.isBlank() ? 1 : Integer.parseInt(w.trim());
                sel.add(new RunService.GeneratorSel(id, weight));
            }
        }
        sel.sort(java.util.Comparator.comparingLong(RunService.GeneratorSel::generatorId));
        return new RunService.CreateRequest(scenarioId, sel, null, null, policy, timeout);
    }

    // ------------------------------------------------------------------ live и результаты

    @GetMapping("/{id}")
    public String view(@PathVariable Long id, Model model) {
        RunViewService.RunView v = views.view(id);
        model.addAttribute("v", v);
        model.addAttribute("run", v.run());
        model.addAttribute("snap", v.snapshot());
        model.addAttribute("blockedControl", v.canControl());
        model.addAttribute("grafanaUrl", grafanaUrl(v));
        return "runs/view";
    }

    /** Ссылка на дашборд Grafana: шаблон SUT, иначе общий из настроек; подставляются run_id и границы времени. */
    private String grafanaUrl(RunViewService.RunView v) {
        String tpl = v.snapshot().sut() != null && v.snapshot().sut().grafanaUrlTpl() != null && !v.snapshot().sut().grafanaUrlTpl().isBlank()
                ? v.snapshot().sut().grafanaUrlTpl() : settings.get(ru.k6lab.server.settings.SettingsService.GRAFANA_URL_TPL, "");
        if (tpl.isBlank() || v.run().getStartedAt() == null) {
            return null;
        }
        java.time.Instant end = v.run().getFinishedAt() != null ? v.run().getFinishedAt() : java.time.Instant.now().plusSeconds(60);
        return ru.k6lab.core.util.PromQlTemplate.renderUrl(tpl, v.run().getStartedAt(), end, v.run().getId());
    }

    /** Фрагмент статуса: перезагружается по SSE-событию status и раз в 5 с. */
    @GetMapping("/{id}/status")
    public String status(@PathVariable Long id, Model model) {
        RunViewService.RunView v = views.view(id);
        model.addAttribute("v", v);
        model.addAttribute("run", v.run());
        model.addAttribute("snap", v.snapshot());
        return "runs/_status :: status";
    }

    @GetMapping(value = "/{id}/stream/logs", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamLogs(@PathVariable Long id) {
        // для завершённых run недавний хвост берём из БД, для идущих - из памяти (без чтения БД на каждое событие)
        List<RunEventBroadcaster.Line> fallback = new ArrayList<>();
        if (!orchestrator.isActive(id)) {
            var names = new java.util.HashMap<Long, String>();
            runService.generatorsOf(id).forEach(g -> names.put(g.getGeneratorId(), g.getGeneratorName()));
            for (RunLogService.Row r : logs.tail(id, 500)) {
                fallback.add(new RunEventBroadcaster.Line(r.ts().toEpochMilli(), r.generatorId(), names.get(r.generatorId()), r.level(), r.line()));
            }
        }
        return broadcaster.subscribeLogs(id, fallback);
    }

    @GetMapping(value = "/{id}/stream/status", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamStatus(@PathVariable Long id) {
        return broadcaster.subscribeStatus(id);
    }

    @GetMapping(value = "/{id}/stream/metrics", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamMetrics(@PathVariable Long id) {
        return broadcaster.subscribeMetrics(id);
    }

    @GetMapping("/{id}/log.txt")
    public ResponseEntity<StreamingResponseBody> downloadLog(@PathVariable Long id) {
        StreamingResponseBody body = out -> logs.writeFull(id, out);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("text/plain;charset=UTF-8"))
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename("run-" + id + ".log").build().toString())
                .body(body);
    }

    // ------------------------------------------------------------------ управление

    @PostMapping("/{id}/stop")
    public String stop(@PathVariable Long id, RedirectAttributes ra) {
        try {
            orchestrator.stop(id, "остановлено пользователем");
        } catch (IllegalStateException e) {
            ra.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/runs/" + id;
    }

    @PostMapping("/{id}/abort")
    public String abort(@PathVariable Long id, RedirectAttributes ra) {
        try {
            orchestrator.abort(id);
        } catch (IllegalStateException e) {
            ra.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/runs/" + id;
    }

    @PostMapping("/{id}/control")
    public String control(@PathVariable Long id, @RequestParam String action, @RequestParam(required = false) Integer vus,
                          RedirectAttributes ra) {
        try {
            orchestrator.control(id, action, vus);
        } catch (IllegalStateException | IllegalArgumentException e) {
            ra.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/runs/" + id;
    }
}
