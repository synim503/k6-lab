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
        m1.evaluateStop(runningRun(10L), null, st1, List.of(s1), s1, true, 120, 15, 60, failingRows(), List.of(),
                t0.plusSeconds(105), t0.plusSeconds(120), null, 1);
        verify(orch1, times(1)).stop(eq(10L), anyString());

        // 2. Многоступенчатый тест с extraStepOnMaxSearch=false — немедленная остановка
        RunOrchestrator orch2 = mock(RunOrchestrator.class);
        RunMonitor m2 = newMonitor(orch2, mock(RunLogService.class));
        RunMonitor.State st2 = new RunMonitor.State();
        st2.evaluator = new StopCriteriaEvaluator(new StopCriteriaEvaluator.Config(
                false, 1.5, true, false, 90, false, 1, 1, 60, false));
        m2.evaluateStop(runningRun(11L), null, st2, List.of(s1, s2), s1, true, 120, 15, 60, failingRows(), List.of(),
                t0.plusSeconds(105), t0.plusSeconds(120), null, 1);
        verify(orch2, times(1)).stop(eq(11L), anyString());
    }

    @Test
    void customMetricChecksImmediatelyAfterSettleAndCarriesStreakAcrossSteps() throws Exception {
        RunOrchestrator orchestrator = mock(RunOrchestrator.class);
        RunLogService logs = mock(RunLogService.class);
        RunMonitor monitor = newMonitor(orchestrator, logs);

        Instant t0 = Instant.parse("2026-11-26T00:00:00Z");
        RunStep s1 = step(1, t0, t0.plusSeconds(180));
        RunStep s2 = step(2, t0.plusSeconds(180), t0.plusSeconds(360));
        RunStep s3 = step(3, t0.plusSeconds(360), t0.plusSeconds(540));
        RunStep s4 = step(4, t0.plusSeconds(540), t0.plusSeconds(720));
        RunStep s5 = step(5, t0.plusSeconds(720), t0.plusSeconds(900));
        List<RunStep> steps = List.of(s1, s2, s3, s4, s5);

        var cpuRule = new ru.k6lab.core.analysis.StepAnalysis.CustomCheck(
                "CPU unix", "cpu_usage", "LTE", 80.0, "%", true, true);
        var breach = List.of(new ru.k6lab.core.analysis.StepAnalysis.CustomCheckResult(
                cpuRule, 86.1, "storage-05", false, false));

        Run run = runningRun(77L);
        RunMonitor.State st = new RunMonitor.State();
        // consecutive = 3, k6-критерии выключены, только кастомный автостоп
        st.evaluator = new StopCriteriaEvaluator(new StopCriteriaEvaluator.Config(
                false, 1.5, false, false, 90, false, 1, 3, 60, true));

        // 2 нарушения в конце ступени 3 (1/3 и 2/3, шаг ровно 15 с по реальному времени) — автостоп ещё не сработал
        monitor.evaluateStop(run, null, st, steps, s3, true, 150, 30, 60, List.of(), breach,
                t0.plusSeconds(495), t0.plusSeconds(510), null, 3);
        // Повторный тик через 5 с (< 15 с) игнорируется и не должен засчитываться в серию
        monitor.evaluateStop(run, null, st, steps, s3, true, 155, 30, 60, List.of(), breach,
                t0.plusSeconds(500), t0.plusSeconds(515), null, 3);
        monitor.evaluateStop(run, null, st, steps, s3, true, 165, 30, 60, List.of(), breach,
                t0.plusSeconds(510), t0.plusSeconds(525), null, 3);
        assertThat(st.pendingStopAt).isNull();

        // Переходим на ступень 4 (intoHold = 25 с: уже >= SETTLE_SEC=20, хотя < SETTLE_SEC+minWindow=50!)
        // Серия не сброшена -> 3-я проверка подряд сразу фиксирует деградацию на ступени 4 и планирует стоп после ступени 5
        monitor.evaluateStop(run, null, st, steps, s4, true, 25, 30, 60, List.of(), breach,
                t0.plusSeconds(550), t0.plusSeconds(565), null, 4);
        assertThat(st.pendingStopTriggerStep).isEqualTo(4);
        assertThat(st.pendingStopAfterStep).isEqualTo(5);
        assertThat(st.pendingStopAt).isEqualTo(s5.getHoldEndAt());
        verify(orchestrator, never()).stop(anyLong(), anyString());
    }

    @Test
    void briefSingleTickCustomMetricSpikeDoesNotTriggerAutostop() throws Exception {
        RunOrchestrator orchestrator = mock(RunOrchestrator.class);
        RunMonitor monitor = newMonitor(orchestrator, mock(RunLogService.class));

        Instant t0 = Instant.parse("2026-11-26T00:00:00Z");
        RunStep s1 = step(1, t0, t0.plusSeconds(180));
        RunStep s2 = step(2, t0.plusSeconds(180), t0.plusSeconds(360));
        List<RunStep> steps = List.of(s1, s2);

        var cpuRule = new ru.k6lab.core.analysis.StepAnalysis.CustomCheck(
                "CPU unix", "cpu_usage", "LTE", 80.0, "%", true, true);
        var spike = List.of(new ru.k6lab.core.analysis.StepAnalysis.CustomCheckResult(
                cpuRule, 86.1, "storage-05", false, false));
        var normal = List.of(new ru.k6lab.core.analysis.StepAnalysis.CustomCheckResult(
                cpuRule, 72.0, "storage-05", true, false));

        Run run = runningRun(78L);
        RunMonitor.State st = new RunMonitor.State();
        st.evaluator = new StopCriteriaEvaluator(new StopCriteriaEvaluator.Config(
                false, 1.5, false, false, 90, false, 1, 3, 60, true));

        // 1 всплеск -> возврат в норму через 15 с -> ещё 1 всплеск через 15 с: серия каждый раз сбрасывается
        monitor.evaluateStop(run, null, st, steps, s1, true, 60, 30, 60, List.of(), spike,
                t0.plusSeconds(45), t0.plusSeconds(60), null, 1);
        monitor.evaluateStop(run, null, st, steps, s1, true, 75, 30, 60, List.of(), normal,
                t0.plusSeconds(60), t0.plusSeconds(75), null, 1);
        monitor.evaluateStop(run, null, st, steps, s1, true, 90, 30, 60, List.of(), spike,
                t0.plusSeconds(75), t0.plusSeconds(90), null, 1);

        assertThat(st.pendingStopAt).isNull();
        verify(orchestrator, never()).stop(anyLong(), anyString());
    }

    @Test
    void stopCheckUsesWallClockIntervalWithoutTickJitter() throws Exception {
        RunOrchestrator orchestrator = mock(RunOrchestrator.class);
        RunMonitor monitor = newMonitor(orchestrator, mock(RunLogService.class));

        Instant t0 = Instant.parse("2026-11-26T00:00:00Z");
        RunStep s1 = step(1, t0, t0.plusSeconds(300));
        var cpuRule = new ru.k6lab.core.analysis.StepAnalysis.CustomCheck(
                "CPU unix", "cpu_usage", "LTE", 80.0, "%", true, true);
        var breach = List.of(new ru.k6lab.core.analysis.StepAnalysis.CustomCheckResult(
                cpuRule, 88.0, "storage-05", false, false));

        Run run = runningRun(79L);
        RunMonitor.State st = new RunMonitor.State();
        st.evaluator = new StopCriteriaEvaluator(new StopCriteriaEvaluator.Config(
                false, 1.5, false, false, 90, false, 1, 2, 60, false));

        // Первая проверка сразу после SETTLE_SEC (intoHold=20) срабатывает без задержки (1/2)
        monitor.evaluateStop(run, null, st, List.of(s1), s1, true, 20, 30, 60, List.of(), breach,
                t0.plusSeconds(20), t0.plusSeconds(35), null, 1);
        assertThat(st.lastStopCheckAt).isEqualTo(t0.plusSeconds(35));
        verify(orchestrator, never()).stop(anyLong(), anyString());

        // Тики через 5 с и 14 с (< 15 с) пропускаются — автостоп (2/2) НЕ срабатывает преждевременно
        monitor.evaluateStop(run, null, st, List.of(s1), s1, true, 25, 30, 60, List.of(), breach,
                t0.plusSeconds(25), t0.plusSeconds(40), null, 1);
        monitor.evaluateStop(run, null, st, List.of(s1), s1, true, 34, 30, 60, List.of(), breach,
                t0.plusSeconds(34), t0.plusSeconds(49), null, 1);
        verify(orchestrator, never()).stop(anyLong(), anyString());

        // Ровно через 15 с (t0+50) выполняется 2-я проверка -> автостоп срабатывает
        monitor.evaluateStop(run, null, st, List.of(s1), s1, true, 35, 30, 60, List.of(), breach,
                t0.plusSeconds(35), t0.plusSeconds(50), null, 1);
        verify(orchestrator, times(1)).stop(eq(79L), anyString());
    }
}
