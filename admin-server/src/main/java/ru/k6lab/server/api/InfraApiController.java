package ru.k6lab.server.api;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import ru.k6lab.server.config.AppProperties;
import ru.k6lab.server.deploy.DeployService;
import ru.k6lab.server.deploy.Deployment;
import ru.k6lab.server.deploy.DeploymentRepository;
import ru.k6lab.server.distribution.DistributionArtifact;
import ru.k6lab.server.distribution.DistributionRepository;
import ru.k6lab.server.generator.Generator;
import ru.k6lab.server.generator.GeneratorRepository;
import ru.k6lab.server.generator.GeneratorService;
import ru.k6lab.server.generator.GeneratorView;
import ru.k6lab.server.generator.GeneratorViewService;
import ru.k6lab.server.ssh.SshCredential;
import ru.k6lab.server.ssh.SshCredentialService;
import ru.k6lab.server.tuning.HostTuningService;
import ru.k6lab.server.tuning.HostTuningStore;
import ru.k6lab.server.tuning.TuningAnalyzer;
import ru.k6lab.server.tuning.TuningProfileStore;

import java.security.Principal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

/** REST API генераторов нагрузки, журнала операций деплоя и тюнинга Linux-хостов. */
@RestController
@RequestMapping("/api/v1")
public class InfraApiController {

    private final GeneratorViewService generatorViews;
    private final GeneratorService generatorService;
    private final GeneratorRepository generatorRepo;
    private final DeployService deploy;
    private final DeploymentRepository deployments;
    private final SshCredentialService sshCreds;
    private final DistributionRepository distributions;
    private final AppProperties props;
    private final HostTuningService tuningService;
    private final HostTuningStore tuningStore;
    private final TuningProfileStore tuningProfiles;

    public InfraApiController(GeneratorViewService generatorViews, GeneratorService generatorService,
                              GeneratorRepository generatorRepo, DeployService deploy,
                              DeploymentRepository deployments, SshCredentialService sshCreds,
                              DistributionRepository distributions, AppProperties props,
                              HostTuningService tuningService, HostTuningStore tuningStore,
                              TuningProfileStore tuningProfiles) {
        this.generatorViews = generatorViews;
        this.generatorService = generatorService;
        this.generatorRepo = generatorRepo;
        this.deploy = deploy;
        this.deployments = deployments;
        this.sshCreds = sshCreds;
        this.distributions = distributions;
        this.props = props;
        this.tuningService = tuningService;
        this.tuningStore = tuningStore;
        this.tuningProfiles = tuningProfiles;
    }

    public record QuickAddPayload(String name, String host, Integer port, String username, String authType,
                                  String password, String privateKey, String passphrase,
                                  String sudoPassword, String serverUrl, Boolean tuneOs) {
    }

    public record AdvancedAddPayload(String name, String host, Integer sshPort, Long credentialId,
                                     String mode, String dir, String serverUrl, Boolean tuneOs,
                                     Integer maxConcurrentRuns, Long agentArtifactId, Long k6ArtifactId) {
    }

    public record BulkAddPayload(String hosts, Long credentialId, String mode, String serverUrl, Boolean tuneOs) {
    }

    public record UpdateGenPayload(Long agentArtifactId, Long k6ArtifactId) {
    }

    public record TuningActionPayload(List<Long> generatorIds, Integer seconds, Long sourceId) {
    }

    public record OptProfilePayload(String name, String description, Long fromHost, Map<String, String> settings) {
    }

    // ==================== Generators ====================

