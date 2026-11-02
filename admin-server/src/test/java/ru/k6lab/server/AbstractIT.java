package ru.k6lab.server;

import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.io.IOException;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;

/** Общая основа интеграционных тестов: PostgreSQL 16 в Testcontainers (один на все тесты JVM). */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Import(RecordingConfig.class)
public abstract class AbstractIT {

    protected static final String ADMIN_PASSWORD = "Admin#12345";
    protected static final int AGENT_PORT = freePort();
    private static final Path STORAGE = tempDir();

    /** PostgreSQL 16: Testcontainers, а если Docker недоступен - встроенный (zonky), чтобы тесты шли и без Docker. */
    static final PostgreSQLContainer<?> POSTGRES;
    static final io.zonky.test.db.postgres.embedded.EmbeddedPostgres EMBEDDED;
    static final String JDBC_URL;

    static {
        PostgreSQLContainer<?> container = null;
        io.zonky.test.db.postgres.embedded.EmbeddedPostgres embedded = null;
        String url;
        if (System.getProperty("k6lab.it.embedded-pg") == null && org.testcontainers.DockerClientFactory.instance().isDockerAvailable()) {
            container = new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"))
                    .withDatabaseName("k6lab").withUsername("k6lab").withPassword("k6lab");
            container.start();
            url = container.getJdbcUrl();
        } else {
            try {
                embedded = io.zonky.test.db.postgres.embedded.EmbeddedPostgres.builder().start();
                url = embedded.getJdbcUrl("postgres", "postgres");
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        }
        POSTGRES = container;
        EMBEDDED = embedded;
        JDBC_URL = url;
        // исключения в IO-потоках SSH/WebSocket при закрытии сессий не должны валить чужие проверки
        org.awaitility.Awaitility.doNotCatchUncaughtExceptionsByDefault();
    }

    @LocalServerPort
    protected int httpPort;

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", () -> JDBC_URL);
        r.add("spring.datasource.username", () -> POSTGRES != null ? POSTGRES.getUsername() : "postgres");
        r.add("spring.datasource.password", () -> POSTGRES != null ? POSTGRES.getPassword() : "postgres");
        r.add("k6lab.master-key", () -> Base64.getEncoder().encodeToString(new byte[]{
                1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20, 21, 22, 23, 24, 25, 26, 27, 28, 29, 30, 31, 32}));
        r.add("k6lab.agent-port", () -> AGENT_PORT);
        r.add("k6lab.admin-password", () -> ADMIN_PASSWORD);
        r.add("k6lab.storage-dir", STORAGE::toString);
        r.add("k6lab.deploy.registration-timeout", () -> "90s");
        r.add("k6lab.require-diagnostics", () -> "false");
        r.add("k6lab.run.lost-grace-sec", () -> "20");
        // цель smoke-теста диагностики: порт агентов отвечает 404 на не-/agent пути, для k6 это нормальный ответ
        r.add("k6lab.public-url", () -> "http://127.0.0.1:" + AGENT_PORT);
    }

    private static int freePort() {
        try (ServerSocket s = new ServerSocket(0)) {
            return s.getLocalPort();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Path tempDir() {
        try {
            return Files.createTempDirectory("k6lab-it-storage");
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
