package ru.k6lab.server.load;

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
import ru.k6lab.core.model.OperationSpec;

/** Операция нагрузки скрипта: экспортируемая функция с интенсивностью и SLA. */
@Getter
@Setter
@Entity
@Table(name = "load_operation")
public class LoadOperation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "script_id", nullable = false)
    private Long scriptId;

    @Column(nullable = false)
    private int position;

    @Column(nullable = false)
    private String name;

    @Column(name = "exec_fn", nullable = false)
    private String execFn;

    /** Интенсивность, операций в секунду. */
    @Column(name = "rate_per_sec", nullable = false)
    private double ratePerSec;

    /** Ожидаемая длительность одной операции: по ней оценивается число VU. */
    @Column(name = "expected_duration_sec", nullable = false)
    private double expectedDurationSec = 0.3;

    @Column(name = "sla_percentile", nullable = false)
    private int slaPercentile = 95;

    @Column(name = "sla_threshold_ms", nullable = false)
    private double slaThresholdMs = 1000;

    @Column(name = "max_error_pct", nullable = false)
    private double maxErrorPct = 1;

    @Enumerated(EnumType.STRING)
    @Column(name = "failure_source", nullable = false)
    private OperationSpec.FailureSource failureSource = OperationSpec.FailureSource.HTTP;

    @Column(nullable = false)
    private boolean enabled = true;

    public OperationSpec toSpec() {
        return new OperationSpec(name, execFn, OperationSpec.Mode.OPEN, ratePerSec, expectedDurationSec, null, null, null, null,
                slaPercentile, slaThresholdMs, maxErrorPct, failureSource, null, null, enabled, null);
    }
}
