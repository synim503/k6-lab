package ru.k6lab.server.profile;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import ru.k6lab.core.profile.ProfileType;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Controller
@RequestMapping("/profiles")
public class ProfileController {

    private final ProfileService service;

    public ProfileController(ProfileService service) {
        this.service = service;
    }

    @GetMapping
    public String list(Model model) {
        model.addAttribute("profiles", service.list());
        return "profiles/list";
    }

    @GetMapping("/new")
    public String create(@RequestParam(defaultValue = "MAX_SEARCH") ProfileType type, Model model) {
        model.addAttribute("p", null);
        model.addAttribute("type", type);
        fill(model, type, null, Map.of());
        return "profiles/form";
    }

    @GetMapping("/{id}/edit")
    public String edit(@PathVariable Long id, Model model) {
        TestProfile p = service.get(id);
        model.addAttribute("p", p);
        model.addAttribute("type", p.getType());
        fill(model, p.getType(), p.getTemplateId(), ProfileService.paramsToForm(p));
        return "profiles/form";
    }

    /** htmx: поля параметров по типу профиля. */
    @GetMapping("/fields")
    public String fields(@RequestParam ProfileType type, @RequestParam(required = false) Long templateId,
                         @RequestParam(required = false) Long id, Model model) {
        Map<String, String> values = Map.of();
        if (id != null) {
            TestProfile p = service.get(id);
            if (p.getType() == type) {
                values = ProfileService.paramsToForm(p);
            }
        }
        fill(model, type, templateId, values);
        return "profiles/_fields :: fields";
    }

    @PostMapping
    public String save(@RequestParam(required = false) Long id, @RequestParam String name, @RequestParam ProfileType type,
                       @RequestParam(required = false) Long templateId, @RequestParam Map<String, String> all,
                       @RequestParam(defaultValue = "30") int gracefulStopSec,
                       @RequestParam(defaultValue = "30") int gracefulRampDownSec,
                       @RequestParam(defaultValue = "false") boolean template, Model model, RedirectAttributes ra) {
        Map<String, String> params = new LinkedHashMap<>();
        all.forEach((k, v) -> {
            if (k.startsWith("p.")) {
                params.put(k.substring(2), v);
            }
        });
        try {
            TestProfile saved = service.save(new ProfileService.ProfileForm(id, name, type, templateId, params,
                    gracefulStopSec, gracefulRampDownSec, template));
            ra.addFlashAttribute("info", "Профиль сохранён: " + service.steps(saved).size() + " ступеней");
            return "redirect:/profiles";
        } catch (IllegalArgumentException | IllegalStateException e) {
            model.addAttribute("error", e.getMessage());
            TestProfile existing = id == null ? null : service.get(id);
            model.addAttribute("p", existing);
            model.addAttribute("type", type);
            model.addAttribute("formName", name);
            model.addAttribute("formGracefulStop", gracefulStopSec);
            model.addAttribute("formGracefulRampDown", gracefulRampDownSec);
            fill(model, type, templateId, params);
            return "profiles/form";
        }
    }

    /** Ступени профиля (htmx-фрагмент для списка). */
    @GetMapping("/{id}/steps")
    public String steps(@PathVariable Long id, Model model) {
        try {
            model.addAttribute("steps", service.steps(service.get(id)));
        } catch (RuntimeException e) {
            model.addAttribute("error", e.getMessage());
        }
        return "profiles/_steps :: steps";
    }

    @PostMapping("/{id}/clone")
    public String clone(@PathVariable Long id, RedirectAttributes ra) {
        try {
            service.clone(id, null);
        } catch (IllegalArgumentException e) {
            ra.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/profiles";
    }

    @PostMapping("/{id}/delete")
    public String delete(@PathVariable Long id, RedirectAttributes ra) {
        try {
            service.delete(id);
        } catch (IllegalStateException e) {
            ra.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/profiles";
    }

    private void fill(Model model, ProfileType type, Long templateId, Map<String, String> values) {
        JsonNode def = templateId == null ? null : service.template(templateId).getDefinition();
        List<ProfileSchemas.Param> schema = ProfileSchemas.forType(type, def);
        model.addAttribute("types", ProfileType.values());
        model.addAttribute("templates", service.templates());
        model.addAttribute("templateId", templateId);
        model.addAttribute("schema", schema);
        model.addAttribute("values", values);
        model.addAttribute("typeSelected", type);
    }
}
