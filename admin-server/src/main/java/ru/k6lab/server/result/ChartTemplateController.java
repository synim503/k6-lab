package ru.k6lab.server.result;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import ru.k6lab.server.script.ScriptService;

import java.security.Principal;

/** Свои графики отчёта скрипта: вставляется PromQL и задаётся имя графика. */
@Controller
public class ChartTemplateController {

    private final ScriptService scripts;
    private final ChartTemplateStore store;
    private final GrafanaDashboardImport grafana;
    private final ChartVariableStore variables;

    public ChartTemplateController(ScriptService scripts, ChartTemplateStore store, GrafanaDashboardImport grafana, ChartVariableStore variables) {
        this.variables = variables;
        this.scripts = scripts;
        this.store = store;
        this.grafana = grafana;
    }

    /** Импорт дашборда Grafana: каждая панель с запросами Prometheus становится графиком отчёта (график с тем же именем обновляется). */
    @PostMapping("/scripts/{id}/charts/import-grafana")
    public String importGrafana(@PathVariable Long id, @RequestParam(required = false) String json,
                                @RequestParam(required = false) org.springframework.web.multipart.MultipartFile file,
                                Principal user, RedirectAttributes ra) {
        scripts.get(id);
        try {
            String body = file != null && !file.isEmpty() ? new String(file.getBytes(), java.nio.charset.StandardCharsets.UTF_8) : json;
            var res = grafana.parse(body);
            if (res.panels().isEmpty()) {
                throw new IllegalArgumentException("В дашборде нет панелей с запросами Prometheus"
                        + (res.skipped().isEmpty() ? "" : ". Пропущено: " + String.join("; ", res.skipped())));
            }
            String by = user == null ? "system" : user.getName();
            res.panels().forEach(pn -> store.save(id, pn.name(), pn.queries(), pn.unit().isEmpty() ? null : pn.unit(), by));
            // переменные дашборда становятся переменными скрипта; уже заданные (и правленые руками) не затираются
            long newVars = res.variables().entrySet().stream().filter(e -> variables.addIfAbsent(id, e.getKey(), e.getValue())).count();
            ra.addFlashAttribute("info", "Из дашборда" + (res.dashboardTitle().isBlank() ? "" : " «" + res.dashboardTitle() + "»")
                    + " создано графиков: " + res.panels().size()
                    + (res.variables().isEmpty() ? "" : ", переменных: " + res.variables().size() + (newVars < res.variables().size() ? " (новых " + newVars + ", остальные уже были)" : ""))
                    + (res.skipped().isEmpty() ? "" : ". Пропущено панелей: " + res.skipped().size() + " (" + String.join("; ", res.skipped()) + ")"));
        } catch (IllegalArgumentException | java.io.IOException e) {
            ra.addFlashAttribute("error", "Импорт дашборда не выполнен. " + e.getMessage());
            ra.addFlashAttribute("grafanaJson", json);
        }
        return "redirect:/scripts/" + id + "/charts";
    }

    @GetMapping("/scripts/{id}/charts")
    public String page(@PathVariable Long id, Model model) {
        model.addAttribute("script", scripts.get(id));
        model.addAttribute("templates", store.list(id));
        model.addAttribute("variables", variables.list(id));
        return "scripts/charts";
    }

    /** promql и legend приходят параллельными списками: строка формы - один запрос и его подпись в легенде. */
    @PostMapping("/scripts/{id}/charts")
    public String save(@PathVariable Long id, @RequestParam String name, jakarta.servlet.http.HttpServletRequest req,
                       @RequestParam(required = false) String unit, Principal user, RedirectAttributes ra) {
        scripts.get(id);
        // значения берём напрямую: привязка к List<String> режет единственное значение по запятым, а в PromQL они везде
        java.util.List<String> promql = java.util.Arrays.asList(req.getParameterValues("promql") == null ? new String[0] : req.getParameterValues("promql"));
        java.util.List<String> legend = java.util.Arrays.asList(req.getParameterValues("legend") == null ? new String[0] : req.getParameterValues("legend"));
        String n = name == null ? "" : name.strip();
        java.util.List<ChartTemplateStore.Query> queries = new java.util.ArrayList<>();
        for (int i = 0; i < promql.size(); i++) {
            String q = promql.get(i) == null ? "" : promql.get(i).strip();
            if (!q.isEmpty()) {
                String l = legend != null && i < legend.size() && legend.get(i) != null ? legend.get(i).strip() : "";
                queries.add(new ChartTemplateStore.Query(q, l));
            }
        }
        if (n.isEmpty() || n.length() > 120) {
            ra.addFlashAttribute("error", "Имя графика: от 1 до 120 символов");
        } else if (queries.isEmpty()) {
            ra.addFlashAttribute("error", "Введите хотя бы один PromQL-запрос");
        } else if (queries.size() > 20) {
            ra.addFlashAttribute("error", "На одном графике не больше 20 запросов");
        } else {
            store.save(id, n, queries, unit == null || unit.isBlank() ? null : unit.strip(), user == null ? "system" : user.getName());
            ra.addFlashAttribute("info", "График «" + n + "» сохранён (запросов: " + queries.size() + ")");
        }
        return "redirect:/scripts/" + id + "/charts";
    }

    private static final java.util.regex.Pattern VAR_NAME = java.util.regex.Pattern.compile("[A-Za-z_][A-Za-z0-9_]{0,63}");

    /** Задать или изменить значение переменной графиков. */
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
    public String deleteSelected(@PathVariable Long id, @RequestParam(name = "ids", required = false) java.util.List<Long> ids, RedirectAttributes ra) {
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
}
