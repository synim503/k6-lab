package ru.k6lab.server.script;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import ru.k6lab.core.util.ZipUtil;
import ru.k6lab.server.run.K6Flag;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Отладка нового скрипта: файлы из браузера кладутся во временный каталог, k6 выполняет одну функцию малым числом
 * VU/итераций прямо на сервере админки, вывод читается с позиции. Потом набор файлов сохраняется как обычный скрипт.
 * Процесс k6 запускается с пустым окружением (без переменных и секретов самой админки).
 */
@Service
public class SandboxService {

    private static final Logger log = LoggerFactory.getLogger(SandboxService.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    static final int MAX_FILES = 500;
    static final long MAX_TOTAL_BYTES = 50L * 1024 * 1024;
    private static final int MAX_LINES = 20_000;
    private static final int MAX_LINE_CHARS = 4_000;
    private static final int MAX_ACTIVE = 3;
    private static final long KEEP_MS = 30 * 60_000L;

    /** Файл из браузера: текст либо base64 (бинарные данные, сертификаты и т.п.). */
    public record FileIn(String path, String text, String base64) {
    }

    public record RunRequest(List<FileIn> files, String entrypoint, String exec, Integer vus, Integer iterations,
                             Integer maxDurationSec, Map<String, String> env, List<String> flags) {
    }

    public record Line(long t, String level, String text) {
    }

    public record Chunk(List<Line> lines, int next, boolean done, Integer exitCode, String note) {
    }

    private static final class Session {
        final Path dir;
        final List<Line> lines = new ArrayList<>();
        volatile Process process;
        volatile boolean done;
        volatile Integer exitCode;
        volatile String note;
        final long created = System.currentTimeMillis();
        volatile long finished;

        Session(Path dir) {
            this.dir = dir;
        }
    }

    private final K6Tool k6;
    private final AdminHelper helper;
    private final Map<String, Session> sessions = new ConcurrentHashMap<>();

    public SandboxService(K6Tool k6, AdminHelper helper) {
        this.k6 = k6;
        this.helper = helper;
    }

    // ------------------------------------------------------------------ файлы

    /** Проверенные файлы: безопасные пути, лимиты на число и размер. */
    static Map<String, byte[]> decode(List<FileIn> files) {
        if (files == null || files.isEmpty()) {
            throw new IllegalArgumentException("Добавьте хотя бы один файл");
        }
        if (files.size() > MAX_FILES) {
            throw new IllegalArgumentException("Слишком много файлов (максимум " + MAX_FILES + ")");
        }
        Map<String, byte[]> out = new LinkedHashMap<>();
        long total = 0;
        for (FileIn f : files) {
            String path = f.path() == null ? null : ZipUtil.safePath(f.path().trim());
            if (path == null) {
                throw new IllegalArgumentException("Недопустимый путь файла: " + f.path());
            }
            byte[] data = f.base64() != null && !f.base64().isEmpty() ? Base64.getDecoder().decode(f.base64())
                    : (f.text() == null ? new byte[0] : f.text().getBytes(StandardCharsets.UTF_8));
            total += data.length;
            if (total > MAX_TOTAL_BYTES) {
                throw new IllegalArgumentException("Файлы больше " + MAX_TOTAL_BYTES / 1024 / 1024 + " МБ");
            }
            if (out.put(path, data) != null) {
                throw new IllegalArgumentException("Файл указан дважды: " + path);
            }
        }
        return out;
    }

    public static byte[] zip(Map<String, byte[]> files) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(bos)) {
            for (var e : files.entrySet()) {
                zos.putNextEntry(new ZipEntry(e.getKey()));
                zos.write(e.getValue());
                zos.closeEntry();
            }
        }
        return bos.toByteArray();
    }

    // ------------------------------------------------------------------ запуск

    public String start(RunRequest req) throws IOException {
        cleanup();
        long active = sessions.values().stream().filter(s -> !s.done).count();
        if (active >= MAX_ACTIVE) {
            throw new IllegalStateException("Уже идёт " + active + " отладочных запусков: дождитесь завершения или остановите их");
        }
        Path k6bin = k6.locate().orElseThrow(() -> new IllegalStateException(
                "k6 не найден на сервере админки: загрузите k6 для архитектуры сервера в «Дистрибутивы» или задайте K6LAB_K6_PATH"));
        Map<String, byte[]> files = decode(req.files());
        String entry = req.entrypoint() == null ? "" : req.entrypoint().trim();
        if (entry.isEmpty() || ZipUtil.safePath(entry) == null || !files.containsKey(ZipUtil.safePath(entry))) {
            throw new IllegalArgumentException("Укажите основной файл скрипта из добавленных");
        }
        entry = ZipUtil.safePath(entry);
        List<K6Flag> flags = K6Flag.parse(req.flags());

        Path dir = Files.createTempDirectory("k6lab-sandbox");
        try {
            for (var e : files.entrySet()) {
                Path p = dir.resolve(e.getKey()).normalize();
                if (!p.startsWith(dir)) {
                    throw new IllegalArgumentException("Недопустимый путь файла: " + e.getKey());
                }
                Files.createDirectories(p.getParent());
                Files.write(p, e.getValue());
            }
            Files.createDirectories(dir.resolve("__admin"));
            Files.write(dir.resolve("__admin/admin.js"), helper.content());
            Path cfg = dir.resolve("__sandbox-config.json");
            Files.writeString(cfg, config(req), StandardCharsets.UTF_8);

            List<String> cmd = new ArrayList<>(List.of(k6bin.toString(), "run", "--quiet", "--no-color", "--config", cfg.toString()));
            flags.forEach(f -> cmd.add(f.getArg()));
            cmd.add(entry);

            ProcessBuilder pb = new ProcessBuilder(cmd).directory(dir.toFile()).redirectErrorStream(true);
            // пустое окружение: переменные и секреты самой админки скрипту не видны
            pb.environment().clear();
            String path = System.getenv("PATH");
            pb.environment().put("PATH", path == null ? "/usr/bin:/bin" : path);
            // на Windows (dev) без системных переменных Go-программы не стартуют
            for (String k : new String[]{"SystemRoot", "SYSTEMROOT", "TEMP", "TMP"}) {
                if (System.getenv(k) != null) {
                    pb.environment().put(k, System.getenv(k));
                }
            }
            pb.environment().put("HOME", dir.toString());
            pb.environment().put("K6_NO_USAGE_REPORT", "true");
            pb.environment().put("K6_NO_VERSION_CHECK", "true");
            pb.environment().put("K6_AUTO_EXTENSION_RESOLUTION", "false");
            if (req.env() != null) {
                req.env().forEach((k, v) -> {
                    if (k != null && k.matches("[A-Za-z_][A-Za-z0-9_]*") && v != null) {
                        pb.environment().put(k, v);
                    }
                });
            }
            Session s = new Session(dir);
            String id = UUID.randomUUID().toString().substring(0, 12);
            sessions.put(id, s);
            add(s, "INFO", "$ k6 run " + String.join(" ", cmd.subList(2, cmd.size()).stream()
                    .map(a -> a.startsWith(dir.toString()) ? a.substring(dir.toString().length() + 1) : a).toList()));
            s.process = pb.start();
            s.process.getOutputStream().close();
            int limit = Math.max(10, Math.min(req.maxDurationSec() == null ? 300 : req.maxDurationSec(), 3600));
            Thread t = new Thread(() -> pump(s, limit), "sandbox-" + id);
            t.setDaemon(true);
            t.start();
            return id;
        } catch (IOException | RuntimeException e) {
            deleteQuietly(dir);
            throw e;
        }
    }

    /** Конфиг для k6: один сценарий, выполняющий выбранную функцию (или default), по умолчанию 1 VU и 1 итерация. */
    static String config(RunRequest req) {
        ObjectNode sc = MAPPER.createObjectNode();
        sc.put("executor", "per-vu-iterations");
        sc.put("vus", clamp(req.vus(), 1, 50, 1));
        sc.put("iterations", clamp(req.iterations(), 1, 1000, 1));
        sc.put("maxDuration", clamp(req.maxDurationSec(), 10, 3600, 300) + "s");
        // «default» - основная функция: поле exec для неё не задаётся
        if (req.exec() != null && !req.exec().isBlank() && !"default".equals(req.exec().trim())) {
            if (!req.exec().trim().matches("[A-Za-z_$][A-Za-z0-9_$]*")) {
                throw new IllegalArgumentException("Некорректное имя функции: " + req.exec());
            }
            sc.put("exec", req.exec().trim());
        }
        ObjectNode root = MAPPER.createObjectNode();
        root.putObject("scenarios").set("debug", sc);
        return root.toString();
    }

    private static int clamp(Integer v, int min, int max, int def) {
        return v == null ? def : Math.max(min, Math.min(max, v));
    }

    private void pump(Session s, int limitSec) {
        try (BufferedReader r = new BufferedReader(new InputStreamReader(s.process.getInputStream(), StandardCharsets.UTF_8))) {
            Thread killer = new Thread(() -> {
                try {
                    if (!s.process.waitFor(limitSec + 15L, TimeUnit.SECONDS)) {
                        s.note = "Остановлено по времени (" + limitSec + " с)";
                        s.process.destroyForcibly();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }, "sandbox-killer");
            killer.setDaemon(true);
            killer.start();
            String line;
            while ((line = r.readLine()) != null) {
                add(s, level(line), line);
            }
            s.exitCode = s.process.waitFor();
        } catch (IOException | InterruptedException e) {
            s.note = "Ошибка чтения вывода: " + e.getMessage();
        } finally {
            s.finished = System.currentTimeMillis();
            s.done = true;
        }
    }

    private static void add(Session s, String level, String text) {
        String t = text.length() > MAX_LINE_CHARS ? text.substring(0, MAX_LINE_CHARS) + "…" : text;
        synchronized (s.lines) {
            if (s.lines.size() < MAX_LINES) {
                s.lines.add(new Line(System.currentTimeMillis(), level, t));
            } else if (s.lines.size() == MAX_LINES) {
                s.lines.add(new Line(System.currentTimeMillis(), "WARN", "… вывод обрезан (больше " + MAX_LINES + " строк)"));
            }
        }
    }

    /** Уровень по формату лога k6 (level=warn ...), для остального вывода (итог, console.log) - INFO. */
    static String level(String line) {
        int i = line.indexOf("level=");
        if (i >= 0) {
            String rest = line.substring(i + 6);
            if (rest.startsWith("error") || rest.startsWith("fatal")) {
                return "ERROR";
            }
            if (rest.startsWith("warn")) {
                return "WARN";
            }
            if (rest.startsWith("debug")) {
                return "DEBUG";
            }
        }
        return "INFO";
    }

    public Chunk read(String id, int from) {
        Session s = sessions.get(id);
        if (s == null) {
            throw new IllegalArgumentException("Отладочный запуск не найден (возможно, истёк срок хранения)");
        }
        List<Line> part;
        int next;
        synchronized (s.lines) {
            int start = Math.max(0, Math.min(from, s.lines.size()));
            part = new ArrayList<>(s.lines.subList(start, s.lines.size()));
            next = s.lines.size();
        }
        return new Chunk(part, next, s.done, s.exitCode, s.note);
    }

    public void stop(String id) {
        Session s = sessions.get(id);
        if (s != null && s.process != null && s.process.isAlive()) {
            s.note = "Остановлено пользователем";
            s.process.destroy();
        }
    }

    /** Каталоги завершённых отладочных запусков удаляются по расписанию, а не только при следующем запуске. */
    @org.springframework.scheduling.annotation.Scheduled(fixedDelay = 600_000, initialDelay = 600_000)
    public void purge() {
        cleanup();
    }

    private void cleanup() {
        long now = System.currentTimeMillis();
        sessions.entrySet().removeIf(e -> {
            Session s = e.getValue();
            boolean old = s.done && now - s.finished > KEEP_MS;
            if (old) {
                deleteQuietly(s.dir);
            }
            return old;
        });
    }

    private static void deleteQuietly(Path dir) {
        try (var walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        } catch (IOException e) {
            log.debug("Не удалось удалить {}: {}", dir, e.getMessage());
        }
    }
}
