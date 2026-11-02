package ru.k6lab.server.script;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import ru.k6lab.server.common.StorageService;
import ru.k6lab.server.config.AppProperties;
import ru.k6lab.server.distribution.DistributionArtifact;
import ru.k6lab.server.distribution.DistributionService;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * k6 на сервере админки: {@code k6 inspect} при сохранении версии скрипта и
 * {@code k6 inspect --execution-requirements} для требований к ресурсам. k6 берётся из настроек, из дистрибутивов
 * (версия по умолчанию для архитектуры сервера) либо из PATH.
 */
@Component
public class K6Tool {

    private static final Logger log = LoggerFactory.getLogger(K6Tool.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    public record Result(int exitCode, String stdout, String stderr, boolean timedOut) {
        public boolean ok() {
            return exitCode == 0 && !timedOut;
        }
    }

    public record Inspect(ScriptVersion.InspectStatus status, Map<String, Object> options, String error) {
    }

    private final AppProperties props;
    private final DistributionService distributions;
    private final StorageService storage;
    private volatile Path cached;
    private volatile long cachedAt;

    public K6Tool(AppProperties props, DistributionService distributions, StorageService storage) {
        this.props = props;
        this.distributions = distributions;
        this.storage = storage;
    }

    public synchronized Optional<Path> locate() {
        if (cached != null && System.currentTimeMillis() - cachedAt < 60_000 && Files.isExecutable(cached)) {
            return Optional.of(cached);
        }
        cached = null;
        if (props.getK6Path() != null && !props.getK6Path().isBlank()) {
            Path p = Path.of(props.getK6Path());
            if (Files.isExecutable(p)) {
                return remember(p);
            }
        }
        Optional<Path> fromArtifact = fromArtifact();
        if (fromArtifact.isPresent()) {
            return remember(fromArtifact.get());
        }
        Optional<Path> fromPath = fromPath();
        fromPath.ifPresent(this::remember);
        return fromPath;
    }

    private Optional<Path> remember(Path p) {
        cached = p;
        cachedAt = System.currentTimeMillis();
        return Optional.of(p);
    }

    private Optional<Path> fromArtifact() {
        String os = System.getProperty("os.name", "").toLowerCase();
        if (!os.contains("linux")) {
            return Optional.empty();
        }
        String arch = System.getProperty("os.arch", "");
        DistributionArtifact.Arch a = arch.equals("amd64") || arch.equals("x86_64") ? DistributionArtifact.Arch.LINUX_AMD64
                : arch.equals("aarch64") ? DistributionArtifact.Arch.LINUX_ARM64 : null;
        if (a == null) {
            return Optional.empty();
        }
        Optional<DistributionArtifact> art = distributions.defaultFor(DistributionArtifact.Kind.K6, a);
        if (art.isEmpty()) {
            return Optional.empty();
        }
        try {
            Path dir = props.getStorageDir().resolve("tools").toAbsolutePath();
            Files.createDirectories(dir);
            Path bin = dir.resolve("k6-" + art.get().getSha256().substring(0, 12));
            if (!Files.isExecutable(bin)) {
                Path tmp = dir.resolve(bin.getFileName() + ".part");
                try (InputStream in = storage.open(art.get().getStorageKey())) {
                    Files.copy(in, tmp, StandardCopyOption.REPLACE_EXISTING);
                }
                tmp.toFile().setExecutable(true, false);
                Files.move(tmp, bin, StandardCopyOption.REPLACE_EXISTING);
            }
            return Optional.of(bin);
        } catch (IOException e) {
            log.warn("Не удалось подготовить k6 из дистрибутива: {}", e.toString());
            return Optional.empty();
        }
    }

    private Optional<Path> fromPath() {
        try {
            Result r = exec(List.of("k6", "version"), null, Duration.ofSeconds(10));
            return r.ok() ? Optional.of(Path.of("k6")) : Optional.empty();
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    public Optional<String> version() {
        return locate().map(p -> {
            try {
                Result r = exec(List.of(p.toString(), "version"), null, Duration.ofSeconds(10));
                return r.ok() ? r.stdout().lines().findFirst().orElse("") : null;
            } catch (IOException e) {
                return null;
            }
        });
    }

    /** k6 inspect: опции скрипта (JSON со всеми полями, неустановленные - null). */
    public Inspect inspect(Path dir, String entrypoint) {
        Optional<Path> k6 = locate();
        if (k6.isEmpty()) {
            return new Inspect(ScriptVersion.InspectStatus.UNAVAILABLE, null,
                    "k6 не найден на сервере админки: загрузите k6 для архитектуры сервера в «Дистрибутивы» или задайте K6LAB_K6_PATH");
        }
        try {
            Result r = exec(List.of(k6.get().toString(), "inspect", entrypoint), dir, Duration.ofSeconds(60));
            if (!r.ok()) {
                String err = r.timedOut() ? "k6 inspect не завершился за 60 с" : tail(r.stderr().isBlank() ? r.stdout() : r.stderr());
                if (!r.timedOut() && err.contains("unknown modules")) {
                    // скрипт использует расширения xk6, которых нет в k6 на сервере админки: они есть в сборке на генераторах
                    return new Inspect(ScriptVersion.InspectStatus.UNAVAILABLE, null,
                            "Скрипт использует расширения xk6 (k6/x/...), которых нет в k6 на сервере админки");
                }
                return new Inspect(ScriptVersion.InspectStatus.FAILED, null, err);
            }
            Map<String, Object> options = MAPPER.readValue(r.stdout(), new TypeReference<>() {
            });
            return new Inspect(ScriptVersion.InspectStatus.OK, options, null);
        } catch (IOException e) {
            return new Inspect(ScriptVersion.InspectStatus.FAILED, null, "Не удалось выполнить k6 inspect: " + e.getMessage());
        }
    }

    /** k6 inspect --execution-requirements --config: максимум VU и длительность для итогового конфига. */
    public Result requirements(Path dir, String entrypoint, String configJson) throws IOException {
        Optional<Path> k6 = locate();
        if (k6.isEmpty()) {
            throw new IOException("k6 не найден на сервере админки");
        }
        Path cfg = Files.createTempFile(dir, "k6cfg", ".json");
        try {
            Files.writeString(cfg, configJson, StandardCharsets.UTF_8);
            return exec(List.of(k6.get().toString(), "inspect", "--execution-requirements", "--config",
                    cfg.toAbsolutePath().toString(), entrypoint), dir, Duration.ofSeconds(60));
        } finally {
            Files.deleteIfExists(cfg);
        }
    }

    private static Result exec(List<String> cmd, Path dir, Duration timeout) throws IOException {
        ProcessBuilder pb = new ProcessBuilder(new ArrayList<>(cmd));
        if (dir != null) {
            pb.directory(dir.toFile());
        }
        pb.environment().put("K6_NO_USAGE_REPORT", "true");
        pb.environment().put("K6_NO_COLOR", "true");
        // без интернета и без зависаний: k6 не докачивает расширения сам (скрипты с k6/x/... проверяются статически)
        pb.environment().put("K6_AUTO_EXTENSION_RESOLUTION", "false");
        Process p = pb.start();
        p.getOutputStream().close();
        var out = java.util.concurrent.CompletableFuture.supplyAsync(() -> read(p.getInputStream()));
        var err = java.util.concurrent.CompletableFuture.supplyAsync(() -> read(p.getErrorStream()));
        try {
            if (!p.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                p.destroyForcibly();
                return new Result(-1, "", "", true);
            }
            return new Result(p.exitValue(), out.get(5, TimeUnit.SECONDS), err.get(5, TimeUnit.SECONDS), false);
        } catch (InterruptedException e) {
            p.destroyForcibly();
            Thread.currentThread().interrupt();
            throw new IOException("Прервано", e);
        } catch (java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException e) {
            throw new IOException("Не удалось прочитать вывод k6", e);
        }
    }

    private static String read(InputStream in) {
        try {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "";
        }
    }

    private static String tail(String s) {
        String t = s.strip();
        return t.length() > 2000 ? "…" + t.substring(t.length() - 2000) : t;
    }
}
