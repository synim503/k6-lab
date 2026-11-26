package ru.k6lab.server.tuning;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import ru.k6lab.server.audit.AuditService;
import ru.k6lab.server.common.SecretMasker;
import ru.k6lab.server.deploy.DeployStore;
import ru.k6lab.server.deploy.Deployment;
import ru.k6lab.server.generator.Generator;
import ru.k6lab.server.generator.GeneratorRepository;
import ru.k6lab.server.ssh.ResolvedCredential;
import ru.k6lab.server.ssh.SshConnection;
import ru.k6lab.server.ssh.SshConnector;
import ru.k6lab.server.ssh.SshCredentialService;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Function;

/**
 * Профилирование хостов-генераторов по SSH и применение рекомендованных настроек ядра с возможностью отката.
 * Профилирование только читает. Применение пишет файл {@value #CONF} и вызывает sysctl; исходные значения каждого параметра
 * сохраняются в БД, откат возвращает именно их (а не «значения по умолчанию») и удаляет файл. Каждое действие попадает в журнал операций.
 */
@Service
public class HostTuningService {

    private static final Logger log = LoggerFactory.getLogger(HostTuningService.class);

    /** Отдельный файл: загружается после установочного 99-k6.conf и перекрывает его. */
    public static final String CONF = "/etc/sysctl.d/99-k6lab-tuning.conf";
    /** Настройки из /sys переживают перезагрузку через systemd-tmpfiles. */
    public static final String TMPFILES = "/etc/tmpfiles.d/k6lab-tuning.conf";
    /** sysctl лежит в sbin, которого может не быть в PATH не-интерактивной SSH-сессии. */
    private static final String PATH_FIX = "PATH=$PATH:/usr/sbin:/sbin:/usr/local/sbin; export PATH; ";
    private static final String THP_FILE = "/sys/kernel/mm/transparent_hugepage/enabled";
    private static final String GOV_GLOB = "/sys/devices/system/cpu/cpu*/cpufreq/scaling_governor";
    private static final Duration CONNECT = Duration.ofSeconds(20);
    public static final int MIN_SECONDS = 10;
    public static final int MAX_SECONDS = 300;

    private final GeneratorRepository generators;
    private final SshCredentialService credentials;
    private final SshConnector connector;
    private final HostTuningStore store;
    private final AuditService audit;
    private final DeployStore deployStore;
    private final TuningProfileStore profiles;
    private final String script;
    private final ExecutorService profilePool = Executors.newFixedThreadPool(4, r -> {
        Thread t = new Thread(r, "host-tuning-profile");
        t.setDaemon(true);
        return t;
    });
    private final ExecutorService actionPool = Executors.newFixedThreadPool(8, r -> {
        Thread t = new Thread(r, "host-tuning-action");
        t.setDaemon(true);
        return t;
    });
    private final Map<Long, ReentrantLock> hostLocks = new ConcurrentHashMap<>();

