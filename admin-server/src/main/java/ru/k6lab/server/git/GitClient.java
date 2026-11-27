package ru.k6lab.server.git;

import org.apache.sshd.common.NamedResource;
import org.apache.sshd.common.config.keys.KeyUtils;
import org.apache.sshd.common.config.keys.PublicKeyEntry;
import org.apache.sshd.common.util.security.SecurityUtils;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.TransportCommand;
import org.eclipse.jgit.errors.UnsupportedCredentialItem;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectLoader;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.transport.CredentialsProvider;
import org.eclipse.jgit.transport.SshTransport;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;
import org.eclipse.jgit.transport.sshd.ServerKeyDatabase;
import org.eclipse.jgit.transport.sshd.SshdSessionFactory;
import org.eclipse.jgit.transport.sshd.SshdSessionFactoryBuilder;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.eclipse.jgit.treewalk.filter.PathFilter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import ru.k6lab.core.util.ZipUtil;
import ru.k6lab.server.config.AppProperties;
import ru.k6lab.server.ssh.KnownHostService;
import ru.k6lab.server.ssh.ResolvedCredential;
import ru.k6lab.server.ssh.SshExceptions;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.PublicKey;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Работа с Git через JGit. Для каждого репозитория держим bare-кеш (storage/git-cache/<id>): fetch вместо
 * повторного clone; файлы версии читаются из дерева коммита без checkout.
 */
@Component
public class GitClient {

    private static final Logger log = LoggerFactory.getLogger(GitClient.class);
    private static final long MAX_BLOB = 200L * 1024 * 1024;

    public record RefInfo(String name, String kind, String commit) {
    }

    public record CommitInfo(String hash, String author, Instant time, String message) {
    }

    public record PushResult(String commit, boolean changed, String branch) {
    }

    /** Параметры доступа: токен либо SSH-учётка (уже расшифрованные, живут только на время операции). */
    public record Auth(String token, ResolvedCredential ssh) {
        public static Auth none() {
            return new Auth(null, null);
        }
    }

    private final AppProperties props;
    private final KnownHostService knownHosts;

    public GitClient(AppProperties props, KnownHostService knownHosts) {
        this.props = props;
        this.knownHosts = knownHosts;
    }

    private Path cacheDir(Long repoId) {
        return props.getStorageDir().resolve("git-cache").resolve(String.valueOf(repoId)).toAbsolutePath();
    }

    private static final int TRANSPORT_TIMEOUT_SEC = 60;

    private java.util.concurrent.locks.ReentrantReadWriteLock rwLock(Long repoId) {
        return rwLocks.computeIfAbsent(repoId, k -> new java.util.concurrent.locks.ReentrantReadWriteLock());
    }

    private final java.util.concurrent.ConcurrentHashMap<Long, java.util.concurrent.locks.ReentrantReadWriteLock> rwLocks = new java.util.concurrent.ConcurrentHashMap<>();

    /** Обновляет кеш (clone при первом обращении, иначе fetch). */
    public void sync(Long repoId, String url, Auth auth) throws IOException {
        var lock = rwLock(repoId).writeLock();
        lock.lock();
        SshHandle sshHandle = null;
        try {
            Path dir = cacheDir(repoId);
            boolean exists = Files.isDirectory(dir.resolve("objects"));
            String effectiveUrl = applyDefaultSshUser(url, auth);
            String logicalHost = extractLogicalHost(effectiveUrl);
            try {
                if (!exists) {
                    Files.createDirectories(dir);
                    Git.init().setBare(true).setDirectory(dir.toFile()).call().close();
                }
                try (Git git = Git.open(dir.toFile())) {
                    var fetch = git.fetch().setRemote(effectiveUrl)
                            .setRefSpecs("+refs/heads/*:refs/heads/*", "+refs/tags/*:refs/tags/*")
                            .setRemoveDeletedRefs(true)
                            .setTimeout(TRANSPORT_TIMEOUT_SEC);
                    sshHandle = configure(fetch, auth, logicalHost);
                    fetch.call();
                }
            } catch (org.eclipse.jgit.api.errors.GitAPIException e) {
                if (!exists) {
                    deleteQuietly(dir);
                }
                if (sshHandle != null && sshHandle.problem().get() != null) {
                    throw sshHandle.problem().get();
                }
                throw unwrap(e);
            } catch (RuntimeException e) {
                if (!exists) {
                    deleteQuietly(dir);
                }
                if (sshHandle != null && sshHandle.problem().get() != null) {
                    throw sshHandle.problem().get();
                }
                throw e;
            }
        } finally {
            if (sshHandle != null) {
                sshHandle.close();
            }
            lock.unlock();
        }
    }

