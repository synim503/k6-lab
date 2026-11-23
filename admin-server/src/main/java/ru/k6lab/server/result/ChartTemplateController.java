package ru.k6lab.server.result;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import ru.k6lab.server.metrics.PrometheusClient;
import ru.k6lab.server.run.Run;
import ru.k6lab.server.run.RunRepository;
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
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/** Конструктор своих графиков отчёта скрипта: PromQL-запросы, предпросмотр на данных запусков, порядок, переменные и импорт Grafana. */
@Controller
public class ChartTemplateController {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Pattern VAR_NAME = Pattern.compile("[A-Za-z_][A-Za-z0-9_]{0,63}");
    private static final DateTimeFormatter RUN_DT = DateTimeFormatter.ofPattern("dd.MM HH:mm").withZone(ZoneId.systemDefault());

    public record RunOption(long id, String label, String status) {
    }

    public record QueryDto(String promql, String legend) {
    }

    public record SaveChartReq(Long id, String name, String unit, Boolean enabled, List<QueryDto> queries,
                               Map<String, String> variables) {
    }

    public record PreviewReq(String name, String unit, List<QueryDto> queries, Map<String, String> variables,
                             Long runId, Integer minutes) {
    }

    public record IdsReq(List<Long> ids) {
    }

    public record BulkToggleReq(List<Long> ids, boolean enabled) {
    }

    public record VarReq(String name, String value) {
    }

    private final ScriptService scripts;
    private final ChartTemplateStore store;
    private final GrafanaDashboardImport grafana;
    private final ChartVariableStore variables;
    private final RunRepository runs;
    private final CustomChartService customCharts;
    private final PrometheusClient prom;
    private final SettingsService settings;
    private final ChartStyleService chartStyle;

    public ChartTemplateController(ScriptService scripts, ChartTemplateStore store, GrafanaDashboardImport grafana,
                                   ChartVariableStore variables, RunRepository runs, CustomChartService customCharts,
                                   PrometheusClient prom, SettingsService settings, ChartStyleService chartStyle) {
        this.scripts = scripts;
        this.store = store;
        this.grafana = grafana;
        this.variables = variables;
        this.runs = runs;
        this.customCharts = customCharts;
        this.prom = prom;
        this.settings = settings;
        this.chartStyle = chartStyle;
    }

    @GetMapping("/scripts/{id}/charts")
    public String page(@PathVariable Long id, Model model) {
        model.addAttribute("script", scripts.get(id));
        List<ChartTemplateStore.Template> templates = store.list(id);
        List<ChartVariableStore.Variable> vars = variables.list(id);
        model.addAttribute("templates", templates);
        model.addAttribute("variables", vars);
        model.addAttribute("runs", recentRuns(id));
        model.addAttribute("promConfigured", settings.prometheus().configured());
        model.addAttribute("chartStyle", chartStyle.get());
        model.addAttribute("templatesJson", toJson(templates));
        model.addAttribute("variablesJson", toJson(vars));
        return "scripts/charts";
    }

