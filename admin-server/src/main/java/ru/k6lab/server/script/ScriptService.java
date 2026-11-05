package ru.k6lab.server.script;

import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import ru.k6lab.core.script.ScriptAnalyzer;
import ru.k6lab.core.util.ZipUtil;
import ru.k6lab.server.audit.AuditService;
import ru.k6lab.server.common.Hashes;
import ru.k6lab.server.common.StorageService;
import ru.k6lab.server.git.GitClient;
import ru.k6lab.server.git.GitRepo;
import ru.k6lab.server.git.GitRepoService;
import ru.k6lab.server.script.ScriptVersion.FileEntry;
import ru.k6lab.server.script.ScriptVersion.OpEntry;
import ru.k6lab.server.script.ScriptVersion.Source;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Скрипты и версии: загрузка (js/zip/tar.gz), редактор, откат, импорт из Git. Каждая версия проходит валидацию:
 * k6 inspect (опции), блокирующая проверка опций, извлечение операций, определение admin.js, предупреждения.
 */
@Service
public class ScriptService {

    private static final Logger log = LoggerFactory.getLogger(ScriptService.class);
    private static final long MAX_UPLOAD = 512L * 1024 * 1024;
    private static final List<String> DEFAULT_ENTRYPOINTS = List.of("main.js", "script.js", "index.js", "test.js");
    private static final Set<String> SCRIPT_EXT = Set.of("js", "mjs", "ts");

    private final ScriptRepository scripts;
    private final ScriptVersionRepository versions;
    private final StorageService storage;
    private final K6Tool k6;
    private final AdminHelper helper;
    private final AuditService audit;
    private final GitClient git;
    private final GitRepoService gitRepos;
    private final org.springframework.transaction.support.TransactionTemplate tx;

    public ScriptService(ScriptRepository scripts, ScriptVersionRepository versions, StorageService storage, K6Tool k6,
                         AdminHelper helper, AuditService audit, GitClient git, GitRepoService gitRepos,
                         org.springframework.transaction.PlatformTransactionManager txm) {
        this.scripts = scripts;
        this.versions = versions;
        this.storage = storage;
        this.k6 = k6;
        this.helper = helper;
        this.audit = audit;
        this.git = git;
        this.gitRepos = gitRepos;
        this.tx = new org.springframework.transaction.support.TransactionTemplate(txm);
    }

    // ------------------------------------------------------------------ запросы

    public List<Script> list() {
        return scripts.findAllByOrderByNameAsc();
    }

    public Script get(Long id) {
        return scripts.findById(id).orElseThrow(() -> new IllegalArgumentException("Скрипт не найден"));
    }

    public List<ScriptVersion> versionsOf(Long scriptId) {
        return versions.findByScriptIdOrderByNumberDesc(scriptId);
    }

    public ScriptVersion version(Long scriptId, Long versionId) {
        ScriptVersion v = versions.findById(versionId).orElseThrow(() -> new IllegalArgumentException("Версия не найдена"));
        if (!v.getScriptId().equals(scriptId)) {
            throw new IllegalArgumentException("Версия не принадлежит скрипту");
        }
        return v;
    }

    public ScriptVersion version(Long versionId) {
        return versions.findById(versionId).orElseThrow(() -> new IllegalArgumentException("Версия не найдена"));
    }

    public Optional<ScriptVersion> latest(Long scriptId) {
        return versions.findFirstByScriptIdOrderByNumberDesc(scriptId);
    }

    /** Файл версии на месте в хранилище (после переустановки без переноса data/storage его может не быть). */
    public boolean hasFiles(ScriptVersion v) {
        return storage.exists(v.getStorageKey());
    }

    public byte[] zipBytes(ScriptVersion v) throws IOException {
        try (InputStream in = storage.open(v.getStorageKey())) {
            return in.readAllBytes();
        }
    }

    // ------------------------------------------------------------------ создание версий

