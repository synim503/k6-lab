package ru.k6lab.server.load;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import lombok.Getter;
import lombok.Setter;
import ru.k6lab.core.model.OperationSpec;

import java.util.LinkedHashMap;
import java.util.Map;

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

    /** Исполнитель k6; ramping-arrival-rate - сценарий по профилю нагрузки (оп/с, разгон, удержание). */
    @Column(nullable = false)
    private String executor = OperationSpec.PROFILE_EXECUTOR;

    /** Параметры сценария как в options.scenarios (vus, duration, stages, startTime, env, tags и т.д.). */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private Map<String, Object> scenario = new LinkedHashMap<>();

    public boolean profileDriven() {
        return OperationSpec.isProfile(executor, scenario);
    }

    public OperationSpec toSpec() {
        Map<String, Object> rest = new LinkedHashMap<>(scenario);
        Integer pre = null;
        Integer max = null;
        Map<String, String> env = null;
        Map<String, String> tags = null;
        if (profileDriven()) {
            // у сценария по профилю VU, env и теги - отдельные поля спецификации, остальное (startTime, gracefulStop) остаётся в сценарии
            Object p = rest.remove("preAllocatedVUs");
            Object m = rest.remove("maxVUs");
            pre = p instanceof Number n ? n.intValue() : null;
            max = m instanceof Number n ? n.intValue() : null;
            env = strings(rest.remove("env"));
            tags = strings(rest.remove("tags"));
        }
        return new OperationSpec(name, execFn, OperationSpec.Mode.OPEN, ratePerSec, expectedDurationSec, null, null, pre, max,
                slaPercentile, slaThresholdMs, maxErrorPct, failureSource, env, tags, enabled, null, executor, rest);
    }

    private static Map<String, String> strings(Object o) {
        if (!(o instanceof Map<?, ?> m)) {
            return null;
        }
        Map<String, String> out = new LinkedHashMap<>();
        m.forEach((k, v) -> out.put(String.valueOf(k), String.valueOf(v)));
        return out;
    }
}
