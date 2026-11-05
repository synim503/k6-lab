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

    /** Сколько генераторов показывать на главной. */
    static final int GENERATORS_ON_DASHBOARD = 6;

    private final GeneratorViewService views;
    private final DeploymentRepository deployments;
    private final RunService runs;
    private final RunViewService runViews;
    private final ScriptService scripts;
    private final ru.k6lab.server.script.StorageHealth storageHealth;

    public DashboardController(GeneratorViewService views, DeploymentRepository deployments, RunService runs,
                               RunViewService runViews, ScriptService scripts, ru.k6lab.server.script.StorageHealth storageHealth) {
        this.storageHealth = storageHealth;
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
        // на главной - только самые важные: сначала проблемные, затем занятые, остальные по имени; полный список по ссылке «все»
        model.addAttribute("generatorsShown", all.stream()
                .sorted(java.util.Comparator.comparingInt(DashboardController::priority)
                        .thenComparing(v -> v.g().getName(), String.CASE_INSENSITIVE_ORDER))
                .limit(GENERATORS_ON_DASHBOARD).toList());
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
        model.addAttribute("scriptsNoFiles", storageHealth.scriptsWithoutFiles());
        model.addAttribute("storagePath", storageHealth.storagePath());
        model.addAttribute("lastRuns", runs.search(null, null, 8));
        addRunning(model);
        return "dashboard";
    }

    private static int priority(GeneratorView v) {
        Generator.Status st = v.g().getStatus();
        if (st == Generator.Status.ERROR || st == Generator.Status.OFFLINE) {
            return 0;
        }
        return v.g().isBusy() ? 1 : 2;
    }

    /** htmx-фрагмент «Запущенные скрипты»: обновляется каждые 5 секунд. */
    @GetMapping("/dashboard/running")
    public String running(Model model) {
        addRunning(model);
        return "dashboard :: running";
    }

    /** Блок «Запущенные скрипты» собирается не чаще раза в 3 с на всех зрителей: при 10-15 запусках это сотни запросов. */
    private static final long RUNNING_CACHE_MS = 3000;
    private List<RunViewService.RunView> runningCached = List.of();
    private long runningCachedAt;

    /** Сбрасывает кэш блока «Запущенные скрипты» (после запуска/остановки и в тестах). */
    public synchronized void invalidateRunning() {
        runningCachedAt = 0;
    }

    private synchronized List<RunViewService.RunView> runningViews() {
        long now = System.currentTimeMillis();
        if (now - runningCachedAt < RUNNING_CACHE_MS) {
            return runningCached;
        }
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
        runningCached = running;
        runningCachedAt = now;
        return running;
    }

    private void addRunning(Model model) {
        model.addAttribute("running", runningViews());
    }

    @GetMapping("/login")
    public String login() {
        return "login";
    }
}