    /** Импорт дашборда Grafana: каждая панель с запросами Prometheus становится графиком отчёта (график с тем же именем обновляется). */
    @PostMapping("/scripts/{id}/charts/import-grafana")
    public String importGrafana(@PathVariable Long id, @RequestParam(required = false) String json,
                                @RequestParam(required = false) org.springframework.web.multipart.MultipartFile file,
                                Principal user, RedirectAttributes ra) {
        scripts.get(id);
        try {
            String body = file != null && !file.isEmpty() ? new String(file.getBytes(), StandardCharsets.UTF_8) : json;
            var res = grafana.parse(body);
            if (res.panels().isEmpty()) {
                throw new IllegalArgumentException("В дашборде нет панелей с запросами Prometheus"
                        + (res.skipped().isEmpty() ? "" : ". Пропущено: " + String.join("; ", res.skipped())));
            }
            String by = user == null ? "system" : user.getName();
            for (var pn : res.panels()) {
                Map<String, String> panelVars = panelVariables(pn, res.variables());
                store.save(id, null, pn.name(), pn.queries(), pn.unit().isEmpty() ? null : pn.unit(), true, panelVars, by);
            }
            long newVars = res.variables().entrySet().stream().filter(e -> variables.addIfAbsent(id, e.getKey(), e.getValue())).count();
            ra.addFlashAttribute("info", "Из дашборда" + (res.dashboardTitle().isBlank() ? "" : " «" + res.dashboardTitle() + "»")
                    + " создано графиков: " + res.panels().size()
                    + (res.variables().isEmpty() ? "" : ", переменных: " + res.variables().size() + (newVars < res.variables().size() ? " (новых " + newVars + ", остальные уже были)" : ""))
                    + (res.skipped().isEmpty() ? "" : ". Пропущено панелей: " + res.skipped().size() + " (" + String.join("; ", res.skipped()) + ")"));
        } catch (IllegalArgumentException | IOException e) {
            ra.addFlashAttribute("error", "Импорт дашборда не выполнен. " + e.getMessage());
            ra.addFlashAttribute("grafanaJson", json);
        }
        return "redirect:/scripts/" + id + "/charts";
    }

    private static final Pattern VAR_REF = Pattern.compile("\\$\\{([A-Za-z_][A-Za-z0-9_]*)(?::[^}]*)?}|\\$([A-Za-z_][A-Za-z0-9_]*)");

    /** Отбирает из переменных дашборда Grafana те, что реально используются в имени или PromQL конкретной панели. */
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

    /** Сохранение через стандартную HTML-форму (обратная совместимость). */
    @PostMapping("/scripts/{id}/charts")
    public String save(@PathVariable Long id, @RequestParam(required = false) Long templateId,
                       @RequestParam String name, jakarta.servlet.http.HttpServletRequest req,
                       @RequestParam(required = false) String unit,
                       @RequestParam(required = false) Boolean enabled,
                       Principal user, RedirectAttributes ra) {
        scripts.get(id);
        List<String> promql = Arrays.asList(req.getParameterValues("promql") == null ? new String[0] : req.getParameterValues("promql"));
        List<String> legend = Arrays.asList(req.getParameterValues("legend") == null ? new String[0] : req.getParameterValues("legend"));
        List<ChartTemplateStore.Query> queries = new ArrayList<>();
        for (int i = 0; i < promql.size(); i++) {
            String q = promql.get(i) == null ? "" : promql.get(i).strip();
            if (!q.isEmpty()) {
                String l = i < legend.size() && legend.get(i) != null ? legend.get(i).strip() : "";
                queries.add(new ChartTemplateStore.Query(q, l));
            }
        }
        String n = name == null ? "" : name.strip();
        String u = unit == null || unit.isBlank() ? null : unit.strip();
        String err = validateChart(n, u, queries);
        if (err != null) {
            ra.addFlashAttribute("error", err);
        } else {
            try {
                store.save(id, templateId, n, queries, u, enabled, user == null ? "system" : user.getName());
                ra.addFlashAttribute("info", "График «" + n + "» сохранён (запросов: " + queries.size() + ")");
            } catch (IllegalArgumentException | org.springframework.dao.InvalidDataAccessApiUsageException e) {
                ra.addFlashAttribute("error", rootMessage(e));
            }
        }
        return "redirect:/scripts/" + id + "/charts";
    }

    /** Задать или изменить значение переменной графиков (HTML-форма). */
    @PostMapping("/scripts/{id}/charts/variables")
    public String saveVariable(@PathVariable Long id, @RequestParam String name, @RequestParam(defaultValue = "") String value, RedirectAttributes ra) {
        scripts.get(id);
        String n = name == null ? "" : name.strip().replaceFirst("^\\$", "");
        if (!VAR_NAME.matcher(n).matches()) {
            ra.addFlashAttribute("error", "Имя переменной: буквы, цифры и _, не с цифры (в запросе пишется $имя)");
        } else {
            variables.save(id, n, value == null ? "" : value.strip());
            ra.addFlashAttribute("info", "Переменная $" + n + " сохранена");
        }
        return "redirect:/scripts/" + id + "/charts";
    }

