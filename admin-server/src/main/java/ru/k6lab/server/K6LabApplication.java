package ru.k6lab.server;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;
import ru.k6lab.server.bootstrap.SetupWizard;

import java.nio.file.Path;

@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling
@EnableAsync
public class K6LabApplication {

    public static void main(String[] args) throws Exception {
        // Портативный режим: параметры БД берутся из config/k6lab.properties; если их нет или база недоступна,
        // сначала запускается мастер настройки. Серверная установка (K6LAB_DB_URL в окружении) мастер пропускает.
        Path config = Path.of(SetupWizard.CONFIG_FILE).toAbsolutePath();
        if (SetupWizard.needed(config, System.getenv())) {
            int port = Integer.parseInt(System.getenv().getOrDefault("K6LAB_HTTP_PORT", "8080"));
            SetupWizard.run(config, port);
        }
        if (java.nio.file.Files.isRegularFile(config)) {
            System.setProperty("spring.config.additional-location", "optional:file:" + config.toString().replace('\\', '/'));
        }
        SpringApplication.run(K6LabApplication.class, args);
    }
}
