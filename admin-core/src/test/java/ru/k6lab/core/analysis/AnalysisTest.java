package ru.k6lab.core.analysis;

import org.junit.jupiter.api.Test;
import ru.k6lab.core.analysis.MaxFinder.Kind;
import ru.k6lab.core.analysis.StepAnalysis.OpMetrics;
import ru.k6lab.core.analysis.StepAnalysis.OpSla;
import ru.k6lab.core.analysis.StepAnalysis.Status;
import ru.k6lab.core.analysis.StepAnalysis.StepVerdict;
import ru.k6lab.core.analysis.StepAnalysis.SuccessCriteria;
import ru.k6lab.core.runstate.RunStatus;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AnalysisTest {

    private static final Map<String, OpSla> SLA = Map.of("pay", new OpSla("pay", 95, 800, 1.0));
    private static final SuccessCriteria C = SuccessCriteria.defaults();

    private static OpMetrics m(double target, double actual, double p95, double err, double dropped) {
        return new OpMetrics("pay", target, actual, p95 / 2, p95 * 0.8, p95, p95 * 1.2, err, dropped);
    }

    private static StepVerdict analyze(int no, OpMetrics metrics) {
        return StepAnalysis.analyze(no, true, false, List.of(metrics), SLA, C);
    }

    // ---------------------------------------------------------------- успешность ступени

    @Test
    void stepIsSuccessfulWhenEverythingIsWithinLimits() {
        var v = analyze(1, m(100, 99, 700, 0.5, 0));
        assertThat(v.status()).isEqualTo(Status.SUCCESS);
        assertThat(v.reasons()).isEmpty();
        assertThat(v.operations().get(0).deviationPct()).isCloseTo(-1.0, org.assertj.core.data.Offset.offset(1e-9));
    }

    @Test
    void boundariesAreInclusive() {
        // ровно на пороге: p95 = SLA, ошибки = 1%, интенсивность = 95%
        assertThat(analyze(1, m(100, 95, 800, 1.0, 0)).status()).isEqualTo(Status.SUCCESS);
    }

    @Test
    void eachViolationFailsTheStepWithReason() {
        assertThat(analyze(1, m(100, 100, 801, 0, 0)).reasons()).anyMatch(r -> r.contains("p95"));
        assertThat(analyze(1, m(100, 100, 100, 1.01, 0)).reasons()).anyMatch(r -> r.contains("ошибки"));
        assertThat(analyze(1, m(100, 94.9, 100, 0, 0)).reasons()).anyMatch(r -> r.contains("интенсивность"));
        assertThat(analyze(1, m(100, 100, 100, 0, 1)).reasons()).anyMatch(r -> r.contains("dropped"));
        assertThat(analyze(1, m(100, 100, 100, 0, 1)).status()).isEqualTo(Status.FAILED);
    }

    @Test
    void missingDataFailsAndOverloadedGeneratorsFail() {
        var noData = new OpMetrics("pay", 100.0, null, null, null, null, null, null, null);
        var v = StepAnalysis.analyze(1, true, false, List.of(noData), SLA, C);
        assertThat(v.status()).isEqualTo(Status.FAILED);
        assertThat(v.reasons()).anyMatch(r -> r.contains("нет данных"));

        var ok = m(100, 100, 100, 0, 0);
        var overloaded = StepAnalysis.analyze(1, true, true, List.of(ok), SLA, C);
        assertThat(overloaded.status()).isEqualTo(Status.FAILED);
        assertThat(overloaded.reasons()).anyMatch(r -> r.contains("перегружены"));
    }

    @Test
    void unreliableStepIsNeitherSuccessNorFailure() {
        var v = StepAnalysis.analyze(1, false, false, List.of(m(100, 100, 100, 0, 0)), SLA, C);
        assertThat(v.status()).isEqualTo(Status.UNRELIABLE);
        assertThat(v.reasons().get(0)).contains("недостоверна");
    }

    @Test
    void percentileFollowsSlaSetting() {
        var sla99 = Map.of("pay", new OpSla("pay", 99, 900, 1.0));
        var metrics = new OpMetrics("pay", 10.0, 10.0, 100.0, 200.0, 300.0, 950.0, 0.0, 0.0);
        assertThat(StepAnalysis.analyze(1, true, false, List.of(metrics), sla99, C).status()).isEqualTo(Status.FAILED);
        assertThatThrownBy(() -> StepAnalysis.analyze(1, true, false, List.of(metrics), Map.of(), C)).hasMessageContaining("SLA");
    }

    // ---------------------------------------------------------------- максимум

    private static StepVerdict v(int no, Status s) {
        return new StepVerdict(no, s, List.of(), List.of());
    }

    @Test
    void maxIsLastSuccessBeforeFirstFailureNotLastSuccessOverall() {
        // успех, успех, провал, успех (шум), успех -> кандидат шаг 2, а не 5
        var r = MaxFinder.find(List.of(v(1, Status.SUCCESS), v(2, Status.SUCCESS), v(3, Status.FAILED),
                v(4, Status.SUCCESS), v(5, Status.SUCCESS)));
        assertThat(r.kind()).isEqualTo(Kind.FOUND);
        assertThat(r.candidateStep()).isEqualTo(2);
        assertThat(r.firstProblemStep()).isEqualTo(3);
    }

    @Test
    void allSuccessMeansNotReachedAndFirstFailureMeansNone() {
        var all = MaxFinder.find(List.of(v(1, Status.SUCCESS), v(2, Status.SUCCESS)));
        assertThat(all.kind()).isEqualTo(Kind.NOT_REACHED);
        assertThat(all.candidateStep()).isEqualTo(2);
        var none = MaxFinder.find(List.of(v(1, Status.FAILED), v(2, Status.SUCCESS)));
        assertThat(none.kind()).isEqualTo(Kind.NONE);
        assertThat(none.candidateStep()).isNull();
        assertThat(MaxFinder.find(List.of()).kind()).isEqualTo(Kind.NONE);
    }

    @Test
    void unreliableStepStopsTheSearch() {
        var r = MaxFinder.find(List.of(v(1, Status.SUCCESS), v(2, Status.UNRELIABLE), v(3, Status.FAILED)));
        assertThat(r.kind()).isEqualTo(Kind.INDETERMINATE);
        assertThat(r.candidateStep()).isEqualTo(1);
        assertThat(r.firstProblemStep()).isEqualTo(2);
    }

    @Test
    void verdictsAreSortedByStepNumber() {
        var r = MaxFinder.find(List.of(v(3, Status.FAILED), v(1, Status.SUCCESS), v(2, Status.SUCCESS)));
        assertThat(r.candidateStep()).isEqualTo(2);
    }

    // ---------------------------------------------------------------- критерии остановки

    private static StopCriteriaEvaluator.Sample sample(Double p, Double err, Double rate, Double dropped) {
        return new StopCriteriaEvaluator.Sample("pay", p, 800, err, 1.0, rate, 100, dropped);
    }

    @Test
    void stopsOnlyAfterNConsecutiveViolations() {
        var ev = new StopCriteriaEvaluator(StopCriteriaEvaluator.Config.defaults(true));
        var bad = List.of(sample(1300.0, 0.0, 100.0, 0.0)); // > 800 * 1.5
        assertThat(ev.evaluate(bad)).isEmpty();
        assertThat(ev.evaluate(bad)).isEmpty();
        Optional<String> third = ev.evaluate(bad);
        assertThat(third).isPresent();
        assertThat(third.get()).contains("pay").contains("3 проверки подряд");
    }

    @Test
    void goodSampleResetsTheStreak() {
        var ev = new StopCriteriaEvaluator(StopCriteriaEvaluator.Config.defaults(true));
        var bad = List.of(sample(0.0 + 1300, 0.0, 100.0, 0.0));
        var good = List.of(sample(500.0, 0.0, 100.0, 0.0));
        ev.evaluate(bad);
        ev.evaluate(bad);
        assertThat(ev.evaluate(good)).isEmpty();
        assertThat(ev.evaluate(bad)).isEmpty();
        assertThat(ev.evaluate(bad)).isEmpty();
        assertThat(ev.evaluate(bad)).isPresent();
    }

    @Test
    void eachCriterionWorksIndependentlyAndCanBeSwitchedOff() {
        var cfg = new StopCriteriaEvaluator.Config(false, 1.5, true, true, 90, true, 1, 60);
        var ev = new StopCriteriaEvaluator(cfg);
        // перцентиль выключен: даже огромное значение не останавливает
        assertThat(ev.evaluate(List.of(sample(99999.0, 0.0, 100.0, 0.0)))).isEmpty();
        assertThat(ev.evaluate(List.of(sample(1.0, 5.0, 100.0, 0.0)))).get().asString().contains("ошибки");
        assertThat(ev.evaluate(List.of(sample(1.0, 0.0, 80.0, 0.0)))).get().asString().contains("интенсивность");
        assertThat(ev.evaluate(List.of(sample(1.0, 0.0, 100.0, 2.0)))).get().asString().contains("dropped_iterations");
        assertThat(new StopCriteriaEvaluator(StopCriteriaEvaluator.Config.defaults(false))
                .evaluate(List.of(sample(99999.0, 99.0, 1.0, 99.0)))).isEmpty();
        assertThat(StopCriteriaEvaluator.Config.defaults(false).anyEnabled()).isFalse();
    }

    @Test
    void missingDataDoesNotCountAndResetClearsStreaks() {
        var ev = new StopCriteriaEvaluator(new StopCriteriaEvaluator.Config(true, 1.5, true, true, 90, true, 2, 60));
        var bad = List.of(sample(2000.0, 0.0, 100.0, 0.0));
        ev.evaluate(bad);
        assertThat(ev.evaluate(List.of(sample(null, null, null, null)))).isEmpty(); // нет данных: счётчик не сбрасывается
        assertThat(ev.evaluate(bad)).isPresent();
        ev.reset();
        assertThat(ev.evaluate(bad)).isEmpty();
    }

    // ---------------------------------------------------------------- state machine

    @Test
    void legalTransitionsPass() {
        assertThat(RunStatus.QUEUED.transitionTo(RunStatus.PREPARING)).isEqualTo(RunStatus.PREPARING);
        assertThat(RunStatus.PREPARING.transitionTo(RunStatus.RUNNING)).isEqualTo(RunStatus.RUNNING);
        assertThat(RunStatus.RUNNING.transitionTo(RunStatus.STOPPING)).isEqualTo(RunStatus.STOPPING);
        assertThat(RunStatus.STOPPING.transitionTo(RunStatus.FINISHED)).isEqualTo(RunStatus.FINISHED);
        assertThat(RunStatus.RUNNING.canTransitionTo(RunStatus.FINISHED)).isTrue();
        assertThat(RunStatus.PREPARING.canTransitionTo(RunStatus.FAILED)).isTrue();
        assertThat(RunStatus.QUEUED.canTransitionTo(RunStatus.ABORTED)).isTrue();
    }

    @Test
    void illegalTransitionsThrowAndTerminalStatesAreFinal() {
        assertThatThrownBy(() -> RunStatus.QUEUED.transitionTo(RunStatus.RUNNING))
                .isInstanceOf(RunStatus.IllegalStateTransitionException.class).hasMessageContaining("QUEUED -> RUNNING");
        assertThatThrownBy(() -> RunStatus.FINISHED.transitionTo(RunStatus.RUNNING)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> RunStatus.RUNNING.transitionTo(RunStatus.PREPARING)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> RunStatus.STOPPING.transitionTo(RunStatus.RUNNING)).isInstanceOf(IllegalStateException.class);
        for (RunStatus terminal : new RunStatus[]{RunStatus.FINISHED, RunStatus.FAILED, RunStatus.ABORTED}) {
            assertThat(terminal.isTerminal()).isTrue();
            for (RunStatus next : RunStatus.values()) {
                assertThat(terminal.canTransitionTo(next)).isFalse();
            }
        }
        assertThat(RunStatus.RUNNING.isActive()).isTrue();
        assertThat(RunStatus.QUEUED.isActive()).isFalse();
    }
}
