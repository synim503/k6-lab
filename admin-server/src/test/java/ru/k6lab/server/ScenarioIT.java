package ru.k6lab.server;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import ru.k6lab.core.model.OperationSpec;
import ru.k6lab.core.model.RateUnit;
import ru.k6lab.core.profile.ProfileType;
import ru.k6lab.core.util.ZipUtil;
import ru.k6lab.server.loadmodel.LoadModel;
import ru.k6lab.server.loadmodel.LoadModelService;
import ru.k6lab.server.loadmodel.ModelForm;
import ru.k6lab.server.profile.ProfileService;
import ru.k6lab.server.profile.TestProfile;
import ru.k6lab.server.scenario.Scenario;
import ru.k6lab.server.scenario.ScenarioForm;
import ru.k6lab.server.scenario.ScenarioPlan;
import ru.k6lab.server.scenario.ScenarioPlanner;
import ru.k6lab.server.scenario.ScenarioService;
import ru.k6lab.server.scenario.SecretService;
import ru.k6lab.server.script.K6Tool;
import ru.k6lab.server.script.ScriptService;
import ru.k6lab.server.script.ScriptVersion;
import ru.k6lab.server.sut.SutForm;
import ru.k6lab.server.sut.SutService;
import ru.k6lab.server.testdata.ScenarioDataFile;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;

/** Модель нагрузки, профили, шаблоны профилей, сценарии, секреты, SUT, режимы данных, превью и итоговый конфиг. */
class ScenarioIT extends AbstractIT {

    @Autowired
    ScriptService scripts;
    @Autowired
    LoadModelService models;
    @Autowired
    ProfileService profiles;
    @Autowired
    ScenarioService scenarios;
    @Autowired
    ScenarioPlanner planner;
    @Autowired
    SecretService secrets;
    @Autowired
    SutService suts;
    @Autowired
    K6Tool k6;
    @Autowired
    ru.k6lab.server.script.AdminHelper helper;
    @Autowired
    MockMvc mvc;

    private static String n() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private static final String SCRIPT = """
            import http from 'k6/http';
            import { op, check } from './__admin/admin.js';
            export function createPayment() { op(() => { http.get('http://localhost/a'); }); }
            export function getPayment() { op(() => { http.get('http://localhost/b'); }); }
            export default function () {}
            """;

