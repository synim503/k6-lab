package ru.k6lab.server.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.file.Path;
import java.time.Duration;

@Getter
@Setter
@ConfigurationProperties(prefix = "k6lab")
public class AppProperties {

    /** base64 от 32 байт - ключ AES-GCM для шифрования секретов в БД. */
    private String masterKey = "";
    private Path storageDir = Path.of("data/storage");
    private int agentPort = 47331;
    /** Пароль первого администратора; если пуст - генерируется и пишется в лог один раз. */
    private String adminPassword = "";
    private Duration oneTimeTokenTtl = Duration.ofHours(24);
    /** Явный путь к k6 на сервере админки (для k6 inspect); иначе берётся из дистрибутивов или из PATH. */
    private String k6Path = "";
    /** Значения Prometheus по умолчанию (переопределяются в Настройки -> Prometheus/Grafana). */
    private String prometheusRwUrl = "";
    private String prometheusQueryUrl = "";
    /** Без успешной диагностики Prometheus запуск тестов недоступен (отключать только в dev/тестах). */
    private boolean requireDiagnostics = true;
    /** URL UI админки, доступный с генераторов: цель smoke-теста диагностики (/actuator/health). */
    private String publicUrl = "";
    /** Каталог с дистрибутивами портативной сборки (manifest.json + файлы); импортируются при старте. */
    private Path distDir = Path.of("dist");
    private Deploy deploy = new Deploy();

    @Getter
    @Setter
    public static class Deploy {
        private int maxParallel = 4;
        private Duration registrationTimeout = Duration.ofSeconds(60);
    }
}