    public HostTuningService(GeneratorRepository generators, SshCredentialService credentials, SshConnector connector,
                             HostTuningStore store, AuditService audit, DeployStore deployStore,
                             TuningProfileStore profiles) {
        this.generators = generators;
        this.credentials = credentials;
        this.connector = connector;
        this.store = store;
        this.audit = audit;
        this.deployStore = deployStore;
        this.profiles = profiles;
        try (var in = new ClassPathResource("tuning/profile.sh").getInputStream()) {
            this.script = new String(in.readAllBytes(), StandardCharsets.UTF_8).replace("\r\n", "\n");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @PostConstruct
    void init() {
        store.failStaleProfiles();
    }

    @PreDestroy
    void shutdown() {
        profilePool.shutdownNow();
        actionPool.shutdownNow();
    }

    // ------------------------------------------------------------------ журнал операций

    /** Запись в журнале «Операции»: что сделано на хосте, по шагам. */
    private final class Op {
        final long id;
        final SecretMasker masker = new SecretMasker();
        private final List<Deployment.StepInfo> steps = new ArrayList<>();
        private final StringBuilder text = new StringBuilder();

        Op(Generator g, Deployment.Operation operation, String user, Map<String, Object> params) {
            Deployment d = new Deployment();
            d.setGeneratorId(g.getId());
            d.setTargetHost(g.getHost());
            d.setOperation(operation);
            d.setStatus(Deployment.Status.RUNNING);
            d.setStartedBy(user == null ? "system" : user);
            d.setStartedAt(Instant.now());
            d.setParams(new LinkedHashMap<>(params));
            this.id = deployStore.create(d).getId();
        }

        void step(String name, String status, String message) {
            Instant now = Instant.now();
            String safe = message == null ? null : masker.mask(message);
            steps.add(new Deployment.StepInfo(steps.size() + 1, name, status, safe, now, now));
            text.append("▶ Шаг ").append(steps.size()).append(": ").append(name);
            if (safe != null && !safe.isBlank()) {
                text.append(" — ").append(safe);
            }
            text.append('\n');
            deployStore.saveProgress(id, steps, text.toString());
        }

        void finish(boolean ok, String error) {
            String safeErr = error == null ? null : masker.mask(error);
            deployStore.finish(id, ok ? Deployment.Status.SUCCEEDED : Deployment.Status.FAILED, safeErr, steps, text.toString());
        }
    }

    // ------------------------------------------------------------------ профилирование

    /** Запускает профилирование выбранных генераторов (параллельно, в фоне); возвращает сообщения о тех, что не удалось поставить в очередь. */
    public List<String> startProfiling(List<Long> generatorIds, int seconds, String user) {
        if (seconds < MIN_SECONDS || seconds > MAX_SECONDS) {
            throw new IllegalArgumentException("Длительность измерения: от " + MIN_SECONDS + " до " + MAX_SECONDS + " секунд");
        }
        List<String> problems = new ArrayList<>();
        for (Generator g : load(generatorIds)) {
            if (g.getSshCredentialId() == null) {
                problems.add(g.getName() + ": не задана SSH-учётка");
                continue;
            }
            long profileId = store.createProfile(g.getId(), g.getName(), seconds);
            audit.record("host.profile", "generator", g.getId(), Map.of("seconds", seconds, "by", String.valueOf(user)));
            Op op = new Op(g, Deployment.Operation.PROFILE, user, Map.of("seconds", seconds));
            profilePool.submit(() -> profile(g, profileId, seconds, op));
        }
        return problems;
    }

    private void profile(Generator g, long profileId, int seconds, Op op) {
        try (SshConnection ssh = connect(g, op.masker)) {
            op.step("Подключение по SSH", "OK", g.getHost());
            String cmd = script.replace("@SECS@", String.valueOf(seconds)).replace("@KEYS@", String.join(" ", TuningAnalyzer.KEYS));
            var r = ssh.sudo(cmd, Duration.ofSeconds(seconds + 60L));
            boolean limited = false;
            if (!r.ok() && r.stdout().isBlank()) {
                // sudo недоступен: без root часть данных (дескрипторы чужих процессов) не видна, но профиль всё равно полезен
                r = ssh.exec("sh -c " + SshConnection.quote(cmd), Duration.ofSeconds(seconds + 60L));
                limited = true;
            }
            if (r.stdout().isBlank()) {
                throw new IllegalStateException("Скрипт не вернул данных" + (r.stderr().isBlank() ? "" : ": " + r.stderr().strip()));
            }
            op.step("Снятие показателей за " + seconds + " с", "OK", limited ? "без прав root" : null);
            HostMetrics m = HostMetricsParser.parse(r.stdout(), seconds);
            TuningAnalyzer.Analysis a = TuningAnalyzer.analyze(m);
            List<TuningAnalyzer.Finding> findings = new ArrayList<>(a.findings());
            if (limited) {
                findings.add(new TuningAnalyzer.Finding(TuningAnalyzer.Severity.INFO, "system", "Профиль снят без прав root",
                        "sudo для SSH-учётки не работает: часть показателей (например, дескрипторы процессов k6) могла быть недоступна.", null, null, null));
            }
            store.finishProfile(profileId, "DONE", null, m, findings);
            long crit = findings.stream().filter(f -> f.severity() == TuningAnalyzer.Severity.CRIT).count();
            long warn = findings.stream().filter(f -> f.severity() == TuningAnalyzer.Severity.WARN).count();
            op.step("Анализ", "OK", "критично: " + crit + ", замечаний: " + warn + ", настроек для применения: " + a.recommended().size());
            op.finish(true, null);
        } catch (RuntimeException e) {
            String safeMsg = op.masker.mask(message(e));
            log.warn("Профилирование {} не удалось: {}", g.getName(), safeMsg);
            store.finishProfile(profileId, "FAILED", safeMsg, null, null);
            op.step("Профилирование", "FAILED", safeMsg);
            op.finish(false, safeMsg);
        }
    }

    // ------------------------------------------------------------------ применение

    /** Применяет рекомендации последнего профиля каждого хоста к нему самому. */
    public List<String> apply(List<Long> generatorIds, String user) {
        return runAll(load(generatorIds), g -> applyOne(g, user, null, null));
    }

    /**
     * Раскатывает одинаковый набор настроек: берутся рекомендации профиля генератора {@code sourceId} (обычно с наибольшим числом
     * изменений) и применяются на всех выбранных хостах; пустой список - на всех, где задана SSH-учётка.
     */
    public List<String> rollout(long sourceId, List<Long> targetIds, String user) {
        HostTuningStore.Profile src = store.latestDone(sourceId).orElseThrow(
                () -> new IllegalArgumentException("У эталонного хоста нет готового профиля"));
        Map<String, String> wanted = recommended(src);
        if (wanted.isEmpty()) {
            throw new IllegalArgumentException("В профиле эталонного хоста нет настроек для применения");
        }
        List<Generator> targets = targetIds == null || targetIds.isEmpty()
                ? generators.findAll().stream().filter(g -> g.getSshCredentialId() != null).toList()
                : load(targetIds);
        return runAll(targets, g -> applyOne(g, user, wanted, src.generatorName()));
    }

    /** Раскатывает сохранённый профиль оптимизации на выбранные хосты (пустой список - на все, где задана SSH-учётка). */
    public List<String> applyProfile(long profileId, List<Long> targetIds, String user) {
        TuningProfileStore.OptProfile p = profiles.find(profileId).orElseThrow(() -> new IllegalArgumentException("Профиль оптимизации не найден"));
        Map<String, String> wanted = new LinkedHashMap<>();
        p.settings().forEach((k, v) -> {
            if (TuningAnalyzer.valid(k, v)) {
                wanted.put(k, norm(v));
            }
        });
        if (wanted.isEmpty()) {
            throw new IllegalArgumentException("В профиле «" + p.name() + "» нет настроек для применения");
        }
        List<Generator> targets = targetIds == null || targetIds.isEmpty()
                ? generators.findAll().stream().filter(g -> g.getSshCredentialId() != null).toList()
                : load(targetIds);
        return runAll(targets, g -> applyOne(g, user, wanted, "профиль «" + p.name() + "»"));
    }

    public List<String> rollback(List<Long> generatorIds, String user) {
        return runAll(load(generatorIds), g -> rollbackOne(g, user));
    }

    /** Возвращает в исходное состояние все хосты, на которых действует применённый тюнинг. */
    public List<String> rollbackAll(String user) {
        List<Long> ids = generators.findAll().stream().map(Generator::getId)
                .filter(id -> !store.activeTunings(id).isEmpty()).toList();
        if (ids.isEmpty()) {
            throw new IllegalArgumentException("Нет хостов с применённым тюнингом");
        }
        return rollback(ids, user);
    }

    private String applyOne(Generator g, String user, Map<String, String> forced, String sourceName) {
        ReentrantLock lock = hostLocks.computeIfAbsent(g.getId(), k -> new ReentrantLock());
        lock.lock();
        try {
            Map<String, String> wanted = forced;
            if (wanted == null) {
                HostTuningStore.Profile p = store.latestDone(g.getId()).orElse(null);
                if (p == null) {
                    return "нет профиля: сначала запустите профилирование";
                }
                wanted = recommended(p);
                if (wanted.isEmpty()) {
                    return "рекомендаций по настройкам нет";
                }
            }
            if (g.getSshCredentialId() == null) {
                return "не задана SSH-учётка";
            }
            Op op = new Op(g, Deployment.Operation.TUNE_APPLY, user,
                    Map.of("settings", wanted.size(), "source", sourceName == null ? "собственный профиль" : sourceName));
            try (SshConnection ssh = connect(g, op.masker)) {
                op.step("Подключение по SSH", "OK", g.getHost());
                List<HostTuningStore.Tuning> active = store.activeTunings(g.getId());
                Map<String, String> original = new LinkedHashMap<>();   // исходное значение до первого применения
                Map<String, String> desired = new LinkedHashMap<>();    // что должно стоять (ранее применённое + новое)
                for (HostTuningStore.Tuning t : active) {
                    for (JsonNode c : t.changes()) {
                        original.putIfAbsent(c.path("key").asText(), c.path("previous").asText());
                        desired.put(c.path("key").asText(), c.path("requested").asText());
                    }
                }
                desired.putAll(wanted);
                Map<String, String> current = read(ssh, new ArrayList<>(desired.keySet()));
                desired.keySet().forEach(k -> original.putIfAbsent(k, current.getOrDefault(k, "")));
                op.step("Сохранены исходные значения", "OK", original.size() + " параметров");

                // Применяем параметры напрямую; постоянные файлы записываем только для реально применившихся параметров
                StringBuilder applyCmd = new StringBuilder(PATH_FIX).append("rc=0; ");
                for (Map.Entry<String, String> e : desired.entrySet()) {
                    applyCmd.append(writeCmd(e.getKey(), e.getValue()));
                }
                applyCmd.append("exit $rc");
                var r = ssh.sudo(applyCmd.toString(), Duration.ofSeconds(60));
                Map<String, String> after = read(ssh, new ArrayList<>(desired.keySet()));

                List<Map<String, Object>> changes = new ArrayList<>();
                Map<String, String> succeeded = new LinkedHashMap<>();
                int okCount = 0;
                for (Map.Entry<String, String> e : desired.entrySet()) {
                    String actual = after.getOrDefault(e.getKey(), "");
                    boolean ok = norm(actual).equals(norm(e.getValue()));
                    if (ok) {
                        okCount++;
                        succeeded.put(e.getKey(), e.getValue());
                    }
                    Map<String, Object> c = new LinkedHashMap<>();
                    c.put("key", e.getKey());
                    c.put("previous", original.getOrDefault(e.getKey(), ""));
                    c.put("requested", e.getValue());
                    c.put("applied", actual);
                    c.put("ok", ok);
                    changes.add(c);
                    op.step("Параметр " + e.getKey(), ok ? "OK" : "FAILED",
                            original.getOrDefault(e.getKey(), "") + " → " + actual + (ok ? "" : " (запрошено " + e.getValue() + ")"));
                }
                String err = r.ok() ? null : (r.stderr().isBlank() ? "команда вернула код " + r.exitCode() : r.stderr().strip());
                if (okCount == 0) {
                    if (active.isEmpty()) {
                        ssh.sudo(PATH_FIX + "rm -f " + CONF + " " + TMPFILES, Duration.ofSeconds(20));
                    }
                    store.insertTuning(g.getId(), g.getName(), user, "FAILED", changes, err);
                    String msg = err == null ? "значения не изменились" : err;
                    op.finish(false, msg);
                    return "не применилось: " + msg + " (в контейнерах и ограниченных окружениях это нормально)";
                }

                // Записываем CONF и TMPFILES только для применившихся параметров через приватный временный каталог
                StringBuilder conf = new StringBuilder("# Настройки ядра от k6-lab (профилирование хоста). Откат - в админке.\n");
                StringBuilder tmpfiles = new StringBuilder("# Настройки /sys от k6-lab. Откат - в админке.\n");
                int sysctlCount = 0;
                int sysCount = 0;
                for (Map.Entry<String, String> e : succeeded.entrySet()) {
                    if (TuningAnalyzer.SYS_KEYS.contains(e.getKey())) {
                        tmpfiles.append("w ").append(e.getKey().equals(TuningAnalyzer.THP) ? THP_FILE : GOV_GLOB)
                                .append(" - - - - ").append(e.getValue()).append('\n');
                        sysCount++;
                    } else {
                        conf.append(e.getKey()).append(" = ").append(e.getValue()).append('\n');
                        sysctlCount++;
                    }
                }
                String tmpDir = null;
                try {
                    var mk = ssh.exec("mktemp -d /tmp/k6lab-tune.XXXXXX", Duration.ofSeconds(15));
                    if (!mk.ok() || mk.stdoutTrim().isEmpty()) {
                        throw new IllegalStateException("Не удалось создать временный каталог на хосте");
                    }
                    tmpDir = mk.stdoutTrim();
                    ssh.exec("chmod 700 " + SshConnection.quote(tmpDir), Duration.ofSeconds(15));
                    StringBuilder persistCmd = new StringBuilder(PATH_FIX);
                    if (sysctlCount > 0) {
                        String confTmp = tmpDir + "/tuning.conf";
                        ssh.uploadText(conf.toString(), confTmp);
                        persistCmd.append("install -m 644 ").append(SshConnection.quote(confTmp)).append(' ').append(CONF).append("; ");
                    } else {
                        persistCmd.append("rm -f ").append(CONF).append("; ");
                    }
                    if (sysCount > 0) {
                        String tfTmp = tmpDir + "/tmpfiles.conf";
                        ssh.uploadText(tmpfiles.toString(), tfTmp);
                        persistCmd.append("install -m 644 ").append(SshConnection.quote(tfTmp)).append(' ').append(TMPFILES).append("; ");
                    } else {
                        persistCmd.append("rm -f ").append(TMPFILES).append("; ");
                    }
                    ssh.sudo(persistCmd.toString(), Duration.ofSeconds(30));
                } finally {
                    if (tmpDir != null) {
                        try {
                            ssh.exec("rm -rf " + SshConnection.quote(tmpDir), Duration.ofSeconds(15));
                        } catch (RuntimeException ignored) {
                            // best-effort cleanup
                        }
                    }
                }

                // новая запись заменяет прежние: исходные значения перенесены в неё
                store.markReplaced(g.getId());
                store.insertTuning(g.getId(), g.getName(), user, "APPLIED", changes, err);
                audit.record("host.tuning.apply", "generator", g.getId(), Map.of("changes", changes.size(), "by", String.valueOf(user)));
                op.finish(true, err);
                return "применено параметров: " + okCount + " из " + changes.size() + (err == null ? "" : " (" + op.masker.mask(err) + ")");
            } catch (RuntimeException e) {
                String safeMsg = op.masker.mask(message(e));
                op.step("Применение", "FAILED", safeMsg);
                op.finish(false, safeMsg);
                return "ошибка: " + safeMsg;
            }
        } finally {
            lock.unlock();
        }
    }

    private String rollbackOne(Generator g, String user) {
        ReentrantLock lock = hostLocks.computeIfAbsent(g.getId(), k -> new ReentrantLock());
        lock.lock();
        try {
            List<HostTuningStore.Tuning> active = store.activeTunings(g.getId());
            if (active.isEmpty()) {
                return "нечего откатывать";
            }
            Map<String, String> original = new LinkedHashMap<>();
            for (HostTuningStore.Tuning t : active) {
                for (JsonNode c : t.changes()) {
                    original.putIfAbsent(c.path("key").asText(), c.path("previous").asText());
                }
            }
            Op op = new Op(g, Deployment.Operation.TUNE_ROLLBACK, user, Map.of("settings", original.size()));
            try (SshConnection ssh = connect(g, op.masker)) {
                op.step("Подключение по SSH", "OK", g.getHost());
                StringBuilder cmd = new StringBuilder(PATH_FIX).append("rm -f ").append(CONF).append(' ').append(TMPFILES).append("; rc=0; ");
                for (Map.Entry<String, String> e : original.entrySet()) {
                    String v = norm(e.getValue());
                    if (!TuningAnalyzer.valid(e.getKey(), v)) {
                        continue; // исходное значение не прочитано: параметр не трогаем
                    }
                    cmd.append(writeCmd(e.getKey(), v));
                }
                cmd.append("exit $rc");
                var r = ssh.sudo(cmd.toString(), Duration.ofSeconds(60));
                Map<String, String> after = read(ssh, new ArrayList<>(original.keySet()));
                List<String> notRestored = new ArrayList<>();
                original.forEach((k, v) -> {
                    boolean ok = norm(v).isEmpty() || norm(after.getOrDefault(k, "")).equals(norm(v));
                    if (!ok) {
                        notRestored.add(k);
                    }
                    op.step("Параметр " + k, ok ? "OK" : "FAILED", after.getOrDefault(k, "") + (ok ? " (исходное)" : " (ожидалось " + norm(v) + ")"));
                });
                op.step("Файлы настроек удалены", "OK", CONF);
                audit.record("host.tuning.rollback", "generator", g.getId(), Map.of("by", String.valueOf(user)));
                if (!notRestored.isEmpty()) {
                    String msg = "не вернулись: " + String.join(", ", notRestored) + (r.stderr().isBlank() ? "" : " (" + r.stderr().strip() + ")");
                    op.finish(false, msg);
                    return "откат выполнен частично, " + op.masker.mask(msg) + " (запись оставлена активной для повторного отката)";
                }
                store.markRolledBack(g.getId());
                op.finish(true, null);
                return "возвращено параметров: " + original.size() + ", файлы настроек удалены";
            } catch (RuntimeException e) {
                String safeMsg = op.masker.mask(message(e));
                op.step("Откат", "FAILED", safeMsg);
                op.finish(false, safeMsg);
                return "ошибка: " + safeMsg;
            }
        } finally {
            lock.unlock();
        }
    }

    /** Команда записи значения параметра на хосте (значение уже проверено {@link TuningAnalyzer#valid}). */
    private static String writeCmd(String key, String v) {
        return switch (key) {
            case TuningAnalyzer.THP -> "echo " + v + " > " + THP_FILE + " || rc=1; ";
            case TuningAnalyzer.GOVERNOR -> "for f in " + GOV_GLOB + "; do echo " + v + " > \"$f\" || rc=1; done; ";
            default -> "sysctl -w " + key + "=\"" + v + "\" >/dev/null || rc=1; ";
        };
    }

    /** Команда чтения текущего значения параметра. */
    private static String readCmd(String key) {
        return switch (key) {
            case TuningAnalyzer.THP -> "echo \"" + key + "=$(sed -n 's/.*\\[\\(.*\\)\\].*/\\1/p' " + THP_FILE + " 2>/dev/null)\"; ";
            case TuningAnalyzer.GOVERNOR -> "echo \"" + key + "=$(cat /sys/devices/system/cpu/cpu0/cpufreq/scaling_governor 2>/dev/null)\"; ";
            default -> "echo \"" + key + "=$(sysctl -n " + key + " 2>/dev/null | tr '\\t' ' ')\"; ";
        };
    }
    // ------------------------------------------------------------------ вспомогательное

    /** Рекомендованные настройки из находок профиля; только известные параметры и безопасные значения. */
    static Map<String, String> recommended(HostTuningStore.Profile p) {
        Map<String, String> out = new LinkedHashMap<>();
        if (p.findings() == null) {
            return out;
        }
        for (JsonNode f : p.findings()) {
            String key = f.path("key").asText(null), rec = f.path("recommended").asText(null);
            if (key != null && rec != null && TuningAnalyzer.valid(key, rec)) {
                out.put(key, norm(rec));
            }
        }
        return out;
    }

    private Map<String, String> read(SshConnection ssh, List<String> keys) {
        StringBuilder cmd = new StringBuilder(PATH_FIX);
        int n = 0;
        for (String k : keys) {
            if (TuningAnalyzer.allKeys().contains(k)) {
                cmd.append(readCmd(k));
                n++;
            }
        }
        Map<String, String> out = new LinkedHashMap<>();
        if (n == 0) {
            return out;
        }
        var r = ssh.sudo(cmd.toString(), Duration.ofSeconds(30));
        for (String line : r.stdout().split("\\R")) {
            int eq = line.indexOf('=');
            if (eq > 0) {
                out.put(line.substring(0, eq).strip(), norm(line.substring(eq + 1)));
            }
        }
        return out;
    }
    private SshConnection connect(Generator g, SecretMasker masker) {
        if (g.getSshCredentialId() == null) {
            throw new IllegalStateException("У генератора не задана SSH-учётка");
        }
        ResolvedCredential cred = credentials.resolve(g.getSshCredentialId());
        for (ResolvedCredential c = cred; c != null; c = c.jump()) {
            masker.add(c.secret(), c.passphrase(), c.sudoPassword());
        }
        return connector.open(g.getHost(), g.getSshPort(), cred, CONNECT);
    }

    private List<Generator> load(List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            throw new IllegalArgumentException("Выберите хотя бы один генератор");
        }
        return generators.findAllById(ids);
    }

    private List<String> runAll(List<Generator> gens, Function<Generator, String> task) {
        Map<Generator, Future<String>> futures = new LinkedHashMap<>();
        for (Generator g : gens) {
            futures.put(g, actionPool.submit(() -> task.apply(g)));
        }
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(120);
        List<String> out = new ArrayList<>();
        futures.forEach((g, f) -> {
            long remNs = Math.max(0L, deadline - System.nanoTime());
            try {
                out.add(g.getName() + ": " + f.get(remNs, TimeUnit.NANOSECONDS));
            } catch (TimeoutException e) {
                f.cancel(true);
                out.add(g.getName() + ": ошибка: таймаут операции (120 с)");
            } catch (Exception e) {
                out.add(g.getName() + ": ошибка: " + message(e));
            }
        });
        return out;
    }

    private static String norm(String s) {
        return s == null ? "" : s.strip().replaceAll("\\s+", " ");
    }

    private static String message(Throwable e) {
        String m = e.getMessage();
        return m == null || m.isBlank() ? e.getClass().getSimpleName() : m;
    }
}
