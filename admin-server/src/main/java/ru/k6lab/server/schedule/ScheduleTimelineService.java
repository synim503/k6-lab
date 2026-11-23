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
import java.util.TreeSet;
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

    /** Лёгкий снимок занятости генераторов для быстрого расчёта слотов (в памяти, без повторных запросов к БД и планировщику). */
    static final class Snapshot {
        record GenSlot(long id, String name, boolean online, int slots) {
        }

        record SlotBar(long fromMs, long toMs, Long generatorId, Long scheduleId, int count) {
        }

        final List<GenSlot> generators;
        final List<SlotBar> bars;

        Snapshot(List<GenSlot> generators, List<SlotBar> bars) {
            this.generators = generators;
            this.bars = bars;
        }

        void book(long scheduleId, List<Long> genIds, Instant runAt, int durationSec) {
            long from = runAt.toEpochMilli();
            long to = from + (long) (Math.max(1, durationSec) + TAIL_SEC) * 1000L;
            if (genIds == null || genIds.isEmpty()) {
                bars.add(new SlotBar(from, to, null, scheduleId, 1));
            } else {
                for (Long gid : genIds) {
                    bars.add(new SlotBar(from, to, gid, scheduleId, 1));
                }
            }
        }
    }

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
        for (ScheduledRunStore.Item it : store.active()) {
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
     * Снимок занятости генераторов для расчёта слотов и сдвига очереди: активные запуски, недавно завершившиеся запуски
     * (держат 5-минутную паузу охлаждения после окончания, чтобы сдвиг очереди не запускал следующий тест вплотную)
     * и все ожидающие/стартующие записи расписания.
     */
    Snapshot snapshot() {
        long now = Instant.now().toEpochMilli();
        List<Generator> all = generators.findAll().stream().sorted(Comparator.comparing(Generator::getName)).toList();
        List<Snapshot.GenSlot> gens = new ArrayList<>(all.size());
        for (Generator g : all) {
            gens.add(new Snapshot.GenSlot(g.getId(), g.getName(),
                    g.getStatus() == Generator.Status.ONLINE && !g.isMaintenance(),
                    Math.max(1, g.getMaxConcurrentRuns())));
        }
        List<Snapshot.SlotBar> bars = new ArrayList<>();
        java.util.Set<Long> activeRunIds = new java.util.HashSet<>();
        for (Run r : runs.activeRuns()) {
            activeRunIds.add(r.getId());
            long from = (r.getPlannedStartAt() != null ? r.getPlannedStartAt() : r.getStartedAt() != null ? r.getStartedAt() : r.getQueuedAt())
                    .toEpochMilli();
            long span = ConfigDuration.of(r.getK6Config()).totalSec();
            long to = Math.max(now + 30_000, from + (span + TAIL_SEC) * 1000L);
            for (RunGenerator rg : runs.generatorsOf(r.getId())) {
                if (rg.getGeneratorId() != null && !rg.getStatus().isDone()) {
                    bars.add(new Snapshot.SlotBar(from, to, rg.getGeneratorId(), null, 1));
                }
            }
        }
        // недавно завершившиеся запуски держат паузу TAIL_SEC (5 мин) на своих генераторах, чтобы shiftQueue не запускал следующий тест без перерыва
        long cooldownCutoff = now - TAIL_SEC * 1000L;
        for (Run r : runs.search(null, null, 20)) {
            if (activeRunIds.contains(r.getId()) || r.getFinishedAt() == null) {
                continue;
            }
            long fin = r.getFinishedAt().toEpochMilli();
            if (fin <= cooldownCutoff) {
                continue;
            }
            long to = fin + TAIL_SEC * 1000L;
            for (RunGenerator rg : runs.generatorsOf(r.getId())) {
                if (rg.getGeneratorId() != null) {
                    bars.add(new Snapshot.SlotBar(fin, to, rg.getGeneratorId(), null, 1));
                }
            }
        }
        Map<String, Integer> durations = new HashMap<>();
        for (ScheduledRunStore.Item it : store.active()) {
            int n = !it.generatorIds().isEmpty() ? it.generatorIds().size() : it.generatorCount() == null ? 1 : it.generatorCount();
            int sec = durations.computeIfAbsent(it.scriptId() + ":" + it.profileId() + ":" + n,
                    k -> durationSec(it.scriptId(), it.profileId(), n));
            long from = it.runAt().toEpochMilli();
            long to = from + (sec + TAIL_SEC) * 1000L;
            if (it.generatorIds().isEmpty()) {
                bars.add(new Snapshot.SlotBar(from, to, null, it.id(), n));
            } else {
                for (Long gid : it.generatorIds()) {
                    bars.add(new Snapshot.SlotBar(from, to, gid, it.id(), 1));
                }
            }
        }
        return new Snapshot(gens, bars);
    }

    /**
     * Снимок только активных (текущих) запусков — без ожидающих записей расписания и без остывания завершившихся тестов.
     * Используется при снятии паузы для перекладки всей очереди в исходном порядке.
     */
    Snapshot snapshotRunningOnly() {
        long now = Instant.now().toEpochMilli();
        List<Generator> all = generators.findAll().stream().sorted(Comparator.comparing(Generator::getName)).toList();
        List<Snapshot.GenSlot> gens = new ArrayList<>(all.size());
        for (Generator g : all) {
            gens.add(new Snapshot.GenSlot(g.getId(), g.getName(),
                    g.getStatus() == Generator.Status.ONLINE && !g.isMaintenance(),
                    Math.max(1, g.getMaxConcurrentRuns())));
        }
        List<Snapshot.SlotBar> bars = new ArrayList<>();
        for (Run r : runs.activeRuns()) {
            long from = (r.getPlannedStartAt() != null ? r.getPlannedStartAt() : r.getStartedAt() != null ? r.getStartedAt() : r.getQueuedAt())
                    .toEpochMilli();
            long span = ConfigDuration.of(r.getK6Config()).totalSec();
            long to = Math.max(now + 30_000, from + (span + TAIL_SEC) * 1000L);
            for (RunGenerator rg : runs.generatorsOf(r.getId())) {
                if (rg.getGeneratorId() != null && !rg.getStatus().isDone()) {
                    bars.add(new Snapshot.SlotBar(from, to, rg.getGeneratorId(), null, 1));
                }
            }
        }
        return new Snapshot(gens, bars);
    }

    int durationSecFor(ScheduledRunStore.Item it) {
        int n = !it.generatorIds().isEmpty() ? it.generatorIds().size() : it.generatorCount() == null ? 1 : it.generatorCount();
        return durationSec(it.scriptId(), it.profileId(), Math.max(1, n));
    }

    /**
     * Самое раннее время не раньше notBefore, когда свободны выбранные генераторы (ids) или любые n штук (ids пусты) на весь запуск с паузой.
     * Кандидаты: сам notBefore и уникальные моменты окончания существующих полос (с учётом паузы).
     */
    public Instant earliestSlot(List<Long> ids, int n, int durationSec, Instant notBefore) {
        return earliestSlot(snapshot(), ids, n, durationSec, notBefore);
    }

    Instant earliestSlot(Snapshot snap, List<Long> ids, int n, int durationSec, Instant notBefore) {
        long minMs = notBefore.toEpochMilli();
        TreeSet<Long> cand = new TreeSet<>();
        cand.add(minMs);
        for (Snapshot.SlotBar b : snap.bars) {
            if (b.toMs() > minMs) {
                cand.add(b.toMs());
            }
        }
        for (long t : cand) {
            Instant at = Instant.ofEpochMilli(t);
            if (ids.isEmpty()) {
                if (findFree(snap, t, durationSec, n) != null) {
                    return at;
                }
            } else if (busyNames(snap, ids, t, durationSec).isEmpty()) {
                return at;
            }
        }
        throw new IllegalArgumentException("Не удалось найти время, когда свободно нужное число генераторов (нужно " + (ids.isEmpty() ? n : ids.size())
                + "): проверьте, что генераторы в сети");
    }

    public void requireFree(List<Long> ids, Instant runAt, int durationSec) {
        requireFree(snapshot(), ids, runAt, durationSec);
    }

    void requireFree(Snapshot snap, List<Long> ids, Instant runAt, int durationSec) {
        List<String> busy = busyNames(snap, ids, runAt.toEpochMilli(), durationSec);
        if (!busy.isEmpty()) {
            throw new IllegalArgumentException("Генераторы заняты на это время (с паузой " + TAIL_SEC / 60 + " мин между запусками): " + String.join(", ", busy)
                    + ". Выберите другое время или нажмите «Ближайшее свободное» на таймлайне");
        }
    }

    private static List<String> busyNames(Snapshot snap, List<Long> ids, long start, int durationSec) {
        long end = start + (long) (Math.max(1, durationSec) + TAIL_SEC) * 1000L;
        List<String> busy = new ArrayList<>();
        for (Snapshot.GenSlot g : snap.generators) {
            if (!ids.contains(g.id())) {
                continue;
            }
            int used = 0;
            for (Snapshot.SlotBar b : snap.bars) {
                if (b.generatorId() != null && b.generatorId() == g.id() && b.fromMs() < end && b.toMs() > start) {
                    used++;
                }
            }
            if (used >= g.slots()) {
                busy.add(g.name());
            }
        }
        return busy;
    }

    public List<Long> pickFree(Instant runAt, int durationSec, int n) {
        return pickFree(snapshot(), runAt, durationSec, n);
    }

    List<Long> pickFree(Snapshot snap, Instant runAt, int durationSec, int n) {
        long start = runAt.toEpochMilli();
        List<Long> picked = findFree(snap, start, durationSec, n);
        if (picked == null) {
            int avail = countFree(snap, start, durationSec);
            throw new IllegalArgumentException("На это время свободно генераторов: " + Math.max(0, avail) + ", а нужно " + n
                    + ". Выберите другое время или нажмите «Ближайшее свободное» на таймлайне");
        }
        return picked;
    }

    private static List<Long> findFree(Snapshot snap, long start, int durationSec, int n) {
        long end = start + (long) (Math.max(1, durationSec) + TAIL_SEC) * 1000L;
        Map<Long, Integer> busy = new HashMap<>();
        Map<Long, Integer> pool = new HashMap<>();
        for (Snapshot.SlotBar b : snap.bars) {
            if (b.fromMs() >= end || b.toMs() <= start) {
                continue;
            }
            if (b.generatorId() != null) {
                busy.merge(b.generatorId(), 1, Integer::sum);
            } else if (b.scheduleId() != null) {
                pool.putIfAbsent(b.scheduleId(), b.count());
            }
        }
        List<Long> free = new ArrayList<>();
        for (Snapshot.GenSlot g : snap.generators) {
            if (g.online() && busy.getOrDefault(g.id(), 0) < g.slots()) {
                free.add(g.id());
            }
        }
        int reservedByPool = pool.values().stream().mapToInt(Integer::intValue).sum();
        if (free.size() - reservedByPool < n) {
            return null;
        }
        return new ArrayList<>(free.subList(0, n));
    }

    private static int countFree(Snapshot snap, long start, int durationSec) {
        long end = start + (long) (Math.max(1, durationSec) + TAIL_SEC) * 1000L;
        Map<Long, Integer> busy = new HashMap<>();
        Map<Long, Integer> pool = new HashMap<>();
        for (Snapshot.SlotBar b : snap.bars) {
            if (b.fromMs() >= end || b.toMs() <= start) {
                continue;
            }
            if (b.generatorId() != null) {
                busy.merge(b.generatorId(), 1, Integer::sum);
            } else if (b.scheduleId() != null) {
                pool.putIfAbsent(b.scheduleId(), b.count());
            }
        }
        int free = 0;
        for (Snapshot.GenSlot g : snap.generators) {
            if (g.online() && busy.getOrDefault(g.id(), 0) < g.slots()) {
                free++;
            }
        }
        int reservedByPool = pool.values().stream().mapToInt(Integer::intValue).sum();
        return Math.max(0, free - reservedByPool);
    }

    /**
     * Самое раннее время не раньше notBefore, когда все генераторы записи свободны на весь её интервал (с паузой).
     * Свои полосы записи не учитываются. null - раньше назначенного времени освободиться нечему.
     */
    public Instant earliestStart(ScheduledRunStore.Item it, Instant notBefore) {
        return earliestStart(it, notBefore, true);
    }

    /**
     * Самое раннее время не раньше notBefore, когда генераторы записи свободны на весь её интервал, без ограничения назначенным временем
     * (для просроченных записей после снятия паузы). Если генераторы не назначены - notBefore.
     */
    public Instant earliestFreeStart(ScheduledRunStore.Item it, Instant notBefore) {
        Instant t = earliestStart(it, notBefore, false);
        return t == null ? notBefore : t;
    }

    private Instant earliestStart(ScheduledRunStore.Item it, Instant notBefore, boolean onlyBeforeRunAt) {
        if (it.generatorIds().isEmpty()) {
            return null;
        }
        long span = (durationSec(it.scriptId(), it.profileId(), it.generatorIds().size()) + TAIL_SEC) * 1000L;
        Snapshot snap = snapshot();
        Map<Long, Integer> slots = new HashMap<>();
        for (Snapshot.GenSlot g : snap.generators) {
            slots.put(g.id(), g.slots());
        }
        List<Snapshot.SlotBar> bars = snap.bars.stream()
                .filter(b -> b.generatorId() != null && it.generatorIds().contains(b.generatorId())
                        && !(b.scheduleId() != null && b.scheduleId() == it.id()))
                .toList();
        TreeSet<Long> candidates = new TreeSet<>();
        long minMs = notBefore.toEpochMilli();
        candidates.add(minMs);
        for (Snapshot.SlotBar b : bars) {
            if (b.toMs() > minMs) {
                candidates.add(b.toMs());
            }
        }
        for (long t : candidates) {
            if (onlyBeforeRunAt && t >= it.runAt().toEpochMilli()) {
                return null;
            }
            boolean ok = true;
            for (Long gid : it.generatorIds()) {
                int busy = 0;
                for (Snapshot.SlotBar b : bars) {
                    if (b.generatorId() == gid.longValue() && b.fromMs() < t + span && b.toMs() > t) {
                        busy++;
                    }
                }
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
