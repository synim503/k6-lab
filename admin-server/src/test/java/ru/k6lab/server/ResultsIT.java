package ru.k6lab.server;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import ru.k6lab.core.runstate.RunStatus;
import ru.k6lab.server.generator.Generator;
import ru.k6lab.server.generator.GeneratorRepository;
import ru.k6lab.server.result.ResultData;
import ru.k6lab.server.result.StepResultService;
import ru.k6lab.server.load.LoadForm;
import ru.k6lab.server.load.LoadService;
import ru.k6lab.server.run.Run;
import ru.k6lab.server.run.RunService;
import ru.k6lab.server.script.Script;
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
    LoadService load;
    @Autowired
    RunService runService;
    @Autowired
    StepResultService results;
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

    private Script scriptWithLoad() throws Exception {
        String n = UUID.randomUUID().toString().substring(0, 8);
        ScriptVersion v = scripts.createFromUpload("scr-" + n, "", "s.js", new ByteArrayInputStream(SCRIPT.getBytes(StandardCharsets.UTF_8)), "", null);
        LoadForm f = new LoadForm();
        LoadForm.OpForm o = new LoadForm.OpForm();
        o.setName("work");
        o.setExecFn("work");
        o.setRatePerSec(10.0);
        o.setExpectedDurationSec(0.1);
        o.setSlaThresholdMs(500.0);
        o.setSlaPercentile(95);
        o.setMaxErrorPct(1.0);
        f.getOps().add(o);
        load.save(v.getScriptId(), f);
        return scripts.get(v.getScriptId());
    }

    /** Завершённый run с тремя ступенями (100/110/120%); t0 - начало теста. */
    private Run finishedRun(Script s, Instant t0) throws Exception {
        Generator g = new Generator();
        g.setName("res-" + UUID.randomUUID().toString().substring(0, 6));
        g.setHost("127.0.0.1");
        g.setInstallDir("/opt/k6-agent");
        g.setServerUrl("ws://127.0.0.1:1/agent/ws");
        g.setStatus(Generator.Status.INSTALLING);
        g = generators.save(g);
        Run run = runService.create(new RunService.CreateRequest(s.getId(), List.of(new RunService.GeneratorSel(g.getId(), 1)),
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
    void stepVerdictsAreComputedFromPrometheusAndPagesRender() throws Exception {
        connectProm();
        Instant t0 = Instant.now().minusSeconds(3600);
        prom.respond(responder(t0, 1.0));
        Script s = scriptWithLoad();
        Run run = finishedRun(s, t0);

        ResultData data = results.compute(run.getId());
        assertThat(data.prometheusOk()).as(String.valueOf(data.error())).isTrue();
        assertThat(data.steps()).extracting(ResultData.StepRow::status).containsExactly("SUCCESS", "SUCCESS", "FAILED");
        ResultData.OpRow third = data.steps().get(2).ops().get(0);
        assertThat(third.p95()).isEqualTo(900.0);
        assertThat(third.ok()).isFalse();
        assertThat(data.steps().get(2).reasons()).anyMatch(r -> r.contains("SLA"));
        // сохранено и читается обратно
        assertThat(results.load(run.getId())).get().extracting(d -> d.steps().size()).isEqualTo(3);
        // перцентили запрашивались только как histogram_quantile по сумме всех генераторов
        assertThat(prom.queries).anyMatch(q -> q.startsWith("histogram_quantile(0.95, sum by (scenario, le)"));
        assertThat(prom.queries).noneMatch(q -> q.contains("avg(") && q.contains("quantile"));

        mvc.perform(get("/runs/" + run.getId() + "/results")).andExpect(status().isOk())
                .andExpect(content().string(containsString("Пересчитать итоги")));
        mvc.perform(get("/runs/" + run.getId())).andExpect(status().isOk()).andExpect(content().string(containsString("Метрики")));
        String html = mvc.perform(get("/runs/" + run.getId() + "/report.html")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(html).contains("Отчёт о нагрузочном тесте").contains("Ступень").contains("data:image/png;base64");
        // графики по времени от начала теста (много точек), а не «по ступеням» (одна точка на ступень)
        assertThat(html).contains("Интенсивность: факт и план").contains("Время ответа: work").contains("Ошибки");
        assertThat(prom.queries).anyMatch(q -> q.startsWith("sum by (scenario) (increase(k6_iterations_total"));
        byte[] pdf = mvc.perform(get("/runs/" + run.getId() + "/report.pdf")).andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        assertThat(new String(pdf, 0, 5, StandardCharsets.ISO_8859_1)).isEqualTo("%PDF-");
        assertThat(pdf.length).isGreaterThan(5_000);

        mvc.perform(post("/runs/" + run.getId() + "/conclusion").with(csrf()).param("conclusion", "Деградация").param("comment", "ок"))
                .andExpect(status().is3xxRedirection());
        assertThat(runService.get(run.getId()).getConclusion()).isEqualTo("Деградация");
    }

    @Test
    void unavailablePrometheusIsReportedInsteadOfFakeNumbers() throws Exception {
        settings.savePrometheus("http://127.0.0.1:1/api/v1/write", "http://127.0.0.1:1", SettingsService.AuthType.NONE, null, null, "");
        try {
            Script s = scriptWithLoad();
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
