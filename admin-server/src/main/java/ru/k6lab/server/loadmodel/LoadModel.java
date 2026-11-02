package ru.k6lab.server.loadmodel;

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

/** Модель нагрузки: набор операций скрипта с интенсивностью и SLA. Привязана к скрипту, а не к версии. */
@Getter
@Setter
@Entity
@Table(name = "load_model")
public class LoadModel extends Auditable {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String name;

    private String description;

    @Column(name = "script_id", nullable = false)
    private Long scriptId;

    @Column(name = "is_template", nullable = false)
    private boolean template;

    /** Общая интенсивность (сумма включённых операций), оп/сек. */
    @Column(name = "total_per_sec", nullable = false)
    private double totalPerSec;

    @OneToMany(mappedBy = "model", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.EAGER)
    @OrderBy("position ASC")
    private List<LoadOperation> operations = new ArrayList<>();
}
