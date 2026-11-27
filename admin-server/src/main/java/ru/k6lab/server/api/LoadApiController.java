package ru.k6lab.server.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import ru.k6lab.core.util.ZipUtil;
import ru.k6lab.server.git.GitRepoService;
import ru.k6lab.server.load.K6ConfigOverrides;
import ru.k6lab.server.load.LoadForm;
import ru.k6lab.server.load.LoadJson;
import ru.k6lab.server.load.LoadOperation;
import ru.k6lab.server.load.LoadProfileStore;
import ru.k6lab.server.load.LoadService;
import ru.k6lab.server.load.ProfileImportService;
import ru.k6lab.server.load.RunPlan;
import ru.k6lab.server.load.RunPlanner;
import ru.k6lab.server.result.ChartTemplateStore;
import ru.k6lab.server.schedule.ScheduledRunService;
import ru.k6lab.server.script.AdminHelper;
import ru.k6lab.server.script.K6Tool;
import ru.k6lab.server.script.Script;
import ru.k6lab.server.script.ScriptService;
import ru.k6lab.server.secret.Secret;
import ru.k6lab.server.secret.SecretService;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.Principal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/** REST API конструктора нагрузки и профилей для React SPA. */
@RestController
@RequestMapping("/api/v1/scripts/{id}/load")
public class LoadApiController {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final ScriptService scripts;
    private final GitRepoService gitRepos;
    private final LoadService load;
    private final RunPlanner planner;
    private final SecretService secrets;
    private final K6Tool k6;
    private final AdminHelper helper;
    private final LoadProfileStore profiles;
    private final ProfileImportService profileImport;
    private final ScheduledRunService scheduledRuns;
    private final ChartTemplateStore chartTemplates;

    public LoadApiController(ScriptService scripts, GitRepoService gitRepos, LoadService load,
                             RunPlanner planner, SecretService secrets, K6Tool k6,
                             AdminHelper helper, LoadProfileStore profiles,
                             ProfileImportService profileImport, ScheduledRunService scheduledRuns,
                             ChartTemplateStore chartTemplates) {
        this.scripts = scripts;
        this.gitRepos = gitRepos;
        this.load = load;
        this.planner = planner;
        this.secrets = secrets;
        this.k6 = k6;
        this.helper = helper;
        this.profiles = profiles;
        this.profileImport = profileImport;
        this.scheduledRuns = scheduledRuns;
        this.chartTemplates = chartTemplates;
    }

    public record SaveLoadPayload(LoadForm form, Map<String, String> env, String baseWarmupDuration,
                                  Boolean baseOverrideEnv, Boolean baseOverrideStop, Boolean baseOverrideSla,
                                  Boolean baseOverrideWarmup, Boolean baseOverrideData, Boolean baseOverrideGenerators) {
    }

    public record SaveProfilePayload(String profileName, Long profileId, LoadForm form, Map<String, String> env,
                                     String baseWarmupDuration) {
    }

    public record IdsPayload(List<Long> ids) {
    }

    public record RenamePayload(String name) {
    }

    public record ConfigPayload(String config) {
    }

    @GetMapping
    public Map<String, Object> getLoad(@PathVariable Long id,
                                       @RequestParam(required = false) Long profile,
                                       @RequestParam(required = false) Integer generators) {
        Script s = scripts.get(id);
        LoadForm f = load.toForm(id);
        Long loadedProfileId = null;
        String loadedProfileName = null;
        if (profile != null) {
            var p = profiles.find(id, profile)
                    .orElseThrow(() -> new IllegalArgumentException("Профиль не найден"));
            f = LoadJson.fromJson(p.data());
            load.completeWithScriptFunctions(id, f);
            loadedProfileId = p.id();
            loadedProfileName = p.name();
        }
        int gens = Math.max(1, generators != null ? generators : (f.getGeneratorCount() != null ? f.getGeneratorCount() : s.getGeneratorCount()));
        return buildLoadState(s, f, gens, loadedProfileId, loadedProfileName);
    }

