package ru.k6lab.server.loadmodel;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import ru.k6lab.core.model.OperationSpec;
import ru.k6lab.core.model.RateUnit;
import ru.k6lab.server.script.ScriptService;
import ru.k6lab.server.script.ScriptVersion;

import java.util.List;

@Controller
@RequestMapping("/models")
public class LoadModelController {

    private final LoadModelService service;
    private final ScriptService scripts;

    public LoadModelController(LoadModelService service, ScriptService scripts) {
        this.service = service;
        this.scripts = scripts;
    }

    @GetMapping
    public String list(Model model) {
        model.addAttribute("models", service.list());
        model.addAttribute("scriptNames", scripts.list().stream().collect(java.util.stream.Collectors.toMap(
                s -> s.getId(), s -> s.getName())));
        return "models/list";
    }

    @GetMapping("/new")
    public String create(@RequestParam(required = false) Long scriptId, Model model) {
        ModelForm f = new ModelForm();
        f.setScriptId(scriptId);
        f.getOps().add(defaultOp());
        fill(model, f);
        return "models/form";
    }

    @GetMapping("/{id}/edit")
    public String edit(@PathVariable Long id, Model model) {
        fill(model, service.toForm(id));
        return "models/form";
    }

    /** htmx: пустая строка операции. */
    @GetMapping("/row")
    public String row(@RequestParam int index, @RequestParam(required = false) Long scriptId, Model model) {
        model.addAttribute("i", index);
        model.addAttribute("o", defaultOp());
        model.addAttribute("rateUnits", RateUnit.values());
        model.addAttribute("operationNames", operationNames(scriptId));
        return "models/_row :: row";
    }

    @PostMapping
    public String save(@ModelAttribute("form") ModelForm f, Model model, RedirectAttributes ra) {
        try {
            LoadModel saved = service.save(f);
            ra.addFlashAttribute("info", "Модель сохранена");
            return "redirect:/models/" + saved.getId() + "/edit";
        } catch (IllegalArgumentException | IllegalStateException e) {
            model.addAttribute("error", e.getMessage());
            fill(model, f);
            return "models/form";
        }
    }

    @PostMapping("/{id}/clone")
    public String clone(@PathVariable Long id, @RequestParam(defaultValue = "") String name, RedirectAttributes ra) {
        try {
            LoadModel c = service.clone(id, name);
            ra.addFlashAttribute("info", "Создана копия «" + c.getName() + "»");
            return "redirect:/models/" + c.getId() + "/edit";
        } catch (IllegalArgumentException e) {
            ra.addFlashAttribute("error", e.getMessage());
            return "redirect:/models";
        }
    }

    @PostMapping("/{id}/delete")
    public String delete(@PathVariable Long id, RedirectAttributes ra) {
        try {
            service.delete(id);
        } catch (IllegalStateException e) {
            ra.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/models";
    }

    private ModelForm.OpForm defaultOp() {
        ModelForm.OpForm o = new ModelForm.OpForm();
        o.setMode(OperationSpec.Mode.OPEN);
        o.setIntensityUnit(RateUnit.PER_HOUR);
        o.setSlaPercentile(95);
        o.setSlaThresholdMs(1000.0);
        o.setMaxErrorPct(1.0);
        return o;
    }

    private void fill(Model model, ModelForm f) {
        model.addAttribute("form", f);
        model.addAttribute("scripts", scripts.list());
        model.addAttribute("rateUnits", RateUnit.values());
        model.addAttribute("operationNames", operationNames(f.getScriptId()));
        model.addAttribute("scriptName", f.getScriptId() == null ? null : scripts.get(f.getScriptId()).getName());
    }

    private List<String> operationNames(Long scriptId) {
        if (scriptId == null) {
            return List.of();
        }
        return scripts.latest(scriptId).map(ScriptVersion::operationNames).orElse(List.of());
    }
}
