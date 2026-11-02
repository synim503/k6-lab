package ru.k6lab.server.deploy;

import org.springframework.stereotype.Component;
import ru.k6lab.server.agent.AgentSessionRegistry;
import ru.k6lab.server.agent.TokenService;
import ru.k6lab.server.common.SecretMasker;
import ru.k6lab.server.config.AppProperties;
import ru.k6lab.server.common.StorageService;
import ru.k6lab.server.distribution.DistributionArtifact;
import ru.k6lab.server.distribution.DistributionArtifact.Arch;
import ru.k6lab.server.distribution.DistributionArtifact.Kind;
import ru.k6lab.server.distribution.DistributionService;
import ru.k6lab.server.generator.Generator;
import ru.k6lab.server.generator.GeneratorRepository;
import ru.k6lab.server.ssh.ResolvedCredential;
import ru.k6lab.server.ssh.SshConnection;
import ru.k6lab.server.ssh.SshConnection.ExecResult;
import ru.k6lab.server.ssh.SshConnector;
import ru.k6lab.server.ssh.SshCredentialService;
import ru.k6lab.server.ssh.SshExceptions.SshFailure;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.regex.Pattern;

/**
 * Шаги установки, обновления, перезапуска и удаления агента по SSH. Идемпотентно: каждый шаг сначала сверяет
 * состояние хоста (хеши файлов, содержимое unit'ов) и меняет только то, что отличается.
 * SSH используется только для управления агентом - тесты запускаются через WebSocket.
 */
@Component
public class DeployEngine {

    static final Duration CONNECT = Duration.ofSeconds(20);
    static final Duration SHORT = Duration.ofSeconds(30);
    static final Duration MEDIUM = Duration.ofSeconds(120);

    private static final Pattern SAFE_PATH = Pattern.compile("^[A-Za-z0-9_./~-]+$");
    private static final Pattern SAFE_HOST = Pattern.compile("^[A-Za-z0-9._:-]+$");

    /** Параметры целевого хоста. */
    public record Target(Long generatorId, String name, String host, int port, Long credentialId,
                         Generator.InstallMode mode, String dir, String serverUrl, boolean tuneOs,
                         Generator.Supervisor supervisor) {
        boolean system() {
            return mode == Generator.InstallMode.SYSTEM;
        }
    }

    private final SshConnector connector;
    private final SshCredentialService credentials;
    private final DistributionService distributions;
    private final StorageService storage;
    private final TokenService tokens;
    private final DeployStore store;
    private final GeneratorRepository generators;
    private final AgentSessionRegistry registry;
    private final AppProperties props;

    public DeployEngine(SshConnector connector, SshCredentialService credentials, DistributionService distributions,
                        StorageService storage, TokenService tokens, DeployStore store,
                        GeneratorRepository generators, AgentSessionRegistry registry, AppProperties props) {
        this.connector = connector;
        this.credentials = credentials;
        this.distributions = distributions;
        this.storage = storage;
        this.tokens = tokens;
        this.store = store;
        this.generators = generators;
        this.registry = registry;
        this.props = props;
    }

    // ================================================================== вспомогательное

    /** Удалённый хост: логирует команды и их вывод. */
    private final class Remote {
        final SshConnection ssh;
        final DeployLog log;
        final Target target;
        /** Агент - бинарь Go (без JRE); задаётся после выбора артефактов. */
        boolean nativeAgent;

        Remote(SshConnection ssh, DeployLog log, Target target) {
            this.ssh = ssh;
            this.log = log;
            this.target = target;
        }

        ExecResult tryExec(String cmd, Duration timeout) {
            log.command(cmd);
            ExecResult r = ssh.exec(cmd, timeout);
            report(r);
            return r;
        }

        ExecResult trySudo(String cmd, Duration timeout) {
            log.command((target.system() && !ssh.credential().isRoot() ? "sudo " : "") + cmd);
            ExecResult r = target.system() ? ssh.sudo(cmd, timeout) : ssh.exec(cmd, timeout);
            report(r);
            return r;
        }

        /** Команда с правами root в системном режиме, обычная - в пользовательском. Ошибка при ненулевом коде. */
        String priv(String cmd, Duration timeout) {
            return check(trySudo(cmd, timeout), cmd);
        }

        String exec(String cmd, Duration timeout) {
            return check(tryExec(cmd, timeout), cmd);
        }

        private void report(ExecResult r) {
            log.output(limit(r.stdout()));
            log.output(limit(r.stderr()));
        }

        private String check(ExecResult r, String cmd) {
            if (!r.ok()) {
                String why = (r.stderr().isBlank() ? r.stdout() : r.stderr()).strip();
                throw new SshFailure("Команда завершилась с кодом " + r.exitCode()
                        + (why.isEmpty() ? "" : ": " + limit(why)));
            }
            return r.stdout().strip();
        }
    }

    private static String limit(String s) {
        if (s == null) {
            return "";
        }
        String[] lines = s.stripTrailing().split("\\R");
        if (lines.length <= 40) {
            return s;
        }
        return String.join("\n", Arrays.copyOfRange(lines, 0, 20)) + "\n    ... (" + (lines.length - 40)
                + " строк пропущено) ...\n" + String.join("\n", Arrays.copyOfRange(lines, lines.length - 20, lines.length));
    }

