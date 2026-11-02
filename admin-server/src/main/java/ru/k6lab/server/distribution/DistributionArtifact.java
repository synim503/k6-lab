package ru.k6lab.server.distribution;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

@Getter
@Setter
@Entity
@Table(name = "distribution_artifact")
public class DistributionArtifact {

    public enum Kind {
        AGENT("Агент (бинарь Go или jar)"), K6("k6"), JRE("JRE 21 (tar.gz)"), XK6("k6 (xk6-сборка)");

        private final String title;

        Kind(String title) {
            this.title = title;
        }

        public String getTitle() {
            return title;
        }
    }

    public enum Arch {
        ANY("любая"), LINUX_AMD64("linux-amd64"), LINUX_ARM64("linux-arm64");

        private final String title;

        Arch(String title) {
            this.title = title;
        }

        public String getTitle() {
            return title;
        }

        /** Соответствие выводу uname -m. */
        public static Arch fromUname(String uname) {
            return switch (uname == null ? "" : uname.trim()) {
                case "x86_64", "amd64" -> LINUX_AMD64;
                case "aarch64", "arm64" -> LINUX_ARM64;
                default -> null;
            };
        }
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Kind kind;

    /** Метка кастомной сборки (xk6), для остальных может быть пустой. */
    @Column(nullable = false)
    private String label = "";

    @Column(nullable = false)
    private String version;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Arch arch;

    @Column(nullable = false)
    private String filename;

    @Column(name = "storage_key", nullable = false)
    private String storageKey;

    @Column(nullable = false)
    private String sha256;

    @Column(name = "size_bytes", nullable = false)
    private long sizeBytes;

    @Column(name = "is_default", nullable = false)
    private boolean isDefault;

    @Column(name = "uploaded_by")
    private String uploadedBy;

    @Version
    @Column(name = "lock_version")
    private long lockVersion;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    /** Агент в виде самостоятельного бинаря (Go) - в отличие от jar, не требует JRE на генераторе. */
    public boolean isNativeAgent() {
        return kind == Kind.AGENT && filename != null && !filename.toLowerCase().endsWith(".jar");
    }
}
