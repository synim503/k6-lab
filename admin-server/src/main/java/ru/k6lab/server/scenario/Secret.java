package ru.k6lab.server.scenario;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import ru.k6lab.server.common.Auditable;

/** Секрет сценария: на него ссылаются как ${secret:NAME}; значение шифруется и нигде не показывается. */
@Getter
@Setter
@Entity
@Table(name = "secret")
public class Secret extends Auditable {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String name;

    private String description;

    @Column(name = "value_enc", nullable = false)
    private byte[] valueEnc;
}
