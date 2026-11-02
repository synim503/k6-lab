package ru.k6lab.core.analysis;

import ru.k6lab.core.analysis.StepAnalysis.Status;
import ru.k6lab.core.analysis.StepAnalysis.StepVerdict;

import java.util.Comparator;
import java.util.List;

/**
 * Определение максимума: <b>последняя успешная ступень перед первой неуспешной</b> (а не последняя успешная вообще).
 * Это кандидат; итоговое решение остаётся за инженером.
 * <p>
 * Недостоверная ступень (генератор отваливался) не считается ни успешной, ни провальной, но поиск на ней
 * останавливается: перепрыгивать ступень без данных нельзя.
 */
public final class MaxFinder {

    private MaxFinder() {
    }

    public enum Kind {
        /** Есть успешная ступень, за которой идёт неуспешная. */
        FOUND,
        /** Все ступени успешны: максимум выше проверенного уровня. */
        NOT_REACHED,
        /** Уже первая ступень неуспешна: максимум ниже стартового уровня. */
        NONE,
        /** Поиск остановился на недостоверной ступени. */
        INDETERMINATE
    }

    public record Result(Kind kind, Integer candidateStep, Integer firstProblemStep, String note) {
    }

    public static Result find(List<StepVerdict> verdicts) {
        List<StepVerdict> v = verdicts.stream().sorted(Comparator.comparingInt(StepVerdict::stepNo)).toList();
        if (v.isEmpty()) {
            return new Result(Kind.NONE, null, null, "Нет ступеней для анализа");
        }
        Integer lastSuccess = null;
        for (StepVerdict s : v) {
            if (s.status() == Status.SUCCESS) {
                lastSuccess = s.stepNo();
                continue;
            }
            if (s.status() == Status.UNRELIABLE) {
                return new Result(Kind.INDETERMINATE, lastSuccess, s.stepNo(),
                        "Ступень " + s.stepNo() + " недостоверна (генератор отваливался)"
                                + (lastSuccess != null ? "; нижняя граница - ступень " + lastSuccess : ""));
            }
            // первая неуспешная
            if (lastSuccess == null) {
                return new Result(Kind.NONE, null, s.stepNo(), "Первая же ступень неуспешна: максимум ниже стартового уровня");
            }
            return new Result(Kind.FOUND, lastSuccess, s.stepNo(), "Кандидат: ступень " + lastSuccess
                    + " (следующая, " + s.stepNo() + ", неуспешна)");
        }
        return new Result(Kind.NOT_REACHED, lastSuccess, null,
                "Все ступени успешны: максимум не достигнут, нижняя граница - ступень " + lastSuccess);
    }
}
