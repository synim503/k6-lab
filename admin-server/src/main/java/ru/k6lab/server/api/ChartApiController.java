package ru.k6lab.server.api;

import org.springframework.dao.InvalidDataAccessApiUsageException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import ru.k6lab.server.git.GitRepoService;
import ru.k6lab.server.metrics.PrometheusClient;
import ru.k6lab.server.result.ChartStyle;
import ru.k6lab.server.result.ChartStyleService;
import ru.k6lab.server.result.ChartTemplateController;
import ru.k6lab.server.result.ChartTemplateStore;
import ru.k6lab.server.result.ChartVariableStore;
import ru.k6lab.server.result.CustomChartService;
import ru.k6lab.server.result.GrafanaDashboardImport;
import ru.k6lab.server.run.Run;
import ru.k6lab.server.run.RunRepository;
import ru.k6lab.server.script.Script;
import ru.k6lab.server.script.ScriptService;
import ru.k6lab.server.settings.SettingsService;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.Principal;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/** REST API студии графиков отчёта скрипта под /api/v1/scripts/{id}/charts (JSON 401/403, предпросмотр, переменные, копирование, импорт Grafana). */
@RestController
@RequestMapping("/api/v1/scripts/{id}/charts")
public class ChartApiController {

    private static final Pattern VAR_NAME = Pattern.compile("[A-Za-z_][A-Za-z0-9_]{0,63}");
    private static final Pattern VAR_REF = Pattern.compile("\\$\\{([A-Za-z_][A-Za-z0-9_]*)(?::[^}]*)?}|\\$([A-Za-z_][A-Za-z0-9_]*)");
    private static final DateTimeFormatter RUN_DT = DateTimeFormatter.ofPattern("dd.MM HH:mm").withZone(ZoneId.systemDefault());

    private final ScriptService scripts;
    private final GitRepoService gitRepos;
    private final ChartTemplateStore store;
    private final ChartVariableStore variables;
    private final GrafanaDashboardImport grafana;
    private final RunRepository runs;
    private final SettingsService settings;
    private final CustomChartService customCharts;
    private final PrometheusClient prom;
    private final ChartStyleService chartStyle;

    public ChartApiController(ScriptService scripts, GitRepoService gitRepos, ChartTemplateStore store,
                              ChartVariableStore variables, GrafanaDashboardImport grafana,
                              RunRepository runs, SettingsService settings,
                              CustomChartService customCharts, PrometheusClient prom,
                              ChartStyleService chartStyle) {
        this.scripts = scripts;
        this.gitRepos = gitRepos;
        this.store = store;
        this.variables = variables;
        this.grafana = grafana;
        this.runs = runs;
        this.settings = settings;
        this.customCharts = customCharts;
        this.prom = prom;
        this.chartStyle = chartStyle;
    }

    @GetMapping
    public Map<String, Object> init(@PathVariable Long id) {
        Script s = scripts.get(id);
        ChartStyle cs = chartStyle.get();
        Map<String, Object> csDto = new LinkedHashMap<>();
        csDto.put("theme", cs.theme().name());
        csDto.put("width", cs.width());
        csDto.put("height", cs.height());
        csDto.put("legend", cs.legend().name());
        csDto.put("stats", cs.stats());
        csDto.put("maxLegend", cs.maxLegend());
        csDto.put("autoWidth", cs.autoWidth());
        csDto.put("sortBy", cs.sortBy().name());
        csDto.put("sortDir", cs.sortDir().name());
        csDto.put("absoluteTime", chartStyle.absoluteTime());
        csDto.put("skipWarmup", chartStyle.skipWarmup());

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("script", ScriptApiController.toScriptDto(s, s.getGitRepoId() != null ? safeRepo(s.getGitRepoId()) : null));
        out.put("templates", store.list(id));
        out.put("variables", variables.list(id));
        out.put("prometheusConfigured", settings.prometheus().configured());
        out.put("chartStyle", csDto);
        out.put("runs", recentRuns(id));
        return out;
    }

