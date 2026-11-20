package ru.k6lab.server.result;

import com.openhtmltopdf.pdfboxout.PdfRendererBuilder;
import org.jsoup.Jsoup;
import org.jsoup.helper.W3CDom;
import org.springframework.stereotype.Service;
import org.thymeleaf.context.Context;
import org.thymeleaf.spring6.SpringTemplateEngine;
import ru.k6lab.server.run.Run;
import ru.k6lab.server.run.RunService;
import ru.k6lab.server.run.RunSnapshot;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.util.List;
import java.util.Locale;

/** HTML (самодостаточный) и PDF отчёт из одного Thymeleaf-шаблона reports/report. */
@Service
public class ReportService {

    private final SpringTemplateEngine engine;
    private final RunService runService;
    private final StepResultService results;
    private final RunSeriesService seriesService;
    private final CustomChartService customCharts;
    private final ChartStyleService chartStyle;

    public ReportService(SpringTemplateEngine engine, RunService runService, StepResultService results, RunSeriesService seriesService,
                         CustomChartService customCharts, ChartStyleService chartStyle) {
        this.chartStyle = chartStyle;
        this.engine = engine;
        this.runService = runService;
        this.results = results;
        this.seriesService = seriesService;
        this.customCharts = customCharts;
    }

    /** Название отчёта: «сценарий - профиль» (без профиля - только сценарий). */
    public static String title(Run run) {
        Object profile = run.getRequest() == null ? null : run.getRequest().get("loadProfileName");
        return profile == null || profile.toString().isBlank() ? run.getScriptName() : run.getScriptName() + " - " + profile;
    }

    /** Абсолютное время на графиках: abs == null - как в настройках. */
    private boolean absolute(Boolean abs) {
        return abs != null ? abs : chartStyle.absoluteTime();
    }

    private List<ChartRenderer.Img> charts(Run run, RunSnapshot snap, ResultData data, Boolean abs) {
        Instant start = run.getPlannedStartAt() != null ? run.getPlannedStartAt() : run.getStartedAt();
        Instant end = run.getFinishedAt() != null ? run.getFinishedAt() : Instant.now();
        ChartRenderer.TimeBase tb = absolute(abs) && start != null ? new ChartRenderer.TimeBase(start.toEpochMilli(), end.toEpochMilli()) : null;
        return ChartRenderer.withTime(tb, () -> ChartRenderer.with(chartStyle.get(), () -> buildCharts(run, snap, data)));
    }

    private List<ChartRenderer.Img> buildCharts(Run run, RunSnapshot snap, ResultData data) {
        List<ChartRenderer.Img> charts = List.of();
        try {
            // графики по времени от начала теста; если рядов нет - по ступеням (имеют смысл при двух и более ступенях)
            charts = ChartRenderer.timeCharts(seriesService.series(run, snap));
            if (charts.isEmpty()) {
                charts = ChartRenderer.charts(data);
            }
            List<ChartRenderer.Img> custom = customCharts.charts(run);
            if (!custom.isEmpty()) {
                charts = new java.util.ArrayList<>(charts);
                charts.addAll(custom);
            }
        } catch (RuntimeException | LinkageError e) {
            // графики - украшение: без них отчёт всё равно полезен (например, нет AWT на сервере)
        }
        return charts;
    }

    public byte[] renderDocx(long runId) {
        return renderDocx(runId, null);
    }

