package ru.k6lab.core.k6config;

/**
 * Подбор {@code timeUnit} для arrival-rate: rate в k6 - целое число итераций за timeUnit. Выбираем наименьший
 * timeUnit, при котором ошибка округления целей всех ступеней не больше 1%.
 */
public final class TimeUnitSelector {

    public static final int[] CANDIDATES_SEC = {1, 2, 5, 10, 30, 60, 120, 300, 600, 1800, 3600};
    public static final double MAX_ERROR_PCT = 1.0;

    private TimeUnitSelector() {
    }

    public record Selection(int unitSec, long[] targets, double maxErrorPct) {
        public boolean withinTolerance() {
            return maxErrorPct <= MAX_ERROR_PCT + 1e-9;
        }

        /** Фактическая интенсивность ступени i, оп/сек. */
        public double actualPerSec(int i) {
            return (double) targets[i] / unitSec;
        }
    }

    public static Selection select(double[] ratesPerSec) {
        Selection best = null;
        for (int unit : CANDIDATES_SEC) {
            Selection s = evaluate(ratesPerSec, unit);
            if (s.withinTolerance()) {
                return s;
            }
            if (best == null || s.maxErrorPct() < best.maxErrorPct()) {
                best = s;
            }
        }
        return best;
    }

    static Selection evaluate(double[] rates, int unit) {
        long[] targets = new long[rates.length];
        double maxErr = 0;
        for (int i = 0; i < rates.length; i++) {
            double r = rates[i];
            // ненулевая интенсивность не должна округляться в 0: минимум 1 итерация за timeUnit
            targets[i] = r > 0 ? Math.max(1, Math.round(r * unit)) : 0;
            double err;
            if (r <= 0) {
                err = targets[i] == 0 ? 0 : 100;
            } else {
                err = Math.abs((double) targets[i] / unit - r) / r * 100.0;
            }
            maxErr = Math.max(maxErr, err);
        }
        return new Selection(unit, targets, maxErr);
    }
}