    @PostMapping
    public Map<String, Object> saveBase(@PathVariable Long id, @RequestBody SaveLoadPayload req) {
        Script s = scripts.get(id);
        LoadForm f = prepareForm(req.form(), req.env(), req.baseWarmupDuration());
        f.setBaseProfileActive(false);
        load.save(id, f);
        int gens = Math.max(1, f.getGeneratorCount() != null ? f.getGeneratorCount() : s.getGeneratorCount());
        Map<String, Object> state = buildLoadState(scripts.get(id), load.toForm(id), gens, null, null);
        state.put("ok", true);
        state.put("message", "Нагрузка сохранена");
        return state;
    }

    @PostMapping("/apply-base")
    public Map<String, Object> applyBase(@PathVariable Long id, @RequestBody SaveLoadPayload req, Principal user) {
        LoadForm f = prepareForm(req.form(), req.env(), req.baseWarmupDuration());
        f.setBaseOverrideEnv(Boolean.TRUE.equals(req.baseOverrideEnv()));
        f.setBaseOverrideStop(Boolean.TRUE.equals(req.baseOverrideStop()));
        f.setBaseOverrideSla(Boolean.TRUE.equals(req.baseOverrideSla()));
        f.setBaseOverrideWarmup(Boolean.TRUE.equals(req.baseOverrideWarmup()));
        f.setBaseOverrideData(Boolean.TRUE.equals(req.baseOverrideData()));
        f.setBaseOverrideGenerators(Boolean.TRUE.equals(req.baseOverrideGenerators()));
        boolean any = f.isBaseOverrideEnv() || f.isBaseOverrideStop() || f.isBaseOverrideSla()
                || f.isBaseOverrideWarmup() || f.isBaseOverrideData() || f.isBaseOverrideGenerators();
        if (!any) {
            throw new IllegalArgumentException("Выберите хотя бы один раздел для отправки в остальные профили");
        }
        f.setBaseConfigSubmitted(true);
        f.setBaseProfileActive(false);
        load.save(id, f);
        int updated = load.syncBaseToAllProfiles(id, user != null ? user.getName() : "system");
        Script s = scripts.get(id);
        Map<String, Object> state = buildLoadState(s, load.toForm(id), s.getGeneratorCount(), null, null);
        state.put("ok", true);
        state.put("message", updated > 0
                ? "Выбранные параметры отправлены в профили (" + updated + ")"
                : "Базовая нагрузка сохранена");
        return state;
    }

    @PostMapping("/profile")
    public Map<String, Object> saveProfile(@PathVariable Long id, @RequestBody SaveProfilePayload req, Principal user) {
        String name = req.profileName() == null ? "" : req.profileName().strip();
        if (name.isEmpty() || name.length() > 120) {
            throw new IllegalArgumentException("Название профиля: от 1 до 120 символов");
        }
        LoadForm f = prepareForm(req.form(), req.env(), req.baseWarmupDuration());
        if (f.getOps() != null) {
            f.getOps().removeIf(LoadForm.OpForm::blank);
            f.getOps().forEach(LoadForm.OpForm::parseScenario);
        }
        if (f.getOps() == null || f.getOps().stream().noneMatch(LoadForm.OpForm::isEnabled)) {
            throw new IllegalArgumentException("В профиле нет включённых операций");
        }
        load.build(id, f);
        var saved = profiles.save(id, name, LoadJson.toJson(f), user != null ? user.getName() : "system");
        Script s = scripts.get(id);
        int gens = Math.max(1, f.getGeneratorCount() != null ? f.getGeneratorCount() : s.getGeneratorCount());
        Map<String, Object> state = buildLoadState(s, f, gens, saved.id(), name);
        state.put("ok", true);
        state.put("savedProfileId", saved.id());
        state.put("updated", saved.updated());
        state.put("message", saved.updated() ? "Профиль «" + name + "» обновлён" : "Профиль «" + name + "» сохранён");
        return state;
    }

    @PostMapping("/profile/{pid}/delete")
    public Map<String, Object> deleteProfile(@PathVariable Long id, @PathVariable Long pid) {
        profiles.delete(id, pid);
        return Map.of("ok", true, "profiles", serializeProfiles(profiles.list(id)));
    }