    /** Удаляет локальный bare-кеш репозитория (вызывается после commit удаления GitRepo). */
    public void evictCache(Long repoId) {
        var lock = rwLock(repoId).writeLock();
        lock.lock();
        try {
            Path dir = cacheDir(repoId);
            if (Files.exists(dir)) {
                deleteQuietly(dir);
            }
        } finally {
            lock.unlock();
        }
    }

    public List<RefInfo> refs(Long repoId) throws IOException {
        var lock = rwLock(repoId).readLock();
        lock.lock();
        try (Repository r = open(repoId)) {
            List<RefInfo> out = new ArrayList<>();
            for (Ref ref : r.getRefDatabase().getRefs()) {
                String n = ref.getName();
                ObjectId id = r.getRefDatabase().peel(ref).getPeeledObjectId() != null
                        ? r.getRefDatabase().peel(ref).getPeeledObjectId() : ref.getObjectId();
                if (n.startsWith("refs/heads/")) {
                    out.add(new RefInfo(n.substring(11), "branch", id.getName()));
                } else if (n.startsWith("refs/tags/")) {
                    out.add(new RefInfo(n.substring(10), "tag", id.getName()));
                }
            }
            out.sort(Comparator.comparing(RefInfo::kind).thenComparing(RefInfo::name));
            return out;
        } finally {
            lock.unlock();
        }
    }

    /** Разрешает ветку, тег или (сокращённый) hash коммита. */
    public String resolve(Long repoId, String ref) throws IOException {
        var lock = rwLock(repoId).readLock();
        lock.lock();
        try (Repository r = open(repoId); RevWalk walk = new RevWalk(r)) {
            ObjectId id = null;
            for (String candidate : new String[]{"refs/heads/" + ref, "refs/tags/" + ref, ref}) {
                id = r.resolve(candidate + "^{commit}");
                if (id != null) {
                    break;
                }
            }
            if (id == null) {
                throw new IOException("Ветка, тег или коммит «" + ref + "» не найдены");
            }
            return walk.parseCommit(id).getName();
        } finally {
            lock.unlock();
        }
    }

    /** Файлы коммита из подкаталога (пути относительно него) - как zip. */
    public byte[] exportZip(Long repoId, String commit, String subdir) throws IOException {
        var lock = rwLock(repoId).readLock();
        lock.lock();
        try (Repository r = open(repoId); RevWalk walk = new RevWalk(r)) {
            RevCommit c = walk.parseCommit(ObjectId.fromString(commit));
            String sub = normalizeSubdir(subdir);
            Map<String, byte[]> files = new LinkedHashMap<>();
            try (TreeWalk tw = new TreeWalk(r)) {
                tw.addTree(c.getTree());
                tw.setRecursive(true);
                if (!sub.isEmpty()) {
                    tw.setFilter(PathFilter.create(sub));
                }
                while (tw.next()) {
                    if (tw.getFileMode().getObjectType() != org.eclipse.jgit.lib.Constants.OBJ_BLOB
                            || tw.getFileMode(0).equals(org.eclipse.jgit.lib.FileMode.SYMLINK)) {
                        continue;
                    }
                    String path = tw.getPathString();
                    if (!sub.isEmpty() && !path.startsWith(sub + "/")) {
                        continue;
                    }
                    String rel = sub.isEmpty() ? path : path.substring(sub.length() + 1);
                    if (rel.startsWith(".git/")) {
                        continue;
                    }
                    ObjectLoader l = r.open(tw.getObjectId(0));
                    if (l.getSize() > MAX_BLOB) {
                        throw new IOException("Файл " + path + " слишком большой (> 200 МБ)");
                    }
                    files.put(rel, l.getBytes());
                }
            }
            if (files.isEmpty()) {
                throw new IOException("В коммите нет файлов" + (sub.isEmpty() ? "" : " в каталоге " + sub));
            }
            return ZipUtil.write(files);
        } finally {
            lock.unlock();
        }
    }

