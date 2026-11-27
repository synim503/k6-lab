package ru.k6lab.server.git;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import ru.k6lab.core.util.ZipUtil;
import ru.k6lab.server.audit.AuditService;
import ru.k6lab.server.common.Hashes;
import ru.k6lab.server.load.LoadForm;
import ru.k6lab.server.load.LoadJson;
import ru.k6lab.server.load.LoadProfileStore;
import ru.k6lab.server.load.LoadService;
import ru.k6lab.server.result.ChartTemplateStore;
import ru.k6lab.server.result.ChartVariableStore;
import ru.k6lab.server.script.Script;
import ru.k6lab.server.script.ScriptService;
import ru.k6lab.server.script.ScriptVersion;
import ru.k6lab.server.script.StorageHealth;
import ru.k6lab.server.settings.SettingsService;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Резервное копирование и синхронизация всех скриптов (исходники, базовая нагрузка, профили, графики отчёта)
 * в выбранный Git-репозиторий, а также восстановление скриптов из Git-бэкапа.
 */
@Service
public class GitBackupService {

    private static final Logger log = LoggerFactory.getLogger(GitBackupService.class);
    public static final String SETTING_KEY = "git.backup.config";

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);

    public record BackupConfig(
            Long repoId,
            String branch,
            String subdir,
            boolean autoSync,
            boolean pruneDeleted,
            String lastSyncAt,
            String lastCommit,
            String lastStatus,
            String lastMessage,
            int lastScriptCount
    ) {
        public static final BackupConfig DEFAULT = new BackupConfig(
                null, "main", "", false, false, null, null, "IDLE", "", 0
        );
    }

    public record SyncOutcome(
            boolean ok,
            boolean changed,
            String commit,
            String branch,
            int scriptCount,
            String syncedAt,
            String message
    ) {
    }

    public record RemoteScriptEntry(
            String slug,
            String name,
            String description,
            String entrypoint,
            int versionNumber,
            int fileCount,
            int profileCount,
            int chartCount,
            boolean existsLocally,
            Long localScriptId,
            boolean filesMissingLocally,
            boolean codeDiffers
    ) {
    }

    public record RestoreOutcome(
            int created,
            int updated,
            int skipped,
            int profilesRestored,
            int chartsRestored,
            List<String> details
    ) {
    }

    private final SettingsService settings;
    private final GitRepoService gitRepos;
    private final GitClient git;
    private final ScriptService scripts;
    private final LoadService load;
    private final LoadProfileStore profiles;
    private final ChartTemplateStore chartTemplates;
    private final ChartVariableStore chartVariables;
    private final StorageHealth storageHealth;
    private final AuditService audit;
    private final ReentrantLock syncLock = new ReentrantLock();

    public GitBackupService(SettingsService settings, GitRepoService gitRepos, GitClient git,
                            ScriptService scripts, LoadService load, LoadProfileStore profiles,
                            ChartTemplateStore chartTemplates, ChartVariableStore chartVariables,
                            StorageHealth storageHealth, AuditService audit) {
        this.settings = settings;
        this.gitRepos = gitRepos;
        this.git = git;
        this.scripts = scripts;
        this.load = load;
        this.profiles = profiles;
        this.chartTemplates = chartTemplates;
        this.chartVariables = chartVariables;
        this.storageHealth = storageHealth;
        this.audit = audit;
    }

    public BackupConfig getConfig() {
        Optional<String> raw = settings.get(SETTING_KEY);
        if (raw.isEmpty() || raw.get().isBlank()) {
            return BackupConfig.DEFAULT;
        }
        try {
            JsonNode n = MAPPER.readTree(raw.get());
            Long repoId = n.hasNonNull("repoId") && n.get("repoId").asLong(0) > 0 ? n.get("repoId").asLong() : null;
            String branch = GitClient.normalizeBranchName(n.path("branch").asText("main"));
            String subdir = GitClient.normalizeSubdir(n.path("subdir").asText(""));
            boolean autoSync = n.path("autoSync").asBoolean(false);
            boolean pruneDeleted = n.path("pruneDeleted").asBoolean(false);
            String lastSyncAt = n.hasNonNull("lastSyncAt") ? n.get("lastSyncAt").asText() : null;
            String lastCommit = n.hasNonNull("lastCommit") ? n.get("lastCommit").asText() : null;
            String lastStatus = n.path("lastStatus").asText("IDLE");
            String lastMessage = n.path("lastMessage").asText("");
            int lastCount = n.path("lastScriptCount").asInt(0);
            return new BackupConfig(repoId, branch, subdir, autoSync, pruneDeleted,
                    lastSyncAt, lastCommit, lastStatus, lastMessage, lastCount);
        } catch (Exception e) {
            return BackupConfig.DEFAULT;
        }
    }

    public BackupConfig saveConfig(Long repoId, String branch, String subdir, boolean autoSync, boolean pruneDeleted) {
        if (repoId != null) {
            gitRepos.get(repoId);
        }
        BackupConfig prev = getConfig();
        BackupConfig next = new BackupConfig(
                repoId,
                GitClient.normalizeBranchName(branch),
                GitClient.normalizeSubdir(subdir),
                autoSync && repoId != null,
                pruneDeleted,
                prev.lastSyncAt(),
                prev.lastCommit(),
                prev.lastStatus(),
                prev.lastMessage(),
                prev.lastScriptCount()
        );
        persistConfig(next);
        audit.record("git_backup.config", "setting", SETTING_KEY,
                Map.of("repoId", String.valueOf(repoId), "branch", next.branch(), "autoSync", next.autoSync()));
        return next;
    }

    private void persistConfig(BackupConfig cfg) {
        try {
            settings.set(SETTING_KEY, MAPPER.writeValueAsString(cfg));
        } catch (IOException e) {
            throw new IllegalStateException("Не удалось сохранить настройки Git-бэкапа", e);
        }
    }

    /** Фоновая автосинхронизация (выполняет коммит и push только при реальных изменениях дерева файлов). */
    @Scheduled(fixedDelay = 45_000, initialDelay = 30_000)
    public void scheduledAutoSync() {
        BackupConfig cfg = getConfig();
        if (!cfg.autoSync() || cfg.repoId() == null) {
            return;
        }
        if (!syncLock.tryLock()) {
            return;
        }
        try {
            backupAllInternal(cfg.repoId(), cfg.branch(), cfg.subdir(), cfg.pruneDeleted(), "auto-backup", true);
        } catch (Exception e) {
            log.warn("Фоновый Git-бэкап не выполнен: {}", e.getMessage());
        } finally {
            syncLock.unlock();
        }
    }

    public SyncOutcome backupAll(Long overrideRepoId, String overrideBranch, String overrideSubdir,
                                 Boolean overridePrune, String actor) throws IOException {
        BackupConfig cfg = getConfig();
        Long repoId = overrideRepoId != null ? overrideRepoId : cfg.repoId();
        if (repoId == null) {
            throw new IllegalArgumentException("Выберите Git-репозиторий для хранения бэкапов скриптов");
        }
        String branch = overrideBranch != null && !overrideBranch.isBlank()
                ? GitClient.normalizeBranchName(overrideBranch) : cfg.branch();
        String subdir = overrideSubdir != null
                ? GitClient.normalizeSubdir(overrideSubdir) : cfg.subdir();
        boolean prune = overridePrune != null ? overridePrune : cfg.pruneDeleted();

        syncLock.lock();
        try {
            return backupAllInternal(repoId, branch, subdir, prune, actor, false);
        } finally {
            syncLock.unlock();
        }
    }

    public SyncOutcome backupSingleScript(Long scriptId, String actor) throws IOException {
        BackupConfig cfg = getConfig();
        if (cfg.repoId() == null) {
            throw new IllegalArgumentException("Хранилище Git-бэкапа ещё не настроено (откройте раздел «Git-репозитории»)");
        }
        Script script = scripts.get(scriptId);
        GitRepo repo = gitRepos.get(cfg.repoId());
        String branch = cfg.branch();
        String basePrefix = effectivePrefix(repo, cfg.subdir());

        syncLock.lock();
        try {
            String slug = computeUniqueSlug(script, scripts.list());
            String scriptDir = joinPath(basePrefix, "scripts/" + slug);
            Map<String, byte[]> files = new TreeMap<>();
            exportOneScript(script, slug, basePrefix, files);
            if (files.isEmpty()) {
                throw new IllegalArgumentException("У скрипта «" + script.getName() + "» нет доступных файлов версии для выгрузки");
            }
            String commitMsg = "backup(script): " + script.getName() + " (k6-lab)";
            GitClient.PushResult pr = git.commitAndPush(
                    repo.getId(), repo.getUrl(), gitRepos.authFor(repo),
                    branch, scriptDir, files, Set.of(), actor, commitMsg
            );
            String nowIso = Instant.now().toString();
            String msg = pr.changed()
                    ? "Скрипт «" + script.getName() + "» выгружен в ветку " + branch + " (" + pr.commit().substring(0, 8) + ")"
                    : "Скрипт «" + script.getName() + "» в Git уже актуален (" + pr.commit().substring(0, 8) + ")";
            persistConfig(new BackupConfig(
                    cfg.repoId(), cfg.branch(), cfg.subdir(), cfg.autoSync(), cfg.pruneDeleted(),
                    nowIso, pr.commit(), pr.changed() ? "OK" : "UP_TO_DATE", msg, scripts.list().size()
            ));
            audit.record("git_backup.push_script", "script", scriptId,
                    Map.of("repo", repo.getName(), "branch", branch, "commit", pr.commit(), "changed", pr.changed()));
            return new SyncOutcome(true, pr.changed(), pr.commit(), branch, 1, nowIso, msg);
        } finally {
            syncLock.unlock();
        }
    }

    private SyncOutcome backupAllInternal(Long repoId, String branch, String subdir, boolean pruneDeleted,
                                          String actor, boolean quietAuditWhenUnchanged) throws IOException {
        GitRepo repo = gitRepos.get(repoId);
        String basePrefix = effectivePrefix(repo, subdir);
        List<Script> allScripts = scripts.list();
        Map<Long, String> slugs = computeAllSlugs(allScripts);

        Map<String, byte[]> files = new TreeMap<>();
        Set<String> deletePrefixes = new LinkedHashSet<>();
        int exportedCount = 0;
        List<String> readmeRows = new ArrayList<>();

        for (Script s : allScripts) {
            String slug = slugs.get(s.getId());
            int before = files.size();
            Optional<ScriptVersion> lv = exportOneScript(s, slug, basePrefix, files);
            if (files.size() > before && lv.isPresent()) {
                exportedCount++;
                int profCnt = profiles.list(s.getId()).size();
                int chartCnt = chartTemplates.list(s.getId()).size();
                readmeRows.add("| " + escMd(s.getName()) + " | `scripts/" + slug + "` | v" + lv.get().getNumber()
                        + " | `" + lv.get().getEntrypoint() + "` | " + profCnt + " | " + chartCnt + " |");
                if (!pruneDeleted) {
                    // Заменяем каталог конкретного скрипта целиком, чтобы удалённые внутри скрипта файлы/профили не оставались
                    deletePrefixes.add(joinPath(basePrefix, "scripts/" + slug));
                }
            }
        }

        StringBuilder md = new StringBuilder();
        md.append("# Резервная копия скриптов k6-lab\n\n");
        md.append("Автоматически синхронизируемое хранилище сценариев нагрузки, профилей, SLA и графиков отчёта.\n\n");
        if (readmeRows.isEmpty()) {
            md.append("_Скрипты отсутствуют._\n");
        } else {
            md.append("| Скрипт | Каталог | Версия | Точка входа | Профилей | Графиков |\n");
            md.append("|--------|---------|--------|-------------|----------|----------|\n");
            readmeRows.forEach(r -> md.append(r).append("\n"));
        }
        files.put(joinPath(basePrefix, "README.md"), md.toString().getBytes(StandardCharsets.UTF_8));

        String replaceScope = pruneDeleted ? joinPath(basePrefix, "scripts") : null;
        String commitMsg = "backup(k6-lab): синхронизация скриптов (" + exportedCount + ")";

        try {
            GitClient.PushResult pr = git.commitAndPush(
                    repo.getId(), repo.getUrl(), gitRepos.authFor(repo),
                    branch, replaceScope, files, deletePrefixes, actor, commitMsg
            );
            String nowIso = Instant.now().toString();
            String msg = pr.changed()
                    ? "Синхронизировано скриптов: " + exportedCount + " → " + repo.getName() + ":" + branch + " (" + pr.commit().substring(0, 8) + ")"
                    : "Изменений нет — все " + exportedCount + " скриптов уже актуальны в Git (" + pr.commit().substring(0, 8) + ")";

            BackupConfig cur = getConfig();
            if (repoId.equals(cur.repoId()) || cur.repoId() == null) {
                persistConfig(new BackupConfig(
                        cur.repoId() != null ? cur.repoId() : repoId,
                        cur.repoId() != null ? cur.branch() : branch,
                        cur.repoId() != null ? cur.subdir() : subdir,
                        cur.autoSync(),
                        cur.pruneDeleted(),
                        nowIso,
                        pr.commit(),
                        pr.changed() ? "OK" : "UP_TO_DATE",
                        msg,
                        exportedCount
                ));
            }
            if (pr.changed() || !quietAuditWhenUnchanged) {
                audit.record("git_backup.push_all", "git_repo", repoId,
                        Map.of("branch", branch, "scripts", exportedCount, "commit", pr.commit(), "changed", pr.changed()));
            }
            return new SyncOutcome(true, pr.changed(), pr.commit(), branch, exportedCount, nowIso, msg);
        } catch (IOException | RuntimeException e) {
            BackupConfig cur = getConfig();
            if (repoId.equals(cur.repoId())) {
                persistConfig(new BackupConfig(
                        cur.repoId(), cur.branch(), cur.subdir(), cur.autoSync(), cur.pruneDeleted(),
                        Instant.now().toString(), cur.lastCommit(), "ERROR",
                        e.getMessage() != null ? e.getMessage() : e.toString(), exportedCount
                ));
            }
            throw e;
        }
    }

    private Optional<ScriptVersion> exportOneScript(Script s, String slug, String basePrefix, Map<String, byte[]> out) throws IOException {
        Optional<ScriptVersion> lvOpt = scripts.latest(s.getId());
        if (lvOpt.isEmpty() || !scripts.hasFiles(lvOpt.get())) {
            return Optional.empty();
        }
        ScriptVersion lv = lvOpt.get();
        String root = joinPath(basePrefix, "scripts/" + slug);

        // 1. Исходные файлы скрипта (распакованные для читаемых git diff)
        Map<String, byte[]> srcFiles = ZipUtil.readAll(new ByteArrayInputStream(scripts.zipBytes(lv)));
        for (var fe : srcFiles.entrySet()) {
            String safe = ZipUtil.safePath(fe.getKey());
            if (safe != null && !safe.isEmpty()) {
                out.put(joinPath(root, "src/" + safe), fe.getValue());
            }
        }

        // 2. Паспорт скрипта (детерминированный JSON без плавающих таймстампов, чтобы не порождать лишних коммитов)
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("formatVersion", 1);
        meta.put("name", s.getName());
        meta.put("description", s.getDescription() != null ? s.getDescription() : "");
        meta.put("entrypoint", lv.getEntrypoint());
        meta.put("versionNumber", lv.getNumber());
        meta.put("sha256", lv.getSha256());
        meta.put("generatorCount", s.getGeneratorCount());
        meta.put("warmupSec", s.getWarmupSec());
        meta.put("k6Overrides", s.getK6Overrides() != null ? s.getK6Overrides() : Map.of());
        if (s.getGitBranch() != null) {
            meta.put("sourceGitBranch", s.getGitBranch());
        }
        if (s.getGitEntrypoint() != null) {
            meta.put("sourceGitEntrypoint", s.getGitEntrypoint());
        }
        out.put(joinPath(root, "k6lab-script.json"), jsonBytes(meta));

        // 3. Базовая нагрузка
        try {
            LoadForm baseForm = load.toForm(s.getId());
            out.put(joinPath(root, "load/base-load.json"), LoadJson.toJson(baseForm).getBytes(StandardCharsets.UTF_8));
        } catch (RuntimeException ignored) {
            // если базовая форма не собралась, пропускаем без срыва бэкапа
        }

        // 4. Профили нагрузки (включая папки профилей)
        List<LoadProfileStore.Profile> profList = profiles.list(s.getId());
        int idx = 1;
        for (var p : profList) {
            Map<String, Object> pm = new LinkedHashMap<>();
            pm.put("name", p.name());
            pm.put("folder", p.folder() != null ? p.folder() : "");
            try {
                pm.put("config", MAPPER.readTree(p.data()));
            } catch (IOException e) {
                pm.put("configRaw", p.data());
            }
            String pFile = String.format(Locale.ROOT, "%03d-%s.json", idx++, slugifyName(p.name()));
            out.put(joinPath(root, "load/profiles/" + pFile), jsonBytes(pm));
        }

        // 5. Пользовательские графики отчёта и переменные $var
        var tpls = chartTemplates.list(s.getId());
        if (!tpls.isEmpty()) {
            List<Map<String, Object>> chartArr = new ArrayList<>();
            for (var t : tpls) {
                Map<String, Object> tm = new LinkedHashMap<>();
                tm.put("name", t.name());
                tm.put("unit", t.unit() != null ? t.unit() : "");
                tm.put("enabled", t.enabled());
                tm.put("queries", t.queries());
                tm.put("variables", t.variables());
                chartArr.add(tm);
            }
            out.put(joinPath(root, "charts/templates.json"), jsonBytes(chartArr));
        }
        Map<String, String> vars = chartVariables.map(s.getId());
        if (!vars.isEmpty()) {
            out.put(joinPath(root, "charts/variables.json"), jsonBytes(vars));
        }
        return Optional.of(lv);
    }

    /** Скачивает ветку бэкапа и возвращает список найденных в Git скриптов со сверкой против локальной базы. */
    public List<RemoteScriptEntry> inspectRemote(Long overrideRepoId, String overrideBranch, String overrideSubdir) throws IOException {
        BackupConfig cfg = getConfig();
        Long repoId = overrideRepoId != null ? overrideRepoId : cfg.repoId();
        if (repoId == null) {
            throw new IllegalArgumentException("Выберите Git-репозиторий");
        }
        GitRepo repo = gitRepos.get(repoId);
        String branch = overrideBranch != null && !overrideBranch.isBlank()
                ? GitClient.normalizeBranchName(overrideBranch) : cfg.branch();
        String basePrefix = effectivePrefix(repo, overrideSubdir != null ? overrideSubdir : cfg.subdir());

        gitRepos.refresh(repo.getId());
        String scriptsRoot = joinPath(basePrefix, "scripts");
        Map<String, byte[]> allFiles = git.readBranchFiles(repo.getId(), branch, scriptsRoot);
        if (allFiles.isEmpty()) {
            return List.of();
        }

        Map<String, Script> localByName = new LinkedHashMap<>();
        for (Script s : scripts.list()) {
            localByName.put(s.getName().toLowerCase(Locale.ROOT), s);
        }

        Map<String, ParsedRemoteBundle> bundles = parseBundles(scriptsRoot, allFiles);
        List<RemoteScriptEntry> out = new ArrayList<>();
        for (var b : bundles.values()) {
            Script existing = localByName.get(b.name.toLowerCase(Locale.ROOT));
            boolean missingFiles = false;
            boolean codeDiffers = true;
            if (existing != null) {
                Optional<ScriptVersion> lv = scripts.latest(existing.getId());
                if (lv.isEmpty() || !scripts.hasFiles(lv.get())) {
                    missingFiles = true;
                } else {
                    try {
                        byte[] remoteZip = ZipUtil.write(b.srcFiles);
                        String remoteSha = Hashes.sha256(remoteZip);
                        codeDiffers = !remoteSha.equalsIgnoreCase(lv.get().getSha256());
                    } catch (IOException ignored) {
                        codeDiffers = true;
                    }
                }
            }
            out.add(new RemoteScriptEntry(
                    b.slug,
                    b.name,
                    b.description,
                    b.entrypoint,
                    b.versionNumber,
                    b.srcFiles.size(),
                    b.profileJsons.size(),
                    b.chartTemplatesCount,
                    existing != null,
                    existing != null ? existing.getId() : null,
                    missingFiles,
                    codeDiffers
            ));
        }
        return out;
    }

    /** Восстанавливает выбранные скрипты (исходники, базовую нагрузку, профили с папками, графики отчёта) из Git-бэкапа. */
    public RestoreOutcome restoreFromGit(Long overrideRepoId, String overrideBranch, String overrideSubdir,
                                         List<String> selectedSlugs, boolean overwriteExisting, String actor) throws IOException {
        BackupConfig cfg = getConfig();
        Long repoId = overrideRepoId != null ? overrideRepoId : cfg.repoId();
        if (repoId == null) {
            throw new IllegalArgumentException("Выберите Git-репозиторий");
        }
        GitRepo repo = gitRepos.get(repoId);
        String branch = overrideBranch != null && !overrideBranch.isBlank()
                ? GitClient.normalizeBranchName(overrideBranch) : cfg.branch();
        String basePrefix = effectivePrefix(repo, overrideSubdir != null ? overrideSubdir : cfg.subdir());

        gitRepos.refresh(repo.getId());
        String scriptsRoot = joinPath(basePrefix, "scripts");
        Map<String, byte[]> allFiles = git.readBranchFiles(repo.getId(), branch, scriptsRoot);
        Map<String, ParsedRemoteBundle> bundles = parseBundles(scriptsRoot, allFiles);
        if (bundles.isEmpty()) {
            throw new IllegalArgumentException("В ветке «" + branch + "» не найдено резервных копий скриптов k6-lab");
        }

        Set<String> filter = selectedSlugs == null || selectedSlugs.isEmpty()
                ? null : new LinkedHashSet<>(selectedSlugs);

        Map<String, Script> localByName = new LinkedHashMap<>();
        for (Script s : scripts.list()) {
            localByName.put(s.getName().toLowerCase(Locale.ROOT), s);
        }

        int created = 0;
        int updated = 0;
        int skipped = 0;
        int profRestored = 0;
        int chartsRestored = 0;
        List<String> details = new ArrayList<>();
        String user = actor == null || actor.isBlank() ? "admin" : actor;

        for (var b : bundles.values()) {
            if (filter != null && !filter.contains(b.slug)) {
                continue;
            }
            if (b.srcFiles.isEmpty()) {
                skipped++;
                details.add("Пропущен «" + b.name + "»: нет файлов в src/");
                continue;
            }

            Script existing = localByName.get(b.name.toLowerCase(Locale.ROOT));
            Long targetScriptId;
            if (existing == null) {
                ScriptVersion sv = scripts.createFromFiles(
                        b.name,
                        b.description,
                        b.srcFiles,
                        b.entrypoint,
                        "Восстановлено из Git-бэкапа (" + repo.getName() + ":" + branch + ")"
                );
                targetScriptId = sv.getScriptId();
                created++;
                details.add("Создан скрипт «" + b.name + "» (v1)");
            } else {
                Optional<ScriptVersion> lv = scripts.latest(existing.getId());
                boolean missingLocalFiles = lv.isEmpty() || !scripts.hasFiles(lv.get());
                if (!overwriteExisting && !missingLocalFiles) {
                    skipped++;
                    details.add("Пропущен существующий «" + b.name + "»");
                    continue;
                }
                targetScriptId = existing.getId();
                boolean needNewVersion = missingLocalFiles;
                if (!needNewVersion && lv.isPresent()) {
                    String remoteSha = Hashes.sha256(ZipUtil.write(b.srcFiles));
                    needNewVersion = !remoteSha.equalsIgnoreCase(lv.get().getSha256());
                }
                if (needNewVersion) {
                    ScriptVersion sv = scripts.addVersionFromFiles(
                            targetScriptId,
                            b.srcFiles,
                            b.entrypoint,
                            "Синхронизировано из Git-бэкапа (" + repo.getName() + ":" + branch + ")"
                    );
                    details.add("Обновлён код «" + b.name + "» → v" + sv.getNumber());
                } else {
                    details.add("Обновлены профили/настройки «" + b.name + "»");
                }
                updated++;
            }

            // Сначала восстанавливаем профили нагрузки (чтобы пустая базовая нагрузка тоже была допустима)
            for (byte[] profRaw : b.profileJsons) {
                try {
                    JsonNode pn = MAPPER.readTree(profRaw);
                    String pName = pn.path("name").asText("").strip();
                    String pFolder = pn.path("folder").asText("");
                    JsonNode cfgNode = pn.get("config");
                    String cfgStr = cfgNode != null && cfgNode.isObject()
                            ? MAPPER.writeValueAsString(cfgNode)
                            : pn.path("configRaw").asText("");
                    if (!pName.isEmpty() && !cfgStr.isBlank()) {
                        LoadJson.fromJson(cfgStr); // валидация
                        profiles.save(targetScriptId, pName, pFolder, cfgStr, user);
                        profRestored++;
                    }
                } catch (Exception e) {
                    log.warn("Профиль скрипта «{}» не восстановлен: {}", b.name, e.getMessage());
                }
            }

            // Базовая нагрузка и ручные правки k6Overrides
            if (b.baseLoadJson != null) {
                try {
                    LoadForm form = LoadJson.fromJson(new String(b.baseLoadJson, StandardCharsets.UTF_8));
                    load.save(targetScriptId, form);
                } catch (Exception e) {
                    log.debug("Базовая нагрузка «{}» пропущена при восстановлении: {}", b.name, e.getMessage());
                }
            }
            if (b.k6Overrides != null && !b.k6Overrides.isEmpty()) {
                try {
                    load.saveK6Overrides(targetScriptId, b.k6Overrides);
                } catch (Exception ignored) {
                }
            }

            // Переменные и шаблоны графиков отчёта
            if (b.chartVariablesJson != null) {
                try {
                    Map<String, String> vars = MAPPER.readValue(b.chartVariablesJson, new TypeReference<>() {});
                    vars.forEach((k, v) -> {
                        if (k != null && !k.isBlank()) {
                            chartVariables.save(targetScriptId, k.strip(), v != null ? v : "");
                        }
                    });
                } catch (Exception ignored) {
                }
            }
            if (b.chartTemplatesJson != null) {
                try {
                    JsonNode arr = MAPPER.readTree(b.chartTemplatesJson);
                    if (arr != null && arr.isArray()) {
                        for (JsonNode tn : arr) {
                            String tName = tn.path("name").asText("").strip();
                            if (tName.isEmpty()) continue;
                            String unit = tn.path("unit").asText("");
                            boolean enabled = tn.path("enabled").asBoolean(true);
                            List<ChartTemplateStore.Query> qs = new ArrayList<>();
                            for (JsonNode qn : tn.path("queries")) {
                                String pq = qn.path("promql").asText("").strip();
                                if (!pq.isEmpty()) {
                                    qs.add(new ChartTemplateStore.Query(pq, qn.path("legend").asText("")));
                                }
                            }
                            if (qs.isEmpty()) continue;
                            Map<String, String> tv = new LinkedHashMap<>();
                            if (tn.has("variables") && tn.get("variables").isObject()) {
                                tn.get("variables").fields().forEachRemaining(ev -> tv.put(ev.getKey(), ev.getValue().asText("")));
                            }
                            chartTemplates.save(targetScriptId, null, tName, qs, unit.isEmpty() ? null : unit, enabled, tv, user);
                            chartsRestored++;
                        }
                    }
                } catch (Exception e) {
                    log.warn("Графики скрипта «{}» не восстановлены: {}", b.name, e.getMessage());
                }
            }
        }

        storageHealth.invalidate();
        audit.record("git_backup.restore", "git_repo", repoId,
                Map.of("branch", branch, "created", created, "updated", updated, "profiles", profRestored));
        return new RestoreOutcome(created, updated, skipped, profRestored, chartsRestored, details);
    }

    private static final class ParsedRemoteBundle {
        final String slug;
        String name;
        String description = "";
        String entrypoint = null;
        int versionNumber = 1;
        Map<String, Object> k6Overrides = Map.of();
        final Map<String, byte[]> srcFiles = new LinkedHashMap<>();
        byte[] baseLoadJson;
        final List<byte[]> profileJsons = new ArrayList<>();
        byte[] chartTemplatesJson;
        byte[] chartVariablesJson;
        int chartTemplatesCount = 0;

        ParsedRemoteBundle(String slug) {
            this.slug = slug;
            this.name = slug;
        }
    }

    private static Map<String, ParsedRemoteBundle> parseBundles(String scriptsRoot, Map<String, byte[]> allFiles) {
        String prefix = scriptsRoot + "/";
        Map<String, ParsedRemoteBundle> bundles = new LinkedHashMap<>();
        for (var e : allFiles.entrySet()) {
            String path = e.getKey();
            if (!path.startsWith(prefix)) continue;
            String rest = path.substring(prefix.length());
            int slash = rest.indexOf('/');
            if (slash <= 0) continue;
            String slug = rest.substring(0, slash);
            String inner = rest.substring(slash + 1);
            ParsedRemoteBundle b = bundles.computeIfAbsent(slug, ParsedRemoteBundle::new);

            if ("k6lab-script.json".equals(inner)) {
                try {
                    JsonNode meta = MAPPER.readTree(e.getValue());
                    String n = meta.path("name").asText("").strip();
                    if (!n.isEmpty()) b.name = n;
                    b.description = meta.path("description").asText("");
                    String ep = meta.path("entrypoint").asText("").strip();
                    if (!ep.isEmpty()) b.entrypoint = ep;
                    b.versionNumber = meta.path("versionNumber").asInt(1);
                    if (meta.has("k6Overrides") && meta.get("k6Overrides").isObject()) {
                        b.k6Overrides = MAPPER.convertValue(meta.get("k6Overrides"), new TypeReference<>() {});
                    }
                } catch (Exception ignored) {
                }
            } else if (inner.startsWith("src/") && inner.length() > 4) {
                b.srcFiles.put(inner.substring(4), e.getValue());
            } else if ("load/base-load.json".equals(inner)) {
                b.baseLoadJson = e.getValue();
            } else if (inner.startsWith("load/profiles/") && inner.endsWith(".json")) {
                b.profileJsons.add(e.getValue());
            } else if ("charts/templates.json".equals(inner)) {
                b.chartTemplatesJson = e.getValue();
                try {
                    JsonNode arr = MAPPER.readTree(e.getValue());
                    if (arr != null && arr.isArray()) b.chartTemplatesCount = arr.size();
                } catch (Exception ignored) {
                }
            } else if ("charts/variables.json".equals(inner)) {
                b.chartVariablesJson = e.getValue();
            }
        }
        return bundles;
    }

    private static String effectivePrefix(GitRepo repo, String subdirOverride) {
        String s = subdirOverride != null && !subdirOverride.isBlank()
                ? GitClient.normalizeSubdir(subdirOverride)
                : GitClient.normalizeSubdir(repo.getSubdir());
        return s;
    }

    private static String joinPath(String prefix, String rel) {
        String p = GitClient.normalizeSubdir(prefix);
        String r = GitClient.normalizeSubdir(rel);
        if (p.isEmpty()) return r;
        if (r.isEmpty()) return p;
        return p + "/" + r;
    }

    private static Map<Long, String> computeAllSlugs(List<Script> scripts) {
        Map<Long, String> out = new LinkedHashMap<>();
        Set<String> used = new HashSet<>();
        for (Script s : scripts) {
            String base = slugifyName(s.getName());
            String cand = base;
            if (!used.add(cand.toLowerCase(Locale.ROOT))) {
                cand = base + "-" + s.getId();
                used.add(cand.toLowerCase(Locale.ROOT));
            }
            out.put(s.getId(), cand);
        }
        return out;
    }

    private static String computeUniqueSlug(Script target, List<Script> all) {
        return computeAllSlugs(all).getOrDefault(target.getId(), slugifyName(target.getName()));
    }

    static String slugifyName(String raw) {
        if (raw == null || raw.isBlank()) {
            return "script";
        }
        String s = raw.strip()
                .replaceAll("[^\\p{L}\\p{N}._-]+", "-")
                .replaceAll("-{2,}", "-")
                .replaceAll("^[.-]+|[.-]+$", "");
        if (s.isEmpty()) {
            return "script";
        }
        return s.length() > 80 ? s.substring(0, 80) : s;
    }

    private static byte[] jsonBytes(Object obj) throws IOException {
        return (MAPPER.writeValueAsString(obj) + "\n").getBytes(StandardCharsets.UTF_8);
    }

    private static String escMd(String s) {
        return s == null ? "" : s.replace("|", "\\|").replace("\n", " ");
    }
}
