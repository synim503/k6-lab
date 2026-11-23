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
    private final ru.k6lab.server.schedule.ScheduledRunService schedule;
    private final ru.k6lab.server.load.LoadService load;
    private final ru.k6lab.server.load.LoadProfileStore profileStore;
    private static final com.fasterxml.jackson.databind.ObjectMapper JSON = new com.fasterxml.jackson.databind.ObjectMapper();

    public RunStartController(RunService runs, RunOrchestrator orchestrator, RunPlanner planner, ScriptService scripts,
                              GeneratorRepository generators, ru.k6lab.server.schedule.ScheduledRunService schedule,
                              ru.k6lab.server.load.LoadService load, ru.k6lab.server.load.LoadProfileStore profileStore) {
        this.load = load;
        this.profileStore = profileStore;
        this.schedule = schedule;
        this.runs = runs;
        this.orchestrator = orchestrator;
        this.planner = planner;
        this.scripts = scripts;
        this.generators = generators;
    }

    @GetMapping("/run")
    public String form(@PathVariable Long id, @RequestParam(required = false) Long profile, Model model) {
        model.addAttribute("selectedProfileId", profile);
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
        model.addAttribute("durationSec", totalDurationSec(plan));
        model.addAttribute("warnings", plan.warnings());
        model.addAttribute("blockReason", runs.blockReason().orElse(null));
        // запланированные запуски: переменные скрипта для редактирования и уже ожидающие этого скрипта
        StringBuilder env = new StringBuilder();
        script.getEnv().forEach((k, v) -> env.append(k).append('=').append(v).append('\n'));
        model.addAttribute("envText", env.toString());
        model.addAttribute("pendingSchedule", schedule.pendingOf(id));
        // профили нагрузки для планирования: выбор профиля меняет список операций (интенсивность), переменные и сводку справа
        var profilesList = profileStore.list(id);
        model.addAttribute("loadProfiles", profilesList);
        model.addAttribute("baseOk", plan.ok());
        Long selected = (Long) model.getAttribute("selectedProfileId");
        Long firstValidProfileId = null;
        java.util.Map<Long, List<String>> errorsByProfileId = new java.util.LinkedHashMap<>();
        try {
            java.util.Map<String, java.util.List<String>> opsByProfile = new java.util.LinkedHashMap<>();
            opsByProfile.put("", plan.operations().stream().map(o -> o.getName()).toList());
            load.profileOperationNames(id).forEach((pid, names) -> opsByProfile.put(String.valueOf(pid), names));
            java.util.Map<String, String> envByProfile = new java.util.LinkedHashMap<>();
            envByProfile.put("", env.toString());
            load.profileEnvText(id).forEach((pid, t) -> envByProfile.put(String.valueOf(pid), t));
            java.util.Map<String, Object> detailsByProfile = new java.util.LinkedHashMap<>();
            detailsByProfile.put("", summaryOf("Нагрузка скрипта", plan, script.getEnv().size()));
            for (var p : profilesList) {
                try {
                    var built = load.fromProfile(id, p.id());
                    RunPlan pp = planner.plan(id, script.getGeneratorCount(), built);
                    detailsByProfile.put(String.valueOf(p.id()), summaryOf("Профиль «" + p.name() + "»", pp, built.env().size()));
                    errorsByProfileId.put(p.id(), pp.errors());
                    if (firstValidProfileId == null && pp.ok()) {
                        firstValidProfileId = p.id();
                    }
                } catch (IllegalArgumentException | IllegalStateException e) {
                    List<String> errs = List.of(e.getMessage() == null ? "Профиль повреждён" : e.getMessage());
                    errorsByProfileId.put(p.id(), errs);
                    java.util.Map<String, Object> bad = new java.util.LinkedHashMap<>();
                    bad.put("title", "Профиль «" + p.name() + "»");
                    bad.put("durationSec", 0);
                    bad.put("envCount", 0);
                    bad.put("errors", errs);
                    bad.put("ops", List.of());
                    detailsByProfile.put(String.valueOf(p.id()), bad);
                }
            }
            model.addAttribute("profileOpsJson", JSON.writeValueAsString(opsByProfile));
            model.addAttribute("profileEnvJson", JSON.writeValueAsString(envByProfile));
            model.addAttribute("profileDetailsJson", JSON.writeValueAsString(detailsByProfile));
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            model.addAttribute("profileOpsJson", "{}");
            model.addAttribute("profileEnvJson", "{}");
            model.addAttribute("profileDetailsJson", "{}");
        }
        // если базовая нагрузка скрипта не задана, но есть готовые профили — сразу выбираем первый валидный профиль
        boolean autoSelected = false;
        if (selected == null && !plan.ok() && firstValidProfileId != null) {
            selected = firstValidProfileId;
            autoSelected = true;
            model.addAttribute("selectedProfileId", selected);
        }
        model.addAttribute("autoSelectedProfile", autoSelected);
        List<String> activeErrors = selected != null ? errorsByProfileId.getOrDefault(selected, List.of()) : plan.errors();
        model.addAttribute("errors", activeErrors);
    }

    private static int totalDurationSec(RunPlan p) {
        if (p.config() != null) {
            long span = ru.k6lab.core.k6config.ConfigDuration.of(p.config()).totalSec();
            if (span > 0) {
                return (int) Math.min(Integer.MAX_VALUE, span);
            }
        }
        return p.steps() == null ? 0 : p.steps().stream().mapToInt(ru.k6lab.core.profile.StepPlan::totalSec).sum();
    }

    private static java.util.Map<String, Object> summaryOf(String title, RunPlan p, int envCount) {
        java.util.Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("title", title);
        m.put("durationSec", totalDurationSec(p));
        m.put("envCount", envCount);
        m.put("errors", p.errors());
        java.util.List<java.util.Map<String, Object>> ops = new java.util.ArrayList<>();
        for (var o : p.operations()) {
            java.util.Map<String, Object> om = new java.util.LinkedHashMap<>();
            om.put("name", o.getName());
            om.put("execFn", o.getExecFn());
            om.put("ratePerSec", o.getRatePerSec());
            om.put("executor", o.getExecutor());
            om.put("slaPercentile", o.getSlaPercentile());
            om.put("slaThresholdMs", o.getSlaThresholdMs());
            ops.add(om);
        }
        m.put("ops", ops);
        return m;
    }

    /**
     * Если отмечены хосты, берутся они, иначе нужное число свободных генераторов выбирается автоматически.
     */
    @PostMapping("/run")
    public String start(@PathVariable Long id, @RequestParam(name = "generatorId", required = false) List<Long> generatorIds,
                        @RequestParam(required = false) Integer count,
                        @RequestParam(name = "httpDebug", required = false) String httpDebug,
                        @RequestParam(name = "flag", required = false) List<String> flagIds,
                        @RequestParam(name = "profile", required = false) Long profile, Model model) {
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
            // запуск с профилем нагрузки: операции, сценарии и переменные берутся из профиля, нагрузка скрипта не меняется
            RunService.Overrides ov = null;
            if (profile != null) {
                String profileName = load.fromProfile(id, profile).profileName();
                ov = new RunService.Overrides(null, java.util.Map.of(), null, null, profile, profileName);
            }
            Run run;
            try {
                run = runs.create(new RunService.CreateRequest(id, sel, Run.FailurePolicy.CONTINUE, 120, flags, ov));
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
            model.addAttribute("selectedProfileId", profile);
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
