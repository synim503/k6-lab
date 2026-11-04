package ru.k6lab.server;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BiFunction;

/** Минимальный Prometheus для тестов: принимает remote write (204) и отвечает на /api/v1/query[_range] через заданную функцию. */
public final class FakePrometheus implements AutoCloseable {

    /** (promql, time epoch seconds) -> значения по scenario; null - пустой результат. */
    public interface Responder extends BiFunction<String, Double, Map<String, Double>> {
    }

    private final HttpServer server;
    public final CopyOnWriteArrayList<String> queries = new CopyOnWriteArrayList<>();
    public volatile int writes;
    private volatile Responder responder = (q, t) -> Map.of();

    public FakePrometheus() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v1/write", ex -> {
            ex.getRequestBody().readAllBytes();
            writes++;
            ex.sendResponseHeaders(204, -1);
            ex.close();
        });
        server.createContext("/api/v1/query", this::query);
        server.createContext("/api/v1/query_range", this::queryRange);
        server.start();
    }

    public void respond(Responder r) {
        this.responder = r;
    }

    public String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    public String rwUrl() {
        return baseUrl() + "/api/v1/write";
    }

    private void query(HttpExchange ex) throws IOException {
        Map<String, String> p = params(ex);
        String q = p.getOrDefault("query", "");
        queries.add(q);
        double t = Double.parseDouble(p.getOrDefault("time", "0"));
        Map<String, Double> res = "1".equals(q) ? Map.of("", 1.0) : responder.apply(q, t);
        // агрегация по имени метрики: ключи ответа - имена метрик (метка __name__), иначе - значение метки scenario
        String labelName = q.startsWith("count by (__name__)") ? "__name__" : "scenario";
        StringBuilder sb = new StringBuilder("{\"status\":\"success\",\"data\":{\"resultType\":\"vector\",\"result\":[");
        if (res != null) {
            boolean first = true;
            for (var e : res.entrySet()) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                sb.append("{\"metric\":{").append(e.getKey().isEmpty() ? "" : "\"" + labelName + "\":\"" + e.getKey() + "\"")
                        .append("},\"value\":[").append(t).append(",\"").append(e.getValue()).append("\"]}");
            }
        }
        send(ex, sb.append("]}}").toString());
    }

    private void queryRange(HttpExchange ex) throws IOException {
        Map<String, String> p = params(ex);
        String q = p.getOrDefault("query", "");
        queries.add(q);
        double start = Double.parseDouble(p.getOrDefault("start", "0"));
        double end = Double.parseDouble(p.getOrDefault("end", "0"));
        double step = Math.max(1, Double.parseDouble(p.getOrDefault("step", "15")));
        // ряд на каждый scenario из ответа responder, значения - по времени каждой точки (как настоящий query_range)
        Map<String, StringBuilder> series = new LinkedHashMap<>();
        for (double t = start; t <= end; t += step) {
            Map<String, Double> res = responder.apply(q, t);
            if (res == null) {
                continue;
            }
            for (var e : res.entrySet()) {
                StringBuilder v = series.computeIfAbsent(e.getKey(), k -> new StringBuilder());
                v.append(v.length() == 0 ? "" : ",").append('[').append(t).append(",\"").append(e.getValue()).append("\"]");
            }
        }
        StringBuilder sb = new StringBuilder("{\"status\":\"success\",\"data\":{\"resultType\":\"matrix\",\"result\":[");
        boolean first = true;
        for (var e : series.entrySet()) {
            if (!first) {
                sb.append(',');
            }
            first = false;
            sb.append("{\"metric\":{").append(e.getKey().isEmpty() ? "" : "\"scenario\":\"" + e.getKey() + "\"").append("},\"values\":[")
                    .append(e.getValue()).append("]}");
        }
        send(ex, sb.append("]}}").toString());
    }

    private static void send(HttpExchange ex, String body) throws IOException {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(200, b.length);
        ex.getResponseBody().write(b);
        ex.close();
    }

    private static Map<String, String> params(HttpExchange ex) throws IOException {
        String raw = ex.getRequestMethod().equals("POST") ? new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)
                : ex.getRequestURI().getRawQuery();
        Map<String, String> m = new LinkedHashMap<>();
        if (raw != null) {
            for (String kv : raw.split("&")) {
                int i = kv.indexOf('=');
                if (i > 0) {
                    m.put(URLDecoder.decode(kv.substring(0, i), StandardCharsets.UTF_8), URLDecoder.decode(kv.substring(i + 1), StandardCharsets.UTF_8));
                }
            }
        }
        return m;
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
