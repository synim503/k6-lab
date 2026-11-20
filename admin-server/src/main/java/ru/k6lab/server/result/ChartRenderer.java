package ru.k6lab.server.result;

import org.knowm.xchart.BitmapEncoder;
import org.knowm.xchart.XYChart;
import org.knowm.xchart.XYChartBuilder;
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
    // палитра и оформление - как у тёмной темы Grafana
    private static final Color[] COLORS = {new Color(0x73BF69), new Color(0xFADE2A), new Color(0x5794F2), new Color(0xFF9830),
            new Color(0xF2495C), new Color(0xB877D9), new Color(0x8AB8FF), new Color(0xFF780A)};
    private static final Color BG = new Color(0x181B1F);
    private static final Color GRID = new Color(0x2C3235);
    private static final Color TEXT = new Color(0xCCCCDC);
    private static final Color RED = new Color(0xF2495C);

    public record Img(String title, String dataUri) {

        /** PNG-байты (для docx). */
        public byte[] png() {
            int i = dataUri.indexOf(',');
            return Base64.getDecoder().decode(dataUri.substring(i + 1));
        }
    }

    /** График по произвольному запросу пользователя: ряды по меткам, в легенде Last / Mean / Max, ось X - время от начала теста. */
    public static Img custom(String title, String unit, java.util.Map<String, List<RunSeriesService.Pt>> series) {
        XYChart c = chart(title, unit == null ? "" : unit, "Минуты от начала теста");
        int i = 0;
        for (var e : series.entrySet()) {
            if (e.getValue().isEmpty()) {
                continue;
            }
            timeLine(c, e.getKey(), e.getValue(), COLORS[i++ % COLORS.length], false);
        }
        return series.isEmpty() || c.getSeriesMap().isEmpty() ? null : img(title, c);
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
            line(c, "p" + pn, x, y, COLORS[0], true);
            line(c, "SLA", List.of(min(x), max(x)), List.of(sla, sla), RED, false);
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
            Color col = COLORS[i++ % COLORS.length];
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
        XYChart rate = chart("Интенсивность: факт и план", "оп/с", "Минуты от начала теста");
        int i = 0;
        for (RunSeriesService.OpSeries s : ops) {
            Color col = COLORS[i++ % COLORS.length];
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
            XYChart c = chart("Время ответа: " + s.op(), "мс", "Минуты от начала теста");
            timeLine(c, "p" + s.pctN(), s.pct(), COLORS[0], false);
            double last = s.pct().get(s.pct().size() - 1).min();
            timeLine(c, "SLA", List.of(new RunSeriesService.Pt(s.pct().get(0).min(), s.slaMs()), new RunSeriesService.Pt(last, s.slaMs())),
                    RED, true);
            markWarmup(c, warm);
            out.add(img(c.getTitle(), c));
        }
        XYChart err = chart("Ошибки", "%", "Минуты от начала теста");
        i = 0;
        for (RunSeriesService.OpSeries s : ops) {
            Color col = COLORS[i++ % COLORS.length];
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
                new Color(0xFF9830), true);
    }

    private static void timeLine(XYChart c, String name, List<RunSeriesService.Pt> pts, Color color, boolean dashed) {
        // у ряда с единственной точкой линии нет: рисуем маркер, остальные ряды - линией без маркеров
        List<Double> x = pts.stream().map(RunSeriesService.Pt::min).toList();
        List<Double> y = pts.stream().map(RunSeriesService.Pt::v).toList();
        XYSeries s = c.addSeries(dashed || pts.isEmpty() ? name : legend(name, pts), x, y);
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
        XYChart c = new XYChartBuilder().width(1000).height(380).title(title).xAxisTitle(xTitle).yAxisTitle(yTitle).build();
        var st = c.getStyler();
        st.setLegendPosition(Styler.LegendPosition.OutsideS);
        st.setLegendLayout(Styler.LegendLayout.Vertical);
        st.setChartTitleFont(font(Font.BOLD, 15));
        st.setLegendFont(font(Font.PLAIN, 11));
        st.setAxisTitleFont(font(Font.PLAIN, 11));
        st.setAxisTickLabelsFont(font(Font.PLAIN, 10));
        st.setYAxisMin(0.0);
        st.setPlotGridLinesVisible(true);
        st.setPlotGridVerticalLinesVisible(false);
        st.setPlotGridLinesColor(GRID);
        st.setPlotGridLinesStroke(new java.awt.BasicStroke(1f));
        st.setChartBackgroundColor(BG);
        st.setPlotBackgroundColor(BG);
        st.setPlotBorderVisible(false);
        st.setChartTitleBoxVisible(false);
        st.setChartFontColor(TEXT);
        st.setAxisTickLabelsColor(TEXT);
        st.setAxisTickMarksColor(GRID);
        st.setLegendBackgroundColor(BG);
        st.setLegendBorderColor(BG);
        st.setChartPadding(12);
        return c;
    }

    private static void line(XYChart c, String name, List<Double> x, List<Double> y, Color color, boolean markers) {
        XYSeries s = c.addSeries(name, x, y);
        s.setLineColor(color);
        s.setMarkerColor(color);
        s.setMarker(markers ? SeriesMarkers.CIRCLE : SeriesMarkers.NONE);
    }

    private static Img img(String title, XYChart c) {
        try {
            byte[] png = BitmapEncoder.getBitmapBytes(c, BitmapEncoder.BitmapFormat.PNG);
            return new Img(title, "data:image/png;base64," + Base64.getEncoder().encodeToString(png));
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static double min(List<Double> v) {
        return v.stream().mapToDouble(Double::doubleValue).min().orElse(0);
    }

    private static double max(List<Double> v) {
        return v.stream().mapToDouble(Double::doubleValue).max().orElse(0);
    }
}
