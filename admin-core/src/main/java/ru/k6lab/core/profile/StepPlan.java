package ru.k6lab.core.profile;

/**
 * Ступень профиля: разгон до уровня и удержание. Уровень - % от интенсивности модели (100 = номинал).
 * Номер ступени и интервал удержания - основа анализа результатов.
 */
public record StepPlan(int no, double levelPct, int rampSec, int holdSec, String label) {

    public StepPlan {
        if (levelPct < 0) {
            throw new IllegalArgumentException("Уровень ступени " + no + " не может быть отрицательным");
        }
        if (rampSec < 0 || holdSec < 0) {
            throw new IllegalArgumentException("Длительности ступени " + no + " не могут быть отрицательными");
        }
        if (holdSec == 0) {
            throw new IllegalArgumentException("У ступени " + no + " нет удержания: анализировать нечего");
        }
    }

    public int totalSec() {
        return rampSec + holdSec;
    }
}
