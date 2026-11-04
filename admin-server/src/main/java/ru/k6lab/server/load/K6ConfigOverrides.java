package ru.k6lab.server.load;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Ручные правки расчётного конфига k6 (maxVUs, preAllocatedVUs, gracefulStop, опции вроде noConnectionReuse и т.д.).
 * Хранятся как JSON merge patch (RFC 7386): объекты сливаются по ключам, null удаляет ключ, остальное (включая массивы)
 * заменяется целиком. Благодаря этому правки переживают изменение нагрузки, а не затираются перерасчётом.
 */
public final class K6ConfigOverrides {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Поля сценария, от которых зависят расчёт плана, график и отчёт: их правка расходится с планом нагрузки. */
    private static final Set<String> PLAN_FIELDS = Set.of("executor", "exec", "stages", "rate", "startRate", "timeUnit", "duration",
            "iterations", "vus", "startVUs", "startTime", "tags", "env");

    private K6ConfigOverrides() {
    }

    public record Applied(JsonNode config, List<String> notes) {
    }

    /** Сливает правки с расчётным конфигом; правки сценариев, которых в расчёте уже нет, пропускаются с пояснением. */
    public static Applied apply(JsonNode base, Map<String, Object> overrides) {
        List<String> notes = new ArrayList<>();
        if (overrides == null || overrides.isEmpty()) {
            return new Applied(base, notes);
        }
        ObjectNode patch = MAPPER.valueToTree(overrides);
        // сценарий, которого больше нет (операцию переименовали или убрали из нагрузки), создавать из правки нельзя
        JsonNode baseScenarios = base.path("scenarios");
        JsonNode patchScenarios = patch.get("scenarios");
        if (patchScenarios != null && patchScenarios.isObject()) {
            Iterator<String> names = patchScenarios.fieldNames();
            List<String> skip = new ArrayList<>();
            while (names.hasNext()) {
                String n = names.next();
                if (!baseScenarios.has(n)) {
                    skip.add(n);
                }
            }
            for (String n : skip) {
                if (!patchScenarios.get(n).isNull()) {
                    notes.add("Ручная правка конфига k6 для «" + n + "» не применена: такой операции в нагрузке больше нет");
                }
                ((ObjectNode) patchScenarios).remove(n);
            }
        }
        JsonNode merged = merge(base, patch);
        List<String> risky = riskyPaths(patch);
        if (!risky.isEmpty()) {
            notes.add("Правки конфига k6 затрагивают поля плана нагрузки (" + String.join(", ", risky)
                    + "): график, расчёт VU и итоговый отчёт строятся по плану и эту правку не учитывают");
        }
        return new Applied(merged, notes);
    }

    /** JSON merge patch (RFC 7386); base не изменяется. */
    static JsonNode merge(JsonNode base, JsonNode patch) {
        if (!patch.isObject()) {
            return patch.deepCopy();
        }
        ObjectNode out = base != null && base.isObject() ? ((ObjectNode) base).deepCopy() : MAPPER.createObjectNode();
        Iterator<Map.Entry<String, JsonNode>> it = patch.fields();
        while (it.hasNext()) {
            var e = it.next();
            if (e.getValue().isNull()) {
                out.remove(e.getKey());
            } else {
                out.set(e.getKey(), merge(out.get(e.getKey()), e.getValue()));
            }
        }
        return out;
    }

    /** Патч, превращающий base в edited. Пустой, если конфиги равны. */
    public static ObjectNode diff(JsonNode base, JsonNode edited) {
        ObjectNode patch = MAPPER.createObjectNode();
        Iterator<Map.Entry<String, JsonNode>> it = edited.fields();
        while (it.hasNext()) {
            var e = it.next();
            JsonNode b = base.get(e.getKey());
            if (b == null) {
                patch.set(e.getKey(), e.getValue());
            } else if (b.isObject() && e.getValue().isObject()) {
                ObjectNode sub = diff(b, e.getValue());
                if (!sub.isEmpty()) {
                    patch.set(e.getKey(), sub);
                }
            } else if (!same(b, e.getValue())) {
                patch.set(e.getKey(), e.getValue());
            }
        }
        Iterator<String> names = base.fieldNames();
        while (names.hasNext()) {
            String n = names.next();
            if (!edited.has(n)) {
                patch.putNull(n);
            }
        }
        return patch;
    }

    /** Равенство по значению: 5, 5L и 5.0 - одно и то же число (расчётный конфиг и разобранный текст различаются типами). */
    static boolean same(JsonNode a, JsonNode b) {
        if (a.isNumber() && b.isNumber()) {
            return a.decimalValue().compareTo(b.decimalValue()) == 0;
        }
        if (a.isArray() && b.isArray()) {
            if (a.size() != b.size()) {
                return false;
            }
            for (int i = 0; i < a.size(); i++) {
                if (!same(a.get(i), b.get(i))) {
                    return false;
                }
            }
            return true;
        }
        if (a.isObject() && b.isObject()) {
            return a.size() == b.size() && diff(a, b).isEmpty();
        }
        return a.equals(b);
    }

    public static Map<String, Object> toMap(ObjectNode patch) {
        return MAPPER.convertValue(patch, new TypeReference<LinkedHashMap<String, Object>>() {
        });
    }

    /** Пути правок вида scenarios.login.maxVUs - для списка в предупреждении. */
    public static List<String> paths(Map<String, Object> overrides) {
        List<String> out = new ArrayList<>();
        if (overrides != null && !overrides.isEmpty()) {
            collect("", MAPPER.valueToTree(overrides), out);
        }
        return out;
    }

    private static void collect(String prefix, JsonNode n, List<String> out) {
        if (n.isObject() && !n.isEmpty()) {
            n.fields().forEachRemaining(e -> collect(prefix.isEmpty() ? e.getKey() : prefix + "." + e.getKey(), e.getValue(), out));
        } else {
            out.add(prefix);
        }
    }

    private static List<String> riskyPaths(ObjectNode patch) {
        List<String> out = new ArrayList<>();
        for (String p : paths(MAPPER.convertValue(patch, new TypeReference<LinkedHashMap<String, Object>>() {
        }))) {
            String[] seg = p.split("\\.");
            boolean scenarioField = seg.length >= 3 && seg[0].equals("scenarios") && PLAN_FIELDS.contains(seg[2]);
            // сценарий целиком удалён или добавлен вручную - тоже расходится с планом
            boolean scenarioWhole = seg.length == 2 && seg[0].equals("scenarios");
            if (scenarioField || scenarioWhole || p.equals("systemTags") || p.equals("scenarios")) {
                out.add(p);
            }
        }
        return out;
    }
}
