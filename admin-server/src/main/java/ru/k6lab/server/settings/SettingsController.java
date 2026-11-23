package ru.k6lab.server.settings;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import ru.k6lab.server.config.AppProperties;
import ru.k6lab.server.generator.Generator;
import ru.k6lab.server.generator.GeneratorRepository;
import ru.k6lab.server.agent.AgentSessionRegistry;
import ru.k6lab.server.metrics.MetricNamesService;
import ru.k6lab.server.metrics.PrometheusDiagnostics;

import java.util.List;

/** Настройки системы (только ADMIN): Prometheus и диагностика, метрики, хранение, логи агентов. */
@Controller
@RequestMapping("/settings")
public class SettingsController {

    private final SettingsService settings;
    private final PrometheusDiagnostics diagnostics;
    private final MetricNamesService names;
    private final GeneratorRepository generators;
    private final AgentSessionRegistry registry;
    private final AppProperties props;
    private final ru.k6lab.server.run.RunOrchestrator orchestrator;
    private final ru.k6lab.server.result.ChartStyleService chartStyle;

    public SettingsController(SettingsService settings, PrometheusDiagnostics diagnostics, MetricNamesService names,
                              GeneratorRepository generators, AgentSessionRegistry registry, AppProperties props,
                              ru.k6lab.server.run.RunOrchestrator orchestrator, ru.k6lab.server.result.ChartStyleService chartStyle) {
        this.chartStyle = chartStyle;
        this.settings = settings;
        this.diagnostics = diagnostics;
        this.names = names;
        this.generators = generators;
        this.registry = registry;
        this.props = props;
        this.orchestrator = orchestrator;
    }

    @GetMapping
    public String index(Model model) {
        SettingsService.Prometheus p = settings.prometheus();
        model.addAttribute("p", p);
        model.addAttribute("hasSecret", settings.hasPrometheusSecret());
        model.addAttribute("grafana", settings.get(SettingsService.GRAFANA_URL_TPL, ""));
        model.addAttribute("classicHist", settings.classicHistograms());
        model.addAttribute("pushSec", settings.pushIntervalSec());
        model.addAttribute("allowMetrics", String.join("\n", settings.allowMetrics()));
        model.addAttribute("essentialMetrics", orchestrator.essentialMetrics());
        model.addAttribute("onlyNeeded", settings.onlyNeededHistograms());
        model.addAttribute("nameTag", settings.urlNameTag());
        model.addAttribute("metricNames", names.json());
        model.addAttribute("logLines", settings.logLinesPerSecond());
        model.addAttribute("logDays", settings.logRetentionDays());
        model.addAttribute("metricsDays", settings.metricsRetentionDays());
        model.addAttribute("chartStyle", chartStyle.get());
        model.addAttribute("timeAbs", chartStyle.absoluteTime());
        model.addAttribute("skipWarmup", chartStyle.skipWarmup());
        model.addAttribute("agentPort", props.getAgentPort());
        model.addAttribute("requireDiagnostics", props.isRequireDiagnostics());
        List<Generator> connected = generators.findAll().stream().filter(g -> registry.isConnected(g.getId())).toList();
        model.addAttribute("connected", connected);
        addDiag(model);
        return "settings/index";
    }

    private void addDiag(Model model) {
        model.addAttribute("diagRunning", diagnostics.isRunning());
        model.addAttribute("diagProgress", diagnostics.progress());
        model.addAttribute("diagLast", diagnostics.last().orElse(null));
        model.addAttribute("diagPassed", diagnostics.passed());
    }

    @PostMapping("/prometheus")
    public String savePrometheus(@RequestParam String rwUrl, @RequestParam String queryUrl,
                                 @RequestParam(defaultValue = "NONE") SettingsService.AuthType authType,
                                 @RequestParam(required = false) String user, @RequestParam(required = false) String secret,
                                 @RequestParam(required = false) String grafana,
                                 @RequestParam(defaultValue = "CLASSIC") String histMode,
                                 @RequestParam(required = false) Integer pushSec, RedirectAttributes ra) {
        settings.savePrometheus(rwUrl, queryUrl, authType, user, secret, grafana);
        settings.set(SettingsService.HISTOGRAM_MODE, "NATIVE".equals(histMode) ? "NATIVE" : "CLASSIC");
        if (pushSec != null) {
            settings.set(SettingsService.PUSH_INTERVAL_SEC, String.valueOf(Math.max(5, Math.min(60, pushSec))));
        }
        ra.addFlashAttribute("info", "Параметры Prometheus сохранены. Выполните диагностику.");
        return "redirect:/settings";
    }

