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

    public SettingsController(SettingsService settings, PrometheusDiagnostics diagnostics, MetricNamesService names,
                              GeneratorRepository generators, AgentSessionRegistry registry, AppProperties props,
                              ru.k6lab.server.run.RunOrchestrator orchestrator) {
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

    @PostMapping("/limits")
    public String saveLimits(@RequestParam int logLines, @RequestParam int logDays, @RequestParam int metricsDays, RedirectAttributes ra) {
        settings.set(SettingsService.LOG_LINES_PER_SEC, String.valueOf(Math.max(1, logLines)));
        settings.set(SettingsService.RETENTION_LOG_DAYS, String.valueOf(Math.max(1, logDays)));
        settings.set(SettingsService.RETENTION_METRICS_DAYS, String.valueOf(Math.max(1, metricsDays)));
        ra.addFlashAttribute("info", "Сохранено");
        return "redirect:/settings";
    }
}
