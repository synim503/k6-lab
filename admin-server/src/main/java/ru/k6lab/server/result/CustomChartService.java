package ru.k6lab.server.result;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import ru.k6lab.server.metrics.PrometheusClient;
import ru.k6lab.server.run.Run;
import ru.k6lab.server.run.RunRepository;
import ru.k6lab.server.settings.SettingsService;

import java.io.IOException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * Свои графики отчёта: PromQL пользователя выполняется за время запуска. В запросе можно использовать
 * {run_id} (значение метки test_run_id) и {range} (окно для rate/increase в секундах, например 45s).
 */
@Service
public class CustomChartService {

    private static final Logger log = LoggerFactory.getLogger(CustomChartService.class);
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault());
    static final int MAX_POINTS = 150;

    private final ChartTemplateStore store;
    private final PrometheusClient prom;
    private final SettingsService settings;
    private final ChartVariableStore variables;
    private final RunRepository runs;
    private final ChartStyleService chartStyle;

    public CustomChartService(ChartTemplateStore store, PrometheusClient prom, SettingsService settings,
                              ChartVariableStore variables, RunRepository runs, ChartStyleService chartStyle) {
        this.variables = variables;
        this.store = store;
        this.prom = prom;
        this.settings = settings;
        this.runs = runs;
        this.chartStyle = chartStyle;
    }

    /** Подстановка в запрос: переменные скрипта ($имя, ${имя}), затем {run_id} (идентификатор запуска) и {range}/{range_s}/{range_ms}. */
    static String expand(String promql, long runId, long rangeSec, Map<String, String> vars) {
        String s = GrafanaDashboardImport.substitute(promql, vars == null ? Map.of() : vars);
        return s.replace("{run_id}", String.valueOf(runId))
                .replace("{range_ms}", String.valueOf(rangeSec * 1000L))
                .replace("{range_s}", String.valueOf(rangeSec))
                .replace("{range}", rangeSec + "s");
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

    /** Объединение переменных: локальные переменные графика имеют приоритет над общими переменными скрипта. */
    static Map<String, String> mergeVars(Map<String, String> scriptVars, Map<String, String> chartVars) {
        Map<String, String> out = new LinkedHashMap<>();
        if (scriptVars != null) {
            out.putAll(scriptVars);
        }
        if (chartVars != null) {
            out.putAll(chartVars);
        }
        return out;
    }

    public List<ChartRenderer.Img> charts(Run run) {
        return charts(run, 0, false);
    }

    public List<ChartRenderer.Img> charts(Run run, long warmupSec, boolean skipWarmup) {
        List<ChartTemplateStore.Template> templates = store.enabled(run.getScriptId());
        Instant start = run.getPlannedStartAt() != null ? run.getPlannedStartAt() : run.getStartedAt();
        SettingsService.Prometheus p = settings.prometheus();
        if (templates.isEmpty() || start == null || !p.configured()) {
            return List.of();
        }
        Instant end = run.getFinishedAt() != null ? run.getFinishedAt() : Instant.now();
        boolean hasWarmup = warmupSec > 0 && start.plusSeconds(warmupSec).isBefore(end);
        Instant queryStart = (skipWarmup && hasWarmup) ? start.plusSeconds(warmupSec) : start;
        double warmupMarkerMin = (!skipWarmup && hasWarmup) ? warmupSec / 60.0 : 0;
        long total = Math.max(1, end.getEpochSecond() - queryStart.getEpochSecond());
        int push = settings.pushIntervalSec();
        int step = (int) Math.max(push, (long) Math.ceil(total / (double) MAX_POINTS));
        long window = Math.max(15, 3L * push);
        List<ChartRenderer.Img> out = new ArrayList<>();
        Map<String, String> scriptVars = variables.map(run.getScriptId());
        long t0 = start.getEpochSecond();
        long minTs = queryStart.getEpochSecond();
        for (ChartTemplateStore.Template t : templates) {
            Map<String, String> vars = mergeVars(scriptVars, t.variables());
            Map<String, List<RunSeriesService.Pt>> series = new LinkedHashMap<>();
            // несколько запросов на одном графике: сбой одного не отменяет остальные
            for (ChartTemplateStore.Query q : t.queries()) {
                if (q.promql() == null || q.promql().isBlank()) {
                    continue;
                }
                try {
                    List<PrometheusClient.Series> raw = prom.rangeSeries(p, expand(q.promql(), run.getId(), window, vars), queryStart, end, step);
                    for (PrometheusClient.Series sr : raw) {
                        String label = seriesName(q.legend(), sr.labels(), sr.labelText(), raw.size());
                        List<RunSeriesService.Pt> mapped = sr.points().stream().filter(pt -> pt.ts() >= minTs)
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
                ChartRenderer.Img img = ChartRenderer.custom(title(t.name(), vars), t.unit(), series, warmupMarkerMin);
                if (img != null) {
                    out.add(img);
                }
            } catch (RuntimeException | LinkageError e) {
                log.warn("Run {}: свой график «{}» не нарисован: {}", run.getId(), t.name(), e.toString());
            }
        }
        return out;
    }

    /**
     * Строит графики по кастомным метрикам Prometheus из блока SLA/Автостопа, если для них ещё не создан отдельный график отчёта с тем же именем.
     */
    public List<ChartRenderer.Img> customCheckCharts(Run run, List<ru.k6lab.core.analysis.StepAnalysis.CustomCheck> checks,
                                                     Map<String, String> vars, long warmupSec, boolean skipWarmup) {
        if (checks == null || checks.isEmpty()) {
            return List.of();
        }
        Instant start = run.getPlannedStartAt() != null ? run.getPlannedStartAt() : run.getStartedAt();
        SettingsService.Prometheus p = settings.prometheus();
        if (start == null || !p.configured()) {
            return List.of();
        }
        java.util.Set<String> existingNames = new java.util.HashSet<>();
        if (run.getScriptId() != null) {
            for (ChartTemplateStore.Template t : store.enabled(run.getScriptId())) {
                existingNames.add(t.name().strip().toLowerCase(java.util.Locale.ROOT));
                existingNames.add(title(t.name(), mergeVars(vars, t.variables())).strip().toLowerCase(java.util.Locale.ROOT));
            }
        }
        Instant end = run.getFinishedAt() != null ? run.getFinishedAt() : Instant.now();
        boolean hasWarmup = warmupSec > 0 && start.plusSeconds(warmupSec).isBefore(end);
        Instant queryStart = (skipWarmup && hasWarmup) ? start.plusSeconds(warmupSec) : start;
        double warmupMarkerMin = (!skipWarmup && hasWarmup) ? warmupSec / 60.0 : 0;
        long total = Math.max(1, end.getEpochSecond() - queryStart.getEpochSecond());
        int push = settings.pushIntervalSec();
        int step = (int) Math.max(push, (long) Math.ceil(total / (double) MAX_POINTS));
        long window = Math.max(15, 3L * push);
        long t0 = start.getEpochSecond();
        long minTs = queryStart.getEpochSecond();
        Map<String, String> effectiveVars = vars == null ? Map.of() : vars;
        List<ChartRenderer.Img> out = new ArrayList<>();
        for (ru.k6lab.core.analysis.StepAnalysis.CustomCheck cc : checks) {
            if (cc.promql() == null || cc.promql().isBlank()) {
                continue;
            }
            String renderedTitle = title(cc.name(), effectiveVars);
            if (existingNames.contains(renderedTitle.strip().toLowerCase(java.util.Locale.ROOT))) {
                continue;
            }
            Map<String, List<RunSeriesService.Pt>> series = new LinkedHashMap<>();
            try {
                List<PrometheusClient.Series> raw = prom.rangeSeries(p, expand(cc.promql(), run.getId(), window, effectiveVars), queryStart, end, step);
                for (PrometheusClient.Series sr : raw) {
                    String label = seriesName("", sr.labels(), sr.labelText(), raw.size());
                    if (label == null || label.isBlank()) {
                        label = renderedTitle;
                    }
                    List<RunSeriesService.Pt> mapped = sr.points().stream().filter(pt -> pt.ts() >= minTs)
                            .map(pt -> new RunSeriesService.Pt((pt.ts() - t0) / 60.0, pt.value())).toList();
                    if (mapped.isEmpty()) {
                        continue;
                    }
                    String key = label;
                    for (int i = 2; series.containsKey(key); i++) {
                        key = label + " #" + i;
                    }
                    series.put(key, mapped);
                }
                if (!series.isEmpty()) {
                    String chartTitle = renderedTitle + " (SLA " + cc.formatThreshold() + ")";
                    ChartRenderer.Img img = ChartRenderer.custom(chartTitle, cc.unit() == null ? "" : cc.unit(), series, warmupMarkerMin);
                    if (img != null) {
                        out.add(img);
                    }
                }
            } catch (IOException | RuntimeException | LinkageError e) {
                log.debug("Run {}: график кастомной метрики «{}» не построен: {}", run.getId(), cc.name(), e.toString());
            }
        }
        return out;
    }

    /** Диагностика одного запроса при живом предпросмотре в конструкторе. */
    public record QueryDiag(int index, String rawPromql, String expandedPromql, String legend,
                            int seriesCount, List<String> labelKeys, String error) {
    }

    /** Результат живого предпросмотра графика (та же картинка, что попадёт в HTML/PDF/Word-отчёт). */
    public record PreviewResult(boolean ok, String title, String dataUri, String windowLabel,
                                int totalSeries, long elapsedMs, String error, List<QueryDiag> queries) {
    }

    public PreviewResult preview(long scriptId, String name, String unit, List<ChartTemplateStore.Query> queries,
                                 Long runId, Integer minutes) {
        return preview(scriptId, name, unit, queries, null, runId, minutes);
    }

    /**
     * Выполняет запросы конструктора на выбранном запуске скрипта или за последние N минут и рисует точную копию графика отчёта.
     * Локальные переменные {@code chartVars} перекрывают общие переменные скрипта.
     */
    public PreviewResult preview(long scriptId, String name, String unit, List<ChartTemplateStore.Query> queries,
                                 Map<String, String> chartVars, Long runId, Integer minutes) {
        long tStartNs = System.nanoTime();
        Map<String, String> vars = mergeVars(variables.map(scriptId), chartVars);
        String renderedTitle = title(name == null || name.isBlank() ? "Предпросмотр" : name.strip(), vars);
        int push = settings.pushIntervalSec();
        long window = Math.max(15, 3L * push);

        Instant start;
        Instant end;
        long effectiveRunId = 0;
        String windowLabel;
        if (runId != null && runId > 0) {
            Run run = runs.findById(runId).filter(r -> Long.valueOf(scriptId).equals(r.getScriptId())).orElse(null);
            if (run == null) {
                return new PreviewResult(false, renderedTitle, null, "", 0, 0, "Запуск #" + runId + " не найден для этого скрипта", List.of());
            }
            start = run.getPlannedStartAt() != null ? run.getPlannedStartAt() : run.getStartedAt();
            if (start == null) {
                return new PreviewResult(false, renderedTitle, null, "", 0, 0, "У запуска #" + runId + " ещё нет времени начала", List.of());
            }
            end = run.getFinishedAt() != null ? run.getFinishedAt() : Instant.now();
            if (!end.isAfter(start)) {
                end = start.plusSeconds(60);
            }
            effectiveRunId = run.getId();
            long total = Math.max(1, end.getEpochSecond() - start.getEpochSecond());
            int step = (int) Math.max(push, (long) Math.ceil(total / (double) MAX_POINTS));
            windowLabel = "Запуск #" + run.getId() + " (" + TIME_FMT.format(start) + "–" + TIME_FMT.format(end) + ", шаг " + step + "с)";
        } else {
            int mins = minutes == null ? 30 : Math.max(5, Math.min(1440, minutes));
            end = Instant.now();
            start = end.minusSeconds(mins * 60L);
            List<Run> recent = runs.findTop20ByScriptIdOrderByIdDesc(scriptId);
            if (!recent.isEmpty()) {
                effectiveRunId = recent.get(0).getId();
            }
            long total = Math.max(1, end.getEpochSecond() - start.getEpochSecond());
            int step = (int) Math.max(push, (long) Math.ceil(total / (double) MAX_POINTS));
            windowLabel = "Последние " + (mins >= 60 && mins % 60 == 0 ? (mins / 60) + " ч" : mins + " мин") + " (шаг " + step + "с)";
        }

        long total = Math.max(1, end.getEpochSecond() - start.getEpochSecond());
        int step = (int) Math.max(push, (long) Math.ceil(total / (double) MAX_POINTS));

        SettingsService.Prometheus p = settings.prometheus();
        List<QueryDiag> diags = new ArrayList<>();
        if (!p.configured()) {
            for (int i = 0; i < queries.size(); i++) {
                ChartTemplateStore.Query q = queries.get(i);
                String raw = q.promql() == null ? "" : q.promql().strip();
                String exp = raw.isEmpty() ? "" : expand(raw, effectiveRunId, window, vars);
                diags.add(new QueryDiag(i, raw, exp, q.legend() == null ? "" : q.legend().strip(), 0, List.of(), null));
            }
            return new PreviewResult(false, renderedTitle, null, windowLabel, 0, elapsedMs(tStartNs),
                    "Prometheus не настроен — укажите URL запросов в разделе «Настройки».", diags);
        }

        Map<String, List<RunSeriesService.Pt>> series = new LinkedHashMap<>();
        long t0 = start.getEpochSecond();
        for (int i = 0; i < queries.size(); i++) {
            ChartTemplateStore.Query q = queries.get(i);
            String raw = q.promql() == null ? "" : q.promql().strip();
            String leg = q.legend() == null ? "" : q.legend().strip();
            if (raw.isEmpty()) {
                diags.add(new QueryDiag(i, "", "", leg, 0, List.of(), "Пустой запрос"));
                continue;
            }
            String expanded = expand(raw, effectiveRunId, window, vars);
            try {
                List<PrometheusClient.Series> rawSeries = prom.rangeSeries(p, expanded, start, end, step);
                TreeSet<String> labelKeys = new TreeSet<>();
                for (PrometheusClient.Series sr : rawSeries) {
                    labelKeys.addAll(sr.labels().keySet());
                    String label = seriesName(leg, sr.labels(), sr.labelText(), rawSeries.size());
                    List<RunSeriesService.Pt> mapped = sr.points().stream().filter(pt -> pt.ts() >= t0)
                            .map(pt -> new RunSeriesService.Pt((pt.ts() - t0) / 60.0, pt.value())).toList();
                    String key = label;
                    for (int dup = 2; series.containsKey(key); dup++) {
                        key = label + " #" + dup;
                    }
                    series.put(key, mapped);
                }
                List<String> keysList = labelKeys.stream().limit(24).toList();
                diags.add(new QueryDiag(i, raw, expanded, leg, rawSeries.size(), keysList, null));
            } catch (IOException | RuntimeException e) {
                String msg = e.getMessage() != null && !e.getMessage().isBlank() ? e.getMessage() : e.getClass().getSimpleName();
                diags.add(new QueryDiag(i, raw, expanded, leg, 0, List.of(), msg));
            }
        }

        String dataUri = null;
        String renderErr = null;
        if (!series.isEmpty()) {
            try {
                ChartRenderer.TimeBase tb = chartStyle.absoluteTime()
                        ? new ChartRenderer.TimeBase(start.toEpochMilli(), end.toEpochMilli()) : null;
                ChartRenderer.Img img = ChartRenderer.withTime(tb,
                        () -> ChartRenderer.with(chartStyle.get(), () -> ChartRenderer.custom(renderedTitle, unit, series)));
                if (img != null) {
                    dataUri = img.dataUri();
                }
            } catch (RuntimeException | LinkageError e) {
                renderErr = "Не удалось нарисовать график: " + e.getMessage();
            }
        }
        return new PreviewResult(dataUri != null, renderedTitle, dataUri, windowLabel, series.size(), elapsedMs(tStartNs), renderErr, diags);
    }

    private static long elapsedMs(long startNs) {
        return Math.max(1, (System.nanoTime() - startNs) / 1_000_000L);
    }
}