    private static String q(String s) {
        return SshConnection.quote(s);
    }

    static void validate(Target t) {
        if (!SAFE_HOST.matcher(t.host()).matches()) {
            throw new IllegalArgumentException("Недопустимое имя хоста: " + t.host());
        }
        if (t.port() < 1 || t.port() > 65535) {
            throw new IllegalArgumentException("Недопустимый порт SSH");
        }
        if (t.dir() == null || !SAFE_PATH.matcher(t.dir()).matches() || t.dir().length() < 4 || t.dir().equals("/")) {
            throw new IllegalArgumentException("Недопустимый каталог установки (разрешены буквы, цифры и _ . / ~ -): " + t.dir());
        }
        if (t.system() && !t.dir().startsWith("/")) {
            throw new IllegalArgumentException("Для системного режима нужен абсолютный каталог");
        }
        if (t.name() == null || !t.name().matches("^[A-Za-z0-9_.-]{1,100}$")) {
            throw new IllegalArgumentException("Имя генератора: латиница, цифры и _ . -");
        }
        try {
            URI u = URI.create(t.serverUrl());
            if (u.getHost() == null || u.getPort() < 1 || !(u.getScheme().equals("ws") || u.getScheme().equals("wss"))) {
                throw new IllegalArgumentException();
            }
            if (!SAFE_HOST.matcher(u.getHost()).matches()) {
                throw new IllegalArgumentException();
            }
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("URL сервера должен быть вида ws://host:порт/agent/ws");
        }
    }

    private SshConnection connect(Target t, DeployLog log) {
        ResolvedCredential cred = credentials.resolve(t.credentialId());
        log.masker().add(cred.secret(), cred.passphrase(), cred.sudoPassword());
        if (cred.jump() != null) {
            log.masker().add(cred.jump().secret(), cred.jump().passphrase(), cred.jump().sudoPassword());
        }
        log.line("Подключение " + cred.username() + "@" + t.host() + ":" + t.port()
                + (cred.jumpHost() != null ? " через jump-host " + cred.jumpHost() : ""));
        return connector.open(t.host(), t.port(), cred, CONNECT);
    }

    // ================================================================== pre-flight

    /** Шаги 1-2: подключение и проверки. Соединение возвращается открытым вызывающему. */
    PreflightReport preflight(DeployRun run, Target t, boolean strict, java.util.function.Consumer<SshConnection> keep,
                              long requiredKb) throws Exception {
        DeployLog log = run.log();
        SshConnection ssh = run.step("Подключение по SSH, проверка ключа хоста", () -> connect(t, log));
        keep.accept(ssh);
        Remote r = new Remote(ssh, log, t);
        PreflightReport rep = run.step("Pre-flight проверки хоста", () -> doPreflight(r, t, requiredKb));
        for (String w : rep.warnings) {
            log.line("⚠ " + w);
        }
        for (String e : rep.errors) {
            log.line("✖ " + e);
        }
        if (!rep.ok() && strict) {
            throw new SshFailure("Pre-flight не пройден: " + String.join("; ", rep.errors));
        }
        return rep;
    }

