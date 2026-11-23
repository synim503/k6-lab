package ru.k6lab.server;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import ru.k6lab.core.runstate.RunStatus;
import ru.k6lab.server.generator.Generator;
import ru.k6lab.server.generator.GeneratorRepository;
import ru.k6lab.server.load.LoadForm;
import ru.k6lab.server.load.LoadService;
import ru.k6lab.server.result.ChartRenderer;
import ru.k6lab.server.result.ChartTemplateStore;
import ru.k6lab.server.result.CustomChartService;
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

/** Интеграционные проверки конструктора графиков отчёта: редактирование, переименование, копирование, порядок, массовые операции, переменные и живой предпросмотр. */
@WithMockUser(roles = "ADMIN")
class ChartBuilderIT extends AbstractIT {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    static FakePrometheus prom;

    @Autowired
    ScriptService scripts;
    @Autowired
    LoadService load;
    @Autowired
    RunService runService;
    @Autowired
    GeneratorRepository generators;
    @Autowired
    SettingsService settings;
    @Autowired
    ChartTemplateStore store;
    @Autowired
    CustomChartService customCharts;
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

    private Script newScript() throws Exception {
        String code = "import http from 'k6/http'; export function work() { http.get('http://localhost'); } export default function() {}";
        ScriptVersion v = scripts.createFromUpload("cb-" + UUID.randomUUID().toString().substring(0, 8), "", "s.js",
                new ByteArrayInputStream(code.getBytes(StandardCharsets.UTF_8)), "", null);
        LoadForm f = new LoadForm();
        LoadForm.OpForm o = new LoadForm.OpForm();
        o.setName("work");
        o.setExecFn("work");
        o.setRatePerSec(5.0);
        o.setExpectedDurationSec(0.1);
        f.getOps().add(o);
        load.save(v.getScriptId(), f);
        return scripts.get(v.getScriptId());
    }

    private Run finishedRun(Script s) throws Exception {
        Generator g = new Generator();
        g.setName("cb-gen-" + UUID.randomUUID().toString().substring(0, 6));
        g.setHost("127.0.0.1");
        g.setInstallDir("/opt/k6-agent");
        g.setServerUrl("ws://127.0.0.1:1/agent/ws");
        g.setStatus(Generator.Status.INSTALLING);
        g = generators.save(g);
        Run run = runService.create(new RunService.CreateRequest(s.getId(), List.of(new RunService.GeneratorSel(g.getId(), 1)),
                Run.FailurePolicy.CONTINUE, 60));
        Instant t0 = Instant.now().minusSeconds(600);
        runService.update(run.getId(), r -> {
            r.setStatus(RunStatus.FINISHED);
            r.setStartedAt(t0);
            r.setPlannedStartAt(t0);
            r.setFinishedAt(t0.plusSeconds(300));
        });
        return runService.get(run.getId());
    }

