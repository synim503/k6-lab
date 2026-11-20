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

    @PostMapping("/scripts/{id}/charts")
    public String save(@PathVariable Long id, @RequestParam String name, @RequestParam String promql,
                       @RequestParam(required = false) String unit, Principal user, RedirectAttributes ra) {
        scripts.get(id);
        String n = name == null ? "" : name.strip();
        String q = promql == null ? "" : promql.strip();
        if (n.isEmpty() || n.length() > 120) {
            ra.addFlashAttribute("error", "Имя графика: от 1 до 120 символов");
        } else if (q.isEmpty()) {
            ra.addFlashAttribute("error", "Введите PromQL-запрос");
        } else {
            store.save(id, n, q, unit == null || unit.isBlank() ? null : unit.strip(), user == null ? "system" : user.getName());
            ra.addFlashAttribute("info", "График «" + n + "» сохранён");
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
