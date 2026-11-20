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
    void legendNamesOfSeveralQueries() {
        // подпись задана и ряд один - она; рядов несколько - подпись и метки; подписи нет - метки
        org.assertj.core.api.Assertions.assertThat(callName("память", "значение", 1)).isEqualTo("память");
        org.assertj.core.api.Assertions.assertThat(callName("CPU", "instance=a", 2)).isEqualTo("CPU instance=a");
        org.assertj.core.api.Assertions.assertThat(callName("", "instance=a", 2)).isEqualTo("instance=a");
    }

    private static String callName(String legend, String labels, int n) {
        try {
            var m = ru.k6lab.server.result.CustomChartService.class.getDeclaredMethod("seriesName", String.class, String.class, int.class);
            m.setAccessible(true);
            return (String) m.invoke(null, legend, labels, n);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }
}
