package ru.k6lab.server.load;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import ru.k6lab.core.k6config.GeneratedConfig;
import ru.k6lab.core.util.ZipUtil;
import ru.k6lab.server.script.AdminHelper;
import ru.k6lab.server.script.K6Tool;
import ru.k6lab.server.script.ScriptService;
import ru.k6lab.server.secret.Secret;
import ru.k6lab.server.secret.SecretService;
import ru.k6lab.server.testdata.ScriptDataFile;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;

/** Страница «Нагрузка» скрипта: описание операций с интенсивностью и SLA, расчёт итогового конфига k6. */
@Controller
@RequestMapping("/scripts/{id}")
public class LoadController {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final ScriptService scripts;
    private final LoadService load;
    private final RunPlanner planner;
    private final SecretService secrets;
    private final K6Tool k6;
    private final AdminHelper helper;
    private final LoadProfileStore profiles;
    private final ProfileImportService profileImport;
    private final ru.k6lab.server.schedule.ScheduledRunService scheduledRuns;
    private final ru.k6lab.server.result.ChartTemplateStore chartTemplates;

    public LoadController(ScriptService scripts, LoadService load, RunPlanner planner, SecretService secrets, K6Tool k6,
                          AdminHelper helper, LoadProfileStore profiles, ProfileImportService profileImport,
                          ru.k6lab.server.schedule.ScheduledRunService scheduledRuns,
                          ru.k6lab.server.result.ChartTemplateStore chartTemplates) {
        this.profiles = profiles;
        this.profileImport = profileImport;
        this.scheduledRuns = scheduledRuns;
        this.chartTemplates = chartTemplates;
        this.scripts = scripts;
        this.load = load;
        this.planner = planner;
        this.secrets = secrets;
        this.k6 = k6;
        this.helper = helper;
    }

    @GetMapping("/load")
    public String form(@PathVariable Long id, @RequestParam(required = false) Integer generators,
                       @RequestParam(required = false) Long profile, Model model) {
        // без параметра - число генераторов, заданное в скрипте
        LoadForm f = load.toForm(id);
        Long loadedProfileId = null;
        if (profile != null) {
            // профиль открывается в форме независимо от базовой нагрузки скрипта
            var p = profiles.find(id, profile);
            if (p.isPresent()) {
                try {
                    f = LoadJson.fromJson(p.get().data());
                    load.completeWithScriptFunctions(id, f);
                    loadedProfileId = p.get().id();
                    model.addAttribute("loadedProfile", p.get().name());
                    model.addAttribute("loadedProfileId", loadedProfileId);
                    model.addAttribute("formDirty", true);
                    model.addAttribute("info", "Открыт профиль «" + p.get().name() + "». Изменения сохраняются в этот профиль кнопкой «Сохранить профиль».");
                } catch (IllegalArgumentException e) {
                    model.addAttribute("error", "Профиль «" + p.get().name() + "» не открывается: " + e.getMessage());
                }
            } else {
                model.addAttribute("error", "Профиль не найден");
            }
        }
        fill(model, id, f, generators != null ? generators : scripts.get(id).getGeneratorCount(), loadedProfileId);
        return "scripts/load";
    }

    /** Страница после POST сохранения профиля живёт по этому адресу: обновление или закладка по GET ведут обратно на форму нагрузки. */
    @GetMapping("/load/profile")
    public String profileBack(@PathVariable Long id, @RequestParam(required = false) Long profile) {
        return "redirect:/scripts/" + id + "/load" + (profile == null ? "" : "?profile=" + profile);
    }