    @Test
    void fullChartBuilderWorkflowEditRenameReorderDuplicateBulkAndPreview() throws Exception {
        settings.savePrometheus(prom.rwUrl(), prom.baseUrl(), SettingsService.AuthType.NONE, null, null, "");
        prom.respond((q, t) -> Map.of("work", 42.5, "checkout", 18.0));

        Script s = newScript();
        Run run = finishedRun(s);
        String base = "/scripts/" + s.getId() + "/charts";

        // 1. Страница конструктора рендерится с запуском в селекторе предпросмотра
        mvc.perform(get(base)).andExpect(status().isOk())
                .andExpect(content().string(containsString("cb-studio")))
                .andExpect(content().string(containsString("#" + run.getId())));

        // 2. Создание двух графиков через AJAX API
        String res1 = mvc.perform(post(base + "/api/save").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"CPU","unit":"%","enabled":true,"queries":[{"promql":"rate(node_cpu_seconds_total{env=\\"$env\\"}[{range}])","legend":"{{scenario}}"}]}
                                """))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        long id1 = MAPPER.readTree(res1).path("template").path("id").asLong();
        assertThat(id1).isPositive();

        String res2 = mvc.perform(post(base + "/api/save").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"RAM","unit":"Б","enabled":true,"queries":[{"promql":"node_memory_MemAvailable_bytes","legend":""}]}
                                """))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        long id2 = MAPPER.readTree(res2).path("template").path("id").asLong();

        // 3. Переименование графика по ID и проверка конфликта имён
        mvc.perform(post(base + "/api/save").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"id\":" + id1 + ",\"name\":\"CPU серверов\",\"unit\":\"%\",\"enabled\":true,\"queries\":[{\"promql\":\"rate(node_cpu_seconds_total{env=\\\"$env\\\",run=\\\"{run_id}\\\"}[{range}])\",\"legend\":\"{{scenario}}\"}]}"))
                .andExpect(status().isOk());
        assertThat(store.get(s.getId(), id1)).get().extracting(ChartTemplateStore.Template::name).isEqualTo("CPU серверов");

        mvc.perform(post(base + "/api/save").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"id\":" + id1 + ",\"name\":\"RAM\",\"unit\":\"%\",\"enabled\":true,\"queries\":[{\"promql\":\"up\",\"legend\":\"\"}]}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().string(containsString("уже существует")));

        // 4. Дублирование графика (копия встаёт сразу после оригинала)
        String dupRes = mvc.perform(post(base + "/api/" + id1 + "/duplicate").with(csrf()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        JsonNode dupNode = MAPPER.readTree(dupRes).path("template");
        long dupId = dupNode.path("id").asLong();
        assertThat(dupNode.path("name").asText()).isEqualTo("CPU серверов (копия)");
        assertThat(store.list(s.getId())).extracting(ChartTemplateStore.Template::id).containsExactly(id1, dupId, id2);

        // 5. Изменение порядка графиков (drag-and-drop)
        mvc.perform(post(base + "/api/reorder").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ids\":[" + id2 + "," + id1 + "," + dupId + "]}"))
                .andExpect(status().isOk());
        assertThat(store.list(s.getId())).extracting(ChartTemplateStore.Template::id).containsExactly(id2, id1, dupId);

        // 6. Массовое выключение и удаление выбранных
        mvc.perform(post(base + "/api/bulk-toggle").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ids\":[" + id2 + "," + dupId + "],\"enabled\":false}"))
                .andExpect(status().isOk());
        assertThat(store.enabled(s.getId())).extracting(ChartTemplateStore.Template::id).containsExactly(id1);

        mvc.perform(post(base + "/api/delete-selected").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ids\":[" + dupId + "]}"))
                .andExpect(status().isOk());
        assertThat(store.list(s.getId())).extracting(ChartTemplateStore.Template::id).containsExactly(id2, id1);

        // 7. Переменные через AJAX API
        String varRes = mvc.perform(post(base + "/api/variables/save").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"$env\",\"value\":\"prod\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(MAPPER.readTree(varRes).path("name").asText()).isEqualTo("env");

        // 8. Живой WYSIWYG-предпросмотр на запуске скрипта (с подстановкой $env, {run_id}, {range})
        String prevRes = mvc.perform(post(base + "/api/preview").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"CPU $env\",\"unit\":\"%\",\"runId\":" + run.getId()
                                + ",\"queries\":[{\"promql\":\"rate(node_cpu_seconds_total{env=\\\"$env\\\",test_run_id=\\\"{run_id}\\\"}[{range}])\",\"legend\":\"{{scenario}}\"}]}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        JsonNode prev = MAPPER.readTree(prevRes);
        assertThat(prev.path("ok").asBoolean()).isTrue();
        assertThat(prev.path("title").asText()).isEqualTo("CPU prod");
        assertThat(prev.path("dataUri").asText()).startsWith("data:image/png;base64,");
        assertThat(prev.path("totalSeries").asInt()).isEqualTo(2);
        assertThat(prev.path("queries").get(0).path("expandedPromql").asText())
                .contains("env=\"prod\"").contains("test_run_id=\"" + run.getId() + "\"").doesNotContain("{range}");
        assertThat(prev.path("queries").get(0).path("labelKeys").toString()).contains("scenario");

        // 9. Предпросмотр за последние 30 минут (без привязки к запуску)
        String prevMin = mvc.perform(post(base + "/api/preview").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Live\",\"unit\":\"\",\"minutes\":30,\"queries\":[{\"promql\":\"up\",\"legend\":\"\"}]}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(MAPPER.readTree(prevMin).path("ok").asBoolean()).isTrue();
        assertThat(MAPPER.readTree(prevMin).path("windowLabel").asText()).contains("Последние 30 мин");

        // 10. Список имён метрик Prometheus для автодополнения
        String metRes = mvc.perform(get(base + "/api/metrics")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(MAPPER.readTree(metRes).path("ok").asBoolean()).isTrue();
        assertThat(MAPPER.readTree(metRes).path("metrics").toString()).contains("k6_http_reqs_total").contains("node_cpu_seconds_total");
    }

    @Test
    void perChartVariablesOverrideScriptDefaultsDuplicateAndRenderInReport() throws Exception {
        settings.savePrometheus(prom.rwUrl(), prom.baseUrl(), SettingsService.AuthType.NONE, null, null, "");
        prom.respond((q, t) -> Map.of("work", q.contains("app-01") ? 15.0 : 85.0));

        Script s = newScript();
        Run run = finishedRun(s);
        String base = "/scripts/" + s.getId() + "/charts";

        // Общие переменные скрипта по умолчанию: host=default-host, env=prod
        mvc.perform(post(base + "/api/variables/save").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"host\",\"value\":\"default-host\"}"))
                .andExpect(status().isOk());
        mvc.perform(post(base + "/api/variables/save").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"env\",\"value\":\"prod\"}"))
                .andExpect(status().isOk());

        // Создаём первый график с локальной переменной host=app-01 (переопределяет общую default-host, при этом $env берётся из общих)
        String save1 = mvc.perform(post(base + "/api/save").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name":"CPU $host ($env) #1",
                                  "unit":"%",
                                  "enabled":true,
                                  "variables":{"$host":"app-01"},
                                  "queries":[{"promql":"rate(node_cpu_seconds_total{host=\\"$host\\",env=\\"$env\\"}[{range}])","legend":"{{scenario}}"}]
                                }
                                """))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        JsonNode t1 = MAPPER.readTree(save1).path("template");
        long id1 = t1.path("id").asLong();
        assertThat(t1.path("variables").path("host").asText()).isEqualTo("app-01");

        // Дублируем график: локальные переменные должны скопироваться на копию
        String dupRes = mvc.perform(post(base + "/api/" + id1 + "/duplicate").with(csrf()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        JsonNode t2 = MAPPER.readTree(dupRes).path("template");
        long id2 = t2.path("id").asLong();
        assertThat(t2.path("variables").path("host").asText()).isEqualTo("app-01");

        // Меняем локальную переменную на копии (host=app-02, env=stage) — оригинал не должен измениться
        mvc.perform(post(base + "/api/save").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"id\":" + id2 + ",\"name\":\"CPU $host ($env) #2\",\"unit\":\"%\",\"enabled\":true,"
                                + "\"variables\":{\"host\":\"app-02\",\"env\":\"stage\"},"
                                + "\"queries\":[{\"promql\":\"rate(node_cpu_seconds_total{host=\\\"$host\\\",env=\\\"$env\\\"}[{range}])\",\"legend\":\"{{scenario}}\"}]}"))
                .andExpect(status().isOk());

        assertThat(store.get(s.getId(), id1).orElseThrow().variables()).containsExactly(Map.entry("host", "app-01"));
        assertThat(store.get(s.getId(), id2).orElseThrow().variables()).containsExactly(Map.entry("host", "app-02"), Map.entry("env", "stage"));

        // Предпросмотр с локальными переменными графика: переопределяет общие переменные и подставляет заголовок
        String prev1 = mvc.perform(post(base + "/api/preview").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"CPU $host ($env)\",\"unit\":\"%\",\"runId\":" + run.getId()
                                + ",\"variables\":{\"host\":\"app-01\"}"
                                + ",\"queries\":[{\"promql\":\"rate(node_cpu_seconds_total{host=\\\"$host\\\",env=\\\"$env\\\"}[{range}])\",\"legend\":\"{{scenario}}\"}]}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        JsonNode p1 = MAPPER.readTree(prev1);
        assertThat(p1.path("ok").asBoolean()).isTrue();
        assertThat(p1.path("title").asText()).isEqualTo("CPU app-01 (prod)");
        assertThat(p1.path("queries").get(0).path("expandedPromql").asText())
                .contains("host=\"app-01\"").contains("env=\"prod\"");

        // Рендер отчёта: оба графика одной панели выполняются со своими независимыми переменными и заголовками
        prom.queries.clear();
        List<ChartRenderer.Img> rendered = customCharts.charts(run);
        assertThat(rendered).extracting(ChartRenderer.Img::title)
                .containsExactly("CPU app-01 (prod) #1", "CPU app-02 (stage) #2");
        assertThat(prom.queries).anyMatch(q -> q.contains("host=\"app-01\"") && q.contains("env=\"prod\""));
        assertThat(prom.queries).anyMatch(q -> q.contains("host=\"app-02\"") && q.contains("env=\"stage\""));
    }

    @Test
    void grafanaImportAttachesReferencedVariablesPerPanel() throws Exception {
        Script s = newScript();
        String base = "/scripts/" + s.getId() + "/charts";

        String dashJson = """
                {
                  "title": "Services",
                  "templating": {
                    "list": [
                      {"name": "instance", "current": {"value": "node-1"}},
                      {"name": "job", "current": {"value": "api"}},
                      {"name": "unused", "current": {"value": "x"}}
                    ]
                  },
                  "panels": [
                    {
                      "title": "CPU $instance",
                      "type": "timeseries",
                      "targets": [{"expr": "rate(node_cpu_seconds_total{instance=\\"$instance\\"}[1m])"}]
                    },
                    {
                      "title": "RPS",
                      "type": "timeseries",
                      "targets": [{"expr": "rate(http_requests_total{job=\\"$job\\"}[1m])"}]
                    }
                  ]
                }
                """;

        mvc.perform(post(base + "/import-grafana").with(csrf()).param("json", dashJson))
                .andExpect(status().is3xxRedirection());

        List<ChartTemplateStore.Template> list = store.list(s.getId());
        assertThat(list).hasSize(2);
        assertThat(list.get(0).name()).isEqualTo("CPU $instance");
        assertThat(list.get(0).variables()).containsExactlyEntriesOf(Map.of("instance", "node-1"));
        assertThat(list.get(1).name()).isEqualTo("RPS");
        assertThat(list.get(1).variables()).containsExactlyEntriesOf(Map.of("job", "api"));
    }
}
