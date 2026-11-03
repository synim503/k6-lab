package ru.k6lab.server.simple;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import ru.k6lab.core.model.RateUnit;
import ru.k6lab.core.profile.ProfileType;
import ru.k6lab.server.config.AppProperties;
import ru.k6lab.server.deploy.DeployService;
import ru.k6lab.server.generator.Generator;
import ru.k6lab.server.generator.GeneratorRepository;
import ru.k6lab.server.loadmodel.LoadModel;
import ru.k6lab.server.loadmodel.LoadModelService;
import ru.k6lab.server.loadmodel.ModelForm;
import ru.k6lab.server.profile.ProfileService;
import ru.k6lab.server.run.Run;
import ru.k6lab.server.run.RunService;
import ru.k6lab.server.scenario.Scenario;
import ru.k6lab.server.scenario.ScenarioForm;
import ru.k6lab.server.scenario.ScenarioService;
import ru.k6lab.server.script.ScriptService;
import ru.k6lab.server.script.ScriptVersion;
import ru.k6lab.server.ssh.SshCredential;
import ru.k6lab.server.ssh.SshCredentialService;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Упрощённый путь для повседневной работы: «добавить сервер», «создать тест», «запустить тест». За простыми формами
 * создаются те же сущности (SSH-учётка, модель, профиль, сценарий), что и в расширенном интерфейсе, который остаётся
 * доступным в меню «Ещё».
 */
@Controller
@RequestMapping("/quick")
public class SimpleController {

    private final SshCredentialService credentials;
    private final DeployService deploy;
    private final AppProperties props;
    private final ScriptService scripts;
    private final LoadModelService models;
    private final ProfileService profiles;
    private final ScenarioService scenarios;
    private final RunService runs;
    private final GeneratorRepository generators;

    public SimpleController(SshCredentialService credentials, DeployService deploy, AppProperties props, ScriptService scripts,
                            LoadModelService models, ProfileService profiles, ScenarioService scenarios, RunService runs,
                            GeneratorRepository generators) {
        this.credentials = credentials;
        this.deploy = deploy;
        this.props = props;
        this.scripts = scripts;
        this.models = models;
        this.profiles = profiles;
        this.scenarios = scenarios;
        this.runs = runs;
        this.generators = generators;
    }

    // ------------------------------------------------------------------ сервер-генератор

    @GetMapping("/generator")
    @PreAuthorize("hasRole('ADMIN')")
    public String generatorForm(Model model, HttpServletRequest request) {
        model.addAttribute("serverUrl", "ws://" + request.getServerName() + ":" + props.getAgentPort() + "/agent/ws");
        return "simple/generator";
    }

    @PostMapping("/generator")
    @PreAuthorize("hasRole('ADMIN')")
    public String generatorCreate(@RequestParam String host, @RequestParam(defaultValue = "22") int port, @RequestParam String username,
                                  @RequestParam(defaultValue = "PASSWORD") String authType,
                                  @RequestParam(required = false) String password, @RequestParam(required = false) String privateKey,
                                  @RequestParam(required = false) String passphrase, @RequestParam(required = false) String sudoPassword,
                                  @RequestParam String serverUrl, Model model, RedirectAttributes ra, HttpServletRequest request) {
        try {
            String h = host.trim();
            String user = username.trim();
            boolean key = "KEY".equalsIgnoreCase(authType);
            String secret = key ? privateKey : password;
            if (secret == null || secret.isBlank()) {
                throw new IllegalArgumentException(key ? "Вставьте приватный ключ SSH" : "Введите пароль");
            }
            // пароль SSH используется и для sudo, если вход не под root; при входе по ключу sudo-пароль задаётся отдельно
            String sudo = "root".equals(user) ? null : key ? (sudoPassword == null || sudoPassword.isBlank() ? null : sudoPassword) : password;
            var cred = credentials.save(new SshCredentialService.Form(null, user + "@" + h + "-" + UUID.randomUUID().toString().substring(0, 4),
                    user, key ? SshCredential.AuthType.KEY : SshCredential.AuthType.PASSWORD, secret,
                    key && passphrase != null && !passphrase.isBlank() ? passphrase : null, sudo, null, null, null, false, false));
            String name = h.replaceAll("[^A-Za-z0-9_.-]", "_");
            // без sudo (вход под обычным пользователем без пароля sudo) ставим в пользовательском режиме
            Generator.InstallMode mode = "root".equals(user) || sudo != null ? Generator.InstallMode.SYSTEM : Generator.InstallMode.USER;
            var d = deploy.startInstall(new DeployService.InstallRequest(name, h, port, cred.getId(), null, List.of(), mode,
                    mode == Generator.InstallMode.USER ? "~/k6-agent" : null, serverUrl.trim(), false, 1, null, null));
            // при первом подключении потребуется подтвердить отпечаток ключа - это на странице операции
            return "redirect:/deployments/" + d.getId();
        } catch (IllegalArgumentException | IllegalStateException e) {
            model.addAttribute("error", e.getMessage());
            model.addAttribute("serverUrl", serverUrl);
            model.addAttribute("host", host);
            model.addAttribute("port", port);
            model.addAttribute("username", username);
            model.addAttribute("authType", authType);
            return "simple/generator";
        }
    }

