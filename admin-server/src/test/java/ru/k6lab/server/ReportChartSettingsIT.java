package ru.k6lab.server;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import ru.k6lab.server.result.ChartStyle;
import ru.k6lab.server.result.ChartStyleService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Настройки оформления графиков отчёта: сохранение, значения на странице, предпросмотр, сброс. */
@WithMockUser(roles = "ADMIN")
class ReportChartSettingsIT extends AbstractIT {

    @Autowired
    ChartStyleService styles;
    @Autowired
    MockMvc mvc;

    @Test
    void chartStyleIsSavedShownPreviewedAndReset() throws Exception {
        try {
            assertThat(styles.get()).isEqualTo(ChartStyle.DEFAULT);
            mvc.perform(post("/settings/report-charts").with(csrf()).param("theme", "LIGHT").param("width", "1200").param("height", "400")
                    .param("legend", "RIGHT").param("maxLegend", "12").param("timeAxis", "abs").param("autoWidth", "true")
                    .param("sortBy", "MEAN").param("sortDir", "ASC").param("skipWarmup", "true")).andExpect(status().is3xxRedirection());
            assertThat(styles.absoluteTime()).isTrue();
            assertThat(styles.skipWarmup()).isTrue();
            ChartStyle s = styles.get();
            assertThat(s.theme()).isEqualTo(ChartStyle.Theme.LIGHT);
            assertThat(s.width()).isEqualTo(1200);
            assertThat(s.height()).isEqualTo(400);
            assertThat(s.legend()).isEqualTo(ChartStyle.Legend.RIGHT);
            assertThat(s.maxLegend()).isEqualTo(12);
            assertThat(s.stats()).isFalse();   // флажок не отмечен
            assertThat(s.autoWidth()).isTrue();
            assertThat(s.sortBy()).isEqualTo(ChartStyle.SortBy.MEAN);
            assertThat(s.sortDir()).isEqualTo(ChartStyle.SortDir.ASC);
            mvc.perform(get("/settings")).andExpect(status().isOk()).andExpect(content().string(containsString("Графики отчёта")))
                    .andExpect(content().string(containsString("value=\"1200\"")))
                    .andExpect(content().string(containsString("Сортировка легенды")))
                    .andExpect(content().string(containsString("Прогрев на графиках")));
            byte[] png = mvc.perform(get("/settings/report-charts/preview.png")).andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
            assertThat(png[1]).isEqualTo((byte) 'P');
            assertThat(javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(png)).getWidth()).isGreaterThan(1200); // легенда справа
            mvc.perform(post("/settings/report-charts").with(csrf()).param("theme", "DARK").param("width", "99999").param("height", "1")
                    .param("legend", "BOTTOM").param("stats", "true").param("maxLegend", "500")
                    .param("sortBy", "LAST").param("sortDir", "DESC")).andExpect(status().is3xxRedirection());
            assertThat(styles.get().width()).isEqualTo(ChartStyle.MAX_WIDTH);
            assertThat(styles.get().height()).isEqualTo(ChartStyle.MIN_HEIGHT);
            assertThat(styles.get().maxLegend()).isEqualTo(ChartStyle.MAX_LEGEND);
            assertThat(styles.get().sortBy()).isEqualTo(ChartStyle.SortBy.LAST);
            assertThat(styles.get().sortDir()).isEqualTo(ChartStyle.SortDir.DESC);
            assertThat(styles.skipWarmup()).isFalse();
            mvc.perform(post("/settings/report-charts/reset").with(csrf())).andExpect(status().is3xxRedirection());
            assertThat(styles.get()).isEqualTo(ChartStyle.DEFAULT);
            assertThat(styles.get().sortBy()).isEqualTo(ChartStyle.SortBy.NONE);
            assertThat(styles.get().sortDir()).isEqualTo(ChartStyle.SortDir.DESC);
            assertThat(styles.absoluteTime()).isFalse();
            assertThat(styles.skipWarmup()).isFalse();

            // живой предпросмотр по query-параметрам: учитывает несохранённые значения (включая сортировку и прогрев) и не меняет сохранённый стиль
            byte[] livePng = mvc.perform(get("/settings/report-charts/preview.png")
                            .param("theme", "LIGHT").param("width", "900").param("height", "280")
                            .param("legend", "NONE").param("stats", "false").param("timeAxis", "abs")
                            .param("sortBy", "MAX").param("sortDir", "DESC").param("skipWarmup", "true").param("seriesCount", "4"))
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
            var liveImg = javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(livePng));
            assertThat(liveImg.getWidth()).isEqualTo(900);
            assertThat(liveImg.getHeight()).isEqualTo(280);
            assertThat(liveImg.getRGB(2, 2) & 0xFFFFFF).isEqualTo(0xFFFFFF);
            assertThat(styles.get()).isEqualTo(ChartStyle.DEFAULT);
        } finally {
            styles.reset();   // настройка общая для всех тестов
        }
    }
}