    public ScriptVersion createFromUpload(String name, String description, String filename, InputStream content,
                                          String entrypoint, String comment) throws IOException {
        Script s = tx.execute(st -> {
            requireName(name);
            if (scripts.existsByNameIgnoreCase(name.trim())) {
                throw new IllegalArgumentException("Скрипт с таким именем уже есть");
            }
            Script n = new Script();
            n.setName(name.trim());
            n.setDescription(blankToNull(description));
            Script saved = scripts.save(n);
            audit.record("script.create", "script", saved.getId(), Map.of("name", saved.getName()));
            return saved;
        });
        try {
            return addVersionFromUpload(s.getId(), filename, content, entrypoint, comment);
        } catch (IOException | RuntimeException e) {
            // скрипт без единой версии бесполезен - откатываем создание
            tx.executeWithoutResult(st -> scripts.deleteById(s.getId()));
            throw e;
        }
    }

    public ScriptVersion addVersionFromUpload(Long scriptId, String filename, InputStream content, String entrypoint,
                                              String comment) throws IOException {
        Script s = get(scriptId);
        byte[] data = readLimited(content);
        List<String> notes = new ArrayList<>();
        Map<String, byte[]> files = normalizeUpload(filename, data, notes);
        String entry = chooseEntrypoint(files, entrypoint, filename);
        return finalizeVersion(s, files, entry, Source.UPLOAD, comment, null, null, null, null, null, notes);
    }

    /** Сохранение из редактора = новая версия. */
    public ScriptVersion saveEdit(Long scriptId, Long baseVersionId, String file, String newContent, String comment) throws IOException {
        Script s = get(scriptId);
        ScriptVersion base = version(scriptId, baseVersionId);
        String path = ZipUtil.safePath(file);
        if (path == null) {
            throw new IllegalArgumentException("Недопустимый путь файла");
        }
        Map<String, byte[]> files = ZipUtil.readAll(new ByteArrayInputStream(zipBytes(base)));
        if (!files.containsKey(path)) {
            throw new IllegalArgumentException("Файла " + path + " нет в версии");
        }
        files.put(path, newContent.getBytes(StandardCharsets.UTF_8));
        return finalizeVersion(s, files, base.getEntrypoint(), Source.EDITOR,
                blankToNull(comment) != null ? comment : "правка " + path, base.getId(),
                base.getGitRepoId(), base.getGitRef(), base.getGitBranch(), base.getCommitHash(), new ArrayList<>());
    }

    /** Откат = новая версия из содержимого старой. */
    public ScriptVersion rollback(Long scriptId, Long versionId) throws IOException {
        Script s = get(scriptId);
        ScriptVersion old = version(scriptId, versionId);
        Map<String, byte[]> files = ZipUtil.readAll(new ByteArrayInputStream(zipBytes(old)));
        return finalizeVersion(s, files, old.getEntrypoint(), Source.ROLLBACK, "откат к версии " + old.getNumber(),
                old.getId(), old.getGitRepoId(), old.getGitRef(), old.getGitBranch(), old.getCommitHash(), new ArrayList<>());
    }

