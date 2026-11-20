package ru.k6lab.server.result;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Разбор Grafana-дашборда (JSON из «Export» или «JSON Model», в том числе ответ API с обёрткой {"dashboard": ...}) в свои графики отчёта:
 * каждая панель с запросами Prometheus - один график (имя - заголовок панели, запросы - targets, подписи - legendFormat, единицы - из fieldConfig).
 * Панели внутри свёрнутых строк (row) тоже берутся. Переменные дашборда остаются в запросах как $имя и возвращаются отдельно
 * (имя -> значение из дашборда): переменная теста (testid, run_id...) -> {run_id}, интервалы ($__interval, $__rate_interval, $__range) -> {range}
 * в самих запросах, остальные - текущее значение (для «All» - любое, .*). Значения потом правятся на странице графиков.
 */
@Component
public class GrafanaDashboardImport {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Set<String> RUN_VARS = Set.of("testid", "test_id", "testrun", "test_run", "test_run_id", "run_id", "runid", "run");
    private static final Set<String> SKIP_TYPES = Set.of("row", "text", "news", "dashlist", "alertlist", "logs", "nodeGraph", "traces", "welcome");

    /** Панель, пересобранная в свой график. */
    public record Panel(String name, String unit, List<ChartTemplateStore.Query> queries) {
    }

    public record Result(String dashboardTitle, List<Panel> panels, List<String> skipped, Map<String, String> variables) {
    }

    public Result parse(String json) {
        JsonNode root;
        try {
            root = MAPPER.readTree(json == null ? "" : json);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Некорректный JSON: " + e.getOriginalMessage());
        }
        if (root == null || !root.isObject()) {
            throw new IllegalArgumentException("Ожидается JSON дашборда Grafana (Dashboard settings → JSON Model или Export → Export as JSON)");
        }
        JsonNode dash = root.has("dashboard") && root.get("dashboard").isObject() ? root.get("dashboard") : root;
        if (!dash.has("panels") && !dash.has("rows")) {
            throw new IllegalArgumentException("В JSON нет панелей (поле panels): это не дашборд Grafana");
        }
        Map<String, String> vars = variables(dash.path("templating").path("list"));
        List<Panel> out = new ArrayList<>();
        List<String> skipped = new ArrayList<>();
        Set<String> names = new HashSet<>();
        collect(dash.path("panels"), out, skipped, names);
        for (JsonNode row : dash.path("rows")) { // старый формат дашбордов: rows[].panels[]
            collect(row.path("panels"), out, skipped, names);
        }
        // в результат попадают только переменные, которые реально используются в запросах импортированных панелей
        Map<String, String> used = new java.util.LinkedHashMap<>();
        for (Panel p : out) {
            for (ChartTemplateStore.Query q : p.queries()) {
                Matcher m = VAR.matcher(q.promql());
                while (m.find()) {
                    String name = m.group(1) != null ? m.group(1) : m.group(2) != null ? m.group(2) : m.group(3);
                    if (vars.containsKey(name)) {
                        used.put(name, vars.get(name));
                    }
                }
            }
        }
        return new Result(dash.path("title").asText(""), out, skipped, used);
    }

    private void collect(JsonNode panels, List<Panel> out, List<String> skipped, Set<String> names) {
        for (JsonNode p : panels) {
            String type = p.path("type").asText("");
            if ("row".equals(type)) {
                collect(p.path("panels"), out, skipped, names); // свёрнутая строка хранит панели внутри
                continue;
            }
            String title = p.path("title").asText("").strip();
            if (SKIP_TYPES.contains(type)) {
                continue;
            }
            if (title.isEmpty()) {
                title = "Панель " + (out.size() + skipped.size() + 1);
            }
            List<ChartTemplateStore.Query> queries = new ArrayList<>();
            boolean foreign = false;
            for (JsonNode t : p.path("targets")) {
                if (t.path("hide").asBoolean(false)) {
                    continue;
                }
                String dsType = t.path("datasource").path("type").asText(p.path("datasource").path("type").asText(""));
                String expr = t.path("expr").asText("").strip();
                if (!dsType.isEmpty() && !"prometheus".equalsIgnoreCase(dsType)) {
                    foreign = true; // Loki, InfluxDB и другие источники отчёт не поддерживает
                    continue;
                }
                if (expr.isEmpty()) {
                    continue;
                }
                queries.add(new ChartTemplateStore.Query(substitute(expr, Map.of()), legend(t.path("legendFormat").asText(""))));
            }
            if (queries.isEmpty()) {
                skipped.add("«" + title + "»: " + (foreign ? "запросы не к Prometheus" : "нет запросов PromQL"));
                continue;
            }
            String name = title.length() > 120 ? title.substring(0, 120) : title;
            for (int i = 2; !names.add(name); i++) {
                name = (title.length() > 110 ? title.substring(0, 110) : title) + " (" + i + ")";
            }
            out.add(new Panel(name, unit(p.path("fieldConfig").path("defaults").path("unit").asText(p.path("yaxes").path(0).path("format").asText(""))), queries));
        }
    }

