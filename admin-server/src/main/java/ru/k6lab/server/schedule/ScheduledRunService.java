package ru.k6lab.server.schedule;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import ru.k6lab.server.audit.AuditService;
import ru.k6lab.server.generator.Generator;
import ru.k6lab.server.generator.GeneratorRepository;
import ru.k6lab.server.load.LoadService;
import ru.k6lab.server.load.RunPlan;
import ru.k6lab.server.load.RunPlanner;
import ru.k6lab.server.run.Run;
import ru.k6lab.server.run.RunService;
import ru.k6lab.server.script.ScriptService;
import ru.k6lab.server.secret.SecretService;
import ru.k6lab.server.settings.SettingsService;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Запуск по расписанию: в назначенное время сервер создаёт обычный запуск с заданными переменными и интенсивностью по операциям.
 * Если генераторов в этот момент не хватает или запуск не создаётся, запись помечается ошибкой с причиной (в очередь запуск не ставится).
 */
@Service
public class ScheduledRunService {

    private static final Logger log = LoggerFactory.getLogger(ScheduledRunService.class);
    private static final Pattern ENV_NAME = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");
    /** Позже назначенного времени на столько запуск ещё стартует (сервер мог быть недоступен); дальше - пропуск. */
    static final Duration MAX_LATE = Duration.ofMinutes(2);
    static final Duration MIN_LEAD = Duration.ofSeconds(30);
    public static final double MIN_PCT = 1;
    public static final double MAX_PCT = 10_000;

    private final ScheduledRunStore store;
    private final RunService runs;
    private final RunPlanner planner;
    private final ScriptService scripts;
    private final GeneratorRepository generators;
    private final SecretService secrets;
    private final AuditService audit;
    private final LoadService load;
    private final ScheduleTimelineService timeline;
    private final SettingsService settings;
    private final ScheduleGuardService guards;

    /** Ключ настройки: значение «1» - планировщик на паузе. */
    public static final String PAUSED_KEY = "schedule.paused";
    /** Причина автоматической паузы (например, сработала блокирующая автопроверка). */
    public static final String PAUSE_REASON_KEY = "schedule.pause_reason";

    /** Защита от гонки между фоновым tick() и снятием паузы resume(). */
    private final Object schedulerLock = new Object();

    /** Результат снятия паузы планировщика: немедленно запущенный тест (если генераторы свободны) и число сдвинутых записей очереди. */
    public record ResumeResult(Long startedScheduleId, Long startedRunId, String startedName, int moved) {
    }

    /** Позиция при постановке запуска в очередь расписания: FRONT — в начало очереди (сразу после текущего запуска), BACK — в конец очереди. */
    public enum QueuePosition { FRONT, BACK }

    /** Результат постановки в очередь (или перемещения в очереди): id созданной/перемещённой записи, назначенное время и сколько других записей сдвинуто. */
    public record EnqueueResult(long scheduleId, Instant runAt, String title, int shiftedCount) {
    }

    public ScheduledRunService(ScheduledRunStore store, RunService runs, RunPlanner planner, ScriptService scripts,
                               GeneratorRepository generators, SecretService secrets, AuditService audit, LoadService load,
                               ScheduleTimelineService timeline, SettingsService settings, ScheduleGuardService guards) {
        this.settings = settings;
        this.guards = guards;
        this.load = load;
        this.timeline = timeline;
        this.store = store;
        this.runs = runs;
        this.planner = planner;
        this.scripts = scripts;
        this.generators = generators;
        this.secrets = secrets;
        this.audit = audit;
    }

    @PostConstruct
    void init() {
        store.failStarting();
    }

    /**
     * Планирует запуск. Всё проверяется сразу, чтобы ошибка была видна при планировании, а не в назначенный час.
     * env == null - переменные как в скрипте на момент запуска; иначе полный набор переменных этого запуска.
     */
    public long schedule(long scriptId, Instant runAt, List<Long> generatorIds, Integer count, Map<String, String> env,
                         Map<String, Double> intensityPct, String user) {
        return schedule(scriptId, runAt, generatorIds, count, env, intensityPct, null, user);
    }

    /** То же с профилем нагрузки: операции, сценарии и переменные берутся из профиля (profileId == null - нагрузка скрипта как сохранена). */
    public long schedule(long scriptId, Instant runAt, List<Long> generatorIds, Integer count, Map<String, String> env,
                         Map<String, Double> intensityPct, Long profileId, String user) {
        return schedule(scriptId, runAt, generatorIds, count, env, intensityPct, profileId, user, false);
    }

