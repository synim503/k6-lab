package ru.k6lab.server.scenario;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import ru.k6lab.core.k6config.GeneratedConfig;
import ru.k6lab.core.util.ZipUtil;
import ru.k6lab.server.loadmodel.LoadModelService;
import ru.k6lab.server.profile.ProfileService;
import ru.k6lab.server.script.AdminHelper;
import ru.k6lab.server.script.K6Tool;
import ru.k6lab.server.script.ScriptService;
import ru.k6lab.server.sut.SutService;
import ru.k6lab.server.testdata.ScenarioDataFile;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;

@Controller
@RequestMapping("/scenarios")
public class ScenarioController {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final ScenarioService service;
    private final ScenarioPlanner planner;
    private final ScriptService scripts;
    private final LoadModelService models;
    private final ProfileService profiles;
    private final SutService suts;
    private final SecretService secrets;
    private final K6Tool k6;
    private final AdminHelper helper;

    public ScenarioController(ScenarioService service, ScenarioPlanner planner, ScriptService scripts,
                              LoadModelService models, ProfileService profiles, SutService suts, SecretService secrets,
                              K6Tool k6, AdminHelper helper) {
        this.service = service;
        this.planner = planner;
        this.scripts = scripts;
        this.models = models;
        this.profiles = profiles;
        this.suts = suts;
        this.secrets = secrets;
        this.k6 = k6;
        this.helper = helper;
    }

    @GetMapping
    public String list(Model model) {
        model.addAttribute("scenarios", service.list());
        model.addAttribute("scriptNames", scripts.list().stream().collect(java.util.stream.Collectors.toMap(s -> s.getId(), s -> s.getName())));
        model.addAttribute("modelNames", models.list().stream().collect(java.util.stream.Collectors.toMap(m -> m.getId(), m -> m.getName())));
        model.addAttribute("profileNames", profiles.list().stream().collect(java.util.stream.Collectors.toMap(p -> p.getId(), p -> p.getName())));
        return "scenarios/list";
    }

    // ------------------------------------------------------------------ форма

    @GetMapping("/new")
    public String create(@RequestParam(required = false) Long scriptId, Model model) {
        ScenarioForm f = new ScenarioForm();
        f.setScriptId(scriptId);
        fill(model, f);
        return "scenarios/form";
    }

    @GetMapping("/{id}/edit")
    public String edit(@PathVariable Long id, Model model) {
        fill(model, service.toForm(id));
        return "scenarios/form";
    }

    @PostMapping
    public String save(@ModelAttribute("form") ScenarioForm f, Model model, RedirectAttributes ra) {
        try {
            Scenario s = service.save(f);
            ra.addFlashAttribute("info", "Сценарий сохранён");
            return "redirect:/scenarios/" + s.getId();
        } catch (IllegalArgumentException | IllegalStateException e) {
            model.addAttribute("error", e.getMessage());
            fill(model, f);
            return "scenarios/form";
        }
    }

    /** htmx: модели выбранного скрипта. */
    @GetMapping("/options/models")
    public String modelOptions(@RequestParam(required = false) Long scriptId, Model model) {
        model.addAttribute("models", scriptId == null ? List.of() : models.list().stream().filter(m -> m.getScriptId().equals(scriptId)).toList());
        return "scenarios/_options :: models";
    }

    /** htmx: версии выбранного скрипта. */
    @GetMapping("/options/versions")
    public String versionOptions(@RequestParam(required = false) Long scriptId, Model model) {
        model.addAttribute("versions", scriptId == null ? List.of() : scripts.versionsOf(scriptId));
        return "scenarios/_options :: versions";
    }

    /** htmx: режимы файлов данных версии. */
    @GetMapping("/options/data")
    public String dataOptions(@RequestParam(required = false) Long scriptId, @RequestParam(required = false) Long versionId,
                              @RequestParam(required = false) Long scenarioId, Model model) {
        List<ScenarioForm.DataForm> existing = scenarioId == null ? List.of() : service.toForm(scenarioId).getDataFiles();
        model.addAttribute("rows", scriptId == null ? List.of() : service.dataRowsFor(scriptId, versionId, existing));
        model.addAttribute("modes", ScenarioDataFile.Mode.values());
        return "scenarios/_options :: data";
    }

    // ------------------------------------------------------------------ просмотр и превью

    @GetMapping("/{id}")
    public String view(@PathVariable Long id, @RequestParam(defaultValue = "1") int generators, Model model) throws IOException {
        Scenario s = service.get(id);
        ScenarioPlan plan = planner.plan(s, Math.max(1, generators), null);
        model.addAttribute("s", s);
        model.addAttribute("plan", plan);
        model.addAttribute("generators", plan.generatorCount());
        model.addAttribute("scriptName", scripts.get(s.getScriptId()).getName());
        model.addAttribute("dataFiles", service.dataFiles(id));
        model.addAttribute("chart", plan.generated() == null ? "null" : chartJson(plan.generated()));
        model.addAttribute("grafanaNote", s.getSutId() == null ? null : suts.get(s.getSutId()).getName());
        if (plan.generated() != null) {
            model.addAttribute("opNames", plan.generated().operations().stream().map(GeneratedConfig.OpPlan::name).toList());
            model.addAttribute("perGen", perGenerator(plan));
        }
        return "scenarios/view";
    }

