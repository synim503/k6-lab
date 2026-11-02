package ru.k6lab.server.ssh;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.k6lab.server.audit.AuditService;
import ru.k6lab.server.common.SecretCipher;
import ru.k6lab.server.generator.GeneratorRepository;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class SshCredentialService {

    /** Данные формы. Пустые секреты при редактировании означают «не менять». */
    public record Form(Long id, String name, String username, SshCredential.AuthType authType, String secret,
                       String passphrase, String sudoPassword, String jumpHost, Integer jumpPort,
                       Long jumpCredentialId, boolean clearPassphrase, boolean clearSudoPassword) {
    }

    private final SshCredentialRepository repo;
    private final GeneratorRepository generators;
    private final SecretCipher cipher;
    private final AuditService audit;

    public SshCredentialService(SshCredentialRepository repo, GeneratorRepository generators, SecretCipher cipher,
                                AuditService audit) {
        this.repo = repo;
        this.generators = generators;
        this.cipher = cipher;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public List<SshCredential> list() {
        return repo.findAllByOrderByNameAsc();
    }

    @Transactional(readOnly = true)
    public SshCredential get(Long id) {
        return repo.findById(id).orElseThrow(() -> new IllegalArgumentException("SSH-учётка не найдена"));
    }

    @Transactional
    public SshCredential save(Form f) {
        SshCredential c;
        if (f.id() == null) {
            if (repo.existsByNameIgnoreCase(f.name())) {
                throw new IllegalArgumentException("Учётка с таким именем уже есть");
            }
            c = new SshCredential();
            if (blank(f.secret())) {
                throw new IllegalArgumentException(f.authType() == SshCredential.AuthType.KEY
                        ? "Вставьте приватный ключ" : "Введите пароль");
            }
        } else {
            c = get(f.id());
            if (!c.getName().equalsIgnoreCase(f.name()) && repo.existsByNameIgnoreCase(f.name())) {
                throw new IllegalArgumentException("Учётка с таким именем уже есть");
            }
        }
        c.setName(f.name().trim());
        c.setUsername(f.username().trim());
        c.setAuthType(f.authType());
        if (!blank(f.secret())) {
            c.setSecretEnc(cipher.encrypt(f.authType() == SshCredential.AuthType.KEY ? normalizeKey(f.secret()) : f.secret()));
        }
        if (f.authType() == SshCredential.AuthType.PASSWORD) {
            c.setPassphraseEnc(null);
        } else if (f.clearPassphrase()) {
            c.setPassphraseEnc(null);
        } else if (!blank(f.passphrase())) {
            c.setPassphraseEnc(cipher.encrypt(f.passphrase()));
        }
        if (f.clearSudoPassword()) {
            c.setSudoPasswordEnc(null);
        } else if (!blank(f.sudoPassword())) {
            c.setSudoPasswordEnc(cipher.encrypt(f.sudoPassword()));
        }
        validateJump(c, f);
        c.setJumpHost(blank(f.jumpHost()) ? null : f.jumpHost().trim());
        c.setJumpPort(blank(f.jumpHost()) ? null : f.jumpPort());
        c.setJumpCredentialId(blank(f.jumpHost()) ? null : f.jumpCredentialId());
        SshCredential saved = repo.save(c);
        audit.record(f.id() == null ? "ssh_credential.create" : "ssh_credential.update", "ssh_credential",
                saved.getId(), Map.of("name", saved.getName()));
        return saved;
    }

    private void validateJump(SshCredential c, Form f) {
        if (blank(f.jumpHost())) {
            return;
        }
        if (f.jumpCredentialId() == null) {
            throw new IllegalArgumentException("Для jump-host выберите учётку");
        }
        if (c.getId() != null && c.getId().equals(f.jumpCredentialId())) {
            throw new IllegalArgumentException("Учётка не может быть jump-хостом сама для себя");
        }
        // цикл: A -> B -> A
        Set<Long> seen = new HashSet<>();
        if (c.getId() != null) {
            seen.add(c.getId());
        }
        Long next = f.jumpCredentialId();
        while (next != null) {
            if (!seen.add(next)) {
                throw new IllegalArgumentException("Цепочка jump-хостов зациклена");
            }
            next = repo.findById(next).map(SshCredential::getJumpCredentialId).orElse(null);
        }
    }

    @Transactional
    public void delete(Long id) {
        SshCredential c = get(id);
        if (generators.findAll().stream().anyMatch(g -> id.equals(g.getSshCredentialId()))) {
            throw new IllegalStateException("Учётка используется генераторами");
        }
        if (repo.existsByJumpCredentialId(id)) {
            throw new IllegalStateException("Учётка используется как jump-хост другой учётки");
        }
        repo.delete(c);
        audit.record("ssh_credential.delete", "ssh_credential", id, Map.of("name", c.getName()));
    }

    /** Расшифровывает учётку (с цепочкой jump-host) для использования в операции. */
    @Transactional(readOnly = true)
    public ResolvedCredential resolve(Long id) {
        return resolve(get(id), 0);
    }

    private ResolvedCredential resolve(SshCredential c, int depth) {
        if (depth > 3) {
            throw new IllegalStateException("Слишком длинная цепочка jump-хостов");
        }
        ResolvedCredential jump = c.getJumpCredentialId() == null ? null : resolve(get(c.getJumpCredentialId()), depth + 1);
        return new ResolvedCredential(c.getId(), c.getName(), c.getUsername(), c.getAuthType(),
                cipher.decrypt(c.getSecretEnc()), cipher.decrypt(c.getPassphraseEnc()),
                cipher.decrypt(c.getSudoPasswordEnc()), c.getJumpHost(), c.getJumpPort(), jump);
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    /** Ключ из textarea: приводим переводы строк и гарантируем завершающий перевод строки. */
    static String normalizeKey(String key) {
        String k = key.replace("\r\n", "\n").replace('\r', '\n').trim();
        return k + "\n";
    }
}