    private PreflightReport doPreflight(Remote r, Target t, long requiredKb) {
        PreflightReport rep = new PreflightReport();
        String uname = r.exec("uname -s -m", SHORT);
        rep.uname = uname;
        String[] parts = uname.split("\\s+");
        if (parts.length < 2 || !parts[0].equals("Linux")) {
            rep.error("Поддерживается только Linux (получено: " + uname + ")");
            return rep;
        }
        rep.arch = Arch.fromUname(parts[1]);
        if (rep.arch == null) {
            rep.error("Архитектура " + parts[1] + " не поддерживается (нужны x86_64 или aarch64)");
        }
        rep.osName = r.tryExec("sh -c '. /etc/os-release 2>/dev/null && echo \"$PRETTY_NAME\"'", SHORT).stdoutTrim();
        rep.home = r.exec("echo \"$HOME\"", SHORT);
        rep.systemd = r.tryExec("test -d /run/systemd/system", SHORT).ok();

        for (String tool : new String[]{"tar", "sha256sum"}) {
            if (!r.tryExec("command -v " + tool, SHORT).ok()) {
                rep.error("На хосте нет утилиты " + tool);
            }
        }

        if (t.system()) {
            if (!rep.systemd) {
                rep.error("Системный режим требует systemd (каталог /run/systemd/system не найден)");
            }
            ExecResult sudo = r.trySudo("id -u", SHORT);
            if (!sudo.ok() || !sudo.stdoutTrim().equals("0")) {
                rep.sudoOk = false;
                rep.error("sudo не работает для этой учётки (" + (sudo.stderr().isBlank() ? "код " + sudo.exitCode()
                        : sudo.stderr().strip()) + "). Проверьте пароль sudo в SSH-учётке или права в sudoers.");
            }
            for (String tool : new String[]{"useradd", "install", "systemctl"}) {
                if (rep.sudoOk && !r.trySudo("command -v " + tool, SHORT).ok()) {
                    rep.error("На хосте нет утилиты " + tool);
                }
            }
            rep.supervisor = Generator.Supervisor.SYSTEMD;
        } else {
            String l = r.tryExec("loginctl show-user \"$(id -un)\" -p Linger 2>/dev/null", SHORT).stdoutTrim();
            rep.linger = l.contains("Linger=yes");
            rep.cron = r.tryExec("command -v crontab", SHORT).ok();
            boolean userSystemd = rep.linger && r.tryExec(
                    "XDG_RUNTIME_DIR=/run/user/$(id -u) systemctl --user show-environment", SHORT).ok();
            if (userSystemd) {
                rep.supervisor = Generator.Supervisor.SYSTEMD_USER;
            } else if (rep.cron) {
                rep.supervisor = Generator.Supervisor.CRON;
                rep.warn("Linger у пользователя выключен: агент будет запускаться через cron (скрипт-супервизор: "
                        + "@reboot + watchdog раз в минуту). Это менее надёжно, чем systemd. Чтобы включить systemd --user, "
                        + "выполните на хосте от root: loginctl enable-linger <пользователь>");
            } else {
                rep.error("Нет ни systemd --user с linger, ни cron - агенту нечем обеспечить автозапуск");
            }
        }

        // свободное место (на ближайшем существующем предке каталога установки)
        String dir = t.dir().startsWith("~") ? rep.home + t.dir().substring(1) : t.dir();
        String df = r.tryExec("d=" + q(dir) + "; while [ ! -e \"$d\" ]; do d=$(dirname \"$d\"); done; df -Pk \"$d\" | awk 'NR==2{print $4}'",
                SHORT).stdoutTrim();
        try {
            rep.diskFreeKb = Long.parseLong(df);
            if (rep.diskFreeKb < requiredKb) {
                rep.error("Недостаточно места: свободно " + rep.diskFreeKb / 1024 + " МБ, нужно не менее "
                        + requiredKb / 1024 + " МБ");
            }
        } catch (NumberFormatException e) {
            rep.warn("Не удалось определить свободное место");
        }
        rep.existingInstall = r.tryExec("test -f " + q(dir + "/agent.jar") + " || test -f " + q(dir + "/k6-agent"), SHORT).ok();

        // доступность порта агентов на сервере
        URI u = URI.create(t.serverUrl());
        String check = "H=" + q(u.getHost()) + "; P=" + u.getPort() + "; "
                + "if command -v nc >/dev/null 2>&1; then nc -z -w 5 \"$H\" \"$P\"; "
                + "elif command -v bash >/dev/null 2>&1; then timeout 5 bash -c \"exec 3<>/dev/tcp/$H/$P\"; "
                + "elif command -v curl >/dev/null 2>&1; then curl -s -m 5 -o /dev/null \"http://$H:$P/\"; "
                + "else exit 99; fi";
        ExecResult pr = r.tryExec(check, SHORT);
        if (pr.exitCode() == 99) {
            rep.warn("Нет nc/bash/curl - доступность порта агентов с генератора не проверена");
        } else if (pr.ok()) {
            rep.portReachable = true;
        } else {
            rep.portReachable = false;
            rep.error("С генератора недоступен " + u.getHost() + ":" + u.getPort()
                    + " (порт агентов на сервере админки). Проверьте файрвол и URL сервера.");
        }

        // расхождение часов
        long t0 = System.currentTimeMillis();
        ExecResult date = r.tryExec("date +%s%3N", SHORT);
        long t1 = System.currentTimeMillis();
        String d = date.stdoutTrim();
        if (date.ok() && d.matches("\\d{13}")) {
            long skew = Long.parseLong(d) - (t0 + t1) / 2;
            rep.clockSkewMs = skew;
            long abs = Math.abs(skew);
            if (abs > 2000) {
                rep.error("Часы генератора расходятся с сервером на " + skew + " мс (> 2 с). Настройте синхронизацию "
                        + "времени, например chrony: apt install chrony / dnf install chrony, systemctl enable --now chronyd");
            } else if (abs > 500) {
                rep.warn("Часы генератора расходятся с сервером на " + skew + " мс (> 500 мс): синхронный старт "
                        + "будет заблокирован. Рекомендуется настроить chrony.");
            }
        } else {
            rep.warn("Не удалось точно измерить расхождение часов (date +%s%3N недоступно)");
        }
        log(r.log, rep);
        return rep;
    }

    private static void log(DeployLog log, PreflightReport rep) {
        log.line("Итог pre-flight: " + rep.uname + ", " + rep.osName + ", systemd=" + rep.systemd
                + (rep.supervisor != null ? ", запуск: " + rep.supervisor : "")
                + (rep.clockSkewMs != null ? ", смещение часов " + rep.clockSkewMs + " мс" : "")
                + (rep.diskFreeKb >= 0 ? ", свободно " + rep.diskFreeKb / 1024 + " МБ" : ""));
    }

    // ================================================================== артефакты

    record Artifacts(DistributionArtifact agent, DistributionArtifact k6, DistributionArtifact jre) {
    }