    /** shiftForward: если генераторы освободились раньше (ранняя остановка предыдущего запуска), запись переносится на более раннее время. */
    public long schedule(long scriptId, Instant runAt, List<Long> generatorIds, Integer count, Map<String, String> env,
                         Map<String, Double> intensityPct, Long profileId, String user, boolean shiftForward) {
        var script = scripts.get(scriptId);
        if (runAt != null && runAt.isBefore(Instant.now().plus(MIN_LEAD))) {
            throw new IllegalArgumentException("Время запуска должно быть в будущем, не раньше чем через " + MIN_LEAD.toSeconds() + " секунд");
        }
        List<Long> ids = generatorIds == null ? List.of() : generatorIds.stream().distinct().sorted().toList();
        int n = !ids.isEmpty() ? ids.size() : count == null ? script.getGeneratorCount() : count;
        if (n < 1) {
            throw new IllegalArgumentException("Число генераторов должно быть не меньше 1");
        }
        for (Long id : ids) {
            if (!generators.existsById(id)) {
                throw new IllegalArgumentException("Генератор не найден");
            }
        }
        LoadService.BuiltLoad built = profileId == null ? null : load.fromProfile(scriptId, profileId);
        RunPlan plan = planner.plan(scriptId, n, built);
        if (!plan.ok()) {
            throw new IllegalArgumentException("Скрипт нельзя запустить: " + String.join("; ", plan.errors()));
        }
        Set<String> ops = new HashSet<>();
        plan.operations().forEach(o -> ops.add(o.getName()));
        Map<String, Double> pct = new LinkedHashMap<>();
        if (intensityPct != null) {
            intensityPct.forEach((op, v) -> {
                if (!ops.contains(op)) {
                    throw new IllegalArgumentException("Операции «" + op + "» нет в нагрузке скрипта");
                }
                if (v == null || !Double.isFinite(v) || v < MIN_PCT || v > MAX_PCT) {
                    throw new IllegalArgumentException("Интенсивность операции «" + op + "»: от " + (int) MIN_PCT + " до " + (int) MAX_PCT + " %");
                }
                if (Math.abs(v - 100) > 1e-9) {
                    pct.put(op, v);
                }
            });
        }
        Map<String, String> cleanEnv = env == null ? null : cleanEnv(env);
        if (cleanEnv != null) {
            secrets.requireExist(SecretService.referencedNames(cleanEnv));
        }
        int dur = plan.totalSec() > 0 ? plan.totalSec() : ScheduleTimelineService.FALLBACK_SEC;
        ScheduleTimelineService.Snapshot snap = timeline.snapshot();
        if (runAt == null) {
            // время не выбрано: самое раннее, когда нужные генераторы свободны на весь запуск
            runAt = timeline.earliestSlot(snap, ids, n, dur, Instant.now().plus(MIN_LEAD).plusSeconds(30));
        }
        if (!ids.isEmpty()) {
            timeline.requireFree(snap, ids, runAt, dur);
        } else {
            // «любые свободные»: генераторы бронируются сразу, чтобы другие запуски и записи расписания их не заняли
            ids = timeline.pickFree(snap, runAt, dur, n).stream().sorted().toList();
        }
        long id = store.create(scriptId, script.getName(), runAt, user, ids, null, cleanEnv, pct, profileId,
                built == null ? null : built.profileName(), shiftForward);
        audit.record("run.schedule", "script", scriptId, Map.of("scheduleId", id, "runAt", runAt.toString()));
        return id;
    }

    /** Результат пакетного создания записи расписания (без повторного чтения из БД). */
    record BatchCreated(long id, Instant runAt, List<Long> generatorIds, int durationSec) {
    }

    /**
     * Быстрое планирование одного теста внутри пакета (импорт списка тестов или последовательная серия):
     * переиспользует общий {@link ScheduleTimelineService.Snapshot} и кэш {@link RunPlan}, обновляя занятость в памяти.
     */
    BatchCreated scheduleInBatch(ScheduleTimelineService.Snapshot snap, Map<String, RunPlan> planCache,
                                 Map<String, LoadService.BuiltLoad> builtCache,
                                 ru.k6lab.server.script.Script script, Instant runAt, Instant notBefore,
                                 List<Long> generatorIds, Integer count, Map<String, String> env,
                                 Long profileId, String user, boolean shiftForward) {
        if (runAt != null && runAt.isBefore(Instant.now().plus(MIN_LEAD))) {
            throw new IllegalArgumentException("Время запуска должно быть в будущем, не раньше чем через " + MIN_LEAD.toSeconds() + " секунд");
        }
        List<Long> ids = generatorIds == null ? List.of() : generatorIds.stream().distinct().sorted().toList();
        int n = !ids.isEmpty() ? ids.size() : count == null ? script.getGeneratorCount() : count;
        if (n < 1) {
            throw new IllegalArgumentException("Число генераторов должно быть не меньше 1");
        }
        String builtKey = script.getId() + ":" + profileId;
        LoadService.BuiltLoad built = profileId == null ? null
                : builtCache.computeIfAbsent(builtKey, k -> load.fromProfile(script.getId(), profileId));
        String planKey = builtKey + ":" + n;
        RunPlan plan = planCache.computeIfAbsent(planKey, k -> planner.plan(script.getId(), n, built));
        if (!plan.ok()) {
            throw new IllegalArgumentException("Скрипт нельзя запустить: " + String.join("; ", plan.errors()));
        }
        Map<String, String> cleanEnv = env == null ? null : cleanEnv(env);
        if (cleanEnv != null) {
            secrets.requireExist(SecretService.referencedNames(cleanEnv));
        }
        int dur = plan.totalSec() > 0 ? plan.totalSec() : ScheduleTimelineService.FALLBACK_SEC;
        Instant slot = runAt != null ? runAt
                : timeline.earliestSlot(snap, ids, n, dur, notBefore != null ? notBefore : Instant.now().plus(MIN_LEAD).plusSeconds(30));
        if (!ids.isEmpty()) {
            timeline.requireFree(snap, ids, slot, dur);
        } else {
            ids = timeline.pickFree(snap, slot, dur, n).stream().sorted().toList();
        }
        long id = store.create(script.getId(), script.getName(), slot, user, ids, null, cleanEnv, Map.of(), profileId,
                built == null ? null : built.profileName(), shiftForward);
        snap.book(id, ids, slot, dur);
        audit.record("run.schedule", "script", script.getId(), Map.of("scheduleId", id, "runAt", slot.toString()));
        return new BatchCreated(id, slot, ids, dur);
    }

    /** Элемент последовательной серии запусков (скрипт + опциональный профиль). */
    public record SequentialSpec(long scriptId, Long profileId) {
    }

