package ru.k6lab.server.schedule;

import org.springframework.stereotype.Service;
import ru.k6lab.core.k6config.ConfigDuration;
import ru.k6lab.server.generator.Generator;
import ru.k6lab.server.generator.GeneratorRepository;
import ru.k6lab.server.load.LoadService;
import ru.k6lab.server.load.RunPlan;
import ru.k6lab.server.load.RunPlanner;
import ru.k6lab.server.run.Run;
import ru.k6lab.server.run.RunGenerator;
import ru.k6lab.server.run.RunService;
import ru.k6lab.server.script.ScriptService;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Данные для графического таймлайна: занятость генераторов текущими запусками и уже запланированными.
 * Запланированный запуск без выбранных серверов занимает «любые свободные» (count штук) - он показан отдельной полосой.
 * Каждая полоса несёт подробности (скрипт, профиль, кто назначил, интенсивность, генераторы) для подсказки и панели справа.
 */
@Service
public class ScheduleTimelineService {

    /** Пауза после плановой длительности: остановка k6, освобождение генератора и 5 минут между запусками на одних генераторах. */
    static final int TAIL_SEC = 300;
    /** Если длительность запланированного запуска не удалось рассчитать. */
    static final int FALLBACK_SEC = 30 * 60;

    private final RunService runs;
    private final ScheduledRunStore store;
    private final GeneratorRepository generators;
    private final RunPlanner planner;
    private final LoadService load;
    private final ScriptService scripts;

    public ScheduleTimelineService(RunService runs, ScheduledRunStore store, GeneratorRepository generators, RunPlanner planner,
                                   LoadService load, ScriptService scripts) {
        this.runs = runs;
        this.store = store;
        this.generators = generators;
        this.planner = planner;
        this.load = load;
        this.scripts = scripts;
    }

    /**
     * Для формы планирования скрипта: занятость плюс плановая длительность нового запуска.
     *
     * @param profileId профиль нагрузки (null - нагрузка скрипта)
     * @param count     число генераторов нового запуска (для расчёта его длительности)
     */
    public Map<String, Object> build(long scriptId, Long profileId, Integer count) {
        Map<String, Object> out = overview();
        int mine = durationSec(scriptId, profileId, count == null ? scripts.get(scriptId).getGeneratorCount() : Math.max(1, count));
        out.put("durationSec", mine);
        return out;
    }

    /** Для страницы расписания: только занятость, без нового запуска. */
    public Map<String, Object> overview() {
        long now = Instant.now().toEpochMilli();
        List<Generator> all = generators.findAll().stream().sorted(Comparator.comparing(Generator::getName)).toList();
        Map<Long, String> names = all.stream().collect(Collectors.toMap(Generator::getId, Generator::getName, (a, b) -> a));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("nowMs", now);
        out.put("tailSec", TAIL_SEC);

        List<Map<String, Object>> gens = new ArrayList<>();
        for (Generator g : all) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", g.getId());
            m.put("name", g.getName());
            m.put("online", g.getStatus() == Generator.Status.ONLINE && !g.isMaintenance());
            m.put("slots", Math.max(1, g.getMaxConcurrentRuns()));
            gens.add(m);
        }
        out.put("generators", gens);

        List<Map<String, Object>> bars = new ArrayList<>();
        for (Run r : runs.activeRuns()) {
            long from = (r.getPlannedStartAt() != null ? r.getPlannedStartAt() : r.getStartedAt() != null ? r.getStartedAt() : r.getQueuedAt())
                    .toEpochMilli();
            long span = ConfigDuration.of(r.getK6Config()).totalSec();
            long to = Math.max(now + 30_000, from + (span + TAIL_SEC) * 1000);
            Object pn = r.getRequest() == null ? null : r.getRequest().get("loadProfileName");
            String profile = pn == null ? null : String.valueOf(pn);
            List<RunGenerator> rgs = runs.generatorsOf(r.getId()).stream()
                    .filter(rg -> rg.getGeneratorId() != null && !rg.getStatus().isDone()).toList();
            String genNames = rgs.stream().map(RunGenerator::getGeneratorName).collect(Collectors.joining(", "));
            for (RunGenerator rg : rgs) {
                Map<String, Object> b = bar("run", rg.getGeneratorId(), 1, r.getScriptName(), profile, from, to, r.getId(), null);
                b.put("by", r.getCreatedBy());
                b.put("status", r.getStatus().name());
                b.put("genCount", rgs.size());
                b.put("genNames", genNames);
                b.put("scheduled", r.getRequest() != null && r.getRequest().get("scheduleId") != null);
                bars.add(b);
            }
        }

