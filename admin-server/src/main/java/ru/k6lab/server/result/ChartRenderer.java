package ru.k6lab.server.result;

import org.knowm.xchart.BitmapEncoder;
import org.knowm.xchart.XYChart;
import org.knowm.xchart.XYSeries;
import org.knowm.xchart.style.Styler;
import org.knowm.xchart.style.markers.SeriesMarkers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.Color;
import java.awt.Font;
import java.awt.GraphicsEnvironment;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/** Графики для отчёта: PNG в data-URI (отчёт самодостаточен). Шрифт DejaVu встроен, поэтому кириллица есть и на headless-Linux. */
public final class ChartRenderer {

    private static final Logger log = LoggerFactory.getLogger(ChartRenderer.class);
    // расширенные 16-цветные палитры в стиле Grafana 11: гармоничные и различимые даже при большом числе серий
    private static final Color[] DARK_COLORS = {
            new Color(0x73BF69), new Color(0x5794F2), new Color(0xFADE2A), new Color(0xFF9830),
            new Color(0xB877D9), new Color(0xF2495C), new Color(0x56D4C8), new Color(0x8AB8FF),
            new Color(0xA3E635), new Color(0xFF780A), new Color(0xE879F9), new Color(0x38BDF8),
            new Color(0xFB7185), new Color(0xFACC15), new Color(0x34D399), new Color(0xC084FC)
    };
    private static final Color[] LIGHT_COLORS = {
            new Color(0x2E7D32), new Color(0x1D5EC4), new Color(0xC28A00), new Color(0xD96518),
            new Color(0x8635B3), new Color(0xC4162A), new Color(0x0D8A82), new Color(0x3B82F6),
            new Color(0x65A30D), new Color(0xC2410C), new Color(0xC026D3), new Color(0x0284C7),
            new Color(0xE11D48), new Color(0xA16207), new Color(0x059669), new Color(0x7C3AED)
    };

    private static final ThreadLocal<ChartStyle> STYLE = ThreadLocal.withInitial(() -> ChartStyle.DEFAULT);

    /** Выполняет построение графиков с заданным оформлением (на время вызова, в текущем потоке). */
    public static <T> T with(ChartStyle style, java.util.function.Supplier<T> body) {
        ChartStyle prev = STYLE.get();
        STYLE.set(style == null ? ChartStyle.DEFAULT : style);
        try {
            return body.get();
        } finally {
            STYLE.set(prev);
        }
    }

    /** Привязка оси времени к часам: origin - начало теста, end - конец (или «сейчас»), оба в мс. null - минуты от начала теста. */
    public record TimeBase(long originMs, long endMs) {
    }

    private static final ThreadLocal<TimeBase> TIME = new ThreadLocal<>();

    /** Построение графиков по времени с абсолютной осью (часы вместо минут от начала); tb == null - как обычно. */
    public static <T> T withTime(TimeBase tb, java.util.function.Supplier<T> body) {
        TimeBase prev = TIME.get();
        TIME.set(tb);
        try {
            return body.get();
        } finally {
            TIME.set(prev);
        }
    }

    private static boolean absTime() {
        return TIME.get() != null;
    }

    private static String timeTitle() {
        return absTime() ? "Время" : "Минуты от начала теста";
    }

    /** Формат подписей оси времени: до 10 минут - с секундами, если тест перешёл за полночь - с датой. */
    static String datePattern(TimeBase tb) {
        var zone = java.time.ZoneId.systemDefault();
        var from = java.time.Instant.ofEpochMilli(tb.originMs()).atZone(zone).toLocalDate();
        var to = java.time.Instant.ofEpochMilli(tb.endMs()).atZone(zone).toLocalDate();
        String time = (tb.endMs() - tb.originMs()) < 10 * 60_000L ? "HH:mm:ss" : "HH:mm";
        return from.equals(to) ? time : "dd.MM " + time;
    }

    private static ChartStyle style() {
        return STYLE.get();
    }

    private static boolean dark() {
        return style().theme() == ChartStyle.Theme.DARK;
    }

    private static Color[] colors() {
        return dark() ? DARK_COLORS : LIGHT_COLORS;
    }

    private static Color bg() {
        return dark() ? new Color(0x181B1F) : Color.WHITE;
    }

    private static Color plotBg() {
        return dark() ? new Color(0x14171B) : new Color(0xFCFDFF);
    }

    private static Color grid() {
        return dark() ? new Color(0x252B33) : new Color(0xE8ECF2);
    }

    private static Color text() {
        return dark() ? new Color(0xD8DCE6) : new Color(0x1F2937);
    }

    private static Color mutedText() {
        return dark() ? new Color(0x8E96A4) : new Color(0x64748B);
    }

    private static Color red() {
        return dark() ? new Color(0xF2495C) : new Color(0xC4162A);
    }

    private static Color warmupColor() {
        return dark() ? new Color(0xFF9830) : new Color(0xE0752D);
    }

    private static Color accentColor() {
        return dark() ? new Color(0x6EA8FE) : new Color(0x1D5EC4);
    }

    /** Метаданные строки табличной легенды: чистая подпись серии, колонки Last / Mean / Max и числовые значения для сортировки. */
    record LegendRow(XYSeries series, String label, String last, String mean, String max,
                     double lastVal, double meanVal, double maxVal, boolean dashed) {
        LegendRow(XYSeries series, String label, String last, String mean, String max,
                  double lastVal, double meanVal, double maxVal) {
            this(series, label, last, mean, max, lastVal, meanVal, maxVal, false);
        }

        boolean hasStats() {
            return last != null;
        }

        boolean hasNumeric() {
            return Double.isFinite(maxVal);
        }

        double sortValue(ChartStyle.SortBy by) {
            return switch (by) {
                case LAST -> lastVal;
                case MEAN -> meanVal;
                case MAX -> maxVal;
                case NONE -> Double.NaN;
            };
        }
    }

    /** XYChart с изменяемым размером и метаданными для панели в стиле Grafana. */
    private static final class SizedChart extends XYChart {
        final List<LegendRow> legendRows = new ArrayList<>();
        final String unitBadge;

        SizedChart(int width, int height, String unitBadge) {
            super(width, height);
            this.unitBadge = unitBadge == null ? "" : unitBadge.strip();
        }

        void resize(int width, int height) {
            setWidth(width);
            setHeight(height);
        }

