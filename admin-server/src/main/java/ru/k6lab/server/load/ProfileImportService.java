package ru.k6lab.server.load;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Service;
import ru.k6lab.server.script.Script;
import ru.k6lab.server.script.ScriptService;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Импорт и экспорт профилей нагрузки в JSON. Формат: массив или {"profiles": [...]}; элемент - конфиг нагрузки
 * (тот же JSON, что у «Нагрузка» - «JSON»: operations, env, dataFiles, stop...) плюс "name" - имя профиля
 * и, при общем импорте, "script" - имя скрипта. Профиль с существующим именем обновляется.
 * Всё или ничего: сначала проверяются все профили, сохраняются только если ошибок нет.
 */
@Service
public class ProfileImportService {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    public record Imported(String script, String name, boolean updated) {
    }

    private final LoadService load;
    private final LoadProfileStore profiles;
    private final ScriptService scripts;
    private final RunPlanner planner;

    public ProfileImportService(LoadService load, LoadProfileStore profiles, ScriptService scripts, RunPlanner planner) {
        this.planner = planner;
        this.load = load;
        this.profiles = profiles;
        this.scripts = scripts;
    }

    private record Ready(Script script, String name, String data) {
    }

    /** scriptId != null - профили этого скрипта (поле script в JSON не нужно); null - скрипт берётся из поля script каждого профиля. */
    public List<Imported> importProfiles(Long scriptId, String json, String user) {
        JsonNode root;
        try {
            root = MAPPER.readTree(json == null ? "" : json);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Некорректный JSON: " + e.getOriginalMessage());
        }
        JsonNode list = root == null ? null : root.isArray() ? root : root.path("profiles");
        if (list == null || !list.isArray() || list.isEmpty()) {
            throw new IllegalArgumentException("Ожидается непустой массив профилей или объект {\"profiles\": [...]}");
        }
        Script fixed = scriptId == null ? null : scripts.get(scriptId);
        List<Ready> ready = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        int n = 0;
        for (JsonNode item : list) {
            n++;
            try {
                ready.add(prepare(item, fixed));
            } catch (IllegalArgumentException | IllegalStateException e) {
                throw new IllegalArgumentException("Профиль №" + n + ": " + e.getMessage(), e);
            }
            Ready r = ready.get(ready.size() - 1);
            if (!seen.add(r.script().getId() + "/" + r.name())) {
                throw new IllegalArgumentException("Профиль №" + n + ": имя «" + r.name() + "» повторяется для скрипта «" + r.script().getName() + "»");
            }
        }
        List<Imported> out = new ArrayList<>();
        for (Ready r : ready) {
            var saved = profiles.save(r.script().getId(), r.name(), r.data(), user);
            out.add(new Imported(r.script().getName(), r.name(), saved.updated()));
        }
        return out;
    }

    private Ready prepare(JsonNode item, Script fixed) {
        if (!(item instanceof ObjectNode obj)) {
            throw new IllegalArgumentException("профиль должен быть JSON-объектом");
        }
        ObjectNode cfg = obj.deepCopy();
        String name = cfg.path("name").asText("").strip();
        cfg.remove("name");
        String scriptName = cfg.path("script").asText("").strip();
        cfg.remove("script");
        if (name.isEmpty() || name.length() > 120) {
            throw new IllegalArgumentException("name: от 1 до 120 символов");
        }
        Script script = fixed;
        if (script == null) {
            if (scriptName.isEmpty()) {
                throw new IllegalArgumentException("не указан script (имя скрипта)");
            }
            script = scripts.list().stream().filter(s -> s.getName().equals(scriptName)).findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("скрипт «" + scriptName + "» не найден"));
        }
        LoadForm f = LoadJson.fromJson(cfg.toString());
        f.getOps().removeIf(LoadForm.OpForm::blank);
        f.getOps().forEach(LoadForm.OpForm::parseScenario);
        if (f.getOps().stream().noneMatch(LoadForm.OpForm::isEnabled)) {
            throw new IllegalArgumentException("«" + name + "»: нет включённых операций");
        }
        load.completeWithScriptFunctions(script.getId(), f);
        requireFunctionsExist(script, f, name);
        try {
            // полная проверка как при запуске и планировании (в том числе что функции операций есть в скрипте):
            // ошибку видно при импорте, а не в назначенный час
            var built = load.build(script.getId(), f);
            RunPlan plan = planner.plan(script.getId(), Math.max(1, script.getGeneratorCount()), built);
            if (!plan.ok()) {
                throw new IllegalArgumentException(String.join("; ", plan.errors()));
            }
        } catch (IllegalArgumentException | IllegalStateException e) {
            throw new IllegalArgumentException("«" + name + "»: " + e.getMessage());
        }
        return new Ready(script, name, LoadJson.toJson(LoadJson.fromJson(LoadJson.toJson(f))));
    }

    /**
     * Функции операций должны быть в последней версии скрипта. Без этой проверки, если не нашлась ни одна функция, сервер писал бы общее
     * «Не описана нагрузка»; здесь называем ненайденные функции и перечисляем доступные.
     */
    private void requireFunctionsExist(Script script, LoadForm f, String profile) {
        var version = scripts.latest(script.getId()).orElse(null);
        if (version == null) {
            return;
        }
        java.util.Set<String> available = scripts.operationNames(version);
        List<String> missing = f.getOps().stream().filter(LoadForm.OpForm::isEnabled)
                .map(o -> o.getExecFn() == null ? "" : o.getExecFn().trim()).filter(fn -> !fn.isEmpty() && !available.contains(fn)).distinct().toList();
        if (!missing.isEmpty()) {
            throw new IllegalArgumentException("«" + profile + "»: в скрипте «" + script.getName() + "» (v" + version.getNumber() + ") нет функции "
                    + String.join(", ", missing) + ". Доступные функции: " + (available.isEmpty() ? "нет" : String.join(", ", available)));
        }
    }

    /** Профили скрипта в формате импорта (для переноса между стендами и правки руками). */
    public String export(Long scriptId) {
        try {
            var arr = MAPPER.createArrayNode();
            for (var p : profiles.list(scriptId)) {
                ObjectNode n = MAPPER.createObjectNode();
                n.put("name", p.name());
                JsonNode cfg = MAPPER.readTree(p.data());
                cfg.fields().forEachRemaining(e -> n.set(e.getKey(), e.getValue()));
                arr.add(n);
            }
            ObjectNode root = MAPPER.createObjectNode();
            root.set("profiles", arr);
            return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(root);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }
}
