package ru.k6lab.server.result;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import ru.k6lab.server.metrics.PrometheusClient;
import ru.k6lab.server.run.Run;
import ru.k6lab.server.settings.SettingsService;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Свои графики отчёта: PromQL пользователя выполняется за время запуска. В запросе можно использовать
 * {run_id} (значение метки test_run_id) и {range} (окно для rate/increase в секундах, например 45s).
 */
@Service
public class CustomChartService {

    private static final Logger log = LoggerFactory.getLogger(CustomChartService.class);
    static final int MAX_POINTS = 150;

    private final ChartTemplateStore store;
    private final PrometheusClient prom;
    private final SettingsService settings;
    private final ChartVariableStore variables;

    public CustomChartService(ChartTemplateStore store, PrometheusClient prom, SettingsService settings, ChartVariableStore variables) {
        this.variables = variables;
        this.store = store;
        this.prom = prom;
        this.settings = settings;
    }

    /** Подстановка в запрос: переменные скрипта ($имя, ${имя}), затем {run_id} (идентификатор запуска) и {range} (окно rate/increase). */
    static String expand(String promql, long runId, long rangeSec, Map<String, String> vars) {
        String s = GrafanaDashboardImport.substitute(promql, vars == null ? Map.of() : vars);
        return s.replace("{run_id}", String.valueOf(runId)).replace("{range}", rangeSec + "s");
    }

    /**
     * Подпись ряда. Пусто - метки результата. Если в подписи есть {{метка}} - как в Grafana, подставляется значение метки
     * (нет такой метки - пусто). Иначе подпись как есть, а при нескольких рядах к ней добавляются метки.
     */
    static String seriesName(String legend, Map<String, String> labels, String labelText, int seriesInQuery) {
        if (legend == null || legend.isBlank()) {
            return labelText;
        }
        if (legend.contains("{{")) {
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("\\{\\{\\s*([A-Za-z_][A-Za-z0-9_]*)\\s*}}").matcher(legend);
            StringBuilder sb = new StringBuilder();
            while (m.find()) {
                m.appendReplacement(sb, java.util.regex.Matcher.quoteReplacement(labels.getOrDefault(m.group(1), "")));
            }
            m.appendTail(sb);
            String s = sb.toString().strip();
            return s.isEmpty() ? labelText : s;
        }
        return seriesInQuery > 1 && !labels.isEmpty() ? legend.strip() + " " + labelText : legend.strip();
    }

    /** Заголовок графика: переменные ($job) заменяются значениями; «любое» (.*) - словом «все», служебные значения ({run_id}) не подставляются. */
    static String title(String name, Map<String, String> vars) {
        Map<String, String> shown = new LinkedHashMap<>();
        vars.forEach((k, v) -> {
            if (v != null && !v.contains("{")) {
                shown.put(k, ".*".equals(v) ? "все" : v);
            }
        });
        return GrafanaDashboardImport.substitute(name, shown);
    }

    public List<ChartRenderer.Img> charts(Run run) {
        List<ChartTemplateStore.Template> templates = store.enabled(run.getScriptId());
        Instant start = run.getPlannedStartAt() != null ? run.getPlannedStartAt() : run.getStartedAt();
        SettingsService.Prometheus p = settings.prometheus();
        if (templates.isEmpty() || start == null || !p.configured()) {
            return List.of();
        }
        Instant end = run.getFinishedAt() != null ? run.getFinishedAt() : Instant.now();
        long total = Math.max(1, end.getEpochSecond() - start.getEpochSecond());
        int push = settings.pushIntervalSec();
        int step = (int) Math.max(push, (long) Math.ceil(total / (double) MAX_POINTS));
        long window = Math.max(15, 3L * push);
        List<ChartRenderer.Img> out = new ArrayList<>();
        Map<String, String> vars = variables.map(run.getScriptId());
        long t0 = start.getEpochSecond();
        for (ChartTemplateStore.Template t : templates) {
            Map<String, List<RunSeriesService.Pt>> series = new LinkedHashMap<>();
            // несколько запросов на одном графике: сбой одного не отменяет остальные
            for (ChartTemplateStore.Query q : t.queries()) {
                if (q.promql() == null || q.promql().isBlank()) {
                    continue;
                }
                try {
                    List<PrometheusClient.Series> raw = prom.rangeSeries(p, expand(q.promql(), run.getId(), window, vars), start, end, step);
                    for (PrometheusClient.Series sr : raw) {
                        String label = seriesName(q.legend(), sr.labels(), sr.labelText(), raw.size());
                        List<RunSeriesService.Pt> mapped = sr.points().stream().filter(pt -> pt.ts() >= t0)
                                .map(pt -> new RunSeriesService.Pt((pt.ts() - t0) / 60.0, pt.value())).toList();
                        String key = label;
                        for (int i = 2; series.containsKey(key); i++) {
                            key = label + " #" + i;
                        }
                        series.put(key, mapped);
                    }
                } catch (IOException | RuntimeException e) {
                    log.warn("Run {}: запрос свого графика «{}» не выполнен: {}", run.getId(), t.name(), e.toString());
                }
            }
            try {
                ChartRenderer.Img img = ChartRenderer.custom(title(t.name(), vars), t.unit(), series);
                if (img != null) {
                    out.add(img);
                }
            } catch (RuntimeException | LinkageError e) {
                log.warn("Run {}: свой график «{}» не нарисован: {}", run.getId(), t.name(), e.toString());
            }
        }
        return out;
    }
}