    /** Коммиты (новые первыми) от exclusive (не включая) до ref; не больше limit. */
    public List<CommitInfo> log(Long repoId, String fromExclusive, String toCommit, int limit) throws IOException {
        var lock = rwLock(repoId).readLock();
        lock.lock();
        try (Repository r = open(repoId); RevWalk walk = new RevWalk(r)) {
            walk.markStart(walk.parseCommit(ObjectId.fromString(toCommit)));
            if (fromExclusive != null) {
                try {
                    walk.markUninteresting(walk.parseCommit(ObjectId.fromString(fromExclusive)));
                } catch (IOException e) {
                    // старого коммита в кеше нет (история переписана) - показываем последние
                    log.debug("Коммит {} не найден в кеше", fromExclusive);
                }
            }
            List<CommitInfo> out = new ArrayList<>();
            for (RevCommit c : walk) {
                out.add(new CommitInfo(c.getName(), c.getAuthorIdent().getName(),
                        Instant.ofEpochSecond(c.getCommitTime()), c.getShortMessage()));
                if (out.size() >= limit) {
                    break;
                }
            }
            return out;
        } finally {
            lock.unlock();
        }
    }

    private Repository open(Long repoId) throws IOException {
        Path dir = cacheDir(repoId);
        if (!Files.isDirectory(dir.resolve("objects"))) {
            throw new IOException("Репозиторий ещё не загружен: выполните «Проверить доступ / обновить»");
        }
        return new org.eclipse.jgit.storage.file.FileRepositoryBuilder().setGitDir(dir.toFile()).setBare().build();
    }

