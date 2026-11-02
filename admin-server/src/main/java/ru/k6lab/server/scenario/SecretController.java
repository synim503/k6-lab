package ru.k6lab.server.scenario;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
@RequestMapping("/secrets")
public class SecretController {

    private final SecretService service;

    public SecretController(SecretService service) {
        this.service = service;
    }

    @GetMapping
    public String list(Model model) {
        model.addAttribute("items", service.list());
        return "secrets/list";
    }

    @PostMapping
    public String save(@RequestParam String name, @RequestParam(defaultValue = "") String description,
                       @RequestParam(defaultValue = "") String value, RedirectAttributes ra) {
        try {
            service.save(name, description, value);
            ra.addFlashAttribute("info", "Секрет сохранён. Ссылка в env: ${secret:" + name.trim() + "}");
        } catch (IllegalArgumentException e) {
            ra.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/secrets";
    }

    @PostMapping("/{id}/delete")
    public String delete(@PathVariable Long id) {
        service.delete(id);
        return "redirect:/secrets";
    }
}
