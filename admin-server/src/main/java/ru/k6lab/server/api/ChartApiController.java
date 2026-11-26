package ru.k6lab.server.api;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import ru.k6lab.server.git.GitRepoService;
import ru.k6lab.server.result.ChartTemplateStore;
import ru.k6lab.server.result.ChartVariableStore;
import ru.k6lab.server.result.GrafanaDashboardImport;
import ru.k6lab.server.run.RunRepository;
import ru.k6lab.server.script.Script;
import ru.k6lab.server.script.ScriptService;
import ru.k6lab.server.settings.SettingsService;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.Principal;
import java.util.LinkedHashMap;
import java.util.Map;

/** REST API инициализации и Grafana-импорта графиков отчёта скрипта (основные CRUD доступны на /scripts/{id}/charts/api/*). */
@RestController
@RequestMapping("/api/v1/scripts/{id}/charts")
public class ChartApiController {

    private final ScriptService scripts;
    private final GitRepoService gitRepos;
    private final ChartTemplateStore store;
    private final ChartVariableStore variables;
    private final GrafanaDashboardImport grafana;
    private final RunRepository runs;
    private final SettingsService settings;

    public ChartApiController(ScriptService scripts, GitRepoService gitRepos, ChartTemplateStore store,
                              ChartVariableStore variables, GrafanaDashboardImport grafana,
                              RunRepository runs, SettingsService settings) {
        this.scripts = scripts;
        this.gitRepos = gitRepos;
        this.store = store;
        this.variables = variables;
        this.grafana = grafana;
        this.runs = runs;
        this.settings = settings;
    }

    @GetMapping
    public Map<String, Object> init(@PathVariable Long id) {
        Script s = scripts.get(id);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("script", ScriptApiController.toScriptDto(s, s.getGitRepoId() != null ? safeRepo(s.getGitRepoId()) : null));
        out.put("templates", store.list(id));
        out.put("variables", variables.list(id));
        out.put("prometheusConfigured", settings.prometheus().configured());
        out.put("runs", runs.findTop20ByScriptIdOrderByIdDesc(id).stream().map(r -> Map.of(
                "id", r.getId(),
                "status", r.getStatus().name(),
                "startedAt", r.getStartedAt() != null ? r.getStartedAt().toString() : ""
        )).toList());
        return out;
    }

    @PostMapping("/import-grafana")
    public Map<String, Object> importGrafana(@PathVariable Long id,
                                             @RequestParam(required = false) String json,
                                             @RequestParam(required = false) MultipartFile file,
                                             Principal user) throws IOException {
        String body = file != null && !file.isEmpty() ? new String(file.getBytes(), StandardCharsets.UTF_8) : json;
        var parsed = grafana.parse(body);
        String by = user != null ? user.getName() : "system";
        for (var panel : parsed.panels()) {
            store.save(id, panel.name(), panel.queries(), panel.unit(), by);
        }
        parsed.variables().forEach((k, v) -> variables.addIfAbsent(id, k, v));
        return Map.of(
                "ok", true,
                "imported", parsed.panels().size(),
                "templates", store.list(id),
                "variables", variables.list(id),
                "message", "Импортировано панелей Grafana: " + parsed.panels().size()
        );
    }

    @PostMapping("/delete-all")
    public Map<String, Object> deleteAll(@PathVariable Long id) {
        int n = store.deleteAll(id);
        return Map.of("ok", true, "deleted", n, "templates", store.list(id));
    }

    private ru.k6lab.server.git.GitRepo safeRepo(Long repoId) {
        try {
            return gitRepos.get(repoId);
        } catch (RuntimeException e) {
            return null;
        }
    }
}