        void recordLegend(XYSeries s, String label, double[] rawStats, boolean showStats, boolean dashed) {
            if (rawStats == null) {
                legendRows.add(new LegendRow(s, label, null, null, null, Double.NaN, Double.NaN, Double.NaN, dashed));
            } else if (showStats) {
                legendRows.add(new LegendRow(s, label, num(rawStats[0]), num(rawStats[1]), num(rawStats[2]),
                        rawStats[0], rawStats[1], rawStats[2], dashed));
            } else {
                legendRows.add(new LegendRow(s, label, null, null, null, rawStats[0], rawStats[1], rawStats[2], dashed));
            }
        }
    }

    public record Img(String title, String dataUri) {

        /** PNG-байты (для docx). */
        public byte[] png() {
            int i = dataUri.indexOf(',');
            return Base64.getDecoder().decode(dataUri.substring(i + 1));
        }
    }

    /** График по произвольному запросу пользователя: ряды по меткам, в легенде Last / Mean / Max, ось X - время от начала теста. */
    public static Img custom(String title, String unit, java.util.Map<String, List<RunSeriesService.Pt>> series) {
        return custom(title, unit, series, 0);
    }

    /** График по произвольному запросу пользователя с опциональной вертикальной отметкой конца прогрева (warmupMin > 0). */
    public static Img custom(String title, String unit, java.util.Map<String, List<RunSeriesService.Pt>> series, double warmupMin) {
        Scaled sc = scaleUnits(unit, series);
        series = sc.series();
        XYChart c = chart(title, sc.unit(), timeTitle());
        double top = 0;
        for (var pts : series.values()) {
            for (var p : pts) {
                top = Math.max(top, Math.abs(p.v()));
            }
        }
        if (top >= 1000) {
            c.getStyler().setYAxisDecimalPattern("#,###"); // без «3E8» на оси
        }
        int i = 0;
        for (var e : series.entrySet()) {
            if (e.getValue().isEmpty()) {
                continue;
            }
            timeLine(c, e.getKey(), e.getValue(), colors()[i++ % colors().length], false);
        }
        if (series.isEmpty() || c.getSeriesMap().isEmpty()) {
            return null;
        }
        if (warmupMin > 0) {
            markWarmup(c, warmupMin);
        }
        return img(title, c);
    }

    /** Единицы и значения после перевода: байты - в КБ/МБ/ГБ/ТБ. */
    public record Scaled(String unit, java.util.Map<String, List<RunSeriesService.Pt>> series) {
    }

    private static final String[] BYTE_PREFIX = {"", "К", "М", "Г", "Т"};

    /**
     * Если единицы - байты («Б», «Б/с», «B», «B/s») и значения большие, ряды переводятся в КБ, МБ, ГБ или ТБ (степени 1024),
     * чтобы на оси и в легенде не было «3E8» и девятизначных чисел. Остальные единицы не меняются.
     */
    public static Scaled scaleUnits(String unit, java.util.Map<String, List<RunSeriesService.Pt>> series) {
        String u = unit == null ? "" : unit.strip();
        boolean bytes = !u.isEmpty() && (u.charAt(0) == 'Б' || u.charAt(0) == 'B') && (u.length() == 1 || u.charAt(1) == '/');
        if (!bytes) {
            return new Scaled(u, series);
        }
        double max = 0;
        for (var pts : series.values()) {
            for (var p : pts) {
                max = Math.max(max, Math.abs(p.v()));
            }
        }
        if (max < 1024) {
            return new Scaled(u, series);
        }
        int pow = (int) Math.min(BYTE_PREFIX.length - 1, Math.floor(Math.log(max) / Math.log(1024)));
        double div = Math.pow(1024, pow);
        java.util.Map<String, List<RunSeriesService.Pt>> out = new java.util.LinkedHashMap<>();
        series.forEach((k, pts) -> out.put(k, pts.stream().map(p -> new RunSeriesService.Pt(p.min(), p.v() / div)).toList()));
        return new Scaled(BYTE_PREFIX[pow] + "Б" + u.substring(1), out);
    }

    /** Форматирование чисел в легенде с пробелом-разделителем тысяч (например 10 922 / 42.5 / 0.25). */
    private static String num(double v) {
        if (!Double.isFinite(v)) {
            return "—";
        }
        double a = Math.abs(v);
        if (a >= 1000) {
            var sym = new java.text.DecimalFormatSymbols(java.util.Locale.ROOT);
            sym.setGroupingSeparator(' ');
            var df = new java.text.DecimalFormat("#,##0", sym);
            return df.format(Math.round(v));
        }
        return String.format(java.util.Locale.ROOT, a >= 100 ? "%.0f" : a >= 10 ? "%.1f" : "%.2f", v);
    }

    /** Числовой расчёт Last / Mean / Max для табличной легенды и сортировки (null, если точек нет). */
    private static double[] rawStatsOf(List<RunSeriesService.Pt> pts) {
        if (pts == null || pts.isEmpty()) {
            return null;
        }
        double sum = 0, max = Double.NEGATIVE_INFINITY, last = Double.NaN;
        int n = 0;
        for (var p : pts) {
            if (Double.isFinite(p.v())) {
                sum += p.v();
                max = Math.max(max, p.v());
                last = p.v();
                n++;
            }
        }
        return n == 0 ? null : new double[]{last, sum / n, max};
    }

    private static volatile Font baseRegular;
    private static volatile Font baseBold;

    static {
        System.setProperty("java.awt.headless", "true");
    }

    private ChartRenderer() {
    }

    private static Font loadFontResource(String path, int fallbackStyle) {
        try (InputStream in = ChartRenderer.class.getResourceAsStream(path)) {
            if (in != null) {
                Font f = Font.createFont(Font.TRUETYPE_FONT, in);
                GraphicsEnvironment.getLocalGraphicsEnvironment().registerFont(f);
                return f;
            }
        } catch (Exception e) {
            log.warn("Шрифт {} недоступен: {}", path, e.toString());
        }
        return new Font(Font.SANS_SERIF, fallbackStyle, 12);
    }

    private static Font font(int style, float size) {
        if (baseRegular == null) {
            synchronized (ChartRenderer.class) {
                if (baseRegular == null) {
                    baseRegular = loadFontResource("/fonts/DejaVuSans.ttf", Font.PLAIN);
                    baseBold = loadFontResource("/fonts/DejaVuSans-Bold.ttf", Font.BOLD);
                }
            }
        }
        if ((style & Font.BOLD) != 0 && baseBold != null) {
            return baseBold.deriveFont(Font.PLAIN, size);
        }
        return baseRegular.deriveFont(style, size);
    }