    /**
     * Сохраняет текущее состояние формы (в том числе ещё не сохранённое в нагрузку) как профиль; профиль с таким именем обновляется.
     * Форма остаётся открытой со всеми правками. Полная проверка нагрузки выполняется, когда профиль сохраняют в нагрузку скрипта.
     */
    @PostMapping("/load/profile")
    public String saveProfile(@PathVariable Long id, @ModelAttribute("form") LoadForm f, @RequestParam String profileName,
                              java.security.Principal user, Model model) {
        Long savedProfileId = null;
        try {
            String name = profileName == null ? "" : profileName.strip();
            if (name.isEmpty() || name.length() > 120) {
                throw new IllegalArgumentException("Название профиля: от 1 до 120 символов");
            }
            java.util.Map<String, String> parsedEnv = LoadForm.parseKv(f.getEnvText());
            f.getOps().removeIf(LoadForm.OpForm::blank);
            f.getOps().forEach(LoadForm.OpForm::parseScenario); // ошибка в JSON параметров сценария - сразу понятным сообщением
            if (f.getOps().stream().noneMatch(LoadForm.OpForm::isEnabled)) {
                throw new IllegalArgumentException("В профиле нет включённых операций");
            }
            LoadForm toStore = LoadJson.fromJson(LoadJson.toJson(f));
            var saved = profiles.save(id, name, LoadJson.toJson(toStore), user == null ? "system" : user.getName());
            savedProfileId = saved.id();
            String msg = saved.updated() ? "Профиль «" + name + "» обновлён" : "Профиль «" + name + "» сохранён";
            model.addAttribute("info", msg);
            model.addAttribute("loadedProfile", name);
            model.addAttribute("loadedProfileId", savedProfileId);
        } catch (IllegalArgumentException | IllegalStateException e) {
            model.addAttribute("error", e.getMessage());
        }
        fill(model, id, f, Math.max(1, f.getGeneratorCount() == null ? scripts.get(id).getGeneratorCount() : f.getGeneratorCount()), savedProfileId);
        return "scripts/load";
    }

    /** Импорт профилей скрипта из JSON (текстом или файлом); всё или ничего. Опционально сразу планирует их последовательный запуск. */
    @PostMapping("/load/profiles/import")
    public String importProfiles(@PathVariable Long id, @RequestParam(required = false) String json,
                                 @RequestParam(required = false) org.springframework.web.multipart.MultipartFile file,
                                 @RequestParam(defaultValue = "false") boolean scheduleSequential,
                                 @RequestParam(required = false) String runAt,
                                 @RequestParam(required = false) Integer count,
                                 @RequestParam(defaultValue = "5") int gapMinutes,
                                 @RequestParam(defaultValue = "false") boolean shift,
                                 java.security.Principal user, RedirectAttributes ra) {
        String by = user == null ? "system" : user.getName();
        List<ProfileImportService.Imported> done;
        try {
            String body = file != null && !file.isEmpty() ? new String(file.getBytes(), java.nio.charset.StandardCharsets.UTF_8) : json;
            done = profileImport.importProfiles(id, body, by);
        } catch (IllegalArgumentException | IllegalStateException | java.io.IOException e) {
            ra.addFlashAttribute("error", "Импорт профилей не выполнен, ничего не сохранено. " + e.getMessage());
            ra.addFlashAttribute("importJson", json);
            return "redirect:/scripts/" + id + "/load";
        }
        long upd = done.stream().filter(ProfileImportService.Imported::updated).count();
        String baseMsg = "Импортировано профилей: " + done.size() + (upd > 0 ? " (обновлено существующих: " + upd + ")" : "");
        if (!scheduleSequential) {
            ra.addFlashAttribute("info", baseMsg);
            return "redirect:/scripts/" + id + "/load";
        }
        try {
            java.time.Instant firstAt = null;
            if (runAt != null && !runAt.isBlank()) {
                try {
                    firstAt = java.time.Instant.parse(runAt.trim());
                } catch (java.time.format.DateTimeParseException e) {
                    throw new IllegalArgumentException("Некорректная дата и время старта");
                }
            }
            var specs = done.stream()
                    .map(x -> new ru.k6lab.server.schedule.ScheduledRunService.SequentialSpec(x.scriptId(), x.profileId()))
                    .toList();
            var ids = scheduledRuns.scheduleSequential(specs, firstAt, count, Math.max(5, gapMinutes) * 60, shift, by);
            ra.addFlashAttribute("info", baseMsg + ". Запланировано последовательно: " + ids.size() + " (пауза " + Math.max(5, gapMinutes) + " мин).");
            return "redirect:/schedule";
        } catch (IllegalArgumentException | IllegalStateException e) {
            ra.addFlashAttribute("error", baseMsg + ", но последовательный запуск не запланирован: " + e.getMessage());
            return "redirect:/scripts/" + id + "/load";
        }
    }