    private Artifacts pickArtifacts(Arch arch, Long agentId, Long k6Id, boolean needJre) {
        // агент: явно выбранный, иначе бинарь Go под архитектуру хоста, иначе Java-агент (jar)
        DistributionArtifact agent = agentId != null ? distributions.find(agentId).orElseThrow()
                : distributions.defaultFor(Kind.AGENT, arch).or(() -> distributions.defaultFor(Kind.AGENT, Arch.ANY)).orElse(null);
        DistributionArtifact k6 = k6Id != null ? distributions.find(k6Id).orElseThrow()
                : distributions.defaultFor(Kind.K6, arch).orElse(null);
        boolean nativeAgent = agent != null && agent.isNativeAgent();
        needJre = needJre && !nativeAgent; // бинарю Go JRE не нужна
        DistributionArtifact jre = needJre ? distributions.defaultFor(Kind.JRE, arch).orElse(null) : null;
        StringBuilder missing = new StringBuilder();
        if (agent != null && agent.isNativeAgent() && agent.getArch() != arch && agent.getArch() != Arch.ANY) {
            throw new SshFailure("Выбранный агент собран для " + agent.getArch().getTitle() + ", а хост - " + arch.getTitle());
        }
        if (agent == null) {
            missing.append("агент (бинарь Go или jar); ");
        }
        if (k6 == null) {
            missing.append("k6 для ").append(arch.getTitle()).append("; ");
        }
        if (needJre && jre == null) {
            missing.append("JRE 21 (tar.gz) для ").append(arch.getTitle()).append("; ");
        }
        if (!missing.isEmpty()) {
            throw new SshFailure("Не загружены дистрибутивы: " + missing + "загрузите их в разделе «Дистрибутивы» и "
                    + "отметьте версией по умолчанию");
        }
        if (k6.getArch() != arch && k6.getArch() != Arch.ANY) {
            throw new SshFailure("Выбранный k6 собран для " + k6.getArch().getTitle() + ", а хост - " + arch.getTitle());
        }
        return new Artifacts(agent, k6, jre);
    }

    private static long requiredKb(Artifacts a) {
        long bytes = a.agent().getSizeBytes() + a.k6().getSizeBytes() * 2
                + (a.jre() == null ? 0 : a.jre().getSizeBytes() * 3);
        return bytes / 1024 + 100 * 1024;
    }

    /** Заливает файл в stage (если отличается от установленного) и проверяет sha256 на хосте. */
    private boolean deliver(Remote r, DistributionArtifact art, String installedPath, String stagePath) {
        String remote = r.tryExec("sha256sum " + q(installedPath) + " 2>/dev/null | cut -d' ' -f1", SHORT).stdoutTrim();
        if (remote.equals(art.getSha256())) {
            r.log.line(art.getFilename() + ": на хосте уже актуальная версия (sha256 совпадает) - пропускаем");
            return false;
        }
        upload(r, art, stagePath);
        return true;
    }

    private void upload(Remote r, DistributionArtifact art, String stagePath) {
        r.log.line("Загрузка " + art.getFilename() + " (" + art.getSizeBytes() / 1024 / 1024 + " МБ) по SFTP...");
        try (InputStream in = storage.open(art.getStorageKey())) {
            r.ssh.upload(in, stagePath);
        } catch (IOException e) {
            throw new SshFailure("Не удалось прочитать дистрибутив из хранилища: " + e.getMessage(), e);
        }
        String sha = r.exec("sha256sum " + q(stagePath) + " | cut -d' ' -f1", MEDIUM);
        if (!sha.equals(art.getSha256())) {
            throw new SshFailure("sha256 на хосте (" + sha + ") не совпадает с ожидаемым (" + art.getSha256() + ")");
        }
        r.log.line("sha256 проверен: " + sha);
    }

    private static String ownerOpts(Target t) {
        return t.system() ? "-o " + Units.SYSTEM_USER + " -g " + Units.SYSTEM_USER + " " : "";
    }

    // ================================================================== INSTALL

    public PreflightReport runPreflight(DeployRun run, Target t) throws Exception {
        validate(t);
        SshConnection[] holder = new SshConnection[1];
        try {
            PreflightReport rep = preflight(run, t, false, c -> holder[0] = c, 100 * 1024);
            if (!rep.ok()) {
                throw new SshFailure("Pre-flight выявил проблемы: " + String.join("; ", rep.errors));
            }
            return rep;
        } finally {
            if (holder[0] != null) {
                holder[0].close();
            }
        }
    }

    /** Результат установки: режим запуска, определённый pre-flight. */
    public record InstallResult(Generator.Supervisor supervisor, Arch arch, String osName, String dir) {
    }

