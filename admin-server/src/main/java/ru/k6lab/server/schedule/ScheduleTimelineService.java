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

    /** Запас после плановой длительности: остановка k6 и освобождение генератора. */
    static final int TAIL_SEC = 60;
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
