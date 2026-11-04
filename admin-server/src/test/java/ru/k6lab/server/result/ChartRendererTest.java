package ru.k6lab.server.result;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ChartRendererTest {

    private static List<RunSeriesService.Pt> line(int n, double base) {
        List<RunSeriesService.Pt> out = new ArrayList<>();
        for (int i = 1; i <= n; i++) {
            out.add(new RunSeriesService.Pt(i * 0.25, base + (i % 5)));
        }
        return out;
    }

    @Test
    void timeChartsCoverWholeTestWithManyPoints() {
        var op = new RunSeriesService.OpSeries("pay", 95, 800, 1, line(40, 90), line(4, 100), line(40, 300), line(40, 0.2));
        List<ChartRenderer.Img> imgs = ChartRenderer.timeCharts(List.of(op));
        // интенсивность, время ответа по операции, ошибки
        assertThat(imgs).extracting(ChartRenderer.Img::title).containsExactly("Интенсивность: факт и план", "Время ответа: pay", "Ошибки");
        assertThat(imgs).allMatch(i -> i.dataUri().startsWith("data:image/png;base64,") && i.dataUri().length() > 2000);
        // образцы для ручного просмотра: target/chart-sample-N.png
        try {
            for (int i = 0; i < imgs.size(); i++) {
                java.nio.file.Files.write(java.nio.file.Path.of("target", "chart-sample-" + i + ".png"),
                        java.util.Base64.getDecoder().decode(imgs.get(i).dataUri().substring("data:image/png;base64,".length())));
            }
        } catch (java.io.IOException ignored) {
            // только для ручного просмотра
        }
    }

    @Test
    void emptyOrMissingSeriesGiveNoCharts() {
        assertThat(ChartRenderer.timeCharts(List.of())).isEmpty();
        var empty = new RunSeriesService.OpSeries("x", 95, 800, 1, List.of(), List.of(), List.of(), List.of());
        assertThat(ChartRenderer.timeCharts(List.of(empty))).isEmpty();
    }

    @Test
    void singleStepResultHasNoMeaninglessOnePointStepCharts() {
        var op = new ResultData.OpRow("pay", 95, 800.0, 1.0, 10.0, 9.9, 50.0, 80.0, 90.0, 99.0, 0.1, 0.0, -1.0, true, false);
        var step = new ResultData.StepRow(1, "100%", 100.0, "OK", java.time.Instant.now(), java.time.Instant.now(), java.time.Instant.now(),
                java.time.Instant.now(), true, null, false, List.of(), List.of(op));
        var data = new ResultData(List.of(step), true, null, java.time.Instant.now(), List.of());
        assertThat(ChartRenderer.charts(data)).isEmpty();
    }
}
