package ru.k6lab.server.sut;

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

@Getter
@Setter
@Entity
@Table(name = "sut_metric")
public class SutMetric {

    public enum Aggregation { AVG, MAX, LAST }

    public enum Direction { ABOVE, BELOW }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "sut_id")
    private Sut sut;

    @Column(nullable = false)
    private int position;

    @Column(nullable = false)
    private String name;

    /** PromQL с плейсхолдерами {{start}}, {{end}}, {{range}}. */
    @Column(nullable = false, columnDefinition = "text")
    private String promql;

    private String unit;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Aggregation aggregation = Aggregation.AVG;

    @Column(name = "warn_threshold")
    private Double warnThreshold;

    @Enumerated(EnumType.STRING)
    @Column(name = "warn_direction", nullable = false)
    private Direction warnDirection = Direction.ABOVE;

    public boolean exceeds(double value) {
        if (warnThreshold == null) {
            return false;
        }
        return warnDirection == Direction.ABOVE ? value > warnThreshold : value < warnThreshold;
    }
}
