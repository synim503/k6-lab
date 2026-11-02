package ru.k6lab.core.profile;

/** Встроенные типы профилей теста. TEMPLATE - пользовательский шаблон из профиль-шаблона (создаёт ADMIN). */
public enum ProfileType {
    MAX_SEARCH("Поиск максимума"),
    CONFIRMATION("Подтверждение максимума"),
    RELIABILITY("Надёжность"),
    STRESS("Стресс"),
    SPIKE("Spike"),
    CUSTOM("Произвольный"),
    TEMPLATE("Пользовательский шаблон");

    private final String title;

    ProfileType(String title) {
        this.title = title;
    }

    public String getTitle() {
        return title;
    }

    /** Серверные критерии остановки по умолчанию включены для поиска максимума и стресса. */
    public boolean stopCriteriaDefaultOn() {
        return this == MAX_SEARCH || this == STRESS;
    }
}