    @GetMapping("/generators")
    public Map<String, Object> listGenerators(HttpServletRequest req) {
        List<GeneratorView> all = generatorViews.all();
        long outdated = all.stream().filter(GeneratorView::hasDrift).count();
        String defUrl = props.getPublicUrl() != null && !props.getPublicUrl().isBlank()
                ? props.getPublicUrl()
                : "ws://" + req.getServerName() + ":" + props.getAgentPort() + "/agent/ws";

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("items", all.stream().map(DashboardApiController::toGenDto).toList());
        out.put("outdatedCount", outdated);
        out.put("defaultServerUrl", defUrl);
        out.put("sshCredentials", sshCreds.list().stream().map(c -> Map.of(
                "id", c.getId(),
                "name", c.getName(),
                "username", c.getUsername(),
                "authType", c.getAuthType().name()
        )).toList());
        out.put("agents", distributions.findByKindOrderByCreatedAtDesc(DistributionArtifact.Kind.AGENT).stream()
                .map(AdminApiController::toDistDto).toList());
        out.put("k6s", distributions.findByKindOrderByCreatedAtDesc(DistributionArtifact.Kind.K6).stream()
                .map(AdminApiController::toDistDto).toList());
        return out;
    }

    @GetMapping("/generators/{id}")
    public Map<String, Object> generatorDetail(@PathVariable Long id) {
        GeneratorView v = generatorViews.one(id);
        Generator g = v.g();
        Map<String, Object> dto = DashboardApiController.toGenDto(v);
        dto.put("installMode", g.getInstallMode() != null ? g.getInstallMode().name() : "SYSTEM");
        dto.put("installDir", g.getInstallDir());
        dto.put("supervisor", g.getSupervisor() != null ? g.getSupervisor().name() : null);
        dto.put("osName", g.getOsName());
        dto.put("arch", g.getArch());
        dto.put("serverUrl", g.getServerUrl());

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("generator", dto);
        out.put("history", deployments.findTop50ByGeneratorIdOrderByStartedAtDesc(id).stream()
                .limit(20)
                .map(DashboardApiController::toDeploymentDto)
                .toList());
        out.put("agents", distributions.findByKindOrderByCreatedAtDesc(DistributionArtifact.Kind.AGENT).stream()
                .map(AdminApiController::toDistDto).toList());
        out.put("k6s", distributions.findByKindOrderByCreatedAtDesc(DistributionArtifact.Kind.K6).stream()
                .map(AdminApiController::toDistDto).toList());
        return out;
    }

    @GetMapping("/generators/{id}/agent-log")
    @PreAuthorize("hasRole('ADMIN')")
    public Map<String, Object> agentLog(@PathVariable Long id, @RequestParam(defaultValue = "200") int lines) {
        return Map.of("log", deploy.tailAgentLog(id, Math.min(1000, Math.max(20, lines))));
    }

    @PostMapping("/generators/quick-add")
    @PreAuthorize("hasRole('ADMIN')")
    public Map<String, Object> quickAdd(@RequestBody QuickAddPayload req) {
        String host = req.host() == null ? "" : req.host().trim();
        if (host.isEmpty()) throw new IllegalArgumentException("Укажите адрес хоста");
        int port = req.port() != null ? req.port() : 22;
        SshCredential.AuthType at = "KEY".equalsIgnoreCase(req.authType())
                ? SshCredential.AuthType.KEY
                : SshCredential.AuthType.PASSWORD;
        String secret = at == SshCredential.AuthType.KEY ? req.privateKey() : req.password();
        String credName = "auto-" + host + "-" + System.currentTimeMillis() % 100000;
        SshCredential cred = sshCreds.save(new SshCredentialService.Form(
                null, credName, req.username(), at, secret, req.passphrase(), req.sudoPassword(),
                null, null, null, false, false
        ));
        try {
            String gName = req.name() != null && !req.name().isBlank() ? req.name().trim() : host;
            Deployment d = deploy.startInstall(new DeployService.InstallRequest(
                    gName, host, port, cred.getId(), Generator.InstallMode.SYSTEM, null,
                    req.serverUrl(), Boolean.TRUE.equals(req.tuneOs()), 1, null, null
            ));
            return Map.of("ok", true, "deploymentId", d.getId(), "generatorId", d.getGeneratorId() != null ? d.getGeneratorId() : 0L);
        } catch (RuntimeException e) {
            try {
                sshCreds.delete(cred.getId());
            } catch (RuntimeException ignored) {
            }
            throw e;
        }
    }

