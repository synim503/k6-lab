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

    public record LabeledChart(String category, ChartRenderer.Img img, boolean included) {
        public String title() {
            return img.title();
        }

        public String dataUri() {
            return img.dataUri();
        }
    }

    /** Базовое название отчёта: «сценарий - профиль» (без профиля - только сценарий). */
    public static String defaultTitle(Run run) {
        Object profile = run.getRequest() == null ? null : run.getRequest().get("loadProfileName");
        return profile == null || profile.toString().isBlank() ? run.getScriptName() : run.getScriptName() + " - " + profile;
    }

    /** Название отчёта с учётом пользовательского заголовка из reportConfig. */
    public static String title(Run run) {
        ReportConfig cfg = ReportConfig.fromRun(run);
        if (cfg.customTitle() != null && !cfg.customTitle().isBlank()) {
            return cfg.customTitle().strip();
        }
        return defaultTitle(run);
    }

    /** Абсолютное время на графиках: abs == null - сначала из reportConfig запуска, иначе из глобальных настроек. */
    private boolean absolute(Run run, Boolean abs) {
        if (abs != null) {
            return abs;
        }
        Boolean runDef = ReportConfig.fromRun(run).defaultAbsTime();
        return runDef != null ? runDef : chartStyle.absoluteTime();
    }

    /** Обрезка прогрева на графиках: skip == null - сначала из reportConfig запуска, иначе из глобальных настроек. */
    private boolean skipWarmup(Run run, Boolean skip) {
        if (skip != null) {
            return skip;
        }
        Boolean runDef = ReportConfig.fromRun(run).defaultSkipWarmup();
        return runDef != null ? runDef : chartStyle.skipWarmup();
    }

    public static long warmupSec(RunSnapshot snap) {
        return snap != null && snap.warmup() != null && snap.warmup().sec() > 0 ? snap.warmup().sec() : 0;
    }

    private ChartStyle effectiveStyle(Run run, ChartStyle.SortBy sortBy, ChartStyle.SortDir sortDir) {
        ChartStyle s = chartStyle.get();
        ReportConfig cfg = ReportConfig.fromRun(run);
        ChartStyle.SortBy effBy = sortBy != null ? sortBy : (cfg.defaultSortBy() != null ? cfg.defaultSortBy() : s.sortBy());
        ChartStyle.SortDir effDir = sortDir != null ? sortDir : (cfg.defaultSortDir() != null ? cfg.defaultSortDir() : s.sortDir());
        if (effBy == s.sortBy() && effDir == s.sortDir()) {
            return s;
        }
        return new ChartStyle(s.theme(), s.width(), s.height(), s.legend(), s.stats(), s.maxLegend(), s.autoWidth(), effBy, effDir);
    }

    /** Полный список графиков с категорией и признаком включения в отчёт (для редактора отчёта в SPA). */
    public List<LabeledChart> allChartsForEditor(Run run, RunSnapshot snap, ResultData data, Boolean abs,
                                                 ChartStyle.SortBy sortBy, ChartStyle.SortDir sortDir, Boolean skipWarmup) {
        if (data == null || !data.prometheusOk()) {
            return List.of();
        }
        Instant start = run.getPlannedStartAt() != null ? run.getPlannedStartAt() : run.getStartedAt();
        Instant end = run.getFinishedAt() != null ? run.getFinishedAt() : Instant.now();
        ChartRenderer.TimeBase tb = absolute(run, abs) && start != null ? new ChartRenderer.TimeBase(start.toEpochMilli(), end.toEpochMilli()) : null;
        ChartStyle style = effectiveStyle(run, sortBy, sortDir);
        boolean skip = skipWarmup(run, skipWarmup);
        ReportConfig cfg = ReportConfig.fromRun(run);
        return ChartRenderer.withTime(tb, () -> ChartRenderer.with(style, () -> buildLabeledCharts(run, snap, data, skip, cfg)));
    }

    private List<ChartRenderer.Img> charts(Run run, RunSnapshot snap, ResultData data, Boolean abs,
                                           ChartStyle.SortBy sortBy, ChartStyle.SortDir sortDir, Boolean skipWarmup) {
        ReportConfig cfg = ReportConfig.fromRun(run);
        if (!cfg.show("charts")) {
            return List.of();
        }
        return allChartsForEditor(run, snap, data, abs, sortBy, sortDir, skipWarmup).stream()
                .filter(LabeledChart::included)
                .map(LabeledChart::img)
                .toList();
    }

    private List<LabeledChart> buildLabeledCharts(Run run, RunSnapshot snap, ResultData data, boolean skipWarmup, ReportConfig cfg) {
        List<LabeledChart> out = new java.util.ArrayList<>();
        boolean hasTimeCharts = false;
        try {
            for (var c : ChartRenderer.timeCharts(seriesService.series(run, snap), skipWarmup)) {
                out.add(new LabeledChart("k6", c, cfg.isChartIncluded(c.title())));
                hasTimeCharts = true;
            }
        } catch (RuntimeException | LinkageError e) {
            org.slf4j.LoggerFactory.getLogger(ReportService.class).warn("Run {}: ошибка построения временных графиков: {}", run.getId(), e.toString());
        }
        try {
            if (!hasTimeCharts || data.isStepTest()) {
                for (var c : ChartRenderer.charts(data)) {
                    out.add(new LabeledChart("steps", c, cfg.isChartIncluded(c.title())));
                }
            }
        } catch (RuntimeException | LinkageError e) {
            org.slf4j.LoggerFactory.getLogger(ReportService.class).warn("Run {}: ошибка построения ступенчатых графиков: {}", run.getId(), e.toString());
        }
        long wSec = warmupSec(snap);
        try {
            for (var c : customCharts.charts(run, wSec, skipWarmup)) {
                out.add(new LabeledChart("custom", c, cfg.isChartIncluded(c.title())));
            }
        } catch (RuntimeException | LinkageError e) {
            org.slf4j.LoggerFactory.getLogger(ReportService.class).warn("Run {}: ошибка построения пользовательских графиков: {}", run.getId(), e.toString());
        }
        try {
            for (var c : customCharts.customCheckCharts(run, results.getEffectiveCustomChecks(run, snap),
                    results.varsForRun(run, snap), wSec, skipWarmup)) {
                out.add(new LabeledChart("sla", c, cfg.isChartIncluded(c.title())));
            }
        } catch (RuntimeException | LinkageError e) {
            org.slf4j.LoggerFactory.getLogger(ReportService.class).warn("Run {}: ошибка построения графиков кастомных проверок: {}", run.getId(), e.toString());
        }
        return out;
    }

    public byte[] renderDocx(long runId) {
        return renderDocx(runId, null, null, null, null);
    }

    public byte[] renderDocx(long runId, Boolean abs) {
        return renderDocx(runId, abs, null, null, null);
    }

    public byte[] renderDocx(long runId, Boolean abs, ChartStyle.SortBy sortBy, ChartStyle.SortDir sortDir) {
        return renderDocx(runId, abs, sortBy, sortDir, null);
    }

    public byte[] renderDocx(long runId, Boolean abs, ChartStyle.SortBy sortBy, ChartStyle.SortDir sortDir, Boolean skipWarmup) {
        Run run = runService.get(runId);
        RunSnapshot snap = runService.snapshot(run);
        ResultData data = results.getOrCompute(runId);
        ReportConfig cfg = ReportConfig.fromRun(run);
        var customChecksConfig = results.getEffectiveCustomChecks(run, snap);
        ReportFormat f = new ReportFormat();
        try {
            DocxBuilder d = new DocxBuilder().title("Отчёт о нагрузочном тесте: " + title(run));
            d.muted("Запуск #" + run.getId() + " · статус: " + f.runStatus(run.getStatus().name()) + " · начало " + f.dt(run.getStartedAt())
                    + " · окончание " + f.dt(run.getFinishedAt()) + " · отчёт сформирован " + f.dt(Instant.now()));
            if (cfg.show("summary")) {
                d.h1("Итог");
                if (run.getConclusion() != null) {
                    d.bold("Вывод инженера: " + run.getConclusion());
                }
                if (run.getConclusionComment() != null && !run.getConclusionComment().isBlank()) {
                    for (String block : run.getConclusionComment().replace("\r\n", "\n").split("\n{2,}")) {
                        if (!block.isBlank()) {
                            d.text(block.strip());
                        }
                    }
                }
                if (run.getStopReason() != null) {
                    d.text("Тест остановлен: " + run.getStopReason());
                }
                if (run.isDegraded()) {
                    d.text("Во время теста терялись генераторы: ступени, на которые это повлияло, помечены как недостоверные.");
                }
                if (!data.prometheusOk() && data.error() != null) {
                    d.bold(data.error());
                }
                if (run.getError() != null) {
                    d.text("Ошибка: " + run.getError());
                }
            }
            if (cfg.show("config")) {
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
                if (!customChecksConfig.isEmpty()) {
                    d.h2("Кастомные метрики Prometheus (SLA серверов и Автостоп)");
                    List<List<String>> ccCfg = new java.util.ArrayList<>();
                    for (var cc : customChecksConfig) {
                        String role = (cc.stepSla() ? "SLA отчёта" : "") + (cc.stepSla() && cc.autoStop() ? " + " : "") + (cc.autoStop() ? "Автостоп" : "");
                        ccCfg.add(List.of(cc.name(), cc.promql(), cc.formatThreshold(), role.isEmpty() ? "—" : role));
                    }
                    d.table(List.of("Кастомная метрика Prometheus", "PromQL-запрос", "Порог SLA", "Контроль"), ccCfg, null);
                }
            }
            if (cfg.show("env")) {
                var envRows = ReportFormat.envRows(snap.env(), run.getK6Config());
                if (!envRows.isEmpty()) {
                    d.h2("Переменные запуска");
                    List<List<String>> envTable = envRows.stream()
                            .map(e -> List.of(e.scope(), e.name(), e.value() != null ? e.value() : ""))
                            .toList();
                    d.table(List.of("Где задана", "Переменная", "Значение"), envTable, null);
                }
            }
            if (cfg.show("steps")) {
                d.h1("Результаты по ступеням");
                ResultData.MaxSummary max = data.maxCapacity();
                if (max != null) {
                    d.bold("Поиск максимума: " + max.headline());
                    if (!max.detail().isBlank()) {
                        d.text(max.detail());
                    }
                    List<List<String>> summaryRows = new java.util.ArrayList<>();
                    java.util.Set<Integer> badSteps = new java.util.HashSet<>();
                    for (ResultData.StepRow s : data.steps()) {
                        if (!"SUCCESS".equals(s.status())) {
                            badSteps.add(summaryRows.size());
                        }
                        summaryRows.add(List.of(
                                "Ступень " + s.stepNo(),
                                f.n(s.levelPct(), 1) + " %",
                                "NOT_RUN".equals(s.status()) ? "—" : f.time(s.windowStart()) + "–" + f.time(s.windowEnd()),
                                f.n(s.totalTargetRate(), 1),
                                f.n(s.totalActualRate(), 1),
                                s.worstSlaText(),
                                f.n(s.maxErrorPct(), 2) + " %",
                                f.status(s.status())));
                    }
                    d.table(List.of("Ступень", "Уровень", "Окно", "План, оп/с", "Факт, оп/с", "Худший SLA", "Ошибки макс.", "Статус"), summaryRows, badSteps);
                }
                for (ResultData.StepRow s : data.steps()) {
                    d.h2("Ступень " + s.stepNo() + " — " + f.n(s.levelPct(), 1) + " % от модели: " + f.status(s.status()));
                    if (!s.reasons().isEmpty()) {
                        d.text(String.join("; ", s.reasons()));
                    }
                    if (!s.ops().isEmpty()) {
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
                    if (!s.customChecks().isEmpty()) {
                        List<List<String>> customRows = new java.util.ArrayList<>();
                        java.util.Set<Integer> badCustom = new java.util.HashSet<>();
                        for (ResultData.CustomMetricRow cm : s.customChecks()) {
                            if (!cm.ok()) {
                                badCustom.add(customRows.size());
                            }
                            String u = cm.unit() == null || cm.unit().isBlank() ? "" : " " + cm.unit();
                            String fact = cm.value() == null ? "нет данных"
                                    : f.n(cm.value(), 1) + u + (cm.worstSeries() == null || cm.worstSeries().isBlank() ? "" : " (" + cm.worstSeries() + ")");
                            String role = (cm.stepSla() ? "SLA ступени" : "") + (cm.stepSla() && cm.autoStop() ? " + " : "") + (cm.autoStop() ? "Автостоп" : "");
                            customRows.add(List.of(cm.name(), fact, cm.thresholdText(), role.isEmpty() ? "—" : role,
                                    cm.dataMissing() ? "нет данных" : (cm.ok() ? "в норме" : "нарушение")));
                        }
                        d.table(List.of("Кастомная метрика Prometheus", "Факт (худшее за ступень)", "Порог", "Контроль", "Статус"), customRows, badCustom);
                    }
                }
            }
            if (cfg.show("charts")) {
                List<ChartRenderer.Img> charts = charts(run, snap, data, abs, sortBy, sortDir, skipWarmup);
                if (!charts.isEmpty()) {
                    d.h1("Графики");
                    for (ChartRenderer.Img c : charts) {
                        d.image(c.png(), c.title());
                    }
                }
            }
            if (cfg.show("generators") && data.generators() != null && !data.generators().isEmpty()) {
                d.h1("Генераторы (справочно)");
                d.muted("Сводка k6 по каждому генератору содержит накопленные за весь тест значения; для оценки SLA не используется.");
                List<List<String>> genRows = data.generators().stream()
                        .map(g -> List.of(
                                g.generator() != null ? g.generator() : "",
                                g.status() != null ? g.status() : "",
                                g.exitCode() != null ? String.valueOf(g.exitCode()) : "—",
                                f.n(g.cpuMax(), 0),
                                f.n(g.iterations(), 0),
                                f.n(g.dropped(), 0),
                                f.n(g.httpReqs(), 0),
                                f.n(g.httpFailedRate(), 4)
                        ))
                        .toList();
                d.table(List.of("Генератор", "Статус", "Код выхода k6", "CPU макс., %", "Итераций", "Dropped", "HTTP-запросов", "Доля ошибок HTTP"), genRows, null);
            }
            return d.build();
        } catch (IOException e) {
            throw new IllegalStateException("Не удалось сформировать docx: " + e.getMessage(), e);
        }
    }

    public String renderHtml(long runId) {
        return renderHtml(runId, null, null, null, null);
    }

    public String renderHtml(long runId, Boolean abs) {
        return renderHtml(runId, abs, null, null, null);
    }

    public String renderHtml(long runId, Boolean abs, ChartStyle.SortBy sortBy, ChartStyle.SortDir sortDir) {
        return renderHtml(runId, abs, sortBy, sortDir, null);
    }

    public String renderHtml(long runId, Boolean abs, ChartStyle.SortBy sortBy, ChartStyle.SortDir sortDir, Boolean skipWarmup) {
        Run run = runService.get(runId);
        RunSnapshot snap = runService.snapshot(run);
        ResultData data = results.getOrCompute(runId);
        ReportConfig cfg = ReportConfig.fromRun(run);
        ChartStyle style = effectiveStyle(run, sortBy, sortDir);
        boolean skip = skipWarmup(run, skipWarmup);
        long wSec = warmupSec(snap);
        List<ChartRenderer.Img> charts = charts(run, snap, data, abs, sortBy, sortDir, skipWarmup);
        Context ctx = new Context(new Locale("ru"));
        ctx.setVariable("timeAbs", absolute(run, abs));
        ctx.setVariable("skipWarmup", skip);
        ctx.setVariable("hasWarmup", wSec > 0);
        ctx.setVariable("warmupMinLabel", wSec > 0 ? String.format(Locale.ROOT, "%.1f мин", wSec / 60.0) : "");
        ctx.setVariable("sortBy", style.sortBy().name());
        ctx.setVariable("sortDir", style.sortDir().name());
        ctx.setVariable("title", title(run));
        ctx.setVariable("run", run);
        ctx.setVariable("snap", snap);
        ctx.setVariable("data", data);
        ctx.setVariable("customChecksConfig", results.getEffectiveCustomChecks(run, snap));
        ctx.setVariable("charts", charts);
        ctx.setVariable("envRows", ReportFormat.envRows(snap.env(), run.getK6Config()));
        ctx.setVariable("secSummary", cfg.show("summary"));
        ctx.setVariable("secConfig", cfg.show("config"));
        ctx.setVariable("secEnv", cfg.show("env"));
        ctx.setVariable("secSteps", cfg.show("steps"));
        ctx.setVariable("secCharts", cfg.show("charts"));
        ctx.setVariable("secGenerators", cfg.show("generators"));
        ctx.setVariable("fmt", new ReportFormat());
        ctx.setVariable("generatedAt", Instant.now());
        return engine.process("reports/report", ctx);
    }

    public byte[] renderPdf(long runId) {
        return renderPdf(runId, null, null, null, null);
    }

    public byte[] renderPdf(long runId, Boolean abs) {
        return renderPdf(runId, abs, null, null, null);
    }

    public byte[] renderPdf(long runId, Boolean abs, ChartStyle.SortBy sortBy, ChartStyle.SortDir sortDir) {
        return renderPdf(runId, abs, sortBy, sortDir, null);
    }

    public byte[] renderPdf(long runId, Boolean abs, ChartStyle.SortBy sortBy, ChartStyle.SortDir sortDir, Boolean skipWarmup) {
        String html = renderHtml(runId, abs, sortBy, sortDir, skipWarmup);
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
