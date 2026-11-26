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
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Импорт списка тестов в расписание из JSON. Формат: массив или {"tests": [...], "gapMinutes": 5}; каждый тест:
 * {"script": "имя скрипта", "profile": "имя профиля", "runAt": "2026-11-21T10:00:00+03:00" (необязательно - последовательно с паузой ≥5 мин),
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
    private final ScheduleTimelineService timeline;
    private final ScriptService scripts;
    private final LoadProfileStore profiles;
    private final GeneratorRepository generators;

    public ScheduleImportService(ScheduledRunService schedule, ScheduleTimelineService timeline, ScriptService scripts,
                                 LoadProfileStore profiles, GeneratorRepository generators) {
        this.schedule = schedule;
        this.timeline = timeline;
        this.scripts = scripts;
        this.profiles = profiles;
        this.generators = generators;
    }

    public List<Created> importTests(String json, String user) {
        return importTests(json, null, user);
    }

    public List<Created> importTests(String json, Integer gapMinutesOverride, String user) {
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
        int gapMin = root.isObject() && root.hasNonNull("gapMinutes") ? root.get("gapMinutes").asInt(5)
                : (gapMinutesOverride != null ? gapMinutesOverride : 5);
        int pauseSec = Math.max(ScheduleTimelineService.TAIL_SEC, Math.min(24 * 3600, gapMin * 60));

        return schedule.withLock(() -> {
            // загружаем справочники и снимок таймлайна ОДИН раз на весь пакет, чтобы 20–50 тестов импортировались за доли секунды
            Map<String, Script> scriptsByName = new HashMap<>();
            for (Script s : scripts.list()) {
                scriptsByName.putIfAbsent(s.getName(), s);
            }
            List<Generator> allGens = generators.findAll();
            Map<Long, Generator> genById = new HashMap<>();
            Map<String, Generator> genByName = new HashMap<>();
            for (Generator g : allGens) {
                genById.put(g.getId(), g);
                genByName.putIfAbsent(g.getName(), g);
            }
            Map<Long, Map<String, Long>> profilesByScript = new HashMap<>();
            ScheduleTimelineService.Snapshot snap = timeline.snapshot();
            Map<String, ru.k6lab.server.load.RunPlan> planCache = new HashMap<>();
            Map<String, ru.k6lab.server.load.LoadService.BuiltLoad> builtCache = new HashMap<>();

            Instant cursor = Instant.now().plus(ScheduledRunService.MIN_LEAD).plusSeconds(30);
            List<Long> prevBooked = List.of();
            List<Created> done = new ArrayList<>();
            try {
                int n = 0;
                for (JsonNode t : list) {
                    n++;
                    try {
                        Parsed p = parseOne(t, scriptsByName, profilesByScript, genById, genByName);
                        List<Long> targetIds = p.genIds();
                        int need = !targetIds.isEmpty() ? targetIds.size()
                                : (p.count() != null ? p.count() : Math.max(1, p.script().getGeneratorCount()));
                        // если время не задано: ставим строго последовательно после предыдущего авто-теста + пауза (≥5 мин),
                        // переиспользуя те же генераторы (если не заданы свои), чтобы тесты не ушли в параллель и не склеились при сдвиге
                        boolean autoSeq = p.runAt() == null;
                        if (autoSeq && targetIds.isEmpty() && !prevBooked.isEmpty() && need <= prevBooked.size()) {
                            targetIds = prevBooked.subList(0, need);
                        }
                        boolean itemShift = p.shift() && (!autoSeq || done.isEmpty() || !targetIds.isEmpty());
                        var res = schedule.scheduleInBatch(snap, planCache, builtCache, p.script(), p.runAt(),
                                autoSeq ? cursor : null, targetIds, p.count(), p.env(), p.profileId(), user, itemShift);
                        done.add(new Created(res.id(), p.script().getName(), p.profileName(), res.runAt()));
                        if (autoSeq) {
                            if (!res.generatorIds().isEmpty()) {
                                prevBooked = res.generatorIds();
                            }
                            cursor = res.runAt().plusSeconds((long) res.durationSec() + pauseSec);
                        }
                    } catch (IllegalArgumentException | IllegalStateException e) {
                        throw new IllegalArgumentException("Тест №" + n + ": " + e.getMessage(), e);
                    }
                }
            } catch (RuntimeException e) {
                done.forEach(c -> schedule.cancel(c.scheduleId(), user));
                throw e;
            }
            return done;
        });
    }

    private record Parsed(Script script, Long profileId, String profileName, Instant runAt, List<Long> genIds,
                          Integer count, Map<String, String> env, boolean shift) {
    }

    private Parsed parseOne(JsonNode t, Map<String, Script> scriptsByName, Map<Long, Map<String, Long>> profilesByScript,
                            Map<Long, Generator> genById, Map<String, Generator> genByName) {
        String scriptName = text(t, "script");
        if (scriptName == null) {
            throw new IllegalArgumentException("не указан script (имя скрипта)");
        }
        Script script = scriptsByName.get(scriptName);
        if (script == null) {
            throw new IllegalArgumentException("скрипт «" + scriptName + "» не найден");
        }
        Long profileId = null;
        String profileName = text(t, "profile");
        if (profileName != null) {
            Map<String, Long> byName = profilesByScript.computeIfAbsent(script.getId(), sid -> {
                Map<String, Long> m = new HashMap<>();
                profiles.list(sid).forEach(pr -> m.putIfAbsent(pr.name(), pr.id()));
                return m;
            });
            profileId = byName.get(profileName);
            if (profileId == null) {
                throw new IllegalArgumentException("у скрипта «" + scriptName + "» нет профиля «" + profileName + "»");
            }
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
            for (JsonNode g : gens) {
                Generator found = g.isNumber() ? genById.get(g.asLong()) : genByName.get(g.asText());
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
            t.get("env").fields().forEachRemaining(x -> e.put(x.getKey(), x.getValue() == null || x.getValue().isNull() ? "" : x.getValue().asText()));
        }
        boolean shift = !t.has("shift") || t.get("shift").asBoolean(true);
        return new Parsed(script, profileId, profileName, runAt, genIds, count, env, shift);
    }

    private static String text(JsonNode n, String field) {
        String v = n.path(field).asText(null);
        return v == null || v.isBlank() ? null : v.strip();
    }
}
