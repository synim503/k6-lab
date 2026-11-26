package ru.k6lab.server.result;

import org.springframework.stereotype.Service;
import ru.k6lab.server.settings.SettingsService;

/** Хранение оформления графиков отчёта в настройках сервера. */
@Service
public class ChartStyleService {

    static final String THEME = "report.chart.theme", WIDTH = "report.chart.width", HEIGHT = "report.chart.height",
            LEGEND = "report.chart.legend", STATS = "report.chart.stats", MAX_LEGEND = "report.chart.max_legend",
            TIME_AXIS = "report.chart.time_axis", AUTO_WIDTH = "report.chart.auto_width",
            SORT_BY = "report.chart.sort_by", SORT_DIR = "report.chart.sort_dir",
            SKIP_WARMUP = "report.chart.skip_warmup";

    private final SettingsService settings;

    public ChartStyleService(SettingsService settings) {
        this.settings = settings;
    }

    public ChartStyle get() {
        ChartStyle d = ChartStyle.DEFAULT;
        return ChartStyle.of(settings.get(THEME, d.theme().name()), settings.getInt(WIDTH, d.width()), settings.getInt(HEIGHT, d.height()),
                settings.get(LEGEND, d.legend().name()), !"0".equals(settings.get(STATS, "1")), settings.getInt(MAX_LEGEND, d.maxLegend()),
                "1".equals(settings.get(AUTO_WIDTH, "0")), settings.get(SORT_BY, d.sortBy().name()), settings.get(SORT_DIR, d.sortDir().name()));
    }

    /** Ось времени по умолчанию: true - абсолютное время (часы), false - минуты от начала теста. */
    public boolean absoluteTime() {
        return "abs".equals(settings.get(TIME_AXIS, "rel"));
    }

    /** Прогрев на графиках по умолчанию: true - начинать графики от конца прогрева, false - показывать с начала теста (включая прогрев). */
    public boolean skipWarmup() {
        return "1".equals(settings.get(SKIP_WARMUP, "0"));
    }

    public ChartStyle save(String theme, int width, int height, String legend, boolean stats, int maxLegend, boolean absoluteTime) {
        return save(theme, width, height, legend, stats, maxLegend, absoluteTime, false, null, null, false);
    }

    public ChartStyle save(String theme, int width, int height, String legend, boolean stats, int maxLegend, boolean absoluteTime, boolean autoWidth) {
        return save(theme, width, height, legend, stats, maxLegend, absoluteTime, autoWidth, null, null, false);
    }

    public ChartStyle save(String theme, int width, int height, String legend, boolean stats, int maxLegend, boolean absoluteTime, boolean autoWidth,
                           String sortBy, String sortDir) {
        return save(theme, width, height, legend, stats, maxLegend, absoluteTime, autoWidth, sortBy, sortDir, false);
    }

    @org.springframework.transaction.annotation.Transactional
    public ChartStyle save(String theme, int width, int height, String legend, boolean stats, int maxLegend, boolean absoluteTime, boolean autoWidth,
                           String sortBy, String sortDir, boolean skipWarmup) {
        settings.set(TIME_AXIS, absoluteTime ? "abs" : "rel");
        settings.set(SKIP_WARMUP, skipWarmup ? "1" : "0");
        ChartStyle s = ChartStyle.of(theme, width, height, legend, stats, maxLegend, autoWidth, sortBy, sortDir);
        settings.set(THEME, s.theme().name());
        settings.set(WIDTH, String.valueOf(s.width()));
        settings.set(HEIGHT, String.valueOf(s.height()));
        settings.set(LEGEND, s.legend().name());
        settings.set(STATS, s.stats() ? "1" : "0");
        settings.set(MAX_LEGEND, String.valueOf(s.maxLegend()));
        settings.set(AUTO_WIDTH, s.autoWidth() ? "1" : "0");
        settings.set(SORT_BY, s.sortBy().name());
        settings.set(SORT_DIR, s.sortDir().name());
        return s;
    }

    @org.springframework.transaction.annotation.Transactional
    public void reset() {
        for (String k : new String[]{THEME, WIDTH, HEIGHT, LEGEND, STATS, MAX_LEGEND, TIME_AXIS, AUTO_WIDTH, SORT_BY, SORT_DIR, SKIP_WARMUP}) {
            settings.delete(k);
        }
    }
}
