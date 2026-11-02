package ru.k6lab.server.run;

import org.springframework.stereotype.Service;
import ru.k6lab.core.util.ZipUtil;
import ru.k6lab.server.common.StorageService;
import ru.k6lab.server.script.AdminHelper;
import ru.k6lab.server.script.ScriptService;
import ru.k6lab.server.script.ScriptVersion;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Архив для запуска: версия скрипта + __admin/admin.js рядом с entrypoint. Код пользовательского скрипта не
 * меняется. Архив собирается один раз на версию и раздаётся агентам по одноразовым подписанным ссылкам.
 */
@Service
public class RunArtifactService {

    public record Artifact(String storageKey, String sha256, long size) {
    }

    private final ScriptService scripts;
    private final AdminHelper helper;
    private final StorageService storage;
    private final Map<Long, Artifact> cache = new ConcurrentHashMap<>();

    public RunArtifactService(ScriptService scripts, AdminHelper helper, StorageService storage) {
        this.scripts = scripts;
        this.helper = helper;
        this.storage = storage;
    }

    public Artifact forVersion(ScriptVersion v) throws IOException {
        Artifact cached = cache.get(v.getId());
        if (cached != null && storage.exists(cached.storageKey())) {
            return cached;
        }
        Map<String, byte[]> files = ZipUtil.readAll(new ByteArrayInputStream(scripts.zipBytes(v)));
        files.put(AdminHelper.PATH, helper.content());
        byte[] zip = ZipUtil.write(files);
        var stored = storage.store("run-artifacts", "v" + v.getId() + ".zip", new ByteArrayInputStream(zip));
        Artifact a = new Artifact(stored.key(), stored.sha256(), stored.size());
        cache.put(v.getId(), a);
        return a;
    }
}
