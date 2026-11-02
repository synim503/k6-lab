package ru.k6lab.server.user;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
@RequestMapping("/users")
@PreAuthorize("hasRole('ADMIN')")
public class UserController {

    private final UserService service;
    private final UserRepository repo;

    public UserController(UserService service, UserRepository repo) {
        this.service = service;
        this.repo = repo;
    }

    @GetMapping
    public String list(Model model) {
        model.addAttribute("users", repo.findAll());
        model.addAttribute("roles", Role.values());
        return "users/list";
    }

    @PostMapping
    public String create(@RequestParam String username, @RequestParam String password, @RequestParam Role role,
                         RedirectAttributes ra) {
        return handle(ra, () -> service.create(username, password, role));
    }

    @PostMapping("/{id}")
    public String update(@PathVariable Long id, @RequestParam Role role,
                         @RequestParam(defaultValue = "false") boolean enabled, RedirectAttributes ra) {
        return handle(ra, () -> service.update(id, role, enabled));
    }

    @PostMapping("/{id}/password")
    public String password(@PathVariable Long id, @RequestParam String password, RedirectAttributes ra) {
        return handle(ra, () -> service.changePassword(id, password));
    }

    @PostMapping("/{id}/delete")
    public String delete(@PathVariable Long id, Authentication auth, RedirectAttributes ra) {
        return handle(ra, () -> service.delete(id, auth.getName()));
    }

    private String handle(RedirectAttributes ra, Runnable action) {
        try {
            action.run();
        } catch (IllegalArgumentException | IllegalStateException e) {
            ra.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/users";
    }
}
