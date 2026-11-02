package ru.k6lab.server.script;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Неизменяемая версия скрипта (правка = новая версия). Содержимое - zip в хранилище. */
@Getter
@Setter
@Entity
@Table(name = "script_version")
public class ScriptVersion {

    public enum Source {
        UPLOAD("загрузка"), EDITOR("редактор"), ROLLBACK("откат"), GIT("git");

        private final String title;

        Source(String title) {
            this.title = title;
        }

        public String getTitle() {
            return title;
        }
    }

    public enum InspectStatus { OK, FAILED, UNAVAILABLE }

    public record OpEntry(String name, String origin) {
    }

    public record FileEntry(String path, long size, String kind) {
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "script_id", nullable = false)
    private Long scriptId;

    @Column(nullable = false)
    private int number;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Source source;

    private String comment;

    @Column(name = "parent_version_id")
    private Long parentVersionId;

    @Column(name = "git_repo_id")
    private Long gitRepoId;

    @Column(name = "git_ref")
    private String gitRef;

    @Column(name = "git_branch")
    private String gitBranch;

    @Column(name = "commit_hash")
    private String commitHash;

    @Column(name = "storage_key", nullable = false)
    private String storageKey;

    @Column(nullable = false)
    private String entrypoint;

    @Column(nullable = false)
    private String sha256;

    @Column(name = "size_bytes", nullable = false)
    private long sizeBytes;

    @Enumerated(EnumType.STRING)
    @Column(name = "inspect_status", nullable = false)
    private InspectStatus inspectStatus;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "inspect_result")
    private Map<String, Object> inspectResult;

    @Column(name = "inspect_error", columnDefinition = "text")
    private String inspectError;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private List<OpEntry> operations = new ArrayList<>();

    @Column(name = "valid_for_model", nullable = false)
    private boolean validForModel;

    @Column(name = "invalid_reason", columnDefinition = "text")
    private String invalidReason;

    @Column(name = "uses_helper", nullable = false)
    private boolean usesHelper;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private List<String> warnings = new ArrayList<>();

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private List<FileEntry> files = new ArrayList<>();

    @Column(name = "created_by")
    private String createdBy;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    /** Источник для UI: загрузка либо git + коммит. */
    public String sourceLabel() {
        if (source == Source.GIT && commitHash != null) {
            return "git " + (gitBranch != null ? gitBranch + " @ " : "") + commitHash.substring(0, Math.min(8, commitHash.length()));
        }
        return source.getTitle();
    }

    public List<String> operationNames() {
        return operations.stream().map(OpEntry::name).toList();
    }
}
