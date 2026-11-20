package ru.k6lab.server.result;

import org.springframework.stereotype.Service;
import ru.k6lab.server.settings.SettingsService;

/** Хранение оформления графиков отчёта в настройках сервера. */
@Service
public class ChartStyleService {

    static final String THEME = "report.chart.theme", WIDTH = "report.chart.width", HEIGHT = "report.chart.height",
            LEGEND = "report.chart.legend", STATS = "report.chart.stats", MAX_LEGEND = "report.chart.max_legend", TIME_AXIS = "report.chart.time_axis";

    private final SettingsService settings;

    public ChartStyleService(SettingsService settings) {
        this.settings = settings;
    }

    public ChartStyle get() {
        ChartStyle d = ChartStyle.DEFAULT;
        return ChartStyle.of(settings.get(THEME, d.theme().name()), settings.getInt(WIDTH, d.width()), settings.getInt(HEIGHT, d.height()),
                settings.get(LEGEND, d.legend().name()), !"0".equals(settings.get(STATS, "1")), settings.getInt(MAX_LEGEND, d.maxLegend()));
    }

    /** Ось времени по умолчанию: true - абсолютное время (часы), false - минуты от начала теста. */
    public boolean absoluteTime() {
        return "abs".equals(settings.get(TIME_AXIS, "rel"));
    }

    public ChartStyle save(String theme, int width, int height, String legend, boolean stats, int maxLegend, boolean absoluteTime) {
        settings.set(TIME_AXIS, absoluteTime ? "abs" : "rel");
        ChartStyle s = ChartStyle.of(theme, width, height, legend, stats, maxLegend);
        settings.set(THEME, s.theme().name());
        settings.set(WIDTH, String.valueOf(s.width()));
        settings.set(HEIGHT, String.valueOf(s.height()));
        settings.set(LEGEND, s.legend().name());
        settings.set(STATS, s.stats() ? "1" : "0");
        settings.set(MAX_LEGEND, String.valueOf(s.maxLegend()));
        return s;
    }

    public void reset() {
        for (String k : new String[]{THEME, WIDTH, HEIGHT, LEGEND, STATS, MAX_LEGEND, TIME_AXIS}) {
            settings.delete(k);
        }
    }
}
