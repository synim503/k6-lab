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

    public ScheduleController(ScheduledRunService service, ScheduleTimelineService timeline, ScheduleImportService importer,
                              ru.k6lab.server.load.ProfileImportService profileImport, ru.k6lab.server.run.RunService runs) {
        this.runs = runs;
        this.service = service;
        this.timeline = timeline;
        this.importer = importer;
        this.profileImport = profileImport;
    }

    /** Общий импорт профилей разных скриптов: у каждого профиля в JSON указан script. */
    @PostMapping("/schedule/import-profiles")
    public String importProfiles(@RequestParam(required = false) String json,
                                 @RequestParam(required = false) org.springframework.web.multipart.MultipartFile file,
                                 Principal user, RedirectAttributes ra) {
        try {
            String body = file != null && !file.isEmpty() ? new String(file.getBytes(), java.nio.charset.StandardCharsets.UTF_8) : json;
            var done = profileImport.importProfiles(null, body, user == null ? "system" : user.getName());
            ra.addFlashAttribute("info", "Импортировано профилей: " + done.size());
        } catch (IllegalArgumentException | IllegalStateException | java.io.IOException e) {
            ra.addFlashAttribute("error", "Импорт профилей не выполнен, ничего не сохранено. " + e.getMessage());
            ra.addFlashAttribute("importProfilesJson", json);
        }
        return "redirect:/schedule";
    }

    /** Импорт списка тестов (скрипт + профиль) из JSON: текстом или файлом; все тесты планируются сразу. */
    @PostMapping("/schedule/import")
    public String importTests(@RequestParam(required = false) String json,
                              @RequestParam(required = false) org.springframework.web.multipart.MultipartFile file,
                              Principal user, RedirectAttributes ra) {
        try {
            String body = file != null && !file.isEmpty() ? new String(file.getBytes(), java.nio.charset.StandardCharsets.UTF_8) : json;
            var created = importer.importTests(body, user == null ? "system" : user.getName());
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
        int moved = service.resume(user == null ? "system" : user.getName());
        ra.addFlashAttribute("info", "Пауза снята" + (moved > 0 ? ": записей, время которых прошло, перенесено на ближайшее свободное время: " + moved : ""));
        return "redirect:/schedule";
    }

    @GetMapping("/schedule")
    public String list(Model model) {
        model.addAttribute("paused", service.paused());
        model.addAttribute("activeRuns", runs.activeRuns());
        model.addAttribute("items", service.recent());
        model.addAttribute("fmt", new ReportFormat());
        return "schedule/index";
    }

    /**
     * Форма «Запланировать» на странице запуска скрипта. Время приходит в ISO (браузер переводит локальное время в UTC),
     * процент интенсивности - полями pct_ИМЯОПЕРАЦИИ, переменные - текстом KEY=VALUE, если выбран режим «свои».
     */
    @PostMapping("/scripts/{id}/schedule")
    public String create(@PathVariable Long id, @RequestParam(required = false) String runAt, @RequestParam(required = false) Integer count,
                         @RequestParam(name = "generatorId", required = false) List<Long> generatorIds,
                         @RequestParam(required = false) Long profile,
                         @RequestParam(defaultValue = "false") boolean shift,
                         @RequestParam(defaultValue = "script") String envMode, @RequestParam(required = false) String envText,
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
            return "redirect:/scripts/" + id + "/run#schedule";
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
