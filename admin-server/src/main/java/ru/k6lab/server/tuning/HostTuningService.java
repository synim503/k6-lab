package ru.k6lab.server.tuning;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import ru.k6lab.server.audit.AuditService;
import ru.k6lab.server.generator.Generator;
import ru.k6lab.server.generator.GeneratorRepository;
import ru.k6lab.server.ssh.SshConnection;
import ru.k6lab.server.ssh.SshConnector;
import ru.k6lab.server.ssh.SshCredentialService;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.regex.Pattern;

/**
 * Профилирование хостов-генераторов по SSH и применение рекомендованных настроек ядра с возможностью отката.
 * Профилирование только читает. Применение пишет файл {@value #CONF} и вызывает sysctl; исходные значения каждого параметра
 * сохраняются в БД, откат возвращает именно их (а не «значения по умолчанию») и удаляет файл.
 */
@Service
public class HostTuningService {

    private static final Logger log = LoggerFactory.getLogger(HostTuningService.class);

    /** Отдельный файл: загружается после установочного 99-k6.conf и перекрывает его. */
    public static final String CONF = "/etc/sysctl.d/99-k6lab-tuning.conf";
    private static final String TMP = "/tmp/k6lab-tuning.conf";
    /** Значения параметров: одно-три числа через пробел (диапазон портов, буферы TCP). */
    private static final Pattern VALUE = Pattern.compile("^[0-9]+( [0-9]+){0,2}$");
    private static final Duration CONNECT = Duration.ofSeconds(20);
    public static final int MIN_SECONDS = 10;
    public static final int MAX_SECONDS = 300;

    private final GeneratorRepository generators;
    private final SshCredentialService credentials;
    private final SshConnector connector;
    private final HostTuningStore store;
    private final AuditService audit;
    private final String script;
    private final ExecutorService pool = Executors.newFixedThreadPool(8, r -> {
        Thread t = new Thread(r, "host-tuning");
        t.setDaemon(true);
        return t;
    });

