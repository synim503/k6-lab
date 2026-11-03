package ru.k6lab.server;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import ru.k6lab.server.scenario.ScenarioService;
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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrlPattern;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Упрощённый путь: сервер -> скрипт -> «создать тест» -> «запустить». */
@WithMockUser(roles = "ADMIN")
class QuickFlowIT extends AbstractIT {

    @Autowired
    MockMvc mvc;
    @Autowired
    ScriptService scripts;
    @Autowired
    ScenarioService scenarios;

    @Test
    void createTestFromScriptAndOpenRunForm() throws Exception {
        String js = "import http from 'k6/http';\nexport function login() { http.get('http://localhost/a'); }\n"
                + "export function pay() { http.get('http://localhost/b'); }\nexport default function () {}\n";
        ScriptVersion v = scripts.createFromUpload("quick-script", "", "s.js", new ByteArrayInputStream(js.getBytes(StandardCharsets.UTF_8)), "", null);

        for (String p : new String[]{"/quick/tests", "/quick/test/new", "/quick/generator", "/generators"}) {
            mvc.perform(get(p)).andExpect(status().isOk());
        }
        mvc.perform(get("/quick/test/ops").param("scriptId", String.valueOf(v.getScriptId())))
                .andExpect(content().string(containsString("login"))).andExpect(content().string(containsString("pay")));

        String loc = mvc.perform(post("/quick/test").with(csrf()).param("name", "Быстрый тест").param("scriptId", String.valueOf(v.getScriptId()))
                        .param("minutes", "2").param("percent", "100")
                        .param("op", "login").param("rate", "5").param("sla", "800")
                        .param("op", "pay").param("rate", "").param("sla", "1000"))
                .andExpect(redirectedUrlPattern("/quick/test/*/run")).andReturn().getResponse().getRedirectedUrl();
        long id = Long.parseLong(loc.split("/")[3]);
        var s = scenarios.get(id);
        assertThat(s.getName()).isEqualTo("Быстрый тест");
        mvc.perform(get(loc)).andExpect(status().isOk()).andExpect(content().string(containsString("Запустить тест")));
        mvc.perform(get("/quick/tests")).andExpect(content().string(containsString("Быстрый тест")));

        // число генераторов без отмеченных хостов: свободных нет - понятная ошибка, а не 500
        mvc.perform(post(loc).with(csrf())).andExpect(status().isOk()).andExpect(content().string(containsString("Свободных генераторов")));
        mvc.perform(post(loc).with(csrf()).param("count", "0")).andExpect(status().isOk()).andExpect(content().string(containsString("не меньше 1")));
        // без нагрузки ни на одной операции
        mvc.perform(post("/quick/test").with(csrf()).param("name", "x").param("scriptId", String.valueOf(v.getScriptId()))
                        .param("op", "login").param("rate", "").param("sla", "1000"))
                .andExpect(status().isOk()).andExpect(content().string(containsString("Укажите интенсивность")));
    }

    @Test
    void dashboardShowsRunningTestsGeneratorsAndSections() throws Exception {
        mvc.perform(get("/")).andExpect(status().isOk())
                .andExpect(content().string(containsString("Запущенные тесты")))
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
    void quickGeneratorAcceptsSshKeyAsWellAsPassword() throws Exception {
        mvc.perform(post("/quick/generator").with(csrf()).param("host", "127.0.0.9").param("port", "1").param("username", "deploy")
                        .param("authType", "KEY").param("privateKey", TEST_KEY).param("serverUrl", "ws://127.0.0.1:47331/agent/ws"))
                .andExpect(redirectedUrlPattern("/deployments/*"));
        // ключ не указан - понятная ошибка на той же странице
        mvc.perform(post("/quick/generator").with(csrf()).param("host", "127.0.0.1").param("username", "root")
                        .param("authType", "KEY").param("serverUrl", "ws://127.0.0.1:47331/agent/ws"))
                .andExpect(status().isOk()).andExpect(content().string(containsString("Вставьте приватный ключ")));
        mvc.perform(post("/quick/generator").with(csrf()).param("host", "127.0.0.1").param("username", "root")
                        .param("authType", "PASSWORD").param("serverUrl", "ws://127.0.0.1:47331/agent/ws"))
                .andExpect(status().isOk()).andExpect(content().string(containsString("Введите пароль")));
        mvc.perform(get("/quick/generator")).andExpect(content().string(containsString("Ключ SSH"))).andExpect(content().string(containsString("privateKey")));
    }

    @Test
    void quickGeneratorStartsInstallOperation() throws Exception {
        // хост недоступен: установка стартует и завершится ошибкой, но пользователь попадает на страницу операции
        mvc.perform(post("/quick/generator").with(csrf()).param("host", "127.0.0.1").param("port", "1").param("username", "root")
                        .param("password", "x").param("serverUrl", "ws://127.0.0.1:47331/agent/ws"))
                .andExpect(redirectedUrlPattern("/deployments/*"));
    }
}
