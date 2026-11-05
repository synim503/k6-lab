package ru.k6lab.server.script;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import ru.k6lab.server.config.AppProperties;

import java.util.List;

/**
 * Сверка записей о версиях скриптов с файлами в хранилище. После переустановки админки без переноса data/storage записи в
 * базе остаются, а архивы пропадают: скрипты видны, но запуск и скачивание не работают. Сверяется последняя версия каждого
 * скрипта; результат кэшируется на минуту.
 */
@Component
public class StorageHealth {

    private static final Logger log = LoggerFactory.getLogger(StorageHealth.class);
    private static final long CACHE_MS = 60_000;

    private final ScriptService scripts;
    private final AppProperties props;
    private volatile List<String> cached = List.of();
    private volatile long cachedAt;

    public StorageHealth(ScriptService scripts, AppProperties props) {
        this.scripts = scripts;
        this.props = props;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onStart() {
        List<String> missing = scriptsWithoutFiles(true);
        if (!missing.isEmpty()) {
            log.warn("Хранилище {}: у {} скриптов нет файлов последней версии ({}). Вероятно, переустановка без переноса "
                    + "каталога data/storage или смена K6LAB_STORAGE_DIR.", props.getStorageDir().toAbsolutePath(), missing.size(),
                    String.join(", ", missing.stream().limit(10).toList()));
        }
    }

    /** Имена скриптов, у которых нет файла последней версии. */
    public List<String> scriptsWithoutFiles() {
        return scriptsWithoutFiles(false);
    }

    private List<String> scriptsWithoutFiles(boolean force) {
        long now = System.currentTimeMillis();
        if (!force && now - cachedAt < CACHE_MS) {
            return cached;
        }
        List<String> out = new java.util.ArrayList<>();
        for (Script s : scripts.list()) {
            scripts.latest(s.getId()).ifPresent(v -> {
                if (!scripts.hasFiles(v)) {
                    out.add(s.getName());
                }
            });
        }
        cached = out;
        cachedAt = now;
        return out;
    }

    /** Сбрасывает кэш (после восстановления файлов и в тестах). */
    public void invalidate() {
        cachedAt = 0;
    }

    public String storagePath() {
        return props.getStorageDir().toAbsolutePath().toString();
    }
}