    /** Белый список метрик: что агенты отправляют в Prometheus. Метрики, нужные самой админке, добавляются автоматически. */
    @PostMapping("/allow-metrics")
    public String saveAllowMetrics(@RequestParam(required = false) String allowMetrics,
                                   @RequestParam(defaultValue = "false") boolean onlyNeeded,
                                   @RequestParam(defaultValue = "false") boolean nameTag, RedirectAttributes ra) {
        List<String> patterns = SettingsService.parseMetricMasks(allowMetrics);
        for (String pat : patterns) {
            if (!pat.matches("[A-Za-z0-9_:*]+")) {
                ra.addFlashAttribute("error", "Недопустимая маска «" + pat + "»: имя метрики состоит из букв, цифр, _ и :, допускается *");
                return "redirect:/settings#allow";
            }
        }
        settings.set(SettingsService.ALLOW_METRICS, String.join(System.lineSeparator(), patterns));
        settings.set(SettingsService.ONLY_NEEDED_HISTOGRAMS, String.valueOf(onlyNeeded));
        settings.set(SettingsService.URL_NAME_TAG, String.valueOf(nameTag));
        ra.addFlashAttribute("info", patterns.isEmpty() ? "Белый список пуст: агенты отправляют все метрики"
                : "Агенты будут отправлять только: " + String.join(", ", patterns) + " (и метрики, нужные админке). Применится к следующим запускам.");
        return "redirect:/settings#allow";
    }

    @PostMapping("/diagnostics")
    public String runDiagnostics(@RequestParam long generatorId, RedirectAttributes ra) {
        if (!diagnostics.start(generatorId)) {
            ra.addFlashAttribute("error", "Диагностика уже выполняется");
        }
        return "redirect:/settings#diag";
    }

    /** htmx: панель диагностики, опрашивается пока идёт прогон. */
    @GetMapping("/diagnostics/panel")
    public String panel(Model model) {
        addDiag(model);
        return "settings/_diag :: panel";
    }