    /**
     * Импорт из Git: ветка/тег/коммит + путь к entrypoint. Если для этого коммита и entrypoint версия уже есть,
     * возвращается она. scriptId == null создаёт новый скрипт с именем name.
     */
    public ScriptVersion importFromGit(Long scriptId, String name, Long repoId, String ref, String entrypoint,
                                       String comment) throws IOException {
        GitRepo repo = gitRepos.get(repoId);
        if (entrypoint == null || entrypoint.isBlank()) {
            throw new IllegalArgumentException("Укажите путь к entrypoint относительно каталога репозитория");
        }
        if (ref == null || ref.isBlank()) {
            throw new IllegalArgumentException("Укажите ветку, тег или коммит");
        }
        git.sync(repo.getId(), repo.getUrl(), gitRepos.authFor(repo));
        String commit = git.resolve(repo.getId(), ref.trim());
        boolean isBranch = git.refs(repo.getId()).stream().anyMatch(r -> r.kind().equals("branch") && r.name().equals(ref.trim()));
        Script s;
        if (scriptId == null) {
            s = tx.execute(st -> {
                requireName(name);
                if (scripts.existsByNameIgnoreCase(name.trim())) {
                    throw new IllegalArgumentException("Скрипт с таким именем уже есть");
                }
                Script n = new Script();
                n.setName(name.trim());
                n.setGitRepoId(repo.getId());
                n.setGitBranch(isBranch ? ref.trim() : null);
                n.setGitEntrypoint(entrypoint.trim());
                Script saved = scripts.save(n);
                audit.record("script.create", "script", saved.getId(), Map.of("name", saved.getName(), "git", repo.getName()));
                return saved;
            });
        } else {
            s = get(scriptId);
            Optional<ScriptVersion> same = versions.findByScriptIdAndCommitHash(s.getId(), commit)
                    .filter(v -> v.getEntrypoint().equals(entrypoint.trim()));
            if (same.isPresent()) {
                return same.get();
            }
            if (s.getGitRepoId() == null) {
                Script fs = s;
                tx.executeWithoutResult(st -> {
                    Script x = scripts.findById(fs.getId()).orElseThrow();
                    x.setGitRepoId(repo.getId());
                    x.setGitBranch(isBranch ? ref.trim() : null);
                    x.setGitEntrypoint(entrypoint.trim());
                });
            }
        }
        try {
            byte[] zip = git.exportZip(repo.getId(), commit, repo.getSubdir());
            Map<String, byte[]> files = ZipUtil.readAll(new ByteArrayInputStream(zip));
            List<String> notes = new ArrayList<>();
            String entry = chooseEntrypoint(files, entrypoint.trim(), null);
            return finalizeVersion(s, files, entry, Source.GIT, blankToNull(comment), null, repo.getId(), ref.trim(),
                    isBranch ? ref.trim() : null, commit, notes);
        } catch (IOException | RuntimeException e) {
            if (scriptId == null && versions.countByScriptId(s.getId()) == 0) {
                tx.executeWithoutResult(st -> scripts.deleteById(s.getId()));
            }
            throw e;
        }
    }

    /** «Последняя версия ветки Git» с фиксацией коммита (используется при запуске сценария). */
    public ScriptVersion importLatestOfBranch(Long scriptId) throws IOException {
        Script s = get(scriptId);
        if (s.getGitRepoId() == null || s.getGitBranch() == null) {
            throw new IllegalStateException("Скрипт не привязан к ветке Git");
        }
        return importFromGit(scriptId, null, s.getGitRepoId(), s.getGitBranch(), s.getGitEntrypoint(),
                "последняя версия ветки " + s.getGitBranch());
    }

    /** Новые коммиты в ветке относительно последней импортированной версии. */
    public List<GitClient.CommitInfo> checkGitUpdates(Long scriptId) throws IOException {
        Script s = get(scriptId);
        if (s.getGitRepoId() == null || s.getGitBranch() == null) {
            throw new IllegalStateException("Скрипт не привязан к ветке Git");
        }
        GitRepo repo = gitRepos.get(s.getGitRepoId());
        git.sync(repo.getId(), repo.getUrl(), gitRepos.authFor(repo));
        String head = git.resolve(repo.getId(), s.getGitBranch());
        Optional<ScriptVersion> last = versions.findFirstByScriptIdAndGitBranchOrderByNumberDesc(scriptId, s.getGitBranch());
        String from = last.map(ScriptVersion::getCommitHash).orElse(null);
        if (head.equals(from)) {
            return List.of();
        }
        return git.log(repo.getId(), from, head, 50);
    }

    public void delete(Long scriptId) {
        List<ScriptVersion> vs = versions.findByScriptIdOrderByNumberDesc(scriptId);
        tx.executeWithoutResult(st -> scripts.deleteById(scriptId));
        for (ScriptVersion v : vs) {
            try {
                storage.delete(v.getStorageKey());
            } catch (IOException e) {
                log.warn("Не удалось удалить файл версии {}: {}", v.getId(), e.toString());
            }
        }
        audit.record("script.delete", "script", scriptId, null);
    }

    // ------------------------------------------------------------------ ручное управление операциями