    /** legendFormat: «__auto» - подпись по меткам (пусто). {{метка}} оставляем: так же обрабатывает отчёт. */
    private static String legend(String f) {
        String s = f == null ? "" : f.strip();
        return "__auto".equals(s) ? "" : s;
    }

    /** Единицы Grafana -> подпись оси. */
    static String unit(String u) {
        return switch (u == null ? "" : u) {
            case "percent", "percentunit" -> "%";
            case "ms" -> "мс";
            case "s" -> "с";
            case "µs", "us" -> "мкс";
            case "reqps", "ops", "iops", "cps", "wps", "rps" -> "оп/с";
            case "bytes", "decbytes" -> "Б";
            case "bits" -> "бит";
            case "Bps", "binBps", "decBps" -> "Б/с";
            case "short", "none", "" -> "";
            default -> u;
        };
    }

    static final Pattern VAR = Pattern.compile("\\$\\{(\\w+)(?::[^}]*)?}|\\$(\\w+)|\\[\\[(\\w+)(?::\\w+)?]]");

    /** Переменные дашборда: имя -> значение для подстановки. */
    private static Map<String, String> variables(JsonNode list) {
        Map<String, String> vars = new HashMap<>();
        for (JsonNode v : list) {
            String name = v.path("name").asText("");
            if (name.isEmpty()) {
                continue;
            }
            String vt = v.path("type").asText("");
            if (vt.equals("datasource") || vt.equals("adhoc")) {
                continue;
            }
            if (vt.equals("interval")) {
                vars.put(name, "{range}");
                continue;
            }
            if (RUN_VARS.contains(name.toLowerCase())) {
                vars.put(name, "{run_id}");
                continue;
            }
            JsonNode cur = v.path("current").path("value");
            String val;
            if (cur.isArray()) {
                List<String> parts = new ArrayList<>();
                cur.forEach(x -> parts.add(x.asText()));
                val = parts.isEmpty() || parts.contains("$__all") ? ".*" : String.join("|", parts);
            } else {
                val = cur.asText("");
                if (val.isEmpty() && v.has("query") && "constant".equals(v.path("type").asText())) {
                    val = v.path("query").asText("");
                }
                if (val.isEmpty() || "$__all".equals(val) || "All".equalsIgnoreCase(val)) {
                    val = ".*";
                }
            }
            vars.put(name, val);
        }
        return vars;
    }

    /** Подстановка переменных и встроенных интервалов Grafana. */
    static String substitute(String s, Map<String, String> vars) {
        Matcher m = VAR.matcher(s);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String name = m.group(1) != null ? m.group(1) : m.group(2) != null ? m.group(2) : m.group(3);
            String rep;
            if (name.equals("__interval") || name.equals("__rate_interval") || name.equals("__range") || name.equals("__range_s")) {
                rep = "{range}";
            } else if (name.equals("__interval_ms") || name.equals("__range_ms")) {
                rep = "{range}";
            } else if (vars.containsKey(name)) {
                rep = vars.get(name);
            } else {
                rep = m.group(); // неизвестная переменная остаётся как есть
            }
            m.appendReplacement(sb, Matcher.quoteReplacement(rep));
        }
        m.appendTail(sb);
        return sb.toString();
    }
}
