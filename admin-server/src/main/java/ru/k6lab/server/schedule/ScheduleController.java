package ru.k6lab.server.schedule;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import ru.k6lab.server.load.LoadForm;
import ru.k6lab.server.result.ReportFormat;

import java.security.Principal;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Запланированные запуски: список, создание из формы запуска скрипта и отмена. */
@Controller
public class ScheduleController {

    private final ScheduledRunService service;
    private final ScheduleTimelineService timeline;
    private final ScheduleImportService importer;
    private final ru.k6lab.server.load.ProfileImportService profileImport;
    private final ru.k6lab.server.run.RunService runs;
    private final ru.k6lab.server.script.ScriptService scripts;
    private final ru.k6lab.server.load.LoadProfileStore profileStore;
    private final ru.k6lab.server.load.LoadService load;
    private final ru.k6lab.server.load.RunPlanner planner;
    private final ru.k6lab.server.generator.GeneratorRepository generators;

    public ScheduleController(ScheduledRunService service, ScheduleTimelineService timeline, ScheduleImportService importer,
                              ru.k6lab.server.load.ProfileImportService profileImport, ru.k6lab.server.run.RunService runs,
                              ru.k6lab.server.script.ScriptService scripts, ru.k6lab.server.load.LoadProfileStore profileStore,
                              ru.k6lab.server.load.LoadService load, ru.k6lab.server.load.RunPlanner planner,
                              ru.k6lab.server.generator.GeneratorRepository generators) {
        this.runs = runs;
        this.service = service;
        this.timeline = timeline;
        this.importer = importer;
        this.profileImport = profileImport;
        this.scripts = scripts;
        this.profileStore = profileStore;
        this.load = load;
        this.planner = planner;
        this.generators = generators;
    }

    /** Общий импорт профилей разных скриптов: у каждого профиля в JSON указан script. Опционально сразу планирует последовательный запуск. */
    @PostMapping("/schedule/import-profiles")
    public String importProfiles(@RequestParam(required = false) String json,
                                 @RequestParam(required = false) org.springframework.web.multipart.MultipartFile file,
                                 @RequestParam(defaultValue = "false") boolean scheduleSequential,
                                 @RequestParam(required = false) String runAt,
                                 @RequestParam(required = false) Integer count,
                                 @RequestParam(defaultValue = "5") int gapMinutes,
                                 @RequestParam(defaultValue = "false") boolean shift,
                                 Principal user, RedirectAttributes ra) {
        String by = user == null ? "system" : user.getName();
        List<ru.k6lab.server.load.ProfileImportService.Imported> done;
        try {
            String body = file != null && !file.isEmpty() ? new String(file.getBytes(), java.nio.charset.StandardCharsets.UTF_8) : json;
            done = profileImport.importProfiles(null, body, by);
        } catch (IllegalArgumentException | IllegalStateException | java.io.IOException e) {
            ra.addFlashAttribute("error", "Импорт профилей не выполнен, ничего не сохранено. " + e.getMessage());
            ra.addFlashAttribute("importProfilesJson", json);
            return "redirect:/schedule";
        }
        String baseMsg = "Импортировано профилей: " + done.size();
        if (!scheduleSequential) {
            ra.addFlashAttribute("info", baseMsg);
            return "redirect:/schedule";
        }
        try {
            Instant firstAt = null;
            if (runAt != null && !runAt.isBlank()) {
                try {
                    firstAt = Instant.parse(runAt.trim());
                } catch (DateTimeParseException e) {
                    throw new IllegalArgumentException("Некорректная дата и время старта");
                }
            }
            var specs = done.stream()
                    .map(x -> new ScheduledRunService.SequentialSpec(x.scriptId(), x.profileId()))
                    .toList();
            var ids = service.scheduleSequential(specs, firstAt, count, Math.max(5, gapMinutes) * 60, shift, by);
            ra.addFlashAttribute("info", baseMsg + ". Запланировано последовательно: " + ids.size() + " (пауза " + Math.max(5, gapMinutes) + " мин).");
        } catch (IllegalArgumentException | IllegalStateException e) {
            ra.addFlashAttribute("error", baseMsg + ", но последовательный запуск не запланирован: " + e.getMessage());
        }
        return "redirect:/schedule";
    }

    /** Импорт списка тестов (скрипт + профиль) из JSON: текстом или файлом; все тесты планируются сразу. */
    @PostMapping("/schedule/import")
    public String importTests(@RequestParam(required = false) String json,
                              @RequestParam(required = false) org.springframework.web.multipart.MultipartFile file,
                              @RequestParam(required = false) Integer gapMinutes,
                              Principal user, RedirectAttributes ra) {
        try {
            String body = file != null && !file.isEmpty() ? new String(file.getBytes(), java.nio.charset.StandardCharsets.UTF_8) : json;
            var created = importer.importTests(body, gapMinutes, user == null ? "system" : user.getName());
            ra.addFlashAttribute("info", "Запланировано тестов: " + created.size());
        } catch (IllegalArgumentException | IllegalStateException | java.io.IOException e) {
            ra.addFlashAttribute("error", "Импорт не выполнен, ничего не запланировано. " + e.getMessage());
            ra.addFlashAttribute("importJson", json);
        }
        return "redirect:/schedule";
    }

