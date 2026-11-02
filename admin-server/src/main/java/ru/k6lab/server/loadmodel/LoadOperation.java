package ru.k6lab.server.loadmodel;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import ru.k6lab.core.model.OperationSpec;
import ru.k6lab.core.model.RateUnit;

import java.util.LinkedHashMap;
import java.util.Map;

@Getter
@Setter
@Entity
@Table(name = "load_operation")
public class LoadOperation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "model_id")
    private LoadModel model;

    @Column(nullable = false)
    private int position;

    @Column(nullable = false)
    private String name;

    @Column(name = "exec_fn", nullable = false)
    private String execFn;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private OperationSpec.Mode mode = OperationSpec.Mode.OPEN;

    @Column(name = "intensity_value", nullable = false)
    private double intensityValue;

    @Enumerated(EnumType.STRING)
    @Column(name = "intensity_unit", nullable = false)
    private RateUnit intensityUnit = RateUnit.PER_HOUR;

    @Column(name = "share_pct", nullable = false)
    private double sharePct;

    @Column(name = "expected_duration_sec", nullable = false)
    private double expectedDurationSec;

    @Column(name = "pacing_ms")
    private Integer pacingMs;

    @Column(name = "vus_override")
    private Integer vusOverride;

    @Column(name = "pre_alloc_override")
    private Integer preAllocOverride;

    @Column(name = "max_vus_override")
    private Integer maxVusOverride;

    @Column(name = "sla_percentile", nullable = false)
    private int slaPercentile = 95;

    @Column(name = "sla_threshold_ms", nullable = false)
    private double slaThresholdMs = 1000;

    @Column(name = "max_error_pct", nullable = false)
    private double maxErrorPct = 1;

    @Enumerated(EnumType.STRING)
    @Column(name = "failure_source", nullable = false)
    private OperationSpec.FailureSource failureSource = OperationSpec.FailureSource.HTTP;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private Map<String, String> env = new LinkedHashMap<>();

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private Map<String, String> tags = new LinkedHashMap<>();

    @Column(nullable = false)
    private boolean enabled = true;

    @Column(name = "fixed_level_pct")
    private Double fixedLevelPct;

    public double ratePerSec() {
        return intensityUnit.toPerSec(intensityValue);
    }

    public OperationSpec toSpec() {
        return new OperationSpec(name, execFn, mode, ratePerSec(), expectedDurationSec, pacingMs, vusOverride,
                preAllocOverride, maxVusOverride, slaPercentile, slaThresholdMs, maxErrorPct, failureSource, env, tags,
                enabled, fixedLevelPct);
    }
}
