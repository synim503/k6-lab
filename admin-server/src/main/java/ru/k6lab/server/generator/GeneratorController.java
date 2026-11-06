package ru.k6lab.server.generator;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import ru.k6lab.server.config.AppProperties;
import ru.k6lab.server.deploy.DeployService;
import ru.k6lab.server.deploy.Deployment;
import ru.k6lab.server.deploy.DeploymentRepository;
import ru.k6lab.server.distribution.DistributionArtifact;
import ru.k6lab.server.distribution.DistributionRepository;
import ru.k6lab.server.ssh.SshCredentialService;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Controller
@RequestMapping("/generators")
public class GeneratorController {

    private static final Pattern HOST_LINE = Pattern.compile("^([A-Za-z0-9._-]+)(?::(\\d{1,5}))?(?:\\s+([A-Za-z0-9_.-]+))?$");

    private final GeneratorViewService views;
    private final GeneratorService service;
    private final DeployService deploy;
    private final SshCredentialService credentials;
    private final DistributionRepository distributions;
    private final DeploymentRepository deployments;
    private final AppProperties props;

    public GeneratorController(GeneratorViewService views, GeneratorService service, DeployService deploy,
                               SshCredentialService credentials, DistributionRepository distributions,
                               DeploymentRepository deployments, AppProperties props) {
        this.views = views;
        this.service = service;
        this.deploy = deploy;
        this.credentials = credentials;
        this.distributions = distributions;
        this.deployments = deployments;
        this.props = props;
    }

    // ------------------------------------------------------------------ просмотр (все роли)

    @GetMapping
    public String list(Model model) {
        List<GeneratorView> all = views.all();
        model.addAttribute("generators", all);
        model.addAttribute("outdated", all.stream().filter(GeneratorView::hasDrift).count());
        return "generators/list";
    }

    /** htmx-фрагмент: таблица обновляется раз в 5 секунд. */
    @GetMapping("/table")
    public String table(Model model) {
        model.addAttribute("generators", views.all());
        return "generators/list :: table";
    }

    @GetMapping("/{id}")
    public String detail(@PathVariable Long id, Model model) {
        model.addAttribute("v", views.one(id));
        model.addAttribute("history", deployments.findTop50ByGeneratorIdOrderByStartedAtDesc(id));
        model.addAttribute("agents", distributions.findByKindOrderByCreatedAtDesc(DistributionArtifact.Kind.AGENT));
        model.addAttribute("k6s", k6Artifacts());
        return "generators/detail";
    }

    @GetMapping("/{id}/card")
    public String card(@PathVariable Long id, Model model) {
        model.addAttribute("v", views.one(id));
        return "generators/detail :: card";
    }

    // ------------------------------------------------------------------ управление (ADMIN)

    @GetMapping("/new")
    @PreAuthorize("hasRole('ADMIN')")
    public String newForm(Model model, HttpServletRequest request) {
        GeneratorForm f = new GeneratorForm();
        f.setServerUrl(defaultServerUrl(request));
        fillFormModel(model, f);
        return "generators/form";
    }

    @GetMapping("/bulk")
    @PreAuthorize("hasRole('ADMIN')")
    public String bulkForm(Model model, HttpServletRequest request) {
        GeneratorForm f = new GeneratorForm();
        f.setServerUrl(defaultServerUrl(request));
        fillFormModel(model, f);
        return "generators/bulk";
    }

    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    public String create(@Valid @ModelAttribute("form") GeneratorForm form, BindingResult br, Model model,
                         RedirectAttributes ra) {
        if (form.getName() == null || form.getName().isBlank()) {
            br.rejectValue("name", "required", "Укажите имя");
        }
        if (form.getHost() == null || form.getHost().isBlank()) {
            br.rejectValue("host", "required", "Укажите хост");
        }
        if (br.hasErrors()) {
            fillFormModel(model, form);
            return "generators/form";
        }
        try {
            Deployment d = deploy.startInstall(installRequest(form, form.getName(), form.getHost(), form.getSshPort()));
            return "redirect:/deployments/" + d.getId();
        } catch (IllegalArgumentException | IllegalStateException e) {
            br.reject("deploy", e.getMessage());
            fillFormModel(model, form);
            return "generators/form";
        }
    }

