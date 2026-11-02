package ru.k6lab.server.git;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import ru.k6lab.server.ssh.KnownHostService;
import ru.k6lab.server.ssh.SshCredential;
import ru.k6lab.server.ssh.SshCredentialService;
import ru.k6lab.server.ssh.SshExceptions;

import java.io.IOException;

/** Git-репозитории: токены и SSH-ключи - секреты, поэтому управляет ADMIN; USER импортирует из готовых. */
@Controller
@RequestMapping("/git-repos")
public class GitRepoController {

    /** Форма: токен приходит только на запись и не отдаётся обратно. */
    public static class RepoForm {
        private Long id;
        private String name;
        private String url;
        private GitRepo.AuthType authType = GitRepo.AuthType.NONE;
        private String token;
        private Long sshCredentialId;
        private String subdir = "";
        private boolean clearToken;
        private boolean hasToken;

        public Long getId() { return id; }
        public void setId(Long id) { this.id = id; }
        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public String getUrl() { return url; }
        public void setUrl(String url) { this.url = url; }
        public GitRepo.AuthType getAuthType() { return authType; }
        public void setAuthType(GitRepo.AuthType authType) { this.authType = authType; }
        public String getToken() { return token; }
        public void setToken(String token) { this.token = token; }
        public Long getSshCredentialId() { return sshCredentialId; }
        public void setSshCredentialId(Long sshCredentialId) { this.sshCredentialId = sshCredentialId; }
        public String getSubdir() { return subdir; }
        public void setSubdir(String subdir) { this.subdir = subdir; }
        public boolean isClearToken() { return clearToken; }
        public void setClearToken(boolean clearToken) { this.clearToken = clearToken; }
        public boolean isHasToken() { return hasToken; }
    }

    private final GitRepoService service;
    private final SshCredentialService credentials;
    private final KnownHostService knownHosts;

    public GitRepoController(GitRepoService service, SshCredentialService credentials, KnownHostService knownHosts) {
        this.service = service;
        this.credentials = credentials;
        this.knownHosts = knownHosts;
    }

    @GetMapping
    public String list(Model model) {
        model.addAttribute("repos", service.list());
        return "git/list";
    }

    @GetMapping("/new")
    @PreAuthorize("hasRole('ADMIN')")
    public String create(Model model) {
        fill(model, new RepoForm());
        return "git/form";
    }

    @GetMapping("/{id}/edit")
    @PreAuthorize("hasRole('ADMIN')")
    public String edit(@PathVariable Long id, Model model) {
        GitRepo r = service.get(id);
        RepoForm f = new RepoForm();
        f.id = r.getId();
        f.name = r.getName();
        f.url = r.getUrl();
        f.authType = r.getAuthType();
        f.sshCredentialId = r.getSshCredentialId();
        f.subdir = r.getSubdir();
        f.hasToken = r.getTokenEnc() != null;
        fill(model, f);
        return "git/form";
    }

    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    public String save(@ModelAttribute("form") RepoForm f, Model model, RedirectAttributes ra) {
        try {
            if (f.name == null || f.name.isBlank()) {
                throw new IllegalArgumentException("Укажите имя репозитория");
            }
            service.save(new GitRepoService.Form(f.id, f.name, f.url, f.authType, f.token, f.sshCredentialId, f.subdir, f.clearToken));
            ra.addFlashAttribute("info", "Репозиторий сохранён");
            return "redirect:/git-repos";
        } catch (IllegalArgumentException e) {
            f.token = null;
            model.addAttribute("error", e.getMessage());
            fill(model, f);
            return "git/form";
        }
    }

    @PostMapping("/{id}/delete")
    @PreAuthorize("hasRole('ADMIN')")
    public String delete(@PathVariable Long id) {
        service.delete(id);
        return "redirect:/git-repos";
    }

    /** Проверка доступа: clone/fetch и список веток и тегов. Неизвестный ключ SSH-хоста требует подтверждения. */
    @PostMapping("/{id}/refresh")
    public String refresh(@PathVariable Long id, Model model) {
        model.addAttribute("repo", service.get(id));
        try {
            model.addAttribute("refs", service.refresh(id));
        } catch (GitClient.GitHostKeyException e) {
            model.addAttribute("error", e.getMessage());
            if (e.failure() instanceof SshExceptions.HostKeyUnconfirmed u) {
                model.addAttribute("hostKey", u);
            }
        } catch (IOException | IllegalStateException | IllegalArgumentException e) {
            model.addAttribute("error", e.getMessage());
        }
        return "git/refs :: refs";
    }

    @PostMapping("/confirm-host-key")
    @PreAuthorize("hasRole('ADMIN')")
    public String confirmHostKey(@RequestParam String host, @RequestParam int port, @RequestParam String keyType,
                                 @RequestParam String fingerprint, @RequestParam String publicKey,
                                 @RequestParam Long repoId, Authentication auth, RedirectAttributes ra) {
        try {
            knownHosts.confirm(host, port, keyType, fingerprint, publicKey, auth.getName());
            ra.addFlashAttribute("info", "Ключ хоста " + host + " подтверждён. Нажмите «Проверить доступ» ещё раз.");
        } catch (IllegalStateException e) {
            ra.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/git-repos";
    }

    private void fill(Model model, RepoForm f) {
        model.addAttribute("form", f);
        model.addAttribute("sshCredentials", credentials.list().stream()
                .filter(c -> c.getAuthType() == SshCredential.AuthType.KEY).toList());
    }
}