    public byte[] renderDocx(long runId, Boolean abs) {
        Run run = runService.get(runId);
        RunSnapshot snap = runService.snapshot(run);
        ResultData data = results.load(runId).orElseGet(() -> results.compute(runId));
        ReportFormat f = new ReportFormat();
        try {
            DocxBuilder d = new DocxBuilder().title("Отчёт о нагрузочном тесте: " + title(run));
            d.muted("Запуск #" + run.getId() + " · статус: " + f.runStatus(run.getStatus().name()) + " · начало " + f.dt(run.getStartedAt())
                    + " · окончание " + f.dt(run.getFinishedAt()) + " · отчёт сформирован " + f.dt(Instant.now()));
            d.h1("Итог");
            if (run.getConclusion() != null) {
                d.text("Вывод инженера: " + run.getConclusion());
            }
            if (run.getConclusionComment() != null) {
                d.text(run.getConclusionComment());
            }
            if (run.getStopReason() != null) {
                d.text("Тест остановлен: " + run.getStopReason());
            }
            if (!data.prometheusOk() && data.error() != null) {
                d.bold(data.error());
            }
            d.h1("Конфигурация");
            d.text("Скрипт: " + snap.script().name() + " v" + snap.script().versionNumber());
            d.text("Генераторы: " + String.join(", ", snap.generators().stream().map(g -> g.name() + " (вес " + g.weight() + ")").toList()));
            List<List<String>> ops = new java.util.ArrayList<>();
            for (var o : snap.operations()) {
                if (o.enabled()) {
                    ops.add(List.of(o.name(), String.format(Locale.ROOT, "%.3f", o.ratePerSec()),
                            "p" + o.slaPercentile() + " ≤ " + Math.round(o.slaThresholdMs()) + " мс", String.format(Locale.ROOT, "%.2f", o.maxErrorPct()) + " %"));
                }
            }
            d.table(List.of("Операция", "Интенсивность, оп/с", "SLA", "Допустимые ошибки"), ops, null);
            d.h1("Результаты по ступеням");
            for (ResultData.StepRow s : data.steps()) {
                d.h2("Ступень " + s.stepNo() + " — " + f.n(s.levelPct(), 1) + " % от модели: " + f.status(s.status()));
                if (!s.reasons().isEmpty()) {
                    d.text(String.join("; ", s.reasons()));
                }
                if (s.ops().isEmpty()) {
                    continue;
                }
                List<List<String>> rows = new java.util.ArrayList<>();
                java.util.Set<Integer> bad = new java.util.HashSet<>();
                for (ResultData.OpRow o : s.ops()) {
                    if (!o.ok()) {
                        bad.add(rows.size());
                    }
                    rows.add(List.of(o.operation(), f.n(o.targetRate(), 2), f.n(o.actualRate(), 2), f.n(o.p50(), 0), f.n(o.p90(), 0),
                            f.n(o.p95(), 0), f.n(o.p99(), 0), "p" + o.slaPercentile() + " ≤ " + Math.round(o.slaMs()),
                            f.n(o.errorPct(), 2) + " / " + String.format(Locale.ROOT, "%.2f", o.maxErrorPct()), f.n(o.dropped(), 0)));
                }
                d.table(List.of("Операция", "План, оп/с", "Факт, оп/с", "p50, мс", "p90, мс", "p95, мс", "p99, мс", "SLA", "Ошибки, %", "Dropped"), rows, bad);
            }
            List<ChartRenderer.Img> charts = charts(run, snap, data, abs);
            if (!charts.isEmpty()) {
                d.h1("Графики");
                for (ChartRenderer.Img c : charts) {
                    d.image(c.png(), c.title());
                }
            }
            return d.build();
        } catch (IOException e) {
            throw new IllegalStateException("Не удалось сформировать docx: " + e.getMessage(), e);
        }
    }

    public String renderHtml(long runId) {
        return renderHtml(runId, null);
    }

    public String renderHtml(long runId, Boolean abs) {
        Run run = runService.get(runId);
        RunSnapshot snap = runService.snapshot(run);
        ResultData data = results.load(runId).orElseGet(() -> results.compute(runId));
        List<ChartRenderer.Img> charts = charts(run, snap, data, abs);
        Context ctx = new Context(new Locale("ru"));
        ctx.setVariable("timeAbs", absolute(abs));
        ctx.setVariable("title", title(run));
        ctx.setVariable("run", run);
        ctx.setVariable("snap", snap);
        ctx.setVariable("data", data);
        ctx.setVariable("charts", charts);
        ctx.setVariable("envRows", ReportFormat.envRows(snap.env(), run.getK6Config()));
        ctx.setVariable("fmt", new ReportFormat());
        ctx.setVariable("generatedAt", Instant.now());
        return engine.process("reports/report", ctx);
    }

    public byte[] renderPdf(long runId) {
        return renderPdf(runId, null);
    }

    public byte[] renderPdf(long runId, Boolean abs) {
        String html = renderHtml(runId, abs);
        var jsoup = Jsoup.parse(html);
        org.w3c.dom.Document doc = new W3CDom().fromJsoup(jsoup);
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            PdfRendererBuilder b = new PdfRendererBuilder();
            b.useFastMode();
            b.useFont(() -> font("DejaVuSans.ttf"), "DejaVu Sans", 400, PdfRendererBuilder.FontStyle.NORMAL, true);
            b.useFont(() -> font("DejaVuSans-Bold.ttf"), "DejaVu Sans", 700, PdfRendererBuilder.FontStyle.NORMAL, true);
            b.useFont(() -> font("DejaVuSans-Oblique.ttf"), "DejaVu Sans", 400, PdfRendererBuilder.FontStyle.ITALIC, true);
            b.withW3cDocument(doc, "http://localhost/");
            b.toStream(out);
            b.run();
            return out.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("Не удалось сформировать PDF: " + e.getMessage(), e);
        } catch (Exception e) {
            throw new IllegalStateException("Не удалось сформировать PDF: " + e.getMessage(), e);
        }
    }

    private static InputStream font(String name) {
        return ReportService.class.getResourceAsStream("/fonts/" + name);
    }
}
