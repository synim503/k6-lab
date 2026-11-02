package ru.k6lab.server.run;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/** Ступень run: номер и интервалы разгона/удержания - основа анализа результатов. */
@Getter
@Setter
@Entity
@Table(name = "run_step")
public class RunStep {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "run_id", nullable = false)
    private Long runId;

    @Column(name = "step_no", nullable = false)
    private int stepNo;

    private String label;

    @Column(name = "level_pct", nullable = false)
    private double levelPct;

    @Column(name = "ramp_start_at", nullable = false)
    private Instant rampStartAt;

    @Column(name = "hold_start_at", nullable = false)
    private Instant holdStartAt;

    @Column(name = "hold_end_at", nullable = false)
    private Instant holdEndAt;

    /** false, если генератор из состава отсутствовал на этой ступени. */
    @Column(nullable = false)
    private boolean reliable = true;
}