    public ScriptVersion addOperation(Long scriptId, Long versionId, String opName) {
        String n = opName == null ? "" : opName.trim();
        if (!n.matches("[A-Za-z_$][\\w$]*")) {
            throw new IllegalArgumentException("Имя операции должно быть именем JS-функции");
        }
        return tx.execute(st -> {
            ScriptVersion v = version(scriptId, versionId);
            if (v.getOperations().stream().noneMatch(o -> o.name().equals(n))) {
                List<OpEntry> ops = new ArrayList<>(v.getOperations());
                ops.add(new OpEntry(n, ScriptAnalyzer.Operation.MANUAL));
                v.setOperations(ops);
                versions.save(v);
            }
            return v;
        });
    }

    public ScriptVersion removeOperation(Long scriptId, Long versionId, String opName) {
        return tx.execute(st -> {
            ScriptVersion v = version(scriptId, versionId);
            List<OpEntry> ops = new ArrayList<>(v.getOperations());
            ops.removeIf(o -> o.name().equals(opName));
            v.setOperations(ops);
            return versions.save(v);
        });
    }

    // ------------------------------------------------------------------ ядро: нормализация, валидация, сохранение

    private ScriptVersion finalizeVersion(Script script, Map<String, byte[]> rawFiles, String entrypoint, Source source,
                                          String comment, Long parentVersionId, Long gitRepoId, String gitRef,
                                          String gitBranch, String commit, List<String> notes) throws IOException {
        Map<String, byte[]> files = new LinkedHashMap<>(rawFiles);
        // __admin/ принадлежит админке и подкладывается при подготовке запуска; пользовательскую копию не храним
        if (files.keySet().removeIf(p -> p.startsWith("__admin/"))) {
            notes.add("Каталог __admin/ из архива проигнорирован: актуальный admin.js админка кладёт сама при запуске.");
        }
        if (!files.containsKey(entrypoint)) {
            throw new IllegalArgumentException("Entrypoint «" + entrypoint + "» не найден среди файлов версии");
        }
        byte[] zip = ZipUtil.write(files);
        String sha = sha256(zip);

        // анализ исходников
        String entrySource = new String(files.get(entrypoint), StandardCharsets.UTF_8);
        ScriptAnalyzer.Analysis analysis = ScriptAnalyzer.analyze(entrySource);
        boolean usesHelper = analysis.usesHelper();
        for (Map.Entry<String, byte[]> f : files.entrySet()) {
            if (!usesHelper && isScript(f.getKey())) {
                usesHelper = ScriptAnalyzer.analyze(new String(f.getValue(), StandardCharsets.UTF_8)).usesHelper();
            }
        }
        List<String> warnings = new ArrayList<>(notes);
        warnings.addAll(analysis.warnings());
        if (analysis.operations().isEmpty()) {
            warnings.add("Экспортируемые функции-операции не найдены (список определяется эвристикой): добавьте операции вручную на странице версии.");
        }

        // k6 inspect и блокирующая проверка опций
        K6Tool.Inspect inspect;
        Path dir = Files.createTempDirectory("k6lab-inspect");
        try {
            ZipUtil.extract(new ByteArrayInputStream(zip), dir);
            Path adminDir = dir.resolve("__admin");
            Files.createDirectories(adminDir);
            Files.write(adminDir.resolve("admin.js"), helper.content());
            inspect = k6.inspect(dir, entrypoint);
        } finally {
            deleteTree(dir);
        }
        boolean valid;
        String invalidReason = null;
        switch (inspect.status()) {
            case OK -> {
                List<String> blocked = new ArrayList<>();
                for (String key : ScriptAnalyzer.BLOCKING_OPTIONS) {
                    if (isSet(inspect.options().get(key))) {
                        blocked.add(key);
                    }
                }
                valid = blocked.isEmpty();
                if (!valid) {
                    invalidReason = blockedMessage(blocked);
                }
            }
            case FAILED -> {
                valid = false;
                invalidReason = "k6 inspect завершился с ошибкой, опции скрипта проверить не удалось: " + inspect.error();
            }
            default -> {
                List<String> blocked = ScriptAnalyzer.blockedOptionKeys(entrySource);
                valid = blocked.isEmpty();
                warnings.add(inspect.error() + ". Использована приблизительная статическая проверка опций.");
                if (!valid) {
                    invalidReason = blockedMessage(blocked);
                }
            }
        }

        List<FileEntry> fileList = new ArrayList<>();
        for (Map.Entry<String, byte[]> f : files.entrySet()) {
            fileList.add(new FileEntry(f.getKey(), f.getValue().length, isScript(f.getKey()) ? "SCRIPT" : "DATA"));
        }
        fileList.sort(Comparator.comparing(FileEntry::path));

        var stored = storage.store("scripts", script.getName() + ".zip", new ByteArrayInputStream(zip));
        final boolean fValid = valid;
        final String fReason = invalidReason;
        final boolean fHelper = usesHelper;
        try {
            for (int attempt = 0; ; attempt++) {
                try {
                    return tx.execute(st -> {
                        ScriptVersion v = new ScriptVersion();
                        v.setScriptId(script.getId());
                        v.setNumber(versions.maxNumber(script.getId()) + 1);
                        v.setSource(source);
                        v.setComment(blankToNull(comment));
                        v.setParentVersionId(parentVersionId);
                        v.setGitRepoId(gitRepoId);
                        v.setGitRef(gitRef);
                        v.setGitBranch(gitBranch);
                        v.setCommitHash(commit);
                        v.setStorageKey(stored.key());
                        v.setEntrypoint(entrypoint);
                        v.setSha256(sha);
                        v.setSizeBytes(zip.length);
                        v.setInspectStatus(inspect.status());
                        v.setInspectResult(inspect.options());
                        v.setInspectError(inspect.error());
                        v.setOperations(new ArrayList<>(analysis.operations().stream()
                                .map(o -> new OpEntry(o.name(), o.origin())).toList()));
                        v.setValidForModel(fValid);
                        v.setInvalidReason(fReason);
                        v.setUsesHelper(fHelper);
                        v.setWarnings(warnings);
                        v.setFiles(fileList);
                        var auth = SecurityContextHolder.getContext().getAuthentication();
                        v.setCreatedBy(auth == null ? "system" : auth.getName());
                        ScriptVersion saved = versions.saveAndFlush(v);
                        audit.record("script.version", "script_version", saved.getId(),
                                Map.of("script", script.getName(), "number", saved.getNumber(), "source", source.name()));
                        return saved;
                    });
                } catch (DataIntegrityViolationException e) {
                    if (attempt >= 3) {
                        throw e;
                    }
                }
            }
        } catch (RuntimeException e) {
            storage.delete(stored.key());
            throw e;
        }
    }

