package ru.k6lab.server;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.testcontainers.images.builder.ImageFromDockerfile;
import ru.k6lab.server.agent.AgentSessionRegistry;
import ru.k6lab.server.deploy.DeployService;
import ru.k6lab.server.deploy.Deployment;
import ru.k6lab.server.deploy.DeploymentRepository;
import ru.k6lab.server.distribution.DistributionArtifact.Arch;
import ru.k6lab.server.distribution.DistributionArtifact.Kind;
import ru.k6lab.server.distribution.DistributionService;
import ru.k6lab.server.generator.Generator;
import ru.k6lab.server.generator.GeneratorRepository;
import ru.k6lab.server.ssh.KnownHostRepository;
import ru.k6lab.server.ssh.SshCredential;
import ru.k6lab.server.ssh.SshCredentialService;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Сквозной тест SSH-деплоя на контейнере Debian 12 с systemd и sshd: pre-flight, подтверждение host key,
 * установка в системном (sudo с паролем) и пользовательском (cron, linger выключен) режимах, перезапуск,
 * идемпотентное обновление, удаление, защита от подмены ключа хоста, маскирование секретов.
 * <p>
 * Нужны: Docker, собранный агент (admin-agent/target/k6-agent.jar) и интернет для скачивания JRE 21 (кешируется
 * в target/it-cache). Если чего-то нет - тест пропускается, а не падает.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class SshDeployIT extends AbstractIT {

    private static final String SUDO_PASSWORD = "S3cretPw!x";
    private static final String CONTAINER_HOST = "host.docker.internal";

    static Gen gen;
    static Path agentJar;
    static Path jreTar;

    @Autowired
    DeployService deploy;
    @Autowired
    DeploymentRepository deployments;
    @Autowired
    GeneratorRepository generators;
    @Autowired
    SshCredentialService credentials;
    @Autowired
    DistributionService distributions;
    @Autowired
    KnownHostRepository knownHosts;
    @Autowired
    AgentSessionRegistry registry;

    static Long systemCred;
    static Long userCred;
    static Long systemGen;

    @BeforeAll
    static void startContainer() throws Exception {
        agentJar = Path.of("../admin-agent/target/k6-agent.jar").toAbsolutePath().normalize();
        Assumptions.assumeTrue(org.testcontainers.DockerClientFactory.instance().isDockerAvailable(), "Docker недоступен: SSH-деплой проверяется на контейнерах Debian с systemd");
        Assumptions.assumeTrue(Files.isRegularFile(agentJar), "Не собран агент: " + agentJar);
        jreTar = downloadJre();
        Assumptions.assumeTrue(jreTar != null, "Не удалось получить JRE 21 (нужен интернет)");

        String mirror = System.getenv().getOrDefault("DEBIAN_MIRROR", "deb.debian.org");
        ImageFromDockerfile image = new ImageFromDockerfile("k6lab-sshd-systemd-it", false)
                .withFileFromPath(".", Path.of("../docker/sshd-systemd").toAbsolutePath().normalize())
                .withBuildArg("DEBIAN_MIRROR", mirror);
        gen = Gen.start(image.get());
        gen.execInContainer("sh", "-c", "echo 'deploy:" + SUDO_PASSWORD + "' | chpasswd");
    }

    @AfterAll
    static void stopContainer() {
        if (gen != null) {
            gen.stop();
        }
    }

    private static Path downloadJre() {
        try {
            Path cache = Path.of("target/it-cache/temurin-jre21-linux-x64.tar.gz").toAbsolutePath();
            if (Files.isRegularFile(cache) && Files.size(cache) > 20_000_000) {
                return cache;
            }
            Files.createDirectories(cache.getParent());
            HttpClient http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.ALWAYS)
                    .connectTimeout(Duration.ofSeconds(20)).build();
            HttpResponse<Path> r = http.send(HttpRequest.newBuilder(URI.create(
                            "https://api.adoptium.net/v3/binary/latest/21/ga/linux/x64/jre/hotspot/normal/eclipse"))
                    .timeout(Duration.ofMinutes(5)).build(), HttpResponse.BodyHandlers.ofFile(cache));
            return r.statusCode() == 200 && Files.size(cache) > 20_000_000 ? cache : null;
        } catch (Exception e) {
            return null;
        }
    }

    private String exec(String... cmd) throws Exception {
        var r = gen.execInContainer(cmd);
        return (r.getStdout() + r.getStderr()).trim();
    }

    private Deployment awaitSettled(long id, Duration timeout) {
        await().atMost(timeout).pollInterval(Duration.ofSeconds(1)).until(() -> {
            Deployment d = deployments.findById(id).orElseThrow();
            return d.getStatus().isFinal() || d.getStatus() == Deployment.Status.WAITING_HOST_KEY;
        });
        return deployments.findById(id).orElseThrow();
    }

    private Deployment awaitSucceeded(long id) {
        Deployment d = awaitSettled(id, Duration.ofMinutes(5));
        assertThat(d.getStatus()).as("Журнал операции:\n%s\nОшибка: %s", d.getLog(), d.getError())
                .isEqualTo(Deployment.Status.SUCCEEDED);
        return d;
    }

    private static InputStream fakeK6() {
        return new ByteArrayInputStream("#!/bin/sh\necho \"k6 v1.7.1 (commit/it, go1.24, linux/amd64)\"\n".getBytes(StandardCharsets.UTF_8));
    }

    private String serverUrl() {
        return "ws://" + CONTAINER_HOST + ":" + AGENT_PORT + "/agent/ws";
    }

    // ================================================================== сценарий

    @Test
    @Order(1)
    void prepareDistributionsAndCredentials() throws IOException {
        try (InputStream in = Files.newInputStream(agentJar)) {
            distributions.upload(Kind.AGENT, "", "0.1.0-it", Arch.ANY, "k6-agent.jar", in, true);
        }
        distributions.upload(Kind.K6, "", "1.7.1", Arch.LINUX_AMD64, "k6", fakeK6(), true);
        try (InputStream in = Files.newInputStream(jreTar)) {
            distributions.upload(Kind.JRE, "", "21", Arch.LINUX_AMD64, "jre21.tar.gz", in, true);
        }
        systemCred = credentials.save(new SshCredentialService.Form(null, "it-system", "deploy",
                SshCredential.AuthType.PASSWORD, SUDO_PASSWORD, null, SUDO_PASSWORD, null, null, null, false, false)).getId();
        userCred = credentials.save(new SshCredentialService.Form(null, "it-user", "k6user",
                SshCredential.AuthType.PASSWORD, "k6user", null, null, null, null, null, false, false)).getId();
        assertThat(distributions.list()).hasSize(3).allMatch(a -> a.isDefault());
    }

    @Test
    @Order(2)
    void preflightRequiresHostKeyConfirmationThenPasses() {
        var req = new DeployService.PreflightRequest("localhost", gen.getMappedPort(22), systemCred,
                Generator.InstallMode.SYSTEM, "/opt/k6-agent", serverUrl());
        Deployment d = awaitSettled(deploy.startPreflight(req).getId(), Duration.ofMinutes(2));
        assertThat(d.getStatus()).isEqualTo(Deployment.Status.WAITING_HOST_KEY);
        assertThat(d.getHostKeyFingerprint()).startsWith("SHA256:");
        assertThat(knownHosts.findByHostAndPort("localhost", gen.getMappedPort(22))).isEmpty();

        deploy.confirmHostKey(d.getId(), "it");
        d = awaitSettled(d.getId(), Duration.ofMinutes(2));
        assertThat(d.getStatus()).as(d.getLog()).isEqualTo(Deployment.Status.SUCCEEDED);
        assertThat(d.getLog()).contains("Pre-flight проверки хоста").contains("systemd=true").contains("x86_64");
        assertThat(knownHosts.findByHostAndPort("localhost", gen.getMappedPort(22))).isPresent();
    }

    @Test
    @Order(3)
    void systemInstallEndToEnd() throws Exception {
        var req = new DeployService.InstallRequest("it-system", "localhost", gen.getMappedPort(22), systemCred, null,
                List.of("it"), Generator.InstallMode.SYSTEM, null, serverUrl(), true, 1, null, null);
        Deployment d = awaitSucceeded(deploy.startInstall(req).getId());
        systemGen = d.getGeneratorId();

        Generator g = generators.findById(systemGen).orElseThrow();
        assertThat(g.getStatus()).isEqualTo(Generator.Status.ONLINE);
        assertThat(g.getSupervisor()).isEqualTo(Generator.Supervisor.SYSTEMD);
        assertThat(g.getArch()).isEqualTo("LINUX_AMD64");
        assertThat(g.getK6Version()).isEqualTo("1.7.1");
        assertThat(g.getAgentVersion()).isNotBlank();
        assertThat(g.getOneTimeTokenHash()).isNull(); // постоянный токен выдан

        // шаги и секреты
        assertThat(d.getSteps()).extracting(Deployment.StepInfo::status).doesNotContain("FAILED");
        assertThat(d.getLog()).doesNotContain(SUDO_PASSWORD).contains("Установка завершена");
        assertThat(deployments.findAll().stream().map(Deployment::getLog).toList())
                .noneMatch(l -> l.contains(SUDO_PASSWORD));

        // состояние хоста
        assertThat(exec("systemctl", "is-active", "k6-agent")).isEqualTo("active");
        assertThat(exec("systemctl", "is-enabled", "k6-agent")).isEqualTo("enabled");
        assertThat(exec("stat", "-c", "%a %U", "/opt/k6-agent/agent.properties")).isEqualTo("600 k6agent");
        assertThat(exec("sh", "-c", "grep -c agent.token /opt/k6-agent/agent.properties")).isEqualTo("1");
        assertThat(exec("sh", "-c", "ps -o args= -u k6agent | grep -o Xmx256m | head -1")).isEqualTo("Xmx256m");
        assertThat(exec("cat", "/etc/systemd/system/k6-agent.service")).contains("LimitNOFILE=1048576", "Restart=always", "User=k6agent");
        assertThat(exec("cat", "/etc/sysctl.d/99-k6.conf")).contains("ip_local_port_range");

        // heartbeat идёт и метрики сохраняются
        await().atMost(Duration.ofSeconds(30)).until(() -> registry.isConnected(systemGen));
        await().atMost(Duration.ofSeconds(40)).until(() -> generators.findById(systemGen).orElseThrow().getClockOffsetMs() != null);
        assertThat(Math.abs(generators.findById(systemGen).orElseThrow().getClockOffsetMs())).isLessThan(2000);
    }

    @Test
    @Order(4)
    void restartAndIdempotentUpdate() {
        Deployment restart = awaitSucceeded(deploy.startRestart(systemGen).getId());
        assertThat(restart.getLog()).contains("Агент подключился");

        Deployment update = awaitSucceeded(deploy.startUpdate(systemGen, null, null).getId());
        assertThat(update.getLog()).contains("уже актуальная версия").contains("Версии на хосте уже совпадают");
        assertThat(generators.findById(systemGen).orElseThrow().getStatus()).isEqualTo(Generator.Status.ONLINE);
    }

    @Test
    @Order(5)
    void busyGeneratorCannotBeUpdated() {
        Generator g = generators.findById(systemGen).orElseThrow();
        g.setActiveRuns(1);
        generators.save(g);
        try {
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> deploy.startUpdate(systemGen, null, null))
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining("выполняется тест");
        } finally {
            g = generators.findById(systemGen).orElseThrow();
            g.setActiveRuns(0);
            generators.save(g);
        }
    }

    @Test
    @Order(6)
    void userModeInstallFallsBackToCronWithoutLinger() throws Exception {
        // host key уже подтверждён (тот же хост и порт)
        var req = new DeployService.InstallRequest("it-user", "localhost", gen.getMappedPort(22), userCred, null,
                List.of(), Generator.InstallMode.USER, null, serverUrl(), false, 1, null, null);
        Deployment d = awaitSucceeded(deploy.startInstall(req).getId());
        Generator g = generators.findById(d.getGeneratorId()).orElseThrow();
        assertThat(g.getSupervisor()).isEqualTo(Generator.Supervisor.CRON);
        assertThat(g.getInstallDir()).isEqualTo("/home/k6user/k6-agent");
        assertThat(d.getLog()).contains("Linger у пользователя выключен").contains("cron");
        assertThat(exec("su", "k6user", "-c", "crontab -l")).contains("@reboot").contains("agent-ctl.sh ensure");
        assertThat(exec("su", "k6user", "-c", "/home/k6user/k6-agent/bin/agent-ctl.sh status")).isEqualTo("running");

        // удаление: сервис остановлен, cron-записи и файлы убраны, токен отозван, запись удалена
        Deployment un = awaitSucceeded(deploy.startUninstall(g.getId()).getId());
        assertThat(generators.findById(g.getId())).isEmpty();
        assertThat(exec("sh", "-c", "su k6user -c 'crontab -l' 2>&1 | grep -c k6-agent-managed || true")).isEqualTo("0");
        assertThat(exec("sh", "-c", "test -e /home/k6user/k6-agent && echo exists || echo gone")).isEqualTo("gone");
    }

    @Test
    @Order(7)
    void changedHostKeyAbortsOperation() {
        var kh = knownHosts.findByHostAndPort("localhost", gen.getMappedPort(22)).orElseThrow();
        String real = kh.getFingerprintSha256();
        kh.setFingerprintSha256("SHA256:AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA");
        knownHosts.save(kh);
        try {
            var req = new DeployService.PreflightRequest("localhost", gen.getMappedPort(22), systemCred,
                    Generator.InstallMode.SYSTEM, "/opt/k6-agent", serverUrl());
            Deployment d = awaitSettled(deploy.startPreflight(req).getId(), Duration.ofMinutes(2));
            assertThat(d.getStatus()).isEqualTo(Deployment.Status.FAILED);
            assertThat(d.getError()).contains("не совпадает").contains("Установка прервана");
        } finally {
            kh.setFingerprintSha256(real);
            knownHosts.save(kh);
        }
    }

    @Test
    @Order(8)
    void preflightFailureLeavesHostUntouched() throws Exception {
        // сервер недоступен с генератора: pre-flight падает на проверке порта, ничего не устанавливается
        var bad = new DeployService.InstallRequest("it-bad", "localhost", gen.getMappedPort(22), systemCred, null,
                List.of(), Generator.InstallMode.SYSTEM, "/opt/k6-bad", "ws://127.0.0.1:9/agent/ws", false, 1, null, null);
        Deployment d = awaitSettled(deploy.startInstall(bad).getId(), Duration.ofMinutes(3));
        assertThat(d.getStatus()).isEqualTo(Deployment.Status.FAILED);
        assertThat(d.getError()).contains("Pre-flight не пройден").contains("недоступен");
        assertThat(exec("sh", "-c", "test -e /opt/k6-bad && echo exists || echo none")).isEqualTo("none");
        assertThat(generators.findByName("it-bad").orElseThrow().getStatus()).isEqualTo(Generator.Status.ERROR);
    }

    @Test
    @Order(9)
    void installIsRolledBackWhenAgentCannotRegister() throws Exception {
        // порт доступен, но путь неверный: сервис стартует, регистрации нет -> таймаут -> откат (unit удалён)
        var req = new DeployService.InstallRequest("it-noreg", "localhost", gen.getMappedPort(22), systemCred, null,
                List.of(), Generator.InstallMode.SYSTEM, "/opt/k6-noreg", "ws://" + CONTAINER_HOST + ":" + AGENT_PORT + "/agent/nope",
                false, 1, null, null);
        Deployment d = awaitSettled(deploy.startInstall(req).getId(), Duration.ofMinutes(4));
        assertThat(d.getStatus()).as(d.getLog()).isEqualTo(Deployment.Status.ROLLED_BACK);
        assertThat(d.getError()).contains("не зарегистрировался");
        assertThat(d.getLog()).contains("Откат установки");
        assertThat(exec("sh", "-c", "test -e /etc/systemd/system/k6-agent.service && echo exists || echo gone")).isEqualTo("gone");
        assertThat(generators.findByName("it-noreg").orElseThrow().getStatus()).isEqualTo(Generator.Status.ERROR);
    }

    @Test
    @Order(10)
    void systemUninstallCleansUp() throws Exception {
        Deployment un = awaitSucceeded(deploy.startUninstall(systemGen).getId());
        assertThat(generators.findById(systemGen)).isEmpty();
        assertThat(exec("sh", "-c", "systemctl is-active k6-agent; true")).isEqualTo("inactive");
        assertThat(exec("sh", "-c", "test -e /opt/k6-agent && echo exists || echo gone")).isEqualTo("gone");
        assertThat(exec("sh", "-c", "test -e /etc/sysctl.d/99-k6.conf && echo exists || echo gone")).isEqualTo("gone");
        assertThat(un.getLog()).doesNotContain(SUDO_PASSWORD);
        await().atMost(Duration.ofSeconds(15)).until(() -> !registry.isConnected(systemGen));
    }

    /**
     * Контейнер «генератор» с systemd. Запускается через docker CLI: для systemd на cgroup v2 нужен
     * {@code --cgroupns=host}, а docker-java из Testcontainers этот параметр не поддерживает.
     */
    static final class Gen {
        record Result(String stdout, String stderr, int exitCode) {
            String getStdout() {
                return stdout;
            }

            String getStderr() {
                return stderr;
            }
        }

        private final String id;
        private final int sshPort;

        private Gen(String id, int sshPort) {
            this.id = id;
            this.sshPort = sshPort;
        }

        static Gen start(String image) throws Exception {
            String id = run(List.of("docker", "run", "-d", "--privileged", "--cgroupns=host", "--tmpfs", "/run",
                    "--tmpfs", "/run/lock", "-v", "/sys/fs/cgroup:/sys/fs/cgroup:rw",
                    "--add-host", CONTAINER_HOST + ":host-gateway", "-p", "127.0.0.1::22", image)).stdout().trim();
            Gen g = new Gen(id, 0);
            String port = run(List.of("docker", "port", id, "22/tcp")).stdout().trim().lines().findFirst().orElseThrow();
            Gen started = new Gen(id, Integer.parseInt(port.substring(port.lastIndexOf(':') + 1)));
            await().atMost(Duration.ofMinutes(2)).pollInterval(Duration.ofSeconds(1)).until(() -> {
                // пока systemd загружается, существует /run/nologin и pam_nologin не пускает обычных пользователей
                Result r = started.execInContainer("sh", "-c", "systemctl is-active ssh && ! test -e /run/nologin");
                return r.exitCode() == 0;
            });
            return started;
        }

        int getMappedPort(int ignored) {
            return sshPort;
        }

        Result execInContainer(String... cmd) throws Exception {
            List<String> full = new java.util.ArrayList<>(List.of("docker", "exec", id));
            full.addAll(List.of(cmd));
            return run(full);
        }

        void stop() {
            try {
                run(List.of("docker", "rm", "-f", id));
            } catch (Exception ignored) {
                // best-effort
            }
        }

        private static Result run(List<String> cmd) throws Exception {
            Process p = new ProcessBuilder(cmd).start();
            java.util.concurrent.CompletableFuture<String> err = java.util.concurrent.CompletableFuture.supplyAsync(() -> {
                try {
                    return new String(p.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
                } catch (IOException e) {
                    return "";
                }
            });
            String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            int code = p.waitFor();
            return new Result(out, err.get(), code);
        }
    }
}
