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

    /** Параметры доступа: токен либо SSH-учётка (уже расшифрованные, живут только на время операции). */
    public record Auth(String token, ResolvedCredential ssh) {
        public static Auth none() {
            return new Auth(null, null);
        }
    }

    private final AppProperties props;
    private final KnownHostService knownHosts;
    private final Map<Long, ReentrantLock> locks = new ConcurrentHashMap<>();

    public GitClient(AppProperties props, KnownHostService knownHosts) {
        this.props = props;
        this.knownHosts = knownHosts;
    }

    private Path cacheDir(Long repoId) {
        return props.getStorageDir().resolve("git-cache").resolve(String.valueOf(repoId)).toAbsolutePath();
    }

    /** Обновляет кеш (clone при первом обращении, иначе fetch). */
    public void sync(Long repoId, String url, Auth auth) throws IOException {
        ReentrantLock lock = locks.computeIfAbsent(repoId, k -> new ReentrantLock());
        lock.lock();
        try {
            Path dir = cacheDir(repoId);
            boolean exists = Files.isDirectory(dir.resolve("objects"));
            try {
                if (!exists) {
                    Files.createDirectories(dir);
                    var clone = Git.cloneRepository().setURI(url).setBare(true).setDirectory(dir.toFile());
                    configure(clone, auth);
                    clone.call().close();
                } else {
                    try (Git git = Git.open(dir.toFile())) {
                        var fetch = git.fetch().setRemote(url)
                                .setRefSpecs("+refs/heads/*:refs/heads/*", "+refs/tags/*:refs/tags/*")
                                .setRemoveDeletedRefs(true);
                        configure(fetch, auth);
                        fetch.call();
                    }
                }
            } catch (org.eclipse.jgit.api.errors.GitAPIException e) {
                if (!exists) {
                    deleteQuietly(dir);
                }
                throw unwrap(e);
            }
        } finally {
            lock.unlock();
        }
    }

    public List<RefInfo> refs(Long repoId) throws IOException {
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
        }
    }

    /** Разрешает ветку, тег или (сокращённый) hash коммита. */
    public String resolve(Long repoId, String ref) throws IOException {
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
        }
    }

    /** Файлы коммита из подкаталога (пути относительно него) - как zip. */
    public byte[] exportZip(Long repoId, String commit, String subdir) throws IOException {
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
        }
    }

    /** Коммиты (новые первыми) от exclusive (не включая) до ref; не больше limit. */
    public List<CommitInfo> log(Long repoId, String fromExclusive, String toCommit, int limit) throws IOException {
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
        }
    }

    private Repository open(Long repoId) throws IOException {
        Path dir = cacheDir(repoId);
        if (!Files.isDirectory(dir.resolve("objects"))) {
            throw new IOException("Репозиторий ещё не загружен: выполните «Проверить доступ / обновить»");
        }
        return new org.eclipse.jgit.storage.file.FileRepositoryBuilder().setGitDir(dir.toFile()).setBare().build();
    }

    static String normalizeSubdir(String subdir) {
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

    private void configure(TransportCommand<?, ?> cmd, Auth auth) throws IOException {
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
            SshdSessionFactory factory = sshFactory(auth.ssh());
            cmd.setTransportConfigCallback(t -> {
                if (t instanceof SshTransport st) {
                    st.setSshSessionFactory(factory);
                }
            });
        }
    }

    private SshdSessionFactory sshFactory(ResolvedCredential cred) throws IOException {
        Path home = Files.createTempDirectory("k6lab-git-ssh");
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
        return new SshdSessionFactoryBuilder()
                .setHomeDirectory(home.toFile())
                .setSshDirectory(home.toFile())
                .setDefaultKeysProvider(f -> keys)
                .setServerKeyDatabase((h, s) -> new DbServerKeyDatabase())
                .setConfigStoreFactory((h, c, u) -> null)
                .build(null);
    }

    /** Проверка ключа SSH-хоста Git по таблице known_host (та же, что для генераторов). */
    private final class DbServerKeyDatabase implements ServerKeyDatabase {
        @Override
        public List<PublicKey> lookup(String connectAddress, InetSocketAddress remote, Configuration config) {
            return List.of();
        }

        @Override
        public boolean accept(String connectAddress, InetSocketAddress remote, PublicKey serverKey, Configuration config,
                              CredentialsProvider provider) {
            String host = remote.getHostString();
            int port = remote.getPort();
            String fp = KeyUtils.getFingerPrint(serverKey);
            var known = knownHosts.find(host, port);
            if (known.isPresent()) {
                if (known.get().getFingerprintSha256().equals(fp)) {
                    return true;
                }
                throw new GitHostKeyException(new SshExceptions.HostKeyMismatch(host, port,
                        known.get().getFingerprintSha256(), fp));
            }
            throw new GitHostKeyException(new SshExceptions.HostKeyUnconfirmed(host, port, KeyUtils.getKeyType(serverKey), fp,
                    PublicKeyEntry.toString(serverKey)));
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