    public InstallResult install(DeployRun run, Target t, Long agentArtifactId, Long k6ArtifactId) throws Exception {
        validate(t);
        DeployLog log = run.log();
        SshConnection[] holder = new SshConnection[1];
        boolean serviceTouched = false;
        PreflightReport rep = null;
        Remote remote = null;
        String dir = t.dir();
        try {
            rep = preflight(run, t, true, c -> holder[0] = c, 600 * 1024);
            final PreflightReport report = rep;
            remote = new Remote(holder[0], log, t);
            final Remote r = remote;
            final Target target = t.dir().startsWith("~")
                    ? new Target(t.generatorId(), t.name(), t.host(), t.port(), t.credentialId(), t.mode(),
                    rep.home + t.dir().substring(1), t.serverUrl(), t.tuneOs(), rep.supervisor)
                    : new Target(t.generatorId(), t.name(), t.host(), t.port(), t.credentialId(), t.mode(),
                    t.dir(), t.serverUrl(), t.tuneOs(), rep.supervisor);
            dir = target.dir();
            final String d = dir;
            Artifacts arts = pickArtifacts(rep.arch, agentArtifactId, k6ArtifactId, true);
            r.nativeAgent = arts.agent().isNativeAgent();
            if (requiredKb(arts) > rep.diskFreeKb && rep.diskFreeKb >= 0) {
                throw new SshFailure("Недостаточно места для установки: нужно " + requiredKb(arts) / 1024 + " МБ");
            }
            String stage = target.system() ? "/tmp/k6lab-stage-" + run.id() : d + "/.stage";

            run.stage("Пользователь и каталоги", () -> {
                if (target.system()) {
                    r.priv("id " + Units.SYSTEM_USER + " >/dev/null 2>&1 || useradd --system --home-dir " + q(d)
                            + " --no-create-home --shell /usr/sbin/nologin " + Units.SYSTEM_USER, SHORT);
                    r.priv("mkdir -p " + q(d + "/bin") + " " + q(d + "/data") + " " + q(d + "/logs")
                            + " && chown -R " + Units.SYSTEM_USER + ":" + Units.SYSTEM_USER + " " + q(d)
                            + " && chmod 755 " + q(d), SHORT);
                } else {
                    r.exec("mkdir -p " + q(d + "/bin") + " " + q(d + "/data") + " " + q(d + "/logs"), SHORT);
                }
                r.exec("rm -rf " + q(stage) + " && mkdir -p " + q(stage) + " && chmod 700 " + q(stage), SHORT);
            });

            run.stage("Доставка артефактов (SFTP + проверка sha256)", () -> {
                String own = ownerOpts(target);
                String agentFile = r.nativeAgent ? "k6-agent" : "agent.jar";
                if (deliver(r, arts.agent(), d + "/" + agentFile, stage + "/" + agentFile)) {
                    r.priv("install -m " + (r.nativeAgent ? "755" : "644") + " " + own + q(stage + "/" + agentFile) + " " + q(d + "/" + agentFile), SHORT);
                }
                if (deliver(r, arts.k6(), d + "/bin/k6", stage + "/k6")) {
                    r.priv("install -m 755 " + own + q(stage + "/k6") + " " + q(d + "/bin/k6"), SHORT);
                }
                if (arts.jre() == null) {
                    r.log.line("Агент - самостоятельный бинарь: JRE на генераторе не нужна");
                } else {
                String marker = r.tryExec("cat " + q(d + "/jre/.archive-sha256") + " 2>/dev/null", SHORT).stdoutTrim();
                if (marker.equals(arts.jre().getSha256()) && r.tryExec("test -x " + q(d + "/jre/bin/java"), SHORT).ok()) {
                    r.log.line("JRE: на хосте уже актуальная версия - пропускаем");
                } else {
                    upload(r, arts.jre(), stage + "/jre.tar.gz");
                    r.priv("rm -rf " + q(d + "/jre.new") + " && mkdir -p " + q(d + "/jre.new")
                            + " && tar -xzf " + q(stage + "/jre.tar.gz") + " -C " + q(d + "/jre.new") + " --strip-components=1"
                            + " && echo " + arts.jre().getSha256() + " > " + q(d + "/jre.new/.archive-sha256")
                            + " && rm -rf " + q(d + "/jre") + " && mv " + q(d + "/jre.new") + " " + q(d + "/jre")
                            + (target.system() ? " && chown -R " + Units.SYSTEM_USER + ":" + Units.SYSTEM_USER + " " + q(d + "/jre") : ""),
                            MEDIUM);
                }
                String ver = r.tryExec(q(d + "/jre/bin/java") + " -version 2>&1 | head -1", SHORT).stdoutTrim();
                r.log.line("JRE на хосте: " + ver);
                }
                String k6v = r.tryExec(q(d + "/bin/k6") + " version 2>&1 | head -1", SHORT).stdoutTrim();
                r.log.line("k6 на хосте: " + k6v);
            });

            String oneTime = run.step("Конфигурация агента (одноразовый токен, права 600)", () -> {
                String token = tokens.issueOneTime(target.generatorId());
                log.masker().add(token);
                r.ssh.uploadText(Units.agentConfig(target.serverUrl(), token, target.name(), d), stage + "/agent.properties");
                r.priv("install -m 600 " + ownerOpts(target) + q(stage + "/agent.properties") + " " + q(d + "/agent.properties"), SHORT);
                return token;
            });

            serviceTouched = true;
            Instant since = Instant.now().minusSeconds(1);
            run.stage("Запуск как сервиса (" + report.supervisor + ")", () -> startService(r, target, stage));

            if (target.system() && target.tuneOs()) {
                run.stage("Тюнинг ОС (sysctl)", () -> {
                    r.ssh.uploadText(Units.sysctl(), stage + "/99-k6.conf");
                    r.priv("install -m 644 " + q(stage + "/99-k6.conf") + " " + Units.SYSCTL_FILE, SHORT);
                    ExecResult sysctl = r.trySudo("sysctl -p " + Units.SYSCTL_FILE, SHORT);
                    if (!sysctl.ok()) {
                        log.line("⚠ sysctl применился не полностью (в контейнерах это нормально): "
                                + sysctl.stderr().strip());
                    }
                });
            } else {
                run.skipped("Тюнинг ОС (sysctl)", target.system() ? "не запрошен" : "только для системного режима");
            }

            run.stage("Ожидание регистрации агента (до " + props.getDeploy().getRegistrationTimeout().toSeconds() + " с)",
                    () -> awaitRegistration(r, target, since));
            r.exec("rm -rf " + q(stage), SHORT);
            log.line("Установка завершена: агент " + target.name() + " онлайн");
            return new InstallResult(report.supervisor, report.arch, report.osName, d);
        } catch (Exception e) {
            if (serviceTouched && remote != null) {
                rollbackInstall(remote, run, t, dir, rep == null ? null : rep.supervisor);
                throw new RolledBack(e);
            }
            throw e;
        } finally {
            if (holder[0] != null) {
                holder[0].close();
            }
        }
    }

