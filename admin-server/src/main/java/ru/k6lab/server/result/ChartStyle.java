package ru.k6lab.server.result;

/**
 * Оформление графиков отчёта (настраивается в «Настройках»): тема, размеры, положение легенды, статистика в легенде и
 * число рядов в легенде. height - высота самой области графика: легенда снизу добавляется к ней, поэтому график не сжимается,
 * сколько бы рядов в нём ни было.
 */
public record ChartStyle(Theme theme, int width, int height, Legend legend, boolean stats, int maxLegend, boolean autoWidth,
                         SortBy sortBy, SortDir sortDir) {

    public enum Theme { DARK, LIGHT }

    public enum Legend { BOTTOM, RIGHT, LEFT, NONE }

    /** Сортировка строк табличной легенды: без сортировки, по Last, Mean или Max. */
    public enum SortBy { NONE, LAST, MEAN, MAX }

    /** Направление сортировки легенды: по убыванию (DESC) или по возрастанию (ASC). */
    public enum SortDir { DESC, ASC }

    public static final ChartStyle DEFAULT = new ChartStyle(Theme.DARK, 1400, 320, Legend.BOTTOM, true, 20, false,
            SortBy.NONE, SortDir.DESC);

    public static final int MIN_WIDTH = 600, MAX_WIDTH = 2000, MIN_HEIGHT = 200, MAX_HEIGHT = 800, MAX_LEGEND = 100;

    public ChartStyle {
        theme = theme == null ? Theme.DARK : theme;
        legend = legend == null ? Legend.BOTTOM : legend;
        sortBy = sortBy == null ? SortBy.NONE : sortBy;
        sortDir = sortDir == null ? SortDir.DESC : sortDir;
    }

    /** Значения вне допустимых границ приводятся к ближайшей границе, неизвестные названия - к значению по умолчанию. */
    public static ChartStyle of(String theme, int width, int height, String legend, boolean stats, int maxLegend) {
        return of(theme, width, height, legend, stats, maxLegend, false, null, null);
    }

    public static ChartStyle of(String theme, int width, int height, String legend, boolean stats, int maxLegend, boolean autoWidth) {
        return of(theme, width, height, legend, stats, maxLegend, autoWidth, null, null);
    }

    public static ChartStyle of(String theme, int width, int height, String legend, boolean stats, int maxLegend, boolean autoWidth,
                                String sortBy, String sortDir) {
        return new ChartStyle(parse(Theme.class, theme, DEFAULT.theme), Math.max(MIN_WIDTH, Math.min(MAX_WIDTH, width)),
                Math.max(MIN_HEIGHT, Math.min(MAX_HEIGHT, height)), parse(Legend.class, legend, DEFAULT.legend), stats,
                Math.max(1, Math.min(MAX_LEGEND, maxLegend)), autoWidth,
                parse(SortBy.class, sortBy, DEFAULT.sortBy), parse(SortDir.class, sortDir, DEFAULT.sortDir));
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
