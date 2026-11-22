package ru.k6lab.server.result;

/**
 * Оформление графиков отчёта (настраивается в «Настройках»): тема, размеры, положение легенды, статистика в легенде и
 * число рядов в легенде. height - высота самой области графика: легенда снизу добавляется к ней, поэтому график не сжимается,
 * сколько бы рядов в нём ни было.
 */
public record ChartStyle(Theme theme, int width, int height, Legend legend, boolean stats, int maxLegend, boolean autoWidth) {

    public enum Theme { DARK, LIGHT }

    public enum Legend { BOTTOM, RIGHT, LEFT, NONE }

    public static final ChartStyle DEFAULT = new ChartStyle(Theme.DARK, 1400, 320, Legend.BOTTOM, true, 20, false);

    public static final int MIN_WIDTH = 600, MAX_WIDTH = 2000, MIN_HEIGHT = 200, MAX_HEIGHT = 800, MAX_LEGEND = 100;

    /** Значения вне допустимых границ приводятся к ближайшей границе, неизвестные названия - к значению по умолчанию. */
    public static ChartStyle of(String theme, int width, int height, String legend, boolean stats, int maxLegend) {
        return of(theme, width, height, legend, stats, maxLegend, false);
    }

    public static ChartStyle of(String theme, int width, int height, String legend, boolean stats, int maxLegend, boolean autoWidth) {
        return new ChartStyle(parse(Theme.class, theme, DEFAULT.theme), Math.max(MIN_WIDTH, Math.min(MAX_WIDTH, width)),
                Math.max(MIN_HEIGHT, Math.min(MAX_HEIGHT, height)), parse(Legend.class, legend, DEFAULT.legend), stats,
                Math.max(1, Math.min(MAX_LEGEND, maxLegend)), autoWidth);
    }

    private static <E extends Enum<E>> E parse(Class<E> type, String v, E def) {
        if (v == null) {
            return def;
        }
        try {
            return Enum.valueOf(type, v.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return def;
        }
    }
}
