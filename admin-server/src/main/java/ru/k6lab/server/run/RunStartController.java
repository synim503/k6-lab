package ru.k6lab.server.run;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import ru.k6lab.server.generator.Generator;
import ru.k6lab.server.generator.GeneratorRepository;
import ru.k6lab.server.load.RunPlan;
import ru.k6lab.server.load.RunPlanner;
import ru.k6lab.server.script.ScriptService;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Запуск и остановка скрипта: число генераторов задаётся числом, конкретные хосты выбираются по желанию. */
@Controller
@RequestMapping("/scripts/{id}")
public class RunStartController {

    private final RunService runs;
    private final RunOrchestrator orchestrator;
    private final RunPlanner planner;
    private final ScriptService scripts;
    private final GeneratorRepository generators;

    public RunStartController(RunService runs, RunOrchestrator orchestrator, RunPlanner planner, ScriptService scripts,
                              GeneratorRepository generators) {
        this.runs = runs;
        this.orchestrator = orchestrator;
        this.planner = planner;
        this.scripts = scripts;
        this.generators = generators;
    }

    @GetMapping("/run")
    public String form(@PathVariable Long id, Model model) {
        fill(id, model);
        return "scripts/run";
    }

    private void fill(Long id, Model model) {
        List<Generator> all = generators.findAll().stream().sorted(Comparator.comparing(Generator::getName)).toList();
        var script = scripts.get(id);
        RunPlan plan = planner.plan(id, script.getGeneratorCount());
        model.addAttribute("script", script);
        model.addAttribute("defaultCount", script.getGeneratorCount());
        model.addAttribute("k6Flags", java.util.Arrays.stream(K6Flag.values()).filter(f -> !f.isHttpDebug()).toList());
        model.addAttribute("generators", all);
        model.addAttribute("freeCount", all.stream().filter(Generator::isAvailableForRuns).count());
        model.addAttribute("ops", plan.operations());
        model.addAttribute("durationSec", plan.steps() == null || plan.steps().isEmpty() ? 0 : plan.steps().get(0).holdSec());
        model.addAttribute("errors", plan.errors());
        model.addAttribute("warnings", plan.warnings());
        model.addAttribute("blockReason", runs.blockReason().orElse(null));
    }

    /**
     * Если отмечены хосты, берутся они, иначе нужное число свободных генераторов выбирается автоматически.
     */
    @PostMapping("/run")
    public String start(@PathVariable Long id, @RequestParam(name = "generatorId", required = false) List<Long> generatorIds,
                        @RequestParam(required = false) Integer count,
                        @RequestParam(name = "httpDebug", required = false) String httpDebug,
                        @RequestParam(name = "flag", required = false) List<String> flagIds, Model model) {
        try {
            List<String> ids = new ArrayList<>(flagIds == null ? List.of() : flagIds);
            if ("headers".equals(httpDebug)) {
                ids.add(K6Flag.HTTP_DEBUG.name());
            } else if ("full".equals(httpDebug)) {
                ids.add(K6Flag.HTTP_DEBUG_FULL.name());
            }
            List<K6Flag> flags = K6Flag.parse(ids);
            List<Long> chosen;
            if (generatorIds != null && !generatorIds.isEmpty()) {
                chosen = generatorIds.stream().sorted().toList();
            } else {
                int n = count == null ? scripts.get(id).getGeneratorCount() : count;
                if (n < 1) {
                    throw new IllegalArgumentException("Число генераторов должно быть не меньше 1");
                }
                List<Long> free = generators.findAll().stream().filter(Generator::isAvailableForRuns)
                        .sorted(Comparator.comparing(Generator::getName)).map(Generator::getId).toList();
                if (free.size() < n) {
                    throw new IllegalArgumentException("Свободных генераторов: " + free.size() + ", а нужно " + n);
                }
                chosen = free.subList(0, n);
            }
            List<RunService.GeneratorSel> sel = new ArrayList<>();
            for (Long g : chosen) {
                sel.add(new RunService.GeneratorSel(g, 1));
            }
            Run run;
            try {
                run = runs.create(new RunService.CreateRequest(id, sel, Run.FailurePolicy.CONTINUE, 120, flags));
            } catch (IOException e) {
                throw new IllegalStateException(e.getMessage(), e);
            }
            // число генераторов запоминаем в скрипте: в следующий раз форма запуска подставит его само
            if ((generatorIds == null || generatorIds.isEmpty()) && count != null && count >= 1) {
                scripts.rememberGeneratorCount(id, count);
            }
            return "redirect:/runs/" + run.getId();
        } catch (IllegalArgumentException | IllegalStateException e) {
            model.addAttribute("error", e.getMessage());
            fill(id, model);
            return "scripts/run";
        }
    }

    /** Останавливает все запущенные прогоны скрипта. */
    @PostMapping("/stop")
    public String stop(@PathVariable Long id, RedirectAttributes ra) {
        int stopped = 0;
        for (Run r : runs.activeRuns()) {
            if (id.equals(r.getScriptId())) {
                try {
                    orchestrator.stop(r.getId(), "остановлено пользователем");
                    stopped++;
                } catch (IllegalStateException e) {
                    ra.addFlashAttribute("error", e.getMessage());
                }
            }
        }
        if (stopped == 0 && !ra.getFlashAttributes().containsKey("error")) {
            ra.addFlashAttribute("info", "Запущенных прогонов этого скрипта нет");
        }
        return "redirect:/scripts/" + id;
    }
}
