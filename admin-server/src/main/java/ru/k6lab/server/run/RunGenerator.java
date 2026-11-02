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

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/** Участие генератора в run: доля нагрузки (execution segment), состояние агента, summary, интервалы отсутствия. */
@Getter
@Setter
@Entity
@Table(name = "run_generator")
public class RunGenerator {

    public enum Status {
        RESERVED, PREPARING, PREPARED, RUNNING, STOPPING, EXITED, FAILED, LOST;

        public boolean isDone() {
            return this == EXITED || this == FAILED || this == LOST;
        }
    }

    public record Interval(Instant from, Instant to) {
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "run_id", nullable = false)
    private Long runId;

    @Column(name = "generator_id")
    private Long generatorId;

    @Column(name = "generator_name", nullable = false)
    private String generatorName;

    @Column(name = "seq_index", nullable = false)
    private int seqIndex;

    @Column(nullable = false)
    private int weight = 1;

    @Column(name = "segment_start", nullable = false)
    private String segmentStart = "0";

    @Column(name = "segment_end", nullable = false)
    private String segmentEnd = "1";

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status = Status.RESERVED;

    @Column(name = "exit_code")
    private Integer exitCode;

    @Column(name = "k6_api_port")
    private Integer k6ApiPort;

    @JdbcTypeCode(SqlTypes.JSON)
    private JsonNode summary;

    /** Интервалы, когда генератор был отключён во время теста (ступени на них недостоверны). */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "offline_intervals", nullable = false)
    private List<Interval> offlineIntervals = new ArrayList<>();

    @Column(name = "last_vus")
    private Long lastVus;

    @Column(name = "last_iterations")
    private Long lastIterations;

    @Column(name = "last_dropped")
    private Long lastDropped;

    @Column(name = "dropped_log_lines", nullable = false)
    private long droppedLogLines;

    @Column(name = "prepare_error", columnDefinition = "text")
    private String prepareError;

    @Column(name = "artifact_token_hash")
    private String artifactTokenHash;

    @Column(name = "artifact_consumed_at")
    private Instant artifactConsumedAt;

    public String segment() {
        return segmentStart + ":" + segmentEnd;
    }
}
