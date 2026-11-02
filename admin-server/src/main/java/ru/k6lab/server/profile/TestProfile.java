package ru.k6lab.server.profile;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import ru.k6lab.core.profile.ProfileType;
import ru.k6lab.server.common.Auditable;

import java.util.LinkedHashMap;
import java.util.Map;

/** Профиль теста: тип + параметры + graceful. Уровень % применяется ко всем операциям (кроме зафиксированных). */
@Getter
@Setter
@Entity
@Table(name = "test_profile")
public class TestProfile extends Auditable {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ProfileType type;

    @Column(name = "template_id")
    private Long templateId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private Map<String, Object> params = new LinkedHashMap<>();

    @Column(name = "graceful_stop_sec", nullable = false)
    private int gracefulStopSec = 30;

    @Column(name = "graceful_ramp_down_sec", nullable = false)
    private int gracefulRampDownSec = 30;

    @Column(name = "is_template", nullable = false)
    private boolean template;
}
