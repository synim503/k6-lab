package ru.k6lab.server.api;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import ru.k6lab.server.agent.AgentSessionRegistry;
import ru.k6lab.server.audit.AuditLog;
import ru.k6lab.server.audit.AuditLogRepository;
import ru.k6lab.server.distribution.DistributionArtifact;
import ru.k6lab.server.distribution.DistributionService;
import ru.k6lab.server.generator.GeneratorRepository;
import ru.k6lab.server.metrics.MetricNamesService;
import ru.k6lab.server.metrics.PrometheusDiagnostics;
import ru.k6lab.server.result.ChartStyle;
import ru.k6lab.server.result.ChartStyleService;
import ru.k6lab.server.secret.Secret;
import ru.k6lab.server.secret.SecretService;
import ru.k6lab.server.settings.SettingsService;
import ru.k6lab.server.ssh.KnownHostService;
import ru.k6lab.server.ssh.SshCredential;
import ru.k6lab.server.ssh.SshCredentialService;
import ru.k6lab.server.user.AppUser;
import ru.k6lab.server.user.Role;
import ru.k6lab.server.user.UserRepository;
import ru.k6lab.server.user.UserService;

import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** REST API секретов, дистрибутивов, SSH-учёток, пользователей, системных настроек и аудита. */
@RestController
@RequestMapping("/api/v1")
public class AdminApiController {

    private final SecretService secrets;
    private final DistributionService distributions;
    private final SshCredentialService sshCreds;
    private final KnownHostService knownHosts;
    private final UserService users;
    private final UserRepository userRepo;
    private final SettingsService settings;
    private final PrometheusDiagnostics diagnostics;
    private final MetricNamesService metricNames;
    private final ChartStyleService chartStyle;
    private final GeneratorRepository generators;
    private final AgentSessionRegistry agentRegistry;
    private final AuditLogRepository auditRepo;

    public AdminApiController(SecretService secrets, DistributionService distributions,
                              SshCredentialService sshCreds, KnownHostService knownHosts,
                              UserService users, UserRepository userRepo,
                              SettingsService settings, PrometheusDiagnostics diagnostics,
                              MetricNamesService metricNames, ChartStyleService chartStyle,
                              GeneratorRepository generators, AgentSessionRegistry agentRegistry,
                              AuditLogRepository auditRepo) {
        this.secrets = secrets;
        this.distributions = distributions;
        this.sshCreds = sshCreds;
        this.knownHosts = knownHosts;
        this.users = users;
        this.userRepo = userRepo;
        this.settings = settings;
        this.diagnostics = diagnostics;
        this.metricNames = metricNames;
        this.chartStyle = chartStyle;
        this.generators = generators;
        this.agentRegistry = agentRegistry;
        this.auditRepo = auditRepo;
    }

    public record SecretPayload(String name, String description, String value) {
    }

    public record SshCredPayload(Long id, String name, String username, String authType,
                                 String secret, String passphrase, String sudoPassword,
                                 String jumpHost, Integer jumpPort, Long jumpCredentialId,
                                 Boolean clearPassphrase, Boolean clearSudoPassword) {
    }

    public record UserCreatePayload(String username, String password, Role role) {
    }

    public record UserUpdatePayload(Role role, boolean enabled) {
    }

    public record PasswordPayload(String password) {
    }

    public record PromSettingsPayload(String rwUrl, String queryUrl, String authType,
                                      String user, String secret, String grafanaUrlTpl,
                                      String histogramMode, Integer pushIntervalSec) {
    }

    public record ChartStylePayload(String theme, int width, int height, String legend,
                                    boolean stats, int maxLegend, boolean absoluteTime,
                                    boolean autoWidth, String sortBy, String sortDir, boolean skipWarmup) {
    }

    public record LimitsPayload(Integer logLinesPerSec, Integer retentionLogDays,
                                Integer retentionMetricsDays, String allowMetrics,
                                Boolean onlyNeededHistograms, Boolean urlNameTag) {
    }

    // ==================== Secrets ====================

    @GetMapping("/secrets")
    public Map<String, Object> listSecrets() {
        List<Map<String, Object>> items = secrets.list().stream().map(AdminApiController::toSecretDto).toList();
        return Map.of("items", items);
    }

    @PostMapping("/secrets")
    public Map<String, Object> saveSecret(@RequestBody SecretPayload req) {
        Secret s = secrets.save(req.name(), req.description(), req.value());
        return Map.of("ok", true, "secret", toSecretDto(s));
    }

    @PostMapping("/secrets/{id}/delete")
    public Map<String, Object> deleteSecret(@PathVariable Long id) {
        secrets.delete(id);
        return Map.of("ok", true);
    }