    @PostMapping("/generators")
    @PreAuthorize("hasRole('ADMIN')")
    public Map<String, Object> addAdvanced(@RequestBody AdvancedAddPayload req) {
        Generator.InstallMode mode = "USER".equalsIgnoreCase(req.mode())
                ? Generator.InstallMode.USER
                : Generator.InstallMode.SYSTEM;
        Deployment d = deploy.startInstall(new DeployService.InstallRequest(
                req.name(), req.host(), req.sshPort() != null ? req.sshPort() : 22,
                req.credentialId(), mode, req.dir(), req.serverUrl(),
                Boolean.TRUE.equals(req.tuneOs()),
                req.maxConcurrentRuns() != null ? req.maxConcurrentRuns() : 1,
                req.agentArtifactId(), req.k6ArtifactId()
        ));
        return Map.of("ok", true, "deploymentId", d.getId());
    }

    @PostMapping("/generators/bulk")
    @PreAuthorize("hasRole('ADMIN')")
    public Map<String, Object> addBulk(@RequestBody BulkAddPayload req) {
        Generator.InstallMode mode = "USER".equalsIgnoreCase(req.mode())
                ? Generator.InstallMode.USER
                : Generator.InstallMode.SYSTEM;
        List<Long> depIds = new ArrayList<>();
        for (String line : (req.hosts() == null ? "" : req.hosts()).split("\\r?\\n")) {
            String t = line.trim();
            if (t.isEmpty() || t.startsWith("#")) continue;
            String host = t;
            int port = 22;
            int colon = t.lastIndexOf(':');
            if (colon > 0) {
                try {
                    port = Integer.parseInt(t.substring(colon + 1));
                    host = t.substring(0, colon);
                } catch (NumberFormatException ignored) {
                }
            }
            Deployment d = deploy.startInstall(new DeployService.InstallRequest(
                    host, host, port, req.credentialId(), mode, null,
                    req.serverUrl(), Boolean.TRUE.equals(req.tuneOs()), 1, null, null
            ));
            depIds.add(d.getId());
        }
        return Map.of("ok", true, "deploymentIds", depIds);
    }

    @PostMapping("/generators/{id}/maintenance")
    @PreAuthorize("hasRole('ADMIN')")
    public Map<String, Object> maintenance(@PathVariable Long id, @RequestBody Map<String, Boolean> body) {
        generatorService.setMaintenance(id, Boolean.TRUE.equals(body.get("on")));
        return Map.of("ok", true);
    }

    @PostMapping("/generators/{id}/settings")
    @PreAuthorize("hasRole('ADMIN')")
    public Map<String, Object> updateSettings(@PathVariable Long id, @RequestBody Map<String, Integer> body) {
        int maxRuns = body.getOrDefault("maxConcurrentRuns", 1);
        generatorService.update(id, maxRuns);
        return Map.of("ok", true);
    }

    @PostMapping("/generators/{id}/restart")
    @PreAuthorize("hasRole('ADMIN')")
    public Map<String, Object> restart(@PathVariable Long id) {
        Deployment d = deploy.startRestart(id);
        return Map.of("ok", true, "deploymentId", d.getId());
    }

    @PostMapping("/generators/{id}/update")
    @PreAuthorize("hasRole('ADMIN')")
    public Map<String, Object> update(@PathVariable Long id, @RequestBody UpdateGenPayload req) {
        Deployment d = deploy.startUpdate(id, req.agentArtifactId(), req.k6ArtifactId());
        return Map.of("ok", true, "deploymentId", d.getId());
    }