    private static String blockedMessage(List<String> blocked) {
        return "В options скрипта объявлены: " + String.join(", ", blocked) + ". Опции скрипта имеют приоритет над --config "
                + "и перекроют модель нагрузки из UI. Удалите их из скрипта: профиль нагрузки задаётся моделью и профилем.";
    }

    private static boolean isSet(Object v) {
        if (v == null) {
            return false;
        }
        if (v instanceof Map<?, ?> m) {
            return !m.isEmpty();
        }
        if (v instanceof java.util.Collection<?> c) {
            return !c.isEmpty();
        }
        return true;
    }

    // ------------------------------------------------------------------ загрузка

    private static byte[] readLimited(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[64 * 1024];
        long total = 0;
        int n;
        while ((n = in.read(buf)) > 0) {
            total += n;
            if (total > MAX_UPLOAD) {
                throw new IllegalArgumentException("Файл слишком большой (максимум " + MAX_UPLOAD / 1024 / 1024 + " МБ)");
            }
            out.write(buf, 0, n);
        }
        if (total == 0) {
            throw new IllegalArgumentException("Файл пуст");
        }
        return out.toByteArray();
    }

    static Map<String, byte[]> normalizeUpload(String filename, byte[] data, List<String> notes) throws IOException {
        String fn = filename == null ? "script.js" : filename.replace('\\', '/');
        fn = fn.substring(fn.lastIndexOf('/') + 1);
        String lower = fn.toLowerCase();
        Map<String, byte[]> files;
        if (lower.endsWith(".js") || lower.endsWith(".mjs")) {
            files = new LinkedHashMap<>();
            files.put(fn, data);
        } else if (lower.endsWith(".zip")) {
            files = ZipUtil.stripSingleTopDir(ZipUtil.readAll(new ByteArrayInputStream(data)));
        } else if (lower.endsWith(".tar.gz") || lower.endsWith(".tgz") || lower.endsWith(".tar")) {
            files = ZipUtil.stripSingleTopDir(readTar(data, !lower.endsWith(".tar")));
        } else {
            throw new IllegalArgumentException("Поддерживаются файлы .js, .mjs и архивы .zip, .tar.gz, .tgz");
        }
        Map<String, byte[]> clean = new LinkedHashMap<>();
        for (Map.Entry<String, byte[]> e : files.entrySet()) {
            String p = ZipUtil.safePath(e.getKey());
            if (p == null) {
                throw new IllegalArgumentException("Недопустимый путь в архиве: " + e.getKey());
            }
            if (p.startsWith("__MACOSX/") || p.endsWith(".DS_Store")) {
                continue;
            }
            clean.put(p, e.getValue());
        }
        if (clean.isEmpty()) {
            throw new IllegalArgumentException("В архиве нет файлов");
        }
        return clean;
    }

