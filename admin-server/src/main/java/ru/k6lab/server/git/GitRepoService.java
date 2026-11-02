package ru.k6lab.server.git;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.k6lab.server.audit.AuditService;
import ru.k6lab.server.common.SecretCipher;
import ru.k6lab.server.ssh.SshCredentialService;

import java.io.IOException;
import java.util.List;
import java.util.Map;

@Service
public class GitRepoService {

    public record Form(Long id, String name, String url, GitRepo.AuthType authType, String token,
                       Long sshCredentialId, String subdir, boolean clearToken) {
    }

    private final GitRepoRepository repo;
    private final SecretCipher cipher;
    private final SshCredentialService credentials;
    private final GitClient git;
    private final AuditService audit;

    public GitRepoService(GitRepoRepository repo, SecretCipher cipher, SshCredentialService credentials, GitClient git,
                          AuditService audit) {
        this.repo = repo;
        this.cipher = cipher;
        this.credentials = credentials;
        this.git = git;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public List<GitRepo> list() {
        return repo.findAllByOrderByNameAsc();
    }

    @Transactional(readOnly = true)
    public GitRepo get(Long id) {
        return repo.findById(id).orElseThrow(() -> new IllegalArgumentException("Git-репозиторий не найден"));
    }

    @Transactional
    public GitRepo save(Form f) {
        GitRepo r;
        if (f.id() == null) {
            if (repo.existsByNameIgnoreCase(f.name())) {
                throw new IllegalArgumentException("Репозиторий с таким именем уже есть");
            }
            r = new GitRepo();
        } else {
            r = get(f.id());
        }
        if (f.url() == null || f.url().isBlank()) {
            throw new IllegalArgumentException("Укажите URL репозитория");
        }
        String url = f.url().trim();
        if (f.authType() == GitRepo.AuthType.SSH && !(url.startsWith("ssh://") || url.matches("^[\\w.-]+@[\\w.-]+:.*"))) {
            throw new IllegalArgumentException("Для SSH нужен URL вида ssh://host/path или git@host:path");
        }
        if (f.authType() != GitRepo.AuthType.SSH && (url.startsWith("ssh://") || url.matches("^[\\w.-]+@[\\w.-]+:.*"))) {
            throw new IllegalArgumentException("URL по SSH - выберите тип аутентификации «SSH-ключ»");
        }
        r.setName(f.name().trim());
        r.setUrl(url);
        r.setAuthType(f.authType());
        r.setSubdir(GitClient.normalizeSubdir(f.subdir()));
        if (f.authType() == GitRepo.AuthType.HTTPS_TOKEN) {
            if (f.token() != null && !f.token().isBlank()) {
                r.setTokenEnc(cipher.encrypt(f.token().trim()));
            } else if (r.getTokenEnc() == null || f.clearToken()) {
                throw new IllegalArgumentException("Введите токен доступа");
            }
        } else {
            r.setTokenEnc(null);
        }
        if (f.authType() == GitRepo.AuthType.SSH) {
            if (f.sshCredentialId() == null) {
                throw new IllegalArgumentException("Выберите SSH-учётку с ключом");
            }
            r.setSshCredentialId(f.sshCredentialId());
        } else {
            r.setSshCredentialId(null);
        }
        GitRepo saved = repo.save(r);
        audit.record(f.id() == null ? "git_repo.create" : "git_repo.update", "git_repo", saved.getId(), Map.of("name", saved.getName()));
        return saved;
    }

    @Transactional
    public void delete(Long id) {
        GitRepo r = get(id);
        repo.delete(r);
        audit.record("git_repo.delete", "git_repo", id, Map.of("name", r.getName()));
    }

    /** Расшифрованные данные доступа - только на время операции. */
    @Transactional(readOnly = true)
    public GitClient.Auth authFor(GitRepo r) {
        return switch (r.getAuthType()) {
            case NONE -> GitClient.Auth.none();
            case HTTPS_TOKEN -> new GitClient.Auth(cipher.decrypt(r.getTokenEnc()), null);
            case SSH -> new GitClient.Auth(null, credentials.resolve(r.getSshCredentialId()));
        };
    }

    /** Загружает/обновляет кеш репозитория и возвращает ветки и теги. */
    public List<GitClient.RefInfo> refresh(Long id) throws IOException {
        GitRepo r = get(id);
        git.sync(r.getId(), r.getUrl(), authFor(r));
        return git.refs(r.getId());
    }

    public List<GitClient.RefInfo> cachedRefs(Long id) {
        try {
            return git.refs(id);
        } catch (IOException e) {
            return List.of();
        }
    }
}