    // ------------------------------------------------------------------ тесты

    @GetMapping("/tests")
    public String tests(Model model) {
        model.addAttribute("tests", scenarios.list());
        return "simple/tests";
    }

    @GetMapping("/test/new")
    public String testForm(@RequestParam(required = false) Long scriptId, Model model) {
        model.addAttribute("scripts", scripts.list());
        model.addAttribute("scriptId", scriptId);
        addOps(model, scriptId);
        return "simple/test";
    }

    /** htmx: список операций выбранного скрипта. */
    @GetMapping("/test/ops")
    public String ops(@RequestParam(required = false) Long scriptId, Model model) {
        addOps(model, scriptId);
        return "simple/test :: ops";
    }

    private void addOps(Model model, Long scriptId) {
        List<String> ops = List.of();
        String warn = null;
        if (scriptId != null) {
            var v = scripts.latest(scriptId);
            if (v.isPresent()) {
                ops = v.get().operationNames();
                if (ops.isEmpty()) {
                    warn = "В скрипте не найдено ни одной экспортируемой функции-операции";
                }
            }
        }
        model.addAttribute("ops", ops);
        model.addAttribute("opsWarning", warn);
    }

    @PostMapping("/test")
    public String testCreate(@RequestParam String name, @RequestParam Long scriptId, @RequestParam(defaultValue = "5") int minutes,
                             @RequestParam(defaultValue = "100") double percent,
                             @RequestParam(name = "op", required = false) List<String> opNames,
                             @RequestParam(name = "rate", required = false) List<Double> rates,
                             @RequestParam(name = "sla", required = false) List<Double> slas,
                             Model model, RedirectAttributes ra) {
        try {
            if (opNames == null || opNames.isEmpty()) {
                throw new IllegalArgumentException("Выберите скрипт и задайте нагрузку хотя бы для одной операции");
            }
            scripts.latest(scriptId).orElseThrow(() -> new IllegalArgumentException("У скрипта нет версий"));
            String suffix = UUID.randomUUID().toString().substring(0, 4);
            ModelForm mf = new ModelForm();
            mf.setName(name.trim() + " - модель " + suffix);
            mf.setScriptId(scriptId);
            for (int i = 0; i < opNames.size(); i++) {
                Double rate = i < rates.size() ? rates.get(i) : null;
                if (rate == null || rate <= 0) {
                    continue; // операция не участвует в тесте
                }
                ModelForm.OpForm o = new ModelForm.OpForm();
                o.setName(opNames.get(i));
                o.setExecFn(opNames.get(i));
                o.setIntensityValue(rate);
                o.setIntensityUnit(RateUnit.PER_SEC);
                o.setExpectedDurationSec(0.3);
                o.setSlaPercentile(95);
                o.setSlaThresholdMs(i < slas.size() && slas.get(i) != null ? slas.get(i) : 1000.0);
                o.setMaxErrorPct(1.0);
                mf.getOps().add(o);
            }
            if (mf.getOps().isEmpty()) {
                throw new IllegalArgumentException("Укажите интенсивность (оп/с) хотя бы для одной операции");
            }
            LoadModel m = models.save(mf);
            var p = profiles.save(new ProfileService.ProfileForm(null, name.trim() + " - профиль " + suffix, ProfileType.CONFIRMATION, null,
                    Map.of("levelPct", String.valueOf(percent), "rampSec", "30", "holdSec", String.valueOf(Math.max(1, minutes) * 60)),
                    30, 30, false));
            ScenarioForm sf = new ScenarioForm();
            sf.setName(name.trim());
            sf.setScriptId(scriptId);
            sf.setModelId(m.getId());
            sf.setProfileId(p.getId());
            Scenario s = scenarios.save(sf);
            ra.addFlashAttribute("info", "Тест «" + s.getName() + "» создан. Выберите серверы и запустите.");
            return "redirect:/quick/test/" + s.getId() + "/run";
        } catch (IllegalArgumentException | IllegalStateException e) {
            model.addAttribute("error", e.getMessage());
            model.addAttribute("scripts", scripts.list());
            model.addAttribute("scriptId", scriptId);
            addOps(model, scriptId);
            return "simple/test";
        }
    }