    /** Профили скрипта в формате импорта. */
    @GetMapping(value = "/load/profiles.json", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> exportProfiles(@PathVariable Long id) {
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename("profiles-" + id + ".json").build().toString())
                .body(profileImport.export(id));
    }

    @PostMapping("/load/profile/delete")
    public String deleteProfile(@PathVariable Long id, @RequestParam Long profile, RedirectAttributes ra) {
        if (profiles.delete(id, profile)) {
            ra.addFlashAttribute("info", "Профиль удалён");
        } else {
            ra.addFlashAttribute("error", "Профиль не найден");
        }
        return "redirect:/scripts/" + id + "/load";
    }

    /** Массовое удаление выбранных профилей скрипта. */
    @PostMapping("/load/profiles/bulk-delete")
    public String bulkDeleteProfiles(@PathVariable Long id,
                                     @RequestParam(name = "ids", required = false) List<Long> ids,
                                     @RequestParam(required = false) Long currentProfile,
                                     RedirectAttributes ra) {
        int deleted = profiles.deleteMany(id, ids);
        if (deleted > 0) {
            ra.addFlashAttribute("info", "Удалено профилей: " + deleted);
        } else {
            ra.addFlashAttribute("error", "Не выбрано ни одного профиля для удаления");
        }
        if (currentProfile != null && (ids == null || !ids.contains(currentProfile))) {
            return "redirect:/scripts/" + id + "/load?profile=" + currentProfile;
        }
        return "redirect:/scripts/" + id + "/load";
    }

    @PostMapping("/load/profile/{pid}/duplicate")
    public String duplicateProfile(@PathVariable Long id, @PathVariable Long pid, java.security.Principal user, RedirectAttributes ra) {
        try {
            var copy = profiles.duplicate(id, pid, user == null ? "system" : user.getName());
            ra.addFlashAttribute("info", "Создана копия профиля «" + copy.name() + "»");
            return "redirect:/scripts/" + id + "/load?profile=" + copy.id();
        } catch (IllegalArgumentException | org.springframework.dao.InvalidDataAccessApiUsageException e) {
            ra.addFlashAttribute("error", rootMessage(e));
            return "redirect:/scripts/" + id + "/load";
        }
    }

    @PostMapping("/load/profile/{pid}/rename")
    public String renameProfile(@PathVariable Long id, @PathVariable Long pid, @RequestParam String name,
                                java.security.Principal user, RedirectAttributes ra) {
        try {
            var upd = profiles.rename(id, pid, name, user == null ? "system" : user.getName());
            ra.addFlashAttribute("info", "Профиль переименован в «" + upd.name() + "»");
            return "redirect:/scripts/" + id + "/load?profile=" + upd.id();
        } catch (IllegalArgumentException | org.springframework.dao.InvalidDataAccessApiUsageException e) {
            ra.addFlashAttribute("error", rootMessage(e));
            return "redirect:/scripts/" + id + "/load?profile=" + pid;
        }
    }

    private static String rootMessage(RuntimeException e) {
        Throwable cur = e;
        while (cur.getCause() != null && cur.getCause() != cur) {
            cur = cur.getCause();
        }
        return cur.getMessage() != null ? cur.getMessage() : e.getMessage();
    }

