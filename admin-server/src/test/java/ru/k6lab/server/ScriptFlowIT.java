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
        mvc.perform(get("/scripts/" + id + "/run")).andExpect(status().isOk()).andExpect(content().string(containsString("Количество генераторов")));
        mvc.perform(get("/scripts/" + id + "/config.json")).andExpect(status().isOk()).andExpect(content().string(containsString("login")));

        // свободных генераторов нет - понятная ошибка, а не 500
        mvc.perform(post("/scripts/" + id + "/run").with(csrf())).andExpect(status().isOk())
                .andExpect(content().string(containsString("Свободных генераторов")));
        mvc.perform(post("/scripts/" + id + "/run").with(csrf()).param("count", "0")).andExpect(status().isOk())
                .andExpect(content().string(containsString("не меньше 1")));
        // остановка без выполняющихся запусков безвредна
        mvc.perform(post("/scripts/" + id + "/stop").with(csrf())).andExpect(redirectedUrl("/scripts/" + id));
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