        Map<String, Integer> durations = new HashMap<>();
        for (ScheduledRunStore.Item it : store.recent(200)) {
            if (it.status() != ScheduledRunStore.Status.PENDING && it.status() != ScheduledRunStore.Status.STARTING) {
                continue;
            }
            int n = !it.generatorIds().isEmpty() ? it.generatorIds().size() : it.generatorCount() == null ? 1 : it.generatorCount();
            int sec = durations.computeIfAbsent(it.scriptId() + ":" + it.profileId() + ":" + n,
                    k -> durationSec(it.scriptId(), it.profileId(), n));
            long from = it.runAt().toEpochMilli();
            long to = from + (sec + TAIL_SEC) * 1000L;
            String intensity = it.intensity().entrySet().stream()
                    .map(e -> e.getKey() + " " + java.math.BigDecimal.valueOf(e.getValue()).stripTrailingZeros().toPlainString() + "%")
                    .collect(Collectors.joining(", "));
            String genNames = it.generatorIds().isEmpty() ? "" : it.generatorIds().stream().map(id -> names.getOrDefault(id, "#" + id))
                    .collect(Collectors.joining(", "));
            List<Long> targets = it.generatorIds().isEmpty() ? java.util.Collections.singletonList(null) : it.generatorIds();
            for (Long id : targets) {
                Map<String, Object> b = bar("scheduled", id, id == null ? n : 1, it.scriptName(), it.profileName(), from, to, null, it.id());
                b.put("by", it.createdBy());
                b.put("status", it.status().name());
                b.put("genCount", n);
                b.put("genNames", genNames);
                b.put("intensity", intensity.isEmpty() ? "как в скрипте" : intensity);
                b.put("env", it.env() == null ? "как в скрипте" : it.env().isEmpty() ? "без переменных" : String.join(", ", it.env().keySet()));
                b.put("durationSec", sec);
                bars.add(b);
            }
        }
        out.put("bars", bars);
        return out;
    }

    /**
     * Бронирование при планировании: выбирает n генераторов, свободных на весь интервал запуска (с запасом на остановку).
     * Генератор свободен, если он в сети, не на обслуживании и занятых на этот интервал слотов меньше, чем у него всего
     * (запуски и уже забронированные записи расписания). Старые записи «любые свободные» вычитаются из общего числа.
     *
     * @throws IllegalArgumentException если свободных генераторов на это время не хватает
     */
    /**
     * Самое раннее время не раньше notBefore, когда свободны выбранные генераторы (ids) или любые n штук (ids пусты) на весь запуск с паузой.
     * Кандидаты: сам notBefore и моменты окончания существующих полос (с учётом паузы).
     */
    public Instant earliestSlot(List<Long> ids, int n, int durationSec, Instant notBefore) {
        List<Long> cand = new ArrayList<>();
        cand.add(notBefore.toEpochMilli());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> bars = (List<Map<String, Object>>) overview().get("bars");
        for (Map<String, Object> b : bars) {
            long to = (long) b.get("to");
            if (to > notBefore.toEpochMilli()) {
                cand.add(to);
            }
        }
        cand.sort(Long::compare);
        for (long t : cand) {
            Instant at = Instant.ofEpochMilli(t);
            try {
                if (ids.isEmpty()) {
                    pickFree(at, durationSec, n);
                } else {
                    requireFree(ids, at, durationSec);
                }
                return at;
            } catch (IllegalArgumentException busy) {
                // этот момент занят - пробуем следующий
            }
        }
        throw new IllegalArgumentException("Не удалось найти время, когда свободно нужное число генераторов (нужно " + (ids.isEmpty() ? n : ids.size())
                + "): проверьте, что генераторы в сети");
    }

    public void requireFree(List<Long> ids, Instant runAt, int durationSec) {
        long start = runAt.toEpochMilli();
        long end = start + (long) (Math.max(1, durationSec) + TAIL_SEC) * 1000L;
        Map<String, Object> ov = overview();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> gens = (List<Map<String, Object>>) ov.get("generators");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> bars = (List<Map<String, Object>>) ov.get("bars");
        List<String> busy = new ArrayList<>();
        for (Map<String, Object> g : gens) {
            Long gid = (Long) g.get("id");
            if (!ids.contains(gid)) {
                continue;
            }
            long n = bars.stream().filter(b -> gid.equals(b.get("generatorId")) && (long) b.get("from") < end && (long) b.get("to") > start).count();
            if (n >= (Integer) g.get("slots")) {
                busy.add((String) g.get("name"));
            }
        }
        if (!busy.isEmpty()) {
            throw new IllegalArgumentException("Генераторы заняты на это время (с паузой " + TAIL_SEC / 60 + " мин между запусками): " + String.join(", ", busy)
                    + ". Выберите другое время или нажмите «Ближайшее свободное» на таймлайне");
        }
    }

    public List<Long> pickFree(Instant runAt, int durationSec, int n) {
        long start = runAt.toEpochMilli();
        long end = start + (long) (Math.max(1, durationSec) + TAIL_SEC) * 1000L;
        Map<String, Object> ov = overview();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> gens = (List<Map<String, Object>>) ov.get("generators");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> bars = (List<Map<String, Object>>) ov.get("bars");
        Map<Long, Integer> busy = new HashMap<>();
        Map<Long, Integer> pool = new HashMap<>();
        for (Map<String, Object> b : bars) {
            if ((long) b.get("from") >= end || (long) b.get("to") <= start) {
                continue;
            }
            Long gid = (Long) b.get("generatorId");
            if (gid != null) {
                busy.merge(gid, 1, Integer::sum);
            } else if (b.get("scheduleId") != null) {
                pool.putIfAbsent((Long) b.get("scheduleId"), (Integer) b.get("count"));
            }
        }
        List<Long> free = new ArrayList<>();
        for (Map<String, Object> g : gens) {
            if (Boolean.TRUE.equals(g.get("online")) && busy.getOrDefault((Long) g.get("id"), 0) < (Integer) g.get("slots")) {
                free.add((Long) g.get("id"));
            }
        }
        int reservedByPool = pool.values().stream().mapToInt(Integer::intValue).sum();
        int available = free.size() - reservedByPool;
        if (available < n) {
            throw new IllegalArgumentException("На это время свободно генераторов: " + Math.max(0, available) + ", а нужно " + n
                    + ". Выберите другое время или нажмите «Ближайшее свободное» на таймлайне");
        }
        return free.subList(0, n);
    }

    /**
     * Самое раннее время не раньше notBefore, когда все генераторы записи свободны на весь её интервал (с паузой).
     * Свои полосы записи не учитываются. null - раньше назначенного времени освободиться нечему.
     */
    public Instant earliestStart(ScheduledRunStore.Item it, Instant notBefore) {
        if (it.generatorIds().isEmpty()) {
            return null;
        }
        long span = (durationSec(it.scriptId(), it.profileId(), it.generatorIds().size()) + TAIL_SEC) * 1000L;
        Map<String, Object> ov = overview();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> gens = (List<Map<String, Object>>) ov.get("generators");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> all = (List<Map<String, Object>>) ov.get("bars");
        Map<Long, Integer> slots = new HashMap<>();
        for (Map<String, Object> g : gens) {
            slots.put((Long) g.get("id"), (Integer) g.get("slots"));
        }
        List<Map<String, Object>> bars = all.stream().filter(b -> b.get("generatorId") != null && it.generatorIds().contains((Long) b.get("generatorId"))
                && !(b.get("scheduleId") != null && ((Long) b.get("scheduleId")) == it.id())).toList();
        List<Long> candidates = new ArrayList<>();
        candidates.add(notBefore.toEpochMilli());
        for (Map<String, Object> b : bars) {
            long to = (long) b.get("to");
            if (to > notBefore.toEpochMilli()) {
                candidates.add(to);
            }
        }
        candidates.sort(Long::compare);
        for (long t : candidates) {
            if (t >= it.runAt().toEpochMilli()) {
                return null;
            }
            boolean ok = true;
            for (Long gid : it.generatorIds()) {
                long busy = bars.stream().filter(b -> gid.equals(b.get("generatorId")) && (long) b.get("from") < t + span && (long) b.get("to") > t).count();
                if (busy >= slots.getOrDefault(gid, 1)) {
                    ok = false;
                    break;
                }
            }
            if (ok) {
                return Instant.ofEpochMilli(t);
            }
        }
        return null;
    }

    private int durationSec(long scriptId, Long profileId, int n) {
        try {
            LoadService.BuiltLoad built = profileId == null ? null : load.fromProfile(scriptId, profileId);
            RunPlan plan = planner.plan(scriptId, n, built);
            int sec = plan.totalSec();
            return sec > 0 ? sec : FALLBACK_SEC;
        } catch (RuntimeException e) {
            return FALLBACK_SEC;
        }
    }

    private static Map<String, Object> bar(String kind, Long generatorId, int count, String script, String profile, long from, long to,
                                           Long runId, Long scheduleId) {
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("kind", kind);
        b.put("generatorId", generatorId);
        b.put("count", count);
        b.put("script", script);
        b.put("profile", profile);
        b.put("label", profile == null || profile.isBlank() ? script : script + " · " + profile);
        b.put("from", from);
        b.put("to", to);
        b.put("runId", runId);
        b.put("scheduleId", scheduleId);
        return b;
    }
}
