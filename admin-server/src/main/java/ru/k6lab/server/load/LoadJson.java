package ru.k6lab.server.load;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.exc.MismatchedInputException;
import com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.Iterator;
import java.util.Map;

/** Конфиг нагрузки скрипта в виде JSON: то же, что форма «Нагрузка», но текстом (env - объектом, операции - списком). */
final class LoadJson {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private LoadJson() {
    }

    static String toJson(LoadForm f) {
        ObjectNode n = MAPPER.valueToTree(f);
        n.remove("envText");
        JsonNode ops = n.remove("ops");
        n.set("operations", ops);
        ObjectNode env = n.putObject("env");
        LoadForm.parseKv(f.getEnvText()).forEach(env::put);
        JsonNode data = n.remove("dataFiles");
        n.set("dataFiles", data);
        try {
            return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(n);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    static LoadForm fromJson(String json) {
        if (json == null || json.isBlank()) {
            throw new IllegalArgumentException("Конфиг пуст");
        }
        try {
            JsonNode root = MAPPER.readTree(json);
            if (!(root instanceof ObjectNode n)) {
                throw new IllegalArgumentException("Конфиг должен быть JSON-объектом");
            }
            JsonNode env = n.remove("env");
            JsonNode ops = n.remove("operations");
            if (ops != null) {
                n.set("ops", ops);
            }
            LoadForm f = MAPPER.treeToValue(n, LoadForm.class);
            Map<String, String> kv = new java.util.LinkedHashMap<>();
            if (env != null && !env.isNull()) {
                if (!env.isObject()) {
                    throw new IllegalArgumentException("env должен быть объектом {\"KEY\": \"VALUE\"}");
                }
                Iterator<Map.Entry<String, JsonNode>> it = env.fields();
                while (it.hasNext()) {
                    var e = it.next();
                    kv.put(e.getKey(), e.getValue().asText());
                }
            }
            f.setEnvText(LoadForm.formatKv(kv));
            return f;
        } catch (UnrecognizedPropertyException e) {
            throw new IllegalArgumentException("Некорректный конфиг: неизвестное поле «" + e.getPropertyName() + "» в " + where(e)
                    + ". Допустимые: " + e.getKnownPropertyIds().stream().map(Object::toString).sorted().toList());
        } catch (MismatchedInputException e) {
            throw new IllegalArgumentException("Некорректный конфиг: неверное значение в " + where(e)
                    + (e.getTargetType() == null ? "" : " (ожидается " + typeName(e.getTargetType()) + ")"));
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Некорректный конфиг: " + e.getOriginalMessage());
        }
    }

    private static String where(com.fasterxml.jackson.databind.JsonMappingException e) {
        StringBuilder sb = new StringBuilder();
        for (var ref : e.getPath()) {
            if (ref.getFieldName() != null) {
                String name = "ops".equals(ref.getFieldName()) ? "operations" : ref.getFieldName();
                sb.append(sb.length() == 0 ? "" : ".").append(name);
            } else if (ref.getIndex() >= 0) {
                sb.append('[').append(ref.getIndex()).append(']');
            }
        }
        return sb.length() == 0 ? "корне конфига" : "поле " + sb;
    }

    private static String typeName(Class<?> t) {
        if (Number.class.isAssignableFrom(t) || t == int.class || t == double.class || t == long.class) {
            return "число";
        }
        if (t == boolean.class || t == Boolean.class) {
            return "true или false";
        }
        if (t.isEnum()) {
            return "одно из: " + java.util.Arrays.toString(t.getEnumConstants());
        }
        return t.getSimpleName();
    }
}
