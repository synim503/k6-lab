package ru.k6lab.server.schedule;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import ru.k6lab.server.generator.Generator;
import ru.k6lab.server.generator.GeneratorRepository;
import ru.k6lab.server.load.LoadProfileStore;
import ru.k6lab.server.script.Script;
import ru.k6lab.server.script.ScriptService;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Импорт списка тестов в расписание из JSON. Формат: массив или {"tests": [...]}; каждый тест:
 * {"script": "имя скрипта", "profile": "имя профиля", "runAt": "2026-11-21T10:00:00+03:00" (необязательно - ближайшее свободное время),
 * "generators": ["имя" или id, ...] (необязательно), "count": число генераторов (необязательно), "env": {"KEY": "value"} (необязательно),
 * "shift": true/false (по умолчанию true - сдвигать вперёд при освобождении генераторов)}.
 * Всё или ничего: при ошибке в любом тесте уже созданные записи этого импорта отменяются.
 */
@Service
public class ScheduleImportService {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    public record Created(long scheduleId, String script, String profile, Instant runAt) {
    }

    private final ScheduledRunService schedule;
    private final ScriptService scripts;
    private final LoadProfileStore profiles;
    private final GeneratorRepository generators;

    public ScheduleImportService(ScheduledRunService schedule, ScriptService scripts, LoadProfileStore profiles, GeneratorRepository generators) {
        this.schedule = schedule;
        this.scripts = scripts;
        this.profiles = profiles;
        this.generators = generators;
    }

    public List<Created> importTests(String json, String user) {
        JsonNode root;
        try {
            root = MAPPER.readTree(json == null ? "" : json);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Некорректный JSON: " + e.getOriginalMessage());
        }
        JsonNode list = root == null ? null : root.isArray() ? root : root.path("tests");
        if (list == null || !list.isArray() || list.isEmpty()) {
            throw new IllegalArgumentException("Ожидается непустой массив тестов или объект {\"tests\": [...]}");
        }
        List<Created> done = new ArrayList<>();
        try {
            int n = 0;
            for (JsonNode t : list) {
                n++;
                try {
                    done.add(one(t, user));
                } catch (IllegalArgumentException | IllegalStateException e) {
                    throw new IllegalArgumentException("Тест №" + n + ": " + e.getMessage(), e);
                }
            }
        } catch (RuntimeException e) {
            done.forEach(c -> schedule.cancel(c.scheduleId(), user));
            throw e;
        }
        return done;
    }

    private Created one(JsonNode t, String user) {
        String scriptName = text(t, "script");
        if (scriptName == null) {
            throw new IllegalArgumentException("не указан script (имя скрипта)");
        }
        Script script = scripts.list().stream().filter(s -> s.getName().equals(scriptName)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("скрипт «" + scriptName + "» не найден"));
        Long profileId = null;
        String profileName = text(t, "profile");
        if (profileName != null) {
            profileId = profiles.list(script.getId()).stream().filter(p -> p.name().equals(profileName)).findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("у скрипта «" + scriptName + "» нет профиля «" + profileName + "»")).id();
        }
        Instant runAt = null;
        String at = text(t, "runAt");
        if (at != null) {
            try {
                runAt = at.endsWith("Z") ? Instant.parse(at) : OffsetDateTime.parse(at).toInstant();
            } catch (DateTimeParseException e) {
                throw new IllegalArgumentException("runAt должен быть датой со смещением, например 2026-11-21T10:00:00+03:00");
            }
        }
        List<Long> genIds = new ArrayList<>();
        JsonNode gens = t.path("generators");
        if (gens.isArray()) {
            List<Generator> all = generators.findAll();
            for (JsonNode g : gens) {
                Generator found = g.isNumber() ? all.stream().filter(x -> x.getId().equals(g.asLong())).findFirst().orElse(null)
                        : all.stream().filter(x -> x.getName().equals(g.asText())).findFirst().orElse(null);
                if (found == null) {
                    throw new IllegalArgumentException("генератор «" + g.asText() + "» не найден");
                }
                genIds.add(found.getId());
            }
        }
        Integer count = t.hasNonNull("count") ? t.get("count").asInt() : null;
        Map<String, String> env = null;
        if (t.path("env").isObject()) {
            env = new LinkedHashMap<>();
            Map<String, String> e = env;
            t.get("env").fields().forEachRemaining(x -> e.put(x.getKey(), x.getValue().asText()));
        }
        boolean shift = !t.has("shift") || t.get("shift").asBoolean(true);
        long id = schedule.schedule(script.getId(), runAt, genIds, count, env, Map.of(), profileId, user, shift);
        Instant when = schedule.recent().stream().filter(i -> i.id() == id).findFirst().map(ScheduledRunStore.Item::runAt).orElse(runAt);
        return new Created(id, scriptName, profileName, when);
    }

    private static String text(JsonNode n, String field) {
        String v = n.path(field).asText(null);
        return v == null || v.isBlank() ? null : v.strip();
    }
}