    private ScriptVersion script(String content) throws Exception {
        return scripts.createFromUpload("scr-" + n(), "", "s.js", new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8)), "", null);
    }

    private static ModelForm.OpForm op(String name, double value, RateUnit unit, double durSec) {
        ModelForm.OpForm o = new ModelForm.OpForm();
        o.setName(name);
        o.setExecFn(name);
        o.setIntensityValue(value);
        o.setIntensityUnit(unit);
        o.setExpectedDurationSec(durSec);
        o.setSlaThresholdMs(800.0);
        o.setMaxErrorPct(1.0);
        return o;
    }

    private LoadModel model(ScriptVersion v, ModelForm.OpForm... ops) {
        ModelForm f = new ModelForm();
        f.setName("model-" + n());
        f.setScriptId(v.getScriptId());
        f.getOps().addAll(List.of(ops));
        return models.save(f);
    }

    private TestProfile maxSearch() {
        return profiles.save(new ProfileService.ProfileForm(null, "prof-" + n(), ProfileType.MAX_SEARCH, null,
                Map.of("startPct", "50", "stepPct", "25", "steps", "3", "rampSec", "30", "holdSec", "120"), 30, 30, false));
    }

    private ScenarioForm scenarioForm(ScriptVersion v, LoadModel m, TestProfile p) {
        ScenarioForm f = new ScenarioForm();
        f.setName("sc-" + n());
        f.setScriptId(v.getScriptId());
        f.setModelId(m.getId());
        f.setProfileId(p.getId());
        return f;
    }

    // ---------------------------------------------------------------- модель

    @Test
    void absoluteIntensitiesAreStoredPerSecondWithSharesDerived() throws Exception {
        ScriptVersion v = script(SCRIPT);
        // 3600 оп/час = 1 оп/с, 7200 оп/час = 2 оп/с -> доли 33.3% и 66.7%
        LoadModel m = model(v, op("createPayment", 3600, RateUnit.PER_HOUR, 0.4), op("getPayment", 120, RateUnit.PER_MIN, 0.2));
        assertThat(m.getTotalPerSec()).isEqualTo(3.0);
        assertThat(m.getOperations()).extracting(o -> Math.round(o.getSharePct() * 10) / 10.0).containsExactly(33.3, 66.7);
        assertThat(m.getOperations().get(1).ratePerSec()).isEqualTo(2.0);
        assertThat(models.toForm(m.getId()).getOps()).hasSize(2);
    }

    @Test
    void shareModeComputesRatesFromTotalAndValidatesSum() throws Exception {
        ScriptVersion v = script(SCRIPT);
        ModelForm f = new ModelForm();
        f.setName("m-" + n());
        f.setScriptId(v.getScriptId());
        f.setInputMode(ModelForm.InputMode.SHARE);
        f.setTotalValue(36000.0);
        f.setTotalUnit(RateUnit.PER_HOUR);
        ModelForm.OpForm a = op("createPayment", 0, RateUnit.PER_HOUR, 0.4);
        a.setIntensityValue(null);
        a.setSharePct(70.0);
        ModelForm.OpForm b = op("getPayment", 0, RateUnit.PER_HOUR, 0.4);
        b.setIntensityValue(null);
        b.setSharePct(30.0);
        f.getOps().addAll(List.of(a, b));
        LoadModel m = models.save(f);
        assertThat(m.getTotalPerSec()).isEqualTo(10.0);
        assertThat(m.getOperations().get(0).ratePerSec()).isCloseTo(7.0, org.assertj.core.data.Offset.offset(1e-9));
        assertThat(m.getOperations().get(0).getIntensityValue()).isCloseTo(25200.0, org.assertj.core.data.Offset.offset(1e-6));

        b.setSharePct(20.0);
        f.setName("m-" + n());
        assertThatThrownBy(() -> models.save(f)).hasMessageContaining("100%").hasMessageContaining("90.00");
    }

    @Test
    void closedModelInputsAreSynchronised() throws Exception {
        ScriptVersion v = script(SCRIPT);
        ModelForm.OpForm rate = op("createPayment", 5, RateUnit.PER_SEC, 0.5);
        rate.setMode(OperationSpec.Mode.CLOSED);
        rate.setPacingMs(2000);
        ModelForm.OpForm vu = op("getPayment", 0, RateUnit.PER_SEC, 0.5);
        vu.setIntensityValue(null);
        vu.setMode(OperationSpec.Mode.CLOSED);
        vu.setPacingMs(500);
        vu.setClosedInput(ModelForm.ClosedInput.VU_PACING);
        vu.setVusOverride(10);
        LoadModel m = model(v, rate, vu);
        // 10 VU и pacing 0.5 с -> 20 оп/с
        assertThat(m.getOperations().get(1).ratePerSec()).isEqualTo(20.0);
        assertThat(m.getOperations().get(1).getVusOverride()).isEqualTo(10);
        assertThat(m.getOperations().get(0).getVusOverride()).isNull();
    }

    @Test
    void modelValidation() throws Exception {
        ScriptVersion v = script(SCRIPT);
        ModelForm f = new ModelForm();
        f.setName("bad-" + n());
        f.setScriptId(v.getScriptId());
        assertThatThrownBy(() -> models.save(f)).hasMessageContaining("хотя бы одну");
        f.getOps().add(op("bad name!", 1, RateUnit.PER_SEC, 1));
        assertThatThrownBy(() -> models.save(f)).hasMessageContaining("имя");
        f.getOps().clear();
        ModelForm.OpForm noDuration = op("a", 1, RateUnit.PER_SEC, 0);
        f.getOps().add(noDuration);
        assertThatThrownBy(() -> models.save(f)).hasMessageContaining("длительность");
        f.getOps().clear();
        ModelForm.OpForm closedNoPacing = op("a", 1, RateUnit.PER_SEC, 1);
        closedNoPacing.setMode(OperationSpec.Mode.CLOSED);
        f.getOps().add(closedNoPacing);
        assertThatThrownBy(() -> models.save(f)).hasMessageContaining("pacing");
        f.getOps().clear();
        f.getOps().addAll(List.of(op("dup", 1, RateUnit.PER_SEC, 1), op("dup", 1, RateUnit.PER_SEC, 1)));
        assertThatThrownBy(() -> models.save(f)).hasMessageContaining("повторяется");
    }

    @Test
    void modelCloneKeepsOperations() throws Exception {
        ScriptVersion v = script(SCRIPT);
        LoadModel m = model(v, op("createPayment", 100, RateUnit.PER_HOUR, 0.3));
        LoadModel c = models.clone(m.getId(), "clone-" + n());
        assertThat(c.getId()).isNotEqualTo(m.getId());
        assertThat(c.getOperations()).hasSize(1);
        assertThat(c.getTotalPerSec()).isEqualTo(m.getTotalPerSec());
    }

    // ---------------------------------------------------------------- профили

    @Test
    void profilesExpandAndTemplatesWork() {
        TestProfile p = maxSearch();
        assertThat(profiles.steps(p)).extracting(s -> s.levelPct()).containsExactly(50.0, 75.0, 100.0);
        assertThatThrownBy(() -> profiles.save(new ProfileService.ProfileForm(null, "x-" + n(), ProfileType.MAX_SEARCH, null,
                Map.of("startPct", "50"), 30, 30, false))).hasMessageContaining("Шаг");

        TestProfile custom = profiles.save(new ProfileService.ProfileForm(null, "c-" + n(), ProfileType.CUSTOM, null,
                Map.of("steps", "10;5;60;вход\n20;5;60"), 30, 30, false));
        assertThat(profiles.steps(custom)).hasSize(2);
        assertThat(ProfileService.paramsToForm(custom).get("steps")).contains("10;5;60;вход");

        String def = """
                {"params":[{"name":"n","default":3},{"name":"hold","default":60}],
                 "blocks":[{"repeat":{"count":"n","var":"i"},"steps":[{"level":"10*(i+1)","ramp":"10","hold":"hold"}]}]}
                """;
        var t = profiles.saveTemplate(null, "tpl-" + n(), "d", def);
        TestProfile fromTemplate = profiles.save(new ProfileService.ProfileForm(null, "ft-" + n(), ProfileType.TEMPLATE, t.getId(),
                Map.of("n", "4"), 30, 30, false));
        assertThat(profiles.steps(fromTemplate)).extracting(s -> s.levelPct()).containsExactly(10.0, 20.0, 30.0, 40.0);
        assertThatThrownBy(() -> profiles.saveTemplate(null, "bad-" + n(), "", "{\"blocks\":[{\"steps\":[{\"level\":\"Runtime\",\"hold\":\"5\"}]}]}"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> profiles.saveTemplate(null, "bad2-" + n(), "", "not json")).hasMessageContaining("JSON");
        assertThatThrownBy(() -> profiles.deleteTemplate(t.getId())).hasMessageContaining("используется");
    }

    // ---------------------------------------------------------------- сценарий и итоговый конфиг

    @Test
    void scenarioProducesDeterministicConfigAcceptedByK6() throws Exception {
        ScriptVersion v = script(SCRIPT);
        LoadModel m = model(v, op("createPayment", 360000, RateUnit.PER_HOUR, 0.4), op("getPayment", 36000, RateUnit.PER_HOUR, 0.2));
        TestProfile p = maxSearch();
        Scenario s = scenarios.save(scenarioForm(v, m, p));

        ScenarioPlan plan = planner.plan(s, 2, null);
        assertThat(plan.errors()).isEmpty();
        assertThat(plan.ok()).isTrue();
        // createPayment: 100 оп/с * 50/75/100% ; preAlloc = ceil(100 * 0.4 * 1.5) = 60
        var sc = plan.config().path("scenarios").path("createPayment");
        assertThat(sc.path("executor").asText()).isEqualTo("ramping-arrival-rate");
        assertThat(sc.path("preAllocatedVUs").asInt()).isEqualTo(60);
        assertThat(sc.path("stages").get(1).path("target").asInt()).isEqualTo(50);
        assertThat(sc.path("stages").get(5).path("target").asInt()).isEqualTo(100);
        assertThat(plan.config().path("thresholds").path("op_duration{scenario:createPayment}").get(0).asText()).isEqualTo("p(95)<800");
        // повторная генерация даёт тот же JSON
        assertThat(planner.plan(s, 2, null).configJson()).isEqualTo(plan.configJson());
        assertThat(plan.totalSec()).isEqualTo(3 * 150);

        // k6 принимает сгенерированный конфиг: execution requirements совпадают с расчётом
        Assumptions.assumeTrue(k6.version().isPresent(), "k6 недоступен");
        Path dir = Files.createTempDirectory("req-it");
        ZipUtil.extract(new ByteArrayInputStream(scripts.zipBytes(plan.version())), dir);
        Files.createDirectories(dir.resolve("__admin"));
        Files.write(dir.resolve("__admin/admin.js"), helper.content());
        K6Tool.Result r = k6.requirements(dir, plan.version().getEntrypoint(), plan.configJson());
        assertThat(r.ok()).as(r.stderr()).isTrue();
        // totalDuration k6 включает gracefulStop: 3 ступени по 150 с + 30 с
        assertThat(r.stdout()).contains("\"totalDuration\": \"8m0s\"");
        // maxVUs = сумма maxVUs операций: createPayment 60*3 + getPayment ceil(10*0.2*1.5)=3 -> 9
        assertThat(r.stdout()).contains("\"maxVUs\": 189");
    }

    @Test
    void scenarioConfigWithZeroRampIsValidForK6() throws Exception {
        Assumptions.assumeTrue(k6.version().isPresent(), "k6 недоступен");
        ScriptVersion v = script(SCRIPT);
        LoadModel m = model(v, op("createPayment", 3600, RateUnit.PER_HOUR, 0.4));
        TestProfile spike = profiles.save(new ProfileService.ProfileForm(null, "spike-" + n(), ProfileType.SPIKE, null,
                Map.of("basePct", "30", "peakPct", "120", "peakDurationSec", "20", "peaks", "1", "intervalSec", "30",
                        "spikeRampSec", "0", "rampSec", "0"), 30, 30, false));
        ScenarioPlan plan = planner.plan(scenarios.save(scenarioForm(v, m, spike)), 1, null);
        assertThat(plan.errors()).isEmpty();
        Path dir = Files.createTempDirectory("req-it");
        ZipUtil.extract(new ByteArrayInputStream(scripts.zipBytes(plan.version())), dir);
        Files.createDirectories(dir.resolve("__admin"));
        Files.write(dir.resolve("__admin/admin.js"), helper.content());
        K6Tool.Result r = k6.requirements(dir, plan.version().getEntrypoint(), plan.configJson());
        assertThat(r.ok()).as(r.stderr()).isTrue();
    }

    @Test
    void closedModelWithoutHelperIsBlockedAndInvalidVersionCannotBePinned() throws Exception {
        ScriptVersion plain = script("import http from 'k6/http'; export function a() { http.get('http://x'); } export default function(){}");
        ModelForm.OpForm closed = op("a", 1, RateUnit.PER_SEC, 0.1);
        closed.setMode(OperationSpec.Mode.CLOSED);
        closed.setPacingMs(1000);
        LoadModel m = model(plain, closed);
        Scenario s = scenarios.save(scenarioForm(plain, m, maxSearch()));
        ScenarioPlan plan = planner.plan(s, 1, null);
        assertThat(plan.ok()).isFalse();
        assertThat(plan.errors()).anyMatch(e -> e.contains("admin.js"));

        Assumptions.assumeTrue(k6.version().isPresent(), "k6 недоступен");
        ScriptVersion bad = script("export const options = { vus: 5, duration: '1m' }; export function a() {} export default function(){}");
        LoadModel m2 = model(bad, op("a", 1, RateUnit.PER_SEC, 0.1));
        ScenarioForm f = scenarioForm(bad, m2, maxSearch());
        f.setVersionMode(ScenarioForm.VersionMode.PINNED);
        f.setScriptVersionId(bad.getId());
        assertThatThrownBy(() -> scenarios.save(f)).hasMessageContaining("непригодна");
        // непривязанный сценарий сохраняется, но запуск заблокирован ошибкой плана
        ScenarioPlan blocked = planner.plan(scenarios.save(scenarioForm(bad, m2, maxSearch())), 1, null);
        assertThat(blocked.ok()).isFalse();
        assertThat(blocked.errors()).anyMatch(e -> e.contains("непригодна"));
    }

    @Test
    void operationsMustExistInSelectedVersion() throws Exception {
        ScriptVersion v = script(SCRIPT);
        ModelForm.OpForm ghost = op("ghost", 1, RateUnit.PER_SEC, 0.1);
        ghost.setExecFn("noSuchFunction");
        LoadModel m = model(v, op("createPayment", 1, RateUnit.PER_SEC, 0.1), ghost);
        ScenarioPlan plan = planner.plan(scenarios.save(scenarioForm(v, m, maxSearch())), 1, null);
        assertThat(plan.ok()).isFalse();
        assertThat(plan.errors()).anyMatch(e -> e.contains("noSuchFunction") && e.contains("не найдена"));
        // ручное добавление операции в версию снимает ошибку
        scripts.addOperation(v.getScriptId(), v.getId(), "noSuchFunction");
        assertThat(planner.plan(scenarios.get(plan.scenario().getId()), 1, null).errors()).isEmpty();
    }

    @Test
    void secretsAreReferencedValidatedAndNeverStoredPlain() throws Exception {
        String name = "SEC_" + n();
        secrets.save(name, "тест", "very-secret-value");
        ScriptVersion v = script(SCRIPT);
        LoadModel m = model(v, op("createPayment", 1, RateUnit.PER_SEC, 0.1));
        ScenarioForm f = scenarioForm(v, m, maxSearch());
        f.setEnvText("BASE_URL=http://stand\nTOKEN=${secret:" + name + "}");
        Scenario s = scenarios.save(f);
        assertThat(s.getEnv()).containsEntry("TOKEN", "${secret:" + name + "}");
        assertThat(secrets.resolve(SecretService.referencedNames(s.getEnv()))).containsEntry(name, "very-secret-value");

        ScenarioForm missing = scenarioForm(v, m, maxSearch());
        missing.setEnvText("X=${secret:NO_SUCH_" + n() + "}");
        assertThatThrownBy(() -> scenarios.save(missing)).hasMessageContaining("Не найдены секреты");
        // пустое значение при замене не меняет секрет
        secrets.save(name, "новое описание", "");
        assertThat(secrets.resolve(java.util.Set.of(name))).containsEntry(name, "very-secret-value");
        assertThatThrownBy(() -> secrets.save("NEW_" + n(), "", "")).hasMessageContaining("значение");
    }

    @Test
    void dataModesAreStoredAndSplitWarningIsShown() throws Exception {
        Map<String, byte[]> files = new LinkedHashMap<>();
        files.put("main.js", SCRIPT.getBytes(StandardCharsets.UTF_8));
        StringBuilder csv = new StringBuilder("login,pwd\n");
        for (int i = 0; i < 10; i++) {
            csv.append("u").append(i).append(",p\n");
        }
        files.put("data/users.csv", csv.toString().getBytes(StandardCharsets.UTF_8));
        ScriptVersion v = scripts.createFromUpload("data-" + n(), "", "bundle.zip", new ByteArrayInputStream(ZipUtil.write(files)), "main.js", null);
        // 10 оп/с на 3 ступенях по 150 с -> тысячи итераций, а в файле 10 строк
        LoadModel m = model(v, op("createPayment", 10, RateUnit.PER_SEC, 0.2));
        ScenarioForm f = scenarioForm(v, m, maxSearch());
        ScenarioForm.DataForm d = new ScenarioForm.DataForm();
        d.setPath("data/users.csv");
        d.setMode(ScenarioDataFile.Mode.SPLIT);
        d.setHasHeader(true);
        f.getDataFiles().add(d);
        ScenarioForm.DataForm ghost = new ScenarioForm.DataForm();
        ghost.setPath("data/not-in-archive.csv");
        f.getDataFiles().add(ghost);
        Scenario s = scenarios.save(f);

        assertThat(scenarios.dataFiles(s.getId())).singleElement().satisfies(x -> {
            assertThat(x.getPath()).isEqualTo("data/users.csv");
            assertThat(x.getMode()).isEqualTo(ScenarioDataFile.Mode.SPLIT);
            assertThat(x.isHasHeader()).isTrue();
        });
        ScenarioPlan plan = planner.plan(s, 4, null);
        assertThat(plan.warnings()).anyMatch(w -> w.contains("Данные data/users.csv") && w.contains("закончатся"));
        // в режиме SHARED предупреждения нет
        f.setId(s.getId());
        f.setName(s.getName());
        d.setMode(ScenarioDataFile.Mode.SHARED);
        scenarios.save(f);
        assertThat(planner.plan(scenarios.get(s.getId()), 4, null).warnings()).noneMatch(w -> w.contains("закончатся"));
        assertThat(scenarios.toForm(s.getId()).getDataFiles()).singleElement().satisfies(x -> assertThat(x.getMode()).isEqualTo(ScenarioDataFile.Mode.SHARED));
    }

    @Test
    void expertModeUsesManualConfigAndRejectsAbortOnFail() throws Exception {
        ScriptVersion v = script(SCRIPT);
        LoadModel m = model(v, op("createPayment", 1, RateUnit.PER_SEC, 0.1));
        ScenarioForm f = scenarioForm(v, m, maxSearch());
        f.setExpertMode(true);
        f.setExpertConfigText("{\"scenarios\":{\"createPayment\":{\"executor\":\"constant-arrival-rate\",\"rate\":1,\"duration\":\"10s\",\"preAllocatedVUs\":1,\"exec\":\"createPayment\"}}}");
        ScenarioPlan plan = planner.plan(scenarios.save(f), 1, null);
        assertThat(plan.ok()).isTrue();
        assertThat(plan.generated()).isNull();
        assertThat(plan.warnings()).anyMatch(w -> w.contains("Экспертный режим"));

        ScenarioForm bad = scenarioForm(v, m, maxSearch());
        bad.setExpertMode(true);
        bad.setExpertConfigText("{\"scenarios\":{}, \"thresholds\":{\"x\":[{\"threshold\":\"rate<0.1\",\"abortOnFail\":true}]}}");
        ScenarioPlan abort = planner.plan(scenarios.save(bad), 1, null);
        assertThat(abort.errors()).anyMatch(e -> e.contains("abortOnFail"));
        ScenarioForm broken = scenarioForm(v, m, maxSearch());
        broken.setExpertMode(true);
        broken.setExpertConfigText("{ nope");
        assertThatThrownBy(() -> scenarios.save(broken)).hasMessageContaining("JSON");
    }

    @Test
    void stopCriteriaDefaultsDependOnProfileType() throws Exception {
        ScriptVersion v = script(SCRIPT);
        LoadModel m = model(v, op("createPayment", 1, RateUnit.PER_SEC, 0.1));
        TestProfile search = maxSearch();
        Scenario s = scenarios.save(scenarioForm(v, m, search));
        var cfg = ru.k6lab.server.scenario.ScenarioCriteria.stop(s.getStopCriteria(), search.getType());
        assertThat(cfg.anyEnabled()).isTrue();
        assertThat(cfg.consecutive()).isEqualTo(3);
        TestProfile reliability = profiles.save(new ProfileService.ProfileForm(null, "rel-" + n(), ProfileType.RELIABILITY, null,
                Map.of("levelPct", "70", "rampSec", "60", "durationSec", "3600"), 30, 30, false));
        Scenario rs = scenarios.save(scenarioForm(v, m, reliability));
        assertThat(ru.k6lab.server.scenario.ScenarioCriteria.stop(rs.getStopCriteria(), ProfileType.RELIABILITY).anyEnabled()).isFalse();
        // ручные критерии сохраняются
        ScenarioForm manual = scenarioForm(v, m, reliability);
        manual.setStopMode(ScenarioForm.StopMode.MANUAL);
        manual.setStopErrors(true);
        manual.setStopConsecutive(5);
        Scenario ms = scenarios.save(manual);
        var mc = ru.k6lab.server.scenario.ScenarioCriteria.stop(ms.getStopCriteria(), ProfileType.RELIABILITY);
        assertThat(mc.errorsEnabled()).isTrue();
        assertThat(mc.percentileEnabled()).isFalse();
        assertThat(mc.consecutive()).isEqualTo(5);
    }

    @Test
    void sutWithMetricsIsSaved() {
        SutForm f = new SutForm();
        f.setName("sut-" + n());
        SutForm.MetricForm cpu = new SutForm.MetricForm();
        cpu.setName("CPU");
        cpu.setPromql("avg(rate(process_cpu_seconds_total[{{range}}])) * 100");
        cpu.setUnit("%");
        cpu.setWarnThreshold(80.0);
        f.getMetrics().add(cpu);
        f.getMetrics().add(new SutForm.MetricForm());
        var saved = suts.save(f);
        assertThat(saved.getMetrics()).singleElement().satisfies(m -> {
            assertThat(m.exceeds(81)).isTrue();
            assertThat(m.exceeds(79)).isFalse();
        });
        assertThat(suts.toForm(saved.getId()).getMetrics()).hasSize(1);
        SutForm dup = new SutForm();
        dup.setName("d-" + n());
        dup.getMetrics().addAll(List.of(cpu, cpu));
        assertThatThrownBy(() -> suts.save(dup)).hasMessageContaining("повторяется");
    }

    // ---------------------------------------------------------------- страницы

    @Test
    @WithMockUser(roles = "ADMIN")
    void pagesRenderWithData() throws Exception {
        ScriptVersion v = script(SCRIPT);
        LoadModel m = model(v, op("createPayment", 3600, RateUnit.PER_HOUR, 0.4), op("getPayment", 1800, RateUnit.PER_HOUR, 0.2));
        TestProfile p = maxSearch();
        Scenario s = scenarios.save(scenarioForm(v, m, p));
        var tpl = profiles.saveTemplate(null, "tp-" + n(), "", "{\"params\":[],\"blocks\":[{\"steps\":[{\"level\":\"10\",\"hold\":\"5\"}]}]}");
        SutForm sf = new SutForm();
        sf.setName("s-" + n());
        suts.save(sf);
        secrets.save("PAGE_" + n(), "", "v");

        for (String path : new String[]{"/models", "/models/new", "/models/new?scriptId=" + v.getScriptId(), "/models/" + m.getId() + "/edit",
                "/models/row?index=3&scriptId=" + v.getScriptId(), "/profiles", "/profiles/new", "/profiles/" + p.getId() + "/edit",
                "/profiles/fields?type=SPIKE", "/profiles/fields?type=CUSTOM", "/profiles/fields?type=TEMPLATE&templateId=" + tpl.getId(),
                "/profiles/" + p.getId() + "/steps", "/profile-templates", "/profile-templates/new", "/profile-templates/" + tpl.getId() + "/edit",
                "/scenarios", "/scenarios/new", "/scenarios/new?scriptId=" + v.getScriptId(), "/scenarios/" + s.getId() + "/edit",
                "/scenarios/" + s.getId(), "/scenarios/" + s.getId() + "?generators=3", "/scenarios/" + s.getId() + "/config.json",
                "/scenarios/options/models?scriptId=" + v.getScriptId(), "/scenarios/options/versions?scriptId=" + v.getScriptId(),
                "/scenarios/options/data?scriptId=" + v.getScriptId(), "/sut", "/sut/new", "/sut/metric-row?index=1", "/secrets"}) {
            mvc.perform(get(path)).andExpect(status().isOk());
        }
        mvc.perform(get("/scenarios/" + s.getId() + "?generators=3"))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("createPayment")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("data-chart")));
        mvc.perform(post("/scenarios/" + s.getId() + "/requirements").with(csrf())).andExpect(status().isOk());
        mvc.perform(post("/profile-templates/preview").with(csrf()).param("definition", "{\"blocks\":[{\"steps\":[{\"level\":\"5\",\"hold\":\"5\"}]}]}"))
                .andExpect(status().isOk());
    }
}
