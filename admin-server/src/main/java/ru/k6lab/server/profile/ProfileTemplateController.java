package ru.k6lab.server.profile;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/** Пользовательские типы профилей для методик команды, которых нет среди встроенных (создаёт ADMIN). */
@Controller
@RequestMapping("/profile-templates")
public class ProfileTemplateController {

    static final String EXAMPLE = """
            {
              "params": [
                {"name": "start", "label": "Старт, %", "default": 20},
                {"name": "inc", "label": "Шаг, %", "default": 10},
                {"name": "n", "label": "Число ступеней", "default": 4},
                {"name": "ramp", "label": "Разгон, с", "default": 60},
                {"name": "hold", "label": "Удержание, с", "default": 300}
              ],
              "blocks": [
                {"steps": [{"level": "10", "ramp": "0", "hold": "60", "label": "прогрев"}]},
                {"repeat": {"count": "n", "var": "i"},
                 "steps": [
                   {"level": "start + i*inc", "ramp": "ramp", "hold": "hold", "label": "рост"},
                   {"level": "(start + i*inc) / 2", "ramp": "ramp / 2", "hold": "hold / 2", "label": "откат"}
                 ]}
              ]
            }
            """;

    private final ProfileService service;

    public ProfileTemplateController(ProfileService service) {
        this.service = service;
    }

    @GetMapping
    public String list(Model model) {
        model.addAttribute("items", service.templates());
        return "profiles/templates";
    }

    @GetMapping("/new")
    @PreAuthorize("hasRole('ADMIN')")
    public String create(Model model) {
        model.addAttribute("t", null);
        model.addAttribute("definition", EXAMPLE);
        return "profiles/template-form";
    }

    @GetMapping("/{id}/edit")
    @PreAuthorize("hasRole('ADMIN')")
    public String edit(@PathVariable Long id, Model model) {
        ProfileTemplate t = service.template(id);
        model.addAttribute("t", t);
        model.addAttribute("definition", t.getDefinition().toPrettyString());
        return "profiles/template-form";
    }

    /** Проверка определения: раскрывает шаблон со значениями по умолчанию (htmx). */
    @PostMapping("/preview")
    @PreAuthorize("hasRole('ADMIN')")
    public String preview(@RequestParam String definition, Model model) {
        try {
            model.addAttribute("steps", service.validateDefinition(definition));
        } catch (IllegalArgumentException e) {
            model.addAttribute("error", e.getMessage());
        }
        return "profiles/_steps :: steps";
    }

    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    public String save(@RequestParam(required = false) Long id, @RequestParam String name,
                       @RequestParam(defaultValue = "") String description, @RequestParam String definition,
                       Model model, RedirectAttributes ra) {
        try {
            service.saveTemplate(id, name, description, definition);
            ra.addFlashAttribute("info", "Шаблон профиля сохранён");
            return "redirect:/profile-templates";
        } catch (IllegalArgumentException e) {
            model.addAttribute("error", e.getMessage());
            model.addAttribute("t", id == null ? null : service.template(id));
            model.addAttribute("formName", name);
            model.addAttribute("definition", definition);
            return "profiles/template-form";
        }
    }

    @PostMapping("/{id}/delete")
    @PreAuthorize("hasRole('ADMIN')")
    public String delete(@PathVariable Long id, RedirectAttributes ra) {
        try {
            service.deleteTemplate(id);
        } catch (IllegalStateException e) {
            ra.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/profile-templates";
    }
}
