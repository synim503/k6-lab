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
    ru.k6lab.server.result.StepResultService stepResults;
    @Autowired
    ru.k6lab.server.result.ReportService reports;
    @Autowired
    ru.k6lab.server.load.LoadProfileStore profiles;
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

    @Test
    void copyChartsAndVariablesFromAnotherScriptSelectiveRenameAndOverwrite() throws Exception {
        Script src = newScript();
        Script dst = newScript();
        String srcBase = "/scripts/" + src.getId() + "/charts";
        String dstBase = "/scripts/" + dst.getId() + "/charts";

        // В скрипте-источнике задаём общую переменную $env=prod и два графика (один со своей локальной переменной $instance=app-01)
        mvc.perform(post(srcBase + "/api/variables/save").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"env\",\"value\":\"prod\"}"))
                .andExpect(status().isOk());

        String g1 = mvc.perform(post(srcBase + "/api/save").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name":"CPU $instance ($env)",
                                  "unit":"%",
                                  "enabled":true,
                                  "variables":{"instance":"app-01"},
                                  "queries":[{"promql":"rate(node_cpu_seconds_total{instance=\\"$instance\\",env=\\"$env\\"}[{range}])","legend":"{{instance}}"}]
                                }
                                """))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        long srcTpl1 = MAPPER.readTree(g1).path("template").path("id").asLong();

        String g2 = mvc.perform(post(srcBase + "/api/save").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name":"RAM серверов",
                                  "unit":"Б",
                                  "enabled":true,
                                  "queries":[
                                    {"promql":"node_memory_MemUsed_bytes","legend":"Used"},
                                    {"promql":"node_memory_MemTotal_bytes","legend":"Total"}
                                  ]
                                }
                                """))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        long srcTpl2 = MAPPER.readTree(g2).path("template").path("id").asLong();

        // 1. В целевом скрипте запрашиваем список других скриптов с графиками
        String otherRes = mvc.perform(get(dstBase + "/api/other-scripts"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        JsonNode scriptsNode = MAPPER.readTree(otherRes).path("scripts");
        JsonNode foundSrc = null;
        for (JsonNode n : scriptsNode) {
            if (n.path("id").asLong() == src.getId()) {
                foundSrc = n;
                break;
            }
        }
        assertThat(foundSrc).isNotNull();
        assertThat(foundSrc.path("chartCount").asInt()).isEqualTo(2);
        assertThat(foundSrc.path("variableCount").asInt()).isEqualTo(1);

        // 2. Копируем только первый график + общие переменные в целевой скрипт
        String copy1 = mvc.perform(post(dstBase + "/api/copy-from-script").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sourceScriptId\":" + src.getId() + ",\"templateIds\":[" + srcTpl1 + "],\"copyVariables\":true,\"overwriteExisting\":false}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        JsonNode copy1Node = MAPPER.readTree(copy1);
        assertThat(copy1Node.path("ok").asBoolean()).isTrue();
        assertThat(copy1Node.path("copiedCount").asInt()).isEqualTo(1);
        assertThat(copy1Node.path("copiedVariablesCount").asInt()).isEqualTo(1);

        List<ChartTemplateStore.Template> dstList = store.list(dst.getId());
        assertThat(dstList).hasSize(1);
        assertThat(dstList.get(0).name()).isEqualTo("CPU $instance ($env)");
        assertThat(dstList.get(0).unit()).isEqualTo("%");
        assertThat(dstList.get(0).variables()).containsExactlyEntriesOf(Map.of("instance", "app-01"));

        // 3. Повторное копирование обоих графиков с overwriteExisting=false:
        // существующий график не затирается, а получает имя «CPU $instance ($env) (2)», плюс добавляется «RAM серверов»
        mvc.perform(post(dstBase + "/api/copy-from-script").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sourceScriptId\":" + src.getId() + ",\"templateIds\":[" + srcTpl1 + "," + srcTpl2 + "],\"copyVariables\":false,\"overwriteExisting\":false}"))
                .andExpect(status().isOk());

        List<ChartTemplateStore.Template> dstList2 = store.list(dst.getId());
        assertThat(dstList2).extracting(ChartTemplateStore.Template::name)
                .containsExactly("CPU $instance ($env)", "CPU $instance ($env) (2)", "RAM серверов");
        assertThat(dstList2.get(2).queries()).hasSize(2);

        // 4. Меняем график в источнике и копируем с overwriteExisting=true — одноимённый график в целевом скрипте обновляется
        mvc.perform(post(srcBase + "/api/save").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"id\":" + srcTpl1 + ",\"name\":\"CPU $instance ($env)\",\"unit\":\"%\",\"enabled\":true,"
                                + "\"variables\":{\"instance\":\"app-99\"},"
                                + "\"queries\":[{\"promql\":\"up\",\"legend\":\"{{instance}}\"}]}"))
                .andExpect(status().isOk());

        mvc.perform(post(dstBase + "/api/copy-from-script").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sourceScriptId\":" + src.getId() + ",\"templateIds\":[" + srcTpl1 + "],\"copyVariables\":true,\"overwriteExisting\":true}"))
                .andExpect(status().isOk());

        List<ChartTemplateStore.Template> dstList3 = store.list(dst.getId());
        assertThat(dstList3).hasSize(3);
        assertThat(dstList3.get(0).variables()).containsExactlyEntriesOf(Map.of("instance", "app-99"));
        assertThat(dstList3.get(0).promql()).isEqualTo("up");
    }

    @Test
    void customPrometheusChecksEvaluateInStepResultsAndRenderInReportsAlongsideSla() throws Exception {
        settings.savePrometheus(prom.rwUrl(), prom.baseUrl(), SettingsService.AuthType.NONE, null, null, "");
        // Метрики операций k6 отличные (интенсивность в норме, ошибок 0%, время ответа ниже SLA),
        // но кастомная метрика CPU сервера возвращает 87.5% (выше порога <= 80%), а RAM возвращает 62.0% (в норме <= 85%)
        prom.respond((q, t) -> {
            if (q.contains("node_cpu_seconds_total")) {
                return Map.of("app-server-01", 87.5);
            }
            if (q.contains("node_memory_MemAvailable_bytes")) {
                return Map.of("app-server-01", 62.0);
            }
            if (q.contains("error") || q.contains("failed")) {
                return Map.of("work", 0.0);
            }
            if (q.contains("histogram_quantile")) {
                return Map.of("work", 120.0);
            }
            return Map.of("work", 5.0);
        });

        Script s = newScript();
        LoadForm f = load.toForm(s.getId());
        LoadForm.CustomCheckForm cpuCheck = new LoadForm.CustomCheckForm();
        cpuCheck.setName("Утилизация CPU сервера");
        cpuCheck.setPromql("100 - (avg by (instance) (rate(node_cpu_seconds_total{mode=\"idle\"}[{range}])) * 100)");
        cpuCheck.setCmp("LTE");
        cpuCheck.setThreshold(80.0);
        cpuCheck.setUnit("%");
        cpuCheck.setStepSla(true);
        cpuCheck.setAutoStop(true);

        LoadForm.CustomCheckForm ramCheck = new LoadForm.CustomCheckForm();
        ramCheck.setName("Использование RAM сервера");
        ramCheck.setPromql("(1 - (node_memory_MemAvailable_bytes / node_memory_MemTotal_bytes)) * 100");
        ramCheck.setCmp("LTE");
        ramCheck.setThreshold(85.0);
        ramCheck.setUnit("%");
        ramCheck.setStepSla(true);
        ramCheck.setAutoStop(false);

        f.getStop().getCustomChecks().add(cpuCheck);
        f.getStop().getCustomChecks().add(ramCheck);
        load.save(s.getId(), f);

        // Проверяем, что правила сохранились в скрипт и видны на странице /scripts/{id}/load
        LoadForm savedForm = load.toForm(s.getId());
        assertThat(savedForm.getStop().getCustomChecks()).hasSize(2);
        assertThat(savedForm.getStop().getCustomChecks().get(0).getName()).isEqualTo("Утилизация CPU сервера");
        assertThat(savedForm.getStop().getCustomChecks().get(0).getThreshold()).isEqualTo(80.0);

        mvc.perform(get("/scripts/" + s.getId() + "/load"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Кастомные метрики Prometheus")))
                .andExpect(content().string(containsString("Утилизация CPU сервера")));

        // Создаём завершённый запуск с одной ступенью удержания и считаем итоги по ступеням
        Run run = finishedRun(s);
        Instant t0 = run.getStartedAt();
        ru.k6lab.server.run.RunStep step = new ru.k6lab.server.run.RunStep();
        step.setRunId(run.getId());
        step.setStepNo(1);
        step.setLabel("Ступень 1");
        step.setLevelPct(100.0);
        step.setRampStartAt(t0);
        step.setHoldStartAt(t0);
        step.setHoldEndAt(t0.plusSeconds(240));
        runService.saveSteps(run.getId(), List.of(step));

        var res = stepResults.compute(run.getId());
        assertThat(res.steps()).hasSize(1);
        var stepRow = res.steps().get(0);
        // Из-за превышения кастомной метрики CPU (87.5% > 80%) ступень получает статус FAILED и причину в списке reasons
        assertThat(stepRow.status()).isEqualTo("FAILED");
        assertThat(stepRow.reasons()).anyMatch(r -> r.contains("Утилизация CPU сервера") && r.contains("87.5") && r.contains("80"));
        assertThat(stepRow.customChecks()).hasSize(2);
        assertThat(stepRow.customChecks().get(0).ok()).isFalse();
        assertThat(stepRow.customChecks().get(0).value()).isEqualTo(87.5);
        assertThat(stepRow.customChecks().get(1).ok()).isTrue();
        assertThat(stepRow.customChecks().get(1).value()).isEqualTo(62.0);

        // В HTML-отчёте и Word-отчёте кастомные метрики Prometheus рендерятся рядом с таблицей SLA
        String html = reports.renderHtml(run.getId());
        assertThat(html).contains("Кастомная метрика Prometheus")
                .contains("Утилизация CPU сервера")
                .contains("Использование RAM сервера")
                .contains("87.5");
        byte[] docx = reports.renderDocx(run.getId());
        assertThat(docx).isNotEmpty();
    }

    @Test
    void baseLoadProfileAppliesSharedEnvAutostopPrometheusChecksSlaAndWarmupAcrossAllProfiles() throws Exception {
        String code = "import http from 'k6/http'; export function work() { http.get('http://localhost'); } export default function() {}";
        ScriptVersion v = scripts.createFromUpload("base40-" + UUID.randomUUID().toString().substring(0, 8), "", "s.js",
                new ByteArrayInputStream(code.getBytes(StandardCharsets.UTF_8)), "", null);
        Script s = scripts.get(v.getScriptId());

        // Импортируем 2 профиля (аналог 40 профилей) со старыми/разными env, SLA, автостопом и без прогрева
        String importJson = """
                {
                  "profiles": [
                    {
                      "name": "Профиль 1 (постоянный)",
                      "operations": [
                        {
                          "name": "work",
                          "execFn": "work",
                          "enabled": true,
                          "executor": "constant-arrival-rate",
                          "scenario": {"rate": 25, "timeUnit": "1s", "duration": "5m", "preAllocatedVUs": 10, "maxVUs": 50},
                          "slaPercentile": 95,
                          "slaThresholdMs": 1000,
                          "maxErrorPct": 5.0
                        }
                      ],
                      "env": {"STAND": "old-1"},
                      "stop": {"errorsEnabled": false, "consecutive": 4, "windowSec": 60}
                    },
                    {
                      "name": "Профиль 2 (ступеньки)",
                      "operations": [
                        {
                          "name": "work",
                          "execFn": "work",
                          "enabled": true,
                          "executor": "ramping-arrival-rate",
                          "scenario": {
                            "startRate": 0, "timeUnit": "1s", "preAllocatedVUs": 10, "maxVUs": 50,
                            "stages": [{"duration": "0s", "target": 40}, {"duration": "5m", "target": 40}]
                          },
                          "slaPercentile": 90,
                          "slaThresholdMs": 1500,
                          "maxErrorPct": 2.0
                        }
                      ],
                      "env": {"STAND": "old-2"},
                      "stop": {"errorsEnabled": false, "consecutive": 4, "windowSec": 60}
                    }
                  ]
                }
                """;
        mvc.perform(post("/scripts/" + s.getId() + "/load/profiles/import").with(csrf()).param("json", importJson))
                .andExpect(status().is3xxRedirection());
        var profList = profiles.list(s.getId());
        assertThat(profList).hasSize(2);

        // В базовом профиле скрипта (даже без включённых базовых сценариев!) задаём общие параметры для всех профилей:
        // env, автостоп + кастомную метрику Prometheus (CPU <= 80%), SLA операции work (p99 <= 250 мс, ошибки <= 0.5%), общий прогрев 2m и 3 генератора
        mvc.perform(post("/scripts/" + s.getId() + "/load/apply-base").with(csrf())
                        .param("baseProfileActive", "true")
                        .param("baseOverrideEnv", "true")
                        .param("baseOverrideStop", "true")
                        .param("baseOverrideSla", "true")
                        .param("baseOverrideWarmup", "true")
                        .param("baseOverrideData", "true")
                        .param("baseOverrideGenerators", "true")
                        .param("baseWarmupDuration", "2m")
                        .param("generatorCount", "3")
                        .param("envText", "STAND=unified-prod\nTOKEN=sec123")
                        .param("ops[0].name", "work")
                        .param("ops[0].execFn", "work")
                        .param("ops[0].enabled", "false")
                        .param("ops[0].executor", "constant-arrival-rate")
                        .param("ops[0].slaPercentile", "99")
                        .param("ops[0].slaThresholdMs", "250")
                        .param("ops[0].maxErrorPct", "0.5")
                        .param("stop.errorsEnabled", "true")
                        .param("stop.consecutive", "3")
                        .param("stop.windowSec", "45")
                        .param("stop.customChecks[0].name", "CPU серверов")
                        .param("stop.customChecks[0].promql", "100 - avg(rate(node_cpu_seconds_total{mode=\"idle\"}[{range}])) * 100")
                        .param("stop.customChecks[0].cmp", "LTE")
                        .param("stop.customChecks[0].threshold", "80")
                        .param("stop.customChecks[0].unit", "%")
                        .param("stop.customChecks[0].stepSla", "true")
                        .param("stop.customChecks[0].autoStop", "true"))
                .andExpect(status().is3xxRedirection());

        assertThat(scripts.get(s.getId()).getGeneratorCount()).isEqualTo(3);

        // Проверяем, что при сборке нагрузки любого из профилей (fromProfile) применяются все базовые параметры, а кривая нагрузки остаётся своей
        for (var p : profiles.list(s.getId())) {
            var built = load.fromProfile(s.getId(), p.id());
            assertThat(built.env()).containsEntry("STAND", "unified-prod").containsEntry("TOKEN", "sec123");
            assertThat(built.stop()).containsEntry("errorsEnabled", true).containsEntry("consecutive", 3).containsEntry("windowSec", 45);
            assertThat(ru.k6lab.server.load.LoadCriteria.customChecks(built.stop()))
                    .singleElement()
                    .satisfies(cc -> {
                        assertThat(cc.name()).isEqualTo("CPU серверов");
                        assertThat(cc.threshold()).isEqualTo(80.0);
                        assertThat(cc.autoStop()).isTrue();
                    });
            assertThat(built.ops()).hasSize(1);
            var op = built.ops().get(0);
            assertThat(op.getSlaPercentile()).isEqualTo(99);
            assertThat(op.getSlaThresholdMs()).isEqualTo(250.0);
            assertThat(op.getMaxErrorPct()).isEqualTo(0.5);
            // Прогрев 2m применился и к constant-arrival-rate, и к ramping-arrival-rate
            assertThat(op.getScenario().get("warmup")).isNotNull();
        }
    }
}
