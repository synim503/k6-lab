package ru.k6lab.server.api;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import ru.k6lab.server.metrics.PrometheusClient;
import ru.k6lab.server.result.ChartVariableStore;
import ru.k6lab.server.result.CustomChartService;
import ru.k6lab.server.run.Run;
import ru.k6lab.server.run.RunService;
import ru.k6lab.server.settings.SettingsService;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Глобальный REST API автодополнения и живой проверки PromQL для всех разделов интерфейса. */
@RestController
@RequestMapping("/api/v1/prometheus")
public class PrometheusApiController {

    private final PrometheusClient prom;
    private final SettingsService settings;
    private final ChartVariableStore variables;
    private final RunService runs;

    public PrometheusApiController(PrometheusClient prom, SettingsService settings,
                                   ChartVariableStore variables, RunService runs) {
        this.prom = prom;
        this.settings = settings;
        this.variables = variables;
        this.runs = runs;
    }

    public record TestQueryPayload(String promql, Long scriptId, Long rangeSec, Map<String, String> vars) {
    }

    @GetMapping("/metrics")
    public Map<String, Object> metrics() {
        var p = settings.prometheus();
        if (!p.configured()) {
            return Map.of("ok", false, "configured", false, "metrics", List.of(), "error", "Prometheus не настроен");
        }
        try {
            List<String> names = prom.metricNames(p).stream().sorted().limit(5000).toList();
            return Map.of("ok", true, "configured", true, "metrics", names);
        } catch (IOException e) {
            return Map.of("ok", false, "configured", true, "metrics", List.of(), "error", e.getMessage());
        }
    }

    @GetMapping("/labels")
    public Map<String, Object> labels(@RequestParam(required = false) String metric) {
        var p = settings.prometheus();
        if (!p.configured()) {
            return Map.of("ok", false, "configured", false, "labels", List.of());
        }
        try {
            List<String> names = prom.labelNames(p, metric).stream().sorted().limit(1000).toList();
            return Map.of("ok", true, "configured", true, "labels", names);
        } catch (IOException e) {
            return Map.of("ok", false, "configured", true, "labels", List.of(), "error", e.getMessage());
        }
    }

    @PostMapping("/test-query")
    public Map<String, Object> testQuery(@RequestBody TestQueryPayload req) {
        String raw = req != null && req.promql() != null ? req.promql().strip() : "";
        if (raw.isEmpty()) {
            return Map.of("ok", false, "error", "Введите выражение PromQL");
        }
        var p = settings.prometheus();
        if (!p.configured()) {
            return Map.of("ok", false, "error", "URL запросов Prometheus не настроен в Администрирование → Настройки");
        }
        Map<String, String> mergedVars = new LinkedHashMap<>();
        long runId = 0L;
        if (req.scriptId() != null) {
            mergedVars.putAll(variables.map(req.scriptId()));
            List<Run> recent = runs.search(req.scriptId(), null, 1);
            if (!recent.isEmpty()) {
                runId = recent.get(0).getId();
            }
        }
        if (req.vars() != null) {
            req.vars().forEach((k, v) -> {
                if (k != null && v != null) {
                    mergedVars.put(k, v);
                }
            });
        }
        long rangeSec = req.rangeSec() != null && req.rangeSec() > 0 ? req.rangeSec() : 60L;
        String expanded = CustomChartService.expandQuery(raw, runId, rangeSec, mergedVars);
        try {
            List<PrometheusClient.Sample> samples = prom.query(p, expanded, Instant.now());
            List<Map<String, Object>> outSamples = new ArrayList<>(Math.min(samples.size(), 25));
            for (int i = 0; i < Math.min(samples.size(), 25); i++) {
                PrometheusClient.Sample s = samples.get(i);
                Map<String, Object> sm = new LinkedHashMap<>();
                sm.put("labels", s.labels());
                sm.put("value", Double.isFinite(s.value()) ? s.value() : null);
                outSamples.add(sm);
            }
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("ok", true);
            out.put("query", expanded);
            out.put("count", samples.size());
            out.put("samples", outSamples);
            return out;
        } catch (IOException e) {
            return Map.of("ok", false, "query", expanded, "error", e.getMessage() != null ? e.getMessage() : "Ошибка запроса Prometheus");
        }
    }
}
