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

    public ChartTemplateController(ScriptService scripts, ChartTemplateStore store) {
        this.scripts = scripts;
        this.store = store;
    }

    @GetMapping("/scripts/{id}/charts")
    public String page(@PathVariable Long id, Model model) {
        model.addAttribute("script", scripts.get(id));
        model.addAttribute("templates", store.list(id));
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

    @PostMapping("/scripts/{id}/charts/{tid}/toggle")
    public String toggle(@PathVariable Long id, @PathVariable Long tid, @RequestParam boolean enabled) {
        store.setEnabled(id, tid, enabled);
        return "redirect:/scripts/" + id + "/charts";
    }

    @PostMapping("/scripts/{id}/charts/{tid}/delete")
    public String delete(@PathVariable Long id, @PathVariable Long tid, RedirectAttributes ra) {
        store.delete(id, tid);
        ra.addFlashAttribute("info", "График удалён");
        return "redirect:/scripts/" + id + "/charts";
    }
}
