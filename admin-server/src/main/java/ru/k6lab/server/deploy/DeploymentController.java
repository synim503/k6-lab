package ru.k6lab.server.deploy;

import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
@RequestMapping("/deployments")
public class DeploymentController {

    private final DeploymentRepository repo;
    private final DeployService service;

    public DeploymentController(DeploymentRepository repo, DeployService service) {
        this.repo = repo;
        this.service = service;
    }

    @GetMapping
    public String list(Model model) {
        model.addAttribute("items", repo.findTop50ByOrderByStartedAtDesc());
        return "deploy/list";
    }

    @GetMapping("/{id}")
    public String view(@PathVariable Long id, Model model) {
        model.addAttribute("d", repo.findById(id).orElseThrow());
        return "deploy/view";
    }

    /** Блок статуса и шагов; страница перезагружает его по SSE-событию step/done. */
    @GetMapping("/{id}/status")
    public String status(@PathVariable Long id, Model model) {
        model.addAttribute("d", repo.findById(id).orElseThrow());
        return "deploy/view :: status";
    }

    @GetMapping(value = "/{id}/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@PathVariable Long id) {
        return service.stream(id);
    }

    @PostMapping("/{id}/confirm-host-key")
    @PreAuthorize("hasRole('ADMIN')")
    public String confirmHostKey(@PathVariable Long id, Authentication auth, RedirectAttributes ra) {
        try {
            service.confirmHostKey(id, auth.getName());
        } catch (IllegalStateException | IllegalArgumentException e) {
            ra.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/deployments/" + id;
    }
}