    @PostMapping("/scripts/{id}/charts/variables/{vid}/delete")
    public String deleteVariable(@PathVariable Long id, @PathVariable Long vid) {
        variables.delete(id, vid);
        return "redirect:/scripts/" + id + "/charts";
    }

    @PostMapping("/scripts/{id}/charts/{tid}/toggle")
    public String toggle(@PathVariable Long id, @PathVariable Long tid, @RequestParam boolean enabled) {
        store.setEnabled(id, tid, enabled);
        return "redirect:/scripts/" + id + "/charts";
    }

    /** Удалить отмеченные галочками графики. */
    @PostMapping("/scripts/{id}/charts/delete-selected")
    public String deleteSelected(@PathVariable Long id, @RequestParam(name = "ids", required = false) List<Long> ids, RedirectAttributes ra) {
        int n = store.deleteMany(id, ids);
        ra.addFlashAttribute(n > 0 ? "info" : "error", n > 0 ? "Удалено графиков: " + n : "Не выбрано ни одного графика");
        return "redirect:/scripts/" + id + "/charts";
    }

    @PostMapping("/scripts/{id}/charts/delete-all")
    public String deleteAll(@PathVariable Long id, RedirectAttributes ra) {
        ra.addFlashAttribute("info", "Удалено графиков: " + store.deleteAll(id));
        return "redirect:/scripts/" + id + "/charts";
    }

    @PostMapping("/scripts/{id}/charts/{tid}/delete")
    public String delete(@PathVariable Long id, @PathVariable Long tid, RedirectAttributes ra) {
        store.delete(id, tid);
        ra.addFlashAttribute("info", "График удалён");
        return "redirect:/scripts/" + id + "/charts";
    }

    // ==================== AJAX / JSON API для интерактивного конструктора ====================

    @PostMapping("/scripts/{id}/charts/api/save")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> apiSave(@PathVariable Long id, @RequestBody SaveChartReq req, Principal user) {
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
        } catch (IllegalArgumentException | org.springframework.dao.InvalidDataAccessApiUsageException e) {
            return ResponseEntity.badRequest().body(Map.of("ok", false, "error", rootMessage(e)));
        }
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

    @PostMapping("/scripts/{id}/charts/api/{tid}/toggle")
    @ResponseBody
    public Map<String, Object> apiToggle(@PathVariable Long id, @PathVariable Long tid, @RequestBody Map<String, Boolean> body) {
        scripts.get(id);
        boolean en = Boolean.TRUE.equals(body.get("enabled"));
        store.setEnabled(id, tid, en);
        return Map.of("ok", true, "id", tid, "enabled", en);
    }

    @PostMapping("/scripts/{id}/charts/api/{tid}/duplicate")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> apiDuplicate(@PathVariable Long id, @PathVariable Long tid, Principal user) {
        scripts.get(id);
        try {
            ChartTemplateStore.Template copy = store.duplicate(id, tid, user == null ? "system" : user.getName());
            return ResponseEntity.ok(Map.of("ok", true, "template", copy, "templates", store.list(id),
                    "message", "Создана копия «" + copy.name() + "»"));
        } catch (IllegalArgumentException | org.springframework.dao.InvalidDataAccessApiUsageException e) {
            return ResponseEntity.badRequest().body(Map.of("ok", false, "error", rootMessage(e)));
        }
    }

    @PostMapping("/scripts/{id}/charts/api/{tid}/delete")
    @ResponseBody
    public Map<String, Object> apiDelete(@PathVariable Long id, @PathVariable Long tid) {
        scripts.get(id);
        boolean removed = store.delete(id, tid);
        return Map.of("ok", removed, "templates", store.list(id));
    }