    @PostMapping("/load")
    public String save(@PathVariable Long id, @ModelAttribute("form") LoadForm f, Model model, RedirectAttributes ra) {
        try {
            f.setBaseProfileActive(false);
            load.save(id, f);
            ra.addFlashAttribute("info", "Нагрузка сохранена");
            return "redirect:/scripts/" + id;
        } catch (IllegalArgumentException | IllegalStateException e) {
            model.addAttribute("error", e.getMessage());
            fill(model, id, f, Math.max(1, f.getGeneratorCount() == null ? scripts.get(id).getGeneratorCount() : f.getGeneratorCount()));
            return "scripts/load";
        }
    }

    /**
     * Ручная разовая отправка выбранных разделов базового профиля в остальные профили скрипта.
     * Постоянная привязка не создаётся — после отправки каждый профиль остаётся независимым.
     */
    @PostMapping("/load/apply-base")
    public String saveAndApplyBase(@PathVariable Long id, @ModelAttribute("form") LoadForm f,
                                   java.security.Principal user, Model model, RedirectAttributes ra) {
        try {
            boolean anySection = f.isBaseOverrideEnv() || f.isBaseOverrideStop() || f.isBaseOverrideSla()
                    || f.isBaseOverrideWarmup() || f.isBaseOverrideData() || f.isBaseOverrideGenerators();
            if (!anySection) {
                throw new IllegalArgumentException("Выберите хотя бы один раздел для отправки в остальные профили");
            }
            f.setBaseConfigSubmitted(true);
            f.setBaseProfileActive(false);
            load.save(id, f);
            int updated = load.syncBaseToAllProfiles(id, user == null ? "system" : user.getName());
            ra.addFlashAttribute("info", updated > 0
                    ? "Выбранные параметры отправлены в остальные профили (" + updated + "). Профили остаются независимыми."
                    : "Базовая нагрузка сохранена (других профилей у скрипта пока нет)");
            return "redirect:/scripts/" + id + "/load";
        } catch (IllegalArgumentException | IllegalStateException e) {
            model.addAttribute("error", e.getMessage());
            fill(model, id, f, Math.max(1, f.getGeneratorCount() == null ? scripts.get(id).getGeneratorCount() : f.getGeneratorCount()));
            return "scripts/load";
        }
    }

    /** Сохранение нагрузки из JSON-конфига (та же валидация, что у формы). */
    @PostMapping("/load/json")
    public String saveJson(@PathVariable Long id, @RequestParam String json, Model model, RedirectAttributes ra) {
        try {
            load.save(id, LoadJson.fromJson(json));
            ra.addFlashAttribute("info", "Нагрузка сохранена из конфига");
            return "redirect:/scripts/" + id;
        } catch (IllegalArgumentException | IllegalStateException e) {
            fill(model, id, load.toForm(id), scripts.get(id).getGeneratorCount());
            model.addAttribute("error", e.getMessage());
            model.addAttribute("formJson", json);
            model.addAttribute("jsonOpen", true);
            return "scripts/load";
        }
    }

