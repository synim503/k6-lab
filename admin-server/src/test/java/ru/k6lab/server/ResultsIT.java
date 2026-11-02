package ru.k6lab.server;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import ru.k6lab.core.model.RateUnit;
import ru.k6lab.core.profile.ProfileType;
import ru.k6lab.core.runstate.RunStatus;
import ru.k6lab.server.generator.Generator;
import ru.k6lab.server.generator.GeneratorRepository;
import ru.k6lab.server.loadmodel.LoadModel;
import ru.k6lab.server.loadmodel.LoadModelService;
import ru.k6lab.server.loadmodel.ModelForm;
import ru.k6lab.server.profile.ProfileService;
import ru.k6lab.server.result.ResultData;
import ru.k6lab.server.result.RunCompareService;
import ru.k6lab.server.result.StepResultService;
import ru.k6lab.server.run.Run;
import ru.k6lab.server.run.RunService;
import ru.k6lab.server.scenario.Scenario;
import ru.k6lab.server.scenario.ScenarioForm;
import ru.k6lab.server.scenario.ScenarioService;
import ru.k6lab.server.script.ScriptService;
import ru.k6lab.server.script.ScriptVersion;
import ru.k6lab.server.settings.SettingsService;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrlPattern;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Итоги run, максимум, сравнение, отчёты. Prometheus подменён заглушкой, которая отдаёт значения в зависимости от
 * ступени (по времени запроса); run создаются обычным путём, а ступени/время завершения задаются напрямую в БД.
 */
@WithMockUser(roles = "ADMIN")
class ResultsIT extends AbstractIT {

    static final int STEP_SEC = 150;
    static FakePrometheus prom;

    @Autowired
    ScriptService scripts;
    @Autowired
    LoadModelService models;
    @Autowired
    ProfileService profiles;
    @Autowired
    ScenarioService scenarios;
    @Autowired
    RunService runService;
    @Autowired
    StepResultService results;
    @Autowired
    RunCompareService compare;
    @Autowired
    GeneratorRepository generators;
    @Autowired
    SettingsService settings;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    MockMvc mvc;

    @BeforeAll
    static void startProm() throws Exception {
        prom = new FakePrometheus();
    }

    @AfterAll
    static void stopProm() {
        prom.close();
    }

    private static final String SCRIPT = """
            import http from 'k6/http';
            export function work() { http.get('http://localhost/a'); }
            export default function () {}
            """;

    private Scenario scenario() throws Exception {
        String n = UUID.randomUUID().toString().substring(0, 8);
        ScriptVersion v = scripts.createFromUpload("scr-" + n, "", "s.js", new ByteArrayInputStream(SCRIPT.getBytes(StandardCharsets.UTF_8)), "", null);
        ModelForm mf = new ModelForm();
        mf.setName("m-" + n);
        mf.setScriptId(v.getScriptId());
        ModelForm.OpForm o = new ModelForm.OpForm();
        o.setName("work");
        o.setExecFn("work");
        o.setIntensityValue(10.0);
        o.setIntensityUnit(RateUnit.PER_SEC);
        o.setExpectedDurationSec(0.1);
        o.setSlaThresholdMs(500.0);
        o.setSlaPercentile(95);
        o.setMaxErrorPct(1.0);
        mf.getOps().add(o);
        LoadModel m = models.save(mf);
        var p = profiles.save(new ProfileService.ProfileForm(null, "p-" + n, ProfileType.MAX_SEARCH, null,
                Map.of("startPct", "100", "stepPct", "10", "steps", "3", "rampSec", "30", "holdSec", "120"), 30, 30, false));
        ScenarioForm sf = new ScenarioForm();
        sf.setName("sc-" + n);
        sf.setScriptId(v.getScriptId());
        sf.setModelId(m.getId());
        sf.setProfileId(p.getId());
        return scenarios.save(sf);
    }

