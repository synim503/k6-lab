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
    void tableLegendRendersCleanlyForBottomLeftRightAndBothThemes() throws Exception {
        var op1 = new RunSeriesService.OpSeries("sendLogBatch2100Exec", 95, 5000, 1, line(40, 675), line(4, 800), line(40, 10920), line(40, 2.3));
        var op2 = new RunSeriesService.OpSeries("sendLogTokenizer", 95, 5000, 1, line(40, 0.25), line(4, 2.0), line(40, 1517), line(40, 0.1));
        var op3 = new RunSeriesService.OpSeries("sendLogTokenizerLike", 95, 5000, 1, line(40, 0.30), line(4, 2.0), line(40, 10332), line(40, 0.1));
        var styles = new ChartStyle[]{
                ChartStyle.of("DARK", 1000, 320, "RIGHT", true, 20, true),
                ChartStyle.of("DARK", 1000, 320, "BOTTOM", true, 20, true),
                ChartStyle.of("LIGHT", 1000, 320, "RIGHT", true, 20, true),
                ChartStyle.of("DARK", 1000, 320, "LEFT", true, 20, true)
        };
        String[] names = {"table-right-dark", "table-bottom-dark", "table-right-light", "table-left-dark"};
        for (int i = 0; i < styles.length; i++) {
            var imgs = ChartRenderer.with(styles[i], () -> ChartRenderer.timeCharts(List.of(op1, op2, op3)));
            assertThat(imgs).isNotEmpty();
            byte[] png = imgs.get(0).png();
            var bi = javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(png));
            assertThat(bi).isNotNull();
            if (styles[i].legend() == ChartStyle.Legend.BOTTOM) {
                assertThat(bi.getHeight()).isGreaterThan(styles[i].height());
            } else {
                assertThat(bi.getWidth()).isGreaterThan(styles[i].width());
            }
            java.nio.file.Files.write(java.nio.file.Path.of("target", names[i] + ".png"), png);
        }
    }

    @Test
    void legendSortingByMaxMeanLastAscDescAndReferenceLinesStayAtBottom() throws Exception {
        // Три серии с разными лидерами:
        // "spike" -> Max=100, Mean=30, Last=5
        // "steady" -> Max=60, Mean=55, Last=50
        // "ramp" -> Max=80, Mean=45, Last=80
        var series = new java.util.LinkedHashMap<String, List<RunSeriesService.Pt>>();
        series.put("spike", List.of(new RunSeriesService.Pt(0, 10), new RunSeriesService.Pt(1, 100), new RunSeriesService.Pt(2, 5), new RunSeriesService.Pt(3, 5)));
        series.put("steady", List.of(new RunSeriesService.Pt(0, 55), new RunSeriesService.Pt(1, 60), new RunSeriesService.Pt(2, 55), new RunSeriesService.Pt(3, 50)));
        series.put("ramp", List.of(new RunSeriesService.Pt(0, 10), new RunSeriesService.Pt(1, 30), new RunSeriesService.Pt(2, 60), new RunSeriesService.Pt(3, 80)));

        // NONE: исходный порядок
        assertThat(ChartRenderer.legendOrder(series, ChartStyle.of("DARK", 1000, 320, "BOTTOM", true, 20, false, "NONE", "DESC")))
                .containsExactly("spike", "steady", "ramp");

        // MAX DESC / ASC
        assertThat(ChartRenderer.legendOrder(series, ChartStyle.of("DARK", 1000, 320, "BOTTOM", true, 20, false, "MAX", "DESC")))
                .containsExactly("spike", "ramp", "steady");
        assertThat(ChartRenderer.legendOrder(series, ChartStyle.of("DARK", 1000, 320, "BOTTOM", true, 20, false, "MAX", "ASC")))
                .containsExactly("steady", "ramp", "spike");

        // MEAN DESC / ASC
        assertThat(ChartRenderer.legendOrder(series, ChartStyle.of("DARK", 1000, 320, "BOTTOM", true, 20, false, "MEAN", "DESC")))
                .containsExactly("steady", "ramp", "spike");
        assertThat(ChartRenderer.legendOrder(series, ChartStyle.of("DARK", 1000, 320, "BOTTOM", true, 20, false, "MEAN", "ASC")))
                .containsExactly("spike", "ramp", "steady");

        // LAST DESC / ASC (работает и при скрытой статистике stats=false)
        assertThat(ChartRenderer.legendOrder(series, ChartStyle.of("DARK", 1000, 320, "BOTTOM", true, 20, false, "LAST", "DESC")))
                .containsExactly("ramp", "steady", "spike");
        assertThat(ChartRenderer.legendOrder(series, ChartStyle.of("DARK", 1000, 320, "BOTTOM", false, 20, false, "LAST", "ASC")))
                .containsExactly("spike", "steady", "ramp");

        // Ограничение maxLegend=2 учитывает выбранную сортировку
        assertThat(ChartRenderer.legendOrder(series, ChartStyle.of("DARK", 1000, 320, "BOTTOM", true, 2, false, "MEAN", "DESC")))
                .containsExactly("steady", "ramp");
        assertThat(ChartRenderer.legendOrder(series, ChartStyle.of("DARK", 1000, 320, "BOTTOM", true, 2, false, "LAST", "ASC")))
                .containsExactly("spike", "steady");

        // Опорные линии (без статистики) остаются внизу при любой сортировке
        var rows = List.of(
                new ChartRenderer.LegendRow(null, "SLA", null, null, null, Double.NaN, Double.NaN, Double.NaN),
                new ChartRenderer.LegendRow(null, "low", "10", "10", "10", 10, 10, 10),
                new ChartRenderer.LegendRow(null, "high", "90", "90", "90", 90, 90, 90),
                new ChartRenderer.LegendRow(null, "Прогрев", null, null, null, Double.NaN, Double.NaN, Double.NaN));
        assertThat(ChartRenderer.sortAndCapLegendRows(rows, ChartStyle.of("DARK", 1000, 320, "BOTTOM", true, 20, false, "MAX", "DESC"))
                .stream().map(ChartRenderer.LegendRow::label).toList())
                .containsExactly("high", "low", "SLA", "Прогрев");
        assertThat(ChartRenderer.sortAndCapLegendRows(rows, ChartStyle.of("DARK", 1000, 320, "BOTTOM", true, 20, false, "MAX", "ASC"))
                .stream().map(ChartRenderer.LegendRow::label).toList())
                .containsExactly("low", "high", "SLA", "Прогрев");

        // Полный рендер PNG со стрелками сортировки в заголовках таблицы (MAX DESC и MEAN ASC)
        for (var st : new ChartStyle[]{
                ChartStyle.of("DARK", 1000, 320, "RIGHT", true, 20, true, "MAX", "DESC"),
                ChartStyle.of("LIGHT", 1000, 320, "BOTTOM", true, 20, false, "MEAN", "ASC")}) {
            var img = ChartRenderer.with(st, () -> ChartRenderer.custom("CPU", "%", series));
            assertThat(img).isNotNull();
            var bi = javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(img.png()));
            assertThat(bi).isNotNull();
            assertThat(bi.getWidth()).isGreaterThanOrEqualTo(st.width());
        }
    }

    @Test
    void trimWarmupCutsWarmupSegmentInterpolatesPlanAndKeepsFallbackWhenRunEndsEarly() {
        var actual = List.of(
                new RunSeriesService.Pt(0.5, 500),   // всплеск на прогреве
                new RunSeriesService.Pt(1.5, 400),
                new RunSeriesService.Pt(2.5, 100),   // конец прогрева
                new RunSeriesService.Pt(5.0, 105),
                new RunSeriesService.Pt(8.0, 110));
        var plan = List.of(
                new RunSeriesService.Pt(0.0, 0),
                new RunSeriesService.Pt(5.0, 100),
                new RunSeriesService.Pt(10.0, 100));
        var op = new RunSeriesService.OpSeries("pay", 95, 500, 1, actual, plan, actual, List.of(), 2.5);

        List<RunSeriesService.OpSeries> trimmed = ChartRenderer.trimWarmup(List.of(op));
        assertThat(trimmed).hasSize(1);
        var t = trimmed.get(0);
        assertThat(t.warmupMin()).isEqualTo(0.0);
        assertThat(t.actual()).extracting(RunSeriesService.Pt::min).containsExactly(2.5, 5.0, 8.0);
        // плановая линия линейно интерполирована ровно в точке warmupMin=2.5 (50.0)
        assertThat(t.plan()).extracting(RunSeriesService.Pt::min).containsExactly(2.5, 5.0, 10.0);
        assertThat(t.plan().get(0).v()).isEqualTo(50.0);

        // рендер с skipWarmup=true и false проходит успешно и даёт валидные PNG
        assertThat(ChartRenderer.timeCharts(List.of(op), false)).hasSize(2);
        assertThat(ChartRenderer.timeCharts(List.of(op), true)).hasSize(2);

        // fallback: если тест оборвался до окончания прогрева, исходные точки не пропадают
        var early = new RunSeriesService.OpSeries("pay", 95, 500, 1,
                List.of(new RunSeriesService.Pt(0.5, 10), new RunSeriesService.Pt(1.0, 20)),
                plan, List.of(), List.of(), 5.0);
        var earlyTrimmed = ChartRenderer.trimWarmup(List.of(early));
        assertThat(earlyTrimmed.get(0).actual()).hasSize(2);
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
