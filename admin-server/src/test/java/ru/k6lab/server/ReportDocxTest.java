package ru.k6lab.server;

import org.junit.jupiter.api.Test;
import ru.k6lab.server.result.ChartRenderer;
import ru.k6lab.server.result.DocxBuilder;
import ru.k6lab.server.result.RunSeriesService;

import java.io.ByteArrayInputStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipInputStream;

import static org.assertj.core.api.Assertions.assertThat;

/** Графики отчёта в стиле Grafana и docx без внешних библиотек. */
class ReportDocxTest {

    @Test
    void customChartIsRenderedAsPngAndPutIntoAValidDocx() throws Exception {
        Map<String, List<RunSeriesService.Pt>> series = new LinkedHashMap<>();
        series.put("instance=app-1", List.of(new RunSeriesService.Pt(0.5, 10), new RunSeriesService.Pt(1, 40), new RunSeriesService.Pt(1.5, 25)));
        series.put("instance=app-2", List.of(new RunSeriesService.Pt(0.5, 5), new RunSeriesService.Pt(1, 8), new RunSeriesService.Pt(1.5, 9)));
        ChartRenderer.Img img = ChartRenderer.custom("CPU приложения", "%", series);
        assertThat(img).isNotNull();
        byte[] png = img.png();
        assertThat(png[1]).isEqualTo((byte) 'P');
        assertThat(ChartRenderer.custom("пусто", "", Map.of())).isNull();

        byte[] docx = new DocxBuilder().title("Отчёт: Сценарий - Профиль").h1("Итог").text("текст <b> & ок")
                .table(List.of("A", "B"), List.of(List.of("1", "2"), List.of("3", "4")), Set.of(1)).image(png, "CPU").build();
        java.util.Set<String> names = new java.util.HashSet<>();
        String document = null;
        try (ZipInputStream z = new ZipInputStream(new ByteArrayInputStream(docx))) {
            for (var e = z.getNextEntry(); e != null; e = z.getNextEntry()) {
                names.add(e.getName());
                if (e.getName().equals("word/document.xml")) {
                    document = new String(z.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                }
            }
        }
        assertThat(names).contains("[Content_Types].xml", "word/document.xml", "word/styles.xml", "word/media/img1.png", "word/_rels/document.xml.rels");
        assertThat(document).contains("Сценарий - Профиль").contains("&lt;b&gt; &amp; ок").contains("rIdImg1");
        // документ - корректный XML
        javax.xml.parsers.DocumentBuilderFactory.newInstance().newDocumentBuilder()
                .parse(new ByteArrayInputStream(document.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    }

    @Test
    void grafanaDashboardBecomesChartsWithQueriesLegendsUnitsAndVariables() {
        String json = """
                {"dashboard": {"title": "k6 + SUT", "templating": {"list": [
                  {"name": "testid", "current": {"value": "abc"}},
                  {"name": "inst", "current": {"value": ["$__all"]}},
                  {"name": "job", "current": {"value": "app"}}]},
                 "panels": [
                  {"type": "row", "title": "Свёрнутая", "panels": [
                     {"type": "timeseries", "title": "RPS", "fieldConfig": {"defaults": {"unit": "reqps"}},
                      "targets": [{"expr": "sum(rate(k6_http_reqs_total{testid=\\"$testid\\"}[$__rate_interval]))", "legendFormat": "rps"}]}]},
                  {"type": "timeseries", "title": "CPU", "fieldConfig": {"defaults": {"unit": "percent"}},
                   "targets": [
                     {"refId": "A", "expr": "100 - avg by (instance) (rate(node_cpu_seconds_total{mode=\\"idle\\",instance=~\\"${inst}\\",job=\\"$job\\"}[$__interval])) * 100", "legendFormat": "{{instance}}"},
                     {"refId": "B", "expr": "up", "hide": true},
                     {"refId": "C", "expr": "avg(node_load1)", "legendFormat": "__auto"}]},
                  {"type": "timeseries", "title": "CPU", "targets": [{"expr": "node_load5"}]},
                  {"type": "logs", "title": "Логи", "targets": [{"expr": "{app=\\"x\\"}", "datasource": {"type": "loki"}}]},
                  {"type": "timeseries", "title": "Из Loki", "targets": [{"expr": "rate({app=\\"x\\"}[1m])", "datasource": {"type": "loki"}}]},
                  {"type": "text", "title": "Заметка"}]}}
                """;
        var res = new ru.k6lab.server.result.GrafanaDashboardImport().parse(json);
        org.assertj.core.api.Assertions.assertThat(res.dashboardTitle()).isEqualTo("k6 + SUT");
        org.assertj.core.api.Assertions.assertThat(res.panels()).extracting(ru.k6lab.server.result.GrafanaDashboardImport.Panel::name)
                .containsExactly("RPS", "CPU", "CPU (2)");
        var rps = res.panels().get(0);
        org.assertj.core.api.Assertions.assertThat(rps.unit()).isEqualTo("оп/с");
        // переменные остаются в запросе как $имя, а их значения возвращаются отдельно; интервалы заменены на {range}
        org.assertj.core.api.Assertions.assertThat(rps.queries().get(0).promql())
                .isEqualTo("sum(rate(k6_http_reqs_total{testid=\"$testid\"}[{range}]))");
        org.assertj.core.api.Assertions.assertThat(res.variables()).containsEntry("testid", "{run_id}").containsEntry("inst", ".*")
                .containsEntry("job", "app").hasSize(3);
        var cpu = res.panels().get(1);
        org.assertj.core.api.Assertions.assertThat(cpu.unit()).isEqualTo("%");
        org.assertj.core.api.Assertions.assertThat(cpu.queries()).hasSize(2);     // скрытый запрос B не берётся
        org.assertj.core.api.Assertions.assertThat(cpu.queries().get(0).promql())
                .contains("instance=~\"${inst}\"").contains("job=\"$job\"").contains("[{range}]");
        org.assertj.core.api.Assertions.assertThat(cpu.queries().get(0).legend()).isEqualTo("{{instance}}");
        org.assertj.core.api.Assertions.assertThat(cpu.queries().get(1).legend()).isEmpty();   // __auto
        // график не из Prometheus пропущен с причиной; логи и текстовые панели - молча (это не графики)
        org.assertj.core.api.Assertions.assertThat(res.skipped()).hasSize(1).allMatch(s -> s.contains("Из Loki") && s.contains("не к Prometheus"));
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> new ru.k6lab.server.result.GrafanaDashboardImport().parse("{\"a\": 1}"))
                .hasMessageContaining("не дашборд");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> new ru.k6lab.server.result.GrafanaDashboardImport().parse("не json"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void chartVariablesAreSubstitutedIntoQueries() throws Exception {
        var m = ru.k6lab.server.result.CustomChartService.class.getDeclaredMethod("expand", String.class, long.class, long.class, Map.class);
        m.setAccessible(true);
        String q = "rate(x{testid=\"$testid\",i=~\"${inst}\",j=\"$job\",k=\"$unknown\"}[$__rate_interval]) + y{a=\"[[job]]\"} and z{r=\"foo$\"}";
        String out = (String) m.invoke(null, q, 42L, 45L, Map.of("testid", "{run_id}", "inst", "a|b", "job", "app"));
        org.assertj.core.api.Assertions.assertThat(out).isEqualTo(
                "rate(x{testid=\"42\",i=~\"a|b\",j=\"app\",k=\"$unknown\"}[45s]) + y{a=\"app\"} and z{r=\"foo$\"}");
        org.assertj.core.api.Assertions.assertThat((String) m.invoke(null, "up{id=\"{run_id}\"}[{range}]", 7L, 30L, Map.of())).isEqualTo("up{id=\"7\"}[30s]");
    }

    private static Map<String, List<RunSeriesService.Pt>> manySeries(int count) {
        Map<String, List<RunSeriesService.Pt>> m = new LinkedHashMap<>();
        for (int i = 1; i <= count; i++) {
            m.put("host-" + i + ".example:9100", List.of(new RunSeriesService.Pt(0.5, i), new RunSeriesService.Pt(1, i * 2), new RunSeriesService.Pt(1.5, i)));
        }
        return m;
    }

    private static java.awt.image.BufferedImage render(ru.k6lab.server.result.ChartStyle st, int series) throws Exception {
        var img = ChartRenderer.with(st, () -> ChartRenderer.custom("График", "%", manySeries(series)));
        return javax.imageio.ImageIO.read(new ByteArrayInputStream(img.png()));
    }

    @Test
    void chartGrowsWithLegendInsteadOfSqueezingThePlotAndFollowsTheStyle() throws Exception {
        var d = ru.k6lab.server.result.ChartStyle.DEFAULT;
        var few = render(d, 2);
        var sixteen = render(d, 16);
        // образцы для просмотра глазами: target/chart-samples
        java.nio.file.Path dir = java.nio.file.Path.of("target", "chart-samples");
        java.nio.file.Files.createDirectories(dir);
        javax.imageio.ImageIO.write(sixteen, "png", dir.resolve("dark-16.png").toFile());
        javax.imageio.ImageIO.write(render(ru.k6lab.server.result.ChartStyle.of("LIGHT", 1000, 320, "BOTTOM", true, 20), 16), "png", dir.resolve("light-16.png").toFile());
        javax.imageio.ImageIO.write(render(ru.k6lab.server.result.ChartStyle.of("DARK", 1000, 320, "RIGHT", true, 20), 16), "png", dir.resolve("right-16.png").toFile());
        // 16 рядов: легенда добавила высоту, область графика не сжалась (раньше легенда съедала весь график)
        org.assertj.core.api.Assertions.assertThat(sixteen.getWidth()).isEqualTo(d.width());
        org.assertj.core.api.Assertions.assertThat(sixteen.getHeight()).isGreaterThanOrEqualTo(d.height() + 16 * 17);
        org.assertj.core.api.Assertions.assertThat(sixteen.getHeight()).isGreaterThan(few.getHeight());
        // лимит легенды: из 40 рядов в легенде не больше 10, и график не вырастает на все 40 строк
        var capped = render(ru.k6lab.server.result.ChartStyle.of("DARK", 1000, 320, "BOTTOM", true, 10), 40);
        org.assertj.core.api.Assertions.assertThat(capped.getHeight()).isLessThan(d.height() + 40 * 17);
        org.assertj.core.api.Assertions.assertThat(capped.getHeight()).isGreaterThanOrEqualTo(d.height() + 10 * 17);
        // без легенды - высота ровно заданная; справа - шире; размеры берутся из настроек
        var none = render(ru.k6lab.server.result.ChartStyle.of("DARK", 900, 300, "NONE", true, 20), 16);
        org.assertj.core.api.Assertions.assertThat(none.getWidth()).isEqualTo(900);
        org.assertj.core.api.Assertions.assertThat(none.getHeight()).isEqualTo(300);
        // слева: легенда рисуется отдельной панелью, ширина больше, фон у левого края - фон темы, а на уровне первой строки есть цветная линия
        var left = render(ru.k6lab.server.result.ChartStyle.of("DARK", 1000, 320, "LEFT", true, 20), 16);
        org.assertj.core.api.Assertions.assertThat(left.getWidth()).isGreaterThan(1000 + 200);
        org.assertj.core.api.Assertions.assertThat(left.getRGB(1, 1) & 0xFFFFFF).isEqualTo(0x181B1F);
        boolean swatch = false;
        for (int x = 12; x < 40 && !swatch; x++) {
            for (int y = 10; y < 30; y++) {
                if ((left.getRGB(x, y) & 0xFFFFFF) != 0x181B1F) {
                    swatch = true;
                    break;
                }
            }
        }
        org.assertj.core.api.Assertions.assertThat(swatch).as("образец линии первой строки легенды").isTrue();
        javax.imageio.ImageIO.write(left, "png", java.nio.file.Path.of("target", "chart-samples", "left-16.png").toFile());
        var right = render(ru.k6lab.server.result.ChartStyle.of("DARK", 1000, 320, "RIGHT", true, 20), 16);
        org.assertj.core.api.Assertions.assertThat(right.getWidth()).isGreaterThan(1000);
        // тема: фон светлый или тёмный
        int dark = render(d, 3).getRGB(2, 2) & 0xFFFFFF;
        int light = render(ru.k6lab.server.result.ChartStyle.of("LIGHT", 1000, 320, "BOTTOM", true, 20), 3).getRGB(2, 2) & 0xFFFFFF;
        org.assertj.core.api.Assertions.assertThat(dark).isEqualTo(0x181B1F);
        org.assertj.core.api.Assertions.assertThat(light).isEqualTo(0xFFFFFF);
        // границы настроек: слишком большие и маленькие значения приводятся к допустимым, мусор - к значениям по умолчанию
        var clamped = ru.k6lab.server.result.ChartStyle.of("???", 99999, 5, "???", false, 0);
        org.assertj.core.api.Assertions.assertThat(clamped.width()).isEqualTo(ru.k6lab.server.result.ChartStyle.MAX_WIDTH);
        org.assertj.core.api.Assertions.assertThat(clamped.height()).isEqualTo(ru.k6lab.server.result.ChartStyle.MIN_HEIGHT);
        org.assertj.core.api.Assertions.assertThat(clamped.maxLegend()).isEqualTo(1);
        org.assertj.core.api.Assertions.assertThat(clamped.theme()).isEqualTo(d.theme());
        org.assertj.core.api.Assertions.assertThat(clamped.legend()).isEqualTo(d.legend());
    }

    @Test
    void autoWidthExpandsChartWhenLegendDoesNotFit() throws Exception {
        Map<String, List<RunSeriesService.Pt>> longNames = new LinkedHashMap<>();
        longNames.put("instance=very-long-production-application-server-node-01.eu-central.internal.example.com:9100, job=node_exporter",
                List.of(new RunSeriesService.Pt(0.5, 42), new RunSeriesService.Pt(1.0, 58), new RunSeriesService.Pt(1.5, 51)));

        // снизу: при autoWidth=false ширина фиксирована (600), при autoWidth=true расширяется под длинную легенду
        var bFixed = javax.imageio.ImageIO.read(new ByteArrayInputStream(
                ChartRenderer.with(ru.k6lab.server.result.ChartStyle.of("DARK", 600, 300, "BOTTOM", true, 20, false),
                        () -> ChartRenderer.custom("CPU", "%", longNames)).png()));
        var bAuto = javax.imageio.ImageIO.read(new ByteArrayInputStream(
                ChartRenderer.with(ru.k6lab.server.result.ChartStyle.of("DARK", 600, 300, "BOTTOM", true, 20, true),
                        () -> ChartRenderer.custom("CPU", "%", longNames)).png()));
        assertThat(bFixed.getWidth()).isEqualTo(600);
        assertThat(bAuto.getWidth()).isGreaterThan(750);

        // справа и слева: autoWidth даёт больше ширины под длинную легенду, чем фиксированный предел
        var rFixed = javax.imageio.ImageIO.read(new ByteArrayInputStream(
                ChartRenderer.with(ru.k6lab.server.result.ChartStyle.of("DARK", 800, 300, "RIGHT", true, 20, false),
                        () -> ChartRenderer.custom("CPU", "%", longNames)).png()));
        var rAuto = javax.imageio.ImageIO.read(new ByteArrayInputStream(
                ChartRenderer.with(ru.k6lab.server.result.ChartStyle.of("DARK", 800, 300, "RIGHT", true, 20, true),
                        () -> ChartRenderer.custom("CPU", "%", longNames)).png()));
        assertThat(rFixed.getWidth()).isGreaterThan(800).isLessThanOrEqualTo(800 + 600);
        assertThat(rAuto.getWidth()).isGreaterThan(rFixed.getWidth());

        var lFixed = javax.imageio.ImageIO.read(new ByteArrayInputStream(
                ChartRenderer.with(ru.k6lab.server.result.ChartStyle.of("DARK", 800, 300, "LEFT", true, 20, false),
                        () -> ChartRenderer.custom("CPU", "%", longNames)).png()));
        var lAuto = javax.imageio.ImageIO.read(new ByteArrayInputStream(
                ChartRenderer.with(ru.k6lab.server.result.ChartStyle.of("DARK", 800, 300, "LEFT", true, 20, true),
                        () -> ChartRenderer.custom("CPU", "%", longNames)).png()));
        assertThat(lAuto.getWidth()).isGreaterThan(lFixed.getWidth());
    }

    @Test
    void bigByteValuesAreScaledToKilobytesMegabytesAndGigabytes() {
        Map<String, List<RunSeriesService.Pt>> traffic = new LinkedHashMap<>();
        traffic.put("ens192 - Receive", List.of(new RunSeriesService.Pt(1, 196_924_819), new RunSeriesService.Pt(2, 340_116_260)));
        traffic.put("lo", List.of(new RunSeriesService.Pt(1, 0), new RunSeriesService.Pt(2, 5)));
        var mb = ChartRenderer.scaleUnits("Б", traffic);
        org.assertj.core.api.Assertions.assertThat(mb.unit()).isEqualTo("МБ");
        org.assertj.core.api.Assertions.assertThat(mb.series().get("ens192 - Receive").get(1).v()).isCloseTo(324.36, org.assertj.core.api.Assertions.within(0.01));
        org.assertj.core.api.Assertions.assertThat(mb.series().get("lo").get(1).v()).isCloseTo(5 / 1048576.0, org.assertj.core.api.Assertions.within(1e-9));
        // скорость, гигабайты и килобайты; малые значения и другие единицы не трогаются
        org.assertj.core.api.Assertions.assertThat(ChartRenderer.scaleUnits("Б/с", Map.of("x", List.of(new RunSeriesService.Pt(1, 5_000_000))))
                .unit()).isEqualTo("МБ/с");
        org.assertj.core.api.Assertions.assertThat(ChartRenderer.scaleUnits("B", Map.of("x", List.of(new RunSeriesService.Pt(1, 3.5e9)))).unit()).isEqualTo("ГБ");
        org.assertj.core.api.Assertions.assertThat(ChartRenderer.scaleUnits("Б", Map.of("x", List.of(new RunSeriesService.Pt(1, 5000)))).unit()).isEqualTo("КБ");
        org.assertj.core.api.Assertions.assertThat(ChartRenderer.scaleUnits("Б", Map.of("x", List.of(new RunSeriesService.Pt(1, 900)))).unit()).isEqualTo("Б");
        org.assertj.core.api.Assertions.assertThat(ChartRenderer.scaleUnits("%", Map.of("x", List.of(new RunSeriesService.Pt(1, 5e9)))).unit()).isEqualTo("%");
        org.assertj.core.api.Assertions.assertThat(ChartRenderer.scaleUnits("бит", Map.of("x", List.of(new RunSeriesService.Pt(1, 5e9)))).unit()).isEqualTo("бит");
    }

    @Test
    void absoluteTimeAxisIsDrawnAndItsFormatDependsOnDurationAndDate() throws Exception {
        var zone = java.time.ZoneId.systemDefault();
        long noon = java.time.LocalDate.of(2026, 11, 20).atTime(12, 0).atZone(zone).toInstant().toEpochMilli();
        var m = ChartRenderer.class.getDeclaredMethod("datePattern", ChartRenderer.TimeBase.class);
        m.setAccessible(true);
        org.assertj.core.api.Assertions.assertThat((String) m.invoke(null, new ChartRenderer.TimeBase(noon, noon + 5 * 60_000L))).isEqualTo("HH:mm:ss");
        org.assertj.core.api.Assertions.assertThat((String) m.invoke(null, new ChartRenderer.TimeBase(noon, noon + 60 * 60_000L))).isEqualTo("HH:mm");
        org.assertj.core.api.Assertions.assertThat((String) m.invoke(null, new ChartRenderer.TimeBase(noon, noon + 14 * 3600_000L))).isEqualTo("dd.MM HH:mm");
        // график с осью времени строится (ряды, пороговая линия), размер соответствует стилю
        var img = ChartRenderer.withTime(new ChartRenderer.TimeBase(noon, noon + 3 * 60_000L), () -> ChartRenderer.with(
                ru.k6lab.server.result.ChartStyle.DEFAULT, () -> ChartRenderer.custom("График", "%", manySeries(3))));
        org.assertj.core.api.Assertions.assertThat(img).isNotNull();
        var png = javax.imageio.ImageIO.read(new ByteArrayInputStream(img.png()));
        org.assertj.core.api.Assertions.assertThat(png.getWidth()).isEqualTo(ru.k6lab.server.result.ChartStyle.DEFAULT.width());
        javax.imageio.ImageIO.write(png, "png", java.nio.file.Path.of("target", "chart-samples", "abs-time.png").toFile());
    }

    @Test
    void chartTitleShowsVariableValues() throws Exception {
        var m = ru.k6lab.server.result.CustomChartService.class.getDeclaredMethod("title", String.class, Map.class);
        m.setAccessible(true);
        org.assertj.core.api.Assertions.assertThat((String) m.invoke(null, "Утилизация cpu job $job, ${inst}, id $testid",
                Map.of("job", "abyss", "inst", ".*", "testid", "{run_id}"))).isEqualTo("Утилизация cpu job abyss, все, id $testid");
    }

    @Test
    void legendNamesOfSeveralQueries() {
        // подпись задана и ряд один - она; рядов несколько - подпись и метки; подписи нет - метки; {{метка}} подставляется как в Grafana
        var one = Map.of("instance", "a");
        org.assertj.core.api.Assertions.assertThat(callName("память", Map.of(), "значение", 1)).isEqualTo("память");
        org.assertj.core.api.Assertions.assertThat(callName("CPU", one, "instance=a", 2)).isEqualTo("CPU instance=a");
        org.assertj.core.api.Assertions.assertThat(callName("", one, "instance=a", 2)).isEqualTo("instance=a");
        org.assertj.core.api.Assertions.assertThat(callName("{{instance}} / {{ job }}", Map.of("instance", "a", "job", "k6"), "x", 3)).isEqualTo("a / k6");
        org.assertj.core.api.Assertions.assertThat(callName("{{nope}}", one, "instance=a", 2)).isEqualTo("instance=a");
    }

    private static String callName(String legend, Map<String, String> labels, String text, int n) {
        try {
            var m = ru.k6lab.server.result.CustomChartService.class.getDeclaredMethod("seriesName", String.class, Map.class, String.class, int.class);
            m.setAccessible(true);
            return (String) m.invoke(null, legend, labels, text, n);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void reportConfigCustomTitleSectionsAndExcludedCharts() {
        var run = new ru.k6lab.server.run.Run();
        run.setScriptName("Платежи");
        run.setRequest(new LinkedHashMap<>(Map.of("loadProfileName", "Поиск макс.")));
        assertThat(ru.k6lab.server.result.ReportService.defaultTitle(run)).isEqualTo("Платежи - Поиск макс.");
        assertThat(ru.k6lab.server.result.ReportService.title(run)).isEqualTo("Платежи - Поиск макс.");

        var cfg = ru.k6lab.server.result.ReportConfig.fromMap(Map.of(
                "customTitle", "  Релиз 2026.11 — Платежи  ",
                "sections", Map.of("env", false, "generators", false, "summary", true),
                "excludedCharts", List.of("CPU приложения", ""),
                "timeAxis", "abs",
                "warmup", "skip",
                "sortBy", "max",
                "sortDir", "asc"
        ));
        run.getRequest().put("reportConfig", cfg.toMap());
        assertThat(ru.k6lab.server.result.ReportService.title(run)).isEqualTo("Релиз 2026.11 — Платежи");
        var parsed = ru.k6lab.server.result.ReportConfig.fromRun(run);
        assertThat(parsed.show("summary")).isTrue();
        assertThat(parsed.show("steps")).isTrue();
        assertThat(parsed.show("env")).isFalse();
        assertThat(parsed.show("generators")).isFalse();
        assertThat(parsed.isChartIncluded("RPS")).isTrue();
        assertThat(parsed.isChartIncluded("CPU приложения")).isFalse();
        assertThat(parsed.defaultAbsTime()).isTrue();
        assertThat(parsed.defaultSkipWarmup()).isTrue();
        assertThat(parsed.defaultSortBy()).isEqualTo(ru.k6lab.server.result.ChartStyle.SortBy.MAX);
        assertThat(parsed.defaultSortDir()).isEqualTo(ru.k6lab.server.result.ChartStyle.SortDir.ASC);
    }
}
