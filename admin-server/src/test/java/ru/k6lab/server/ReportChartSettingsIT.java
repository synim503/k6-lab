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
                    .param("legend", "RIGHT").param("maxLegend", "12").param("timeAxis", "abs")).andExpect(status().is3xxRedirection());
            assertThat(styles.absoluteTime()).isTrue();
            ChartStyle s = styles.get();
            assertThat(s.theme()).isEqualTo(ChartStyle.Theme.LIGHT);
            assertThat(s.width()).isEqualTo(1200);
            assertThat(s.height()).isEqualTo(400);
            assertThat(s.legend()).isEqualTo(ChartStyle.Legend.RIGHT);
            assertThat(s.maxLegend()).isEqualTo(12);
            assertThat(s.stats()).isFalse();   // флажок не отмечен
            mvc.perform(get("/settings")).andExpect(status().isOk()).andExpect(content().string(containsString("Графики отчёта")))
                    .andExpect(content().string(containsString("value=\"1200\"")));
            byte[] png = mvc.perform(get("/settings/report-charts/preview.png")).andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
            assertThat(png[1]).isEqualTo((byte) 'P');
            assertThat(javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(png)).getWidth()).isGreaterThan(1200); // легенда справа
            mvc.perform(post("/settings/report-charts").with(csrf()).param("theme", "DARK").param("width", "99999").param("height", "1")
                    .param("legend", "BOTTOM").param("stats", "true").param("maxLegend", "500")).andExpect(status().is3xxRedirection());
            assertThat(styles.get().width()).isEqualTo(ChartStyle.MAX_WIDTH);
            assertThat(styles.get().height()).isEqualTo(ChartStyle.MIN_HEIGHT);
            assertThat(styles.get().maxLegend()).isEqualTo(ChartStyle.MAX_LEGEND);
            mvc.perform(post("/settings/report-charts/reset").with(csrf())).andExpect(status().is3xxRedirection());
            assertThat(styles.get()).isEqualTo(ChartStyle.DEFAULT);
            assertThat(styles.absoluteTime()).isFalse();
        } finally {
            styles.reset();   // настройка общая для всех тестов
        }
    }
}