    /** Завершённый run с тремя ступенями (100/110/120%); t0 - начало теста. */
    private Run finishedRun(Scenario s, Instant t0) throws Exception {
        Generator g = new Generator();
        g.setName("res-" + UUID.randomUUID().toString().substring(0, 6));
        g.setHost("127.0.0.1");
        g.setInstallDir("/opt/k6-agent");
        g.setServerUrl("ws://127.0.0.1:1/agent/ws");
        g.setStatus(Generator.Status.INSTALLING);
        g = generators.save(g);
        Run run = runService.create(new RunService.CreateRequest(s.getId(), List.of(new RunService.GeneratorSel(g.getId(), 1)), null, null,
                Run.FailurePolicy.CONTINUE, 90));
        runService.update(run.getId(), r -> {
            r.setStatus(RunStatus.FINISHED);
            r.setStartedAt(t0);
            r.setFinishedAt(t0.plusSeconds(3L * STEP_SEC));
        });
        for (int i = 1; i <= 3; i++) {
            Instant ramp = t0.plusSeconds((long) (i - 1) * STEP_SEC);
            jdbc.update("insert into run_step (run_id, step_no, label, level_pct, ramp_start_at, hold_start_at, hold_end_at) values (?,?,?,?,?,?,?)",
                    run.getId(), i, "ступень " + i, 100.0 + 10 * (i - 1), java.sql.Timestamp.from(ramp),
                    java.sql.Timestamp.from(ramp.plusSeconds(30)), java.sql.Timestamp.from(ramp.plusSeconds(STEP_SEC)));
        }
        return runService.get(run.getId());
    }

    /** Заглушка: p95 на ступенях 100/200/900 мс (SLA 500 -> третья неуспешна), интенсивность 99% цели, ошибки 0.1%. */
    private static FakePrometheus.Responder responder(Instant t0, double latencyFactor) {
        return (q, t) -> {
            int step = (int) Math.max(1, Math.min(3, Math.round((t - t0.getEpochSecond()) / (double) STEP_SEC)));
            if (q.contains("histogram_quantile")) {
                return Map.of("work", new double[]{100, 200, 900}[step - 1] * latencyFactor);
            }
            if (q.contains("k6_iterations_total")) {
                return Map.of("work", 10.0 * (1 + 0.1 * (step - 1)) * 0.99);
            }
            if (q.contains("k6_op_errors_total") || q.contains("http_req_failed")) {
                return Map.of("work", 0.1);
            }
            return Map.of();
        };
    }

    private void connectProm() {
        settings.savePrometheus(prom.rwUrl(), prom.baseUrl(), SettingsService.AuthType.NONE, null, null, "");
    }

