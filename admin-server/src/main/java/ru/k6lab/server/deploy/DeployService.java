package ru.k6lab.server.deploy;

import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import ru.k6lab.server.audit.AuditService;
import ru.k6lab.server.common.SecretMasker;
import ru.k6lab.server.config.AppProperties;
import ru.k6lab.server.deploy.DeployEngine.RolledBack;
import ru.k6lab.server.deploy.DeployEngine.Target;
import ru.k6lab.server.generator.Generator;
import ru.k6lab.server.generator.GeneratorRepository;
import ru.k6lab.server.ssh.KnownHostService;
import ru.k6lab.server.ssh.SshCredentialRepository;
import ru.k6lab.server.ssh.SshExceptions.HostKeyUnconfirmed;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Асинхронные операции над генераторами: установка, обновление, перезапуск, удаление, pre-flight.
 * Параллелизм ограничен пулом потоков (k6lab.deploy.max-parallel). История хранится в таблице deployment.
 */
@Service
public class DeployService {

    private static final Logger log = LoggerFactory.getLogger(DeployService.class);

    public record InstallRequest(String name, String host, int port, Long credentialId,
                                 Generator.InstallMode mode, String dir, String serverUrl,
                                 boolean tuneOs, int maxConcurrentRuns, Long agentArtifactId, Long k6ArtifactId) {
    }

    public record PreflightRequest(String host, int port, Long credentialId, Generator.InstallMode mode, String dir,
                                   String serverUrl) {
    }

