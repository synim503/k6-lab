package ru.k6lab.server.result;

import org.junit.jupiter.api.Test;
import ru.k6lab.core.analysis.StepAnalysis;
import ru.k6lab.server.metrics.PrometheusClient;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Проверка анти-всплесковой оценки кастомных метрик на окне ступени:
 * разовые короткие всплески игнорируются, а устойчивое нарушение >= (consecutive - 1) * 15 с валит ступень.
 */
class StepResultSustainedCheckTest {

    private static final StepAnalysis.CustomCheck CPU_RULE =
            new StepAnalysis.CustomCheck("CPU unix", "cpu_usage", "LTE", 80.0, "%", true, true);

    private static PrometheusClient.Series series(String instance, double... tsValPairs) {
        java.util.List<PrometheusClient.Point> pts = new java.util.ArrayList<>();
        for (int i = 0; i < tsValPairs.length; i += 2) {
            pts.add(new PrometheusClient.Point(tsValPairs[i], tsValPairs[i + 1]));
        }
        return new PrometheusClient.Series(Map.of("instance", instance), pts);
    }

    @Test
    void briefSpikeDoesNotFailStepWhenConsecutiveIsThree() {
        // 1 точка 86.1% и 2 точки (10 с < 30 с) не должны валить ступень при consecutive=3
        var sr = series("storage-05",
                0, 70.0,
                10, 72.0,
                20, 86.1,
                30, 74.0,
                40, 83.0,
                50, 85.0,
                60, 73.0,
                70, 71.0);

        StepResultService.RangeEval ev = StepResultService.evaluateSeriesSustained(List.of(sr), CPU_RULE, 3, 10);
        assertThat(ev.violated()).isFalse();
        assertThat(ev.value()).isEqualTo(74.0);
        assertThat(ev.worstSeries()).isEqualTo("storage-05");
    }

    @Test
    void sustainedBreachFailsStepWhenDurationMeetsConsecutiveThreshold() {
        // 4 точки подряд от t=30 до t=60 (span = 30 с >= (3-1)*15 с) -> устойчивое нарушение
        var sr = series("storage-05",
                0, 72.0,
                10, 75.0,
                20, 78.0,
                30, 82.0,
                40, 85.0,
                50, 86.1,
                60, 84.0,
                70, 77.0);

        StepResultService.RangeEval ev = StepResultService.evaluateSeriesSustained(List.of(sr), CPU_RULE, 3, 10);
        assertThat(ev.violated()).isTrue();
        assertThat(ev.value()).isEqualTo(86.1);
        assertThat(ev.worstSeries()).isEqualTo("storage-05");
    }

    @Test
    void gapBetweenSpikesDoesNotMergeIntoSustainedBreach() {
        // Две одиночные точки с разрывом 60 с (> maxGapSec=30) не склеиваются в одну серию
        var sr = series("storage-05",
                0, 70.0,
                10, 86.0,
                70, 87.0,
                80, 72.0);

        StepResultService.RangeEval ev = StepResultService.evaluateSeriesSustained(List.of(sr), CPU_RULE, 3, 10);
        assertThat(ev.violated()).isFalse();
        assertThat(ev.value()).isEqualTo(72.0);
    }

    @Test
    void shortStepWhereAllPointsViolateStillFails() {
        // Короткое окно 20 с (меньше 45 с при consecutive=4), но 100% точек нарушены -> ступень неуспешна
        var sr = series("storage-05",
                0, 90.0,
                10, 92.5,
                20, 91.0);

        StepResultService.RangeEval ev = StepResultService.evaluateSeriesSustained(List.of(sr), CPU_RULE, 4, 10);
        assertThat(ev.violated()).isTrue();
        assertThat(ev.value()).isEqualTo(92.5);
    }

    @Test
    void consecutiveOneFailsOnSinglePoint() {
        var sr = series("storage-05",
                0, 70.0,
                10, 86.1,
                20, 72.0);

        StepResultService.RangeEval ev = StepResultService.evaluateSeriesSustained(List.of(sr), CPU_RULE, 1, 10);
        assertThat(ev.violated()).isTrue();
        assertThat(ev.value()).isEqualTo(86.1);
    }

