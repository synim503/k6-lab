package ru.k6lab.server.metrics;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import ru.k6lab.core.analysis.StopCriteriaEvaluator;
import ru.k6lab.core.runstate.RunStatus;
import ru.k6lab.server.run.Run;
import ru.k6lab.server.run.RunEventBroadcaster;
import ru.k6lab.server.run.RunLogService;
import ru.k6lab.server.run.RunOrchestrator;
import ru.k6lab.server.run.RunService;
import ru.k6lab.server.run.RunStep;
import ru.k6lab.server.settings.SettingsService;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * Проверка отложенного автостопа в поиске максимума (≥ 2 ступеней):
 * после обнаружения условий автостопа на ступени N тест проходит ещё 1 ступень (N+1) и останавливается.
 */
class RunMonitorMaxSearchAutoStopTest {

    private static RunStep step(int no, Instant start, Instant holdEnd) {
        RunStep s = new RunStep();
        s.setStepNo(no);
        s.setRampStartAt(start);
        s.setHoldStartAt(start);
        s.setHoldEndAt(holdEnd);
        s.setLevelPct(no * 100.0);
        return s;
    }

    private static Run runningRun(long id) {
        Run r = new Run();
        r.setId(id);
        r.setStatus(RunStatus.RUNNING);
        return r;
    }

    private static List<RunMonitor.LiveRow> failingRows() {
        return List.of(new RunMonitor.LiveRow("pay", 100.0, 95.0, 1200.0, 95, 500.0, 5.0, 1.0, 0.0,
                true, List.of("ошибки > порога"), 1200.0));
    }

    private static RunMonitor newMonitor(RunOrchestrator orchestrator, RunLogService logs) {
        return new RunMonitor(
                mock(RunService.class), orchestrator, mock(PrometheusClient.class),
                mock(SettingsService.class), mock(MetricNamesService.class),
                mock(RunEventBroadcaster.class), logs,
                mock(ru.k6lab.server.result.StepResultService.class),
                mock(ru.k6lab.server.result.ChartVariableStore.class));
    }

    @Test
    void maxSearchRunsOneMoreStepAfterAutostopTriggersThenStops() throws Exception {
        RunOrchestrator orchestrator = mock(RunOrchestrator.class);
        RunLogService logs = mock(RunLogService.class);
        RunMonitor monitor = newMonitor(orchestrator, logs);

        Instant t0 = Instant.parse("2026-11-26T00:00:00Z");
        RunStep s1 = step(1, t0, t0.plusSeconds(300));
        RunStep s2 = step(2, t0.plusSeconds(300), t0.plusSeconds(600));
        RunStep s3 = step(3, t0.plusSeconds(600), t0.plusSeconds(900));
        RunStep s4 = step(4, t0.plusSeconds(900), t0.plusSeconds(1200));
        List<RunStep> steps = List.of(s1, s2, s3, s4);

        Run run = runningRun(42L);
        RunMonitor.State st = new RunMonitor.State();
        st.evaluator = new StopCriteriaEvaluator(new StopCriteriaEvaluator.Config(
                false, 1.5, true, false, 90, false, 1, 1, 60, true));
        st.tick = 2; // следующий evaluateStop будет tick=3 (кратно 3)

        // 1. На ступени 2 (середина удержания) срабатывает условие автостопа — немедленной остановки быть НЕ должно
        Instant wallOnStep2 = t0.plusSeconds(420);
        monitor.evaluateStop(run, null, st, steps, s2, true, 120, 15, 60, failingRows(), List.of(),
                wallOnStep2.minusSeconds(15), wallOnStep2, null, 2);

        verify(orchestrator, never()).stop(anyLong(), anyString());
        assertThat(st.pendingStopTriggerStep).isEqualTo(2);
        assertThat(st.pendingStopAfterStep).isEqualTo(3);
        assertThat(st.pendingStopAt).isEqualTo(s3.getHoldEndAt());
        assertThat(st.stopDispatched).isFalse();

        // 2. Во время контрольной ступени 3 (ещё не конец ступени 3) тест продолжает идти
        Instant wallMidStep3 = t0.plusSeconds(750);
        monitor.evaluateStop(run, null, st, steps, s3, true, 150, 15, 60, failingRows(), List.of(),
                wallMidStep3.minusSeconds(15), wallMidStep3, null, 3);
        verify(orchestrator, never()).stop(anyLong(), anyString());

        // 3. Как только ступень 3 завершилась (wall >= s3.holdEndAt) — тест останавливается до ступени 4
        Instant wallEndStep3 = s3.getHoldEndAt();
        monitor.evaluateStop(run, null, st, steps, s4, false, 0, 15, 60, failingRows(), List.of(),
                wallEndStep3.minusSeconds(15), wallEndStep3, null, 4);

        ArgumentCaptor<String> reasonCap = ArgumentCaptor.forClass(String.class);
        verify(orchestrator, times(1)).stop(eq(42L), reasonCap.capture());
        assertThat(reasonCap.getValue())
                .contains("ступень 2")
                .contains("завершена контрольная ступень 3");
        assertThat(st.stopDispatched).isTrue();

        // 4. Повторный тик не вызывает stop() второй раз
        monitor.evaluateStop(run, null, st, steps, s4, false, 0, 15, 60, failingRows(), List.of(),
                wallEndStep3.plusSeconds(5), wallEndStep3.plusSeconds(5), null, 4);
        verify(orchestrator, times(1)).stop(anyLong(), anyString());
    }

