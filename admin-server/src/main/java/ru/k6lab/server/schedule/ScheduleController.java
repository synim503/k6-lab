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

    public ScheduleController(ScheduledRunService service) {
        this.service = service;
    }

    @GetMapping("/schedule")
    public String list(Model model) {
        model.addAttribute("items", service.recent());
        model.addAttribute("fmt", new ReportFormat());
        return "schedule/index";
    }

    /**
     * Форма «Запланировать» на странице запуска скрипта. Время приходит в ISO (браузер переводит локальное время в UTC),
     * процент интенсивности - полями pct_ИМЯОПЕРАЦИИ, переменные - текстом KEY=VALUE, если выбран режим «свои».
     */
    @PostMapping("/scripts/{id}/schedule")
    public String create(@PathVariable Long id, @RequestParam String runAt, @RequestParam(required = false) Integer count,
                         @RequestParam(name = "generatorId", required = false) List<Long> generatorIds,
                         @RequestParam(defaultValue = "script") String envMode, @RequestParam(required = false) String envText,
                         @RequestParam Map<String, String> all, Principal user, RedirectAttributes ra) {
        try {
            Instant at;
            try {
                at = Instant.parse(runAt.trim());
            } catch (DateTimeParseException e) {
                throw new IllegalArgumentException("Укажите дату и время запуска");
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
            long sid = service.schedule(id, at, generatorIds, count, env, pct, user == null ? "system" : user.getName());
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
