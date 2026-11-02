package ru.k6lab.server.metrics;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import ru.k6lab.core.prom.PromQueries;
import ru.k6lab.server.settings.SettingsService;

/** Имена метрик k6 в Prometheus: настройка (JSON), а не константы в коде. */
@Service
public class MetricNamesService {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final SettingsService settings;

    public MetricNamesService(SettingsService settings) {
        this.settings = settings;
    }

    public PromQueries.Names names() {
        PromQueries.Names d = PromQueries.Names.defaults();
        String json = settings.get(SettingsService.METRIC_NAMES, "");
        if (json.isBlank()) {
            return d;
        }
        try {
            JsonNode n = MAPPER.readTree(json);
            return new PromQueries.Names(text(n, "opDuration", d.opDuration()), text(n, "iterationDuration", d.iterationDuration()),
                    text(n, "iterations", d.iterations()), text(n, "dropped", d.dropped()), text(n, "opCount", d.opCount()),
                    text(n, "opErrors", d.opErrors()), text(n, "httpReqFailed", d.httpReqFailed()), text(n, "checks", d.checks()),
                    text(n, "vus", d.vus()));
        } catch (JsonProcessingException e) {
            return d;
        }
    }

    public String json() {
        PromQueries.Names n = names();
        var o = MAPPER.createObjectNode();
        o.put("opDuration", n.opDuration());
        o.put("iterationDuration", n.iterationDuration());
        o.put("iterations", n.iterations());
        o.put("dropped", n.dropped());
        o.put("opCount", n.opCount());
        o.put("opErrors", n.opErrors());
        o.put("httpReqFailed", n.httpReqFailed());
        o.put("checks", n.checks());
        o.put("vus", n.vus());
        return o.toPrettyString();
    }

    public void save(String json) {
        try {
            JsonNode n = MAPPER.readTree(json);
            if (!n.isObject()) {
                throw new IllegalArgumentException("Имена метрик должны быть JSON-объектом");
            }
            for (var e : (Iterable<java.util.Map.Entry<String, JsonNode>>) n::fields) {
                if (!e.getValue().asText().matches("[a-zA-Z_:][a-zA-Z0-9_:]*")) {
                    throw new IllegalArgumentException("Недопустимое имя метрики: " + e.getValue().asText());
                }
            }
            settings.set(SettingsService.METRIC_NAMES, n.toString());
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Некорректный JSON: " + e.getOriginalMessage());
        }
    }

    private static String text(JsonNode n, String key, String def) {
        return n.hasNonNull(key) ? n.get(key).asText() : def;
    }
}