    @PostMapping("/scripts/{id}/charts/api/bulk-toggle")
    @ResponseBody
    public Map<String, Object> apiBulkToggle(@PathVariable Long id, @RequestBody BulkToggleReq req) {
        scripts.get(id);
        int n = store.setEnabledMany(id, req.ids(), req.enabled());
        return Map.of("ok", true, "count", n, "templates", store.list(id));
    }

    @PostMapping("/scripts/{id}/charts/api/delete-selected")
    @ResponseBody
    public Map<String, Object> apiDeleteSelected(@PathVariable Long id, @RequestBody IdsReq req) {
        scripts.get(id);
        int n = store.deleteMany(id, req.ids());
        return Map.of("ok", true, "count", n, "templates", store.list(id));
    }

    @PostMapping("/scripts/{id}/charts/api/reorder")
    @ResponseBody
    public Map<String, Object> apiReorder(@PathVariable Long id, @RequestBody IdsReq req) {
        scripts.get(id);
        store.reorder(id, req.ids());
        return Map.of("ok", true, "templates", store.list(id));
    }

    @PostMapping("/scripts/{id}/charts/api/preview")
    @ResponseBody
    public CustomChartService.PreviewResult apiPreview(@PathVariable Long id, @RequestBody PreviewReq req) {
        scripts.get(id);
        List<ChartTemplateStore.Query> queries = toQueries(req.queries());
        if (queries.isEmpty()) {
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

    @GetMapping("/scripts/{id}/charts/api/metrics")
    @ResponseBody
    public Map<String, Object> apiMetrics(@PathVariable Long id) {
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

    @PostMapping("/scripts/{id}/charts/api/variables/save")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> apiSaveVariable(@PathVariable Long id, @RequestBody VarReq req) {
        scripts.get(id);
        String n = req.name() == null ? "" : req.name().strip().replaceFirst("^\\$", "");
        if (!VAR_NAME.matcher(n).matches()) {
            return ResponseEntity.badRequest().body(Map.of("ok", false,
                    "error", "Имя переменной: латинские буквы, цифры и _, не с цифры (в запросе пишется $имя)"));
        }
        variables.save(id, n, req.value() == null ? "" : req.value().strip());
        return ResponseEntity.ok(Map.of("ok", true, "name", n, "variables", variables.list(id)));
    }

    @PostMapping("/scripts/{id}/charts/api/variables/{vid}/delete")
    @ResponseBody
    public Map<String, Object> apiDeleteVariable(@PathVariable Long id, @PathVariable Long vid) {
        scripts.get(id);
        variables.delete(id, vid);
        return Map.of("ok", true, "variables", variables.list(id));
    }

    // ==================== helpers ====================

    private List<RunOption> recentRuns(long scriptId) {
        List<RunOption> out = new ArrayList<>();
        for (Run r : runs.findByScriptIdOrderByIdDesc(scriptId)) {
            Instant start = r.getPlannedStartAt() != null ? r.getPlannedStartAt() : r.getStartedAt();
            if (start == null) {
                continue;
            }
            Instant end = r.getFinishedAt() != null ? r.getFinishedAt() : Instant.now();
            long mins = Math.max(1, Duration.between(start, end).toMinutes());
            String dur = mins >= 60 ? (mins / 60) + "ч " + (mins % 60) + "м" : mins + " мин";
            String label = "#" + r.getId() + " · " + RUN_DT.format(start) + " (" + dur + ") · " + r.getStatus().name();
            out.add(new RunOption(r.getId(), label, r.getStatus().name()));
            if (out.size() >= 20) {
                break;
            }
        }
        return out;
    }

    private static List<ChartTemplateStore.Query> toQueries(List<QueryDto> dtos) {
        List<ChartTemplateStore.Query> out = new ArrayList<>();
        if (dtos != null) {
            for (QueryDto d : dtos) {
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

    private static String toJson(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            return "[]";
        }
    }
}
