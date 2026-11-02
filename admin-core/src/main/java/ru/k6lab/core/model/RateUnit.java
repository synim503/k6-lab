package ru.k6lab.core.model;

/** Единицы интенсивности. Внутри везде оп/сек; в UI по умолчанию оп/час. */
public enum RateUnit {
    PER_SEC("оп/сек", 1),
    PER_MIN("оп/мин", 60),
    PER_HOUR("оп/час", 3600);

    private final String title;
    private final int seconds;

    RateUnit(String title, int seconds) {
        this.title = title;
        this.seconds = seconds;
    }

    public String getTitle() {
        return title;
    }

    public double toPerSec(double value) {
        return value / seconds;
    }

    public double fromPerSec(double perSec) {
        return perSec * seconds;
    }
}