    @PostMapping("/metric-names")
    public String saveNames(@RequestParam String json, RedirectAttributes ra) {
        try {
            names.save(json);
            ra.addFlashAttribute("info", "Имена метрик сохранены");
        } catch (IllegalArgumentException e) {
            ra.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/settings";
    }

    /** Оформление графиков отчёта: тема, размеры, легенда, сортировка легенды, отображение прогрева. */
    @PostMapping("/report-charts")
    public String saveReportCharts(@RequestParam String theme, @RequestParam int width, @RequestParam int height, @RequestParam String legend,
                                   @RequestParam(defaultValue = "false") boolean stats, @RequestParam int maxLegend,
                                   @RequestParam(defaultValue = "rel") String timeAxis,
                                   @RequestParam(defaultValue = "false") boolean autoWidth,
                                   @RequestParam(defaultValue = "NONE") String sortBy,
                                   @RequestParam(defaultValue = "DESC") String sortDir,
                                   @RequestParam(defaultValue = "false") boolean skipWarmup, RedirectAttributes ra) {
        chartStyle.save(theme, width, height, legend, stats, maxLegend, "abs".equals(timeAxis), autoWidth, sortBy, sortDir, skipWarmup);
        ra.addFlashAttribute("info", "Оформление графиков отчёта сохранено");
        return "redirect:/settings#charts";
    }

    @PostMapping("/report-charts/reset")
    public String resetReportCharts(RedirectAttributes ra) {
        chartStyle.reset();
        ra.addFlashAttribute("info", "Оформление графиков сброшено к значениям по умолчанию");
        return "redirect:/settings#charts";
    }

    /** Пример графика (на тестовых данных): поддерживает передачу несохранённых параметров в query-строке для живого предпросмотра. */
    @GetMapping(value = "/report-charts/preview.png", produces = org.springframework.http.MediaType.IMAGE_PNG_VALUE)
    public org.springframework.http.ResponseEntity<byte[]> previewReportChart(
            @RequestParam(required = false) String theme,
            @RequestParam(required = false) Integer width,
            @RequestParam(required = false) Integer height,
            @RequestParam(required = false) String legend,
            @RequestParam(required = false) Boolean stats,
            @RequestParam(required = false) Integer maxLegend,
            @RequestParam(required = false) String timeAxis,
            @RequestParam(required = false) Boolean autoWidth,
            @RequestParam(required = false) String sortBy,
            @RequestParam(required = false) String sortDir,
            @RequestParam(required = false) Boolean skipWarmup,
            @RequestParam(required = false) Integer seriesCount) {
        ru.k6lab.server.result.ChartStyle saved = chartStyle.get();
        ru.k6lab.server.result.ChartStyle effective = ru.k6lab.server.result.ChartStyle.of(
                theme != null ? theme : saved.theme().name(),
                width != null ? width : saved.width(),
                height != null ? height : saved.height(),
                legend != null ? legend : saved.legend().name(),
                stats != null ? stats : saved.stats(),
                maxLegend != null ? maxLegend : saved.maxLegend(),
                autoWidth != null ? autoWidth : saved.autoWidth(),
                sortBy != null ? sortBy : saved.sortBy().name(),
                sortDir != null ? sortDir : saved.sortDir().name());
        boolean abs = timeAxis != null ? "abs".equals(timeAxis) : chartStyle.absoluteTime();
        boolean skip = skipWarmup != null ? skipWarmup : chartStyle.skipWarmup();
        double previewWarmupMin = 3.0;
        int count = seriesCount == null ? 6 : Math.max(1, Math.min(20, seriesCount));
        java.util.Map<String, java.util.List<ru.k6lab.server.result.RunSeriesService.Pt>> series = new java.util.LinkedHashMap<>();
        for (int s = 1; s <= count; s++) {
            java.util.List<ru.k6lab.server.result.RunSeriesService.Pt> pts = new java.util.ArrayList<>();
            for (int i = 0; i <= 40; i++) {
                double min = i * 0.5;
                if (skip && min < previewWarmupMin) {
                    continue;
                }
                // разные профили (пик в начале / стабильно высокий / рост к концу), чтобы сортировка по Max / Mean / Last давала разный порядок
                double v = switch (s % 4) {
                    case 1 -> (i == 10 ? 96.0 : 18.0 + 6 * Math.sin(i / 3.0)) + (s / 4) * 3;   // высокий Max, низкие Mean/Last
                    case 2 -> 56.0 + 8 * Math.sin(i / 4.0 + s) - i * 0.25 + (s / 4) * 2;       // высокий Mean, средний Last
                    case 3 -> 14.0 + i * 1.75 + 4 * Math.sin(i / 3.0) + (s / 4) * 2;           // высокий Last, средний Mean
                    default -> 26.0 + 10 * Math.sin(i / 4.0 + s) + i * 0.4 + (s / 4) * 2;
                };
                pts.add(new ru.k6lab.server.result.RunSeriesService.Pt(min, Math.max(1.0, v)));
            }
            series.put(String.format("omltd-app-%02d.example:9100", s), pts);
        }
        long endMs = System.currentTimeMillis();
        long originMs = endMs - 20 * 60_000L;
        ru.k6lab.server.result.ChartRenderer.TimeBase tb = abs ? new ru.k6lab.server.result.ChartRenderer.TimeBase(originMs, endMs) : null;
        byte[] png = ru.k6lab.server.result.ChartRenderer.withTime(tb,
                () -> ru.k6lab.server.result.ChartRenderer.with(effective,
                        () -> ru.k6lab.server.result.ChartRenderer.custom("Пример: утилизация CPU", "%", series, skip ? 0 : previewWarmupMin))).png();
        return org.springframework.http.ResponseEntity.ok()
                .cacheControl(org.springframework.http.CacheControl.noStore())
                .body(png);
    }

    @PostMapping("/limits")
    public String saveLimits(@RequestParam int logLines, @RequestParam int logDays, @RequestParam int metricsDays, RedirectAttributes ra) {
        settings.set(SettingsService.LOG_LINES_PER_SEC, String.valueOf(Math.max(1, logLines)));
        settings.set(SettingsService.RETENTION_LOG_DAYS, String.valueOf(Math.max(1, logDays)));
        settings.set(SettingsService.RETENTION_METRICS_DAYS, String.valueOf(Math.max(1, metricsDays)));
        ra.addFlashAttribute("info", "Сохранено");
        return "redirect:/settings";
    }
}