    /**
     * Последовательное планирование серии тестов: каждый следующий тест стартует строго после окончания предыдущего
     * плюс пауза (не меньше {@link ScheduleTimelineService#TAIL_SEC}), даже если свободных генераторов больше, чем нужно.
     * Для последующих тестов с тем же или меньшим числом генераторов переиспользуются генераторы предыдущего теста,
     * чтобы сдвиг очереди ({@code shiftForward}) не запускал серию параллельно.
     * Всё или ничего: при ошибке на любом элементе уже созданные в этой серии записи отменяются.
     */
    public List<Long> scheduleSequential(List<SequentialSpec> items, Instant firstRunAt, Integer countOverride,
                                         int gapSec, boolean shiftForward, String user) {
        if (items == null || items.isEmpty()) {
            throw new IllegalArgumentException("Список тестов для планирования пуст");
        }
        if (firstRunAt != null && firstRunAt.isBefore(Instant.now().plus(MIN_LEAD))) {
            throw new IllegalArgumentException("Время запуска должно быть в будущем, не раньше чем через " + MIN_LEAD.toSeconds() + " секунд");
        }
        if (countOverride != null && countOverride < 1) {
            throw new IllegalArgumentException("Число генераторов должно быть не меньше 1");
        }
        int pauseSec = Math.max(ScheduleTimelineService.TAIL_SEC, Math.min(24 * 3600, gapSec));
        Instant cursor = firstRunAt != null ? firstRunAt : Instant.now().plus(MIN_LEAD).plusSeconds(30);
        ScheduleTimelineService.Snapshot snap = timeline.snapshot();
        Map<String, RunPlan> planCache = new java.util.HashMap<>();
        Map<String, LoadService.BuiltLoad> builtCache = new java.util.HashMap<>();
        Map<Long, ru.k6lab.server.script.Script> scriptCache = new java.util.HashMap<>();
        List<Long> created = new ArrayList<>();
        List<Long> prevBooked = List.of();
        try {
            for (SequentialSpec spec : items) {
                var script = scriptCache.computeIfAbsent(spec.scriptId(), scripts::get);
                int n = countOverride != null ? countOverride : Math.max(1, script.getGeneratorCount());
                List<Long> targetIds = !prevBooked.isEmpty() && n <= prevBooked.size() ? prevBooked.subList(0, n) : List.of();
                boolean itemShift = shiftForward && (created.isEmpty() || !targetIds.isEmpty());
                BatchCreated res = scheduleInBatch(snap, planCache, builtCache, script, null, cursor, targetIds, n,
                        null, spec.profileId(), user, itemShift);
                created.add(res.id());
                if (!res.generatorIds().isEmpty()) {
                    prevBooked = res.generatorIds();
                }
                cursor = res.runAt().plusSeconds((long) res.durationSec() + pauseSec);
            }
            return created;
        } catch (RuntimeException e) {
            created.forEach(id -> cancel(id, user));
            throw e;
        }
    }

    /** Переменные: имена как у переменных окружения, значения - строки. */
    static Map<String, String> cleanEnv(Map<String, String> env) {
        Map<String, String> out = new LinkedHashMap<>();
        env.forEach((k, v) -> {
            String name = k == null ? "" : k.strip();
            if (!ENV_NAME.matcher(name).matches()) {
                throw new IllegalArgumentException("Недопустимое имя переменной «" + name + "»: буквы, цифры и _, не с цифры");
            }
            out.put(name, v == null ? "" : v);
        });
        return out;
    }

    public boolean cancel(long id, String user) {
        boolean ok = store.cancel(id);
        if (ok) {
            audit.record("run.schedule_cancel", "scheduled_run", id, Map.of("by", String.valueOf(user)));
        }
        return ok;
    }

    public List<ScheduledRunStore.Item> recent() {
        return store.recent(100);
    }

    public List<ScheduledRunStore.Item> pendingOf(long scriptId) {
        return store.pendingOfScript(scriptId);
    }

    /**
     * Повторно ставит в очередь запись расписания (завершённую, упавшую, отменённую или ожидающую) с сохранением всех её параметров
     * (скрипт, профиль, генераторы, переменные, масштаб интенсивности):
     * - {@link QueuePosition#FRONT} — в самое начало очереди (сразу после текущего выполняющегося теста или через 1 мин, если сейчас ничего не идёт),
     *   автоматически сдвигая все остальные ожидающие записи следом;
     * - {@link QueuePosition#BACK} — в конец очереди (после всех уже запланированных ожидающих тестов).
     */
    public EnqueueResult requeueScheduleItem(long sourceScheduleId, QueuePosition position, String user) {
        ScheduledRunStore.Item src = store.find(sourceScheduleId)
                .orElseThrow(() -> new IllegalArgumentException("Запись расписания #" + sourceScheduleId + " не найдена"));
        return enqueueParams(src.scriptId(), src.profileId(), src.generatorIds(), src.generatorCount(),
                src.env(), src.intensity(), position, user);
    }

    /**
     * Повторно ставит в очередь уже проведённый (или идущий) прогон {@link Run} по его id:
     * переносит скрипт, профиль нагрузки, генераторы и переменные/интенсивность (если запуск был из расписания)
     * в начало очереди (сразу после текущего запуска) или в конец очереди.
     */
    public EnqueueResult requeueRun(long runId, QueuePosition position, String user) {
        Run run = runs.get(runId);
        if (run.getScriptId() == null) {
            throw new IllegalArgumentException("У запуска #" + runId + " не указан скрипт");
        }
        Map<String, Object> req = run.getRequest() == null ? Map.of() : run.getRequest();
        Long scheduleId = toLong(req.get("scheduleId"));
        if (scheduleId != null) {
            var fromSched = store.find(scheduleId);
            if (fromSched.isPresent()) {
                ScheduledRunStore.Item s = fromSched.get();
                return enqueueParams(run.getScriptId(), s.profileId(), s.generatorIds(), s.generatorCount(),
                        s.env(), s.intensity(), position, user);
            }
        }
        Long profileId = toLong(req.get("loadProfileId"));
        List<Long> genIds = new ArrayList<>(runs.generatorsOf(runId).stream()
                .map(ru.k6lab.server.run.RunGenerator::getGeneratorId)
                .filter(java.util.Objects::nonNull)
                .distinct().sorted().toList());
        if (genIds.isEmpty() && req.get("generators") instanceof List<?> sel) {
            for (Object item : sel) {
                if (item instanceof Map<?, ?> m && m.get("id") instanceof Number num) {
                    long gid = num.longValue();
                    if (!genIds.contains(gid)) {
                        genIds.add(gid);
                    }
                }
            }
            genIds.sort(Comparator.naturalOrder());
        }
        Integer count = genIds.isEmpty() ? scripts.get(run.getScriptId()).getGeneratorCount() : genIds.size();
        return enqueueParams(run.getScriptId(), profileId, genIds, count, null, Map.of(), position, user);
    }