    /** Занятость генераторов для таймлайна выбора времени: текущие и уже запланированные запуски. */
    @GetMapping("/scripts/{id}/schedule/timeline")
    @org.springframework.web.bind.annotation.ResponseBody
    public Map<String, Object> timeline(@PathVariable Long id, @RequestParam(required = false) Long profile,
                                        @RequestParam(required = false) Integer count) {
        return timeline.build(id, profile, count);
    }

    /** Занятость генераторов для таймлайна страницы расписания. */
    @GetMapping("/schedule/timeline")
    @org.springframework.web.bind.annotation.ResponseBody
    public Map<String, Object> overview() {
        return timeline.overview();
    }

    /** Пауза планировщика: идущий тест доходит до конца, новые запуски по расписанию не стартуют до снятия паузы. */
    @PostMapping("/schedule/pause")
    public String pause(Principal user, RedirectAttributes ra) {
        service.pause(user == null ? "system" : user.getName());
        ra.addFlashAttribute("info", "Планировщик на паузе: идущий тест закончится, новые запуски по расписанию не стартуют");
        return "redirect:/schedule";
    }

    @PostMapping("/schedule/resume")
    public String resume(Principal user, RedirectAttributes ra) {
        var res = service.resume(user == null ? "system" : user.getName());
        String msg;
        if (res.startedRunId() != null) {
            msg = "Пауза снята: сразу запущен тест «" + res.startedName() + "» (запуск #" + res.startedRunId() + ")"
                    + (res.moved() > 0 ? ", сдвинуто в очереди: " + res.moved() : "");
        } else if (res.moved() > 0) {
            msg = "Пауза снята: генераторы заняты, записей в очереди перенесено на ближайшее свободное время: " + res.moved();
        } else {
            msg = "Пауза снята";
        }
        ra.addFlashAttribute("info", msg);
        return "redirect:/schedule";
    }

    @GetMapping("/schedule")
    public String list(Model model) {
        var items = service.recent();
        var active = runs.activeRuns();
        var gens = generators.findAll().stream()
                .sorted(java.util.Comparator.comparing(ru.k6lab.server.generator.Generator::getName)).toList();
        var pending = items.stream().filter(ScheduledRunStore.Item::pending).toList();
        var nextPending = pending.stream()
                .min(java.util.Comparator.comparing(ScheduledRunStore.Item::runAt))
                .map(ScheduledRunStore.Item::runAt).orElse(null);
        model.addAttribute("paused", service.paused());
        model.addAttribute("activeRuns", active);
        model.addAttribute("items", items);
        model.addAttribute("scripts", scripts.list());
        model.addAttribute("generators", gens);
        model.addAttribute("freeGenCount", gens.stream().filter(ru.k6lab.server.generator.Generator::isAvailableForRuns).count());
        model.addAttribute("onlineGenCount", gens.stream().filter(g -> g.getStatus() == ru.k6lab.server.generator.Generator.Status.ONLINE).count());
        model.addAttribute("pendingCount", pending.size());
        model.addAttribute("startedCount", items.stream().filter(i -> i.status() == ScheduledRunStore.Status.STARTED || i.status() == ScheduledRunStore.Status.STARTING).count());
        model.addAttribute("failedCount", items.stream().filter(i -> i.status() == ScheduledRunStore.Status.FAILED).count());
        model.addAttribute("nextRunAt", nextPending);
        model.addAttribute("fmt", new ReportFormat());
        return "schedule/index";
    }

