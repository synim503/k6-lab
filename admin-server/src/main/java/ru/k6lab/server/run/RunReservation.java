package ru.k6lab.server.run;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.k6lab.core.runstate.RunStatus;
import ru.k6lab.core.segment.ExecutionSegments;
import ru.k6lab.server.agent.AgentSessionRegistry;
import ru.k6lab.server.generator.Generator;
import ru.k6lab.server.generator.GeneratorRepository;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Захват генераторов под run. Все генераторы блокируются {@code SELECT ... FOR UPDATE SKIP LOCKED} в одной транзакции:
 * либо захвачены все нужные, либо ни один (run остаётся в очереди). Здесь же считаются execution segments.
 */
@Service
public class RunReservation {

    /** Порт REST API k6 на генераторе: 6565 + число уже идущих там run (у каждого k6 свой порт). */
    public static final int BASE_API_PORT = 6565;

    private final RunRepository runs;
    private final RunGeneratorRepository runGenerators;
    private final GeneratorRepository generators;
    private final AgentSessionRegistry registry;

    public RunReservation(RunRepository runs, RunGeneratorRepository runGenerators, GeneratorRepository generators,
                          AgentSessionRegistry registry) {
        this.runs = runs;
        this.runGenerators = runGenerators;
        this.generators = generators;
        this.registry = registry;
    }

    @Transactional
    @SuppressWarnings("unchecked")
    public Optional<List<RunGenerator>> reserve(Long runId) {
        Run run = runs.findById(runId).orElseThrow();
        if (run.getStatus() != RunStatus.QUEUED) {
            return Optional.empty();
        }
        Map<String, Object> req = run.getRequest();
        List<Generator> picked = new ArrayList<>();
        List<Integer> weights = new ArrayList<>();
        List<Map<String, Object>> sel = (List<Map<String, Object>>) req.get("generators");
        List<Long> ids = sel.stream().map(m -> ((Number) m.get("id")).longValue()).toList();
        Map<Long, Generator> locked = new HashMap<>();
        generators.lockAvailable(ids).forEach(g -> locked.put(g.getId(), g));
        if (locked.size() < ids.size()) {
            return Optional.empty();
        }
        for (Map<String, Object> m : sel) {
            picked.add(locked.get(((Number) m.get("id")).longValue()));
            weights.add(((Number) m.get("weight")).intValue());
        }
        // генератор числится ONLINE в БД, но связи может не быть (агент как раз отваливается) - не берём
        for (Generator g : picked) {
            if (!registry.isConnected(g.getId())) {
                return Optional.empty();
            }
        }

        ExecutionSegments.Plan plan = ExecutionSegments.weighted(weights);
        List<RunGenerator> out = new ArrayList<>();
        List<Map<String, Object>> info = new ArrayList<>();
        for (int i = 0; i < picked.size(); i++) {
            Generator g = picked.get(i);
            RunGenerator rg = new RunGenerator();
            rg.setRunId(run.getId());
            rg.setGeneratorId(g.getId());
            rg.setGeneratorName(g.getName());
            rg.setSeqIndex(i);
            rg.setWeight(weights.get(i));
            rg.setSegmentStart(plan.start(i).toString());
            rg.setSegmentEnd(plan.end(i).toString());
            rg.setStatus(RunGenerator.Status.RESERVED);
            rg.setK6ApiPort(BASE_API_PORT + g.getActiveRuns());
            g.setActiveRuns(g.getActiveRuns() + 1);
            out.add(runGenerators.save(rg));
            Map<String, Object> gi = new LinkedHashMap<>();
            gi.put("id", g.getId());
            gi.put("name", g.getName());
            gi.put("weight", weights.get(i));
            gi.put("segment", rg.segment());
            info.add(gi);
        }
        Map<String, Object> snap = new LinkedHashMap<>(run.getSnapshot());
        snap.put("generators", info);
        snap.put("segmentSequence", plan.sequence());
        run.setSnapshot(snap);
        run.transitionTo(RunStatus.PREPARING);
        runs.save(run);
        return Optional.of(out);
    }

    /** Выбранные для run генераторы, которых больше нет (удалены, пока run ждал в очереди): такой run не запустится никогда. */
    @Transactional(readOnly = true)
    @SuppressWarnings("unchecked")
    public List<Long> missingGenerators(Long runId) {
        Run run = runs.findById(runId).orElse(null);
        if (run == null || run.getRequest() == null || !(run.getRequest().get("generators") instanceof List<?> sel)) {
            return List.of();
        }
        List<Long> missing = new ArrayList<>();
        for (Map<String, Object> m : (List<Map<String, Object>>) sel) {
            long id = ((Number) m.get("id")).longValue();
            if (!generators.existsById(id)) {
                missing.add(id);
            }
        }
        return missing;
    }

    /** Освобождает генераторы run (active_runs - 1). Вызывается ровно один раз при завершении. */
    @Transactional
    public void release(List<Long> generatorIds) {
        for (Long id : generatorIds) {
            generators.findById(id).ifPresent(g -> g.setActiveRuns(Math.max(0, g.getActiveRuns() - 1)));
        }
    }
}
