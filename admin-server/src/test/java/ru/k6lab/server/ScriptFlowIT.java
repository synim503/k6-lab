package ru.k6lab.server;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import ru.k6lab.server.load.LoadOperation;
import ru.k6lab.server.load.LoadService;
import ru.k6lab.server.script.ScriptService;
import ru.k6lab.server.script.ScriptVersion;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrlPattern;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Основной путь: сервер -> скрипт -> описание нагрузки в скрипте -> запуск и остановка. */
@WithMockUser(roles = "ADMIN")
class ScriptFlowIT extends AbstractIT {

    @Autowired
    MockMvc mvc;
    @Autowired
    ScriptService scripts;
    @Autowired
    LoadService load;

    private ScriptVersion script(String name) throws Exception {
        String js = "import http from 'k6/http';\nexport function login() { http.get('http://localhost/a'); }\n"
                + "export function pay() { http.get('http://localhost/b'); }\nexport default function () {}\n";
        return scripts.createFromUpload(name, "", "s.js", new ByteArrayInputStream(js.getBytes(StandardCharsets.UTF_8)), "", null);
    }

    @Test
    void loadIsDescribedInScriptAndRunFormIsAvailable() throws Exception {
        ScriptVersion v = script("flow-script");
        long id = v.getScriptId();
        for (String p : new String[]{"/scripts", "/scripts/" + id, "/scripts/" + id + "/load", "/scripts/" + id + "/run", "/generators/add", "/generators"}) {
            mvc.perform(get(p)).andExpect(status().isOk());
        }
        // форма нагрузки сразу содержит все функции скрипта
        mvc.perform(get("/scripts/" + id + "/load")).andExpect(content().string(containsString("login")))
                .andExpect(content().string(containsString("pay")));
        // пока нагрузка не описана, запуск недоступен с понятным пояснением
        mvc.perform(get("/scripts/" + id + "/run")).andExpect(content().string(containsString("Не описана нагрузка")));
        assertThat(load.isDefined(id)).isFalse();

        mvc.perform(post("/scripts/" + id + "/load").with(csrf())
                        .param("ops[0].execFn", "login").param("ops[0].name", "login").param("ops[0].ratePerSec", "5")
                        .param("ops[0].slaPercentile", "95").param("ops[0].slaThresholdMs", "800").param("ops[0].maxErrorPct", "1")
                        .param("ops[0].expectedDurationSec", "0.3")
                        .param("ops[1].execFn", "pay").param("ops[1].name", "pay").param("ops[1].ratePerSec", "")
                        .param("ops[1].slaPercentile", "95").param("ops[1].slaThresholdMs", "1000").param("ops[1].maxErrorPct", "1")
                        .param("holdMinutes", "2").param("rampSec", "30").param("levelPct", "100"))
                .andExpect(redirectedUrl("/scripts/" + id));
        // сохраняется только операция с интенсивностью
        assertThat(load.operations(id)).extracting(LoadOperation::getName).containsExactly("login");
        assertThat(load.isDefined(id)).isTrue();
        assertThat(scripts.get(id).getHoldSec()).isEqualTo(120);

        mvc.perform(get("/scripts/" + id)).andExpect(content().string(containsString("Запустить")))
                .andExpect(content().string(containsString("login")));
        // нагрузка описана: блокировки нет; форма с числом генераторов - только если в базе есть серверы
        mvc.perform(get("/scripts/" + id + "/run")).andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.not(containsString("Не описана нагрузка"))))
                .andExpect(content().string(org.hamcrest.Matchers.anyOf(containsString("Количество генераторов"), containsString("Серверов нет"))));
        mvc.perform(get("/scripts/" + id + "/config.json")).andExpect(status().isOk()).andExpect(content().string(containsString("login")));

        // свободных генераторов нет - понятная ошибка, а не 500
        mvc.perform(post("/scripts/" + id + "/run").with(csrf())).andExpect(status().isOk())
                .andExpect(content().string(containsString("Свободных генераторов")));
        mvc.perform(post("/scripts/" + id + "/run").with(csrf()).param("count", "0")).andExpect(status().isOk())
                .andExpect(content().string(containsString("не меньше 1")));
        // остановка без выполняющихся запусков безвредна
        mvc.perform(post("/scripts/" + id + "/stop").with(csrf())).andExpect(redirectedUrl("/scripts/" + id));
    }

    @Autowired
    ru.k6lab.server.script.SandboxService sandbox;
    @Autowired
    ru.k6lab.server.script.K6Tool k6tool;

    @Autowired
    ru.k6lab.server.run.RunService runService;
    @Autowired
    ru.k6lab.server.generator.GeneratorRepository generatorRepo;

    @Test
    void runFormWithServersShowsFlagsAndRunPageShowsChosenOnes() throws Exception {
        long id = script("flow-flags-ui").getScriptId();
        var f = new ru.k6lab.server.load.LoadForm();
        var o = new ru.k6lab.server.load.LoadForm.OpForm();
        o.setName("login");
        o.setExecFn("login");
        o.setRatePerSec(2.0);
        f.getOps().add(o);
        f.setHoldMinutes(1);
        f.setRampSec(0);
        load.save(id, f);
        var g = new ru.k6lab.server.generator.Generator();
        g.setName("flags-ui-" + java.util.UUID.randomUUID().toString().substring(0, 6));
        g.setHost("127.0.0.1");
        g.setInstallDir("/opt/k6-agent");
        g.setServerUrl("ws://127.0.0.1:1/agent/ws");
        g.setStatus(ru.k6lab.server.generator.Generator.Status.INSTALLING);
        g = generatorRepo.save(g);

        // форма запуска при наличии серверов: выбор HTTP-отладки и флаги с пояснениями
        mvc.perform(get("/scripts/" + id + "/run")).andExpect(status().isOk())
                .andExpect(content().string(containsString("Параметры k6 и отладка")))
                .andExpect(content().string(containsString("--http-debug=full")))
                .andExpect(content().string(containsString("--no-thresholds")))
                .andExpect(content().string(containsString("меняет нагрузку")));

        var run = runService.create(new ru.k6lab.server.run.RunService.CreateRequest(id,
                java.util.List.of(new ru.k6lab.server.run.RunService.GeneratorSel(g.getId(), 1)),
                ru.k6lab.server.run.Run.FailurePolicy.CONTINUE, 90, java.util.List.of(ru.k6lab.server.run.K6Flag.HTTP_DEBUG_FULL)));
        mvc.perform(get("/runs/" + run.getId())).andExpect(status().isOk())
                .andExpect(content().string(containsString("флаги k6")))
                .andExpect(content().string(containsString("HTTP_DEBUG_FULL")))
                // кнопок Pause/Resume больше нет
                .andExpect(content().string(org.hamcrest.Matchers.not(containsString("Resume"))));
        mvc.perform(get("/runs/" + run.getId() + "/results")).andExpect(status().isOk());
    }

    @Test
    void k6ConfigCanBeEditedAndOverridesSurviveLoadChanges() throws Exception {
        var om = new com.fasterxml.jackson.databind.ObjectMapper();
        long id = script("flow-k6cfg").getScriptId();
        java.util.function.IntConsumer saveLoad = rate -> {
            try {
                mvc.perform(post("/scripts/" + id + "/load").with(csrf())
                                .param("ops[0].execFn", "login").param("ops[0].name", "login").param("ops[0].ratePerSec", String.valueOf(rate))
                                .param("ops[0].slaPercentile", "95").param("ops[0].slaThresholdMs", "800").param("ops[0].maxErrorPct", "1")
                                .param("ops[0].expectedDurationSec", "0.3").param("holdMinutes", "1").param("rampSec", "0").param("levelPct", "100"))
                        .andExpect(redirectedUrl("/scripts/" + id));
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        };
        saveLoad.accept(5);
        String base = mvc.perform(get("/scripts/" + id + "/config.json")).andReturn().getResponse().getContentAsString();
        var node = (com.fasterxml.jackson.databind.node.ObjectNode) om.readTree(base);
        assertThat(node.path("scenarios").path("login").path("maxVUs").asInt()).isNotEqualTo(77);
        ((com.fasterxml.jackson.databind.node.ObjectNode) node.path("scenarios").path("login")).put("maxVUs", 77);
        node.put("noConnectionReuse", true);

        mvc.perform(post("/scripts/" + id + "/k6-config").with(csrf()).param("config", node.toString()))
                .andExpect(redirectedUrl("/scripts/" + id + "/load#t-config"));
        assertThat(scripts.get(id).getK6Overrides()).containsKeys("scenarios", "noConnectionReuse");
        var merged = om.readTree(mvc.perform(get("/scripts/" + id + "/config.json")).andReturn().getResponse().getContentAsString());
        assertThat(merged.path("scenarios").path("login").path("maxVUs").asInt()).isEqualTo(77);
        assertThat(merged.path("noConnectionReuse").asBoolean()).isTrue();
        mvc.perform(get("/scripts/" + id + "/load")).andExpect(content().string(containsString("Есть ручные правки конфига")));

        // правки переживают изменение нагрузки: интенсивность пересчитана, maxVUs остался ручным
        saveLoad.accept(9);
        var after = om.readTree(mvc.perform(get("/scripts/" + id + "/config.json")).andReturn().getResponse().getContentAsString());
        assertThat(after.path("scenarios").path("login").path("maxVUs").asInt()).isEqualTo(77);
        assertThat(after.path("scenarios").path("login").path("stages").get(1).path("target").asInt()).isEqualTo(9);

        // сценарии добавлять нельзя, без executor - тоже
        var added = (com.fasterxml.jackson.databind.node.ObjectNode) om.readTree(base);
        ((com.fasterxml.jackson.databind.node.ObjectNode) added.path("scenarios")).putObject("extra").put("executor", "constant-vus");
        mvc.perform(post("/scripts/" + id + "/k6-config").with(csrf()).param("config", added.toString()))
                .andExpect(status().isOk()).andExpect(content().string(containsString("Сценарии нельзя добавлять")));
        mvc.perform(post("/scripts/" + id + "/k6-config").with(csrf()).param("config", "{\"scenarios\":1}"))
                .andExpect(status().isOk()).andExpect(content().string(containsString("полем scenarios")));
        mvc.perform(post("/scripts/" + id + "/k6-config").with(csrf()).param("config", "{"))
                .andExpect(status().isOk()).andExpect(content().string(containsString("Некорректный JSON")));
        // k6 сам отклоняет недопустимый конфиг (maxVUs меньше preAllocatedVUs)
        if (k6tool.locate().isPresent()) {
            var bad = (com.fasterxml.jackson.databind.node.ObjectNode) om.readTree(base);
            var sc = (com.fasterxml.jackson.databind.node.ObjectNode) bad.path("scenarios").path("login");
            sc.put("preAllocatedVUs", 50).put("maxVUs", 1);
            mvc.perform(post("/scripts/" + id + "/k6-config").with(csrf()).param("config", bad.toString()))
                    .andExpect(status().isOk()).andExpect(content().string(containsString("k6 не принял конфиг")));
        }

        // сброс возвращает расчётный конфиг
        mvc.perform(post("/scripts/" + id + "/k6-config/reset").with(csrf())).andExpect(redirectedUrl("/scripts/" + id + "/load#t-config"));
        assertThat(scripts.get(id).getK6Overrides()).isEmpty();
        var reset = om.readTree(mvc.perform(get("/scripts/" + id + "/config.json")).andReturn().getResponse().getContentAsString());
        assertThat(reset.path("scenarios").path("login").path("maxVUs").asInt()).isNotEqualTo(77);
        assertThat(reset.has("noConnectionReuse")).isFalse();
    }

    @Test
    void scriptWithOnlyDefaultFunctionIsDetectedAndLoadCanBeSet() throws Exception {
        var v = scripts.createFromUpload("flow-default", "", "d.js", new ByteArrayInputStream(
                "import http from 'k6/http';\nexport default function () { http.get('http://localhost/x'); }\n".getBytes(StandardCharsets.UTF_8)), "", null);
        long id = v.getScriptId();
        assertThat(v.operationNames()).containsExactly("default");
        mvc.perform(get("/scripts/" + id + "/load")).andExpect(content().string(containsString("default")))
                .andExpect(content().string(org.hamcrest.Matchers.not(containsString("не найдено экспортируемых функций"))));
        mvc.perform(post("/scripts/" + id + "/load").with(csrf())
                        .param("ops[0].execFn", "default").param("ops[0].name", "main").param("ops[0].ratePerSec", "4")
                        .param("ops[0].slaPercentile", "95").param("ops[0].slaThresholdMs", "800").param("ops[0].maxErrorPct", "1")
                        .param("ops[0].expectedDurationSec", "0.3").param("holdMinutes", "1").param("rampSec", "0").param("levelPct", "100"))
                .andExpect(redirectedUrl("/scripts/" + id));
        // для default поле exec в конфиге k6 не пишется: k6 сам запускает основную функцию
        String cfg = mvc.perform(get("/scripts/" + id + "/config.json")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        var scenario = new com.fasterxml.jackson.databind.ObjectMapper().readTree(cfg).path("scenarios").path("main");
        assertThat(scenario.isMissingNode()).isFalse();
        assertThat(scenario.has("exec")).isFalse();
    }

    @Test
    void dashboardShowsOnlyFirstGeneratorsAndLinksToTheFullList() throws Exception {
        for (int i = 0; i < 9; i++) {
            var g = new ru.k6lab.server.generator.Generator();
            g.setName("dash-" + java.util.UUID.randomUUID().toString().substring(0, 6));
            g.setHost("127.0.0.1");
            g.setInstallDir("/opt/k6-agent");
            g.setServerUrl("ws://127.0.0.1:1/agent/ws");
            g.setStatus(ru.k6lab.server.generator.Generator.Status.INSTALLING);
            generatorRepo.save(g);
        }
        String html = mvc.perform(get("/")).andExpect(status().isOk())
                .andExpect(content().string(containsString("Показано 6 из")))
                .andReturn().getResponse().getContentAsString();
        // на главной ровно шесть строк генераторов
        int rows = html.split("list-group-item d-flex justify-content-between", -1).length - 1;
        assertThat(rows).isEqualTo(6);
        // раздел «Операции» доступен не только через редирект: ссылка есть и на странице генераторов, и в меню
        mvc.perform(get("/generators")).andExpect(content().string(containsString("href=\"/deployments\"")));
        mvc.perform(get("/deployments")).andExpect(status().isOk());
    }

    @Autowired
    ru.k6lab.server.settings.SettingsService settingsService;
    @Autowired
    ru.k6lab.server.run.RunOrchestrator orchestrator;
    @Autowired
    ru.k6lab.server.metrics.RunMonitor runMonitor;

    @Test
    void metricsPushIntervalIsConfigurableAndOnlyNeededHistogramsAreKept() throws Exception {
        try {
            // по умолчанию 10 с; допустимо 5..60
            settingsService.set(ru.k6lab.server.settings.SettingsService.PUSH_INTERVAL_SEC, "");
            assertThat(settingsService.pushIntervalSec()).isEqualTo(10);
            settingsService.set(ru.k6lab.server.settings.SettingsService.PUSH_INTERVAL_SEC, "2");
            assertThat(settingsService.pushIntervalSec()).isEqualTo(5);
            settingsService.set(ru.k6lab.server.settings.SettingsService.PUSH_INTERVAL_SEC, "999");
            assertThat(settingsService.pushIntervalSec()).isEqualTo(60);
            // окно живых метрик не короче трёх интервалов отправки: в нём должно быть несколько сэмплов
            assertThat(runMonitor.minWindowSec()).isEqualTo(180);
            settingsService.set(ru.k6lab.server.settings.SettingsService.PUSH_INTERVAL_SEC, "10");
            assertThat(runMonitor.minWindowSec()).isEqualTo(30);
        } finally {
            settingsService.set(ru.k6lab.server.settings.SettingsService.PUSH_INTERVAL_SEC, "");
        }
        // агенту отправляются только нужные гистограммы, а не все семь HTTP-гистограмм k6
        assertThat(orchestrator.keptHistograms()).containsExactlyInAnyOrder(
                "k6_op_duration_seconds", "k6_iteration_duration_seconds", "k6_http_req_duration_seconds");
        // страница настроек показывает поле интервала
        mvc.perform(get("/settings")).andExpect(status().isOk()).andExpect(content().string(containsString("Интервал отправки метрик")));
    }

    @Test
    void generatorCountIsStoredInScriptAndPrefilledOnRun() throws Exception {
        long id = script("flow-gencount").getScriptId();
        var g = new ru.k6lab.server.generator.Generator();
        g.setName("gc-" + java.util.UUID.randomUUID().toString().substring(0, 6));
        g.setHost("127.0.0.1");
        g.setInstallDir("/opt/k6-agent");
        g.setServerUrl("ws://127.0.0.1:1/agent/ws");
        g.setStatus(ru.k6lab.server.generator.Generator.Status.INSTALLING);
        generatorRepo.save(g);
        java.util.function.Function<String, org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder> loadPost = cnt ->
                post("/scripts/" + id + "/load").with(csrf())
                        .param("ops[0].execFn", "login").param("ops[0].name", "login").param("ops[0].ratePerSec", "5")
                        .param("ops[0].slaPercentile", "95").param("ops[0].slaThresholdMs", "800").param("ops[0].maxErrorPct", "1")
                        .param("ops[0].expectedDurationSec", "0.3").param("holdMinutes", "1").param("rampSec", "0").param("levelPct", "100")
                        .param("generatorCount", cnt);

        // по умолчанию один генератор
        mvc.perform(get("/scripts/" + id + "/run")).andExpect(content().string(containsString("name=\"count\"")));
        mvc.perform(loadPost.apply("7")).andExpect(redirectedUrl("/scripts/" + id));
        assertThat(scripts.get(id).getGeneratorCount()).isEqualTo(7);
        // страница запуска подставляет число из скрипта, страница нагрузки - тоже
        String run = mvc.perform(get("/scripts/" + id + "/run")).andReturn().getResponse().getContentAsString();
        assertThat(run).containsPattern("name=\"count\"[^>]*|value=\"7\"[^>]*name=\"count\"|name=\"count\" id=\"gen-count\"");
        assertThat(run).contains("value=\"7\"");
        mvc.perform(get("/scripts/" + id + "/load")).andExpect(content().string(containsString("из 7")));
        // нереальные значения отклоняются понятным сообщением
        mvc.perform(loadPost.apply("0")).andExpect(status().isOk()).andExpect(content().string(containsString("от 1 до 200")));
        mvc.perform(loadPost.apply("500")).andExpect(status().isOk()).andExpect(content().string(containsString("от 1 до 200")));
        assertThat(scripts.get(id).getGeneratorCount()).isEqualTo(7);
        // JSON-конфиг нагрузки тоже содержит число генераторов
        mvc.perform(get("/scripts/" + id + "/load")).andExpect(content().string(containsString("generatorCount")));
        mvc.perform(post("/scripts/" + id + "/load/json").with(csrf()).param("json",
                        "{\"operations\":[{\"execFn\":\"login\",\"name\":\"login\",\"ratePerSec\":5,\"slaPercentile\":95,\"slaThresholdMs\":800,"
                                + "\"maxErrorPct\":1,\"expectedDurationSec\":0.3}],\"holdMinutes\":1,\"rampSec\":0,\"levelPct\":100,\"generatorCount\":4}"))
                .andExpect(redirectedUrl("/scripts/" + id));
        assertThat(scripts.get(id).getGeneratorCount()).isEqualTo(4);
    }

    @Test
    void rollbackToVersionWorksThroughTheWebUi() throws Exception {
        var v1 = script("flow-rollback");
        long id = v1.getScriptId();
        var v2 = scripts.saveEdit(id, v1.getId(), "s.js", "export function login() {}\nexport default function () {}\n", "правка");
        assertThat(v2.getNumber()).isEqualTo(2);
        // страница версии показывает кнопку отката
        mvc.perform(get("/scripts/" + id + "/versions/" + v1.getId())).andExpect(status().isOk())
                .andExpect(content().string(containsString("/rollback")));
        // откат: новая версия 3 с содержимым первой, переход на её страницу
        String redirect = mvc.perform(post("/scripts/" + id + "/versions/" + v1.getId() + "/rollback").with(csrf()))
                .andExpect(status().is3xxRedirection()).andReturn().getResponse().getRedirectedUrl();
        assertThat(redirect).startsWith("/scripts/" + id + "/versions/");
        var latest = scripts.latest(id).orElseThrow();
        assertThat(latest.getNumber()).isEqualTo(3);
        assertThat(latest.getSource()).isEqualTo(ru.k6lab.server.script.ScriptVersion.Source.ROLLBACK);
        assertThat(latest.getSha256()).isEqualTo(v1.getSha256());
        mvc.perform(get(redirect)).andExpect(status().isOk());
        mvc.perform(get("/scripts/" + id)).andExpect(status().isOk());
        mvc.perform(get("/scripts/" + id + "/load")).andExpect(status().isOk());
        mvc.perform(get("/scripts/" + id + "/versions/" + latest.getId() + "/edit")).andExpect(status().isOk());
        // откат не трогает прежние версии: у каждой своё содержимое
        java.util.function.Function<ru.k6lab.server.script.ScriptVersion, String> content = v -> {
            try {
                return mvc.perform(get("/scripts/" + id + "/versions/" + v.getId() + "/file").param("path", "s.js"))
                        .andReturn().getResponse().getContentAsString();
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        };
        assertThat(content.apply(v1)).contains("http.get('http://localhost/a')");
        assertThat(content.apply(v2)).doesNotContain("http.get").contains("export function login() {}");
        assertThat(content.apply(latest)).contains("http.get('http://localhost/a')");
        assertThat(scripts.version(id, v1.getId()).getSha256()).isNotEqualTo(scripts.version(id, v2.getId()).getSha256());
        assertThat(scripts.versionsOf(id)).extracting(ru.k6lab.server.script.ScriptVersion::getNumber).containsExactlyInAnyOrder(1, 2, 3);
    }

    @Test
    void sandboxDetectsLoadFunctionsLikeTheUploadAnalyzer() throws Exception {
        String src = "export function login() {}\\nexport const pay = () => {};\\nexport async function refund() {}\\n"
                + "export function setup() {}\\nexport default function () {}\\n";
        mvc.perform(post("/scripts/sandbox/functions").with(csrf()).contentType("application/json")
                        .content("{\"source\":\"" + src + "\"}"))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.functions[0]").value("login"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.functions.length()").value(4))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.functions[3]").value("default"));
        mvc.perform(post("/scripts/sandbox/functions").with(csrf()).contentType("application/json").content("{\"source\":\"\"}"))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.functions.length()").value(0));
        // страница отладки: выбор функции - список, а не ручной ввод
        mvc.perform(get("/scripts/sandbox")).andExpect(content().string(containsString("id=\"sb-exec\"")))
                .andExpect(content().string(org.hamcrest.Matchers.not(containsString("datalist"))));
    }

    @Test
    void functionRemovedFromNewVersionDisappearsFromLoadAndRun() throws Exception {
        var om = new com.fasterxml.jackson.databind.ObjectMapper();
        var v1 = script("flow-stale");
        long id = v1.getScriptId();
        // нагрузка на две функции: login и pay
        mvc.perform(post("/scripts/" + id + "/load").with(csrf())
                        .param("ops[0].execFn", "login").param("ops[0].name", "login").param("ops[0].ratePerSec", "5")
                        .param("ops[0].slaPercentile", "95").param("ops[0].slaThresholdMs", "800").param("ops[0].maxErrorPct", "1")
                        .param("ops[0].expectedDurationSec", "0.3")
                        .param("ops[1].execFn", "pay").param("ops[1].name", "pay").param("ops[1].ratePerSec", "2")
                        .param("ops[1].slaPercentile", "95").param("ops[1].slaThresholdMs", "800").param("ops[1].maxErrorPct", "1")
                        .param("ops[1].expectedDurationSec", "0.3")
                        .param("holdMinutes", "1").param("rampSec", "0").param("levelPct", "100"))
                .andExpect(redirectedUrl("/scripts/" + id));
        assertThat(load.activeOperations(id)).extracting(LoadOperation::getName).containsExactly("login", "pay");

        // новая версия без функции login
        var v2 = scripts.saveEdit(id, v1.getId(), "s.js",
                "import http from 'k6/http';\nexport function pay() { http.get('http://localhost/b'); }\nexport default function () {}\n", "убрали login");
        assertThat(v2.operationNames()).doesNotContain("login");

        // страница нагрузки: login больше нет в списке, есть пояснение; страница скрипта и план - только pay
        String page = mvc.perform(get("/scripts/" + id + "/load")).andExpect(status().isOk())
                .andExpect(content().string(containsString("нет функций операций")))
                .andReturn().getResponse().getContentAsString();
        assertThat(page).doesNotContain("name=\"ops[0].execFn\" value=\"login\"").doesNotContain("value=\"login\"");
        assertThat(load.activeOperations(id)).extracting(LoadOperation::getName).containsExactly("pay");
        assertThat(load.staleOperations(id)).extracting(LoadOperation::getName).containsExactly("login");
        var cfg = om.readTree(mvc.perform(get("/scripts/" + id + "/config.json")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
        assertThat(cfg.path("scenarios").has("login")).isFalse();
        assertThat(cfg.path("scenarios").has("pay")).isTrue();
        mvc.perform(get("/scripts/" + id + "/run")).andExpect(content().string(containsString("Не участвуют в запуске")));

        // откат к первой версии возвращает функцию и её нагрузку
        scripts.rollback(id, v1.getId());
        assertThat(load.activeOperations(id)).extracting(LoadOperation::getName).containsExactly("login", "pay");
        assertThat(load.staleOperations(id)).isEmpty();
    }

    @Autowired
    ru.k6lab.server.run.RunEventBroadcaster broadcaster;

    @Test
    void metricsHistoryKeepsAllPointsFromTestStart() throws Exception {
        long runId = 910_000L + (System.nanoTime() % 1000);
        mvc.perform(get("/runs/" + runId + "/metrics/history")).andExpect(status().isOk()).andExpect(content().string("[]"));
        for (int i = 1; i <= 3; i++) {
            broadcaster.publishMetrics(runId, "{\"ts\":" + i + ",\"step\":1,\"rows\":[]}");
        }
        // график строится с начала теста, а не только с момента открытия страницы
        var arr = new com.fasterxml.jackson.databind.ObjectMapper().readTree(
                mvc.perform(get("/runs/" + runId + "/metrics/history")).andExpect(status().isOk())
                        .andReturn().getResponse().getContentAsString());
        assertThat(arr).hasSize(3);
        assertThat(arr.get(0).path("ts").asLong()).isEqualTo(1);
        assertThat(arr.get(2).path("ts").asLong()).isEqualTo(3);
        assertThat(broadcaster.metricsHistory(runId)).hasSize(3);
        // страница запуска знает адрес истории
        assertThat(mvc.perform(get("/")).andReturn().getResponse().getStatus()).isEqualTo(200);
    }

    @Autowired
    ru.k6lab.server.web.DashboardController dashboardController;

    @Test
    void dashboardListsManyRunningScriptsCompactlyAndHasDebugSection() throws Exception {
        var g = new ru.k6lab.server.generator.Generator();
        g.setName("many-" + java.util.UUID.randomUUID().toString().substring(0, 6));
        g.setHost("127.0.0.1");
        g.setInstallDir("/opt/k6-agent");
        g.setServerUrl("ws://127.0.0.1:1/agent/ws");
        g.setStatus(ru.k6lab.server.generator.Generator.Status.INSTALLING);
        g = generatorRepo.save(g);
        var f = new ru.k6lab.server.load.LoadForm();
        var o = new ru.k6lab.server.load.LoadForm.OpForm();
        o.setName("login");
        o.setExecFn("login");
        o.setRatePerSec(1.0);
        f.getOps().add(o);
        f.setHoldMinutes(1);
        f.setRampSec(0);
        int before = runService.activeRuns().size();
        for (int i = 0; i < 12; i++) {
            long sid = script("flow-many-" + i).getScriptId();
            load.save(sid, f);
            runService.create(new ru.k6lab.server.run.RunService.CreateRequest(sid,
                    java.util.List.of(new ru.k6lab.server.run.RunService.GeneratorSel(g.getId(), 1)),
                    ru.k6lab.server.run.Run.FailurePolicy.CONTINUE, 90));
        }
        dashboardController.invalidateRunning();
        String html = mvc.perform(get("/")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        // одна компактная таблица, строка на запуск; никаких больших карточек с чипами генераторов
        assertThat(html).contains("run-table").doesNotContain("run-card");
        int rows = html.split("stretched-link", -1).length - 1;
        assertThat(rows).isGreaterThanOrEqualTo(12);
        assertThat(html).contains("ждёт свободные генераторы");
        // раздел «Отладка» среди разделов
        assertThat(html).contains("href=\"/scripts/sandbox\"").contains("запуск скрипта с логом");
        // повторный запрос укладывается в кэш и тоже отдаёт страницу
        mvc.perform(get("/dashboard/running")).andExpect(status().isOk()).andExpect(content().string(containsString("run-table")));
    }

    @Test
    void sandboxPageValidatesFilesAndSavesScript() throws Exception {
        mvc.perform(get("/scripts/sandbox")).andExpect(status().isOk()).andExpect(content().string(containsString("Отладка скрипта")));
        mvc.perform(get("/scripts/new")).andExpect(content().string(containsString("/scripts/sandbox")));
        // небезопасные пути и неизвестные флаги отклоняются
        mvc.perform(post("/scripts/sandbox/save").with(csrf()).contentType("application/json")
                        .content("{\"name\":\"sb-bad\",\"entrypoint\":\"a.js\",\"files\":[{\"path\":\"../a.js\",\"text\":\"x\"}]}"))
                .andExpect(status().isBadRequest()).andExpect(content().string(containsString("Недопустимый путь")));
        // сохранение: файлы и данные попадают в версию, основной файл выбран
        String body = "{\"name\":\"sb-saved\",\"entrypoint\":\"main.js\",\"files\":["
                + "{\"path\":\"main.js\",\"text\":\"export default function () {}\\nexport function pay() {}\\n\"},"
                + "{\"path\":\"certs/client.pem\",\"base64\":\"LS0tLS1CRUdJTg==\"}]}";
        String resp = mvc.perform(post("/scripts/sandbox/save").with(csrf()).contentType("application/json").content(body))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(resp).contains("/scripts/");
        var saved = scripts.list().stream().filter(s -> s.getName().equals("sb-saved")).findFirst().orElseThrow();
        var v = scripts.latest(saved.getId()).orElseThrow();
        assertThat(v.getEntrypoint()).isEqualTo("main.js");
        assertThat(v.getFiles()).extracting(f -> f.path()).contains("main.js", "certs/client.pem");
    }

    @Test
    void sandboxCanDebugExistingScriptAndSaveNewVersion() throws Exception {
        var v1 = script("flow-sb-existing");
        long id = v1.getScriptId();
        // меню и выбор скрипта на странице отладки
        mvc.perform(get("/")).andExpect(content().string(containsString("/scripts/sandbox")));
        mvc.perform(get("/scripts/sandbox")).andExpect(content().string(containsString("flow-sb-existing")));
        mvc.perform(get("/scripts/sandbox?script=" + id)).andExpect(status().isOk());
        mvc.perform(get("/scripts/" + id)).andExpect(content().string(containsString("/scripts/sandbox?script=" + id)));
        // файлы последней версии для редактора
        String json = mvc.perform(get("/scripts/sandbox/script/" + id)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        var node = new com.fasterxml.jackson.databind.ObjectMapper().readTree(json);
        assertThat(node.path("name").asText()).isEqualTo("flow-sb-existing");
        assertThat(node.path("version").asInt()).isEqualTo(1);
        assertThat(node.path("entrypoint").asText()).isEqualTo("s.js");
        assertThat(node.path("files").get(0).path("text").asText()).contains("export function login");
        mvc.perform(get("/scripts/sandbox/script/999999")).andExpect(status().isBadRequest());
        // сохранение = новая версия того же скрипта
        String body = "{\"scriptId\":" + id + ",\"name\":\"flow-sb-existing\",\"entrypoint\":\"s.js\",\"comment\":\"из отладчика\",\"files\":["
                + "{\"path\":\"s.js\",\"text\":\"export function login() {}\\nexport default function () {}\\n\"}]}";
        String resp = mvc.perform(post("/scripts/sandbox/save").with(csrf()).contentType("application/json").content(body))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(resp).contains("/scripts/" + id + "/versions/");
        var latest = scripts.latest(id).orElseThrow();
        assertThat(latest.getNumber()).isEqualTo(2);
        assertThat(scripts.list().stream().filter(x -> x.getName().equals("flow-sb-existing")).count()).isEqualTo(1);
    }

    @Test
    void sandboxRunsScriptWithRealK6AndStreamsLog() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(k6tool.locate().isPresent(), "k6 не найден");
        var req = new ru.k6lab.server.script.SandboxService.RunRequest(java.util.List.of(
                new ru.k6lab.server.script.SandboxService.FileIn("main.js",
                        "export default function () { console.log('hello ' + __ENV.WHO); }\n"
                                + "export function other() { console.log('other-fn'); }\n", null),
                new ru.k6lab.server.script.SandboxService.FileIn("data.txt", "x", null)),
                "main.js", "other", 1, 2, 60, java.util.Map.of("WHO", "sandbox"), java.util.List.of("VERBOSE"));
        String id = sandbox.start(req);
        ru.k6lab.server.script.SandboxService.Chunk c;
        long end = System.currentTimeMillis() + 60_000;
        do {
            Thread.sleep(300);
            c = sandbox.read(id, 0);
        } while (!c.done() && System.currentTimeMillis() < end);
        String all = String.join("\n", c.lines().stream().map(l -> l.text()).toList());
        assertThat(c.done()).isTrue();
        assertThat(c.exitCode()).as(all).isEqualTo(0);
        assertThat(all).contains("other-fn").doesNotContain("hello");
        // default в выпадающем списке = основная функция: exec в конфиге не задаётся, запуск проходит
        String id2 = sandbox.start(new ru.k6lab.server.script.SandboxService.RunRequest(req.files(), "main.js", "default", 1, 1, 60,
                java.util.Map.of("WHO", "def"), java.util.List.of()));
        ru.k6lab.server.script.SandboxService.Chunk c2;
        long end2 = System.currentTimeMillis() + 60_000;
        do {
            Thread.sleep(300);
            c2 = sandbox.read(id2, 0);
        } while (!c2.done() && System.currentTimeMillis() < end2);
        String all2 = String.join("\n", c2.lines().stream().map(l -> l.text()).toList());
        assertThat(c2.exitCode()).as(all2).isEqualTo(0);
        assertThat(all2).contains("hello def");
        assertThatThrownBy(() -> sandbox.start(new ru.k6lab.server.script.SandboxService.RunRequest(java.util.List.of(
                new ru.k6lab.server.script.SandboxService.FileIn("a.js", "x", null)), "missing.js", null, 1, 1, 10, null, null)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void k6FlagsCatalogIsValidatedAndShownOnRunForm() throws Exception {
        assertThat(ru.k6lab.server.run.K6Flag.parse(java.util.List.of("HTTP_DEBUG", "HTTP_DEBUG_FULL", "NO_SETUP")))
                .containsExactly(ru.k6lab.server.run.K6Flag.HTTP_DEBUG_FULL, ru.k6lab.server.run.K6Flag.NO_SETUP);
        assertThat(ru.k6lab.server.run.K6Flag.args(java.util.List.of("HTTP_DEBUG_FULL", "UNKNOWN")))
                .containsExactly("--http-debug=full");
        assertThatThrownBy(() -> ru.k6lab.server.run.K6Flag.parse(java.util.List.of("--evil")))
                .isInstanceOf(IllegalArgumentException.class);
        long id = script("flow-flags").getScriptId();
        mvc.perform(get("/scripts/" + id + "/run")).andExpect(status().isOk());
        // неизвестный флаг отклоняется с сообщением, а не игнорируется
        mvc.perform(post("/scripts/" + id + "/run").with(csrf()).param("flag", "EVIL"))
                .andExpect(status().isOk()).andExpect(content().string(containsString("Неизвестный флаг k6")));
    }

    @Test
    void loadCanBeEditedAsJsonConfig() throws Exception {
        long id = script("flow-json").getScriptId();
        String json = "{\"operations\":[{\"execFn\":\"login\",\"name\":\"login\",\"ratePerSec\":7,\"slaPercentile\":95,"
                + "\"slaThresholdMs\":700,\"maxErrorPct\":1,\"expectedDurationSec\":0.3,\"failureSource\":\"HTTP\"}],"
                + "\"levelPct\":100,\"rampSec\":10,\"holdMinutes\":3,\"env\":{\"BASE_URL\":\"http://x\"},\"dataFiles\":[]}";
        mvc.perform(post("/scripts/" + id + "/load/json").with(csrf()).param("json", json))
                .andExpect(redirectedUrl("/scripts/" + id));
        assertThat(load.operations(id)).extracting(LoadOperation::getName).containsExactly("login");
        assertThat(scripts.get(id).getHoldSec()).isEqualTo(180);
        // сохранённое видно в конфиге и скачивается обратно
        mvc.perform(get("/scripts/" + id + "/load")).andExpect(content().string(containsString("BASE_URL")));
        mvc.perform(get("/scripts/" + id + "/load.json")).andExpect(status().isOk())
                .andExpect(content().string(containsString("\"ratePerSec\" : 7.0")));
        // ошибки - понятное сообщение, текст пользователя сохраняется
        mvc.perform(post("/scripts/" + id + "/load/json").with(csrf()).param("json", "{\"nope\":1}"))
                .andExpect(status().isOk()).andExpect(content().string(containsString("Некорректный конфиг")));
        mvc.perform(post("/scripts/" + id + "/load/json").with(csrf())
                        .param("json", "{\"operations\":[{\"execFn\":\"login\",\"name\":\"login\",\"ratePerSec\":\"abc\"}]}"))
                .andExpect(status().isOk()).andExpect(content().string(containsString("operations[0].ratePerSec")))
                .andExpect(content().string(containsString("ожидается число")));
        mvc.perform(post("/scripts/" + id + "/load/json").with(csrf()).param("json", "["))
                .andExpect(status().isOk()).andExpect(content().string(containsString("Некорректный конфиг")));
    }

    @Test
    void loadWithoutIntensityIsRejectedWithMessage() throws Exception {
        long id = script("flow-empty").getScriptId();
        mvc.perform(post("/scripts/" + id + "/load").with(csrf())
                        .param("ops[0].execFn", "login").param("ops[0].name", "login").param("ops[0].ratePerSec", "")
                        .param("ops[0].slaPercentile", "95").param("holdMinutes", "1").param("levelPct", "100"))
                .andExpect(status().isOk()).andExpect(content().string(containsString("Укажите интенсивность")));
        // некорректное имя операции
        mvc.perform(post("/scripts/" + id + "/load").with(csrf())
                        .param("ops[0].execFn", "login").param("ops[0].name", "плохое имя").param("ops[0].ratePerSec", "1")
                        .param("ops[0].slaPercentile", "95").param("holdMinutes", "1").param("levelPct", "100"))
                .andExpect(status().isOk()).andExpect(content().string(containsString("латиница")));
    }

    @Test
    void dashboardShowsRunningScriptsGeneratorsAndSections() throws Exception {
        mvc.perform(get("/")).andExpect(status().isOk())
                .andExpect(content().string(containsString("Запущенные скрипты")))
                .andExpect(content().string(containsString("Генераторы")))
                .andExpect(content().string(containsString("Последние запуски")))
                .andExpect(content().string(containsString("Разделы")));
        // кнопка «обновить агент на всех»: на странице есть, при отсутствии устаревших - понятное сообщение
        mvc.perform(get("/generators")).andExpect(content().string(containsString("Обновить агент на всех")));
        mvc.perform(post("/generators/update-all").with(csrf())).andExpect(status().is3xxRedirection());
        mvc.perform(get("/dashboard/running")).andExpect(status().isOk()).andExpect(content().string(containsString("running-box")));
    }

    // одноразовый тестовый ключ, нигде не используется
    private static final String TEST_KEY = "-----BEGIN OPENSSH PRIVATE KEY-----\nb3BlbnNzaC1rZXktdjEAAAAABG5vbmUAAAAEbm9uZQAAAAAAAAABAAAAMwAAAAtzc2gtZW\nQyNTUxOQAAACDHlixhbKTVF9kSvKWoFZE1njyuM2VkycG1wy1nFlJb0AAAAJgxEpc4MRKX\nOAAAAAtzc2gtZWQyNTUxOQAAACDHlixhbKTVF9kSvKWoFZE1njyuM2VkycG1wy1nFlJb0A\nAAAEDfYnPIOnWqlFIxSNTTnvzBfEygVdgw/h+rehqgNrgkRseWLGFspNUX2RK8pagVkTWe\nPK4zZWTJwbXDLWcWUlvQAAAAFWFkbWluQERFU0tUT1AtNkRNSFZKTQ==\n-----END OPENSSH PRIVATE KEY-----\n";

    @Test
    void generatorFormAcceptsSshKeyAsWellAsPassword() throws Exception {
        mvc.perform(post("/generators/add").with(csrf()).param("host", "127.0.0.9").param("port", "1").param("username", "deploy")
                        .param("authType", "KEY").param("privateKey", TEST_KEY).param("serverUrl", "ws://127.0.0.1:47331/agent/ws"))
                .andExpect(redirectedUrlPattern("/deployments/*"));
        // вход по ключу без пароля sudo: агент всё равно ставится системным сервисом systemd, а не в пользовательском режиме с cron
        var created = generatorRepo.findAll().stream().filter(x -> "127.0.0.9".equals(x.getHost())).findFirst().orElseThrow();
        assertThat(created.getInstallMode()).isEqualTo(ru.k6lab.server.generator.Generator.InstallMode.SYSTEM);
        // ключ не указан - понятная ошибка на той же странице
        mvc.perform(post("/generators/add").with(csrf()).param("host", "127.0.0.1").param("username", "root")
                        .param("authType", "KEY").param("serverUrl", "ws://127.0.0.1:47331/agent/ws"))
                .andExpect(status().isOk()).andExpect(content().string(containsString("Вставьте приватный ключ")));
        mvc.perform(post("/generators/add").with(csrf()).param("host", "127.0.0.1").param("username", "root")
                        .param("authType", "PASSWORD").param("serverUrl", "ws://127.0.0.1:47331/agent/ws"))
                .andExpect(status().isOk()).andExpect(content().string(containsString("Введите пароль")));
        mvc.perform(get("/generators/add")).andExpect(content().string(containsString("Ключ SSH"))).andExpect(content().string(containsString("privateKey")));
    }

    @Test
    void generatorFormStartsInstallOperation() throws Exception {
        // хост недоступен: установка стартует и завершится ошибкой, но пользователь попадает на страницу операции
        mvc.perform(post("/generators/add").with(csrf()).param("host", "127.0.0.1").param("port", "1").param("username", "root")
                        .param("password", "x").param("serverUrl", "ws://127.0.0.1:47331/agent/ws"))
                .andExpect(redirectedUrlPattern("/deployments/*"));
    }
}
