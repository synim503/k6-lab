package ru.k6lab.server.sut;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import ru.k6lab.server.common.Auditable;

import java.util.ArrayList;
import java.util.List;

/** Тестируемая система: метрики из Prometheus, показываемые рядом с метриками k6 (live и по ступеням). */
@Getter
@Setter
@Entity
@Table(name = "sut")
public class Sut extends Auditable {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String name;

    private String description;

    /** Шаблон ссылки на Grafana-дашборд: плейсхолдеры {{start}}, {{end}} (мс), {{run_id}}. */
    @Column(name = "grafana_url_tpl")
    private String grafanaUrlTpl;

    @OneToMany(mappedBy = "sut", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.EAGER)
    @OrderBy("position ASC")
    private List<SutMetric> metrics = new ArrayList<>();
}
