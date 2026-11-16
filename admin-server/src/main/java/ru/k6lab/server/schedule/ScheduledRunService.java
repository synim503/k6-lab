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

    public ScheduledRunService(ScheduledRunStore store, RunService runs, RunPlanner planner, ScriptService scripts,
                               GeneratorRepository generators, SecretService secrets, AuditService audit, LoadService load) {
        this.load = load;
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
        var script = scripts.get(scriptId);
        if (runAt == null || runAt.isBefore(Instant.now().plus(MIN_LEAD))) {
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
        long id = store.create(scriptId, script.getName(), runAt, user, ids, ids.isEmpty() ? n : null, cleanEnv, pct, profileId,
                built == null ? null : built.profileName());
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

    @Scheduled(fixedDelay = 10_000, initialDelay = 20_000)
    public void tick() {
        for (ScheduledRunStore.Item item : store.due(Instant.now())) {
            if (store.claim(item.id())) {
                startOne(item);
            }
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
