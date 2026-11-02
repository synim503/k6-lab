package ru.k6lab.server.ssh;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.k6lab.server.audit.AuditService;

import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
public class KnownHostService {

    private final KnownHostRepository repo;
    private final AuditService audit;

    public KnownHostService(KnownHostRepository repo, AuditService audit) {
        this.repo = repo;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public Optional<KnownHost> find(String host, int port) {
        return repo.findByHostAndPort(host, port);
    }

    @Transactional(readOnly = true)
    public List<KnownHost> list() {
        return repo.findAll();
    }

    /** Подтверждение ключа пользователем. Существующая запись с другим ключом НЕ перезаписывается. */
    @Transactional
    public KnownHost confirm(String host, int port, String keyType, String fingerprint, String publicKey, String user) {
        Optional<KnownHost> existing = repo.findByHostAndPort(host, port);
        if (existing.isPresent()) {
            if (existing.get().getFingerprintSha256().equals(fingerprint)) {
                return existing.get();
            }
            throw new IllegalStateException("Для " + host + ":" + port + " уже подтверждён другой ключ. "
                    + "Удалите запись о хосте, если ключ сменили намеренно.");
        }
        KnownHost k = new KnownHost();
        k.setHost(host);
        k.setPort(port);
        k.setKeyType(keyType);
        k.setFingerprintSha256(fingerprint);
        k.setPublicKey(publicKey);
        k.setConfirmedBy(user);
        KnownHost saved = repo.save(k);
        audit.record("known_host.confirm", "known_host", saved.getId(),
                Map.of("host", host, "port", port, "fingerprint", fingerprint));
        return saved;
    }

    @Transactional
    public void delete(Long id) {
        repo.findById(id).ifPresent(k -> {
            repo.delete(k);
            audit.record("known_host.delete", "known_host", id, Map.of("host", k.getHost(), "port", k.getPort()));
        });
    }
}
