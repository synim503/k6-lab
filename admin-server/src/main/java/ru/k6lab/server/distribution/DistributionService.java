package ru.k6lab.server.distribution;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.security.core.context.SecurityContextHolder;
import ru.k6lab.server.audit.AuditService;
import ru.k6lab.server.common.StorageService;
import ru.k6lab.server.distribution.DistributionArtifact.Arch;
import ru.k6lab.server.distribution.DistributionArtifact.Kind;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
public class DistributionService {

    private final DistributionRepository repo;
    private final StorageService storage;
    private final AuditService audit;

    public DistributionService(DistributionRepository repo, StorageService storage, AuditService audit) {
        this.repo = repo;
        this.storage = storage;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public List<DistributionArtifact> list() {
        return repo.findAllByOrderByKindAscCreatedAtDesc();
    }

    @Transactional
    public DistributionArtifact upload(Kind kind, String label, String version, Arch arch, String filename,
                                       InputStream content, boolean makeDefault) throws IOException {
        String normLabel = label == null ? "" : label.trim();
        if (version == null || version.isBlank()) {
            throw new IllegalArgumentException("Укажите версию");
        }
        if (kind == Kind.XK6 && normLabel.isEmpty()) {
            throw new IllegalArgumentException("Для xk6-сборки укажите метку");
        }
        boolean jar = filename != null && filename.toLowerCase().endsWith(".jar");
        if (kind == Kind.AGENT && jar && arch != Arch.ANY) {
            throw new IllegalArgumentException("Агент (jar) не зависит от архитектуры - выберите «любая»");
        }
        if (kind == Kind.AGENT && !jar && arch == Arch.ANY) {
            throw new IllegalArgumentException("Агент-бинарь (Go) собран под конкретную архитектуру - укажите linux-amd64 или linux-arm64");
        }
        if ((kind == Kind.K6 || kind == Kind.XK6 || kind == Kind.JRE) && arch == Arch.ANY) {
            throw new IllegalArgumentException("Для " + kind.getTitle() + " нужно указать архитектуру");
        }
        if ((kind == Kind.K6 || kind == Kind.XK6) && filename != null
                && filename.toLowerCase().matches(".*\\.(tar\\.gz|tgz|zip|gz)$")) {
            throw new IllegalArgumentException("Нужен сам исполняемый файл k6, а не архив: распакуйте " + filename
                    + " и загрузите бинарь (на генераторе он кладётся как есть)");
        }
        if (repo.findFirstByKindAndVersionAndArchAndLabel(kind, version.trim(), arch, normLabel).isPresent()) {
            throw new IllegalArgumentException("Такая версия уже загружена");
        }
        StorageService.StoredFile stored = storage.store("artifacts", filename, content);
        DistributionArtifact a = new DistributionArtifact();
        a.setKind(kind);
        a.setLabel(normLabel);
        a.setVersion(version.trim());
        a.setArch(arch);
        a.setFilename(filename);
        a.setStorageKey(stored.key());
        a.setSha256(stored.sha256());
        a.setSizeBytes(stored.size());
        a.setUploadedBy(SecurityContextHolder.getContext().getAuthentication() == null ? null
                : SecurityContextHolder.getContext().getAuthentication().getName());
        // первый артефакт этого вида/архитектуры автоматически становится версией по умолчанию
        boolean first = kind == Kind.XK6 ? false : repo.findByKindOrderByCreatedAtDesc(kind).stream()
                .noneMatch(x -> x.getArch() == arch);
        DistributionArtifact saved = repo.save(a);
        if ((makeDefault || first) && kind != Kind.XK6) {
            markDefault(saved);
        }
        audit.record("distribution.upload", "distribution", saved.getId(),
                Map.of("kind", kind, "version", saved.getVersion(), "arch", arch, "sha256", saved.getSha256()));
        return saved;
    }

    @Transactional
    public void setDefault(Long id) {
        DistributionArtifact a = repo.findById(id).orElseThrow();
        if (a.getKind() == Kind.XK6) {
            throw new IllegalArgumentException("xk6-сборки выбираются явно, версии по умолчанию у них нет");
        }
        markDefault(a);
        audit.record("distribution.default", "distribution", id, null);
    }

    private void markDefault(DistributionArtifact a) {
        for (DistributionArtifact other : repo.findByKindOrderByCreatedAtDesc(a.getKind())) {
            if (other.getArch() == a.getArch() && other.isDefault() && !other.getId().equals(a.getId())) {
                other.setDefault(false);
            }
        }
        a.setDefault(true);
    }

    @Transactional
    public void delete(Long id) throws IOException {
        DistributionArtifact a = repo.findById(id).orElseThrow();
        repo.delete(a);
        repo.flush();
        storage.delete(a.getStorageKey());
        audit.record("distribution.delete", "distribution", id, Map.of("version", a.getVersion()));
    }

    @Transactional(readOnly = true)
    public Optional<DistributionArtifact> find(Long id) {
        return repo.findById(id);
    }

    /** Версия по умолчанию для вида и архитектуры (точное совпадение архитектуры приоритетнее ANY). */
    @Transactional(readOnly = true)
    public Optional<DistributionArtifact> defaultFor(Kind kind, Arch arch) {
        List<DistributionArtifact> exact = repo.findDefaultsExact(kind, arch);
        if (!exact.isEmpty()) {
            return Optional.of(exact.get(0));
        }
        return repo.findDefaults(kind, arch).stream().findFirst();
    }
}
