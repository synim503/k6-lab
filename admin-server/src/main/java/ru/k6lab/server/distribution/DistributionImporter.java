package ru.k6lab.server.distribution;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import ru.k6lab.server.config.AppProperties;
import ru.k6lab.server.distribution.DistributionArtifact.Arch;
import ru.k6lab.server.distribution.DistributionArtifact.Kind;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Портативная сборка: при старте подхватывает дистрибутивы из каталога dist/ по manifest.json (агент, k6, xk6-сборки,
 * JRE), которые ещё не загружены. Вручную в «Дистрибутивы» ничего грузить не нужно. Повторный старт ничего не дублирует.
 * <pre>{"items":[{"kind":"K6","label":"","version":"1.7.1","arch":"LINUX_AMD64","file":"k6/linux-amd64/k6","default":true}]}</pre>
 */
@Component
public class DistributionImporter {

    private static final Logger log = LoggerFactory.getLogger(DistributionImporter.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final DistributionService distributions;
    private final DistributionRepository repo;
    private final AppProperties props;

    public DistributionImporter(DistributionService distributions, DistributionRepository repo, AppProperties props) {
        this.distributions = distributions;
        this.repo = repo;
        this.props = props;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onStart() {
        try {
            int n = importAll();
            if (n > 0) {
                log.info("Из {} импортировано дистрибутивов: {}", props.getDistDir(), n);
            }
        } catch (IOException | RuntimeException e) {
            log.warn("Импорт дистрибутивов из {} не выполнен: {}", props.getDistDir(), e.getMessage());
        }
    }

    /** Возвращает число новых дистрибутивов. */
    public int importAll() throws IOException {
        Path dir = props.getDistDir().toAbsolutePath();
        Path manifest = dir.resolve("manifest.json");
        if (!Files.isRegularFile(manifest)) {
            return 0;
        }
        JsonNode root = MAPPER.readTree(manifest.toFile());
        int imported = 0;
        for (JsonNode item : root.path("items")) {
            Kind kind = Kind.valueOf(item.path("kind").asText());
            Arch arch = Arch.valueOf(item.path("arch").asText("ANY"));
            String label = item.path("label").asText("");
            String version = item.path("version").asText();
            Path file = dir.resolve(item.path("file").asText()).normalize();
            if (!file.startsWith(dir) || !Files.isRegularFile(file)) {
                log.warn("Дистрибутив из manifest пропущен (файла нет или путь вне каталога): {}", item.path("file").asText());
                continue;
            }
            if (repo.findFirstByKindAndVersionAndArchAndLabel(kind, version, arch, label).isPresent()) {
                continue;
            }
            try (InputStream in = Files.newInputStream(file)) {
                distributions.upload(kind, label, version, arch, file.getFileName().toString(), in, item.path("default").asBoolean(false));
                imported++;
            } catch (IllegalArgumentException e) {
                log.warn("Дистрибутив {} {} не загружен: {}", kind, file.getFileName(), e.getMessage());
            }
        }
        return imported;
    }
}