    @PostMapping("/bulk")
    @PreAuthorize("hasRole('ADMIN')")
    public String createBulk(@Valid @ModelAttribute("form") GeneratorForm form, BindingResult br, Model model,
                             RedirectAttributes ra) {
        List<DeployService.InstallRequest> requests = new ArrayList<>();
        if (form.getHosts() == null || form.getHosts().isBlank()) {
            br.rejectValue("hosts", "required", "Укажите хотя бы один хост");
        } else {
            int lineNo = 0;
            for (String raw : form.getHosts().split("\\R")) {
                lineNo++;
                String line = raw.strip();
                if (line.isEmpty() || line.startsWith("#")) {
                    continue;
                }
                Matcher m = HOST_LINE.matcher(line);
                if (!m.matches()) {
                    br.rejectValue("hosts", "format", "Строка " + lineNo + ": ожидается «host[:порт] [имя]»");
                    continue;
                }
                String host = m.group(1);
                int port = m.group(2) == null ? form.getSshPort() : Integer.parseInt(m.group(2));
                String name = m.group(3) != null ? m.group(3) : host.replaceAll("[^A-Za-z0-9_.-]", "_");
                requests.add(installRequest(form, name, host, port));
            }
            if (requests.isEmpty() && !br.hasFieldErrors("hosts")) {
                br.rejectValue("hosts", "required", "Укажите хотя бы один хост");
            }
        }
        if (br.hasErrors()) {
            fillFormModel(model, form);
            return "generators/bulk";
        }
        List<String> errors = new ArrayList<>();
        List<Long> started = new ArrayList<>();
        for (DeployService.InstallRequest r : requests) {
            try {
                started.add(deploy.startInstall(r).getId());
            } catch (IllegalArgumentException | IllegalStateException e) {
                errors.add(r.name() + ": " + e.getMessage());
            }
        }
        ra.addFlashAttribute("info", "Запущена установка на " + started.size() + " хост(ах) (параллельно не более "
                + props.getDeploy().getMaxParallel() + ")");
        if (!errors.isEmpty()) {
            ra.addFlashAttribute("error", String.join("; ", errors));
        }
        return "redirect:/deployments";
    }

    @PostMapping("/preflight")
    @PreAuthorize("hasRole('ADMIN')")
    public String preflight(@ModelAttribute("form") GeneratorForm form, Model model) {
        try {
            Deployment d = deploy.startPreflight(new DeployService.PreflightRequest(form.getHost(), form.getSshPort(),
                    form.getCredentialId(), form.getMode(), form.getDir(), form.getServerUrl()));
            return "redirect:/deployments/" + d.getId();
        } catch (IllegalArgumentException | IllegalStateException e) {
            model.addAttribute("error", e.getMessage());
            fillFormModel(model, form);
            return "generators/form";
        }
    }

    @PostMapping("/{id}/maintenance")
    @PreAuthorize("hasRole('ADMIN')")
    public String maintenance(@PathVariable Long id, @RequestParam boolean on, RedirectAttributes ra) {
        service.setMaintenance(id, on);
        return "redirect:/generators/" + id;
    }

