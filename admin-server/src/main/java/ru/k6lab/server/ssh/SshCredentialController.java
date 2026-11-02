package ru.k6lab.server.ssh;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
@RequestMapping("/ssh-credentials")
@PreAuthorize("hasRole('ADMIN')")
public class SshCredentialController {

    /** Форма: секреты приходят только на запись и никогда не отдаются обратно в UI. */
    public static class CredForm {
        private Long id;
        private String name;
        private String username;
        private SshCredential.AuthType authType = SshCredential.AuthType.KEY;
        private String secret;
        private String passphrase;
        private String sudoPassword;
        private String jumpHost;
        private Integer jumpPort;
        private Long jumpCredentialId;
        private boolean clearPassphrase;
        private boolean clearSudoPassword;
        private boolean hasSecret;
        private boolean hasPassphrase;
        private boolean hasSudoPassword;

        public Long getId() { return id; }
        public void setId(Long id) { this.id = id; }
        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public String getUsername() { return username; }
        public void setUsername(String username) { this.username = username; }
        public SshCredential.AuthType getAuthType() { return authType; }
        public void setAuthType(SshCredential.AuthType authType) { this.authType = authType; }
        public String getSecret() { return secret; }
        public void setSecret(String secret) { this.secret = secret; }
        public String getPassphrase() { return passphrase; }
        public void setPassphrase(String passphrase) { this.passphrase = passphrase; }
        public String getSudoPassword() { return sudoPassword; }
        public void setSudoPassword(String sudoPassword) { this.sudoPassword = sudoPassword; }
        public String getJumpHost() { return jumpHost; }
        public void setJumpHost(String jumpHost) { this.jumpHost = jumpHost; }
        public Integer getJumpPort() { return jumpPort; }
        public void setJumpPort(Integer jumpPort) { this.jumpPort = jumpPort; }
        public Long getJumpCredentialId() { return jumpCredentialId; }
        public void setJumpCredentialId(Long jumpCredentialId) { this.jumpCredentialId = jumpCredentialId; }
        public boolean isClearPassphrase() { return clearPassphrase; }
        public void setClearPassphrase(boolean clearPassphrase) { this.clearPassphrase = clearPassphrase; }
        public boolean isClearSudoPassword() { return clearSudoPassword; }
        public void setClearSudoPassword(boolean clearSudoPassword) { this.clearSudoPassword = clearSudoPassword; }
        public boolean isHasSecret() { return hasSecret; }
        public boolean isHasPassphrase() { return hasPassphrase; }
        public boolean isHasSudoPassword() { return hasSudoPassword; }
    }

    private final SshCredentialService service;
    private final KnownHostService knownHosts;

    public SshCredentialController(SshCredentialService service, KnownHostService knownHosts) {
        this.service = service;
        this.knownHosts = knownHosts;
    }

    @GetMapping
    public String list(Model model) {
        model.addAttribute("items", service.list());
        model.addAttribute("knownHosts", knownHosts.list());
        return "ssh/list";
    }

    @GetMapping("/new")
    public String create(Model model) {
        fill(model, new CredForm());
        return "ssh/form";
    }

    @GetMapping("/{id}/edit")
    public String edit(@PathVariable Long id, Model model) {
        SshCredential c = service.get(id);
        CredForm f = new CredForm();
        f.id = c.getId();
        f.name = c.getName();
        f.username = c.getUsername();
        f.authType = c.getAuthType();
        f.jumpHost = c.getJumpHost();
        f.jumpPort = c.getJumpPort();
        f.jumpCredentialId = c.getJumpCredentialId();
        f.hasSecret = c.getSecretEnc() != null;
        f.hasPassphrase = c.getPassphraseEnc() != null;
        f.hasSudoPassword = c.getSudoPasswordEnc() != null;
        fill(model, f);
        return "ssh/form";
    }

    @PostMapping
    public String save(@ModelAttribute("form") CredForm f, Model model, RedirectAttributes ra) {
        try {
            if (f.name == null || f.name.isBlank() || f.username == null || f.username.isBlank()) {
                throw new IllegalArgumentException("Укажите имя учётки и пользователя");
            }
            service.save(new SshCredentialService.Form(f.id, f.name, f.username, f.authType, f.secret, f.passphrase,
                    f.sudoPassword, f.jumpHost, f.jumpPort, f.jumpCredentialId, f.clearPassphrase, f.clearSudoPassword));
            ra.addFlashAttribute("info", "Учётка сохранена");
            return "redirect:/ssh-credentials";
        } catch (IllegalArgumentException e) {
            model.addAttribute("error", e.getMessage());
            f.secret = null;
            f.passphrase = null;
            f.sudoPassword = null;
            fill(model, f);
            return "ssh/form";
        }
    }

    @PostMapping("/{id}/delete")
    public String delete(@PathVariable Long id, RedirectAttributes ra) {
        try {
            service.delete(id);
        } catch (IllegalStateException e) {
            ra.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/ssh-credentials";
    }

    @PostMapping("/known-hosts/{id}/delete")
    public String deleteKnownHost(@PathVariable Long id) {
        knownHosts.delete(id);
        return "redirect:/ssh-credentials";
    }

    private void fill(Model model, CredForm f) {
        model.addAttribute("form", f);
        model.addAttribute("others", service.list().stream().filter(c -> !c.getId().equals(f.id)).toList());
    }
}
