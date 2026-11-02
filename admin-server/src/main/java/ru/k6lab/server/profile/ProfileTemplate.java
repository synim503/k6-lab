package ru.k6lab.server.profile;

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

/** Пользовательский тип профиля (создаёт ADMIN): набор параметров и формула построения ступеней. */
@Getter
@Setter
@Entity
@Table(name = "profile_template")
public class ProfileTemplate extends Auditable {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String name;

    private String description;

    /** {"params":[{"name","label","default"}],"blocks":[{"repeat":{"count","var"},"steps":[{"level","ramp","hold","label"}]}]}. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private JsonNode definition;
}
