package ru.k6lab.server.testdata;

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

/** Режим тестовых данных для файла из архива скрипта: общий либо нарезка между генераторами. */
@Getter
@Setter
@Entity
@Table(name = "script_data_file")
public class ScriptDataFile {

    public enum Mode {
        SHARED("общий (одинаковый на всех)"), SPLIT("нарезка по генераторам");

        private final String title;

        Mode(String title) {
            this.title = title;
        }

        public String getTitle() {
            return title;
        }
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "script_id", nullable = false)
    private Long scriptId;

    @Column(nullable = false)
    private String path;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Mode mode = Mode.SHARED;

    @Column(name = "has_header", nullable = false)
    private boolean hasHeader;

    /** Данные расходуются (каждая строка используется один раз) - тогда нужна проверка достаточности строк. */
    @Column(nullable = false)
    private boolean consumed = true;
}
