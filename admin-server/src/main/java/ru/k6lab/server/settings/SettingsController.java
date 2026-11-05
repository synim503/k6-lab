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

    public SettingsController(SettingsService settings, PrometheusDiagnostics diagnostics, MetricNamesService names,
                              GeneratorRepository generators, AgentSessionRegistry registry, AppProperties props) {
        this.settings = settings;
        this.diagnostics = diagnostics;
        this.names = names;
        this.generators = generators;
        this.registry = registry;
        this.props = props;
    }

    @GetMapping
    public String index(Model model) {
        SettingsService.Prometheus p = settings.prometheus();
        model.addAttribute("p", p);
        model.addAttribute("hasSecret", settings.hasPrometheusSecret());
        model.addAttribute("grafana", settings.get(SettingsService.GRAFANA_URL_TPL, ""));
        model.addAttribute("classicHist", settings.classicHistograms());
        model.addAttribute("pushSec", settings.pushIntervalSec());
        model.addAttribute("dropMetrics", String.join("\n", settings.dropMetrics()));
        model.addAttribute("onlyNeeded", settings.onlyNeededHistograms());
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

    /** Какие метрики агент не отправляет в Prometheus. Метрики, на которых строятся SLA и графики админки, отбросить нельзя. */
    @PostMapping("/drop-metrics")
    public String saveDropMetrics(@RequestParam(required = false) String dropMetrics,
                                  @RequestParam(defaultValue = "false") boolean onlyNeeded, RedirectAttributes ra) {
        List<String> patterns = SettingsService.parseDropMetrics(dropMetrics);
        for (String pat : patterns) {
            if (!pat.matches("[A-Za-z0-9_:*]+")) {
                ra.addFlashAttribute("error", "Недопустимая маска «" + pat + "»: имя метрики состоит из букв, цифр, _ и :, допускается *");
                return "redirect:/settings#drop";
            }
            String essential = essentialMetric(pat);
            if (essential != null) {
                ra.addFlashAttribute("error", "Маска «" + pat + "» отбросила бы «" + essential + "» — на ней строятся SLA и графики. Уточните маску.");
                return "redirect:/settings#drop";
            }
        }
        settings.set(SettingsService.DROP_METRICS, String.join("\n", patterns));
        settings.set(SettingsService.ONLY_NEEDED_HISTOGRAMS, String.valueOf(onlyNeeded));
        ra.addFlashAttribute("info", patterns.isEmpty() ? "Список отбрасываемых метрик пуст" : "Агенты не будут отправлять: " + String.join(", ", patterns)
                + ". Применится к следующим запускам.");
        return "redirect:/settings#drop";
    }

    /** Имя нужной админке метрики (или её серии _bucket/_count/_sum), которую задевает маска; null - не задевает. */
    private String essentialMetric(String pattern) {
        ru.k6lab.core.prom.PromQueries.Names n = names.names();
        for (String base : List.of(n.opDuration(), n.iterationDuration(), n.iterations(), n.dropped(), n.opCount(), n.opErrors(),
                n.httpReqFailed(), n.checks(), n.vus())) {
            for (String suffix : List.of("", "_bucket", "_count", "_sum")) {
                if (SettingsService.globMatches(pattern, base + suffix)) {
                    return base + suffix;
                }
            }
        }
        return null;
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
