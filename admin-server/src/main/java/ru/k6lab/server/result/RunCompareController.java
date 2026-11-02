package ru.k6lab.server.result;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
@RequestMapping("/runs/compare")
public class RunCompareController {

    private final RunCompareService compare;

    public RunCompareController(RunCompareService compare) {
        this.compare = compare;
    }

    @GetMapping
    public String compare(@RequestParam long a, @RequestParam long b, Model model, RedirectAttributes ra) {
        try {
            model.addAttribute("c", compare.compare(a, b));
        } catch (IllegalStateException | IllegalArgumentException e) {
            ra.addFlashAttribute("error", e.getMessage());
            return "redirect:/runs";
        }
        model.addAttribute("fmt", new ReportFormat());
        return "runs/compare";
    }
}