    /** Установка не удалась на шаге 6+ и откатана. */
    public static class RolledBack extends Exception {
        public RolledBack(Throwable cause) {
            super(cause.getMessage(), cause);
        }
    }

    private void startService(Remote r, Target t, String stage) {
        String d = t.dir();
        switch (t.supervisor()) {
            case SYSTEMD -> {
                r.ssh.uploadText(Units.systemUnit(d, r.nativeAgent), stage + "/k6-agent.service");
                r.priv("install -m 644 -o root -g root " + q(stage + "/k6-agent.service")
                        + " /etc/systemd/system/k6-agent.service", SHORT);
                r.priv("systemctl daemon-reload && systemctl enable k6-agent && systemctl restart k6-agent", MEDIUM);
                r.priv("sleep 2; systemctl is-active k6-agent", SHORT);
            }
            case SYSTEMD_USER -> {
                String xdg = "export XDG_RUNTIME_DIR=/run/user/$(id -u); ";
                r.exec("mkdir -p \"$HOME/.config/systemd/user\"", SHORT);
                r.ssh.uploadText(Units.userUnit(d, r.nativeAgent), stage + "/k6-agent.service");
                r.exec("install -m 644 " + q(stage + "/k6-agent.service") + " \"$HOME/.config/systemd/user/k6-agent.service\"", SHORT);
                r.exec(xdg + "systemctl --user daemon-reload && systemctl --user enable k6-agent && systemctl --user restart k6-agent", MEDIUM);
                r.exec(xdg + "sleep 2; systemctl --user is-active k6-agent", SHORT);
            }
            case CRON -> {
                r.ssh.uploadText(Units.ctlScript(r.nativeAgent), stage + "/agent-ctl.sh");
                r.exec("install -m 755 " + q(stage + "/agent-ctl.sh") + " " + q(d + "/bin/agent-ctl.sh"), SHORT);
                r.exec(q(d + "/bin/agent-ctl.sh") + " restart", MEDIUM);
                String ctl = d + "/bin/agent-ctl.sh";
                r.exec("( crontab -l 2>/dev/null | grep -v " + q(Units.CRON_MARK) + "; "
                        + "printf '%s\\n' " + q("@reboot " + ctl + " start # " + Units.CRON_MARK) + " "
                        + q("* * * * * " + ctl + " ensure # " + Units.CRON_MARK) + " ) | crontab -", SHORT);
                r.exec("sleep 2; " + q(ctl) + " status", SHORT);
            }
        }
    }

    private void stopService(Remote r, Target t) {
        String d = t.dir();
        switch (t.supervisor()) {
            case SYSTEMD -> r.trySudo("systemctl stop k6-agent", MEDIUM);
            case SYSTEMD_USER -> r.tryExec("export XDG_RUNTIME_DIR=/run/user/$(id -u); systemctl --user stop k6-agent", MEDIUM);
            case CRON -> r.tryExec(q(d + "/bin/agent-ctl.sh") + " stop", MEDIUM);
        }
    }

    private void startOnly(Remote r, Target t) {
        String d = t.dir();
        switch (t.supervisor()) {
            case SYSTEMD -> r.priv("systemctl start k6-agent", MEDIUM);
            case SYSTEMD_USER -> r.exec("export XDG_RUNTIME_DIR=/run/user/$(id -u); systemctl --user start k6-agent", MEDIUM);
            case CRON -> r.exec(q(d + "/bin/agent-ctl.sh") + " start", MEDIUM);
        }
    }

