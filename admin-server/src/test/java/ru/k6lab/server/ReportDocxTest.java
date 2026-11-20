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
}
