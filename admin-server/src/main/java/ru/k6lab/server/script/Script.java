package ru.k6lab.server.script;

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

/** Скрипт: контейнер версий и описание нагрузки (операции, интенсивность, длительность). Нагрузка относится к скрипту, а не к версии. */
@Getter
@Setter
@Entity
@Table(name = "script")
public class Script extends Auditable {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String name;

    private String description;

    /** Для скриптов из Git: репозиторий, ветка и entrypoint, по которым делается «последняя версия ветки». */
    @Column(name = "git_repo_id")
    private Long gitRepoId;

    @Column(name = "git_branch")
    private String gitBranch;

    @Column(name = "git_entrypoint")
    private String gitEntrypoint;

    /** Переменные окружения k6; значения вида ${secret:имя} подставляются из секретов при запуске. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private Map<String, String> env = new LinkedHashMap<>();

    /** Ручные правки конфига k6 как JSON merge patch поверх расчётного конфига (пусто - без правок). */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "k6_overrides", nullable = false)
    private Map<String, Object> k6Overrides = new LinkedHashMap<>();

    /** Критерии остановки (пусто - по умолчанию), см. {@code LoadCriteria}. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "stop_criteria", nullable = false)
    private Map<String, Object> stopCriteria = new LinkedHashMap<>();

    /** {minRatePct, maxDropped, maxCpuPct}. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "success_criteria", nullable = false)
    private Map<String, Object> successCriteria = new LinkedHashMap<>();

    /** Уровень нагрузки в процентах от заданной интенсивности операций. */
    @Column(name = "level_pct", nullable = false)
    private double levelPct = 100;

    @Column(name = "ramp_sec", nullable = false)
    private int rampSec = 30;

    @Column(name = "hold_sec", nullable = false)
    private int holdSec = 300;

    /** Сколько генераторов брать при запуске (подставляется на странице запуска). */
    @Column(name = "generator_count", nullable = false)
    private int generatorCount = 1;
}