    private void awaitRegistration(Remote r, Target t, Instant since) throws Exception {
        long deadline = System.currentTimeMillis() + props.getDeploy().getRegistrationTimeout().toMillis();
        while (System.currentTimeMillis() < deadline) {
            Generator g = generators.findById(t.generatorId()).orElse(null);
            // одноразовый токен гасится при первом подключении с постоянным: значит, агент токен сохранил и работает
            if (g != null && g.getLastHelloAt() != null && g.getLastHelloAt().isAfter(since)
                    && g.getOneTimeTokenHash() == null && registry.isConnected(t.generatorId())) {
                r.log.line("Агент подключился: версия агента " + g.getAgentVersion() + ", k6 " + g.getK6Version());
                return;
            }
            Thread.sleep(1000);
        }
        r.log.line("Агент не зарегистрировался за отведённое время. Последние строки журнала агента:");
        r.tryExec("tail -n 30 " + q(t.dir() + "/logs/agent.log") + " 2>&1", SHORT);
        throw new SshFailure("Агент не зарегистрировался на сервере за "
                + props.getDeploy().getRegistrationTimeout().toSeconds() + " с (проверьте URL сервера и файрвол)");
    }

    private void rollbackInstall(Remote r, DeployRun run, Target t, String dir, Generator.Supervisor supervisor) {
        DeployLog log = run.log();
        log.line("⟲ Откат установки: останавливаем и удаляем сервис");
        try {
            Target target = new Target(t.generatorId(), t.name(), t.host(), t.port(), t.credentialId(), t.mode(), dir,
                    t.serverUrl(), t.tuneOs(), supervisor);
            if (supervisor != null) {
                disableService(r, target);
            }
            log.line("Откат завершён (файлы в " + dir + " оставлены для повторной идемпотентной установки)");
        } catch (Exception e) {
            log.line("Не удалось выполнить откат полностью: " + e.getMessage());
        }
    }

    private void disableService(Remote r, Target t) {
        String d = t.dir();
        switch (t.supervisor()) {
            case SYSTEMD -> r.trySudo("systemctl disable --now k6-agent; rm -f /etc/systemd/system/k6-agent.service; "
                    + "systemctl daemon-reload; systemctl reset-failed k6-agent", MEDIUM);
            case SYSTEMD_USER -> r.tryExec("export XDG_RUNTIME_DIR=/run/user/$(id -u); systemctl --user disable --now k6-agent; "
                    + "rm -f \"$HOME/.config/systemd/user/k6-agent.service\"; systemctl --user daemon-reload; "
                    + "systemctl --user reset-failed k6-agent", MEDIUM);
            case CRON -> {
                r.tryExec(q(d + "/bin/agent-ctl.sh") + " stop", MEDIUM);
                r.tryExec("crontab -l 2>/dev/null | grep -v " + q(Units.CRON_MARK) + " | crontab -", SHORT);
            }
        }
    }

    // ================================================================== UPDATE

    public void update(DeployRun run, Target t, Long agentArtifactId, Long k6ArtifactId) throws Exception {
        validate(t);
        if (t.supervisor() == null) {
            throw new SshFailure("Неизвестен способ запуска агента на хосте - выполните установку заново");
        }
        DeployLog log = run.log();
        SshConnection[] holder = new SshConnection[1];
        try {
            PreflightReport rep = preflight(run, t, true, c -> holder[0] = c, 600 * 1024);
            Remote r = new Remote(holder[0], log, t);
            Artifacts arts = pickArtifacts(rep.arch, agentArtifactId, k6ArtifactId, false);
            final String agentFile = arts.agent().isNativeAgent() ? "k6-agent" : "agent.jar";
            final String agentMode = arts.agent().isNativeAgent() ? "755" : "644";
            String d = t.dir().startsWith("~") ? rep.home + t.dir().substring(1) : t.dir();
            Target target = new Target(t.generatorId(), t.name(), t.host(), t.port(), t.credentialId(), t.mode(), d,
                    t.serverUrl(), t.tuneOs(), t.supervisor());
            String stage = target.system() ? "/tmp/k6lab-stage-" + run.id() : d + "/.stage";
            String own = ownerOpts(target);
            boolean[] changed = new boolean[2];

            run.stage("Подготовка новых файлов", () -> {
                r.exec("rm -rf " + q(stage) + " && mkdir -p " + q(stage) + " && chmod 700 " + q(stage), SHORT);
                changed[0] = deliver(r, arts.agent(), d + "/" + agentFile, stage + "/" + agentFile);
                changed[1] = deliver(r, arts.k6(), d + "/bin/k6", stage + "/k6");
                if (!changed[0] && !changed[1]) {
                    log.line("Версии на хосте уже совпадают с выбранными - заменять нечего");
                }
            });

            Instant since = Instant.now().minusSeconds(1);
            boolean[] replaced = new boolean[1];
            try {
                run.stage("Остановка, замена файлов, запуск", () -> {
                    stopService(r, target);
                    replaced[0] = true;
                    if (changed[0]) {
                        r.priv("cp -a " + q(d + "/" + agentFile) + " " + q(d + "/" + agentFile + ".bak") + " 2>/dev/null; "
                                + "install -m " + agentMode + " " + own + q(stage + "/" + agentFile) + " " + q(d + "/" + agentFile), SHORT);
                    }
                    if (changed[1]) {
                        r.priv("cp -a " + q(d + "/bin/k6") + " " + q(d + "/bin/k6.bak") + " 2>/dev/null; "
                                + "install -m 755 " + own + q(stage + "/k6") + " " + q(d + "/bin/k6"), SHORT);
                    }
                    startOnly(r, target);
                });
                run.stage("Ожидание переподключения агента", () -> awaitRegistration(r, target, since));
            } catch (Exception e) {
                if (replaced[0]) {
                    log.line("⟲ Откат: возвращаем предыдущие файлы");
                    try {
                        stopService(r, target);
                        if (changed[0]) {
                            r.trySudo("[ -f " + q(d + "/" + agentFile + ".bak") + " ] && cp -a " + q(d + "/" + agentFile + ".bak") + " " + q(d + "/" + agentFile), SHORT);
                        }
                        if (changed[1]) {
                            r.trySudo("[ -f " + q(d + "/bin/k6.bak") + " ] && cp -a " + q(d + "/bin/k6.bak") + " " + q(d + "/bin/k6"), SHORT);
                        }
                        startOnly(r, target);
                        log.line("Предыдущая версия запущена");
                    } catch (Exception re) {
                        log.line("Откат не удался: " + re.getMessage());
                    }
                    throw new RolledBack(e);
                }
                throw e;
            }
            r.tryExec("rm -rf " + q(stage), SHORT);
            log.line("Обновление завершено");
        } finally {
            if (holder[0] != null) {
                holder[0].close();
            }
        }
    }