    /** Все графики отчёта: латентность по операциям, выполнение плана по интенсивности, ошибки. */
    public static List<Img> charts(ResultData data) {
        List<Img> out = new ArrayList<>();
        List<ResultData.StepRow> steps = data.steps().stream().filter(s -> !"NOT_RUN".equals(s.status()) && !s.ops().isEmpty()).toList();
        // график «по уровню нагрузки» из одной ступени - одна точка: при одной ступени строятся графики по времени
        if (steps.size() < 2) {
            return out;
        }
        List<String> ops = steps.get(0).ops().stream().map(ResultData.OpRow::operation).toList();
        for (String op : ops) {
            XYChart c = chart("Время ответа: " + op, "мс");
            List<Double> x = new ArrayList<>();
            List<Double> y = new ArrayList<>();
            double sla = 0;
            int pn = 95;
            for (ResultData.StepRow s : steps) {
                ResultData.OpRow r = s.ops().stream().filter(o -> o.operation().equals(op)).findFirst().orElse(null);
                if (r == null) {
                    continue;
                }
                sla = r.slaMs();
                pn = r.slaPercentile();
                if (r.slaValue() != null) {
                    x.add(s.levelPct());
                    y.add(r.slaValue());
                }
            }
            if (x.isEmpty()) {
                continue;
            }
            line(c, "p" + pn, x, y, colors()[0], true);
            line(c, sla > 0 ? "SLA (" + num(sla) + " мс)" : "SLA", List.of(min(x), max(x)), List.of(sla, sla), red(), false);
            out.add(img(c.getTitle(), c));
        }
        XYChart rate = chart("Выполнение плана по интенсивности", "% от цели");
        XYChart err = chart("Ошибки", "%");
        int i = 0;
        for (String op : ops) {
            List<Double> x1 = new ArrayList<>();
            List<Double> y1 = new ArrayList<>();
            List<Double> x2 = new ArrayList<>();
            List<Double> y2 = new ArrayList<>();
            for (ResultData.StepRow s : steps) {
                ResultData.OpRow r = s.ops().stream().filter(o -> o.operation().equals(op)).findFirst().orElse(null);
                if (r == null) {
                    continue;
                }
                if (r.actualRate() != null && r.targetRate() > 0) {
                    x1.add(s.levelPct());
                    y1.add(r.actualRate() / r.targetRate() * 100.0);
                }
                if (r.errorPct() != null) {
                    x2.add(s.levelPct());
                    y2.add(r.errorPct());
                }
            }
            Color col = colors()[i++ % colors().length];
            if (!x1.isEmpty()) {
                line(rate, op, x1, y1, col, true);
            }
            if (!x2.isEmpty()) {
                line(err, op, x2, y2, col, true);
            }
        }
        if (!rate.getSeriesMap().isEmpty()) {
            out.add(img(rate.getTitle(), rate));
        }
        if (!err.getSeriesMap().isEmpty()) {
            out.add(img(err.getTitle(), err));
        }
        return out;
    }

    /** Графики по времени: от начала теста, минуты по оси X; интенсивность (факт и план), время ответа с порогом SLA, ошибки. */
    public static List<Img> timeCharts(List<RunSeriesService.OpSeries> series) {
        return timeCharts(series, false);
    }

    /** Графики по времени: при skipWarmup=true участки до конца прогрева обрезаются и линия «Прогрев до ...» не рисуется. */
    public static List<Img> timeCharts(List<RunSeriesService.OpSeries> series, boolean skipWarmup) {
        List<Img> out = new ArrayList<>();
        List<RunSeriesService.OpSeries> rawOps = series.stream().filter(RunSeriesService.OpSeries::hasData).toList();
        if (rawOps.isEmpty()) {
            return out;
        }
        List<RunSeriesService.OpSeries> ops = skipWarmup ? trimWarmup(rawOps) : rawOps;
        XYChart rate = chart("Интенсивность: факт и план", "оп/с", timeTitle());
        int i = 0;
        for (RunSeriesService.OpSeries s : ops) {
            Color col = colors()[i++ % colors().length];
            if (!s.actual().isEmpty()) {
                timeLine(rate, s.op() + " факт", s.actual(), col, false);
            }
            if (!s.plan().isEmpty()) {
                timeLine(rate, s.op() + " план", s.plan(), col, true);
            }
        }
        double warm = ops.get(0).warmupMin();
        if (!rate.getSeriesMap().isEmpty()) {
            markWarmup(rate, warm);
            out.add(img(rate.getTitle(), rate));
        }
        for (RunSeriesService.OpSeries s : ops) {
            if (s.pct().isEmpty()) {
                continue;
            }
            XYChart c = chart("Время ответа: " + s.op(), "мс", timeTitle());
            timeLine(c, "p" + s.pctN(), s.pct(), colors()[0], false);
            double last = s.pct().get(s.pct().size() - 1).min();
            timeLine(c, s.slaMs() > 0 ? "SLA (" + num(s.slaMs()) + " мс)" : "SLA",
                    List.of(new RunSeriesService.Pt(s.pct().get(0).min(), s.slaMs()), new RunSeriesService.Pt(last, s.slaMs())),
                    red(), true);
            markWarmup(c, warm);
            out.add(img(c.getTitle(), c));
        }
        XYChart err = chart("Ошибки", "%", timeTitle());
        i = 0;
        for (RunSeriesService.OpSeries s : ops) {
            Color col = colors()[i++ % colors().length];
            if (s.err().isEmpty()) {
                continue;
            }
            timeLine(err, s.op(), s.err(), col, false);
            double first = s.err().get(0).min();
            double last = s.err().get(s.err().size() - 1).min();
            timeLine(err, s.op() + " порог (" + num(s.maxErrPct()) + "%)",
                    List.of(new RunSeriesService.Pt(first, s.maxErrPct()), new RunSeriesService.Pt(last, s.maxErrPct())),
                    col, true);
        }
        if (!err.getSeriesMap().isEmpty()) {
            markWarmup(err, warm);
            out.add(img(err.getTitle(), err));
        }
        return out;
    }

    /** Обрезает период прогрева (min < warmupMin) у временных рядов; если после прогрева точек нет, оставляет исходные ряды. */
    static List<RunSeriesService.OpSeries> trimWarmup(List<RunSeriesService.OpSeries> ops) {
        List<RunSeriesService.OpSeries> out = new ArrayList<>(ops.size());
        for (RunSeriesService.OpSeries s : ops) {
            double w = s.warmupMin();
            if (w <= 0) {
                out.add(s);
                continue;
            }
            List<RunSeriesService.Pt> actual = trimPoints(s.actual(), w, false);
            List<RunSeriesService.Pt> pct = trimPoints(s.pct(), w, false);
            List<RunSeriesService.Pt> err = trimPoints(s.err(), w, false);
            boolean anyAfter = !actual.isEmpty() || !pct.isEmpty() || !err.isEmpty();
            if (!anyAfter) {
                // тест завершился до конца прогрева — не прячем график целиком
                out.add(s);
                continue;
            }
            List<RunSeriesService.Pt> plan = trimPoints(s.plan(), w, true);
            out.add(new RunSeriesService.OpSeries(s.op(), s.pctN(), s.slaMs(), s.maxErrPct(),
                    actual.isEmpty() ? s.actual() : actual,
                    plan.isEmpty() ? s.plan() : plan,
                    pct.isEmpty() ? s.pct() : pct,
                    err.isEmpty() ? s.err() : err,
                    0));
        }
        return out;
    }