    @Test
    void maxSearchOnFinalStepFinishesLastStepBeforeStopping() throws Exception {
        RunOrchestrator orchestrator = mock(RunOrchestrator.class);
        RunMonitor monitor = newMonitor(orchestrator, mock(RunLogService.class));

        Instant t0 = Instant.parse("2026-11-26T00:00:00Z");
        RunStep s1 = step(1, t0, t0.plusSeconds(300));
        RunStep s2 = step(2, t0.plusSeconds(300), t0.plusSeconds(600));
        List<RunStep> steps = List.of(s1, s2);

        Run run = runningRun(43L);
        RunMonitor.State st = new RunMonitor.State();
        st.evaluator = new StopCriteriaEvaluator(new StopCriteriaEvaluator.Config(
                false, 1.5, true, false, 90, false, 1, 1, 60, true));
        st.tick = 2;

        Instant wallOnLast = t0.plusSeconds(420);
        monitor.evaluateStop(run, null, st, steps, s2, true, 120, 15, 60, failingRows(), List.of(),
                wallOnLast.minusSeconds(15), wallOnLast, null, 2);

        verify(orchestrator, never()).stop(anyLong(), anyString());
        assertThat(st.pendingStopTriggerStep).isEqualTo(2);
        assertThat(st.pendingStopAfterStep).isEqualTo(2);
        assertThat(st.pendingStopAt).isEqualTo(s2.getHoldEndAt());

        monitor.evaluateStop(run, null, st, steps, s2, true, 300, 15, 60, failingRows(), List.of(),
                s2.getHoldEndAt().minusSeconds(15), s2.getHoldEndAt(), null, 2);
        verify(orchestrator, times(1)).stop(eq(43L), anyString());
    }

    @Test
    void singleStepOrDisabledExtraStepStopsImmediately() throws Exception {
        Instant t0 = Instant.parse("2026-11-26T00:00:00Z");
        RunStep s1 = step(1, t0, t0.plusSeconds(300));
        RunStep s2 = step(2, t0.plusSeconds(300), t0.plusSeconds(600));

        // 1. Одноступенчатый тест (не поиск максимума) — немедленная остановка
        RunOrchestrator orch1 = mock(RunOrchestrator.class);
        RunMonitor m1 = newMonitor(orch1, mock(RunLogService.class));
        RunMonitor.State st1 = new RunMonitor.State();
        st1.evaluator = new StopCriteriaEvaluator(new StopCriteriaEvaluator.Config(
                false, 1.5, true, false, 90, false, 1, 1, 60, true));
        st1.tick = 2;
        m1.evaluateStop(runningRun(10L), null, st1, List.of(s1), s1, true, 120, 15, 60, failingRows(), List.of(),
                t0.plusSeconds(105), t0.plusSeconds(120), null, 1);
        verify(orch1, times(1)).stop(eq(10L), anyString());

        // 2. Многоступенчатый тест с extraStepOnMaxSearch=false — немедленная остановка
        RunOrchestrator orch2 = mock(RunOrchestrator.class);
        RunMonitor m2 = newMonitor(orch2, mock(RunLogService.class));
        RunMonitor.State st2 = new RunMonitor.State();
        st2.evaluator = new StopCriteriaEvaluator(new StopCriteriaEvaluator.Config(
                false, 1.5, true, false, 90, false, 1, 1, 60, false));
        st2.tick = 2;
        m2.evaluateStop(runningRun(11L), null, st2, List.of(s1, s2), s1, true, 120, 15, 60, failingRows(), List.of(),
                t0.plusSeconds(105), t0.plusSeconds(120), null, 1);
        verify(orch2, times(1)).stop(eq(11L), anyString());
    }
}