    /**
     * Сохранение правок конфига k6: в базу уходит только отличие от расчётного конфига, поэтому правки (maxVUs и т.п.)
     * не теряются при изменении нагрузки. Конфиг проверяется самим k6 (inspect --execution-requirements).
     */
    @PostMapping("/k6-config")
    public String saveK6Config(@PathVariable Long id, @RequestParam String config, Model model, RedirectAttributes ra) {
        try {
            RunPlan base = planner.plan(id, scripts.get(id).getGeneratorCount());
            if (base.baseConfig() == null) {
                throw new IllegalArgumentException("Сначала опишите нагрузку: " + String.join("; ", base.errors()));
            }
            JsonNode edited;
            try {
                edited = MAPPER.readTree(config);
            } catch (IOException e) {
                throw new IllegalArgumentException("Некорректный JSON: " + e.getMessage().lines().findFirst().orElse(""));
            }
            if (edited == null || !edited.isObject() || !edited.path("scenarios").isObject()) {
                throw new IllegalArgumentException("Конфиг должен быть JSON-объектом с полем scenarios");
            }
            if (!names(edited.path("scenarios")).equals(names(base.baseConfig().path("scenarios")))) {
                throw new IllegalArgumentException("Сценарии нельзя добавлять и удалять: набор операций задаётся на странице нагрузки выше");
            }
            for (var e : (Iterable<java.util.Map.Entry<String, JsonNode>>) () -> edited.path("scenarios").fields()) {
                if (!e.getValue().isObject() || !e.getValue().hasNonNull("executor")) {
                    throw new IllegalArgumentException("У сценария «" + e.getKey() + "» должен быть executor");
                }
            }
            ObjectNode patch = K6ConfigOverrides.diff(base.baseConfig(), edited);
            var overrides = K6ConfigOverrides.toMap(patch);
            // k6 сам проверяет, что конфиг допустим (maxVUs >= preAllocatedVUs, типы, названия executor-ов)
            if (!overrides.isEmpty() && k6.locate().isPresent() && base.version() != null) {
                String err = validateWithK6(base, K6ConfigOverrides.apply(base.baseConfig(), overrides).config().toString());
                if (err != null) {
                    throw new IllegalArgumentException("k6 не принял конфиг: " + err);
                }
            }
            load.saveK6Overrides(id, overrides);
            ra.addFlashAttribute("info", overrides.isEmpty() ? "Правок нет: конфиг совпадает с расчётным"
                    : "Конфиг k6 сохранён, ручных правок: " + K6ConfigOverrides.paths(overrides).size());
            return "redirect:/scripts/" + id + "/load#t-config";
        } catch (IllegalArgumentException | IllegalStateException e) {
            fill(model, id, load.toForm(id), scripts.get(id).getGeneratorCount());
            model.addAttribute("error", e.getMessage());
            model.addAttribute("editedConfig", config);
            model.addAttribute("configTabOpen", true);
            return "scripts/load";
        }
    }

    @PostMapping("/k6-config/reset")
    public String resetK6Config(@PathVariable Long id, RedirectAttributes ra) {
        load.saveK6Overrides(id, new java.util.LinkedHashMap<>());
        ra.addFlashAttribute("info", "Ручные правки конфига k6 сброшены");
        return "redirect:/scripts/" + id + "/load#t-config";
    }

    private static java.util.Set<String> names(JsonNode scenarios) {
        java.util.Set<String> out = new java.util.TreeSet<>();
        scenarios.fieldNames().forEachRemaining(out::add);
        return out;
    }

    /** null - k6 принял конфиг (или проверить не удалось из-за таймаута/окружения), иначе текст ошибки. */
    private String validateWithK6(RunPlan plan, String configJson) {
        Path dir = null;
        try {
            dir = Files.createTempDirectory("k6lab-cfg");
            ZipUtil.extract(new ByteArrayInputStream(scripts.zipBytes(plan.version())), dir);
            Files.createDirectories(dir.resolve("__admin"));
            Files.write(dir.resolve("__admin/admin.js"), helper.content());
            K6Tool.Result r = k6.requirements(dir, plan.version().getEntrypoint(), configJson);
            if (!r.ok() && !r.timedOut()) {
                String s = r.stderr().isBlank() ? r.stdout() : r.stderr();
                return s.strip().lines().reduce((a, b) -> b).orElse("ошибка");
            }
            return null;
        } catch (IOException e) {
            return null;
        } finally {
            if (dir != null) {
                try (var walk = Files.walk(dir)) {
                    walk.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
                } catch (IOException ignored) {
                    // best-effort
                }
            }
        }
    }

    @GetMapping(value = "/load.json", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> loadJson(@PathVariable Long id) {
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename("load-" + id + ".json").build().toString())
                .body(LoadJson.toJson(load.toForm(id)));
    }

    private void fill(Model model, Long id, LoadForm f, int generators) {
        fill(model, id, f, generators, null);
    }

