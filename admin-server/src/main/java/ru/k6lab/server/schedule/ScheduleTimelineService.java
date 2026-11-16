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

/**
 * Данные для графического таймлайна планирования: занятость генераторов текущими запусками и уже запланированными.
 * Запланированный запуск без выбранных серверов занимает «любые свободные» (count штук) - он показан отдельной полосой.
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
     * @param scriptId  скрипт, который планируется
     * @param profileId профиль нагрузки (null - нагрузка скрипта)
     * @param count     число генераторов нового запуска (для расчёта его длительности)
     */
    public Map<String, Object> build(long scriptId, Long profileId, Integer count) {
        long now = Instant.now().toEpochMilli();
        List<Generator> all = generators.findAll().stream().sorted(Comparator.comparing(Generator::getName)).toList();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("nowMs", now);

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
            for (RunGenerator rg : runs.generatorsOf(r.getId())) {
                if (rg.getGeneratorId() == null || rg.getStatus().isDone()) {
                    continue;
                }
                bars.add(bar("run", rg.getGeneratorId(), 1, r.getScriptName(), from, to, r.getId(), null));
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
            String label = it.scriptName() + (it.profileName() == null ? "" : " · " + it.profileName());
            if (!it.generatorIds().isEmpty()) {
                for (Long id : it.generatorIds()) {
                    bars.add(bar("scheduled", id, 1, label, from, to, null, it.id()));
                }
            } else {
                bars.add(bar("scheduled", null, n, label, from, to, null, it.id()));
            }
        }
        out.put("bars", bars);

        int mine = durationSec(scriptId, profileId, count == null ? scripts.get(scriptId).getGeneratorCount() : Math.max(1, count));
        out.put("durationSec", mine);
        out.put("tailSec", TAIL_SEC);
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

    private static Map<String, Object> bar(String kind, Long generatorId, int count, String label, long from, long to, Long runId, Long scheduleId) {
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("kind", kind);
        b.put("generatorId", generatorId);
        b.put("count", count);
        b.put("label", label);
        b.put("from", from);
        b.put("to", to);
        b.put("runId", runId);
        b.put("scheduleId", scheduleId);
        return b;
    }
}