    @GetMapping(value = "/{id}/config.json", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> configJson(@PathVariable Long id) {
        ScenarioPlan plan = planner.plan(service.get(id), 1, null);
        if (plan.configJson() == null) {
            return ResponseEntity.unprocessableEntity().body("{\"error\":\"" + String.join("; ", plan.errors()).replace("\"", "'") + "\"}");
        }
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename("k6-config-" + id + ".json").build().toString())
                .body(plan.configJson());
    }

    /** «Требования к ресурсам»: k6 inspect --execution-requirements с итоговым конфигом (htmx). */
    @PostMapping("/{id}/requirements")
    public String requirements(@PathVariable Long id, Model model) {
        ScenarioPlan plan = planner.plan(service.get(id), 1, null);
        if (plan.configJson() == null || plan.version() == null) {
            model.addAttribute("error", String.join("; ", plan.errors()));
            return "scenarios/_requirements :: requirements";
        }
        Path dir = null;
        try {
            dir = Files.createTempDirectory("k6lab-req");
            ZipUtil.extract(new ByteArrayInputStream(scripts.zipBytes(plan.version())), dir);
            Files.createDirectories(dir.resolve("__admin"));
            Files.write(dir.resolve("__admin/admin.js"), helper.content());
            K6Tool.Result r = k6.requirements(dir, plan.version().getEntrypoint(), plan.configJson());
            if (!r.ok()) {
                model.addAttribute("error", r.timedOut() ? "k6 inspect не завершился за 60 с" : r.stderr().strip());
            } else {
                var node = MAPPER.readTree(r.stdout());
                model.addAttribute("maxVus", node.path("maxVUs").asText("?"));
                model.addAttribute("totalDuration", node.path("totalDuration").asText("?"));
            }
        } catch (IOException e) {
            model.addAttribute("error", e.getMessage());
        } finally {
            if (dir != null) {
                try (var walk = Files.walk(dir)) {
                    walk.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
                } catch (IOException ignored) {
                    // best-effort
                }
            }
        }
        return "scenarios/_requirements :: requirements";
    }

    @PostMapping("/{id}/clone")
    public String clone(@PathVariable Long id, RedirectAttributes ra) {
        try {
            Scenario c = service.clone(id, null);
            return "redirect:/scenarios/" + c.getId() + "/edit";
        } catch (IllegalArgumentException e) {
            ra.addFlashAttribute("error", e.getMessage());
            return "redirect:/scenarios";
        }
    }

    @PostMapping("/{id}/delete")
    public String delete(@PathVariable Long id, RedirectAttributes ra) {
        try {
            service.delete(id);
            return "redirect:/scenarios";
        } catch (RuntimeException e) {
            ra.addFlashAttribute("error", "Сценарий используется запусками и не может быть удалён");
            return "redirect:/scenarios/" + id;
        }
    }

    // ------------------------------------------------------------------ вспомогательное

    private void fill(Model model, ScenarioForm f) {
        model.addAttribute("form", f);
        model.addAttribute("scripts", scripts.list());
        model.addAttribute("models", f.getScriptId() == null ? List.of() : models.list().stream().filter(m -> m.getScriptId().equals(f.getScriptId())).toList());
        model.addAttribute("versions", f.getScriptId() == null ? List.of() : scripts.versionsOf(f.getScriptId()));
        model.addAttribute("profiles", profiles.list());
        model.addAttribute("suts", suts.list());
        model.addAttribute("secretNames", secrets.list().stream().map(Secret::getName).toList());
        model.addAttribute("modes", ScenarioDataFile.Mode.values());
        model.addAttribute("rows", f.getScriptId() == null ? List.of()
                : service.dataRowsFor(f.getScriptId(), f.getVersionMode() == ScenarioForm.VersionMode.PINNED ? f.getScriptVersionId() : null, f.getDataFiles()));
        model.addAttribute("scriptVersion", f.getScriptId() == null ? null : scripts.latest(f.getScriptId()).orElse(null));
    }

    /** Данные для stacked-графика интенсивности по операциям: точки (минуты, оп/сек). */
    static String chartJson(GeneratedConfig g) {
        ObjectNode root = MAPPER.createObjectNode();
        ArrayNode datasets = root.putArray("datasets");
        for (GeneratedConfig.OpPlan p : g.operations()) {
            ObjectNode ds = datasets.addObject();
            ds.put("label", p.name());
            ArrayNode pts = ds.putArray("data");
            double prev = g.windows().get(0).rampStartSec() == g.windows().get(0).holdStartSec() ? p.ratesPerSec()[0] : 0;
            for (int i = 0; i < g.windows().size(); i++) {
                var w = g.windows().get(i);
                double r = p.ratesPerSec()[i];
                pts.addObject().put("x", w.rampStartSec() / 60.0).put("y", round(prev));
                pts.addObject().put("x", w.holdStartSec() / 60.0).put("y", round(r));
                pts.addObject().put("x", w.holdEndSec() / 60.0).put("y", round(r));
                prev = r;
            }
        }
        return root.toString();
    }

    private static double round(double v) {
        return Math.round(v * 1000.0) / 1000.0;
    }

    /** Нагрузка на один генератор при равном делении. */
    private record PerGenerator(double ratePerSec, long vus, long preAllocated) {
    }

    private static PerGenerator perGenerator(ScenarioPlan plan) {
        var g = plan.generated();
        int n = Math.max(1, plan.generatorCount());
        return new PerGenerator(g.totalPeakRatePerSec() / n, (long) Math.ceil(g.totalPeakVus() / (double) n),
                (long) Math.ceil(g.operations().stream().mapToLong(GeneratedConfig.OpPlan::preAllocatedVus).sum() / (double) n));
    }
}