    private static Long toLong(Object val) {
        if (val instanceof Number n) {
            return n.longValue();
        }
        if (val instanceof String s && !s.isBlank()) {
            try {
                return Long.parseLong(s.trim());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    /**
     * Перемещает уже ожидающую запись расписания (PENDING) в начало очереди (после текущего теста) или в самый конец очереди.
     */
    public EnqueueResult movePending(long scheduleId, QueuePosition position, String user) {
        synchronized (schedulerLock) {
            ScheduledRunStore.Item target = store.find(scheduleId)
                    .orElseThrow(() -> new IllegalArgumentException("Запись расписания #" + scheduleId + " не найдена"));
            if (!target.pending()) {
                throw new IllegalArgumentException("Запись #" + scheduleId + " уже не ожидает в очереди");
            }
            List<ScheduledRunStore.Item> pending = new ArrayList<>(store.active().stream()
                    .filter(ScheduledRunStore.Item::pending)
                    .toList());
            pending.removeIf(i -> i.id() == scheduleId);
            if (position == QueuePosition.FRONT) {
                pending.add(0, target);
            } else {
                pending.add(target);
            }
            int shifted = reflowPendingOrder(pending);
            ScheduledRunStore.Item updated = store.find(scheduleId).orElse(target);
            String title = updated.profileName() == null || updated.profileName().isBlank()
                    ? updated.scriptName() : updated.scriptName() + " · " + updated.profileName();
            audit.record("run.schedule_move", "scheduled_run", scheduleId,
                    Map.of("position", position.name(), "runAt", updated.runAt().toString(), "shifted", shifted, "by", String.valueOf(user)));
            return new EnqueueResult(scheduleId, updated.runAt(), title, Math.max(0, shifted - 1));
        }
    }

    /**
     * Ставит тест с указанными параметрами в начало очереди (FRONT — сразу после текущего идущего теста, сдвигая остальные)
     * или в конец очереди (BACK — после всех ожидающих записей).
     */
    public EnqueueResult enqueueParams(long scriptId, Long profileId, List<Long> generatorIds, Integer count,
                                       Map<String, String> env, Map<String, Double> intensityPct,
                                       QueuePosition position, String user) {
        synchronized (schedulerLock) {
            var script = scripts.get(scriptId);
            Long validProfileId = profileId;
            LoadService.BuiltLoad built = null;
            if (validProfileId != null) {
                try {
                    built = load.fromProfile(scriptId, validProfileId);
                } catch (IllegalArgumentException e) {
                    validProfileId = null;
                }
            }
            List<Long> validGenIds = new ArrayList<>();
            if (generatorIds != null) {
                for (Long gid : generatorIds) {
                    if (gid != null && generators.existsById(gid) && !validGenIds.contains(gid)) {
                        validGenIds.add(gid);
                    }
                }
                validGenIds.sort(Comparator.naturalOrder());
            }
            int n = !validGenIds.isEmpty() ? validGenIds.size()
                    : (count != null && count >= 1 ? count : Math.max(1, script.getGeneratorCount()));
            RunPlan plan = planner.plan(scriptId, n, built);
            if (!plan.ok()) {
                throw new IllegalArgumentException("Скрипт нельзя запустить: " + String.join("; ", plan.errors()));
            }
            Set<String> ops = new HashSet<>();
            plan.operations().forEach(o -> ops.add(o.getName()));
            Map<String, Double> pct = new LinkedHashMap<>();
            if (intensityPct != null) {
                intensityPct.forEach((op, v) -> {
                    if (ops.contains(op) && v != null && Double.isFinite(v) && v >= MIN_PCT && v <= MAX_PCT && Math.abs(v - 100) > 1e-9) {
                        pct.put(op, v);
                    }
                });
            }
            Map<String, String> cleanEnv = env == null ? null : cleanEnv(env);
            if (cleanEnv != null) {
                secrets.requireExist(SecretService.referencedNames(cleanEnv));
            }
            int dur = plan.totalSec() > 0 ? plan.totalSec() : ScheduleTimelineService.FALLBACK_SEC;
            String title = built == null || built.profileName() == null || built.profileName().isBlank()
                    ? script.getName() : script.getName() + " · " + built.profileName();

            if (position == QueuePosition.BACK) {
                ScheduleTimelineService.Snapshot snap = timeline.snapshot();
                Instant notBefore = Instant.now().plus(MIN_LEAD).plusSeconds(30);
                List<ScheduledRunStore.Item> pending = store.active().stream()
                        .filter(ScheduledRunStore.Item::pending)
                        .toList();
                for (ScheduledRunStore.Item it : pending) {
                    Instant itemEnd = it.runAt().plusSeconds((long) timeline.durationSecFor(it) + ScheduleTimelineService.TAIL_SEC);
                    if (itemEnd.isAfter(notBefore)) {
                        notBefore = itemEnd;
                    }
                }
                Instant slot = timeline.earliestSlot(snap, validGenIds, n, dur, notBefore);
                List<Long> chosenIds = !validGenIds.isEmpty() ? validGenIds
                        : timeline.pickFree(snap, slot, dur, n).stream().sorted().toList();
                long id = store.create(scriptId, script.getName(), slot, user, chosenIds, null, cleanEnv, pct,
                        validProfileId, built == null ? null : built.profileName(), true);
                audit.record("run.schedule", "script", scriptId,
                        Map.of("scheduleId", id, "runAt", slot.toString(), "position", "BACK"));
                return new EnqueueResult(id, slot, title, 0);
            }

            // QueuePosition.FRONT: ставим первым сразу после текущего идущего теста и сдвигаем остальные ожидающие записи следом
            List<ScheduledRunStore.Item> existingPending = store.active().stream()
                    .filter(ScheduledRunStore.Item::pending)
                    .toList();
            ScheduleTimelineService.Snapshot snap = timeline.snapshotRunningWithCooldown();
            Instant earliest = Instant.now().plus(MIN_LEAD).plusSeconds(30);
            Instant firstSlot = timeline.earliestSlot(snap, validGenIds, n, dur, earliest);
            List<Long> chosenIds = !validGenIds.isEmpty() ? validGenIds
                    : timeline.pickFree(snap, firstSlot, dur, n).stream().sorted().toList();
            long newId = store.create(scriptId, script.getName(), firstSlot, user, chosenIds, null, cleanEnv, pct,
                    validProfileId, built == null ? null : built.profileName(), true);
            snap.book(newId, chosenIds, firstSlot, dur);

            Instant prevEnd = firstSlot.plusSeconds((long) dur + ScheduleTimelineService.TAIL_SEC);
            int shifted = 0;
            for (ScheduledRunStore.Item item : existingPending) {
                int itemN = !item.generatorIds().isEmpty() ? item.generatorIds().size()
                        : item.generatorCount() == null ? 1 : item.generatorCount();
                int itemDur = timeline.durationSecFor(item);
                Instant notBefore = item.runAt().isAfter(prevEnd) ? item.runAt() : prevEnd;
                Instant slot = findSlotOrFallback(snap, item.generatorIds(), itemN, itemDur, notBefore);
                if (!slot.equals(item.runAt()) && store.reschedule(item.id(), slot)) {
                    shifted++;
                }
                snap.book(item.id(), item.generatorIds(), slot, itemDur);
                prevEnd = slot.plusSeconds((long) itemDur + ScheduleTimelineService.TAIL_SEC);
            }
            audit.record("run.schedule", "script", scriptId,
                    Map.of("scheduleId", newId, "runAt", firstSlot.toString(), "position", "FRONT", "shifted", shifted));
            return new EnqueueResult(newId, firstSlot, title, shifted);
        }
    }

    /**
     * Перемещает уже ожидающую запись расписания (PENDING) на один шаг вверх ({@code delta = -1}) или вниз ({@code delta = +1})
     * в очереди ожидающих тестов, пересчитывая время старта всей очереди.
     */
    public EnqueueResult stepPending(long scheduleId, int delta, String user) {
        synchronized (schedulerLock) {
            ScheduledRunStore.Item target = store.find(scheduleId)
                    .orElseThrow(() -> new IllegalArgumentException("Запись расписания #" + scheduleId + " не найдена"));
            if (!target.pending()) {
                throw new IllegalArgumentException("Запись #" + scheduleId + " уже не ожидает в очереди");
            }
            List<ScheduledRunStore.Item> pending = new ArrayList<>(store.active().stream()
                    .filter(ScheduledRunStore.Item::pending)
                    .toList());
            int idx = -1;
            for (int i = 0; i < pending.size(); i++) {
                if (pending.get(i).id() == scheduleId) {
                    idx = i;
                    break;
                }
            }
            String title = target.profileName() == null || target.profileName().isBlank()
                    ? target.scriptName() : target.scriptName() + " · " + target.profileName();
            if (idx < 0) {
                return new EnqueueResult(scheduleId, target.runAt(), title, 0);
            }
            int nextIdx = Math.max(0, Math.min(pending.size() - 1, idx + delta));
            if (nextIdx == idx) {
                return new EnqueueResult(scheduleId, target.runAt(), title, 0);
            }
            ScheduledRunStore.Item removed = pending.remove(idx);
            pending.add(nextIdx, removed);
            int shifted = reflowPendingOrder(pending);
            ScheduledRunStore.Item updated = store.find(scheduleId).orElse(target);
            audit.record("run.schedule_step", "scheduled_run", scheduleId,
                    Map.of("delta", delta, "newIndex", nextIdx + 1, "runAt", updated.runAt().toString(), "by", String.valueOf(user)));
            return new EnqueueResult(scheduleId, updated.runAt(), title, shifted);
        }
    }

    /**
     * Устанавливает произвольный порядок ожидающих записей расписания (например, после Drag & Drop в таблице очереди)
     * и пересчитывает времена их старта без наложений с паузой 5 минут.
     */
    public int reorderPending(List<Long> orderedIds, String user) {
        synchronized (schedulerLock) {
            List<ScheduledRunStore.Item> current = store.active().stream()
                    .filter(ScheduledRunStore.Item::pending)
                    .toList();
            if (current.size() <= 1 || orderedIds == null || orderedIds.isEmpty()) {
                return 0;
            }
            Map<Long, ScheduledRunStore.Item> byId = new LinkedHashMap<>();
            current.forEach(i -> byId.put(i.id(), i));
            List<ScheduledRunStore.Item> ordered = new ArrayList<>(current.size());
            Set<Long> seen = new HashSet<>();
            for (Long id : orderedIds) {
                if (id != null && byId.containsKey(id) && seen.add(id)) {
                    ordered.add(byId.get(id));
                }
            }
            for (ScheduledRunStore.Item item : current) {
                if (seen.add(item.id())) {
                    ordered.add(item);
                }
            }
            int shifted = reflowPendingOrder(ordered);
            audit.record("run.schedule_reorder", "schedule", 0L,
                    Map.of("count", ordered.size(), "shifted", shifted, "by", String.valueOf(user)));
            return shifted;
        }
    }

    /**
     * Немедленно запускает выбранную ожидающую запись расписания (если её генераторы свободны прямо сейчас)
     * и сдвигает остальные ожидающие записи следом.
     */
    public ResumeResult startNow(long scheduleId, String user) {
        synchronized (schedulerLock) {
            ScheduledRunStore.Item target = store.find(scheduleId)
                    .orElseThrow(() -> new IllegalArgumentException("Запись расписания #" + scheduleId + " не найдена"));
            if (!target.pending()) {
                throw new IllegalArgumentException("Запись #" + scheduleId + " уже не ожидает в очереди");
            }
            if (!canStartNow(target)) {
                throw new IllegalStateException("Нужные генераторы сейчас заняты активным запуском или недоступны — дождитесь окончания текущего теста или поставьте запись в начало очереди");
            }
            ScheduleGuardService.BeforeStartResult guardRes = guards.runBeforeStartGuards(target.id());
            if (guardRes.blocked()) {
                throw new IllegalStateException("Запуск остановлен автопроверкой: " + guardRes.reason());
            }
            Instant now = Instant.now();
            store.reschedule(target.id(), now);
            if (!store.claim(target.id())) {
                throw new IllegalStateException("Запись #" + scheduleId + " уже захвачена другим потоком");
            }
            ScheduledRunStore.Item fresh = store.find(target.id()).orElse(target);
            startOne(fresh);
            ScheduledRunStore.Item after = store.find(target.id()).orElse(fresh);
            if (after.status() != ScheduledRunStore.Status.STARTED || after.runId() == null) {
                throw new IllegalStateException(after.error() != null ? after.error() : "Не удалось запустить тест");
            }
            List<ScheduledRunStore.Item> remaining = store.active().stream()
                    .filter(ScheduledRunStore.Item::pending)
                    .toList();
            int moved = reflowPendingOrder(remaining);
            String title = target.profileName() == null || target.profileName().isBlank()
                    ? target.scriptName() : target.scriptName() + " · " + target.profileName();
            audit.record("run.schedule_start_now", "scheduled_run", scheduleId,
                    Map.of("runId", after.runId(), "moved", moved, "by", String.valueOf(user)));
            return new ResumeResult(scheduleId, after.runId(), title, moved);
        }
    }

    /** Массовая отмена выбранных ожидающих записей расписания. */
    public int cancelMany(List<Long> ids, String user) {
        if (ids == null || ids.isEmpty()) {
            return 0;
        }
        int n = 0;
        for (Long id : ids) {
            if (id != null && cancel(id, user)) {
                n++;
            }
        }
        return n;
    }

    /** Массовая повторная постановка списка записей расписания в начало (после текущего) или в конец очереди с сохранением их взаимного порядка. */
    public int requeueMany(List<Long> ids, QueuePosition position, String user) {
        if (ids == null || ids.isEmpty()) {
            return 0;
        }
        List<Long> ordered = new ArrayList<>(ids);
        if (position == QueuePosition.FRONT) {
            // чтобы при поочерёдной вставке в начало взаимный порядок выбранных записей сохранился, идём с конца списка
            java.util.Collections.reverse(ordered);
        }
        int count = 0;
        for (Long id : ordered) {
            if (id != null && store.find(id).isPresent()) {
                requeueScheduleItem(id, position, user);
                count++;
            }
        }
        return count;
    }

    /** Очищает историю завершённых, отменённых и упавших записей расписания (ожидающие записи не затрагиваются). */
    public int clearHistory(String user) {
        int n = store.clearHistory();
        if (n > 0) {
            audit.record("schedule.clear_history", "schedule", 0L, Map.of("deleted", n, "by", String.valueOf(user)));
        }
        return n;
    }

    /** Переключает флаг автосдвига (shift_forward) у ожидающей записи. */
    public boolean setShiftForward(long scheduleId, boolean shiftForward, String user) {
        boolean ok = store.setShiftForward(scheduleId, shiftForward);
        if (ok) {
            audit.record("run.schedule_shift_flag", "scheduled_run", scheduleId,
                    Map.of("shiftForward", shiftForward, "by", String.valueOf(user)));
        }
        return ok;
    }

    /** Плановая длительность записи расписания в секундах (для отображения в таблице очереди). */
    public int durationSecOf(ScheduledRunStore.Item item) {
        return timeline.durationSecFor(item);
    }

    /** Перекладывает список ожидающих записей строго в переданном порядке после текущего выполняющегося теста. */
    private int reflowPendingOrder(List<ScheduledRunStore.Item> orderedPending) {
        ScheduleTimelineService.Snapshot snap = timeline.snapshotRunningWithCooldown();
        Instant prevEnd = Instant.now().plus(MIN_LEAD).plusSeconds(30);
        int shifted = 0;
        for (ScheduledRunStore.Item item : orderedPending) {
            int n = !item.generatorIds().isEmpty() ? item.generatorIds().size()
                    : item.generatorCount() == null ? 1 : item.generatorCount();
            int dur = timeline.durationSecFor(item);
            Instant slot = findSlotOrFallback(snap, item.generatorIds(), n, dur, prevEnd);
            if (!slot.equals(item.runAt()) && store.reschedule(item.id(), slot)) {
                shifted++;
            }
            snap.book(item.id(), item.generatorIds(), slot, dur);
            prevEnd = slot.plusSeconds((long) dur + ScheduleTimelineService.TAIL_SEC);
        }
        return shifted;
    }

    /** Планировщик на паузе: новые запуски по расписанию не стартуют (идущие тесты доходят до конца), записи ждут. */
    public boolean paused() {
        return "1".equals(settings.get(PAUSED_KEY, ""));
    }

    public String pauseReason() {
        String r = settings.get(PAUSE_REASON_KEY, "");
        return r == null || r.isBlank() ? null : r;
    }

    public void pause(String user) {
        pause(user, null);
    }

    public void pause(String user, String reason) {
        synchronized (schedulerLock) {
            boolean wasPaused = paused();
            if (!wasPaused) {
                settings.set(PAUSED_KEY, "1");
            }
            if (reason != null && !reason.isBlank()) {
                settings.set(PAUSE_REASON_KEY, reason);
            } else if (!wasPaused) {
                settings.delete(PAUSE_REASON_KEY);
            }
            if (!wasPaused) {
                Map<String, Object> d = new LinkedHashMap<>();
                d.put("by", String.valueOf(user));
                if (reason != null && !reason.isBlank()) {
                    d.put("reason", reason);
                }
                audit.record("schedule.pause", "schedule", 0L, d);
            }
        }
    }

    /**
     * Снимает паузу планировщика:
     * - если в очереди есть ожидающий тест и его генераторы свободны прямо сейчас — он запускается немедленно;
     * - все остальные тесты очереди сдвигаются следом в исходном порядке с соблюдением паузы между запусками;
     * - если генераторы сейчас заняты активным тестом (или недоступны), первый тест не падает в ошибку, а вся очередь
     *   выстраивается от ближайшего свободного слота после окончания текущего запуска.
     */
    public ResumeResult resume(String user) {
        synchronized (schedulerLock) {
            if (!paused()) {
                return new ResumeResult(null, null, null, 0);
            }
            List<ScheduledRunStore.Item> pending = store.active().stream()
                    .filter(ScheduledRunStore.Item::pending)
                    .toList();
            if (pending.isEmpty()) {
                settings.delete(PAUSED_KEY);
                settings.delete(PAUSE_REASON_KEY);
                audit.record("schedule.resume", "schedule", 0L, Map.of("by", String.valueOf(user), "moved", 0));
                return new ResumeResult(null, null, null, 0);
            }

            Instant now = Instant.now();
            ScheduledRunStore.Item first = pending.get(0);
            Long startedScheduleId = null;
            Long startedRunId = null;
            String startedName = null;
            int moved = 0;

            boolean startedFirst = false;
            if (canStartNow(first)) {
                ScheduleGuardService.BeforeStartResult guardRes = guards.runBeforeStartGuards(first.id());
                if (guardRes.blocked()) {
                    settings.set(PAUSE_REASON_KEY, guardRes.reason());
                    throw new IllegalStateException("Не удалось снять с паузы — автопроверка заблокировала старт: " + guardRes.reason());
                }
                store.reschedule(first.id(), now);
                if (store.claim(first.id())) {
                    ScheduledRunStore.Item fresh = store.find(first.id()).orElse(first);
                    startOne(fresh);
                    ScheduledRunStore.Item after = store.find(first.id()).orElse(fresh);
                    if (after.status() == ScheduledRunStore.Status.STARTED) {
                        startedFirst = true;
                        startedScheduleId = first.id();
                        startedRunId = after.runId();
                        startedName = first.profileName() == null || first.profileName().isBlank()
                                ? first.scriptName() : first.scriptName() + " · " + first.profileName();
                    }
                }
            }

            ScheduleTimelineService.Snapshot snap = timeline.snapshotRunningOnly();
            int startIdx;
            Duration delta;
            Instant prevEnd;
            if (startedFirst) {
                int firstDur = timeline.durationSecFor(first);
                delta = Duration.between(first.runAt(), now);
                prevEnd = now.plusSeconds((long) firstDur + ScheduleTimelineService.TAIL_SEC);
                startIdx = 1;
            } else {
                delta = null;
                prevEnd = now.plus(MIN_LEAD).plusSeconds(30);
                startIdx = 0;
            }

            for (int i = startIdx; i < pending.size(); i++) {
                ScheduledRunStore.Item item = pending.get(i);
                int n = !item.generatorIds().isEmpty() ? item.generatorIds().size()
                        : item.generatorCount() == null ? 1 : item.generatorCount();
                int dur = timeline.durationSecFor(item);
                Instant notBefore;
                if (delta == null) {
                    notBefore = prevEnd;
                } else {
                    Instant shifted = item.runAt().plus(delta);
                    notBefore = shifted.isAfter(prevEnd) ? shifted : prevEnd;
                }
                Instant slot = findSlotOrFallback(snap, item.generatorIds(), n, dur, notBefore);
                if (delta == null) {
                    delta = Duration.between(item.runAt(), slot);
                }
                if (!slot.equals(item.runAt()) && store.reschedule(item.id(), slot)) {
                    moved++;
                }
                snap.book(item.id(), item.generatorIds(), slot, dur);
                prevEnd = slot.plusSeconds((long) dur + ScheduleTimelineService.TAIL_SEC);
            }

            // снимаем паузу после перекладки очереди, чтобы фоновый tick() не успел пометить просроченные записи как FAILED
            settings.delete(PAUSED_KEY);
            settings.delete(PAUSE_REASON_KEY);
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("by", String.valueOf(user));
            details.put("moved", moved);
            if (startedRunId != null) {
                details.put("startedRunId", startedRunId);
                details.put("startedScheduleId", startedScheduleId);
            }
            audit.record("schedule.resume", "schedule", 0L, details);
            return new ResumeResult(startedScheduleId, startedRunId, startedName, moved);
        }
    }

    /** Проверяет, свободны ли нужные генераторы прямо сейчас (без учёта остывания уже завершившихся тестов). */
    private boolean canStartNow(ScheduledRunStore.Item item) {
        List<Generator> all = generators.findAll();
        Map<Long, Integer> activeByGen = new java.util.HashMap<>();
        for (Run r : runs.activeRuns()) {
            for (var rg : runs.generatorsOf(r.getId())) {
                if (rg.getGeneratorId() != null && !rg.getStatus().isDone()) {
                    activeByGen.merge(rg.getGeneratorId(), 1, Integer::sum);
                }
            }
        }
        java.util.function.Predicate<Generator> freeNow = g -> g.isAvailableForRuns()
                && activeByGen.getOrDefault(g.getId(), 0) < Math.max(1, g.getMaxConcurrentRuns());
        if (!item.generatorIds().isEmpty()) {
            for (Long id : item.generatorIds()) {
                Generator g = all.stream().filter(x -> x.getId().equals(id)).findFirst().orElse(null);
                if (g == null || !freeNow.test(g)) {
                    return false;
                }
            }
            return true;
        }
        int n = item.generatorCount() == null ? 1 : Math.max(1, item.generatorCount());
        long free = all.stream().filter(freeNow).count();
        return free >= n;
    }

    private Instant findSlotOrFallback(ScheduleTimelineService.Snapshot snap, List<Long> genIds, int n, int dur, Instant notBefore) {
        try {
            return timeline.earliestSlot(snap, genIds, n, dur, notBefore);
        } catch (IllegalArgumentException e) {
            return notBefore;
        }
    }

    @Scheduled(fixedDelay = 10_000, initialDelay = 20_000)
    public void tick() {
        synchronized (schedulerLock) {
            if (paused()) {
                return; // на паузе: ничего не стартуем и не помечаем просроченным
            }
            shiftQueue();
            for (ScheduledRunStore.Item item : store.due(Instant.now())) {
                ScheduleGuardService.BeforeStartResult guardRes = guards.runBeforeStartGuards(item.id());
                if (guardRes.blocked()) {
                    log.warn("Расписание #{}: старт заблокирован автопроверкой ({}); планировщик поставлен на паузу",
                            item.id(), guardRes.reason());
                    pause("schedule-guard", guardRes.reason());
                    return;
                }
                if (store.claim(item.id())) {
                    startOne(item);
                }
            }
        }
    }

    /** Минимальный выигрыш, ради которого запись переносится (чтобы время не прыгало из-за секунд). */
    static final Duration MIN_SHIFT = Duration.ofMinutes(1);

    /**
     * Сдвиг очереди: запись с флагом переносится на самое раннее время, когда её генераторы свободны
     * (с паузой между запусками), если это раньше назначенного не меньше чем на минуту.
     */
    void shiftQueue() {
        try {
            for (ScheduledRunStore.Item item : store.pendingShifting()) {
                Instant from = Instant.now().plus(MIN_LEAD).plusSeconds(30);
                if (!item.runAt().isAfter(from.plus(MIN_SHIFT))) {
                    continue;
                }
                Instant t = timeline.earliestStart(item, from);
                if (t != null && Duration.between(t, item.runAt()).compareTo(MIN_SHIFT) >= 0 && store.reschedule(item.id(), t)) {
                    log.info("Расписание #{}: сдвиг с {} на {} (генераторы освободились раньше)", item.id(), item.runAt(), t);
                    audit.record("run.schedule_shift", "scheduled_run", item.id(), Map.of("from", item.runAt().toString(), "to", t.toString()));
                }
            }
        } catch (RuntimeException e) {
            log.warn("Сдвиг очереди расписания не выполнен: {}", e.toString());
        }
    }

    void startOne(ScheduledRunStore.Item item) {
        try {
            Duration late = Duration.between(item.runAt(), Instant.now());
            if (late.compareTo(MAX_LATE) > 0) {
                store.failed(item.id(), "Пропущено: назначенное время прошло " + Math.max(1, late.toMinutes()) + " мин назад (сервер был недоступен)");
                return;
            }
            List<Generator> all = generators.findAll();
            List<Long> chosen;
            if (!item.generatorIds().isEmpty()) {
                List<String> busy = new ArrayList<>();
                for (Long id : item.generatorIds()) {
                    Generator g = all.stream().filter(x -> x.getId().equals(id)).findFirst().orElse(null);
                    if (g == null) {
                        busy.add("#" + id + " (удалён)");
                    } else if (!g.isAvailableForRuns()) {
                        busy.add(g.getName());
                    }
                }
                if (!busy.isEmpty()) {
                    store.failed(item.id(), "Пропущено: выбранные генераторы недоступны или заняты: " + String.join(", ", busy));
                    return;
                }
                chosen = item.generatorIds();
            } else {
                int n = item.generatorCount() == null ? 1 : item.generatorCount();
                List<Long> free = all.stream().filter(Generator::isAvailableForRuns).sorted(Comparator.comparing(Generator::getName))
                        .map(Generator::getId).toList();
                if (free.size() < n) {
                    store.failed(item.id(), "Пропущено: свободных генераторов " + free.size() + ", а нужно " + n);
                    return;
                }
                chosen = free.subList(0, n);
            }
            List<RunService.GeneratorSel> sel = chosen.stream().map(g -> new RunService.GeneratorSel(g, 1)).toList();
            Run run = runs.create(new RunService.CreateRequest(item.scriptId(), sel, Run.FailurePolicy.CONTINUE, 120, List.of(),
                    new RunService.Overrides(item.env(), item.intensity(), item.id(), item.createdBy(), item.profileId(), item.profileName())));
            store.started(item.id(), run.getId());
            log.info("Запуск по расписанию #{}: создан run {}", item.id(), run.getId());
        } catch (IOException | RuntimeException e) {
            log.warn("Запуск по расписанию #{} не создан: {}", item.id(), e.toString());
            store.failed(item.id(), "Запуск не создан: " + (e.getMessage() == null ? e.toString() : e.getMessage()));
        }
    }
}