    @Test
    void maxIsLastSuccessBeforeFirstFailureAndPagesRender() throws Exception {
        connectProm();
        Instant t0 = Instant.now().minusSeconds(3600);
        prom.respond(responder(t0, 1.0));
        Scenario s = scenario();
        Run run = finishedRun(s, t0);

        ResultData data = results.compute(run.getId());
        assertThat(data.prometheusOk()).as(String.valueOf(data.error())).isTrue();
        assertThat(data.steps()).extracting(ResultData.StepRow::status).containsExactly("SUCCESS", "SUCCESS", "FAILED");
        ResultData.OpRow third = data.steps().get(2).ops().get(0);
        assertThat(third.p95()).isEqualTo(900.0);
        assertThat(third.ok()).isFalse();
        assertThat(data.steps().get(2).reasons()).anyMatch(r -> r.contains("SLA"));
        assertThat(data.max().kind()).isEqualTo("FOUND");
        assertThat(data.max().candidateStep()).isEqualTo(2);
        assertThat(data.max().firstProblemStep()).isEqualTo(3);
        assertThat(data.max().candidateLevelPct()).isEqualTo(110.0);
        // сохранено и читается обратно
        assertThat(results.load(run.getId())).get().extracting(d -> d.max().candidateStep()).isEqualTo(2);
        // перцентили запрашивались только как histogram_quantile по сумме всех генераторов
        assertThat(prom.queries).anyMatch(q -> q.startsWith("histogram_quantile(0.95, sum by (scenario)"));
        assertThat(prom.queries).noneMatch(q -> q.contains("avg(") && q.contains("quantile"));

        mvc.perform(get("/runs/" + run.getId() + "/results")).andExpect(status().isOk())
                .andExpect(content().string(containsString("Кандидат")))
                .andExpect(content().string(containsString("Создать подтверждение максимума")));
        mvc.perform(get("/runs/" + run.getId())).andExpect(status().isOk()).andExpect(content().string(containsString("Метрики")));
        String html = mvc.perform(get("/runs/" + run.getId() + "/report.html")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(html).contains("Отчёт о нагрузочном тесте").contains("Ступень").contains("data:image/png;base64");
        byte[] pdf = mvc.perform(get("/runs/" + run.getId() + "/report.pdf")).andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        assertThat(new String(pdf, 0, 5, StandardCharsets.ISO_8859_1)).isEqualTo("%PDF-");
        assertThat(pdf.length).isGreaterThan(5_000);

        mvc.perform(post("/runs/" + run.getId() + "/conclusion").with(csrf()).param("conclusion", "Максимум найден").param("comment", "ок"))
                .andExpect(status().is3xxRedirection());
        assertThat(runService.get(run.getId()).getConclusion()).isEqualTo("Максимум найден");

        // подтверждение максимума: новый сценарий с профилем CONFIRMATION на уровне 110%
        String loc = mvc.perform(post("/runs/" + run.getId() + "/confirm-max").with(csrf()))
                .andExpect(redirectedUrlPattern("/scenarios/*")).andReturn().getResponse().getRedirectedUrl();
        Scenario created = scenarios.get(Long.parseLong(loc.substring(loc.lastIndexOf('/') + 1)));
        assertThat(created.getName()).contains("подтверждение 110%");
        assertThat(profiles.get(created.getProfileId()).getType()).isEqualTo(ProfileType.CONFIRMATION);
    }

    @Test
    void comparisonHighlightsWorseningAndBaselineIsShownOnNewRun() throws Exception {
        connectProm();
        Instant t0a = Instant.now().minusSeconds(7200);
        Instant t0b = Instant.now().minusSeconds(3600);
        // run B втрое медленнее
        prom.respond((q, t) -> t < t0b.getEpochSecond() - 100 ? responder(t0a, 1.0).apply(q, t) : responder(t0b, 3.0).apply(q, t));
        Scenario s = scenario();
        Run a = finishedRun(s, t0a);
        Run b = finishedRun(s, t0b);
        results.compute(a.getId());
        results.compute(b.getId());

        RunCompareService.Comparison c = compare.compare(a.getId(), b.getId());
        assertThat(c.sameScenario()).isTrue();
        assertThat(c.steps()).hasSize(3);
        RunCompareService.OpCmp first = c.steps().get(0).ops().get(0);
        assertThat(first.latency().a()).isEqualTo(100.0);
        assertThat(first.latency().b()).isEqualTo(300.0);
        assertThat(first.latency().deltaPct()).isEqualTo(200.0);
        assertThat(first.latency().worse()).isTrue();
        assertThat(first.rate().worse()).isFalse();
        assertThat(c.worseCount()).isGreaterThan(0);
        assertThat(c.maxA().candidateStep()).isEqualTo(2);
        assertThat(c.maxB().candidateStep()).isEqualTo(1);

        mvc.perform(get("/runs/compare").param("a", String.valueOf(a.getId())).param("b", String.valueOf(b.getId())))
                .andExpect(status().isOk()).andExpect(content().string(containsString("+200.0")));

        // эталон: на странице нового run показывается сравнение с ним
        runService.setBaseline(a.getId(), true);
        mvc.perform(get("/runs/" + b.getId() + "/results")).andExpect(status().isOk())
                .andExpect(content().string(containsString("Сравнение с эталоном")))
                .andExpect(content().string(containsString("ухудшением")));
        // у сценария один эталон
        runService.setBaseline(b.getId(), true);
        assertThat(runService.get(a.getId()).isBaseline()).isFalse();
    }

    @Test
    void unavailablePrometheusIsReportedInsteadOfFakeNumbers() throws Exception {
        settings.savePrometheus("http://127.0.0.1:1/api/v1/write", "http://127.0.0.1:1", SettingsService.AuthType.NONE, null, null, "");
        try {
            Scenario s = scenario();
            Run run = finishedRun(s, Instant.now().minusSeconds(1800));
            ResultData data = results.compute(run.getId());
            assertThat(data.prometheusOk()).isFalse();
            assertThat(data.steps()).isEmpty();
            assertThat(data.error()).contains("Prometheus");
        } finally {
            connectProm();
        }
    }

    @Test
    void settingsAndAuditPagesRender() throws Exception {
        connectProm();
        mvc.perform(get("/settings")).andExpect(status().isOk())
                .andExpect(content().string(containsString("Диагностика связки k6")))
                .andExpect(content().string(containsString("opDuration")));
        mvc.perform(get("/settings/diagnostics/panel")).andExpect(status().isOk());
        mvc.perform(get("/audit")).andExpect(status().isOk()).andExpect(content().string(containsString("Журнал аудита")));
        mvc.perform(post("/settings/metric-names").with(csrf()).param("json", "{\"opDuration\":\"my_op_duration\"}"))
                .andExpect(status().is3xxRedirection());
        mvc.perform(get("/settings")).andExpect(content().string(containsString("my_op_duration")));
        mvc.perform(post("/settings/metric-names").with(csrf()).param("json", "{\"opDuration\":\"k6_op_duration_seconds\"}"))
                .andExpect(status().is3xxRedirection());
    }
}