    // ================================================================== RESTART / UNINSTALL

    public void restart(DeployRun run, Target t) throws Exception {
        validate(t);
        if (t.supervisor() == null) {
            throw new SshFailure("Неизвестен способ запуска агента на хосте");
        }
        SshConnection[] holder = new SshConnection[1];
        try {
            holder[0] = run.step("Подключение по SSH, проверка ключа хоста", () -> connect(t, run.log()));
            Remote r = new Remote(holder[0], run.log(), t);
            Instant since = Instant.now().minusSeconds(1);
            run.stage("Перезапуск сервиса", () -> {
                switch (t.supervisor()) {
                    case SYSTEMD -> r.priv("systemctl restart k6-agent", MEDIUM);
                    case SYSTEMD_USER -> r.exec("export XDG_RUNTIME_DIR=/run/user/$(id -u); systemctl --user restart k6-agent", MEDIUM);
                    case CRON -> r.exec(q(t.dir() + "/bin/agent-ctl.sh") + " restart", MEDIUM);
                }
            });
            run.stage("Ожидание переподключения агента", () -> awaitRegistration(r, t, since));
        } finally {
            if (holder[0] != null) {
                holder[0].close();
            }
        }
    }

    public void uninstall(DeployRun run, Target t) throws Exception {
        validate(t);
        SshConnection[] holder = new SshConnection[1];
        try {
            holder[0] = run.step("Подключение по SSH, проверка ключа хоста", () -> connect(t, run.log()));
            Remote r = new Remote(holder[0], run.log(), t);
            Generator.Supervisor sup = t.supervisor() != null ? t.supervisor()
                    : (t.system() ? Generator.Supervisor.SYSTEMD : Generator.Supervisor.CRON);
            Target target = new Target(t.generatorId(), t.name(), t.host(), t.port(), t.credentialId(), t.mode(),
                    t.dir(), t.serverUrl(), t.tuneOs(), sup);
            run.stage("Остановка и удаление сервиса", () -> disableService(r, target));
            run.stage("Удаление файлов агента", () -> {
                String dir = target.dir();
                String abs = dir.startsWith("~") ? "\"$HOME" + dir.substring(1) + "\"" : q(dir);
                // защита от катастрофы: удаляем только каталог, где реально лежит агент
                r.priv("[ -f " + abs + "/agent.jar ] || [ -f " + abs + "/k6-agent ] || [ -f " + abs + "/agent.properties ] || "
                        + "{ echo 'в каталоге нет файлов агента - не удаляем'; exit 0; }; rm -rf " + abs, SHORT);
                if (target.system() && target.tuneOs()) {
                    r.trySudo("rm -f " + Units.SYSCTL_FILE, SHORT);
                }
            });
        } finally {
            if (holder[0] != null) {
                holder[0].close();
            }
        }
    }

    /** Последние N строк журнала агента (синхронно, для карточки генератора). */
    public String tailAgentLog(Target t, int lines) {
        validate(t);
        DeployLog quiet = new DeployLog(0, new SecretMasker(), "");
        try (SshConnection ssh = connect(t, quiet)) {
            String dir = t.dir().startsWith("~") ? "\"$HOME" + t.dir().substring(1) + "\"" : q(t.dir());
            ExecResult r = ssh.exec("tail -n " + Math.max(1, Math.min(lines, 2000)) + " " + dir + "/logs/agent.log 2>&1", SHORT);
            return quiet.masker().mask(r.stdout() + r.stderr());
        }
    }
}