    private void fill(Model model, Long id, LoadForm f, int generators, Long loadedProfileId) {
        var profList = profiles.list(id);
        int gens = Math.max(1, generators);
        model.addAttribute("profiles", profList);
        model.addAttribute("profilesJson", profilesJson(profList));
        String fJson;
        try {
            fJson = LoadJson.toJson(f);
        } catch (IllegalArgumentException e) {
            fJson = "{}";
        }
        model.addAttribute("formJson", fJson);
        RunPlan plan = planFor(id, gens, loadedProfileId);
        boolean baseDefined = load.isDefined(id);
        boolean hasReadyProfile = !baseDefined && profList.stream().anyMatch(p -> {
            try {
                return planner.plan(id, gens, load.fromProfile(id, p.id())).ok();
            } catch (IllegalArgumentException e) {
                return false;
            }
        });
        model.addAttribute("script", scripts.get(id));
        model.addAttribute("form", f);
        model.addAttribute("plan", plan);
        model.addAttribute("planFromProfile", loadedProfileId != null);
        model.addAttribute("baseDefined", baseDefined);
        model.addAttribute("hasReadyProfile", hasReadyProfile);
        model.addAttribute("generators", plan.generatorCount());
        model.addAttribute("secretNames", secrets.list().stream().map(Secret::getName).toList());
        model.addAttribute("scriptCharts", chartTemplates.list(id));
        model.addAttribute("modes", ScriptDataFile.Mode.values());
        model.addAttribute("executors", ru.k6lab.core.k6config.K6ConfigGenerator.executors());
        model.addAttribute("operationNames", scripts.latest(id).map(v -> v.operationNames()).orElse(List.of()));
        model.addAttribute("chart", plan.generated() == null ? "null" : chartJson(plan.generated()));
        model.addAttribute("overridePaths", plan.overridePaths());
        // операции, функций которых нет в последней версии скрипта: убраны из списка и из запуска
        model.addAttribute("staleOps", load.staleOperations(id).stream().map(LoadOperation::getName).toList());
        model.addAttribute("baseConfigJson", plan.baseConfig() == null ? null : plan.baseConfig().toPrettyString());
        if (plan.generated() != null) {
            model.addAttribute("opNames", plan.generated().operations().stream().map(GeneratedConfig.OpPlan::name).toList());
            model.addAttribute("perGen", perGenerator(plan));
        }
    }

    /** План запуска для базовой нагрузки скрипта или открытого профиля. */
    private RunPlan planFor(Long id, int generators, Long profileId) {
        int gens = Math.max(1, generators);
        if (profileId == null) {
            return planner.plan(id, gens);
        }
        try {
            return planner.plan(id, gens, load.fromProfile(id, profileId));
        } catch (IllegalArgumentException e) {
            var script = scripts.get(id);
            var version = scripts.latest(id).orElse(null);
            return new RunPlan(script, version, List.of(), List.of(), RunPlanner.GRACEFUL_STOP_SEC, RunPlanner.GRACEFUL_RAMP_DOWN_SEC,
                    null, null, null, List.of(e.getMessage()), List.of(), gens, null, List.of());
        }
    }

    @GetMapping(value = "/config.json", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> configJson(@PathVariable Long id, @RequestParam(required = false) Long profile) {
        RunPlan plan = planFor(id, scripts.get(id).getGeneratorCount(), profile);
        if (plan.configJson() == null) {
            return ResponseEntity.unprocessableEntity().body("{\"error\":\"" + String.join("; ", plan.errors()).replace("\"", "'") + "\"}");
        }
        String fname = profile == null ? "k6-config-" + id + ".json" : "k6-config-" + id + "-profile-" + profile + ".json";
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(fname).build().toString())
                .body(plan.configJson());
    }

