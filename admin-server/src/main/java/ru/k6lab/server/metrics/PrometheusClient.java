package ru.k6lab.server.metrics;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import ru.k6lab.server.settings.SettingsService;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Клиент HTTP API Prometheus (PromQL). Админка только читает; запись метрик делает k6 через remote write. */
@Component
public class PrometheusClient {

    public record Sample(Map<String, String> labels, double value) {
    }

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    /** Instant query на момент at. NaN и пустые значения отбрасываются. */
    public List<Sample> query(SettingsService.Prometheus p, String promql, Instant at) throws IOException {
        JsonNode data = call(p, "/api/v1/query", "query=" + enc(promql) + "&time=" + at.getEpochSecond() + "." + String.format("%03d", at.getNano() / 1_000_000));
        List<Sample> out = new ArrayList<>();
        JsonNode result = data.path("result");
        if (!"vector".equals(data.path("resultType").asText())) {
            if ("scalar".equals(data.path("resultType").asText()) && result.isArray() && result.size() == 2) {
                double v = parse(result.get(1).asText());
                if (Double.isFinite(v)) {
                    out.add(new Sample(Map.of(), v));
                }
            }
            return out;
        }
        for (JsonNode r : result) {
            double v = parse(r.path("value").path(1).asText());
            if (!Double.isFinite(v)) {
                continue;
            }
            Map<String, String> labels = new LinkedHashMap<>();
            r.path("metric").fields().forEachRemaining(e -> labels.put(e.getKey(), e.getValue().asText()));
            out.add(new Sample(labels, v));
        }
        return out;
    }

    /** Значение единственного ряда (или первого) либо empty, если данных нет. */
    public Optional<Double> single(SettingsService.Prometheus p, String promql, Instant at) throws IOException {
        List<Sample> s = query(p, promql, at);
        return s.isEmpty() ? Optional.empty() : Optional.of(s.get(0).value());
    }

    /** Range query: значения первого ряда по шагам (для агрегации SUT-метрик avg/max/last за ступень). */
    public List<Double> queryRange(SettingsService.Prometheus p, String promql, Instant start, Instant end, int stepSec) throws IOException {
        JsonNode data = call(p, "/api/v1/query_range", "query=" + enc(promql) + "&start=" + start.getEpochSecond() + "&end="
                + end.getEpochSecond() + "&step=" + Math.max(1, stepSec));
        List<Double> out = new ArrayList<>();
        JsonNode result = data.path("result");
        if (result.isArray() && result.size() > 0) {
            for (JsonNode v : result.get(0).path("values")) {
                double d = parse(v.path(1).asText());
                if (Double.isFinite(d)) {
                    out.add(d);
                }
            }
        }
        return out;
    }

    /** Точка временного ряда: unix-секунды и значение. */
    public record Point(double ts, double value) {
    }

    /** Range query с разбивкой по метке scenario: все точки каждого ряда (NaN отбрасываются). */
    public Map<String, List<Point>> rangeByScenario(SettingsService.Prometheus p, String promql, Instant start, Instant end,
                                                    int stepSec) throws IOException {
        JsonNode data = call(p, "/api/v1/query_range", "query=" + enc(promql) + "&start=" + start.getEpochSecond() + "&end="
                + end.getEpochSecond() + "&step=" + Math.max(1, stepSec));
        Map<String, List<Point>> out = new LinkedHashMap<>();
        for (JsonNode r : data.path("result")) {
            String sc = r.path("metric").path("scenario").asText(null);
            if (sc == null) {
                continue;
            }
            List<Point> pts = new ArrayList<>();
            for (JsonNode v : r.path("values")) {
                double d = parse(v.path(1).asText());
                if (Double.isFinite(d)) {
                    pts.add(new Point(v.path(0).asDouble(), d));
                }
            }
            out.put(sc, pts);
        }
        return out;
    }

    /** Время первой точки запроса на отрезке (по шагам); empty, если данных ещё нет. Шаг растёт с длиной отрезка, чтобы не превысить лимит точек. */
    public Optional<Instant> firstTimestamp(SettingsService.Prometheus p, String promql, Instant start, Instant end) throws IOException {
        long range = Math.max(1, end.getEpochSecond() - start.getEpochSecond());
        int step = (int) Math.max(5, range / 2000);
        JsonNode data = call(p, "/api/v1/query_range", "query=" + enc(promql) + "&start=" + start.getEpochSecond() + "&end="
                + end.getEpochSecond() + "&step=" + step);
        double best = Double.MAX_VALUE;
        for (JsonNode r : data.path("result")) {
            for (JsonNode v : r.path("values")) {
                if (Double.isFinite(parse(v.path(1).asText()))) {
                    best = Math.min(best, v.path(0).asDouble());
                    break;
                }
            }
        }
        return best == Double.MAX_VALUE ? Optional.empty() : Optional.of(Instant.ofEpochSecond((long) best));
    }

    /** Доступность и права: тривиальный запрос. */
    public void ping(SettingsService.Prometheus p) throws IOException {
        call(p, "/api/v1/query", "query=1");
    }

    private JsonNode call(SettingsService.Prometheus p, String path, String query) throws IOException {
        if (p.queryUrl() == null || p.queryUrl().isBlank()) {
            throw new IOException("URL запросов Prometheus не задан");
        }
        String base = normalize(p.queryUrl());
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(base + path + "?" + query)).timeout(Duration.ofSeconds(15)).GET();
        p.authHeaders().forEach(b::header);
        try {
            HttpResponse<String> r = http.send(b.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (r.statusCode() == 401 || r.statusCode() == 403) {
                throw new IOException("Prometheus отклонил запрос (HTTP " + r.statusCode() + "): проверьте аутентификацию");
            }
            JsonNode root = MAPPER.readTree(r.body());
            if (!"success".equals(root.path("status").asText())) {
                throw new IOException("Prometheus: " + root.path("errorType").asText() + " " + root.path("error").asText("HTTP " + r.statusCode()));
            }
            return root.path("data");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Запрос к Prometheus прерван", e);
        }
    }

    /** Базовый URL: без хвостового / и без /api/v1/... */
    static String normalize(String url) {
        String u = url.trim();
        int i = u.indexOf("/api/v1");
        if (i >= 0) {
            u = u.substring(0, i);
        }
        while (u.endsWith("/")) {
            u = u.substring(0, u.length() - 1);
        }
        return u;
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    private static double parse(String s) {
        try {
            return Double.parseDouble(s);
        } catch (NumberFormatException e) {
            return Double.NaN;
        }
    }
}
