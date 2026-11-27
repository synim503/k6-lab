package ru.k6lab.core.util;

import org.junit.jupiter.api.Test;
import ru.k6lab.core.analysis.StopCriteriaEvaluator;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class NaturalSortTest {

    @Test
    void sortsNumbersNaturallyInsideCyrillicAndLatinStrings() {
        List<String> names = new ArrayList<>(List.of(
                "Профиль 100%",
                "Профиль 20%",
                "профиль 5%",
                "Ступень 10",
                "Ступень 2",
                "v1.10",
                "v1.2"
        ));
        names.sort(NaturalSort.COMPARATOR);
        assertThat(names).containsExactly(
                "v1.2",
                "v1.10",
                "профиль 5%",
                "Профиль 20%",
                "Профиль 100%",
                "Ступень 2",
                "Ступень 10"
        );
    }

    @Test
    void stopCriteriaEvaluatorEvaluatesDroppedPerSecInstantaneously() {
        StopCriteriaEvaluator eval = new StopCriteriaEvaluator(new StopCriteriaEvaluator.Config(
                false, 1.5, false, false, 90.0, true, 1.0, 2, 60, true));

        // 1-й тик: всплеск дропа в моменте 5 оп/с при плане 100 оп/с (5% > 1%), даже если суммарно за окно было много
        var s1 = new StopCriteriaEvaluator.Sample("checkout", 100.0, 500.0, 0.0, 1.0, 95.0, 100.0, 300.0, 5.0);
        assertThat(eval.evaluate(List.of(s1), List.of(), 60)).isEmpty();

        // 2-й тик: в моменте дроп прекратился (0 оп/с), хотя накопительное число dropped всё ещё 300 — серия должна сброситься!
        var s2 = new StopCriteriaEvaluator.Sample("checkout", 100.0, 500.0, 0.0, 1.0, 100.0, 100.0, 300.0, 0.0);
        assertThat(eval.evaluate(List.of(s2), List.of(), 60)).isEmpty();

        // Два подряд реальных мгновенных превышения (3 оп/с = 3% > 1%) срабатывают штатно
        var s3 = new StopCriteriaEvaluator.Sample("checkout", 100.0, 500.0, 0.0, 1.0, 97.0, 100.0, 45.0, 3.0);
        assertThat(eval.evaluate(List.of(s3), List.of(), 60)).isEmpty();
        var res = eval.evaluate(List.of(s3), List.of(), 60);
        assertThat(res).isPresent();
        assertThat(res.get()).contains("дроп операций в моменте").contains("3.0%");
    }
}