    /** Метаданные скрипта для быстрого планирования прямо со страницы /schedule: профили, операции, переменные, длительность. */
    @GetMapping("/schedule/script-meta/{scriptId}")
    @org.springframework.web.bind.annotation.ResponseBody
    public Map<String, Object> scriptMeta(@PathVariable Long scriptId) {
        var s = scripts.get(scriptId);
        var plan = planner.plan(scriptId, s.getGeneratorCount());
        int baseDur = plan.config() != null ? (int) ru.k6lab.core.k6config.ConfigDuration.of(plan.config()).totalSec()
                : (plan.steps() == null ? 0 : plan.steps().stream().mapToInt(ru.k6lab.core.profile.StepPlan::totalSec).sum());
        StringBuilder env = new StringBuilder();
        s.getEnv().forEach((k, v) -> env.append(k).append('=').append(v).append('\n'));
        List<Map<String, Object>> profs = new ArrayList<>();
        for (var p : profileStore.list(scriptId)) {
            Map<String, Object> pm = new LinkedHashMap<>();
            pm.put("id", p.id());
            pm.put("name", p.name());
            try {
                var built = load.fromProfile(scriptId, p.id());
                var pp = planner.plan(scriptId, s.getGeneratorCount(), built);
                int pdur = pp.config() != null ? (int) ru.k6lab.core.k6config.ConfigDuration.of(pp.config()).totalSec()
                        : (pp.steps() == null ? 0 : pp.steps().stream().mapToInt(ru.k6lab.core.profile.StepPlan::totalSec).sum());
                pm.put("ops", pp.operations().stream().map(ru.k6lab.server.load.LoadOperation::getName).toList());
                pm.put("durationSec", pdur);
                pm.put("errors", pp.errors());
                StringBuilder penv = new StringBuilder();
                built.env().forEach((k, v) -> penv.append(k).append('=').append(v).append('\n'));
                pm.put("envText", penv.toString());
            } catch (IllegalArgumentException | IllegalStateException e) {
                pm.put("ops", List.of());
                pm.put("durationSec", 0);
                pm.put("errors", List.of(e.getMessage() == null ? "Профиль повреждён" : e.getMessage()));
                pm.put("envText", "");
            }
            profs.add(pm);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("scriptId", s.getId());
        out.put("scriptName", s.getName());
        out.put("defaultCount", s.getGeneratorCount());
        out.put("durationSec", baseDur);
        out.put("errors", plan.errors());
        out.put("ops", plan.operations().stream().map(ru.k6lab.server.load.LoadOperation::getName).toList());
        out.put("envText", env.toString());
        out.put("profiles", profs);
        return out;
    }

    /** Отменяет все ожидающие записи расписания. */
    @PostMapping("/schedule/cancel-all-pending")
    public String cancelAllPending(Principal user, RedirectAttributes ra) {
        String by = user == null ? "system" : user.getName();
        int n = 0;
        for (var item : service.recent()) {
            if (item.pending() && service.cancel(item.id(), by)) {
                n++;
            }
        }
        ra.addFlashAttribute(n > 0 ? "info" : "error", n > 0 ? "Отменено ожидающих запусков: " + n : "Нет ожидающих запусков для отмены");
        return "redirect:/schedule";
    }

    /**
     * Форма «Запланировать» на странице запуска скрипта (и из быстрого диалога на /schedule). Время приходит в ISO (браузер переводит локальное время в UTC),
     * процент интенсивности - полями pct_ИМЯОПЕРАЦИИ, переменные - текстом KEY=VALUE, если выбран режим «свои».
     */
    @PostMapping("/scripts/{id}/schedule")
    public String create(@PathVariable Long id, @RequestParam(required = false) String runAt, @RequestParam(required = false) Integer count,
                         @RequestParam(name = "generatorId", required = false) List<Long> generatorIds,
                         @RequestParam(required = false) Long profile,
                         @RequestParam(defaultValue = "false") boolean shift,
                         @RequestParam(defaultValue = "script") String envMode, @RequestParam(required = false) String envText,
                         @RequestParam(required = false) String returnTo,
                         @RequestParam Map<String, String> all, Principal user, RedirectAttributes ra) {
        try {
            Instant at = null; // время не выбрано - ближайшее доступное
            if (runAt != null && !runAt.isBlank()) {
                try {
                    at = Instant.parse(runAt.trim());
                } catch (DateTimeParseException e) {
                    throw new IllegalArgumentException("Укажите дату и время запуска");
                }
            }
            Map<String, String> env = "custom".equals(envMode) ? LoadForm.parseKv(envText) : null;
            Map<String, Double> pct = new LinkedHashMap<>();
            List<String> bad = new ArrayList<>();
            all.forEach((k, v) -> {
                if (k.startsWith("pct_") && v != null && !v.isBlank()) {
                    try {
                        pct.put(k.substring(4), Double.parseDouble(v.trim().replace(',', '.')));
                    } catch (NumberFormatException e) {
                        bad.add(k.substring(4));
                    }
                }
            });
            if (!bad.isEmpty()) {
                throw new IllegalArgumentException("Интенсивность должна быть числом (операции: " + String.join(", ", bad) + ")");
            }
            long sid = service.schedule(id, at, generatorIds, count, env, pct, profile, user == null ? "system" : user.getName(), shift);
            ra.addFlashAttribute("info", "Запуск запланирован (#" + sid + ")");
            return "redirect:/schedule";
        } catch (IllegalArgumentException | IllegalStateException e) {
            ra.addFlashAttribute("error", e.getMessage());
            return "schedule".equals(returnTo) ? "redirect:/schedule" : "redirect:/scripts/" + id + "/run#schedule";
        }
    }

    @PostMapping("/schedule/{id}/cancel")
    public String cancel(@PathVariable Long id, Principal user, RedirectAttributes ra) {
        if (service.cancel(id, user == null ? "system" : user.getName())) {
            ra.addFlashAttribute("info", "Запуск отменён");
        } else {
            ra.addFlashAttribute("error", "Запуск уже начат или завершён: отменить нельзя");
        }
        return "redirect:/schedule";
    }
}