    static List<RunSeriesService.Pt> trimPoints(List<RunSeriesService.Pt> pts, double warmupMin, boolean interpolate) {
        if (pts == null || pts.isEmpty() || warmupMin <= 0) {
            return pts == null ? List.of() : pts;
        }
        double eps = 1e-6;
        List<RunSeriesService.Pt> out = new ArrayList<>();
        if (interpolate) {
            for (int i = 0; i < pts.size(); i++) {
                RunSeriesService.Pt cur = pts.get(i);
                if (cur.min() >= warmupMin - eps) {
                    if (out.isEmpty() && cur.min() > warmupMin + eps && i > 0) {
                        RunSeriesService.Pt prev = pts.get(i - 1);
                        double span = cur.min() - prev.min();
                        if (span > eps) {
                            double frac = (warmupMin - prev.min()) / span;
                            out.add(new RunSeriesService.Pt(warmupMin, prev.v() + frac * (cur.v() - prev.v())));
                        }
                    }
                    out.add(cur);
                }
            }
            return out;
        }
        for (RunSeriesService.Pt p : pts) {
            if (p.min() >= warmupMin - eps) {
                out.add(p);
            }
        }
        return out;
    }

    /** Граница прогрева: вертикальная пунктирная линия на уровне максимума графика; всё левее - прогрев, он не оценивается. */
    private static void markWarmup(XYChart c, double warmupMin) {
        if (warmupMin <= 0 || c.getSeriesMap().isEmpty()) {
            return;
        }
        double max = 0;
        for (var s : c.getSeriesMap().values()) {
            for (double v : s.getYData()) {
                if (Double.isFinite(v)) {
                    max = Math.max(max, v);
                }
            }
        }
        timeLine(c, String.format(java.util.Locale.ROOT, "Прогрев до %.1f мин", warmupMin),
                List.of(new RunSeriesService.Pt(warmupMin, 0), new RunSeriesService.Pt(warmupMin, max <= 0 ? 1 : max * 1.05)),
                warmupColor(), true);
    }

    private static String uniqueSeriesKey(XYChart c, String name) {
        String key = name;
        int n = 2;
        while (c.getSeriesMap().containsKey(key)) {
            key = name + " #" + n++;
        }
        return key;
    }

    private static final java.awt.BasicStroke DATA_STROKE = new java.awt.BasicStroke(
            2.2f, java.awt.BasicStroke.CAP_ROUND, java.awt.BasicStroke.JOIN_ROUND);
    private static final java.awt.BasicStroke DASH_STROKE = new java.awt.BasicStroke(
            1.5f, java.awt.BasicStroke.CAP_BUTT, java.awt.BasicStroke.JOIN_MITER, 10f, new float[]{5f, 4f}, 0f);

    private static void timeLine(XYChart c, String name, List<RunSeriesService.Pt> pts, Color color, boolean dashed) {
        // у ряда с единственной точкой линии нет: рисуем маркер, остальные ряды - линией без маркеров
        List<Double> y = pts.stream().map(RunSeriesService.Pt::v).toList();
        String key = uniqueSeriesKey(c, name);
        XYSeries s;
        TimeBase tb = TIME.get();
        if (tb != null) {
            List<java.util.Date> x = pts.stream().map(p -> new java.util.Date(tb.originMs() + Math.round(p.min() * 60_000))).toList();
            c.getStyler().setDatePattern(datePattern(tb));
            s = c.addSeries(key, x, y);
        } else {
            List<Double> x = pts.stream().map(RunSeriesService.Pt::min).toList();
            s = c.addSeries(key, x, y);
        }
        s.setLineColor(color);
        s.setMarkerColor(color);
        s.setMarker(pts.size() == 1 ? SeriesMarkers.CIRCLE : SeriesMarkers.NONE);
        s.setLineStyle(dashed ? DASH_STROKE : DATA_STROKE);
        if (c instanceof SizedChart sc) {
            double[] st = dashed ? null : rawStatsOf(pts);
            sc.recordLegend(s, name, st, style().stats(), dashed);
        }
    }

    private static XYChart chart(String title, String yTitle) {
        return chart(title, yTitle, "Уровень нагрузки, %");
    }

    private static XYChart chart(String title, String yTitle, String xTitle) {
        ChartStyle cs = style();
        XYChart c = new SizedChart(cs.width(), cs.height(), yTitle);
        c.setTitle(title);
        c.setXAxisTitle(xTitle);
        c.setYAxisTitle(yTitle);
        var st = c.getStyler();
        // заголовок панели и табличную легенду рисуем сами в Graphics2D (как в Grafana)
        st.setLegendVisible(false);
        st.setChartTitleVisible(false);
        st.setAxisTitleFont(font(Font.PLAIN, 10.5f));
        st.setAxisTickLabelsFont(font(Font.PLAIN, 10f));
        st.setXAxisTickMarkSpacingHint(155);
        st.setYAxisTickMarkSpacingHint(48);
        st.setYAxisMin(0.0);
        st.setPlotGridLinesVisible(true);
        st.setPlotGridVerticalLinesVisible(true);
        st.setPlotGridLinesColor(grid());
        st.setPlotGridLinesStroke(new java.awt.BasicStroke(1f));
        st.setChartBackgroundColor(bg());
        st.setPlotBackgroundColor(plotBg());
        st.setPlotBorderVisible(true);
        st.setPlotBorderColor(grid());
        st.setChartTitleBoxVisible(false);
        st.setChartFontColor(mutedText());
        st.setAxisTickLabelsColor(mutedText());
        st.setAxisTickMarksColor(grid());
        st.setAxisTickMarksStroke(new java.awt.BasicStroke(1f));
        st.setLegendBackgroundColor(bg());
        st.setLegendBorderColor(bg());
        st.setChartPadding(10);
        st.setPlotContentSize(0.98);
        return c;
    }

