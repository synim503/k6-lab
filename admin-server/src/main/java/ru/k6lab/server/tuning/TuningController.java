package ru.k6lab.server.tuning;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import ru.k6lab.server.generator.Generator;
import ru.k6lab.server.generator.GeneratorRepository;

import java.security.Principal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/** Профилирование хостов-генераторов и тюнинг ОС: только для администраторов (меняет настройки ядра на хостах). */
@Controller
@RequestMapping("/tuning")
@PreAuthorize("hasRole('ADMIN')")
public class TuningController {

    /** Строка списка: генератор, его последний профиль и состояние применённого тюнинга. */
    public record Row(Generator generator, HostTuningStore.Profile profile, HostTuningStore.Tuning tuning,
                      int crit, int warn, int settings, boolean running) {
        public boolean tuned() {
            return tuning != null && "APPLIED".equals(tuning.status());
        }
    }

    private final GeneratorRepository generators;
    private final HostTuningService service;
    private final HostTuningStore store;
    private final TuningProfileStore profiles;

    public TuningController(GeneratorRepository generators, HostTuningService service, HostTuningStore store, TuningProfileStore profiles) {
        this.generators = generators;
        this.service = service;
        this.store = store;
        this.profiles = profiles;
    }

    @GetMapping
    public String index(@RequestParam(required = false) Long std, Model model) {
        List<Row> rows = rows();
        // эталон: профиль с наибольшим числом изменений (можно выбрать другой)
        List<Row> candidates = rows.stream().filter(r -> r.profile() != null && "DONE".equals(r.profile().status()) && r.settings() > 0)
                .sorted(Comparator.comparingInt(Row::settings).reversed().thenComparing(r -> r.generator().getName())).toList();
        Row standard = candidates.stream().filter(r -> std != null && r.generator().getId().equals(std)).findFirst()
                .orElse(candidates.isEmpty() ? null : candidates.get(0));
        model.addAttribute("candidates", candidates);
        model.addAttribute("standard", standard);
        model.addAttribute("standardSettings", standard == null ? List.of() : settingsOf(standard.profile()));
        model.addAttribute("tunedCount", rows.stream().filter(Row::tuned).count());
        model.addAttribute("rows", rows);
        model.addAttribute("anyRunning", rows.stream().anyMatch(Row::running));
        model.addAttribute("minSeconds", HostTuningService.MIN_SECONDS);
        model.addAttribute("maxSeconds", HostTuningService.MAX_SECONDS);
        model.addAttribute("history", store.history(15));
        model.addAttribute("optProfiles", profiles.list());
        model.addAttribute("conf", HostTuningService.CONF);
        return "tuning/index";
    }

    /** htmx-фрагмент: список обновляется, пока идёт профилирование. */
    @GetMapping("/table")
    public String table(Model model) {
        List<Row> rows = rows();
        model.addAttribute("rows", rows);
        model.addAttribute("anyRunning", rows.stream().anyMatch(Row::running));
        return "tuning/_table :: table";
    }

    @GetMapping("/profile/{id}")
    public String profile(@PathVariable long id, Model model, RedirectAttributes ra) {
        HostTuningStore.Profile p = store.profile(id).orElse(null);
        if (p == null) {
            ra.addFlashAttribute("error", "Профиль не найден");
            return "redirect:/tuning";
        }
        model.addAttribute("p", p);
        model.addAttribute("m", p.metrics());
        model.addAttribute("findings", findingsOf(p));
        model.addAttribute("settings", settingsOf(p));
        return "tuning/profile";
    }

