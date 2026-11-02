package ru.k6lab.server.scenario;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import ru.k6lab.server.common.Auditable;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Сценарий = версия скрипта (закреплённая, последняя или «последняя версия ветки Git» с фиксацией коммита при
 * запуске) + модель + профиль + env + секреты + режимы данных + критерии остановки + SUT.
 */
@Getter
@Setter
@Entity
@Table(name = "scenario")
public class Scenario extends Auditable {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String name;

    private String description;

    @Column(name = "script_id", nullable = false)
    private Long scriptId;

    /** Закреплённая версия; null - брать последнюю (или последнюю версию ветки при followBranch). */
    @Column(name = "script_version_id")
    private Long scriptVersionId;

    @Column(name = "follow_branch", nullable = false)
    private boolean followBranch;

    @Column(name = "model_id", nullable = false)
    private Long modelId;

    @Column(name = "profile_id", nullable = false)
    private Long profileId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private Map<String, String> env = new LinkedHashMap<>();

    @Column(name = "expert_mode", nullable = false)
    private boolean expertMode;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "expert_config")
    private JsonNode expertConfig;

    /** {percentileEnabled, slaMultiplier, errorsEnabled, rateEnabled, minRatePct, droppedEnabled, consecutive, windowSec}. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "stop_criteria", nullable = false)
    private Map<String, Object> stopCriteria = new LinkedHashMap<>();

    /** {minRatePct, maxDropped, maxCpuPct}. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "success_criteria", nullable = false)
    private Map<String, Object> successCriteria = new LinkedHashMap<>();

    @Column(name = "sut_id")
    private Long sutId;

    @Column(name = "is_template", nullable = false)
    private boolean template;
}
