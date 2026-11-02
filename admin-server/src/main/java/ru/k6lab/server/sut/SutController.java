package ru.k6lab.server.sut;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
@RequestMapping("/sut")
public class SutController {

    private final SutService service;

    public SutController(SutService service) {
        this.service = service;
    }

    @GetMapping
    public String list(Model model) {
        model.addAttribute("items", service.list());
        return "sut/list";
    }

    @GetMapping("/new")
    public String create(Model model) {
        SutForm f = new SutForm();
        f.getMetrics().add(new SutForm.MetricForm());
        model.addAttribute("form", f);
        return "sut/form";
    }

    @GetMapping("/{id}/edit")
    public String edit(@PathVariable Long id, Model model) {
        model.addAttribute("form", service.toForm(id));
        return "sut/form";
    }

    /** htmx: пустая строка метрики. */
    @GetMapping("/metric-row")
    public String metricRow(@RequestParam int index, Model model) {
        model.addAttribute("i", index);
        model.addAttribute("m", new SutForm.MetricForm());
        return "sut/_row :: metricRow";
    }

    @PostMapping
    public String save(@ModelAttribute("form") SutForm f, Model model, RedirectAttributes ra) {
        try {
            service.save(f);
            ra.addFlashAttribute("info", "SUT сохранён");
            return "redirect:/sut";
        } catch (IllegalArgumentException e) {
            model.addAttribute("error", e.getMessage());
            return "sut/form";
        }
    }

    @PostMapping("/{id}/delete")
    public String delete(@PathVariable Long id, RedirectAttributes ra) {
        try {
            service.delete(id);
        } catch (IllegalStateException e) {
            ra.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/sut";
    }
}
