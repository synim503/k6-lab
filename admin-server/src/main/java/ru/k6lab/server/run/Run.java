package ru.k6lab.server.run;

import com.fasterxml.jackson.databind.JsonNode;
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
import ru.k6lab.core.runstate.RunStatus;
import ru.k6lab.server.common.Auditable;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/** Запуск скрипта. Хранит полный снимок нагрузки и сгенерированного конфига: результаты не зависят от последующих правок. */
@Getter
@Setter
@Entity
@Table(name = "run")
public class Run extends Auditable {

    public enum FailurePolicy {
        CONTINUE("продолжить на оставшихся"), STOP_ALL("остановить все");

        private final String title;

        FailurePolicy(String title) {
            this.title = title;
        }

        public String getTitle() {
            return title;
        }
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "script_id")
    private Long scriptId;

    @Column(name = "script_name", nullable = false)
    private String scriptName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private RunStatus status = RunStatus.QUEUED;

    /** Генератор отвалился во время теста (отдельный флаг, не статус). */
    @Column(nullable = false)
    private boolean degraded;

    /** k6 завершился кодом 99 (thresholds): отображается отдельно от аварии. */
    @Column(name = "thresholds_failed", nullable = false)
    private boolean thresholdsFailed;

    @Enumerated(EnumType.STRING)
    @Column(name = "failure_policy", nullable = false)
    private FailurePolicy failurePolicy = FailurePolicy.CONTINUE;

    @Column(name = "stop_reason", columnDefinition = "text")
    private String stopReason;

    @Column(columnDefinition = "text")
    private String error;

    /** Выбор генераторов: либо список с весами, либо пул и число. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private Map<String, Object> request = new LinkedHashMap<>();

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private Map<String, Object> snapshot = new LinkedHashMap<>();

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "k6_config")
    private JsonNode k6Config;

    @Column(name = "script_version_id")
    private Long scriptVersionId;

    @Column(name = "prepare_timeout_sec", nullable = false)
    private int prepareTimeoutSec = 120;

    @Column(name = "queued_at", nullable = false)
    private Instant queuedAt = Instant.now();

    @Column(name = "planned_start_at")
    private Instant plannedStartAt;

    @Column(name = "started_at")
    private Instant startedAt;

    /** Сколько секунд прошло от плановой точки старта до реального начала нагрузки (инициализация скрипта k6); null - пока не определено. */
    @Column(name = "init_sec")
    private Integer initSec;

    @Column(name = "finished_at")
    private Instant finishedAt;

    @Column(columnDefinition = "text")
    private String conclusion;

    @Column(name = "conclusion_comment", columnDefinition = "text")
    private String conclusionComment;


    @Column(name = "created_by")
    private String createdBy;

    /** Статус с явной проверкой допустимости перехода. */
    public void transitionTo(RunStatus next) {
        this.status = status.transitionTo(next);
        if (next.isTerminal()) {
            this.finishedAt = Instant.now();
        }
    }
}