    @PostMapping("/save")
    public ResponseEntity<Map<String, Object>> save(@PathVariable Long id,
                                                    @RequestBody ChartTemplateController.SaveChartReq req,
                                                    Principal user) {
        scripts.get(id);
        String n = req.name() == null ? "" : req.name().strip();
        String u = req.unit() == null || req.unit().isBlank() ? null : req.unit().strip();
        List<ChartTemplateStore.Query> queries = toQueries(req.queries());
        String err = validateChart(n, u, queries);
        if (err != null) {
            return ResponseEntity.badRequest().body(Map.of("ok", false, "error", err));
        }
        Map<String, String> chartVars;
        try {
            chartVars = cleanChartVars(req.variables());
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("ok", false, "error", e.getMessage()));
        }
        try {
            boolean isUpdate = req.id() != null;
            ChartTemplateStore.Template saved = store.save(id, req.id(), n, queries, u, req.enabled(), chartVars,
                    user == null ? "system" : user.getName());
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("ok", true);
            body.put("template", saved);
            body.put("templates", store.list(id));
            body.put("message", (isUpdate ? "График «" : "Создан график «") + saved.name() + (isUpdate ? "» обновлён" : "»"));
            return ResponseEntity.ok(body);
        } catch (IllegalArgumentException | InvalidDataAccessApiUsageException e) {
            return ResponseEntity.badRequest().body(Map.of("ok", false, "error", rootMessage(e)));
        }
    }

    @PostMapping("/{tid}/toggle")
    public Map<String, Object> toggle(@PathVariable Long id, @PathVariable Long tid, @RequestBody Map<String, Boolean> body) {
        scripts.get(id);
        boolean en = Boolean.TRUE.equals(body.get("enabled"));
        store.setEnabled(id, tid, en);
        return Map.of("ok", true, "id", tid, "enabled", en);
    }

    @PostMapping("/{tid}/duplicate")
    public ResponseEntity<Map<String, Object>> duplicate(@PathVariable Long id, @PathVariable Long tid, Principal user) {
        scripts.get(id);
        try {
            ChartTemplateStore.Template copy = store.duplicate(id, tid, user == null ? "system" : user.getName());
            return ResponseEntity.ok(Map.of("ok", true, "template", copy, "templates", store.list(id),
                    "message", "Создана копия «" + copy.name() + "»"));
        } catch (IllegalArgumentException | InvalidDataAccessApiUsageException e) {
            return ResponseEntity.badRequest().body(Map.of("ok", false, "error", rootMessage(e)));
        }
    }

    @PostMapping("/{tid}/delete")
    public Map<String, Object> delete(@PathVariable Long id, @PathVariable Long tid) {
        scripts.get(id);
        boolean removed = store.delete(id, tid);
        return Map.of("ok", removed, "templates", store.list(id));
    }

    @PostMapping("/bulk-toggle")
    public Map<String, Object> bulkToggle(@PathVariable Long id, @RequestBody ChartTemplateController.BulkToggleReq req) {
        scripts.get(id);
        int n = store.setEnabledMany(id, req.ids(), req.enabled());
        return Map.of("ok", true, "count", n, "templates", store.list(id));
    }

    @PostMapping("/delete-selected")
    public Map<String, Object> deleteSelected(@PathVariable Long id, @RequestBody ChartTemplateController.IdsReq req) {
        scripts.get(id);
        int n = store.deleteMany(id, req.ids());
        return Map.of("ok", true, "count", n, "templates", store.list(id));
    }

    @PostMapping("/delete-all")
    public Map<String, Object> deleteAll(@PathVariable Long id) {
        scripts.get(id);
        int n = store.deleteAll(id);
        return Map.of("ok", true, "deleted", n, "templates", store.list(id));
    }

    @PostMapping("/reorder")
    public Map<String, Object> reorder(@PathVariable Long id, @RequestBody ChartTemplateController.IdsReq req) {
        scripts.get(id);
        store.reorder(id, req.ids());
        return Map.of("ok", true, "templates", store.list(id));
    }

    @PostMapping("/preview")
    public CustomChartService.PreviewResult preview(@PathVariable Long id, @RequestBody ChartTemplateController.PreviewReq req) {
        scripts.get(id);
        List<ChartTemplateStore.Query> queries = new ArrayList<>();
        boolean anyNonEmpty = false;
        if (req.queries() != null) {
            for (ChartTemplateController.QueryDto d : req.queries()) {
                String q = d == null || d.promql() == null ? "" : d.promql().strip();
                String l = d == null || d.legend() == null ? "" : d.legend().strip();
                if (!q.isEmpty()) {
                    anyNonEmpty = true;
                }
                queries.add(new ChartTemplateStore.Query(q, l));
            }
        }
        if (!anyNonEmpty) {
            return new CustomChartService.PreviewResult(false, req.name() == null ? "" : req.name(), null, "", 0, 0,
                    "Добавьте хотя бы один PromQL-запрос для предпросмотра", List.of());
        }
        Map<String, String> chartVars;
        try {
            chartVars = cleanChartVars(req.variables());
        } catch (IllegalArgumentException e) {
            return new CustomChartService.PreviewResult(false, req.name() == null ? "" : req.name(), null, "", 0, 0,
                    e.getMessage(), List.of());
        }
        return customCharts.preview(id, req.name(), req.unit(), queries, chartVars, req.runId(), req.minutes());
    }

    @GetMapping("/metrics")
    public Map<String, Object> metrics(@PathVariable Long id) {
        scripts.get(id);
        SettingsService.Prometheus p = settings.prometheus();
        if (!p.configured()) {
            return Map.of("ok", false, "metrics", List.of(), "error", "Prometheus не настроен");
        }
        try {
            List<String> names = prom.metricNames(p).stream().sorted().limit(5000).toList();
            return Map.of("ok", true, "metrics", names);
        } catch (IOException | RuntimeException e) {
            return Map.of("ok", false, "metrics", List.of(), "error", e.getMessage() == null ? e.toString() : e.getMessage());
        }
    }

    @PostMapping("/variables/save")
    public ResponseEntity<Map<String, Object>> saveVariable(@PathVariable Long id, @RequestBody ChartTemplateController.VarReq req) {
        scripts.get(id);
        String n = req.name() == null ? "" : req.name().strip().replaceFirst("^\\$", "");
        if (!VAR_NAME.matcher(n).matches()) {
            return ResponseEntity.badRequest().body(Map.of("ok", false,
                    "error", "Имя переменной: латинские буквы, цифры и _, не с цифры (в запросе пишется $имя)"));
        }
        variables.save(id, n, req.value() == null ? "" : req.value().strip());
        return ResponseEntity.ok(Map.of("ok", true, "name", n, "variables", variables.list(id)));
    }

    @PostMapping("/variables/{vid}/delete")
    public Map<String, Object> deleteVariable(@PathVariable Long id, @PathVariable Long vid) {
        scripts.get(id);
        variables.delete(id, vid);
        return Map.of("ok", true, "variables", variables.list(id));
    }

    @GetMapping("/other-scripts")
    public Map<String, Object> otherScripts(@PathVariable Long id) {
        scripts.get(id);
        Map<Long, Integer> counts = store.chartCountsByScript(id);
        List<ChartTemplateController.SourceScriptDto> list = new ArrayList<>();
        for (var s : scripts.list()) {
            if (s.getId().equals(id) || !counts.containsKey(s.getId())) {
                continue;
            }
            List<ChartTemplateStore.Template> tpls = store.list(s.getId());
            List<ChartVariableStore.Variable> vars = variables.list(s.getId());
            list.add(new ChartTemplateController.SourceScriptDto(s.getId(), s.getName(), tpls.size(), vars.size(), tpls, vars));
        }
        return Map.of("ok", true, "scripts", list);
    }

    @PostMapping("/copy-from-script")
    public ResponseEntity<Map<String, Object>> copyFromScript(@PathVariable Long id,
                                                              @RequestBody ChartTemplateController.CopyFromScriptReq req,
                                                              Principal user) {
        scripts.get(id);
        if (req.sourceScriptId() == null) {
            return ResponseEntity.badRequest().body(Map.of("ok", false, "error", "Выберите скрипт-источник"));
        }
        var srcScript = scripts.get(req.sourceScriptId());
        try {
            boolean overwrite = Boolean.TRUE.equals(req.overwriteExisting());
            boolean copyVars = req.copyVariables() == null || req.copyVariables();
            String by = user == null ? "system" : user.getName();
            List<ChartTemplateStore.Template> copied = store.copyFromScript(
                    srcScript.getId(), id, req.templateIds(), overwrite, by);
            int copiedVars = 0;
            if (copyVars) {
                for (ChartVariableStore.Variable v : variables.list(srcScript.getId())) {
                    if (overwrite) {
                        variables.save(id, v.name(), v.value());
                        copiedVars++;
                    } else if (variables.addIfAbsent(id, v.name(), v.value())) {
                        copiedVars++;
                    }
                }
            }
            String msg = "Из скрипта «" + srcScript.getName() + "» скопировано графиков: " + copied.size()
                    + (copiedVars > 0 ? ", общих переменных: " + copiedVars : "");
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("ok", true);
            body.put("copiedCount", copied.size());
            body.put("copiedVariablesCount", copiedVars);
            body.put("copied", copied);
            body.put("templates", store.list(id));
            body.put("variables", variables.list(id));
            body.put("message", msg);
            return ResponseEntity.ok(body);
        } catch (IllegalArgumentException | InvalidDataAccessApiUsageException e) {
            return ResponseEntity.badRequest().body(Map.of("ok", false, "error", rootMessage(e)));
        }
    }

    @PostMapping("/import-grafana")
    public Map<String, Object> importGrafana(@PathVariable Long id,
                                             @RequestParam(required = false) String json,
                                             @RequestParam(required = false) MultipartFile file,
                                             Principal user) throws IOException {
        scripts.get(id);
        String body = file != null && !file.isEmpty() ? new String(file.getBytes(), StandardCharsets.UTF_8) : json;
        var res = grafana.parse(body);
        if (res.panels().isEmpty()) {
            throw new IllegalArgumentException("В дашборде нет панелей с запросами Prometheus"
                    + (res.skipped().isEmpty() ? "" : ". Пропущено: " + String.join("; ", res.skipped())));
        }
        String by = user != null ? user.getName() : "system";
        for (var pn : res.panels()) {
            Map<String, String> panelVars = panelVariables(pn, res.variables());
            store.save(id, null, pn.name(), pn.queries(), pn.unit().isEmpty() ? null : pn.unit(), true, panelVars, by);
        }
        long newVars = res.variables().entrySet().stream()
                .filter(e -> variables.addIfAbsent(id, e.getKey(), e.getValue()))
                .count();
        String msg = "Из дашборда" + (res.dashboardTitle().isBlank() ? "" : " «" + res.dashboardTitle() + "»")
                + " создано графиков: " + res.panels().size()
                + (res.variables().isEmpty() ? "" : ", переменных: " + res.variables().size()
                + (newVars < res.variables().size() ? " (новых " + newVars + ", остальные уже были)" : ""))
                + (res.skipped().isEmpty() ? "" : ". Пропущено панелей: " + res.skipped().size() + " (" + String.join("; ", res.skipped()) + ")");

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("imported", res.panels().size());
        out.put("newVariablesCount", newVars);
        out.put("skipped", res.skipped());
        out.put("dashboardTitle", res.dashboardTitle());
        out.put("templates", store.list(id));
        out.put("variables", variables.list(id));
        out.put("message", msg);
        return out;
    }

    private List<Map<String, Object>> recentRuns(long scriptId) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Run r : runs.findTop20ByScriptIdOrderByIdDesc(scriptId)) {
            Instant start = r.getPlannedStartAt() != null ? r.getPlannedStartAt() : r.getStartedAt();
            if (start == null) {
                continue;
            }
            Instant end = r.getFinishedAt() != null ? r.getFinishedAt() : Instant.now();
            long mins = Math.max(1, Duration.between(start, end).toMinutes());
            String dur = mins >= 60 ? (mins / 60) + "ч " + (mins % 60) + "м" : mins + " мин";
            String label = "#" + r.getId() + " · " + RUN_DT.format(start) + " (" + dur + ") · " + r.getStatus().name();
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", r.getId());
            m.put("status", r.getStatus().name());
            m.put("startedAt", start.toString());
            m.put("label", label);
            out.add(m);
            if (out.size() >= 20) {
                break;
            }
        }
        return out;
    }

    private static Map<String, String> panelVariables(GrafanaDashboardImport.Panel pn, Map<String, String> dashVars) {
        Map<String, String> out = new LinkedHashMap<>();
        if (dashVars == null || dashVars.isEmpty()) {
            return out;
        }
        java.util.function.Consumer<String> scan = txt -> {
            if (txt == null || txt.isEmpty()) {
                return;
            }
            var m = VAR_REF.matcher(txt);
            while (m.find()) {
                String k = m.group(1) != null ? m.group(1) : m.group(2);
                if (k != null && dashVars.containsKey(k)) {
                    out.putIfAbsent(k, dashVars.get(k));
                }
            }
        };
        scan.accept(pn.name());
        pn.queries().forEach(q -> scan.accept(q.promql()));
        return out;
    }

    private static Map<String, String> cleanChartVars(Map<String, String> raw) {
        Map<String, String> out = new LinkedHashMap<>();
        if (raw == null) {
            return out;
        }
        for (var e : raw.entrySet()) {
            String k = e.getKey() == null ? "" : e.getKey().strip().replaceFirst("^\\$", "");
            if (k.isEmpty()) {
                continue;
            }
            if (!VAR_NAME.matcher(k).matches()) {
                throw new IllegalArgumentException("Имя переменной «" + k + "»: буквы, цифры и _, не с цифры (в запросе пишется $имя)");
            }
            out.put(k, e.getValue() == null ? "" : e.getValue().strip());
        }
        return out;
    }

    private static List<ChartTemplateStore.Query> toQueries(List<ChartTemplateController.QueryDto> dtos) {
        List<ChartTemplateStore.Query> out = new ArrayList<>();
        if (dtos != null) {
            for (ChartTemplateController.QueryDto d : dtos) {
                if (d == null) {
                    continue;
                }
                String q = d.promql() == null ? "" : d.promql().strip();
                if (!q.isEmpty()) {
                    String l = d.legend() == null ? "" : d.legend().strip();
                    out.add(new ChartTemplateStore.Query(q, l));
                }
            }
        }
        return out;
    }

    private static String validateChart(String name, String unit, List<ChartTemplateStore.Query> queries) {
        if (name.isEmpty() || name.length() > 120) {
            return "Имя графика: от 1 до 120 символов";
        }
        if (unit != null && unit.length() > 32) {
            return "Единицы измерения: не более 32 символов";
        }
        if (queries.isEmpty()) {
            return "Введите хотя бы один PromQL-запрос";
        }
        if (queries.size() > 20) {
            return "На одном графике не больше 20 запросов";
        }
        return null;
    }

    private static String rootMessage(RuntimeException e) {
        Throwable cur = e;
        while (cur.getCause() != null && cur.getCause() != cur) {
            cur = cur.getCause();
        }
        return cur.getMessage() != null ? cur.getMessage() : e.getMessage();
    }

    private ru.k6lab.server.git.GitRepo safeRepo(Long repoId) {
        try {
            return gitRepos.get(repoId);
        } catch (RuntimeException e) {
            return null;
        }
    }
}