    @Test
    void multiServerPicksSustainedBreachOverBriefSpike() {
        var spikeOnly = series("node-spike",
                0, 65.0, 10, 95.0, 20, 66.0, 30, 67.0, 40, 65.0);
        var sustained = series("node-hot",
                0, 78.0, 10, 84.0, 20, 86.5, 30, 88.5, 40, 87.0);

        StepResultService.RangeEval ev = StepResultService.evaluateSeriesSustained(
                List.of(spikeOnly, sustained), CPU_RULE, 3, 10);
        assertThat(ev.violated()).isTrue();
        assertThat(ev.value()).isEqualTo(88.5);
        assertThat(ev.worstSeries()).isEqualTo("node-hot");
    }

    @Test
    void rangePlaceholderRuleEvaluatesOnUnifiedRangeGridWithStopWindow() throws Exception {
        // Правило с {range} (как пресет CPU unix) должно идти через rangeSeries с окном stopWindowSec=60s и шагом 15s,
        // а не усредняться одним instant-запросом за всю длину ступени (150s).
        PrometheusClient prom = org.mockito.Mockito.mock(PrometheusClient.class);
        var promCfg = new ru.k6lab.server.settings.SettingsService.Prometheus(
                "http://prom:9090", null, ru.k6lab.server.settings.SettingsService.AuthType.NONE, null, null);
        java.time.Instant winStart = java.time.Instant.parse("2026-11-26T10:00:00Z");
        java.time.Instant winEnd = winStart.plusSeconds(150);

        var cpuRangeRule = new StepAnalysis.CustomCheck(
                "CPU unix", "100 - avg by (instance) (rate(node_cpu_seconds_total{mode=\"idle\"}[{range}])) * 100",
                "LTE", 80.0, "%", true, true);

        // Начало ступени 70%, в последние 45 с устойчивый рост 84% -> 86% -> 88% -> 89% (4 точки × 15 с = 45 с >= 30 с)
        var lateDegradation = series("storage-05",
                winStart.getEpochSecond(), 70.0,
                winStart.plusSeconds(15).getEpochSecond(), 71.0,
                winStart.plusSeconds(30).getEpochSecond(), 72.0,
                winStart.plusSeconds(45).getEpochSecond(), 73.0,
                winStart.plusSeconds(60).getEpochSecond(), 74.0,
                winStart.plusSeconds(75).getEpochSecond(), 75.0,
                winStart.plusSeconds(90).getEpochSecond(), 78.0,
                winStart.plusSeconds(105).getEpochSecond(), 84.0,
                winStart.plusSeconds(120).getEpochSecond(), 86.0,
                winStart.plusSeconds(135).getEpochSecond(), 88.0,
                winStart.plusSeconds(150).getEpochSecond(), 89.0);

        org.mockito.Mockito.when(prom.rangeSeries(
                        org.mockito.ArgumentMatchers.eq(promCfg),
                        org.mockito.ArgumentMatchers.contains("[60s]"),
                        org.mockito.ArgumentMatchers.eq(winStart),
                        org.mockito.ArgumentMatchers.eq(winEnd),
                        org.mockito.ArgumentMatchers.eq(15)))
                .thenReturn(List.of(lateDegradation));

        StepResultService svc = new StepResultService(null, prom, null, null, null, null, null, null, null);
        List<StepAnalysis.CustomCheckResult> res = svc.evaluateCustomChecks(
                List.of(cpuRangeRule), 1L, winStart, winEnd, 150, Map.of(), promCfg, 3, 60);

        assertThat(res).hasSize(1);
        assertThat(res.get(0).ok()).isFalse();
        assertThat(res.get(0).value()).isEqualTo(89.0);
        assertThat(res.get(0).worstSeries()).isEqualTo("storage-05");
        org.mockito.Mockito.verify(prom, org.mockito.Mockito.never())
                .query(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any());
    }
}