    /** Удаление теста вместе с его моделью и профилем, если они не нужны другим сценариям. История запусков сохраняется. */
    @PostMapping("/test/{id}/delete")
    public String testDelete(@PathVariable Long id, RedirectAttributes ra) {
        try {
            Scenario s = scenarios.get(id);
            boolean running = runs.activeRuns().stream()
                    .anyMatch(r -> id.equals(r.getScenarioId()));
            if (running) {
                throw new IllegalStateException("Тест сейчас выполняется - дождитесь окончания или остановите запуск");
            }
            String name = s.getName();
            scenarios.delete(id);
            try {
                models.delete(s.getModelId());
            } catch (RuntimeException ignored) {
                // модель нужна другому сценарию - остаётся
            }
            try {
                profiles.delete(s.getProfileId());
            } catch (RuntimeException ignored) {
                // профиль нужен другому сценарию - остаётся
            }
            ra.addFlashAttribute("info", "Тест «" + name + "» удалён. История его запусков сохранена.");
        } catch (RuntimeException e) {
            ra.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/quick/tests";
    }

    // ------------------------------------------------------------------ запуск

    @GetMapping("/test/{id}/run")
    public String runForm(@PathVariable Long id, Model model) {
        fillRunForm(id, model);
        return "simple/run";
    }

    private void fillRunForm(Long id, Model model) {
        model.addAttribute("test", scenarios.get(id));
        List<Generator> all = generators.findAll();
        model.addAttribute("generators", all);
        model.addAttribute("freeCount", all.stream().filter(Generator::isAvailableForRuns).count());
    }

    /**
     * Число генераторов задаётся числом; конкретные хосты - по желанию. Если хосты отмечены, берутся они, иначе
     * нужное число свободных генераторов выбирается автоматически.
     */
    @PostMapping("/test/{id}/run")
    public String run(@PathVariable Long id, @RequestParam(name = "generatorId", required = false) List<Long> generatorIds,
                      @RequestParam(required = false) Integer count, Model model, RedirectAttributes ra) {
        try {
            List<Long> chosen;
            if (generatorIds != null && !generatorIds.isEmpty()) {
                chosen = generatorIds.stream().sorted().toList();
            } else {
                int n = count == null ? 1 : count;
                if (n < 1) {
                    throw new IllegalArgumentException("Число генераторов должно быть не меньше 1");
                }
                List<Long> free = generators.findAll().stream().filter(Generator::isAvailableForRuns)
                        .sorted(java.util.Comparator.comparing(Generator::getName)).map(Generator::getId).toList();
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
                run = runs.create(new RunService.CreateRequest(id, sel, null, null, Run.FailurePolicy.CONTINUE, 120));
            } catch (java.io.IOException e) {
                throw new IllegalStateException(e.getMessage(), e);
            }
            return "redirect:/runs/" + run.getId();
        } catch (IllegalArgumentException | IllegalStateException e) {
            model.addAttribute("error", e.getMessage());
            fillRunForm(id, model);
            return "simple/run";
        }
    }
}