    private static Map<String, Object> toSecretDto(Secret s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", s.getId());
        m.put("name", s.getName());
        m.put("description", s.getDescription());
        m.put("updatedAt", s.getUpdatedAt());
        return m;
    }

    // ==================== Distributions ====================

    @GetMapping("/distributions")
    @PreAuthorize("hasRole('ADMIN')")
    public Map<String, Object> listDistributions() {
        return Map.of("items", distributions.list().stream().map(AdminApiController::toDistDto).toList());
    }

    @PostMapping("/distributions")
    @PreAuthorize("hasRole('ADMIN')")
    public Map<String, Object> uploadDistribution(@RequestParam DistributionArtifact.Kind kind,
                                                  @RequestParam(defaultValue = "") String label,
                                                  @RequestParam String version,
                                                  @RequestParam DistributionArtifact.Arch arch,
                                                  @RequestParam(defaultValue = "true") boolean makeDefault,
                                                  @RequestParam("file") MultipartFile file) throws IOException {
        try (InputStream in = file.getInputStream()) {
            DistributionArtifact a = distributions.upload(kind, label, version, arch, file.getOriginalFilename(), in, makeDefault);
            return Map.of("ok", true, "artifact", toDistDto(a));
        }
    }

    @PostMapping("/distributions/{id}/default")
    @PreAuthorize("hasRole('ADMIN')")
    public Map<String, Object> setDefaultDistribution(@PathVariable Long id) {
        distributions.setDefault(id);
        return Map.of("ok", true);
    }

    @PostMapping("/distributions/{id}/delete")
    @PreAuthorize("hasRole('ADMIN')")
    public Map<String, Object> deleteDistribution(@PathVariable Long id) throws IOException {
        distributions.delete(id);
        return Map.of("ok", true);
    }

