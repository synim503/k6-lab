package ru.k6lab.agent.run;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/** REST API k6 (--address 127.0.0.1:port): статус, счётчики и управление (pause/resume/scale). */
public class K6Api {

    public record Status(boolean running, boolean paused, boolean tainted, long vus, long vusMax) {
    }

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    private final String base;

    public K6Api(int port) {
        this.base = "http://127.0.0.1:" + port;
    }

    public Status status() throws IOException {
        JsonNode a = get("/v1/status").path("data").path("attributes");
        return new Status(a.path("running").asBoolean(), a.path("paused").asBoolean(), a.path("tainted").asBoolean(),
                a.path("vus").asLong(), a.path("vus-max").asLong());
    }

    /** Накопительное значение счётчика (count) либо 0, если метрики ещё нет. */
    public long counter(String metric) throws IOException {
        try {
            JsonNode a = get("/v1/metrics/" + metric).path("data").path("attributes").path("sample");
            return (long) a.path("count").asDouble(0);
        } catch (IOException e) {
            return 0;
        }
    }

    public void pause(boolean paused) throws IOException {
        patch("{\"data\":{\"type\":\"status\",\"id\":\"default\",\"attributes\":{\"paused\":" + paused + "}}}");
    }

    public void scaleVus(int vus) throws IOException {
        patch("{\"data\":{\"type\":\"status\",\"id\":\"default\",\"attributes\":{\"vus\":" + vus + "}}}");
    }

    private JsonNode get(String path) throws IOException {
        try {
            HttpResponse<String> r = http.send(HttpRequest.newBuilder(URI.create(base + path)).timeout(Duration.ofSeconds(3)).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            if (r.statusCode() != 200) {
                throw new IOException("k6 API " + path + ": HTTP " + r.statusCode());
            }
            return MAPPER.readTree(r.body());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Прервано", e);
        }
    }

    private void patch(String body) throws IOException {
        try {
            HttpResponse<String> r = http.send(HttpRequest.newBuilder(URI.create(base + "/v1/status")).timeout(Duration.ofSeconds(5))
                    .header("Content-Type", "application/json").method("PATCH", HttpRequest.BodyPublishers.ofString(body)).build(),
                    HttpResponse.BodyHandlers.ofString());
            if (r.statusCode() >= 300) {
                throw new IOException("k6 API PATCH: HTTP " + r.statusCode() + " " + r.body());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Прервано", e);
        }
    }
}
