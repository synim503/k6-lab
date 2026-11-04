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
