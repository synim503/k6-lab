package ru.k6lab.server.web;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import ru.k6lab.server.deploy.DeploymentRepository;
import ru.k6lab.server.generator.Generator;
import ru.k6lab.server.generator.GeneratorView;
import ru.k6lab.server.generator.GeneratorViewService;
import ru.k6lab.server.run.Run;
import ru.k6lab.server.run.RunService;
import ru.k6lab.server.run.RunViewService;
import ru.k6lab.server.script.ScriptService;

import java.util.ArrayList;
import java.util.List;

/** Главная: запущенные скрипты, генераторы, последние запуски и быстрые переходы по разделам. */
@Controller
public class DashboardController {

    private final GeneratorViewService views;
    private final DeploymentRepository deployments;
    private final RunService runs;
    private final RunViewService runViews;
    private final ScriptService scripts;

    public DashboardController(GeneratorViewService views, DeploymentRepository deployments, RunService runs,
                               RunViewService runViews, ScriptService scripts) {
        this.views = views;
        this.deployments = deployments;
        this.runs = runs;
        this.runViews = runViews;
        this.scripts = scripts;
    }

    @GetMapping("/")
    public String dashboard(Model model) {
        List<GeneratorView> all = views.all();
        model.addAttribute("generators", all);
        model.addAttribute("total", all.size());
        model.addAttribute("online", all.stream().filter(v -> v.g().getStatus() == Generator.Status.ONLINE && !v.g().isMaintenance()).count());
        model.addAttribute("busy", all.stream().filter(v -> v.g().isBusy()).count());
        model.addAttribute("problems", all.stream().filter(v -> v.g().getStatus() == Generator.Status.ERROR
                || v.g().getStatus() == Generator.Status.OFFLINE).toList());
        model.addAttribute("hot", all.stream().filter(GeneratorView::cpuHot).toList());
        model.addAttribute("drift", all.stream().filter(GeneratorView::hasDrift).toList());
        model.addAttribute("recent", deployments.findTop50ByOrderByStartedAtDesc().stream().limit(8).toList());
        model.addAttribute("runsCount", runs.count());
        model.addAttribute("scriptsCount", scripts.list().size());
        model.addAttribute("lastRuns", runs.search(null, null, 8));
        addRunning(model);
        return "dashboard";
    }

    /** htmx-фрагмент «Запущенные скрипты»: обновляется каждые 5 секунд. */
    @GetMapping("/dashboard/running")
    public String running(Model model) {
        addRunning(model);
        return "dashboard :: running";
    }

    private void addRunning(Model model) {
        List<RunViewService.RunView> running = new ArrayList<>();
        List<Run> active = new ArrayList<>(runs.activeRuns());
        active.addAll(runs.search(null, ru.k6lab.core.runstate.RunStatus.QUEUED, 20));
        for (Run r : active) {
            try {
                running.add(runViews.view(r.getId()));
            } catch (RuntimeException ignored) {
                // run мог завершиться между запросами
            }
        }
        running.sort((a, b) -> Long.compare(b.run().getId(), a.run().getId()));
        model.addAttribute("running", running);
    }

    @GetMapping("/login")
    public String login() {
        return "login";
    }
}