    private static Map<String, byte[]> readTar(byte[] data, boolean gz) throws IOException {
        Map<String, byte[]> files = new LinkedHashMap<>();
        long total = 0;
        InputStream raw = new ByteArrayInputStream(data);
        try (TarArchiveInputStream tar = new TarArchiveInputStream(gz ? new GzipCompressorInputStream(raw) : raw)) {
            TarArchiveEntry e;
            while ((e = tar.getNextEntry()) != null) {
                if (!e.isFile()) {
                    continue; // каталоги, симлинки и устройства пропускаем
                }
                byte[] b = tar.readAllBytes();
                total += b.length;
                if (total > ZipUtil.MAX_TOTAL_BYTES || files.size() > ZipUtil.MAX_ENTRIES) {
                    throw new IllegalArgumentException("Архив слишком большой");
                }
                files.put(e.getName(), b);
            }
        }
        return files;
    }

    static String chooseEntrypoint(Map<String, byte[]> files, String requested, String uploadedName) {
        if (requested != null && !requested.isBlank()) {
            String p = ZipUtil.safePath(requested.trim());
            if (p == null || !files.containsKey(p)) {
                throw new IllegalArgumentException("Entrypoint «" + requested + "» не найден в загруженных файлах");
            }
            return p;
        }
        List<String> js = files.keySet().stream().filter(ScriptService::isScript).toList();
        if (js.size() == 1) {
            return js.get(0);
        }
        for (String d : DEFAULT_ENTRYPOINTS) {
            if (files.containsKey(d)) {
                return d;
            }
        }
        List<String> rootJs = js.stream().filter(p -> !p.contains("/")).toList();
        if (rootJs.size() == 1) {
            return rootJs.get(0);
        }
        throw new IllegalArgumentException("Укажите entrypoint: в архиве несколько .js-файлов (" + String.join(", ",
                js.stream().limit(8).toList()) + (js.size() > 8 ? ", …" : "") + ")");
    }

    static boolean isScript(String path) {
        int dot = path.lastIndexOf('.');
        return dot >= 0 && SCRIPT_EXT.contains(path.substring(dot + 1).toLowerCase());
    }

    private static String sha256(byte[] data) {
        return java.util.HexFormat.of().formatHex(Hashes.sha256Digest().digest(data));
    }

    private static void requireName(String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Укажите имя скрипта");
        }
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private static void deleteTree(Path dir) {
        try (var walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        } catch (IOException ignored) {
            // временный каталог, best-effort
        }
    }

    /** Список имён операций версии (для проверки при запуске: все операции модели есть в версии). */
    public Set<String> operationNames(ScriptVersion v) {
        return new LinkedHashSet<>(v.operationNames());
    }
}
