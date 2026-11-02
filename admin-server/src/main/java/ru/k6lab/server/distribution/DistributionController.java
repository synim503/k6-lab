package ru.k6lab.server.distribution;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.io.IOException;
import java.io.InputStream;

@Controller
@RequestMapping("/distributions")
@PreAuthorize("hasRole('ADMIN')")
public class DistributionController {

    private final DistributionService service;

    public DistributionController(DistributionService service) {
        this.service = service;
    }

    @GetMapping
    public String list(Model model) {
        model.addAttribute("items", service.list());
        model.addAttribute("kinds", DistributionArtifact.Kind.values());
        model.addAttribute("arches", DistributionArtifact.Arch.values());
        return "distributions/list";
    }

    @PostMapping
    public String upload(@RequestParam DistributionArtifact.Kind kind, @RequestParam(defaultValue = "") String label,
                         @RequestParam String version, @RequestParam DistributionArtifact.Arch arch,
                         @RequestParam(defaultValue = "false") boolean makeDefault,
                         @RequestParam("file") MultipartFile file, RedirectAttributes ra) {
        try {
            if (file.isEmpty()) {
                throw new IllegalArgumentException("Выберите файл");
            }
            try (InputStream in = file.getInputStream()) {
                DistributionArtifact a = service.upload(kind, label, version, arch,
                        file.getOriginalFilename() == null ? "file" : file.getOriginalFilename(), in, makeDefault);
                ra.addFlashAttribute("info", "Загружено: " + a.getFilename() + ", sha256 " + a.getSha256());
            }
        } catch (IllegalArgumentException | IOException e) {
            ra.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/distributions";
    }

    @PostMapping("/{id}/default")
    public String makeDefault(@PathVariable Long id, RedirectAttributes ra) {
        try {
            service.setDefault(id);
        } catch (IllegalArgumentException e) {
            ra.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/distributions";
    }

    @PostMapping("/{id}/delete")
    public String delete(@PathVariable Long id, RedirectAttributes ra) {
        try {
            service.delete(id);
        } catch (IOException e) {
            ra.addFlashAttribute("error", "Не удалось удалить файл: " + e.getMessage());
        }
        return "redirect:/distributions";
    }
}