    @PostMapping("/generators/{id}/redeploy")
    @PreAuthorize("hasRole('ADMIN')")
    public Map<String, Object> redeploy(@PathVariable Long id, @RequestParam(defaultValue = "false") boolean keepMode) {
        Deployment d = deploy.startRedeploy(id, keepMode);
        return Map.of("ok", true, "deploymentId", d.getId());
    }

    @PostMapping("/generators/{id}/uninstall")
    @PreAuthorize("hasRole('ADMIN')")
    public Map<String, Object> uninstall(@PathVariable Long id) {
        Deployment d = deploy.startUninstall(id);
        return Map.of("ok", true, "deploymentId", d.getId());
    }

    @PostMapping("/generators/update-all")
    @PreAuthorize("hasRole('ADMIN')")
    public Map<String, Object> updateAll(@RequestParam(defaultValue = "false") boolean force) {
        List<Long> started = new ArrayList<>();
        for (GeneratorView v : generatorViews.all()) {
            if (v.g().isBusy()) continue;
            if (force || v.hasDrift()) {
                try {
                    started.add(deploy.startUpdate(v.g().getId(), null, null).getId());
                } catch (RuntimeException ignored) {
                }
            }
        }
        return Map.of("ok", true, "count", started.size(), "deploymentIds", started);
    }

    // ==================== Deployments ====================

    @GetMapping("/deployments")
    public Map<String, Object> listDeployments() {
        return Map.of("items", deployments.findTop50ByOrderByStartedAtDesc().stream()
                .map(DashboardApiController::toDeploymentDto)
                .toList());
    }

    @GetMapping("/deployments/{id}")
    public Map<String, Object> deploymentDetail(@PathVariable Long id) {
        Deployment d = deployments.findById(id).orElseThrow(() -> new NoSuchElementException("Операция не найдена"));
        Map<String, Object> m = DashboardApiController.toDeploymentDto(d);
        m.put("steps", d.getSteps());
        m.put("log", d.getLog());
        m.put("final", d.getStatus().isFinal());
        m.put("hostKeyType", d.getHostKeyType());
        m.put("hostKeyFingerprint", d.getHostKeyFingerprint());
        m.put("hostKeyHost", d.getHostKeyHost());
        m.put("hostKeyPort", d.getHostKeyPort());
        return m;
    }

    @PostMapping("/deployments/{id}/confirm-host-key")
    @PreAuthorize("hasRole('ADMIN')")
    public Map<String, Object> confirmDeploymentHostKey(@PathVariable Long id, Authentication auth) {
        deploy.confirmHostKey(id, auth != null ? auth.getName() : "admin");
        return Map.of("ok", true);
    }

    // ==================== Linux Host Tuning ====================

    @GetMapping("/tuning")
    @PreAuthorize("hasRole('ADMIN')")
    public Map<String, Object> tuningIndex() {
        List<Generator> gens = generatorRepo.findAllByOrderByNameAsc();
        Map<Long, HostTuningStore.Profile> profByGen = new HashMap<>();
        for (var p : tuningStore.latestProfiles()) {
            profByGen.putIfAbsent(p.generatorId(), p);
        }
        Map<Long, HostTuningStore.Tuning> tunByGen = new HashMap<>();
        for (var t : tuningStore.latestTunings()) {
            tunByGen.putIfAbsent(t.generatorId(), t);
        }

        List<Map<String, Object>> rows = gens.stream().map(g -> {
            var p = profByGen.get(g.getId());
            var t = tunByGen.get(g.getId());
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", g.getId());
            m.put("name", g.getName());
            m.put("host", g.getHost());
            m.put("status", g.getStatus().name());
            m.put("profile", p);
            m.put("tuning", t);
            m.put("tuned", t != null && "APPLIED".equals(t.status()) && t.rolledBackAt() == null);
            return m;
        }).toList();

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("rows", rows);
        out.put("optProfiles", tuningProfiles.list());
        out.put("history", tuningStore.history(30));
        out.put("allKeys", TuningAnalyzer.allKeys());
        return out;
    }