    /** «Требования к ресурсам»: k6 inspect --execution-requirements с итоговым конфигом (htmx). */
    @PostMapping("/requirements")
    public String requirements(@PathVariable Long id, @RequestParam(required = false) Long profile, Model model) {
        RunPlan plan = planFor(id, scripts.get(id).getGeneratorCount(), profile);
        if (plan.configJson() == null || plan.version() == null) {
            model.addAttribute("error", String.join("; ", plan.errors()));
            return "scripts/_requirements :: requirements";
        }
        Path dir = null;
        try {
            dir = Files.createTempDirectory("k6lab-req");
            ZipUtil.extract(new ByteArrayInputStream(scripts.zipBytes(plan.version())), dir);
            Files.createDirectories(dir.resolve("__admin"));
            Files.write(dir.resolve("__admin/admin.js"), helper.content());
            K6Tool.Result r = k6.requirements(dir, plan.version().getEntrypoint(), plan.configJson());
            if (!r.ok()) {
                model.addAttribute("error", r.timedOut() ? "k6 inspect не завершился за 60 с" : r.stderr().strip());
            } else {
                var node = MAPPER.readTree(r.stdout());
                model.addAttribute("maxVus", node.path("maxVUs").asText("?"));
                model.addAttribute("totalDuration", node.path("totalDuration").asText("?"));
            }
        } catch (IOException e) {
            model.addAttribute("error", e.getMessage());
        } finally {
            if (dir != null) {
                try (var walk = Files.walk(dir)) {
                    walk.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
                } catch (IOException ignored) {
                    // best-effort
                }
            }
        }
        return "scripts/_requirements :: requirements";
    }

    /** Данные для stacked-графика интенсивности по операциям: точки (минуты, оп/сек). */
    static String chartJson(GeneratedConfig g) {
        ObjectNode root = MAPPER.createObjectNode();
        ArrayNode datasets = root.putArray("datasets");
        for (GeneratedConfig.OpPlan p : g.operations()) {
            ObjectNode ds = datasets.addObject();
            ds.put("label", p.name());
            ArrayNode pts = ds.putArray("data");
            double prev = g.windows().get(0).rampStartSec() == g.windows().get(0).holdStartSec() ? p.ratesPerSec()[0] : 0;
            for (int i = 0; i < g.windows().size(); i++) {
                var w = g.windows().get(i);
                double r = p.ratesPerSec()[i];
                pts.addObject().put("x", w.rampStartSec() / 60.0).put("y", round(prev));
                pts.addObject().put("x", w.holdStartSec() / 60.0).put("y", round(r));
                pts.addObject().put("x", w.holdEndSec() / 60.0).put("y", round(r));
                prev = r;
            }
        }
        return root.toString();
    }

    private static double round(double v) {
        return Math.round(v * 1000.0) / 1000.0;
    }

    /** Нагрузка на один генератор при равном делении. */
    private record PerGenerator(double ratePerSec, long vus, long preAllocated) {
    }

    private static PerGenerator perGenerator(RunPlan plan) {
        var g = plan.generated();
        int n = Math.max(1, plan.generatorCount());
        return new PerGenerator(g.totalPeakRatePerSec() / n, (long) Math.ceil(g.totalPeakVus() / (double) n),
                (long) Math.ceil(g.operations().stream().mapToLong(GeneratedConfig.OpPlan::preAllocatedVus).sum() / (double) n));
    }

    /** Метаданные профилей (с разбором операций/переменных для карточек в менеджере профилей). */
    private static String profilesJson(List<LoadProfileStore.Profile> list) {
        ArrayNode arr = MAPPER.createArrayNode();
        for (var p : list) {
            ObjectNode n = arr.addObject();
            n.put("id", p.id());
            n.put("name", p.name());
            n.put("updatedAt", p.updatedAt() == null ? "" : p.updatedAt().toString());
            n.put("updatedBy", p.updatedBy() == null ? "" : p.updatedBy());
            try {
                n.set("data", MAPPER.readTree(p.data()));
            } catch (IOException e) {
                n.putObject("data");
            }
        }
        return arr.toString();
    }
}