    @PostMapping("/profiles/bulk-delete")
    public Map<String, Object> bulkDeleteProfiles(@PathVariable Long id, @RequestBody IdsPayload req) {
        int deleted = profiles.deleteMany(id, req.ids());
        return Map.of("ok", true, "deleted", deleted, "profiles", serializeProfiles(profiles.list(id)));
    }

    @PostMapping("/profile/{pid}/duplicate")
    public Map<String, Object> duplicateProfile(@PathVariable Long id, @PathVariable Long pid, Principal user) {
        var copy = profiles.duplicate(id, pid, user != null ? user.getName() : "system");
        return Map.of("ok", true, "profileId", copy.id(), "profileName", copy.name(), "profiles", serializeProfiles(profiles.list(id)));
    }

    @PostMapping("/profile/{pid}/rename")
    public Map<String, Object> renameProfile(@PathVariable Long id, @PathVariable Long pid,
                                             @RequestBody RenamePayload req, Principal user) {
        var upd = profiles.rename(id, pid, req.name(), user != null ? user.getName() : "system");
        return Map.of("ok", true, "profileId", upd.id(), "profileName", upd.name(), "profiles", serializeProfiles(profiles.list(id)));
    }

    @PostMapping("/profiles/import")
    public Map<String, Object> importProfiles(@PathVariable Long id,
                                              @RequestParam(required = false) String json,
                                              @RequestParam(required = false) MultipartFile file,
                                              @RequestParam(defaultValue = "false") boolean scheduleSequential,
                                              @RequestParam(required = false) String runAt,
                                              @RequestParam(required = false) Integer count,
                                              @RequestParam(defaultValue = "5") int gapMinutes,
                                              @RequestParam(defaultValue = "false") boolean shift,
                                              Principal user) throws IOException {
        String by = user != null ? user.getName() : "system";
        String body = file != null && !file.isEmpty() ? new String(file.getBytes(), StandardCharsets.UTF_8) : json;
        var done = profileImport.importProfiles(id, body, by);
        long upd = done.stream().filter(ProfileImportService.Imported::updated).count();
        String msg = "Импортировано профилей: " + done.size() + (upd > 0 ? " (обновлено: " + upd + ")" : "");
        int scheduledCount = 0;
        if (scheduleSequential && !done.isEmpty()) {
            Instant firstAt = (runAt != null && !runAt.isBlank()) ? Instant.parse(runAt.trim()) : null;
            var specs = done.stream()
                    .map(x -> new ScheduledRunService.SequentialSpec(x.scriptId(), x.profileId()))
                    .toList();
            var ids = scheduledRuns.scheduleSequential(specs, firstAt, count, Math.max(5, gapMinutes) * 60, shift, by);
            scheduledCount = ids.size();
            msg += ". Запланировано последовательно: " + scheduledCount;
        }
        return Map.of(
                "ok", true,
                "importedCount", done.size(),
                "scheduledCount", scheduledCount,
                "message", msg,
                "profiles", serializeProfiles(profiles.list(id))
        );
    }

