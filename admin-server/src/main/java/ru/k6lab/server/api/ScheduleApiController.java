package ru.k6lab.server.api;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import ru.k6lab.server.generator.Generator;
import ru.k6lab.server.generator.GeneratorRepository;
import ru.k6lab.server.load.LoadProfileStore;
import ru.k6lab.server.load.ProfileImportService;
import ru.k6lab.server.schedule.ScheduleGuardService;
import ru.k6lab.server.schedule.ScheduleGuardStore;
import ru.k6lab.server.schedule.ScheduleImportService;
import ru.k6lab.server.schedule.ScheduleTimelineService;
import ru.k6lab.server.schedule.ScheduledRunService;
import ru.k6lab.server.schedule.ScheduledRunStore;
import ru.k6lab.server.script.ScriptService;
import ru.k6lab.server.ssh.SshCredentialService;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.Principal;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** REST API планировщика очереди тестов и автопроверок метрик Prometheus/SSH. */
@RestController
@RequestMapping("/api/v1/schedule")
public class ScheduleApiController {

    private final ScheduledRunService schedule;
    private final ScheduleTimelineService timeline;
    private final ScheduleImportService scheduleImport;
    private final ProfileImportService profileImport;
    private final ScheduleGuardService guards;
    private final ScriptService scripts;
    private final LoadProfileStore profiles;
    private final GeneratorRepository generators;
    private final SshCredentialService sshCreds;
    private final ru.k6lab.server.run.RunService runService;

    public ScheduleApiController(ScheduledRunService schedule, ScheduleTimelineService timeline,
                                 ScheduleImportService scheduleImport, ProfileImportService profileImport,
                                 ScheduleGuardService guards, ScriptService scripts,
                                 LoadProfileStore profiles, GeneratorRepository generators,
                                 SshCredentialService sshCreds,
                                 ru.k6lab.server.run.RunService runService) {
        this.schedule = schedule;
        this.timeline = timeline;
        this.scheduleImport = scheduleImport;
        this.profileImport = profileImport;
        this.guards = guards;
        this.scripts = scripts;
        this.profiles = profiles;
        this.generators = generators;
        this.sshCreds = sshCreds;
        this.runService = runService;
    }

    public record IdsReq(List<Long> ids, String position) {
    }

    public record MoveReq(String position) {
    }

    public record ShiftReq(boolean shiftForward) {
    }

    public record GuardReq(Long id, String name, Boolean enabled, String promql, String cmp,
                           Double threshold, String unit, String runWhen, String sshHost,
                           Integer sshPort, Long sshCredentialId, Boolean useSudo,
                           String script, Integer timeoutSec, Integer cooldownSec, Boolean blockOnFail) {
    }

