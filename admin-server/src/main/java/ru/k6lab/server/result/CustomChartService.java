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

    public CustomChartService(ChartTemplateStore store, PrometheusClient prom, SettingsService settings) {
        this.store = store;
        this.prom = prom;
        this.settings = settings;
    }

    /** Подстановка параметров запуска в запрос. */
    static String expand(String promql, long runId, long rangeSec) {
        return promql.replace("{run_id}", String.valueOf(runId)).replace("{range}", rangeSec + "s");
    }

    /** Подпись ряда: задана легенда - она (если запрос вернул несколько рядов, к ней добавляются метки); иначе метки результата. */
    static String seriesName(String legend, String labels, int seriesInQuery) {
        if (legend == null || legend.isBlank()) {
            return labels;
        }
        return seriesInQuery > 1 && !"значение".equals(labels) ? legend.strip() + " " + labels : legend.strip();
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
        long t0 = start.getEpochSecond();
        for (ChartTemplateStore.Template t : templates) {
            Map<String, List<RunSeriesService.Pt>> series = new LinkedHashMap<>();
            // несколько запросов на одном графике: сбой одного не отменяет остальные
            for (ChartTemplateStore.Query q : t.queries()) {
                if (q.promql() == null || q.promql().isBlank()) {
                    continue;
                }
                try {
                    Map<String, List<PrometheusClient.Point>> raw = prom.rangeAll(p, expand(q.promql(), run.getId(), window), start, end, step);
                    raw.forEach((name, pts) -> {
                        String label = seriesName(q.legend(), name, raw.size());
                        List<RunSeriesService.Pt> mapped = pts.stream().filter(pt -> pt.ts() >= t0)
                                .map(pt -> new RunSeriesService.Pt((pt.ts() - t0) / 60.0, pt.value())).toList();
                        String key = label;
                        for (int i = 2; series.containsKey(key); i++) {
                            key = label + " #" + i;
                        }
                        series.put(key, mapped);
                    });
                } catch (IOException | RuntimeException e) {
                    log.warn("Run {}: запрос свого графика «{}» не выполнен: {}", run.getId(), t.name(), e.toString());
                }
            }
            try {
                ChartRenderer.Img img = ChartRenderer.custom(t.name(), t.unit(), series);
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
