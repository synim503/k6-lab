package ru.k6lab.server;

import org.junit.jupiter.api.Test;
import ru.k6lab.server.result.ChartRenderer;
import ru.k6lab.server.result.ReportFormat;
import ru.k6lab.server.result.ResultData;
import ru.k6lab.server.result.RunCompareService;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ResultUnitTest {

    @Test
    void worseningThresholdIsTenPercentAndDirectionDependsOnMetric() {
        // время/ошибки: рост = хуже
        assertThat(RunCompareService.delta(100.0, 111.0, true).worse()).isTrue();
        assertThat(RunCompareService.delta(100.0, 109.0, true).worse()).isFalse();
        assertThat(RunCompareService.delta(100.0, 80.0, true).worse()).isFalse();
        // интенсивность: падение = хуже
        assertThat(RunCompareService.delta(100.0, 85.0, false).worse()).isTrue();
        assertThat(RunCompareService.delta(100.0, 120.0, false).worse()).isFalse();
        assertThat(RunCompareService.delta(100.0, 111.0, true).deltaPct()).isCloseTo(11.0, org.assertj.core.data.Offset.offset(1e-9));
    }

    @Test
    void zeroBaselineAndMissingValuesAreHandled() {
        assertThat(RunCompareService.delta(0.0, 0.0, true).deltaPct()).isZero();
        assertThat(RunCompareService.delta(0.0, 0.0, true).worse()).isFalse();
        // ошибок не было, а теперь есть - это ухудшение, хоть процент и не определён
        var d = RunCompareService.delta(0.0, 2.0, true);
        assertThat(d.deltaPct()).isNull();
        assertThat(d.worse()).isTrue();
        assertThat(RunCompareService.delta(null, 5.0, true).worse()).isFalse();
        assertThat(RunCompareService.delta(5.0, null, false).deltaPct()).isNull();
    }

    @Test
    void formatterShowsDashForMissingValues() {
        ReportFormat f = new ReportFormat();
        assertThat(f.n(null)).isEqualTo("—");
        assertThat(f.n(Double.NaN, 2)).isEqualTo("—");
        assertThat(f.n(12.3456, 2)).isEqualTo("12.35");
        assertThat(f.dt(null)).isEqualTo("—");
        assertThat(f.status("FAILED")).isEqualTo("неуспешна");
    }

    @Test
    void chartsAreRenderedAsPngDataUris() {
        Instant t = Instant.now();
        List<ResultData.StepRow> steps = List.of(
                step(1, 100, 120.0, 400.0), step(2, 110, 180.0, 400.0), step(3, 120, 700.0, 400.0));
        ResultData data = new ResultData(steps, null, true, null, t, List.of());
        List<ChartRenderer.Img> imgs = ChartRenderer.charts(data);
        // латентность операции + план + ошибки
        assertThat(imgs).hasSize(3);
        assertThat(imgs).allMatch(i -> i.dataUri().startsWith("data:image/png;base64,") && i.dataUri().length() > 2000);
    }

    private static ResultData.StepRow step(int no, double level, double p95, double sla) {
        var op = new ResultData.OpRow("work", 95, sla, 1.0, 10 * level / 100, 9.9 * level / 100, p95 / 2, p95 * 0.8, p95, p95 * 1.2,
                0.1, 0.0, -1.0, p95 <= sla, false);
        return new ResultData.StepRow(no, "s" + no, level, p95 <= sla ? "SUCCESS" : "FAILED", Instant.now(), Instant.now(),
                Instant.now(), Instant.now(), true, 30.0, false, List.of(), List.of(op), List.of());
    }
}