    @PostMapping("/{id}/settings")
    @PreAuthorize("hasRole('ADMIN')")
    public String settings(@PathVariable Long id, @RequestParam(defaultValue = "1") int maxConcurrentRuns, RedirectAttributes ra) {
        try {
            service.update(id, maxConcurrentRuns);
            ra.addFlashAttribute("info", "Настройки сохранены");
        } catch (IllegalArgumentException e) {
            ra.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/generators/" + id;
    }

    @PostMapping("/{id}/update")
    @PreAuthorize("hasRole('ADMIN')")
    public String update(@PathVariable Long id, @RequestParam(required = false) Long agentArtifactId,
                         @RequestParam(required = false) Long k6ArtifactId, RedirectAttributes ra) {
        return run(id, ra, () -> deploy.startUpdate(id, agentArtifactId, k6ArtifactId));
    }

    @PostMapping("/{id}/restart")
    @PreAuthorize("hasRole('ADMIN')")
    public String restart(@PathVariable Long id, RedirectAttributes ra) {
        return run(id, ra, () -> deploy.startRestart(id));
    }

    /** Жёсткий редеплой: снести всё, что касается старого агента, и поставить заново системным сервисом systemd. */
    @PostMapping("/{id}/redeploy")
    @PreAuthorize("hasRole('ADMIN')")
    public String redeploy(@PathVariable Long id, @RequestParam(defaultValue = "false") boolean keepMode, RedirectAttributes ra) {
        return run(id, ra, () -> deploy.startRedeploy(id, keepMode));
    }

    @PostMapping("/{id}/uninstall")
    @PreAuthorize("hasRole('ADMIN')")
    public String uninstall(@PathVariable Long id, RedirectAttributes ra) {
        return run(id, ra, () -> deploy.startUninstall(id));
    }

    /** Обновляет агент (и k6) на всех генераторах, версия которых отличается от актуальной; занятые пропускаются. */
    @PostMapping("/update-all")
    @PreAuthorize("hasRole('ADMIN')")
    public String updateAll(@RequestParam(defaultValue = "false") boolean force, RedirectAttributes ra) {
        int started = 0;
        List<String> skipped = new ArrayList<>();
        for (GeneratorView v : views.all()) {
            // force: версии совпадают, но обновление запрошено явно (например, дистрибутив пересобран с той же версией)
            if (!force && !v.hasDrift()) {
                continue;
            }
            try {
                deploy.startUpdate(v.g().getId(), null, null);
                started++;
            } catch (IllegalStateException | IllegalArgumentException e) {
                skipped.add(v.g().getName() + " (" + e.getMessage() + ")");
            }
        }
        if (started == 0 && skipped.isEmpty()) {
            ra.addFlashAttribute("info", "Все генераторы уже на актуальной версии агента");
            return "redirect:/generators";
        }
        ra.addFlashAttribute("info", "Обновление запущено на генераторах: " + started);
        if (!skipped.isEmpty()) {
            ra.addFlashAttribute("error", "Пропущены: " + String.join("; ", skipped));
        }
        return "redirect:/deployments";
    }

    /** Последние N строк журнала агента (htmx-фрагмент). */
    @GetMapping("/{id}/agent-log")
    @PreAuthorize("hasRole('ADMIN')")
    public String agentLog(@PathVariable Long id, @RequestParam(defaultValue = "200") int lines, Model model) {
        try {
            model.addAttribute("text", deploy.tailAgentLog(id, lines));
        } catch (RuntimeException e) {
            model.addAttribute("error", e.getMessage());
        }
        return "generators/detail :: agentLog";
    }

    // ------------------------------------------------------------------ вспомогательное

    private String run(Long id, RedirectAttributes ra, java.util.function.Supplier<Deployment> action) {
        try {
            return "redirect:/deployments/" + action.get().getId();
        } catch (IllegalArgumentException | IllegalStateException e) {
            ra.addFlashAttribute("error", e.getMessage());
            return "redirect:/generators/" + id;
        }
    }

    private DeployService.InstallRequest installRequest(GeneratorForm f, String name, String host, int port) {
        return new DeployService.InstallRequest(name, host, port, f.getCredentialId(),
                f.getMode(), f.getDir(), f.getServerUrl(), f.isTuneOs(), f.getMaxConcurrentRuns(),
                f.getAgentArtifactId(), f.getK6ArtifactId());
    }

    private void fillFormModel(Model model, GeneratorForm form) {
        model.addAttribute("form", form);
        model.addAttribute("credentials", credentials.list());
        model.addAttribute("agents", distributions.findByKindOrderByCreatedAtDesc(DistributionArtifact.Kind.AGENT));
        model.addAttribute("k6s", k6Artifacts());
    }

    private List<DistributionArtifact> k6Artifacts() {
        List<DistributionArtifact> all = new ArrayList<>(distributions.findByKindOrderByCreatedAtDesc(DistributionArtifact.Kind.K6));
        all.addAll(distributions.findByKindOrderByCreatedAtDesc(DistributionArtifact.Kind.XK6));
        return all;
    }

    private String defaultServerUrl(HttpServletRequest request) {
        return "ws://" + request.getServerName() + ":" + props.getAgentPort() + "/agent/ws";
    }
}