    @PostMapping("/profile")
    public String start(@RequestParam(required = false) List<Long> generatorIds, @RequestParam(defaultValue = "30") int seconds,
                        Principal user, RedirectAttributes ra) {
        try {
            List<String> problems = service.startProfiling(generatorIds, seconds, user == null ? null : user.getName());
            ra.addFlashAttribute("info", "Профилирование запущено. Результаты появятся через " + seconds + " секунд и дольше.");
            if (!problems.isEmpty()) {
                ra.addFlashAttribute("error", String.join("; ", problems));
            }
        } catch (IllegalArgumentException e) {
            ra.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/tuning";
    }

    @PostMapping("/apply")
    public String apply(@RequestParam(required = false) List<Long> generatorIds, Principal user, RedirectAttributes ra) {
        return run(generatorIds, ra, ids -> service.apply(ids, user == null ? null : user.getName()), "Применение настроек");
    }

    /** Раскатывает набор настроек эталонного хоста на выбранные (или на все) хосты. */
    @PostMapping("/rollout")
    public String rollout(@RequestParam long sourceId, @RequestParam(required = false) List<Long> generatorIds, Principal user, RedirectAttributes ra) {
        return run(generatorIds, ra, ids -> service.rollout(sourceId, ids, user == null ? null : user.getName()), "Раскатка эталона");
    }

    // ------------------------------------------------------------------ профили оптимизации (сохранённые наборы настроек)

    /** Сохраняет рекомендации профиля хоста как именованный профиль оптимизации, который потом можно править и раскатывать. */
    @PostMapping("/profiles")
    public String createOpt(@RequestParam String name, @RequestParam(required = false) String description, @RequestParam long fromHost,
                            Principal user, RedirectAttributes ra) {
        String n = name == null ? "" : name.strip();
        if (n.isEmpty() || n.length() > 100) {
            ra.addFlashAttribute("error", "Название профиля оптимизации: от 1 до 100 символов");
            return "redirect:/tuning";
        }
        if (profiles.nameTaken(n, null)) {
            ra.addFlashAttribute("error", "Профиль оптимизации «" + n + "» уже есть: выберите другое название");
            return "redirect:/tuning";
        }
        HostTuningStore.Profile src = store.latestDone(fromHost).orElse(null);
        java.util.Map<String, String> settings = src == null ? java.util.Map.of() : HostTuningService.recommended(src);
        if (settings.isEmpty()) {
            ra.addFlashAttribute("error", "В профиле хоста нет настроек для сохранения");
            return "redirect:/tuning";
        }
        long id = profiles.create(n, description == null || description.isBlank() ? null : description.strip(), settings, user == null ? null : user.getName());
        ra.addFlashAttribute("info", "Профиль оптимизации «" + n + "» сохранён: " + settings.size() + " настроек. Здесь его можно отредактировать.");
        return "redirect:/tuning/profiles/" + id;
    }

    @GetMapping("/profiles/{id}")
    public String editOpt(@PathVariable long id, Model model, RedirectAttributes ra) {
        TuningProfileStore.OptProfile p = profiles.find(id).orElse(null);
        if (p == null) {
            ra.addFlashAttribute("error", "Профиль оптимизации не найден");
            return "redirect:/tuning";
        }
        model.addAttribute("p", p);
        List<String> free = new ArrayList<>(TuningAnalyzer.allKeys());
        free.removeAll(p.settings().keySet());
        model.addAttribute("freeKeys", free);
        model.addAttribute("describe", (java.util.function.Function<String, String>) TuningAnalyzer::describe);
        return "tuning/opt";
    }

    @PostMapping("/profiles/{id}")
    public String saveOpt(@PathVariable long id, @RequestParam String name, @RequestParam(required = false) String description,
                          @RequestParam(required = false) List<String> key, @RequestParam(required = false) List<String> value,
                          @RequestParam(required = false) List<String> remove, @RequestParam(required = false) String newKey,
                          @RequestParam(required = false) String newValue, RedirectAttributes ra) {
        if (profiles.find(id).isEmpty()) {
            ra.addFlashAttribute("error", "Профиль оптимизации не найден");
            return "redirect:/tuning";
        }
        String n = name == null ? "" : name.strip();
        if (n.isEmpty() || n.length() > 100) {
            ra.addFlashAttribute("error", "Название профиля оптимизации: от 1 до 100 символов");
            return "redirect:/tuning/profiles/" + id;
        }
        if (profiles.nameTaken(n, id)) {
            ra.addFlashAttribute("error", "Профиль оптимизации «" + n + "» уже есть: выберите другое название");
            return "redirect:/tuning/profiles/" + id;
        }
        java.util.Map<String, String> settings = new java.util.LinkedHashMap<>();
        for (int i = 0; key != null && i < key.size(); i++) {
            String k = key.get(i), v = value != null && i < value.size() ? value.get(i) : "";
            if (remove != null && remove.contains(k)) {
                continue;
            }
            if (!TuningAnalyzer.valid(k, v)) {
                ra.addFlashAttribute("error", "Недопустимое значение для " + k + ": «" + v + "» (" + TuningAnalyzer.describe(k) + ")");
                return "redirect:/tuning/profiles/" + id;
            }
            settings.put(k, v.strip().replaceAll("\\s+", " "));
        }
        if (newKey != null && !newKey.isBlank()) {
            if (!TuningAnalyzer.valid(newKey, newValue)) {
                ra.addFlashAttribute("error", "Недопустимое значение для " + newKey + ": «" + newValue + "» (" + TuningAnalyzer.describe(newKey) + ")");
                return "redirect:/tuning/profiles/" + id;
            }
            settings.put(newKey, newValue.strip().replaceAll("\\s+", " "));
        }
        if (settings.isEmpty()) {
            ra.addFlashAttribute("error", "В профиле должна остаться хотя бы одна настройка (чтобы убрать всё, удалите профиль)");
            return "redirect:/tuning/profiles/" + id;
        }
        profiles.update(id, n, description == null || description.isBlank() ? null : description.strip(), settings);
        ra.addFlashAttribute("info", "Профиль оптимизации сохранён");
        return "redirect:/tuning/profiles/" + id;
    }

    @PostMapping("/profiles/{id}/delete")
    public String deleteOpt(@PathVariable long id, RedirectAttributes ra) {
        profiles.delete(id);
        ra.addFlashAttribute("info", "Профиль оптимизации удалён (применённые на хостах настройки он не меняет)");
        return "redirect:/tuning";
    }

    /** Раскатывает профиль оптимизации на отмеченные хосты, а если ничего не отмечено - на все. */
    @PostMapping("/profiles/{id}/apply")
    public String applyOpt(@PathVariable long id, @RequestParam(required = false) List<Long> generatorIds, Principal user, RedirectAttributes ra) {
        return run(generatorIds, ra, ids -> service.applyProfile(id, ids, user == null ? null : user.getName()), "Раскатка профиля оптимизации");
    }

    /** Возвращает в исходное состояние все хосты с применённым тюнингом. */
    @PostMapping("/rollback-all")
    public String rollbackAll(Principal user, RedirectAttributes ra) {
        return run(null, ra, ids -> service.rollbackAll(user == null ? null : user.getName()), "Возврат в исходное");
    }

    @PostMapping("/rollback")
    public String rollback(@RequestParam(required = false) List<Long> generatorIds, Principal user, RedirectAttributes ra) {
        return run(generatorIds, ra, ids -> service.rollback(ids, user == null ? null : user.getName()), "Откат настроек");
    }

    private String run(List<Long> ids, RedirectAttributes ra, java.util.function.Function<List<Long>, List<String>> action, String title) {
        try {
            List<String> results = action.apply(ids);
            ra.addFlashAttribute("info", title + ": " + String.join("; ", results));
        } catch (IllegalArgumentException e) {
            ra.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/tuning";
    }

    // ------------------------------------------------------------------ данные страницы

    private List<Row> rows() {
        Map<Long, HostTuningStore.Profile> profiles = new HashMap<>();
        store.latestProfiles().forEach(p -> profiles.put(p.generatorId(), p));
        Map<Long, HostTuningStore.Tuning> tunings = new HashMap<>();
        store.latestTunings().forEach(t -> tunings.put(t.generatorId(), t));
        List<Row> out = new ArrayList<>();
        for (Generator g : generators.findAll()) {
            HostTuningStore.Profile p = profiles.get(g.getId());
            int crit = 0, warn = 0;
            Set<String> keys = new TreeSet<>();
            if (p != null && p.findings() != null) {
                for (JsonNode f : p.findings()) {
                    String sev = f.path("severity").asText();
                    crit += "CRIT".equals(sev) ? 1 : 0;
                    warn += "WARN".equals(sev) ? 1 : 0;
                    if (!f.path("recommended").isMissingNode() && !f.path("recommended").isNull()) {
                        keys.add(f.path("key").asText());
                    }
                }
            }
            out.add(new Row(g, p, tunings.get(g.getId()), crit, warn, keys.size(), p != null && "RUNNING".equals(p.status())));
        }
        out.sort(Comparator.comparing(r -> r.generator().getName()));
        return out;
    }

    private static List<JsonNode> findingsOf(HostTuningStore.Profile p) {
        List<JsonNode> out = new ArrayList<>();
        if (p.findings() != null) {
            p.findings().forEach(out::add);
        }
        // сначала критичные
        out.sort(Comparator.comparingInt(f -> switch (f.path("severity").asText()) {
            case "CRIT" -> 0;
            case "WARN" -> 1;
            default -> 2;
        }));
        return out;
    }

    private static List<JsonNode> settingsOf(HostTuningStore.Profile p) {
        List<JsonNode> out = new ArrayList<>();
        for (JsonNode f : findingsOf(p)) {
            if (!f.path("recommended").isMissingNode() && !f.path("recommended").isNull()) {
                out.add(f);
            }
        }
        return out;
    }
}