    private static void line(XYChart c, String name, List<Double> x, List<Double> y, Color color, boolean markers) {
        XYSeries s = c.addSeries(uniqueSeriesKey(c, name), x, y);
        s.setLineColor(color);
        s.setMarkerColor(color);
        s.setMarker(markers ? SeriesMarkers.CIRCLE : SeriesMarkers.NONE);
        boolean dashed = !markers && name != null && name.startsWith("SLA");
        s.setLineStyle(dashed ? DASH_STROKE : DATA_STROKE);
        if (c instanceof SizedChart sc) {
            sc.recordLegend(s, name, null, false, dashed);
        }
    }

    /** Параметры панели в стиле Grafana и предел авто-ширины графика. */
    private static final int OUTER_PAD = 4, HEADER_H = 36, TABLE_HEAD_H = 25, TABLE_ROW_H = 23, TABLE_FOOT_H = 21;
    private static final int TABLE_PAD = 12, MAX_AUTO_WIDTH = 3200;
    private static final int SIDE_LEGEND_MAX = 540, SIDE_LEGEND_AUTO_MAX = 1600;

    /**
     * Финальная полировка плота перед рендером:
     * - лёгкий верхний запас по оси Y (+8%), чтобы пики и SLA не прилипали к верхней границе;
     * - мягкая полупрозрачная заливка под линией (Grafana area tint), когда серий данных 1–2.
     */
    private static void applyPlotPolish(XYChart c) {
        double maxY = 0;
        double minY = 0;
        int dataSeriesCount = 0;
        if (c instanceof SizedChart sc) {
            for (LegendRow r : sc.legendRows) {
                if (!r.dashed()) {
                    dataSeriesCount++;
                }
            }
        }
        for (XYSeries s : c.getSeriesMap().values()) {
            for (double v : s.getYData()) {
                if (Double.isFinite(v)) {
                    maxY = Math.max(maxY, v);
                    minY = Math.min(minY, v);
                }
            }
        }
        if (minY < 0) {
            c.getStyler().setYAxisMin(null);
        }
        if (maxY > 0) {
            c.getStyler().setYAxisMax(maxY * 1.08);
        }
        if (maxY >= 1000) {
            c.getStyler().setYAxisDecimalPattern("#,###");
        }
        // мягкая заливка под кривой (как в Grafana) для одиночных/парных рядов при неотрицательных значениях
        if (c instanceof SizedChart sc && dataSeriesCount >= 1 && dataSeriesCount <= 2 && minY >= 0) {
            int alpha = dark() ? 30 : 24;
            for (LegendRow r : sc.legendRows) {
                if (!r.dashed() && r.series().getYData().length >= 2) {
                    Color col = r.series().getLineColor();
                    if (col != null) {
                        r.series().setXYSeriesRenderStyle(XYSeries.XYSeriesRenderStyle.Area);
                        r.series().setFillColor(new Color(col.getRed(), col.getGreen(), col.getBlue(), alpha));
                    }
                }
            }
        }
    }

