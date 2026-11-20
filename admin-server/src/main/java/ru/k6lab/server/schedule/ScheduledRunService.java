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

    /** Ключ настройки: значение «1» - планировщик на паузе. */
    public static final String PAUSED_KEY = "schedule.paused";

    public ScheduledRunService(ScheduledRunStore store, RunService runs, RunPlanner planner, ScriptService scripts,
                               GeneratorRepository generators, SecretService secrets, AuditService audit, LoadService load,
                               ScheduleTimelineService timeline, SettingsService settings) {
        this.settings = settings;
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
        if (runAt == null) {
            // время не выбрано: самое раннее, когда нужные генераторы свободны на весь запуск
            runAt = timeline.earliestSlot(ids, n, dur, Instant.now().plus(MIN_LEAD).plusSeconds(30));
        }
        if (!ids.isEmpty()) {
            timeline.requireFree(ids, runAt, dur);
        } else {
            // «любые свободные»: генераторы бронируются сразу, чтобы другие запуски и записи расписания их не заняли
            ids = timeline.pickFree(runAt, dur, n).stream().sorted().toList();
        }
        long id = store.create(scriptId, script.getName(), runAt, user, ids, null, cleanEnv, pct, profileId,
                built == null ? null : built.profileName(), shiftForward);
        audit.record("run.schedule", "script", scriptId, Map.of("scheduleId", id, "runAt", runAt.toString()));
        return id;
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

    /** Планировщик на паузе: новые запуски по расписанию не стартуют (идущие тесты доходят до конца), записи ждут. */
    public boolean paused() {
        return "1".equals(settings.get(PAUSED_KEY, ""));
    }

    public void pause(String user) {
        if (!paused()) {
            settings.set(PAUSED_KEY, "1");
            audit.record("schedule.pause", "schedule", 0L, Map.of("by", String.valueOf(user)));
        }
    }

    /**
     * Снимает паузу. Записи, время которых прошло за паузу, не пропускаются как «просроченные»: каждая переносится на самое раннее
     * время, когда её генераторы свободны (с паузой между запусками), в прежнем порядке. Остальные остаются на своём времени.
     */
    public int resume(String user) {
        if (!paused()) {
            return 0;
        }
        settings.delete(PAUSED_KEY);
        int moved = 0;
        Instant notBefore = Instant.now().plus(MIN_LEAD).plusSeconds(30);
        for (ScheduledRunStore.Item item : store.due(notBefore.plus(MIN_SHIFT))) {
            Instant t = timeline.earliestFreeStart(item, notBefore);
            if (store.reschedule(item.id(), t)) {
                moved++;
            }
        }
        audit.record("schedule.resume", "schedule", 0L, Map.of("by", String.valueOf(user), "moved", moved));
        return moved;
    }

    @Scheduled(fixedDelay = 10_000, initialDelay = 20_000)
    public void tick() {
        if (paused()) {
            return; // на паузе: ничего не стартуем и не помечаем просроченным
        }
        shiftQueue();
        for (ScheduledRunStore.Item item : store.due(Instant.now())) {
            if (store.claim(item.id())) {
                startOne(item);
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
