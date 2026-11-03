package ru.k6lab.server;

import org.junit.jupiter.api.Test;
import ru.k6lab.server.result.ChartRenderer;
import ru.k6lab.server.result.ReportFormat;
import ru.k6lab.server.result.ResultData;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ResultUnitTest {

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
        ResultData data = new ResultData(steps, true, null, t, List.of());
        List<ChartRenderer.Img> imgs = ChartRenderer.charts(data);
        // латентность операции + план + ошибки
        assertThat(imgs).hasSize(3);
        assertThat(imgs).allMatch(i -> i.dataUri().startsWith("data:image/png;base64,") && i.dataUri().length() > 2000);
    }

    private static ResultData.StepRow step(int no, double level, double p95, double sla) {
        var op = new ResultData.OpRow("work", 95, sla, 1.0, 10 * level / 100, 9.9 * level / 100, p95 / 2, p95 * 0.8, p95, p95 * 1.2,
                0.1, 0.0, -1.0, p95 <= sla, false);
        return new ResultData.StepRow(no, "s" + no, level, p95 <= sla ? "SUCCESS" : "FAILED", Instant.now(), Instant.now(),
                Instant.now(), Instant.now(), true, 30.0, false, List.of(), List.of(op));
    }
}
