package ru.k6lab.server.bootstrap;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Map;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

class SetupWizardTest {

    @TempDir
    Path tmp;

    @Test
    void wizardIsNeededWithoutConfigButNotForServerInstallWithEnv() {
        Path cfg = tmp.resolve("config/k6lab.properties");
        assertThat(SetupWizard.needed(cfg, Map.of())).isTrue();
        assertThat(SetupWizard.needed(cfg, Map.of("K6LAB_DB_URL", "jdbc:postgresql://db/k6lab"))).isFalse();
    }

    @Test
    void unreachableDatabaseGivesReadableError() {
        String err = SetupWizard.check("jdbc:postgresql://127.0.0.1:1/k6lab", "u", "p");
        assertThat(err).startsWith("Не удалось подключиться к PostgreSQL");
        assertThat(SetupWizard.check("", "u", "p")).isNotNull();
    }

    @Test
    void savedConfigHasGeneratedMasterKeyAndKeepsItOnResave() throws Exception {
        Path cfg = tmp.resolve("config/k6lab.properties");
        SetupWizard.save(cfg, "jdbc:postgresql://h:5432/k6lab", "k6lab", "pw", "adm");
        Properties p = SetupWizard.load(cfg);
        assertThat(p.getProperty("spring.datasource.url")).isEqualTo("jdbc:postgresql://h:5432/k6lab");
        assertThat(Base64.getDecoder().decode(p.getProperty("k6lab.master-key"))).hasSize(32);
        assertThat(p.getProperty("k6lab.admin-password")).isEqualTo("adm");
        String key = p.getProperty("k6lab.master-key");
        // повторная настройка (например, смена пароля БД) не меняет ключ шифрования - иначе секреты в БД станут нечитаемыми
        SetupWizard.save(cfg, "jdbc:postgresql://h2:5432/k6lab", "k6lab", "pw2", "other");
        Properties p2 = SetupWizard.load(cfg);
        assertThat(p2.getProperty("k6lab.master-key")).isEqualTo(key);
        assertThat(p2.getProperty("k6lab.admin-password")).isEqualTo("adm");
        assertThat(p2.getProperty("spring.datasource.url")).contains("h2");
    }

    @Test
    void wizardPageShowsErrorAndDoesNotSaveWhenDatabaseIsUnreachable() throws Exception {
        Path cfg = tmp.resolve("config/k6lab.properties");
        int port;
        try (var s = new java.net.ServerSocket(0)) {
            port = s.getLocalPort();
        }
        Thread t = new Thread(() -> {
            try {
                SetupWizard.run(cfg, port);
            } catch (Exception ignored) {
                // прерывание в конце теста
            }
        });
        t.setDaemon(true);
        t.start();
        HttpClient c = HttpClient.newHttpClient();
        String page = null;
        for (int i = 0; i < 30 && page == null; i++) {
            try {
                page = c.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/")).build(), HttpResponse.BodyHandlers.ofString()).body();
            } catch (java.io.IOException e) {
                Thread.sleep(100);
            }
        }
        assertThat(page).contains("Адрес PostgreSQL").contains("Проверить и сохранить");
        String after = c.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/"))
                .POST(HttpRequest.BodyPublishers.ofString("host=127.0.0.1&port=1&db=k6lab&user=u&password=p&admin=", StandardCharsets.UTF_8))
                .header("Content-Type", "application/x-www-form-urlencoded").build(), HttpResponse.BodyHandlers.ofString()).body();
        assertThat(after).contains("Не удалось подключиться к PostgreSQL").contains("Адрес PostgreSQL");
        assertThat(Files.exists(cfg)).isFalse();
        t.interrupt();
    }
}