    public HostTuningService(GeneratorRepository generators, SshCredentialService credentials, SshConnector connector,
                             HostTuningStore store, AuditService audit) {
        this.generators = generators;
        this.credentials = credentials;
        this.connector = connector;
        this.store = store;
        this.audit = audit;
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
            pool.submit(() -> profile(g, profileId, seconds));
        }
        return problems;
    }

    private void profile(Generator g, long profileId, int seconds) {
        try (SshConnection ssh = connect(g)) {
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
            HostMetrics m = HostMetricsParser.parse(r.stdout(), seconds);
            TuningAnalyzer.Analysis a = TuningAnalyzer.analyze(m);
            List<TuningAnalyzer.Finding> findings = new ArrayList<>(a.findings());
            if (limited) {
                findings.add(new TuningAnalyzer.Finding(TuningAnalyzer.Severity.INFO, "system", "Профиль снят без прав root",
                        "sudo для SSH-учётки не работает: часть показателей (например, дескрипторы процессов k6) могла быть недоступна.", null, null, null));
            }
            store.finishProfile(profileId, "DONE", null, m, findings);
        } catch (RuntimeException e) {
            log.warn("Профилирование {} не удалось: {}", g.getName(), e.toString());
            store.finishProfile(profileId, "FAILED", message(e), null, null);
        }
    }

    // ------------------------------------------------------------------ применение

    /** Применяет рекомендации последнего профиля к выбранным генераторам. Возвращает по строке-результату на генератор. */
    public List<String> apply(List<Long> generatorIds, String user) {
        return runAll(load(generatorIds), g -> applyOne(g, user));
    }

    public List<String> rollback(List<Long> generatorIds, String user) {
        return runAll(load(generatorIds), g -> rollbackOne(g, user));
    }

    private String applyOne(Generator g, String user) {
        HostTuningStore.Profile p = store.latestDone(g.getId()).orElse(null);
        if (p == null) {
            return "нет профиля: сначала запустите профилирование";
        }
        Map<String, String> wanted = recommended(p);
        if (wanted.isEmpty()) {
            return "рекомендаций по настройкам нет";
        }
        try (SshConnection ssh = connect(g)) {
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

            StringBuilder conf = new StringBuilder("# Настройки ядра от k6-lab (профилирование хоста). Откат - в админке.\n");
            desired.forEach((k, v) -> conf.append(k).append(" = ").append(v).append('\n'));
            ssh.uploadText(conf.toString(), TMP);
            var r = ssh.sudo("install -m 644 " + TMP + " " + CONF + " && rm -f " + TMP + " && sysctl -p " + CONF, Duration.ofSeconds(40));
            Map<String, String> after = read(ssh, new ArrayList<>(desired.keySet()));

            List<Map<String, Object>> changes = new ArrayList<>();
            int okCount = 0;
            for (Map.Entry<String, String> e : desired.entrySet()) {
                String actual = after.getOrDefault(e.getKey(), "");
                boolean ok = norm(actual).equals(norm(e.getValue()));
                okCount += ok ? 1 : 0;
                Map<String, Object> c = new LinkedHashMap<>();
                c.put("key", e.getKey());
                c.put("previous", original.getOrDefault(e.getKey(), ""));
                c.put("requested", e.getValue());
                c.put("applied", actual);
                c.put("ok", ok);
                changes.add(c);
            }
            String err = r.ok() ? null : (r.stderr().isBlank() ? "sysctl вернул код " + r.exitCode() : r.stderr().strip());
            if (okCount == 0) {
                ssh.sudo("rm -f " + CONF, Duration.ofSeconds(20));
                store.insertTuning(g.getId(), g.getName(), user, "FAILED", changes, err);
                return "не применилось: " + (err == null ? "значения не изменились" : err) + " (в контейнерах и ограниченных окружениях это нормально)";
            }
            // новая запись заменяет прежние: исходные значения перенесены в неё
            store.markReplaced(g.getId());
            store.insertTuning(g.getId(), g.getName(), user, "APPLIED", changes, err);
            audit.record("host.tuning.apply", "generator", g.getId(), Map.of("changes", changes.size(), "by", String.valueOf(user)));
            return "применено параметров: " + okCount + " из " + changes.size() + (err == null ? "" : " (" + err + ")");
        } catch (RuntimeException e) {
            return "ошибка: " + message(e);
        }
    }

    private String rollbackOne(Generator g, String user) {
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
        try (SshConnection ssh = connect(g)) {
            StringBuilder cmd = new StringBuilder("rm -f ").append(CONF).append("; rc=0; ");
            for (Map.Entry<String, String> e : original.entrySet()) {
                String v = norm(e.getValue());
                if (!TuningAnalyzer.KEYS.contains(e.getKey()) || !VALUE.matcher(v).matches()) {
                    continue; // исходное значение не прочитано: параметр не трогаем
                }
                cmd.append("sysctl -w ").append(e.getKey()).append("=\"").append(v).append("\" >/dev/null || rc=1; ");
            }
            cmd.append("exit $rc");
            var r = ssh.sudo(cmd.toString(), Duration.ofSeconds(40));
            Map<String, String> after = read(ssh, new ArrayList<>(original.keySet()));
            List<String> notRestored = new ArrayList<>();
            original.forEach((k, v) -> {
                if (!norm(v).isEmpty() && !norm(after.getOrDefault(k, "")).equals(norm(v))) {
                    notRestored.add(k);
                }
            });
            store.markRolledBack(g.getId());
            audit.record("host.tuning.rollback", "generator", g.getId(), Map.of("by", String.valueOf(user)));
            if (!notRestored.isEmpty()) {
                return "откат выполнен, но не вернулись: " + String.join(", ", notRestored) + (r.stderr().isBlank() ? "" : " (" + r.stderr().strip() + ")");
            }
            return "возвращено параметров: " + original.size() + ", файл настроек удалён";
        } catch (RuntimeException e) {
            return "ошибка: " + message(e);
        }
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
            if (key != null && rec != null && TuningAnalyzer.KEYS.contains(key) && VALUE.matcher(norm(rec)).matches()) {
                out.put(key, norm(rec));
            }
        }
        return out;
    }

    private Map<String, String> read(SshConnection ssh, List<String> keys) {
        StringBuilder cmd = new StringBuilder();
        for (String k : keys) {
            if (TuningAnalyzer.KEYS.contains(k)) {
                cmd.append("echo \"").append(k).append("=$(sysctl -n ").append(k).append(" 2>/dev/null | tr '\\t' ' ')\"; ");
            }
        }
        Map<String, String> out = new LinkedHashMap<>();
        if (cmd.isEmpty()) {
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

    private SshConnection connect(Generator g) {
        if (g.getSshCredentialId() == null) {
            throw new IllegalStateException("У генератора не задана SSH-учётка");
        }
        return connector.open(g.getHost(), g.getSshPort(), credentials.resolve(g.getSshCredentialId()), CONNECT);
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
            futures.put(g, pool.submit(() -> task.apply(g)));
        }
        List<String> out = new ArrayList<>();
        futures.forEach((g, f) -> {
            try {
                out.add(g.getName() + ": " + f.get(120, TimeUnit.SECONDS));
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