    private static Img img(String title, XYChart c) {
        ChartStyle cs = style();
        c.getStyler().setLegendVisible(false);
        applyPlotPolish(c);
        try {
            List<LegendRow> allRows = collectLegendRows(c);
            int totalSeries = allRows.size();
            List<LegendRow> rows = cs.legend() == ChartStyle.Legend.NONE ? List.of() : sortAndCapLegendRows(allRows, cs);
            byte[] png = renderPanel(title, c, rows, totalSeries, cs);
            return new Img(title, "data:image/png;base64," + Base64.getEncoder().encodeToString(png));
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static List<LegendRow> collectLegendRows(XYChart c) {
        List<LegendRow> out = new ArrayList<>();
        if (c instanceof SizedChart sc && !sc.legendRows.isEmpty()) {
            for (LegendRow r : sc.legendRows) {
                if (r.series().isShowInLegend()) {
                    out.add(r);
                }
            }
            return out;
        }
        for (XYSeries s : c.getSeriesMap().values()) {
            if (s.isShowInLegend()) {
                double m = maxOf(s);
                out.add(new LegendRow(s, s.getName(), null, null, null, Double.NaN, Double.NaN, Double.isFinite(m) ? m : Double.NaN, false));
            }
        }
        return out;
    }

    /**
     * Сортировка и ограничение числа строк табличной легенды по настройке ChartStyle.
     * Опорные линии без статистики (SLA, прогрев, план) всегда остаются внизу в исходном порядке.
     */
    static List<LegendRow> sortAndCapLegendRows(List<LegendRow> rows, ChartStyle cs) {
        if (rows.isEmpty()) {
            return List.of();
        }
        List<LegendRow> ordered = new ArrayList<>(rows);
        if (cs.sortBy() != ChartStyle.SortBy.NONE) {
            boolean asc = cs.sortDir() == ChartStyle.SortDir.ASC;
            ordered.sort((a, b) -> {
                if (a.hasNumeric() != b.hasNumeric()) {
                    return a.hasNumeric() ? -1 : 1; // опорные линии всегда внизу
                }
                if (!a.hasNumeric()) {
                    return 0;
                }
                double va = a.sortValue(cs.sortBy());
                double vb = b.sortValue(cs.sortBy());
                return asc ? Double.compare(va, vb) : Double.compare(vb, va);
            });
            if (ordered.size() > cs.maxLegend()) {
                return new ArrayList<>(ordered.subList(0, cs.maxLegend()));
            }
            return ordered;
        }
        // без сортировки: если рядов больше лимита, в легенде остаются ряды с наибольшим максимумом (в исходном порядке)
        if (ordered.size() > cs.maxLegend()) {
            List<LegendRow> byMax = new ArrayList<>(ordered);
            byMax.sort((x, y) -> Double.compare(
                    y.hasNumeric() ? y.maxVal() : maxOf(y.series()),
                    x.hasNumeric() ? x.maxVal() : maxOf(x.series())));
            var keep = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<LegendRow, Boolean>());
            for (int i = 0; i < cs.maxLegend(); i++) {
                keep.add(byMax.get(i));
            }
            ordered.removeIf(r -> !keep.contains(r));
        }
        return ordered;
    }

    /** Вспомогательный метод для тестов: порядок имён серий в табличной легенде для заданного стиля. */
    static List<String> legendOrder(java.util.Map<String, List<RunSeriesService.Pt>> series, ChartStyle cs) {
        return with(cs, () -> {
            XYChart c = chart("test", "", timeTitle());
            int i = 0;
            for (var e : series.entrySet()) {
                if (!e.getValue().isEmpty()) {
                    timeLine(c, e.getKey(), e.getValue(), colors()[i++ % colors().length], false);
                }
            }
            return sortAndCapLegendRows(collectLegendRows(c), cs).stream().map(LegendRow::label).toList();
        });
    }

    private static String colHeader(String name, ChartStyle.SortBy col, ChartStyle cs) {
        if (cs.sortBy() == col) {
            return name + (cs.sortDir() == ChartStyle.SortDir.ASC ? " ↑" : " ↓");
        }
        return name;
    }

    /** Полная карточка графика в стиле Grafana: единая шапка с бейджами + область графика + табличная легенда. */
    private static byte[] renderPanel(String title, XYChart c, List<LegendRow> rows, int totalSeries, ChartStyle cs) throws IOException {
        boolean showLegend = cs.legend() != ChartStyle.Legend.NONE && !rows.isEmpty();
        boolean hasStats = showLegend && cs.stats() && rows.stream().anyMatch(LegendRow::hasStats);
        boolean truncated = showLegend && rows.size() < totalSeries;
        String hLast = colHeader("Last", ChartStyle.SortBy.LAST, cs);
        String hMean = colHeader("Mean", ChartStyle.SortBy.MEAN, cs);
        String hMax = colHeader("Max", ChartStyle.SortBy.MAX, cs);
        Font fTitle = font(Font.BOLD, 13.5f);
        Font fBadge = font(Font.PLAIN, 10f);
        Font fHead = font(Font.BOLD, 10.5f);
        Font fRow = font(Font.PLAIN, 11f);
        Font fFoot = font(Font.PLAIN, 10f);

        java.awt.image.BufferedImage probe = new java.awt.image.BufferedImage(1, 1, java.awt.image.BufferedImage.TYPE_INT_RGB);
        java.awt.Graphics2D gp = probe.createGraphics();
        gp.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING, java.awt.RenderingHints.VALUE_ANTIALIAS_ON);
        gp.setRenderingHint(java.awt.RenderingHints.KEY_TEXT_ANTIALIASING, java.awt.RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        gp.setRenderingHint(java.awt.RenderingHints.KEY_FRACTIONALMETRICS, java.awt.RenderingHints.VALUE_FRACTIONALMETRICS_ON);
        java.awt.FontMetrics fmTitle, fmBadge, fmHead, fmRow, fmFoot;
        int swatchW = 16, swatchGap = 9, cellPadX = 10, colGap = 18;
        int wLabelNat = 0, wLast = 0, wMean = 0, wMax = 0;
        try {
            fmTitle = gp.getFontMetrics(fTitle);
            fmBadge = gp.getFontMetrics(fBadge);
            fmHead = gp.getFontMetrics(fHead);
            fmRow = gp.getFontMetrics(fRow);
            fmFoot = gp.getFontMetrics(fFoot);
            wLabelNat = fmHead.stringWidth("Серия");
            if (hasStats) {
                wLast = Math.max(46, fmHead.stringWidth(hLast));
                wMean = Math.max(46, fmHead.stringWidth(hMean));
                wMax = Math.max(46, fmHead.stringWidth(hMax));
            }
            for (LegendRow r : rows) {
                wLabelNat = Math.max(wLabelNat, fmRow.stringWidth(r.label()));
                if (hasStats && r.hasStats()) {
                    wLast = Math.max(wLast, fmRow.stringWidth(r.last()));
                    wMean = Math.max(wMean, fmRow.stringWidth(r.mean()));
                    wMax = Math.max(wMax, fmRow.stringWidth(r.max()));
                }
            }
        } finally {
            gp.dispose();
        }

        int statsBlockW = hasStats ? (colGap + wLast + colGap + wMean + colGap + wMax) : 0;
        int naturalTableW = cellPadX + swatchW + swatchGap + wLabelNat + statsBlockW + cellPadX + 10;
        int tableH = showLegend ? ((hasStats ? TABLE_HEAD_H : 0) + rows.size() * TABLE_ROW_H + (truncated ? TABLE_FOOT_H : 0) + 2) : 0;

        int plotW = cs.width();
        int plotH = cs.height();
        int outW, outH, plotX, plotY, tblX = 0, tblY = 0, tblW = 0;
        int cardX = OUTER_PAD, cardY = OUTER_PAD;

        if (!showLegend) {
            // без легенды сохраняем точные габариты cs.width() x cs.height(), умещая шапку панели внутри
            outW = cs.width();
            outH = cs.height();
            plotW = Math.max(120, outW - 2 * OUTER_PAD - 2);
            plotH = Math.max(120, outH - 2 * OUTER_PAD - HEADER_H - 2);
            plotX = cardX + 1;
            plotY = cardY + HEADER_H + 1;
        } else if (cs.legend() == ChartStyle.Legend.BOTTOM) {
            int desiredOutW = cs.width();
            if (cs.autoWidth()) {
                int needW = naturalTableW + 2 * (TABLE_PAD + OUTER_PAD);
                if (needW > desiredOutW) {
                    desiredOutW = Math.min(MAX_AUTO_WIDTH, needW);
                }
            }
            outW = desiredOutW;
            plotW = outW - 2 * OUTER_PAD - 2;
            plotH = cs.height();
            outH = 2 * OUTER_PAD + HEADER_H + plotH + tableH + TABLE_PAD + 2;
            plotX = cardX + 1;
            plotY = cardY + HEADER_H + 1;
            tblX = cardX + TABLE_PAD;
            tblY = plotY + plotH + 2;
            tblW = outW - 2 * (OUTER_PAD + TABLE_PAD);
        } else {
            // LEFT или RIGHT: единая шапка на всю ширину карточки, ниже — график и таблица бок о бок
            int sideCap = cs.autoWidth()
                    ? Math.min(SIDE_LEGEND_AUTO_MAX, Math.max(SIDE_LEGEND_MAX, MAX_AUTO_WIDTH - cs.width() - 2 * OUTER_PAD))
                    : SIDE_LEGEND_MAX;
            tblW = Math.min(sideCap, Math.max(220, naturalTableW));
            int panelW = tblW + 2 * TABLE_PAD;
            outW = Math.min(MAX_AUTO_WIDTH, cs.width() + panelW + 2 * OUTER_PAD);
            int innerW = outW - 2 * OUTER_PAD - 2;
            plotW = Math.min(cs.width(), innerW - 180);
            panelW = innerW - plotW;
            tblW = Math.max(160, panelW - 2 * TABLE_PAD);
            int bodyH = Math.max(plotH, tableH + 2 * TABLE_PAD);
            outH = 2 * OUTER_PAD + HEADER_H + bodyH + 2;
            boolean left = cs.legend() == ChartStyle.Legend.LEFT;
            plotX = left ? (cardX + 1 + panelW) : (cardX + 1);
            plotY = cardY + HEADER_H + 1;
            tblX = left ? (cardX + TABLE_PAD) : (plotX + plotW + TABLE_PAD);
            tblY = cardY + HEADER_H + TABLE_PAD;
        }

        if (c instanceof SizedChart sc) {
            sc.resize(plotW, plotH);
        }

        java.awt.image.BufferedImage plot = BitmapEncoder.getBufferedImage(c);
        java.awt.image.BufferedImage out = new java.awt.image.BufferedImage(outW, outH, java.awt.image.BufferedImage.TYPE_INT_RGB);
        java.awt.Graphics2D g = out.createGraphics();
        g.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING, java.awt.RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(java.awt.RenderingHints.KEY_TEXT_ANTIALIASING, java.awt.RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(java.awt.RenderingHints.KEY_FRACTIONALMETRICS, java.awt.RenderingHints.VALUE_FRACTIONALMETRICS_ON);

        // внешний фон темы (в т.ч. углы (1,1) и (2,2))
        g.setColor(bg());
        g.fillRect(0, 0, outW, outH);

        int cardW = outW - 2 * OUTER_PAD;
        int cardH = outH - 2 * OUTER_PAD;
        Color cardBorder = dark() ? new Color(0x2D333D) : new Color(0xD8DFE9);

        // тело карточки и график
        g.drawImage(plot, plotX, plotY, null);

        // единая шапка панели Grafana
        String unitBadge = (c instanceof SizedChart sc) ? sc.unitBadge : "";
        drawPanelHeader(g, title == null ? "" : title, unitBadge, rows.size(), totalSeries, cs,
                cardX, cardY, cardW, HEADER_H, fTitle, fBadge, fmTitle, fmBadge, cardBorder);

        // табличная легенда
        if (showLegend) {
            drawLegendTable(g, rows, totalSeries, cs, hasStats, hLast, hMean, hMax, tblX, tblY, tblW, tableH,
                    fHead, fRow, fFoot, fmHead, fmRow, fmFoot, swatchW, swatchGap, cellPadX, colGap, wLast, wMean, wMax);
        }

        // внешняя рамка всей карточки графика
        g.setColor(cardBorder);
        g.setStroke(new java.awt.BasicStroke(1f));
        g.drawRoundRect(cardX, cardY, cardW - 1, cardH - 1, 8, 8);

        g.dispose();
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        javax.imageio.ImageIO.write(out, "png", bos);
        return bos.toByteArray();
    }

    private static void drawPanelHeader(java.awt.Graphics2D g, String title, String unitBadge, int shown, int total, ChartStyle cs,
                                        int x, int y, int w, int h,
                                        Font fTitle, Font fBadge, java.awt.FontMetrics fmTitle, java.awt.FontMetrics fmBadge,
                                        Color borderCol) {
        Color headBg = dark() ? new Color(0x1E232A) : new Color(0xF5F7FB);
        Color badgeBg = dark() ? new Color(0x282F3A) : new Color(0xE8EEF7);
        Color badgeTxt = dark() ? new Color(0xB8C2D4) : new Color(0x3B4960);

        // фон шапки со скруглением сверху
        g.setColor(headBg);
        g.fillRoundRect(x + 1, y + 1, w - 2, h + 4, 8, 8);
        g.fillRect(x + 1, y + h - 4, w - 2, 5);

        // правые компактные бейджи (единицы, число серий / лимит, активная сортировка)
        List<String> badges = new ArrayList<>();
        if (unitBadge != null && !unitBadge.isBlank()) {
            badges.add(unitBadge);
        }
        if (cs.legend() != ChartStyle.Legend.NONE && total > 0) {
            if (shown < total) {
                badges.add("Топ " + shown + " из " + total);
            } else if (total > 1) {
                badges.add(pluralSeries(total));
            }
        }
        if (cs.sortBy() != ChartStyle.SortBy.NONE) {
            String sortLbl = switch (cs.sortBy()) {
                case MAX -> "Max";
                case MEAN -> "Mean";
                case LAST -> "Last";
                case NONE -> "";
            };
            badges.add(sortLbl + (cs.sortDir() == ChartStyle.SortDir.ASC ? " ↑" : " ↓"));
        }

        int rightCursor = x + w - 12;
        int badgeH = 20;
        int badgeY = y + (h - badgeH) / 2;
        int badgeBaseY = badgeY + (badgeH + fmBadge.getAscent() - fmBadge.getDescent()) / 2;
        g.setFont(fBadge);
        for (int i = badges.size() - 1; i >= 0; i--) {
            String b = badges.get(i);
            int bw = fmBadge.stringWidth(b) + 14;
            int bx = rightCursor - bw;
            if (bx < x + 140) {
                break;
            }
            boolean isSort = (i == badges.size() - 1) && cs.sortBy() != ChartStyle.SortBy.NONE;
            g.setColor(isSort ? (dark() ? new Color(0x1E3352) : new Color(0xDBEAFE)) : badgeBg);
            g.fillRoundRect(bx, badgeY, bw, badgeH, 10, 10);
            g.setColor(isSort ? accentColor() : badgeTxt);
            g.drawString(b, bx + 7, badgeBaseY);
            rightCursor = bx - 6;
        }

        // заголовок слева
        g.setFont(fTitle);
        g.setColor(dark() ? new Color(0xEEF2F7) : new Color(0x0F172A));
        int titleX = x + 14;
        int titleMaxW = Math.max(60, rightCursor - titleX - 8);
        int titleBaseY = y + (h + fmTitle.getAscent() - fmTitle.getDescent()) / 2;
        g.drawString(fitText(title, fmTitle, titleMaxW), titleX, titleBaseY);

        // разделитель под шапкой
        g.setColor(borderCol);
        g.drawLine(x + 1, y + h, x + w - 2, y + h);
    }

    private static void drawLegendTable(java.awt.Graphics2D g, List<LegendRow> rows, int totalSeries, ChartStyle cs, boolean hasStats,
                                        String hLast, String hMean, String hMax,
                                        int x, int y, int w, int h,
                                        Font fHead, Font fRow, Font fFoot,
                                        java.awt.FontMetrics fmHead, java.awt.FontMetrics fmRow, java.awt.FontMetrics fmFoot,
                                        int swatchW, int swatchGap, int cellPadX, int colGap,
                                        int wLast, int wMean, int wMax) {
        Color borderCol = dark() ? new Color(0x2D333D) : new Color(0xDCE2EC);
        Color headBg = dark() ? new Color(0x20252E) : new Color(0xF1F5F9);
        Color zebraBg = dark() ? new Color(0x1B1F26) : new Color(0xF8FAFC);
        Color rowLine = dark() ? new Color(0x252B34) : new Color(0xE9EEF5);
        Color headTxt = dark() ? new Color(0x94A0B5) : new Color(0x475569);
        Color numTxt = dark() ? new Color(0xE8ECF4) : new Color(0x0F172A);
        Color dashTxt = dark() ? new Color(0x5B6474) : new Color(0x94A3B8);

        // фон таблицы
        g.setColor(plotBg());
        g.fillRoundRect(x, y, w, h, 6, 6);

        int rightMax = x + w - cellPadX;
        int rightMean = rightMax - wMax - colGap;
        int rightLast = rightMean - wMean - colGap;
        int labelX = x + cellPadX + swatchW + swatchGap;
        int labelMaxW = hasStats ? Math.max(40, (rightLast - wLast - colGap) - labelX)
                : Math.max(40, (x + w - cellPadX) - labelX);

        int curY = y + 1;
        if (hasStats) {
            g.setColor(headBg);
            g.fillRect(x + 1, curY, w - 2, TABLE_HEAD_H);
            g.setFont(fHead);
            int baseY = curY + (TABLE_HEAD_H + fmHead.getAscent() - fmHead.getDescent()) / 2;
            g.setColor(headTxt);
            g.drawString("Серия", labelX, baseY);
            g.setColor(cs.sortBy() == ChartStyle.SortBy.LAST ? accentColor() : headTxt);
            drawRight(g, fmHead, hLast, rightLast, baseY);
            g.setColor(cs.sortBy() == ChartStyle.SortBy.MEAN ? accentColor() : headTxt);
            drawRight(g, fmHead, hMean, rightMean, baseY);
            g.setColor(cs.sortBy() == ChartStyle.SortBy.MAX ? accentColor() : headTxt);
            drawRight(g, fmHead, hMax, rightMax, baseY);
            curY += TABLE_HEAD_H;
            g.setColor(borderCol);
            g.drawLine(x + 1, curY, x + w - 2, curY);
        }

        for (int i = 0; i < rows.size(); i++) {
            LegendRow r = rows.get(i);
            if (i % 2 == 1) {
                g.setColor(zebraBg);
                g.fillRect(x + 1, curY + 1, w - 2, TABLE_ROW_H - 1);
            }
            int midY = curY + TABLE_ROW_H / 2;
            int baseY = curY + (TABLE_ROW_H + fmRow.getAscent() - fmRow.getDescent()) / 2;

            // образец серии: аккуратная скруглённая капсула для данных, чёткий пунктир для опорных линий
            Color sCol = r.series().getLineColor() == null ? text() : r.series().getLineColor();
            g.setColor(sCol);
            if (r.dashed()) {
                java.awt.Stroke prevStroke = g.getStroke();
                g.setStroke(new java.awt.BasicStroke(2f, java.awt.BasicStroke.CAP_BUTT, java.awt.BasicStroke.JOIN_MITER, 10f, new float[]{4f, 3f}, 0f));
                g.drawLine(x + cellPadX, midY, x + cellPadX + swatchW, midY);
                g.setStroke(prevStroke);
            } else {
                g.fillRoundRect(x + cellPadX, midY - 2, swatchW, 4, 4, 4);
            }

            // имя серии (опорные линии чуть мягче по контрасту)
            g.setFont(fRow);
            g.setColor(r.dashed() ? mutedText() : text());
            g.drawString(fitText(r.label(), fmRow, labelMaxW), labelX, baseY);

            // числовые колонки Last / Mean / Max
            if (hasStats) {
                if (r.hasStats()) {
                    g.setColor(cs.sortBy() == ChartStyle.SortBy.LAST ? numTxt : text());
                    drawRight(g, fmRow, r.last(), rightLast, baseY);
                    g.setColor(cs.sortBy() == ChartStyle.SortBy.MEAN ? numTxt : text());
                    drawRight(g, fmRow, r.mean(), rightMean, baseY);
                    g.setColor(cs.sortBy() == ChartStyle.SortBy.MAX ? numTxt : text());
                    drawRight(g, fmRow, r.max(), rightMax, baseY);
                } else {
                    g.setColor(dashTxt);
                    drawRight(g, fmRow, "—", rightLast, baseY);
                    drawRight(g, fmRow, "—", rightMean, baseY);
                    drawRight(g, fmRow, "—", rightMax, baseY);
                }
            }

            curY += TABLE_ROW_H;
            if (i < rows.size() - 1 || rows.size() < totalSeries) {
                g.setColor(rowLine);
                g.drawLine(x + 1, curY, x + w - 2, curY);
            }
        }

        // подвал, если часть серий скрыта лимитом maxLegend
        if (rows.size() < totalSeries) {
            g.setColor(headBg);
            g.fillRect(x + 1, curY + 1, w - 2, TABLE_FOOT_H - 1);
            g.setFont(fFoot);
            g.setColor(mutedText());
            int footBaseY = curY + (TABLE_FOOT_H + fmFoot.getAscent() - fmFoot.getDescent()) / 2;
            String note = "Показано " + rows.size() + " из " + totalSeries + " серий (лимит легенды)";
            g.drawString(fitText(note, fmFoot, Math.max(60, w - 2 * cellPadX)), x + cellPadX, footBaseY);
        }

        g.setColor(borderCol);
        g.setStroke(new java.awt.BasicStroke(1f));
        g.drawRoundRect(x, y, w - 1, h - 1, 6, 6);
    }

    private static String pluralSeries(int n) {
        int m100 = Math.abs(n) % 100;
        int m10 = m100 % 10;
        if (m100 >= 11 && m100 <= 14) {
            return n + " серий";
        }
        return switch (m10) {
            case 1 -> n + " серия";
            case 2, 3, 4 -> n + " серии";
            default -> n + " серий";
        };
    }

    private static void drawRight(java.awt.Graphics2D g, java.awt.FontMetrics fm, String s, int rightX, int baseY) {
        g.drawString(s, rightX - fm.stringWidth(s), baseY);
    }

    private static String fitText(String s, java.awt.FontMetrics fm, int maxW) {
        if (fm.stringWidth(s) <= maxW) {
            return s;
        }
        String out = s;
        while (out.length() > 1 && fm.stringWidth(out + "…") > maxW) {
            out = out.substring(0, out.length() - 1);
        }
        return out + "…";
    }

    private static double maxOf(XYSeries s) {
        double m = Double.NEGATIVE_INFINITY;
        for (double v : s.getYData()) {
            if (Double.isFinite(v)) {
                m = Math.max(m, v);
            }
        }
        return m;
    }

    private static double min(List<Double> v) {
        return v.stream().mapToDouble(Double::doubleValue).min().orElse(0);
    }

    private static double max(List<Double> v) {
        return v.stream().mapToDouble(Double::doubleValue).max().orElse(0);
    }
}