    public static Map<String, Object> toDistDto(DistributionArtifact a) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", a.getId());
        m.put("kind", a.getKind().name());
        m.put("kindTitle", a.getKind().getTitle());
        m.put("label", a.getLabel());
        m.put("version", a.getVersion());
        m.put("arch", a.getArch().name());
        m.put("archTitle", a.getArch().getTitle());
        m.put("filename", a.getFilename());
        m.put("sha256", a.getSha256());
        m.put("sizeBytes", a.getSizeBytes());
        m.put("isDefault", a.isDefault());
        m.put("uploadedBy", a.getUploadedBy());
        m.put("createdAt", a.getCreatedAt());
        return m;
    }

    // ==================== SSH Credentials & Known Hosts ====================

    @GetMapping("/ssh-credentials")
    @PreAuthorize("hasRole('ADMIN')")
    public Map<String, Object> listSsh() {
        List<Map<String, Object>> creds = sshCreds.list().stream().map(c -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", c.getId());
            m.put("name", c.getName());
            m.put("username", c.getUsername());
            m.put("authType", c.getAuthType().name());
            m.put("hasSecret", c.getSecretEnc() != null);
            m.put("hasPassphrase", c.getPassphraseEnc() != null);
            m.put("hasSudoPassword", c.getSudoPasswordEnc() != null);
            m.put("jumpHost", c.getJumpHost());
            m.put("jumpPort", c.getJumpPort());
            m.put("jumpCredentialId", c.getJumpCredentialId());
            m.put("updatedAt", c.getUpdatedAt());
            return m;
        }).toList();
        List<Map<String, Object>> hosts = knownHosts.list().stream().map(k -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", k.getId());
            m.put("host", k.getHost());
            m.put("port", k.getPort());
            m.put("keyType", k.getKeyType());
            m.put("fingerprintSha256", k.getFingerprintSha256());
            m.put("confirmedBy", k.getConfirmedBy());
            m.put("confirmedAt", k.getConfirmedAt());
            return m;
        }).toList();
        return Map.of("credentials", creds, "knownHosts", hosts);
    }

    @PostMapping("/ssh-credentials")
    @PreAuthorize("hasRole('ADMIN')")
    public Map<String, Object> saveSsh(@RequestBody SshCredPayload req) {
        SshCredential.AuthType at = "KEY".equalsIgnoreCase(req.authType())
                ? SshCredential.AuthType.KEY
                : SshCredential.AuthType.PASSWORD;
        SshCredential saved = sshCreds.save(new SshCredentialService.Form(
                req.id(), req.name(), req.username(), at, req.secret(),
                req.passphrase(), req.sudoPassword(), req.jumpHost(), req.jumpPort(),
                req.jumpCredentialId(),
                Boolean.TRUE.equals(req.clearPassphrase()),
                Boolean.TRUE.equals(req.clearSudoPassword())
        ));
        return Map.of("ok", true, "id", saved.getId());
    }

    @PostMapping("/ssh-credentials/{id}/delete")
    @PreAuthorize("hasRole('ADMIN')")
    public Map<String, Object> deleteSsh(@PathVariable Long id) {
        sshCreds.delete(id);
        return Map.of("ok", true);
    }

    @PostMapping("/ssh-credentials/known-hosts/{id}/delete")
    @PreAuthorize("hasRole('ADMIN')")
    public Map<String, Object> deleteKnownHost(@PathVariable Long id) {
        knownHosts.delete(id);
        return Map.of("ok", true);
    }

    // ==================== Users ====================

    @GetMapping("/users")
    @PreAuthorize("hasRole('ADMIN')")
    public Map<String, Object> listUsers() {
        List<Map<String, Object>> items = userRepo.findAll(Sort.by("username")).stream().map(AdminApiController::toUserDto).toList();
        return Map.of("items", items);
    }

    @PostMapping("/users")
    @PreAuthorize("hasRole('ADMIN')")
    public Map<String, Object> createUser(@RequestBody UserCreatePayload req) {
        AppUser u = users.create(req.username(), req.password(), req.role() != null ? req.role() : Role.USER);
        return Map.of("ok", true, "user", toUserDto(u));
    }

    @PostMapping("/users/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public Map<String, Object> updateUser(@PathVariable Long id, @RequestBody UserUpdatePayload req) {
        users.update(id, req.role(), req.enabled());
        return Map.of("ok", true);
    }

    @PostMapping("/users/{id}/password")
    @PreAuthorize("hasRole('ADMIN')")
    public Map<String, Object> changePassword(@PathVariable Long id, @RequestBody PasswordPayload req) {
        users.changePassword(id, req.password());
        return Map.of("ok", true);
    }

    @PostMapping("/users/{id}/delete")
    @PreAuthorize("hasRole('ADMIN')")
    public Map<String, Object> deleteUser(@PathVariable Long id, Authentication auth) {
        users.delete(id, auth != null ? auth.getName() : "");
        return Map.of("ok", true);
    }

    private static Map<String, Object> toUserDto(AppUser u) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", u.getId());
        m.put("username", u.getUsername());
        m.put("role", u.getRole().name());
        m.put("enabled", u.isEnabled());
        m.put("createdAt", u.getCreatedAt());
        return m;
    }

    // ==================== Settings ====================

    @GetMapping("/settings")
    @PreAuthorize("hasRole('ADMIN')")
    public Map<String, Object> getSettings() {
        var prom = settings.prometheus();
        ChartStyle cs = chartStyle.get();

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("prometheus", Map.of(
                "rwUrl", prom.rwUrl() != null ? prom.rwUrl() : "",
                "queryUrl", prom.queryUrl() != null ? prom.queryUrl() : "",
                "authType", prom.authType() != null ? prom.authType().name() : "NONE",
                "user", prom.user() != null ? prom.user() : "",
                "hasSecret", settings.hasPrometheusSecret(),
                "grafanaUrlTpl", settings.get(SettingsService.GRAFANA_URL_TPL, ""),
                "histogramMode", settings.classicHistograms() ? "CLASSIC" : "NATIVE",
                "pushIntervalSec", settings.pushIntervalSec()
        ));
        Map<String, Object> diagMap = new LinkedHashMap<>();
        diagMap.put("running", diagnostics.isRunning());
        diagMap.put("passed", diagnostics.passed());
        diagMap.put("last", diagnostics.last().orElse(null));
        diagMap.put("progress", diagnostics.progress());
        out.put("diagnostics", diagMap);
        out.put("connectedGenerators", generators.findAllByOrderByNameAsc().stream()
                .filter(g -> agentRegistry.isConnected(g.getId()))
                .map(g -> Map.of("id", g.getId(), "name", g.getName()))
                .toList());
        Map<String, Object> csMap = new LinkedHashMap<>();
        csMap.put("theme", cs.theme().name());
        csMap.put("width", cs.width());
        csMap.put("height", cs.height());
        csMap.put("legend", cs.legend().name());
        csMap.put("stats", cs.stats());
        csMap.put("maxLegend", cs.maxLegend());
        csMap.put("autoWidth", cs.autoWidth());
        csMap.put("sortBy", cs.sortBy().name());
        csMap.put("sortDir", cs.sortDir().name());
        csMap.put("absoluteTime", chartStyle.absoluteTime());
        csMap.put("skipWarmup", chartStyle.skipWarmup());
        out.put("chartStyle", csMap);
        out.put("limits", Map.of(
                "logLinesPerSec", settings.logLinesPerSecond(),
                "retentionLogDays", settings.logRetentionDays(),
                "retentionMetricsDays", settings.metricsRetentionDays(),
                "allowMetrics", String.join("\n", settings.allowMetrics()),
                "onlyNeededHistograms", settings.onlyNeededHistograms(),
                "urlNameTag", settings.urlNameTag()
        ));
        out.put("metricNamesJson", metricNames.json());
        return out;
    }

    @PostMapping("/settings/prometheus")
    @PreAuthorize("hasRole('ADMIN')")
    public Map<String, Object> savePrometheus(@RequestBody PromSettingsPayload req) {
        SettingsService.AuthType at = SettingsService.AuthType.NONE;
        if ("BASIC".equalsIgnoreCase(req.authType())) at = SettingsService.AuthType.BASIC;
        else if ("BEARER".equalsIgnoreCase(req.authType())) at = SettingsService.AuthType.BEARER;

        settings.savePrometheus(req.rwUrl(), req.queryUrl(), at, req.user(), req.secret(), req.grafanaUrlTpl());
        if (req.histogramMode() != null) {
            settings.set(SettingsService.HISTOGRAM_MODE, req.histogramMode());
        }
        if (req.pushIntervalSec() != null) {
            settings.set(SettingsService.PUSH_INTERVAL_SEC, String.valueOf(Math.max(1, req.pushIntervalSec())));
        }
        return Map.of("ok", true);
    }

    @PostMapping("/settings/diagnostics")
    @PreAuthorize("hasRole('ADMIN')")
    public Map<String, Object> startDiagnostics(@RequestBody Map<String, Long> body) {
        Long gid = body.get("generatorId");
        if (gid == null) throw new IllegalArgumentException("Выберите генератор для диагностики");
        var res = diagnostics.runSync(gid);
        return Map.of("ok", res.ok(), "result", res);
    }

    @PostMapping("/settings/report-charts")
    @PreAuthorize("hasRole('ADMIN')")
    public Map<String, Object> saveChartStyle(@RequestBody ChartStylePayload req) {
        chartStyle.save(req.theme(), req.width(), req.height(), req.legend(), req.stats(),
                req.maxLegend(), req.absoluteTime(), req.autoWidth(), req.sortBy(), req.sortDir(), req.skipWarmup());
        return Map.of("ok", true);
    }

    @PostMapping("/settings/report-charts/reset")
    @PreAuthorize("hasRole('ADMIN')")
    public Map<String, Object> resetChartStyle() {
        chartStyle.reset();
        return Map.of("ok", true);
    }

    @PostMapping("/settings/limits")
    @PreAuthorize("hasRole('ADMIN')")
    public Map<String, Object> saveLimits(@RequestBody LimitsPayload req) {
        if (req.logLinesPerSec() != null) settings.set(SettingsService.LOG_LINES_PER_SEC, String.valueOf(req.logLinesPerSec()));
        if (req.retentionLogDays() != null) settings.set(SettingsService.RETENTION_LOG_DAYS, String.valueOf(req.retentionLogDays()));
        if (req.retentionMetricsDays() != null) settings.set(SettingsService.RETENTION_METRICS_DAYS, String.valueOf(req.retentionMetricsDays()));
        if (req.allowMetrics() != null) settings.set(SettingsService.ALLOW_METRICS, req.allowMetrics());
        if (req.onlyNeededHistograms() != null) settings.set(SettingsService.ONLY_NEEDED_HISTOGRAMS, String.valueOf(req.onlyNeededHistograms()));
        if (req.urlNameTag() != null) settings.set(SettingsService.URL_NAME_TAG, String.valueOf(req.urlNameTag()));
        return Map.of("ok", true);
    }

    @PostMapping("/settings/metric-names")
    @PreAuthorize("hasRole('ADMIN')")
    public Map<String, Object> saveMetricNames(@RequestBody Map<String, String> body) {
        metricNames.save(body.getOrDefault("json", "{}"));
        return Map.of("ok", true);
    }

    // ==================== Audit ====================

    @GetMapping("/audit")
    @PreAuthorize("hasRole('ADMIN')")
    public Map<String, Object> listAudit(@RequestParam(defaultValue = "0") int page,
                                         @RequestParam(required = false) String q) {
        var pr = PageRequest.of(Math.max(0, page), 100, Sort.by(Sort.Direction.DESC, "ts", "id"));
        Page<AuditLog> p = (q == null || q.isBlank())
                ? auditRepo.findAll(pr)
                : auditRepo.search(q.trim(), pr);
        return Map.of(
                "items", p.getContent(),
                "page", p.getNumber(),
                "totalPages", p.getTotalPages(),
                "totalElements", p.getTotalElements()
        );
    }
}