    /**
     * Читает все файлы ветки под указанным префиксом (пути в карте возвращаются относительно корня репозитория).
     * Если ветка ещё не существует в репозитории, возвращает пустую карту.
     */
    public Map<String, byte[]> readBranchFiles(Long repoId, String branch, String prefix) throws IOException {
        var lock = rwLock(repoId).readLock();
        lock.lock();
        try (Repository r = open(repoId); RevWalk walk = new RevWalk(r)) {
            String cleanBranch = normalizeBranchName(branch);
            ObjectId commitOid = r.resolve("refs/heads/" + cleanBranch + "^{commit}");
            if (commitOid == null) {
                return Map.of();
            }
            RevCommit c = walk.parseCommit(commitOid);
            String sub = normalizeSubdir(prefix);
            Map<String, byte[]> files = new LinkedHashMap<>();
            try (TreeWalk tw = new TreeWalk(r)) {
                tw.addTree(c.getTree());
                tw.setRecursive(true);
                if (!sub.isEmpty()) {
                    tw.setFilter(PathFilter.create(sub));
                }
                while (tw.next()) {
                    if (tw.getFileMode().getObjectType() != org.eclipse.jgit.lib.Constants.OBJ_BLOB
                            || tw.getFileMode(0).equals(org.eclipse.jgit.lib.FileMode.SYMLINK)) {
                        continue;
                    }
                    String path = tw.getPathString();
                    if (!sub.isEmpty() && !path.equals(sub) && !path.startsWith(sub + "/")) {
                        continue;
                    }
                    ObjectLoader l = r.open(tw.getObjectId(0));
                    if (l.getSize() > MAX_BLOB) {
                        continue;
                    }
                    files.put(path, l.getBytes());
                }
            }
            return files;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Формирует коммит в bare-кеше поверх удалённой ветки и отправляет (push) в удалённый Git-репозиторий.
     *
     * @param replacePrefix  если не null: все файлы родительского коммита внутри этого каталога ("" = весь репозиторий,
     *                       "sub/path" = только внутри "sub/path/...") заменяются переданной картой {@code files};
     *                       файлы вне {@code replacePrefix} сохраняются нетронутыми.
     * @param files          полные пути файлов относительно корня репозитория -> содержимое
     * @param deletePrefixes дополнительные префиксы путей для удаления из дерева
     */
    public PushResult commitAndPush(Long repoId, String url, Auth auth, String branch,
                                    String replacePrefix, Map<String, byte[]> files,
                                    java.util.Set<String> deletePrefixes,
                                    String authorName, String message) throws IOException {
        String cleanBranch = normalizeBranchName(branch);
        String normReplace = replacePrefix == null ? null : normalizeSubdir(replacePrefix);
        List<String> delList = new ArrayList<>();
        if (deletePrefixes != null) {
            for (String dp : deletePrefixes) {
                String n = normalizeSubdir(dp);
                if (!n.isEmpty()) {
                    delList.add(n);
                }
            }
        }

        var lock = rwLock(repoId).writeLock();
        lock.lock();
        SshHandle sshFetch = null;
        SshHandle sshPush = null;
        try {
            Path dir = cacheDir(repoId);
            String effectiveUrl = applyDefaultSshUser(url, auth);
            String logicalHost = extractLogicalHost(effectiveUrl);

            if (!Files.isDirectory(dir.resolve("objects"))) {
                Files.createDirectories(dir);
                try {
                    Git.init().setBare(true).setDirectory(dir.toFile()).call().close();
                } catch (org.eclipse.jgit.api.errors.GitAPIException e) {
                    deleteQuietly(dir);
                    throw unwrap(e);
                }
            }

            try (Git git = Git.open(dir.toFile())) {
                // 1. Синхронизируем актуальные ветки с удалённого сервера (пустой удалённый репозиторий допустим)
                try {
                    var fetch = git.fetch().setRemote(effectiveUrl)
                            .setRefSpecs("+refs/heads/*:refs/heads/*", "+refs/tags/*:refs/tags/*")
                            .setRemoveDeletedRefs(true)
                            .setTimeout(TRANSPORT_TIMEOUT_SEC);
                    sshFetch = configure(fetch, auth, logicalHost);
                    fetch.call();
                } catch (org.eclipse.jgit.api.errors.TransportException te) {
                    if (sshFetch != null && sshFetch.problem().get() != null) {
                        throw sshFetch.problem().get();
                    }
                    String msg = te.getMessage() == null ? "" : te.getMessage();
                    // Пустой репозиторий без единой ветки у некоторых серверов отдаёт remote does not have refs
                    if (!msg.contains("no refs") && !msg.contains("Remote does not have")) {
                        throw unwrap(te);
                    }
                } catch (org.eclipse.jgit.api.errors.GitAPIException e) {
                    if (sshFetch != null && sshFetch.problem().get() != null) {
                        throw sshFetch.problem().get();
                    }
                    throw unwrap(e);
                }

                Repository r = git.getRepository();
                RevCommit parent = null;
                ObjectId parentOid = r.resolve("refs/heads/" + cleanBranch + "^{commit}");
                if (parentOid != null) {
                    try (RevWalk rw = new RevWalk(r)) {
                        parent = rw.parseCommit(parentOid);
                    }
                }

                // 2. Собираем дерево коммита в памяти через DirCache
                var dc = org.eclipse.jgit.dircache.DirCache.newInCore();
                var builder = dc.builder();
                java.util.Set<String> newPaths = new java.util.HashSet<>();
                for (String p : files.keySet()) {
                    String cleanPath = normalizeSubdir(p);
                    if (!cleanPath.isEmpty()) {
                        newPaths.add(cleanPath);
                    }
                }

                try (var inserter = r.newObjectInserter()) {
                    if (parent != null) {
                        try (TreeWalk tw = new TreeWalk(r)) {
                            tw.addTree(parent.getTree());
                            tw.setRecursive(true);
                            while (tw.next()) {
                                String path = tw.getPathString();
                                if (newPaths.contains(path)) {
                                    continue;
                                }
                                if (normReplace != null) {
                                    if (normReplace.isEmpty() || path.equals(normReplace) || path.startsWith(normReplace + "/")) {
                                        continue;
                                    }
                                }
                                boolean deleted = false;
                                for (String dp : delList) {
                                    if (path.equals(dp) || path.startsWith(dp + "/")) {
                                        deleted = true;
                                        break;
                                    }
                                }
                                if (deleted) {
                                    continue;
                                }
                                var entry = new org.eclipse.jgit.dircache.DirCacheEntry(path);
                                entry.setFileMode(tw.getFileMode(0));
                                entry.setObjectId(tw.getObjectId(0));
                                builder.add(entry);
                            }
                        }
                    }

                    for (var e : files.entrySet()) {
                        String cleanPath = normalizeSubdir(e.getKey());
                        if (cleanPath.isEmpty()) {
                            continue;
                        }
                        byte[] bytes = e.getValue() != null ? e.getValue() : new byte[0];
                        ObjectId blobId = inserter.insert(org.eclipse.jgit.lib.Constants.OBJ_BLOB, bytes);
                        var entry = new org.eclipse.jgit.dircache.DirCacheEntry(cleanPath);
                        entry.setFileMode(org.eclipse.jgit.lib.FileMode.REGULAR_FILE);
                        entry.setLength(bytes.length);
                        entry.setObjectId(blobId);
                        builder.add(entry);
                    }
                    builder.finish();

                    ObjectId treeId = dc.writeTree(inserter);
                    if (parent != null && parent.getTree().getId().equals(treeId)) {
                        return new PushResult(parent.getName(), false, cleanBranch);
                    }

                    String actor = (authorName == null || authorName.isBlank()) ? "k6-lab" : authorName.strip();
                    var ident = new org.eclipse.jgit.lib.PersonIdent(actor, actor.replaceAll("[^A-Za-z0-9._-]", "_") + "@k6-lab.local");
                    var cb = new org.eclipse.jgit.lib.CommitBuilder();
                    cb.setTreeId(treeId);
                    if (parent != null) {
                        cb.setParentId(parent.getId());
                    }
                    cb.setAuthor(ident);
                    cb.setCommitter(ident);
                    cb.setMessage(message == null || message.isBlank() ? "chore(backup): sync k6-lab scripts" : message);

                    ObjectId commitId = inserter.insert(cb);
                    inserter.flush();

                    var refUpdate = r.updateRef("refs/heads/" + cleanBranch);
                    refUpdate.setNewObjectId(commitId);
                    refUpdate.setForceUpdate(true);
                    refUpdate.update();

                    // 3. Отправляем коммит в удалённый репозиторий
                    var push = git.push()
                            .setRemote(effectiveUrl)
                            .setRefSpecs(new org.eclipse.jgit.transport.RefSpec(commitId.name() + ":refs/heads/" + cleanBranch))
                            .setTimeout(TRANSPORT_TIMEOUT_SEC);
                    sshPush = configure(push, auth, logicalHost);
                    var pushResults = push.call();
                    for (var pr : pushResults) {
                        for (var rru : pr.getRemoteUpdates()) {
                            var st = rru.getStatus();
                            if (st != org.eclipse.jgit.transport.RemoteRefUpdate.Status.OK
                                    && st != org.eclipse.jgit.transport.RemoteRefUpdate.Status.UP_TO_DATE) {
                                String reason = rru.getMessage() != null ? rru.getMessage() : st.name();
                                throw new IOException("Удалённый Git отклонил запись в ветку «" + cleanBranch + "»: " + reason);
                            }
                        }
                    }
                    return new PushResult(commitId.name(), true, cleanBranch);
                } catch (org.eclipse.jgit.api.errors.GitAPIException e) {
                    if (sshPush != null && sshPush.problem().get() != null) {
                        throw sshPush.problem().get();
                    }
                    throw unwrap(e);
                }
            } catch (RuntimeException e) {
                if (sshFetch != null && sshFetch.problem().get() != null) {
                    throw sshFetch.problem().get();
                }
                if (sshPush != null && sshPush.problem().get() != null) {
                    throw sshPush.problem().get();
                }
                throw e;
            }
        } finally {
            if (sshFetch != null) {
                sshFetch.close();
            }
            if (sshPush != null) {
                sshPush.close();
            }
            lock.unlock();
        }
    }

    public static String normalizeBranchName(String branch) {
        String b = branch == null ? "" : branch.strip();
        if (b.startsWith("refs/heads/")) {
            b = b.substring("refs/heads/".length());
        }
        if (b.isEmpty()) {
            return "main";
        }
        if (b.contains("..") || b.contains(" ") || b.contains("\\") || b.startsWith("/") || b.endsWith("/")) {
            throw new IllegalArgumentException("Недопустимое имя ветки Git: «" + branch + "»");
        }
        return b;
    }

    public static String normalizeSubdir(String subdir) {
        if (subdir == null) {
            return "";
        }
        String s = subdir.trim().replace('\\', '/');
        while (s.startsWith("/")) {
            s = s.substring(1);
        }
        while (s.endsWith("/")) {
            s = s.substring(0, s.length() - 1);
        }
        if (s.contains("..")) {
            throw new IllegalArgumentException("Недопустимый подкаталог");
        }
        return s;
    }

    // ------------------------------------------------------------------ авторизация

    private record SshHandle(SshdSessionFactory factory, Path home,
                             java.util.concurrent.atomic.AtomicReference<GitHostKeyException> problem) implements AutoCloseable {
        @Override
        public void close() {
            try {
                factory.close();
            } catch (RuntimeException ignored) {
                // best-effort
            }
            deleteQuietly(home);
        }
    }

    private static String applyDefaultSshUser(String url, Auth auth) {
        if (url == null || auth == null || auth.ssh() == null) {
            return url;
        }
        String defUser = auth.ssh().username();
        if (defUser == null || defUser.isBlank() || !url.startsWith("ssh://")) {
            return url;
        }
        try {
            org.eclipse.jgit.transport.URIish u = new org.eclipse.jgit.transport.URIish(url);
            if (u.getUser() == null || u.getUser().isBlank()) {
                return u.setUser(defUser.trim()).toString();
            }
        } catch (Exception ignored) {
            // оставляем исходный URL
        }
        return url;
    }

    private static String extractLogicalHost(String url) {
        if (url == null || url.isBlank()) {
            return null;
        }
        try {
            org.eclipse.jgit.transport.URIish u = new org.eclipse.jgit.transport.URIish(url);
            if (u.getHost() != null && !u.getHost().isBlank()) {
                return u.getHost().trim();
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    static String resolveHostName(String logicalHost, String connectAddress, InetSocketAddress remote) {
        if (logicalHost != null && !logicalHost.isBlank()) {
            return logicalHost;
        }
        if (connectAddress != null && !connectAddress.isBlank()) {
            String ca = connectAddress.trim();
            if (ca.startsWith("[") && ca.contains("]")) {
                return ca.substring(1, ca.indexOf(']'));
            }
            int colon = ca.indexOf(':');
            return colon > 0 ? ca.substring(0, colon) : ca;
        }
        return remote.getHostString();
    }

    private SshHandle configure(TransportCommand<?, ?> cmd, Auth auth, String logicalHost) throws IOException {
        if (auth.token() != null && !auth.token().isBlank()) {
            String t = auth.token();
            String user = "token";
            String pass = t;
            int colon = t.indexOf(':');
            if (colon > 0) {
                user = t.substring(0, colon);
                pass = t.substring(colon + 1);
            }
            cmd.setCredentialsProvider(new UsernamePasswordCredentialsProvider(user, pass));
        }
        if (auth.ssh() != null) {
            SshHandle handle = sshFactory(auth.ssh(), logicalHost);
            cmd.setTransportConfigCallback(t -> {
                if (t instanceof SshTransport st) {
                    st.setSshSessionFactory(handle.factory());
                }
            });
            return handle;
        }
        return null;
    }

    private SshHandle sshFactory(ResolvedCredential cred, String logicalHost) throws IOException {
        Path home = Files.createTempDirectory("k6lab-git-ssh");
        var problem = new java.util.concurrent.atomic.AtomicReference<GitHostKeyException>();
        try {
            List<KeyPair> keys = new ArrayList<>();
            if (cred.authType() == ru.k6lab.server.ssh.SshCredential.AuthType.KEY) {
                try {
                    for (KeyPair kp : SecurityUtils.loadKeyPairIdentities(null, NamedResource.ofName(cred.name()),
                            new ByteArrayInputStream(cred.secret().getBytes(StandardCharsets.UTF_8)),
                            (s, r, i) -> cred.passphrase())) {
                        keys.add(kp);
                    }
                } catch (Exception e) {
                    throw new IOException("Не удалось прочитать SSH-ключ учётки «" + cred.name() + "»: " + e.getMessage(), e);
                }
            } else {
                throw new IOException("Для Git по SSH нужна учётка с приватным ключом (тип «ключ»)");
            }
            SshdSessionFactory factory = new SshdSessionFactoryBuilder()
                    .setHomeDirectory(home.toFile())
                    .setSshDirectory(home.toFile())
                    .setDefaultKeysProvider(f -> keys)
                    .setServerKeyDatabase((h, s) -> new DbServerKeyDatabase(problem, logicalHost))
                    .setConfigStoreFactory((h, c, u) -> null)
                    .build(null);
            return new SshHandle(factory, home, problem);
        } catch (IOException | RuntimeException e) {
            deleteQuietly(home);
            throw e;
        }
    }

    /** Проверка ключа SSH-хоста Git по таблице known_host (та же, что для генераторов). */
    private final class DbServerKeyDatabase implements ServerKeyDatabase {
        private final java.util.concurrent.atomic.AtomicReference<GitHostKeyException> problem;
        private final String logicalHost;

        private DbServerKeyDatabase(java.util.concurrent.atomic.AtomicReference<GitHostKeyException> problem, String logicalHost) {
            this.problem = problem;
            this.logicalHost = logicalHost;
        }

        @Override
        public List<PublicKey> lookup(String connectAddress, InetSocketAddress remote, Configuration config) {
            return List.of();
        }

        @Override
        public boolean accept(String connectAddress, InetSocketAddress remote, PublicKey serverKey, Configuration config,
                              CredentialsProvider provider) {
            String host = resolveHostName(logicalHost, connectAddress, remote);
            int port = remote.getPort();
            String fp = KeyUtils.getFingerPrint(serverKey);
            String keyType = KeyUtils.getKeyType(serverKey);
            String pubKey = PublicKeyEntry.toString(serverKey);
            var known = knownHosts.find(host, port);
            if (known.isPresent()) {
                if (known.get().getFingerprintSha256().equals(fp)) {
                    return true;
                }
                problem.set(new GitHostKeyException(new SshExceptions.HostKeyMismatch(host, port, keyType,
                        known.get().getFingerprintSha256(), fp, pubKey)));
                return false;
            }
            problem.set(new GitHostKeyException(new SshExceptions.HostKeyUnconfirmed(host, port, keyType, fp, pubKey)));
            return false;
        }
    }

    /** Ключ SSH-хоста Git не подтверждён/не совпал; внутри - исходное исключение SSH-слоя. */
    public static class GitHostKeyException extends RuntimeException {
        private final SshExceptions.SshFailure failure;

        public GitHostKeyException(SshExceptions.SshFailure failure) {
            super(failure.getMessage(), failure);
            this.failure = failure;
        }

        public SshExceptions.SshFailure failure() {
            return failure;
        }
    }

    private static IOException unwrap(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof GitHostKeyException g) {
                throw g;
            }
            if (t instanceof UnsupportedCredentialItem) {
                return new IOException("Репозиторий требует аутентификацию: проверьте токен или SSH-учётку", e);
            }
        }
        String msg = e.getMessage() == null ? e.toString() : e.getMessage();
        if (msg.contains("not authorized") || msg.contains("Authentication is required") || msg.contains("401")) {
            msg = "Нет доступа к репозиторию (проверьте токен/ключ): " + msg;
        }
        return new IOException(msg, e);
    }

    private static void deleteQuietly(Path dir) {
        try (var walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).map(Path::toFile).forEach(File::delete);
        } catch (IOException ignored) {
            // best-effort
        }
    }
}