    @GetMapping
    public Map<String, Object> index() {
        List<Generator> allGens = generators.findAll().stream().sorted(Comparator.comparing(Generator::getName)).toList();
        Map<Long, String> genNames = new HashMap<>();
        allGens.forEach(g -> genNames.put(g.getId(), g.getName()));

        List<ScheduledRunStore.Item> recent = schedule.recent();
        List<Map<String, Object>> items = recent.stream().map(it -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", it.id());
            m.put("scriptId", it.scriptId());
            m.put("scriptName", it.scriptName());
            m.put("profileId", it.profileId());
            m.put("profileName", it.profileName());
            m.put("runAt", it.runAt());
            m.put("createdAt", it.createdAt());
            m.put("createdBy", it.createdBy());
            m.put("generatorIds", it.generatorIds());
            m.put("generatorNames", it.generatorIds().stream().map(gid -> genNames.getOrDefault(gid, "#" + gid)).toList());
            m.put("generatorCount", it.generatorCount());
            m.put("env", it.env());
            m.put("intensity", it.intensity());
            m.put("status", it.status().name());
            m.put("pending", it.pending());
            m.put("runId", it.runId());
            m.put("error", it.error());
            m.put("finishedAt", it.finishedAt());
            m.put("shiftForward", it.shiftForward());
            m.put("durationSec", schedule.durationSecOf(it));
            return m;
        }).toList();

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("paused", schedule.paused());
        out.put("pauseReason", schedule.pauseReason());
        out.put("activeRuns", runService.activeRuns().stream().map(DashboardApiController::toRunSummaryDto).toList());
        out.put("items", items);
        out.put("timeline", timeline.overview());
        out.put("guards", guards.list().stream().map(ScheduleApiController::toGuardDto).toList());
        out.put("scripts", scripts.list().stream().map(s -> Map.of(
                "id", s.getId(),
                "name", s.getName(),
                "generatorCount", s.getGeneratorCount(),
                "profiles", profiles.list(s.getId()).stream().map(p -> Map.of(
                        "id", p.id(),
                        "name", p.name(),
                        "folder", p.folder() != null ? p.folder() : ""
                )).toList()
        )).toList());
        out.put("generators", allGens.stream().map(g -> Map.of(
                "id", g.getId(),
                "name", g.getName(),
                "host", g.getHost(),
                "sshPort", g.getSshPort(),
                "status", g.getStatus().name(),
                "busy", g.isBusy(),
                "availableForRuns", g.isAvailableForRuns()
        )).toList());
        out.put("sshCredentials", sshCreds.list().stream().map(c -> Map.of(
                "id", c.getId(),
                "name", c.getName(),
                "username", c.getUsername()
        )).toList());
        return out;
    }

    @PostMapping("/pause")
    public Map<String, Object> pause(Principal user) {
        schedule.pause(user != null ? user.getName() : "system");
        return Map.of("ok", true);
    }

    @PostMapping("/resume")
    public Map<String, Object> resume(Principal user) {
        var res = schedule.resume(user != null ? user.getName() : "system");
        return Map.of("ok", true, "startedRunId", res.startedRunId() != null ? res.startedRunId() : 0L, "moved", res.moved());
    }

    @PostMapping("/{id}/start-now")
    public Map<String, Object> startNow(@PathVariable long id, Principal user) {
        var res = schedule.startNow(id, user != null ? user.getName() : "system");
        return Map.of("ok", true, "startedRunId", res.startedRunId() != null ? res.startedRunId() : 0L);
    }

    @PostMapping("/{id}/cancel")
    public Map<String, Object> cancel(@PathVariable long id, Principal user) {
        schedule.cancel(id, user != null ? user.getName() : "system");
        return Map.of("ok", true);
    }

    @PostMapping("/{id}/move")
    public Map<String, Object> move(@PathVariable long id, @RequestBody MoveReq req, Principal user) {
        String by = user != null ? user.getName() : "system";
        String pos = req.position() != null ? req.position() : "front";
        if ("up".equalsIgnoreCase(pos)) {
            schedule.stepPending(id, -1, by);
        } else if ("down".equalsIgnoreCase(pos)) {
            schedule.stepPending(id, 1, by);
        } else {
            var qp = "back".equalsIgnoreCase(pos)
                    ? ScheduledRunService.QueuePosition.BACK
                    : ScheduledRunService.QueuePosition.FRONT;
            schedule.movePending(id, qp, by);
        }
        return Map.of("ok", true);
    }

    @PostMapping("/{id}/requeue")
    public Map<String, Object> requeue(@PathVariable long id, @RequestBody MoveReq req, Principal user) {
        var qp = "back".equalsIgnoreCase(req.position())
                ? ScheduledRunService.QueuePosition.BACK
                : ScheduledRunService.QueuePosition.FRONT;
        var res = schedule.requeueScheduleItem(id, qp, user != null ? user.getName() : "system");
        return Map.of("ok", true, "scheduleId", res.scheduleId());
    }

    @PostMapping("/{id}/shift")
    public Map<String, Object> shift(@PathVariable long id, @RequestBody ShiftReq req, Principal user) {
        schedule.setShiftForward(id, req.shiftForward(), user != null ? user.getName() : "system");
        return Map.of("ok", true);
    }

    @PostMapping("/reorder")
    public Map<String, Object> reorder(@RequestBody IdsReq req, Principal user) {
        int cnt = schedule.reorderPending(req.ids(), user != null ? user.getName() : "system");
        return Map.of("ok", true, "count", cnt);
    }

    @PostMapping("/bulk-requeue")
    public Map<String, Object> bulkRequeue(@RequestBody IdsReq req, Principal user) {
        var qp = "back".equalsIgnoreCase(req.position())
                ? ScheduledRunService.QueuePosition.BACK
                : ScheduledRunService.QueuePosition.FRONT;
        int cnt = schedule.requeueMany(req.ids(), qp, user != null ? user.getName() : "system");
        return Map.of("ok", true, "count", cnt);
    }

    @PostMapping("/bulk-cancel")
    public Map<String, Object> bulkCancel(@RequestBody IdsReq req, Principal user) {
        int cnt = schedule.cancelMany(req.ids(), user != null ? user.getName() : "system");
        return Map.of("ok", true, "count", cnt);
    }

    @PostMapping("/cancel-all-pending")
    public Map<String, Object> cancelAllPending(Principal user) {
        int cnt = schedule.cancelAllPending(user != null ? user.getName() : "system");
        return Map.of("ok", true, "count", cnt);
    }

    @PostMapping("/clear-history")
    public Map<String, Object> clearHistory(Principal user) {
        int cnt = schedule.clearHistory(user != null ? user.getName() : "system");
        return Map.of("ok", true, "count", cnt);
    }

    @PostMapping("/import")
    public Map<String, Object> importSchedule(@RequestParam(required = false) String json,
                                              @RequestParam(required = false) MultipartFile file,
                                              @RequestParam(required = false) Integer gapMinutes,
                                              Principal user) throws IOException {
        String body = file != null && !file.isEmpty() ? new String(file.getBytes(), StandardCharsets.UTF_8) : json;
        var created = scheduleImport.importTests(body, gapMinutes, user != null ? user.getName() : "system");
        return Map.of("ok", true, "count", created.size(), "message", "Запланировано запусков: " + created.size());
    }

    @GetMapping("/timeline")
    public Map<String, Object> getTimeline(@RequestParam(required = false) Long from,
                                           @RequestParam(required = false) Long to) {
        return timeline.overview(from, to);
    }

    @PostMapping("/import-profiles")
    public Map<String, Object> importProfiles(@RequestParam(required = false) String json,
                                              @RequestParam(required = false) MultipartFile file,
                                              @RequestParam(defaultValue = "false") boolean scheduleSequential,
                                              @RequestParam(required = false) String runAt,
                                              @RequestParam(required = false) Integer count,
                                              @RequestParam(defaultValue = "5") int gapMinutes,
                                              @RequestParam(defaultValue = "true") boolean shift,
                                              Principal user) throws IOException {
        String by = user != null ? user.getName() : "system";
        String body = file != null && !file.isEmpty() ? new String(file.getBytes(), StandardCharsets.UTF_8) : json;
        var done = profileImport.importProfiles(null, body, by);
        long upd = done.stream().filter(ProfileImportService.Imported::updated).count();
        String msg = "Импортировано профилей: " + done.size() + (upd > 0 ? " (обновлено: " + upd + ")" : "");
        int scheduledCount = 0;
        if (scheduleSequential && !done.isEmpty()) {
            java.time.Instant firstAt = (runAt != null && !runAt.isBlank()) ? java.time.Instant.parse(runAt.trim()) : null;
            var specs = done.stream()
                    .map(x -> new ScheduledRunService.SequentialSpec(x.scriptId(), x.profileId()))
                    .toList();
            var ids = schedule.scheduleSequential(specs, firstAt, count, Math.max(5, gapMinutes) * 60, shift, by);
            scheduledCount = ids.size();
            msg += ". Запланировано последовательно: " + scheduledCount;
        }
        return Map.of("ok", true, "count", done.size(), "scheduledCount", scheduledCount, "message", msg);
    }

    // ---------- Prometheus / SSH Guards ----------

    @PostMapping("/guards")
    @PreAuthorize("hasRole('ADMIN')")
    public Map<String, Object> saveGuard(@RequestBody GuardReq req, Principal user) {
        String by = user != null ? user.getName() : "admin";
        ScheduleGuardStore.GuardInput in = new ScheduleGuardStore.GuardInput(
                req.name(),
                req.enabled() == null || req.enabled(),
                req.promql(),
                ScheduleGuardStore.Cmp.parse(req.cmp()),
                req.threshold() != null ? req.threshold() : 80.0,
                req.unit() != null ? req.unit() : "%",
                ScheduleGuardStore.RunWhen.parse(req.runWhen()),
                req.sshHost(),
                req.sshPort() != null ? req.sshPort() : 22,
                req.sshCredentialId(),
                Boolean.TRUE.equals(req.useSudo()),
                req.script(),
                req.timeoutSec() != null ? req.timeoutSec() : 60,
                req.cooldownSec() != null ? req.cooldownSec() : 60,
                req.blockOnFail() == null || req.blockOnFail()
        );
        if (req.id() != null) {
            guards.update(req.id(), in, by);
            return Map.of("ok", true, "id", req.id());
        }
        long id = guards.create(in, by);
        return Map.of("ok", true, "id", id);
    }

    @PostMapping("/guards/{id}/toggle")
    @PreAuthorize("hasRole('ADMIN')")
    public Map<String, Object> toggleGuard(@PathVariable long id, @RequestBody Map<String, Boolean> body, Principal user) {
        guards.setEnabled(id, Boolean.TRUE.equals(body.get("enabled")), user != null ? user.getName() : "admin");
        return Map.of("ok", true);
    }

    @PostMapping("/guards/{id}/delete")
    @PreAuthorize("hasRole('ADMIN')")
    public Map<String, Object> deleteGuard(@PathVariable long id, Principal user) {
        guards.delete(id, user != null ? user.getName() : "admin");
        return Map.of("ok", true);
    }

    @PostMapping("/guards/{id}/check")
    @PreAuthorize("hasRole('ADMIN')")
    public Map<String, Object> checkGuard(@PathVariable long id) {
        var out = guards.checkAndRun(id, true, false, true, "ручная проверка");
        return Map.of("ok", !out.failed(), "message", out.message() != null ? out.message() : "Проверка выполнена");
    }

    @PostMapping("/guards/{id}/run-ssh")
    @PreAuthorize("hasRole('ADMIN')")
    public Map<String, Object> runGuardSsh(@PathVariable long id) {
        var out = guards.checkAndRun(id, true, true, true, "ручной запуск SSH");
        return Map.of("ok", !out.failed(), "message", out.message() != null ? out.message() : "SSH-скрипт выполнен");
    }

    private static Map<String, Object> toGuardDto(ScheduleGuardStore.Guard g) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", g.id());
        m.put("name", g.name());
        m.put("enabled", g.enabled());
        m.put("promql", g.promql());
        m.put("cmp", g.cmp().name());
        m.put("threshold", g.threshold());
        m.put("thresholdText", g.formatThreshold());
        m.put("unit", g.unit());
        m.put("runWhen", g.runWhen().name());
        m.put("runWhenTitle", g.runWhen().title());
        m.put("sshHost", g.sshHost());
        m.put("sshPort", g.sshPort());
        m.put("hostSummary", g.hostSummary());
        m.put("sshCredentialId", g.sshCredentialId());
        m.put("useSudo", g.useSudo());
        m.put("script", g.script());
        m.put("timeoutSec", g.timeoutSec());
        m.put("cooldownSec", g.cooldownSec());
        m.put("blockOnFail", g.blockOnFail());
        m.put("lastCheckedAt", g.lastCheckedAt());
        m.put("lastValueText", g.formatValue());
        m.put("lastSeries", g.lastSeries());
        m.put("lastStatus", g.lastStatus() != null ? g.lastStatus().name() : null);
        m.put("lastStatusTitle", g.lastStatus() != null ? g.lastStatus().title() : null);
        m.put("lastOutput", g.lastOutput());
        return m;
    }
}
