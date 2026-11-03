package ru.k6lab.server.load;

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
import ru.k6lab.server.script.AdminHelper;
import ru.k6lab.server.script.K6Tool;
import ru.k6lab.server.script.ScriptService;
import ru.k6lab.server.secret.Secret;
import ru.k6lab.server.secret.SecretService;
import ru.k6lab.server.testdata.ScriptDataFile;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;

/** Страница «Нагрузка» скрипта: описание операций с интенсивностью и SLA, расчёт итогового конфига k6. */
@Controller
@RequestMapping("/scripts/{id}")
public class LoadController {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final ScriptService scripts;
    private final LoadService load;
    private final RunPlanner planner;
    private final SecretService secrets;
    private final K6Tool k6;
    private final AdminHelper helper;

    public LoadController(ScriptService scripts, LoadService load, RunPlanner planner, SecretService secrets, K6Tool k6,
                          AdminHelper helper) {
        this.scripts = scripts;
        this.load = load;
        this.planner = planner;
        this.secrets = secrets;
        this.k6 = k6;
        this.helper = helper;
    }

    @GetMapping("/load")
    public String form(@PathVariable Long id, @RequestParam(defaultValue = "1") int generators, Model model) {
        fill(model, id, load.toForm(id), generators);
        return "scripts/load";
    }

    @PostMapping("/load")
    public String save(@PathVariable Long id, @ModelAttribute("form") LoadForm f, Model model, RedirectAttributes ra) {
        try {
            load.save(id, f);
            ra.addFlashAttribute("info", "Нагрузка сохранена");
            return "redirect:/scripts/" + id;
        } catch (IllegalArgumentException | IllegalStateException e) {
            model.addAttribute("error", e.getMessage());
            fill(model, id, f, 1);
            return "scripts/load";
        }
    }

    private void fill(Model model, Long id, LoadForm f, int generators) {
        RunPlan plan = planner.plan(id, Math.max(1, generators));
        model.addAttribute("script", scripts.get(id));
        model.addAttribute("form", f);
        model.addAttribute("plan", plan);
        model.addAttribute("generators", plan.generatorCount());
        model.addAttribute("secretNames", secrets.list().stream().map(Secret::getName).toList());
        model.addAttribute("modes", ScriptDataFile.Mode.values());
        model.addAttribute("operationNames", scripts.latest(id).map(v -> v.operationNames()).orElse(List.of()));
        model.addAttribute("chart", plan.generated() == null ? "null" : chartJson(plan.generated()));
        if (plan.generated() != null) {
            model.addAttribute("opNames", plan.generated().operations().stream().map(GeneratedConfig.OpPlan::name).toList());
            model.addAttribute("perGen", perGenerator(plan));
        }
    }

    @GetMapping(value = "/config.json", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> configJson(@PathVariable Long id) {
        RunPlan plan = planner.plan(id, 1);
        if (plan.configJson() == null) {
            return ResponseEntity.unprocessableEntity().body("{\"error\":\"" + String.join("; ", plan.errors()).replace("\"", "'") + "\"}");
        }
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename("k6-config-" + id + ".json").build().toString())
                .body(plan.configJson());
    }

    /** «Требования к ресурсам»: k6 inspect --execution-requirements с итоговым конфигом (htmx). */
    @PostMapping("/requirements")
    public String requirements(@PathVariable Long id, Model model) {
        RunPlan plan = planner.plan(id, 1);
        if (plan.configJson() == null || plan.version() == null) {
            model.addAttribute("error", String.join("; ", plan.errors()));
            return "scripts/_requirements :: requirements";
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
        return "scripts/_requirements :: requirements";
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

    private static PerGenerator perGenerator(RunPlan plan) {
        var g = plan.generated();
        int n = Math.max(1, plan.generatorCount());
        return new PerGenerator(g.totalPeakRatePerSec() / n, (long) Math.ceil(g.totalPeakVus() / (double) n),
                (long) Math.ceil(g.operations().stream().mapToLong(GeneratedConfig.OpPlan::preAllocatedVus).sum() / (double) n));
    }
}
