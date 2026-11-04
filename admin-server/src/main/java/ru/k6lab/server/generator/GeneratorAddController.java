package ru.k6lab.server.generator;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import ru.k6lab.server.config.AppProperties;
import ru.k6lab.server.deploy.DeployService;
import ru.k6lab.server.ssh.SshCredential;
import ru.k6lab.server.ssh.SshCredentialService;

import java.util.UUID;

/** Добавление сервера-генератора одной формой: SSH-учётка и установка агента создаются за ней. */
@Controller
@RequestMapping("/generators/add")
@PreAuthorize("hasRole('ADMIN')")
public class GeneratorAddController {

    private final SshCredentialService credentials;
    private final DeployService deploy;
    private final AppProperties props;

    public GeneratorAddController(SshCredentialService credentials, DeployService deploy, AppProperties props) {
        this.credentials = credentials;
        this.deploy = deploy;
        this.props = props;
    }

    @GetMapping
    public String form(Model model, HttpServletRequest request) {
        model.addAttribute("serverUrl", "ws://" + request.getServerName() + ":" + props.getAgentPort() + "/agent/ws");
        return "generators/add";
    }

    @PostMapping
    public String create(@RequestParam String host, @RequestParam(defaultValue = "22") int port, @RequestParam String username,
                         @RequestParam(defaultValue = "PASSWORD") String authType,
                         @RequestParam(required = false) String password, @RequestParam(required = false) String privateKey,
                         @RequestParam(required = false) String passphrase, @RequestParam(required = false) String sudoPassword,
                         @RequestParam String serverUrl, Model model) {
        try {
            String h = host.trim();
            String user = username.trim();
            boolean key = "KEY".equalsIgnoreCase(authType);
            String secret = key ? privateKey : password;
            if (secret == null || secret.isBlank()) {
                throw new IllegalArgumentException(key ? "Вставьте приватный ключ SSH" : "Введите пароль");
            }
            // пароль SSH используется и для sudo, если вход не под root; при входе по ключу sudo-пароль задаётся отдельно
            // (пусто - sudo без пароля, NOPASSWD)
            String sudo = "root".equals(user) ? null : key ? (sudoPassword == null || sudoPassword.isBlank() ? null : sudoPassword) : password;
            var cred = credentials.save(new SshCredentialService.Form(null, user + "@" + h + "-" + UUID.randomUUID().toString().substring(0, 4),
                    user, key ? SshCredential.AuthType.KEY : SshCredential.AuthType.PASSWORD, secret,
                    key && passphrase != null && !passphrase.isBlank() ? passphrase : null, sudo, null, null, null, false, false));
            String name = h.replaceAll("[^A-Za-z0-9_.-]", "_");
            // агент всегда ставится системным сервисом systemd (systemctl): пользователь k6agent, /opt/k6-agent, автозапуск и
            // перезапуск при сбое. Нужен root или sudo (в том числе без пароля); без sudo - «Расширенно» (пользовательский режим)
            var d = deploy.startInstall(new DeployService.InstallRequest(name, h, port, cred.getId(), Generator.InstallMode.SYSTEM,
                    null, serverUrl.trim(), false, 1, null, null));
            // при первом подключении потребуется подтвердить отпечаток ключа - это на странице операции
            return "redirect:/deployments/" + d.getId();
        } catch (IllegalArgumentException | IllegalStateException e) {
            model.addAttribute("error", e.getMessage());
            model.addAttribute("serverUrl", serverUrl);
            model.addAttribute("host", host);
            model.addAttribute("port", port);
            model.addAttribute("username", username);
            model.addAttribute("authType", authType);
            return "generators/add";
        }
    }
}