    @PostMapping("/tuning/profile")
    @PreAuthorize("hasRole('ADMIN')")
    public Map<String, Object> startProfiling(@RequestBody TuningActionPayload req, Principal user) {
        int sec = req.seconds() != null ? req.seconds() : 30;
        List<String> msgs = tuningService.startProfiling(req.generatorIds(), sec, user != null ? user.getName() : "admin");
        return Map.of("ok", true, "messages", msgs);
    }

    @PostMapping("/tuning/apply")
    @PreAuthorize("hasRole('ADMIN')")
    public Map<String, Object> applyTuning(@RequestBody TuningActionPayload req, Principal user) {
        List<String> msgs = tuningService.apply(req.generatorIds(), user != null ? user.getName() : "admin");
        return Map.of("ok", true, "messages", msgs);
    }

    @PostMapping("/tuning/rollout")
    @PreAuthorize("hasRole('ADMIN')")
    public Map<String, Object> rolloutTuning(@RequestBody TuningActionPayload req, Principal user) {
        List<String> msgs = tuningService.rollout(req.sourceId(), req.generatorIds(), user != null ? user.getName() : "admin");
        return Map.of("ok", true, "messages", msgs);
    }

    @PostMapping("/tuning/rollback")
    @PreAuthorize("hasRole('ADMIN')")
    public Map<String, Object> rollbackTuning(@RequestBody TuningActionPayload req, Principal user) {
        List<String> msgs = tuningService.rollback(req.generatorIds(), user != null ? user.getName() : "admin");
        return Map.of("ok", true, "messages", msgs);
    }

    @PostMapping("/tuning/rollback-all")
    @PreAuthorize("hasRole('ADMIN')")
    public Map<String, Object> rollbackAllTuning(Principal user) {
        List<String> msgs = tuningService.rollbackAll(user != null ? user.getName() : "admin");
        return Map.of("ok", true, "messages", msgs);
    }

    @PostMapping("/tuning/profiles")
    @PreAuthorize("hasRole('ADMIN')")
    public Map<String, Object> createOptProfile(@RequestBody OptProfilePayload req, Principal user) {
        Map<String, String> settings = new LinkedHashMap<>();
        if (req.settings() != null && !req.settings().isEmpty()) {
            settings.putAll(req.settings());
        } else if (req.fromHost() != null) {
            var p = tuningStore.latestDone(req.fromHost())
                    .orElseThrow(() -> new IllegalArgumentException("Для выбранного хоста нет завершённого профилирования"));
            JsonNode findings = p.findings();
            if (findings != null && findings.isArray()) {
                for (JsonNode fn : findings) {
                    String key = fn.path("key").asText("");
                    String rec = fn.path("recommended").asText("");
                    if (!key.isEmpty() && !rec.isEmpty() && TuningAnalyzer.valid(key, rec)) {
                        settings.put(key, rec);
                    }
                }
            }
        }
        long id = tuningProfiles.create(req.name(), req.description() != null ? req.description() : "",
                settings, user != null ? user.getName() : "admin");
        return Map.of("ok", true, "id", id);
    }

    @PostMapping("/tuning/profiles/{id}/apply")
    @PreAuthorize("hasRole('ADMIN')")
    public Map<String, Object> applyOptProfile(@PathVariable long id, @RequestBody TuningActionPayload req, Principal user) {
        List<String> msgs = tuningService.applyProfile(id, req.generatorIds(), user != null ? user.getName() : "admin");
        return Map.of("ok", true, "messages", msgs);
    }

    @PostMapping("/tuning/profiles/{id}/delete")
    @PreAuthorize("hasRole('ADMIN')")
    public Map<String, Object> deleteOptProfile(@PathVariable long id) {
        tuningProfiles.delete(id);
        return Map.of("ok", true);
    }
}
