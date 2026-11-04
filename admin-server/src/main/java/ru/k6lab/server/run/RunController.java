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

import java.util.ArrayList;
import java.util.List;

@Controller
@RequestMapping("/runs")
public class RunController {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final RunService runService;
    private final RunViewService views;
    private final RunOrchestrator orchestrator;
    private final RunEventBroadcaster broadcaster;
    private final RunLogService logs;
    private final ru.k6lab.server.settings.SettingsService settings;

    public RunController(RunService runService, RunViewService views, RunOrchestrator orchestrator, RunEventBroadcaster broadcaster,
                         RunLogService logs, ru.k6lab.server.settings.SettingsService settings) {
        this.settings = settings;
        this.runService = runService;
        this.views = views;
        this.orchestrator = orchestrator;
        this.broadcaster = broadcaster;
        this.logs = logs;
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

    /** Ссылка на дашборд Grafana из настроек; подставляются run_id и границы времени. */
    private String grafanaUrl(RunViewService.RunView v) {
        String tpl = settings.get(ru.k6lab.server.settings.SettingsService.GRAFANA_URL_TPL, "");
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

    /** Все точки метрик с начала теста (массив JSON): график на странице строится целиком, затем дополняется live-потоком. */
    @GetMapping(value = "/{id}/metrics/history", produces = MediaType.APPLICATION_JSON_VALUE)
    @org.springframework.web.bind.annotation.ResponseBody
    public String metricsHistory(@PathVariable Long id) {
        return "[" + String.join(",", broadcaster.metricsHistory(id)) + "]";
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