    private final DeployEngine engine;
    private final DeployStore store;
    private final GeneratorRepository generators;
    private final SshCredentialRepository credentialsRepo;
    private final KnownHostService knownHosts;
    private final AuditService audit;
    private final ThreadPoolExecutor executor;
    private final ScheduledExecutorService cleaner = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "deploy-log-cleaner");
        t.setDaemon(true);
        return t;
    });
    private final Map<Long, DeployLog> logs = new ConcurrentHashMap<>();

    public DeployService(DeployEngine engine, DeployStore store, GeneratorRepository generators,
                         SshCredentialRepository credentialsRepo, KnownHostService knownHosts, AuditService audit,
                         AppProperties props) {
        this.engine = engine;
        this.store = store;
        this.generators = generators;
        this.credentialsRepo = credentialsRepo;
        this.knownHosts = knownHosts;
        this.audit = audit;
        int n = Math.max(1, props.getDeploy().getMaxParallel());
        this.executor = new ThreadPoolExecutor(n, n, 0L, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>(1000), r -> {
            Thread t = new Thread(r, "deploy-worker");
            t.setDaemon(true);
            return t;
        });
    }

    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
        cleaner.shutdownNow();
    }

    @EventListener(ApplicationReadyEvent.class)
    void failOrphans() {
        int n = store.failOrphans();
        if (n > 0) {
            log.warn("Помечено прерванными {} операций развёртывания, оставшихся от прошлого процесса", n);
        }
    }

    // ================================================================== запуск операций

    public Deployment startPreflight(PreflightRequest r) {
        checkCredential(r.credentialId());
        Generator.InstallMode mode = r.mode() == null ? Generator.InstallMode.SYSTEM : r.mode();
        String dir = normalizeDir(r.dir(), mode);
        Target t = new Target(null, "preflight", r.host(), r.port(), r.credentialId(), mode, dir, r.serverUrl(), false, null);
        DeployEngine.validate(t);
        Deployment d = newDeployment(null, r.host(), Deployment.Operation.PREFLIGHT);
        d.getParams().put("host", r.host());
        d.getParams().put("port", r.port());
        d.getParams().put("credentialId", r.credentialId());
        d.getParams().put("mode", mode.name());
        d.getParams().put("dir", dir);
        d.getParams().put("serverUrl", r.serverUrl());
        d = store.create(d);
        submit(d.getId());
        return d;
    }

    public Deployment startInstall(InstallRequest r) {
        checkCredential(r.credentialId());
        Generator.InstallMode mode = r.mode() == null ? Generator.InstallMode.SYSTEM : r.mode();
        String dir = normalizeDir(r.dir(), mode);
        if (generators.existsByNameIgnoreCase(r.name())) {
            throw new IllegalArgumentException("Генератор с именем «" + r.name() + "» уже существует");
        }
        Target check = new Target(null, r.name(), r.host(), r.port(), r.credentialId(), mode, dir, r.serverUrl(), r.tuneOs(), null);
        DeployEngine.validate(check);
        if (r.maxConcurrentRuns() < 1) {
            throw new IllegalArgumentException("Максимум одновременных run должен быть не меньше 1");
        }
        Generator g = new Generator();
        g.setName(r.name());
        g.setHost(r.host());
        g.setSshPort(r.port());
        g.setSshCredentialId(r.credentialId());
        g.setInstallMode(mode);
        g.setInstallDir(dir);
        g.setServerUrl(r.serverUrl());
        g.setTuneOs(r.tuneOs() && mode == Generator.InstallMode.SYSTEM);
        g.setMaxConcurrentRuns(r.maxConcurrentRuns());
        g.setStatus(Generator.Status.INSTALLING);
        Generator saved = generators.save(g);
        audit.record("generator.install", "generator", saved.getId(), Map.of("name", r.name(), "host", r.host()));
        Deployment d = newDeployment(saved.getId(), r.host(), Deployment.Operation.INSTALL);
        d.getParams().put("agentArtifactId", r.agentArtifactId());
        d.getParams().put("k6ArtifactId", r.k6ArtifactId());
        d = store.create(d);
        submit(d.getId());
        return d;
    }

    public Deployment startUpdate(Long generatorId, Long agentArtifactId, Long k6ArtifactId) {
        Generator g = idle(generatorId, "обновить");
        audit.record("generator.update", "generator", generatorId, null);
        Deployment d = newDeployment(g.getId(), g.getHost(), Deployment.Operation.UPDATE);
        d.getParams().put("agentArtifactId", agentArtifactId);
        d.getParams().put("k6ArtifactId", k6ArtifactId);
        d = store.create(d);
        submit(d.getId());
        return d;
    }

    public Deployment startRestart(Long generatorId) {
        Generator g = idle(generatorId, "перезапустить");
        audit.record("generator.restart", "generator", generatorId, null);
        Deployment d = store.create(newDeployment(g.getId(), g.getHost(), Deployment.Operation.RESTART));
        submit(d.getId());
        return d;
    }

    public Deployment startUninstall(Long generatorId) {
        Generator g = idle(generatorId, "удалить");
        audit.record("generator.uninstall", "generator", generatorId, Map.of("name", g.getName()));
        store.updateGenerator(generatorId, x -> x.setStatus(Generator.Status.REMOVING));
        Deployment d = store.create(newDeployment(g.getId(), g.getHost(), Deployment.Operation.UNINSTALL));
        submit(d.getId());
        return d;
    }

    /** Подтверждение fingerprint пользователем -> запись в known_host и повторный запуск операции. */
    public void confirmHostKey(Long deploymentId, String user) {
        Deployment d = store.load(deploymentId);
        if (d.getStatus() != Deployment.Status.WAITING_HOST_KEY) {
            throw new IllegalStateException("Операция не ждёт подтверждения ключа хоста");
        }
        knownHosts.confirm(d.getHostKeyHost(), d.getHostKeyPort(), d.getHostKeyType(), d.getHostKeyFingerprint(),
                d.getHostKeyPublic(), user);
        submit(deploymentId);
    }

    public String tailAgentLog(Long generatorId, int lines) {
        Generator g = store.loadGenerator(generatorId);
        return engine.tailAgentLog(targetOf(g), lines);
    }

    public SseEmitter stream(Long deploymentId) {
        DeployLog l = logs.get(deploymentId);
        if (l != null) {
            return l.subscribe();
        }
        Deployment d = store.load(deploymentId);
        DeployLog tmp = new DeployLog(deploymentId, new SecretMasker(), d.getLog());
        if (d.getStatus().isFinal() || d.getStatus() == Deployment.Status.WAITING_HOST_KEY) {
            tmp.finish();
        }
        return tmp.subscribe();
    }

    // ================================================================== исполнение

    private void submit(Long deploymentId) {
        // журнал создаётся сразу, чтобы страница могла подписаться ещё до начала выполнения
        Deployment d = store.load(deploymentId);
        logs.put(deploymentId, new DeployLog(deploymentId, new SecretMasker(), d.getLog()));
        try {
            executor.execute(() -> runDeployment(deploymentId));
        } catch (RuntimeException e) {
            store.finish(deploymentId, Deployment.Status.FAILED, "Очередь операций переполнена", List.of(), d.getLog());
            logs.remove(deploymentId);
            throw new IllegalStateException("Очередь операций переполнена, повторите позже");
        }
    }

    private void runDeployment(Long id) {
        DeployLog dlog = logs.get(id);
        DeployRun run = new DeployRun(id, dlog, store);
        MDC.put("deployment_id", String.valueOf(id));
        Deployment d = store.load(id);
        store.markRunning(id);
        try {
            dlog.line("Операция #" + id + ": " + d.getOperation() + " " + d.getTargetHost());
            switch (d.getOperation()) {
                case PREFLIGHT -> engine.runPreflight(run, preflightTarget(d));
                case INSTALL -> {
                    Generator g = store.loadGenerator(d.getGeneratorId());
                    DeployEngine.InstallResult res = engine.install(run, targetOf(g),
                            asLong(d.getParams().get("agentArtifactId")), asLong(d.getParams().get("k6ArtifactId")));
                    store.updateGenerator(g.getId(), x -> {
                        x.setSupervisor(res.supervisor());
                        x.setArch(res.arch() == null ? null : res.arch().name());
                        x.setOsName(res.osName());
                        x.setInstallDir(res.dir());
                    });
                }
                case UPDATE -> engine.update(run, targetOf(store.loadGenerator(d.getGeneratorId())),
                        asLong(d.getParams().get("agentArtifactId")), asLong(d.getParams().get("k6ArtifactId")));
                case RESTART -> engine.restart(run, targetOf(store.loadGenerator(d.getGeneratorId())));
                case UNINSTALL -> {
                    Generator g = store.loadGenerator(d.getGeneratorId());
                    engine.uninstall(run, targetOf(g));
                    // отзыв токена и удаление записи: агент больше не сможет подключиться
                    store.deleteGenerator(g.getId());
                    dlog.line("Генератор удалён, токены отозваны");
                }
            }
            store.finish(id, Deployment.Status.SUCCEEDED, null, run.steps(), dlog.snapshot());
            dlog.line("✔ Операция завершена успешно");
        } catch (HostKeyUnconfirmed e) {
            dlog.line("⏸ Ключ хоста " + e.host() + ":" + e.port() + " не подтверждён. Fingerprint: " + e.keyType() + " "
                    + e.fingerprint() + ". Сверьте его с ключом хоста и подтвердите в UI.");
            store.waitHostKey(id, e.host(), e.port(), e.keyType(), e.fingerprint(), e.publicKey(), run.steps(),
                    dlog.snapshot());
        } catch (RolledBack e) {
            String msg = dlog.masker().mask(String.valueOf(e.getMessage()));
            dlog.line("✖ Операция не удалась и откачена: " + msg);
            store.finish(id, Deployment.Status.ROLLED_BACK, msg, run.steps(), dlog.snapshot());
            markGeneratorError(d);
        } catch (Exception e) {
            String msg = dlog.masker().mask(String.valueOf(e.getMessage()));
            log.warn("Операция {} #{} завершилась ошибкой: {}", d.getOperation(), id, msg);
            dlog.line("✖ Операция не удалась: " + msg);
            store.finish(id, Deployment.Status.FAILED, msg, run.steps(), dlog.snapshot());
            markGeneratorError(d);
        } finally {
            MDC.remove("deployment_id");
            dlog.finish();
            cleaner.schedule(() -> logs.remove(id, dlog), 10, TimeUnit.MINUTES);
        }
    }

    private void markGeneratorError(Deployment d) {
        if (d.getGeneratorId() == null) {
            return;
        }
        if (d.getOperation() == Deployment.Operation.INSTALL) {
            store.updateGenerator(d.getGeneratorId(), g -> g.setStatus(Generator.Status.ERROR));
        } else if (d.getOperation() == Deployment.Operation.UNINSTALL) {
            store.updateGenerator(d.getGeneratorId(), g -> g.setStatus(Generator.Status.ERROR));
        }
    }

    // ================================================================== утилиты

    private Deployment newDeployment(Long generatorId, String host, Deployment.Operation op) {
        Deployment d = new Deployment();
        d.setGeneratorId(generatorId);
        d.setTargetHost(host);
        d.setOperation(op);
        d.setParams(new LinkedHashMap<>());
        var auth = SecurityContextHolder.getContext().getAuthentication();
        d.setStartedBy(auth == null ? "system" : auth.getName());
        d.setStartedAt(Instant.now());
        return d;
    }

    private Generator idle(Long generatorId, String verb) {
        Generator g = store.loadGenerator(generatorId);
        if (g.isBusy()) {
            throw new IllegalStateException("Нельзя " + verb + " генератор «" + g.getName() + "»: на нём выполняется тест");
        }
        if (store.hasActive(generatorId)) {
            throw new IllegalStateException("С генератором «" + g.getName() + "» уже выполняется другая операция");
        }
        return g;
    }

    private void checkCredential(Long id) {
        if (id == null || !credentialsRepo.existsById(id)) {
            throw new IllegalArgumentException("Выберите SSH-учётку");
        }
    }

    private static String normalizeDir(String dir, Generator.InstallMode mode) {
        if (dir == null || dir.isBlank()) {
            return mode == Generator.InstallMode.SYSTEM ? "/opt/k6-agent" : "~/k6-agent";
        }
        return dir.trim();
    }

    private static Target targetOf(Generator g) {
        return new Target(g.getId(), g.getName(), g.getHost(), g.getSshPort(), g.getSshCredentialId(), g.getInstallMode(),
                g.getInstallDir(), g.getServerUrl(), g.isTuneOs(), g.getSupervisor());
    }

    private static Target preflightTarget(Deployment d) {
        Map<String, Object> p = d.getParams();
        return new Target(null, "preflight", (String) p.get("host"), ((Number) p.get("port")).intValue(),
                asLong(p.get("credentialId")), Generator.InstallMode.valueOf((String) p.get("mode")),
                (String) p.get("dir"), (String) p.get("serverUrl"), false, null);
    }

    private static Long asLong(Object o) {
        return o instanceof Number n ? n.longValue() : null;
    }
}
