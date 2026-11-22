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
    // палитры: тёмная - как у Grafana, светлая - те же цвета, но читаемые на белом
    private static final Color[] DARK_COLORS = {new Color(0x73BF69), new Color(0xFADE2A), new Color(0x5794F2), new Color(0xFF9830),
            new Color(0xF2495C), new Color(0xB877D9), new Color(0x8AB8FF), new Color(0xFF780A)};
    private static final Color[] LIGHT_COLORS = {new Color(0x37872D), new Color(0xC79A00), new Color(0x1F60C4), new Color(0xE0752D),
            new Color(0xC4162A), new Color(0x8F3BB8), new Color(0x0B8AB0), new Color(0xB35900)};

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

    private static Color grid() {
        return dark() ? new Color(0x2C3235) : new Color(0xE5E7EB);
    }

    private static Color text() {
        return dark() ? new Color(0xCCCCDC) : new Color(0x1F2937);
    }

    private static Color red() {
        return dark() ? new Color(0xF2495C) : new Color(0xC4162A);
    }

    private static Color warmupColor() {
        return dark() ? new Color(0xFF9830) : new Color(0xE0752D);
    }

    /** XYChart с изменяемым размером: легенда с большим числом рядов увеличивает высоту, а не сжимает область графика. */
    private static final class SizedChart extends XYChart {
        SizedChart(int width, int height) {
            super(width, height);
        }

        void resize(int width, int height) {
            setWidth(width);
            setHeight(height);
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
        return series.isEmpty() || c.getSeriesMap().isEmpty() ? null : img(title, c);
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

    private static String num(double v) {
        double a = Math.abs(v);
        return String.format(java.util.Locale.ROOT, a >= 100 ? "%.0f" : a >= 10 ? "%.1f" : "%.2f", v);
    }

    /** Имя ряда со сводкой для легенды: Last, Mean, Max (как таблица легенды в Grafana). */
    private static String legend(String name, List<RunSeriesService.Pt> pts) {
        double sum = 0, max = Double.NEGATIVE_INFINITY;
        for (var p : pts) {
            sum += p.v();
            max = Math.max(max, p.v());
        }
        if (!style().stats()) {
            return name;
        }
        return name + "  ·  last " + num(pts.get(pts.size() - 1).v()) + "  mean " + num(sum / pts.size()) + "  max " + num(max);
    }

    private static volatile Font base;

    static {
        System.setProperty("java.awt.headless", "true");
    }

    private ChartRenderer() {
    }

    private static Font font(int style, float size) {
        if (base == null) {
            synchronized (ChartRenderer.class) {
                if (base == null) {
                    try (InputStream in = ChartRenderer.class.getResourceAsStream("/fonts/DejaVuSans.ttf")) {
                        Font f = Font.createFont(Font.TRUETYPE_FONT, in);
                        GraphicsEnvironment.getLocalGraphicsEnvironment().registerFont(f);
                        base = f;
                    } catch (Exception e) {
                        log.warn("Шрифт DejaVu недоступен, подписи графиков могут отображаться некорректно: {}", e.toString());
                        base = new Font(Font.SANS_SERIF, Font.PLAIN, 12);
                    }
                }
            }
        }
        return base.deriveFont(style, size);
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
            line(c, "SLA", List.of(min(x), max(x)), List.of(sla, sla), red(), false);
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
        List<Img> out = new ArrayList<>();
        List<RunSeriesService.OpSeries> ops = series.stream().filter(RunSeriesService.OpSeries::hasData).toList();
        if (ops.isEmpty()) {
            return out;
        }
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
            timeLine(c, "SLA", List.of(new RunSeriesService.Pt(s.pct().get(0).min(), s.slaMs()), new RunSeriesService.Pt(last, s.slaMs())),
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
            timeLine(err, s.op() + " порог", List.of(new RunSeriesService.Pt(first, s.maxErrPct()), new RunSeriesService.Pt(last, s.maxErrPct())),
                    col, true);
        }
        if (!err.getSeriesMap().isEmpty()) {
            markWarmup(err, warm);
            out.add(img(err.getTitle(), err));
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

    private static void timeLine(XYChart c, String name, List<RunSeriesService.Pt> pts, Color color, boolean dashed) {
        // у ряда с единственной точкой линии нет: рисуем маркер, остальные ряды - линией без маркеров
        List<Double> y = pts.stream().map(RunSeriesService.Pt::v).toList();
        XYSeries s;
        TimeBase tb = TIME.get();
        if (tb != null) {
            List<java.util.Date> x = pts.stream().map(p -> new java.util.Date(tb.originMs() + Math.round(p.min() * 60_000))).toList();
            c.getStyler().setDatePattern(datePattern(tb));
            s = c.addSeries(dashed || pts.isEmpty() ? name : legend(name, pts), x, y);
        } else {
            List<Double> x = pts.stream().map(RunSeriesService.Pt::min).toList();
            s = c.addSeries(dashed || pts.isEmpty() ? name : legend(name, pts), x, y);
        }
        s.setLineWidth(dashed ? 1.3f : 1.8f);
        s.setLineColor(color);
        s.setMarkerColor(color);
        s.setMarker(pts.size() == 1 ? SeriesMarkers.CIRCLE : SeriesMarkers.NONE);
        if (dashed) {
            s.setLineStyle(org.knowm.xchart.style.lines.SeriesLines.DASH_DASH);
        }
    }

    private static XYChart chart(String title, String yTitle) {
        return chart(title, yTitle, "Уровень нагрузки, %");
    }

    private static XYChart chart(String title, String yTitle, String xTitle) {
        ChartStyle cs = style();
        XYChart c = new SizedChart(cs.width(), cs.height());
        c.setTitle(title);
        c.setXAxisTitle(xTitle);
        c.setYAxisTitle(yTitle);
        var st = c.getStyler();
        st.setLegendPosition(cs.legend() == ChartStyle.Legend.RIGHT ? Styler.LegendPosition.OutsideE : Styler.LegendPosition.OutsideS);
        st.setLegendLayout(Styler.LegendLayout.Vertical);
        st.setChartTitleFont(font(Font.BOLD, 15));
        st.setLegendFont(font(Font.PLAIN, 11));
        st.setAxisTitleFont(font(Font.PLAIN, 11));
        st.setAxisTickLabelsFont(font(Font.PLAIN, 10));
        st.setYAxisMin(0.0);
        st.setPlotGridLinesVisible(true);
        st.setPlotGridVerticalLinesVisible(false);
        st.setPlotGridLinesColor(grid());
        st.setPlotGridLinesStroke(new java.awt.BasicStroke(1f));
        st.setChartBackgroundColor(bg());
        st.setPlotBackgroundColor(bg());
        st.setPlotBorderVisible(false);
        st.setChartTitleBoxVisible(false);
        st.setChartFontColor(text());
        st.setAxisTickLabelsColor(text());
        st.setAxisTickMarksColor(grid());
        st.setLegendBackgroundColor(bg());
        st.setLegendBorderColor(bg());
        st.setChartPadding(12);
        return c;
    }

    private static void line(XYChart c, String name, List<Double> x, List<Double> y, Color color, boolean markers) {
        XYSeries s = c.addSeries(name, x, y);
        s.setLineColor(color);
        s.setMarkerColor(color);
        s.setMarker(markers ? SeriesMarkers.CIRCLE : SeriesMarkers.NONE);
    }

    /** Высота строки легенды в пикселях (шрифт 11), запас под отступы и предел авто-ширины графика. */
    private static final int LEGEND_ROW = 17, LEGEND_PAD = 24, MAX_AUTO_WIDTH = 3200;

    private static int maxLegendTextWidth(XYChart c) {
        Font f = font(Font.PLAIN, 11);
        java.awt.image.BufferedImage probe = new java.awt.image.BufferedImage(1, 1, java.awt.image.BufferedImage.TYPE_INT_RGB);
        java.awt.Graphics2D g = probe.createGraphics();
        try {
            java.awt.FontMetrics fm = g.getFontMetrics(f);
            int maxW = 0;
            for (XYSeries s : c.getSeriesMap().values()) {
                if (s.isShowInLegend()) {
                    maxW = Math.max(maxW, fm.stringWidth(s.getName()));
                }
            }
            return maxW;
        } finally {
            g.dispose();
        }
    }

    private static Img img(String title, XYChart c) {
        ChartStyle cs = style();
        int w = cs.width(), h = cs.height();
        var all = new ArrayList<>(c.getSeriesMap().values());
        int shown = all.size();
        if (cs.legend() == ChartStyle.Legend.NONE) {
            c.getStyler().setLegendVisible(false);
            shown = 0;
        } else if (all.size() > cs.maxLegend()) {
            // в легенде остаются ряды с наибольшим максимумом, остальные рисуются, но в легенду не попадают
            all.sort((x, y) -> Double.compare(maxOf(y), maxOf(x)));
            for (int i = cs.maxLegend(); i < all.size(); i++) {
                all.get(i).setShowInLegend(false);
            }
            shown = cs.maxLegend();
        }
        if (cs.legend() == ChartStyle.Legend.LEFT && shown > 0) {
            c.getStyler().setLegendVisible(false);   // XChart не умеет легенду слева: рисуем её сами и склеиваем с графиком
            try {
                return new Img(title, "data:image/png;base64," + Base64.getEncoder().encodeToString(withLeftLegend(c, cs.autoWidth())));
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        }
        if (cs.legend() == ChartStyle.Legend.BOTTOM) {
            h += shown * LEGEND_ROW + (shown > 0 ? LEGEND_PAD : 0);
            if (cs.autoWidth() && shown > 0) {
                int need = maxLegendTextWidth(c) + 110;
                if (need > w) {
                    w = Math.min(MAX_AUTO_WIDTH, need);
                }
            }
        } else if (cs.legend() == ChartStyle.Legend.RIGHT && shown > 0) {
            int panel = cs.autoWidth() ? Math.max(380, maxLegendTextWidth(c) + 100) : 380;
            w = Math.min(MAX_AUTO_WIDTH, w + panel);
            h = Math.max(h, shown * LEGEND_ROW + 80);
        }
        if (c instanceof SizedChart sc) {
            sc.resize(w, h);
        }
        try {
            byte[] png = BitmapEncoder.getBitmapBytes(c, BitmapEncoder.BitmapFormat.PNG);
            return new Img(title, "data:image/png;base64," + Base64.getEncoder().encodeToString(png));
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Максимальная ширина панели легенды слева, px (если autoWidth выключен — длинные имена обрезаются с многоточием). */
    private static final int LEFT_LEGEND_MAX = 560, LEFT_LEGEND_AUTO_MAX = 1600;

    /** График без легенды и нарисованная вручную легенда слева (цвет и стиль линии, имя), на одном фоне. */
    private static byte[] withLeftLegend(XYChart c, boolean autoWidth) throws IOException {
        var entries = new ArrayList<XYSeries>();
        for (XYSeries s : c.getSeriesMap().values()) {
            if (s.isShowInLegend()) {
                entries.add(s);
            }
        }
        java.awt.image.BufferedImage plot = BitmapEncoder.getBufferedImage(c);
        Font f = font(Font.PLAIN, 11);
        java.awt.image.BufferedImage probe = new java.awt.image.BufferedImage(1, 1, java.awt.image.BufferedImage.TYPE_INT_RGB);
        java.awt.Graphics2D gp = probe.createGraphics();
        java.awt.FontMetrics fm;
        int swatch = 28, gap = 8, pad = 12;
        int cap = autoWidth ? Math.min(LEFT_LEGEND_AUTO_MAX, Math.max(LEFT_LEGEND_MAX, MAX_AUTO_WIDTH - plot.getWidth())) : LEFT_LEGEND_MAX;
        int textMax = cap - swatch - gap - 2 * pad;
        int textW = 0;
        try {
            fm = gp.getFontMetrics(f);
            for (XYSeries s : entries) {
                textW = Math.max(textW, Math.min(textMax, fm.stringWidth(s.getName())));
            }
        } finally {
            gp.dispose();
        }
        int panelW = pad + swatch + gap + textW + pad;
        int h = Math.max(plot.getHeight(), entries.size() * LEGEND_ROW + 2 * pad);
        java.awt.image.BufferedImage out = new java.awt.image.BufferedImage(panelW + plot.getWidth(), h, java.awt.image.BufferedImage.TYPE_INT_RGB);
        java.awt.Graphics2D g = out.createGraphics();
        g.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING, java.awt.RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(java.awt.RenderingHints.KEY_TEXT_ANTIALIASING, java.awt.RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setColor(bg());
        g.fillRect(0, 0, out.getWidth(), h);
        g.drawImage(plot, panelW, 0, null);
        g.setFont(f);
        int y = pad + 8;
        for (XYSeries s : entries) {
            g.setColor(s.getLineColor() == null ? text() : s.getLineColor());
            // образец рисуется тем же штрихом, что и линия ряда на графике
            g.setStroke(s.getLineStyle() != null ? s.getLineStyle() : new java.awt.BasicStroke(2f));
            g.drawLine(pad, y - 4, pad + swatch, y - 4);
            g.setColor(text());
            String name = s.getName();
            if (fm.stringWidth(name) > textMax) {
                while (name.length() > 1 && fm.stringWidth(name + "…") > textMax) {
                    name = name.substring(0, name.length() - 1);
                }
                name += "…";
            }
            g.drawString(name, pad + swatch + gap, y);
            y += LEGEND_ROW;
        }
        g.dispose();
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        javax.imageio.ImageIO.write(out, "png", bos);
        return bos.toByteArray();
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
