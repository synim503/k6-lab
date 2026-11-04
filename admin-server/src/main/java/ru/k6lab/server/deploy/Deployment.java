package ru.k6lab.server.deploy;

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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** История операций над генератором (установка, обновление, перезапуск, удаление, pre-flight). */
@Getter
@Setter
@Entity
@Table(name = "deployment")
public class Deployment {

    public enum Operation { PREFLIGHT, INSTALL, UPDATE, RESTART, UNINSTALL, REDEPLOY }

    public enum Status {
        PENDING, RUNNING, WAITING_HOST_KEY, SUCCEEDED, FAILED, ROLLED_BACK;

        public boolean isFinal() {
            return this == SUCCEEDED || this == FAILED || this == ROLLED_BACK;
        }
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "generator_id")
    private Long generatorId;

    @Column(name = "target_host", nullable = false)
    private String targetHost;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Operation operation;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status = Status.PENDING;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private List<StepInfo> steps = new ArrayList<>();

    @Column(nullable = false, columnDefinition = "text")
    private String log = "";

    @Column(columnDefinition = "text")
    private String error;

    @Column(name = "host_key_type")
    private String hostKeyType;

    @Column(name = "host_key_fingerprint")
    private String hostKeyFingerprint;

    @Column(name = "host_key_public", columnDefinition = "text")
    private String hostKeyPublic;

    @Column(name = "host_key_host")
    private String hostKeyHost;

    @Column(name = "host_key_port")
    private Integer hostKeyPort;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private Map<String, Object> params = new LinkedHashMap<>();

    @Column(name = "started_by")
    private String startedBy;

    @Column(name = "started_at", nullable = false)
    private Instant startedAt = Instant.now();

    @Column(name = "finished_at")
    private Instant finishedAt;

    public record StepInfo(int n, String name, String status, String message, Instant startedAt, Instant finishedAt) {
    }
}