    @PostMapping("/k6-config")
    public Map<String, Object> saveK6Config(@PathVariable Long id, @RequestBody ConfigPayload req) {
        RunPlan base = planner.plan(id, scripts.get(id).getGeneratorCount());
        if (base.baseConfig() == null) {
            throw new IllegalArgumentException("Сначала опишите и сохраните базовую нагрузку: " + String.join("; ", base.errors()));
        }
        JsonNode edited;
        try {
            edited = MAPPER.readTree(req.config());
        } catch (IOException e) {
            throw new IllegalArgumentException("Некорректный JSON: " + e.getMessage());
        }
        if (edited == null || !edited.isObject() || !edited.path("scenarios").isObject()) {
            throw new IllegalArgumentException("Конфиг должен быть JSON-объектом с полем scenarios");
        }
        if (!scenarioNames(edited.path("scenarios")).equals(scenarioNames(base.baseConfig().path("scenarios")))) {
            throw new IllegalArgumentException("Сценарии нельзя добавлять и удалять вручную");
        }
        for (var e : (Iterable<Map.Entry<String, JsonNode>>) () -> edited.path("scenarios").fields()) {
            if (!e.getValue().isObject() || !e.getValue().hasNonNull("executor")) {
                throw new IllegalArgumentException("У сценария «" + e.getKey() + "» должен быть executor");
            }
        }
        ObjectNode patch = K6ConfigOverrides.diff(base.baseConfig(), edited);
        var overrides = K6ConfigOverrides.toMap(patch);
        if (!overrides.isEmpty() && k6.locate().isPresent() && base.version() != null) {
            String err = validateWithK6(base, K6ConfigOverrides.apply(base.baseConfig(), overrides).config().toString());
            if (err != null) {
                throw new IllegalArgumentException("k6 не принял конфиг: " + err);
            }
        }
        load.saveK6Overrides(id, overrides);
        Script s = scripts.get(id);
        Map<String, Object> state = buildLoadState(s, load.toForm(id), s.getGeneratorCount(), null, null);
        state.put("ok", true);
        state.put("message", overrides.isEmpty() ? "Правок нет: конфиг совпадает с расчётным"
                : "Конфиг k6 сохранён (ручных правок: " + K6ConfigOverrides.paths(overrides).size() + ")");
        return state;
    }

    @PostMapping("/k6-config/reset")
    public Map<String, Object> resetK6Config(@PathVariable Long id) {
        load.saveK6Overrides(id, new LinkedHashMap<>());
        Script s = scripts.get(id);
        Map<String, Object> state = buildLoadState(s, load.toForm(id), s.getGeneratorCount(), null, null);
        state.put("ok", true);
        state.put("message", "Ручные правки конфига сброшены");
        return state;
    }

    @PostMapping("/requirements")
    public Map<String, Object> requirements(@PathVariable Long id, @RequestParam(required = false) Long profile) {
        Script s = scripts.get(id);
        RunPlan plan = planFor(id, s.getGeneratorCount(), profile);
        if (plan.configJson() == null || plan.version() == null) {
            throw new IllegalArgumentException(String.join("; ", plan.errors()));
        }
        Path dir = null;
        try {
            dir = Files.createTempDirectory("k6lab-req");
            ZipUtil.extract(new ByteArrayInputStream(scripts.zipBytes(plan.version())), dir);
            Files.createDirectories(dir.resolve("__admin"));
            Files.write(dir.resolve("__admin/admin.js"), helper.content());
            K6Tool.Result r = k6.requirements(dir, plan.version().getEntrypoint(), plan.configJson());
            if (!r.ok()) {
                throw new IllegalArgumentException(r.timedOut() ? "k6 inspect не завершился за 60 с" : r.stderr().strip());
            }
            var node = MAPPER.readTree(r.stdout());
            return Map.of(
                    "ok", true,
                    "maxVus", node.path("maxVUs").asText("?"),
                    "totalDuration", node.path("totalDuration").asText("?")
            );
        } catch (IOException e) {
            throw new IllegalArgumentException(e.getMessage());
        } finally {
            if (dir != null) {
                try (var walk = Files.walk(dir)) {
                    walk.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
                } catch (IOException ignored) {
                }
            }
        }
    }

    private static LoadForm prepareForm(LoadForm in, Map<String, String> envMap, String baseWarmupDuration) {
        LoadForm f = in != null ? in : new LoadForm();
        if (envMap != null) {
            f.setEnvText(LoadForm.formatKv(envMap));
        }
        if (baseWarmupDuration != null) {
            f.setBaseWarmupDuration(baseWarmupDuration);
        }
        if (f.getOps() == null) {
            f.setOps(new ArrayList<>());
        }
        if (f.getDataFiles() == null) {
            f.setDataFiles(new ArrayList<>());
        }
        if (f.getStop() == null) {
            f.setStop(new LoadForm.StopForm());
        }
        return f;
    }

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
            return new RunPlan(script, version, List.of(), List.of(), 30, 30,
                    null, null, null, List.of(e.getMessage()), List.of(), gens, null, List.of());
        }
    }

    private Map<String, Object> buildLoadState(Script s, LoadForm f, int generators,
                                               Long loadedProfileId, String loadedProfileName) {
        Long id = s.getId();
        var profList = profiles.list(id);
        RunPlan plan = planFor(id, generators, loadedProfileId);
        boolean baseDefined = load.isDefined(id);
        int gens = Math.max(1, generators);
        boolean hasReadyProfile = !baseDefined && profList.stream().anyMatch(p -> {
            try {
                return planner.plan(id, gens, load.fromProfile(id, p.id())).ok();
            } catch (IllegalArgumentException e) {
                return false;
            }
        });

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("script", ScriptApiController.toScriptDto(s, s.getGitRepoId() != null ? safeRepo(s.getGitRepoId()) : null));
        out.put("loadedProfileId", loadedProfileId);
        out.put("loadedProfileName", loadedProfileName);
        out.put("baseDefined", baseDefined);
        out.put("hasReadyProfile", hasReadyProfile);
        out.put("basePushDefaults", Map.of(
                "env", f.isBaseOverrideEnv(),
                "stop", f.isBaseOverrideStop(),
                "sla", f.isBaseOverrideSla(),
                "warmup", f.isBaseOverrideWarmup(),
                "data", f.isBaseOverrideData(),
                "generators", f.isBaseOverrideGenerators()
        ));
        out.put("form", f);
        out.put("env", LoadForm.parseKv(f.getEnvText()));
        out.put("baseWarmupDuration", f.getBaseWarmupDuration());
        out.put("profiles", serializeProfiles(profList));
        out.put("secretNames", secrets.list().stream().map(Secret::getName).toList());
        out.put("scriptCharts", chartTemplates.list(id));
        out.put("staleOps", load.staleOperations(id).stream().map(LoadOperation::getName).toList());
        out.put("operationNames", scripts.latest(id).map(v -> v.operationNames()).orElse(List.of()));
        out.put("plan", Map.of(
                "ok", plan.ok(),
                "errors", plan.errors(),
                "warnings", plan.warnings(),
                "configJson", plan.configJson() != null ? plan.configJson() : "",
                "baseConfigJson", plan.baseConfig() != null ? plan.baseConfig().toPrettyString() : "",
                "overridePaths", plan.overridePaths(),
                "generatorCount", plan.generatorCount(),
                "totalSec", plan.totalSec()
        ));
        return out;
    }

    private List<Map<String, Object>> serializeProfiles(List<LoadProfileStore.Profile> list) {
        List<Map<String, Object>> out = new ArrayList<>(list.size());
        for (var p : list) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", p.id());
            m.put("name", p.name());
            m.put("updatedAt", p.updatedAt());
            m.put("updatedBy", p.updatedBy());
            try {
                m.put("data", MAPPER.readTree(p.data()));
            } catch (IOException e) {
                m.put("data", Map.of());
            }
            out.add(m);
        }
        return out;
    }

    private ru.k6lab.server.git.GitRepo safeRepo(Long repoId) {
        try {
            return gitRepos.get(repoId);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static Set<String> scenarioNames(JsonNode scenarios) {
        Set<String> out = new TreeSet<>();
        scenarios.fieldNames().forEachRemaining(out::add);
        return out;
    }

    private String validateWithK6(RunPlan plan, String configJson) {
        Path dir = null;
        try {
            dir = Files.createTempDirectory("k6lab-cfg");
            ZipUtil.extract(new ByteArrayInputStream(scripts.zipBytes(plan.version())), dir);
            Files.createDirectories(dir.resolve("__admin"));
            Files.write(dir.resolve("__admin/admin.js"), helper.content());
            K6Tool.Result r = k6.requirements(dir, plan.version().getEntrypoint(), configJson);
            if (r.timedOut()) {
                return "Проверка конфигурации через k6 inspect превысила таймаут (60 с)";
            }
            if (!r.ok()) {
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
                }
            }
        }
    }
}
