package ru.k6lab.server;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import ru.k6lab.server.load.LoadForm;
import ru.k6lab.server.load.LoadProfileStore;
import ru.k6lab.server.load.LoadService;
import ru.k6lab.server.script.Script;
import ru.k6lab.server.script.ScriptService;
import ru.k6lab.server.script.ScriptVersion;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Профили нагрузки: сохранение текущей формы с переменными, обновление по имени, открытие без изменения нагрузки скрипта, удаление. */
@WithMockUser(roles = "ADMIN")
class LoadProfileIT extends AbstractIT {

    @Autowired
    ScriptService scripts;
    @Autowired
    LoadService load;
    @Autowired
    LoadProfileStore profiles;
    @Autowired
    MockMvc mvc;

    private static final String SCRIPT = """
            import http from 'k6/http';
            export function work() { http.get('http://localhost/a'); }
            export function other() { http.get('http://localhost/b'); }
            export default function () {}
            """;

    private Script script() throws Exception {
        String n = UUID.randomUUID().toString().substring(0, 8);
        ScriptVersion v = scripts.createFromUpload("prof-" + n, "", "s.js", new ByteArrayInputStream(SCRIPT.getBytes(StandardCharsets.UTF_8)), "", null);
        LoadForm f = new LoadForm();
        LoadForm.OpForm o = new LoadForm.OpForm();
        o.setName("work");
        o.setExecFn("work");
        o.setRatePerSec(10.0);
        o.setExpectedDurationSec(0.1);
        f.getOps().add(o);
        f.setEnvText("BASE=saved");
        load.save(v.getScriptId(), f);
        return scripts.get(v.getScriptId());
    }

    private MockHttpServletRequestBuilder saveProfile(Script s, String name, int rate, String env) {
        return saveProfileJson(s, name, "{\"rate\":" + rate + ",\"timeUnit\":\"1s\",\"duration\":\"3m\",\"preAllocatedVUs\":2,\"maxVUs\":10}", env);
    }

    private MockHttpServletRequestBuilder saveProfileJson(Script s, String name, String scenarioJson, String env) {
        return post("/scripts/" + s.getId() + "/load/profile").with(csrf()).param("profileName", name)
                .param("ops[0].execFn", "work").param("ops[0].name", "work").param("ops[0].enabled", "true").param("ops[0].executor", "constant-arrival-rate")
                .param("ops[0].scenarioJson", scenarioJson)
                .param("ops[0].slaPercentile", "95").param("ops[0].slaThresholdMs", "800").param("ops[0].maxErrorPct", "1")
                .param("ops[0].expectedDurationSec", "0.3").param("holdMinutes", "1").param("levelPct", "100").param("envText", env);
    }

    @Test
    void profileKeepsTheWholeFormWithVariablesAndIsOpenedWithoutChangingTheScriptsLoad() throws Exception {
        Script s = script();
        mvc.perform(saveProfile(s, "Пик", 40, "BASE=peak\nREGION=eu")).andExpect(status().isOk())
                .andExpect(content().string(containsString("Профиль «Пик» сохранён")));
        var list = profiles.list(s.getId());
        assertThat(list).hasSize(1);
        assertThat(list.get(0).data()).contains("\"rate\" : 40").contains("\"BASE\" : \"peak\"").contains("\"REGION\" : \"eu\"");

        // та же форма с тем же названием обновляет профиль, а не создаёт второй
        mvc.perform(saveProfile(s, "Пик", 60, "BASE=peak2")).andExpect(status().isOk()).andExpect(content().string(containsString("обновлён")));
        assertThat(profiles.list(s.getId())).hasSize(1);
        mvc.perform(saveProfile(s, "Ночь", 5, "BASE=night")).andExpect(status().isOk());
        assertThat(profiles.list(s.getId())).extracting(LoadProfileStore.Profile::name).containsExactlyInAnyOrder("Пик", "Ночь");

        // открытие подставляет профиль в форму (и добавляет функции скрипта, которых в нём нет), но нагрузка скрипта остаётся прежней
        long peak = profiles.list(s.getId()).stream().filter(p -> p.name().equals("Пик")).findFirst().orElseThrow().id();
        mvc.perform(get("/scripts/" + s.getId() + "/load").param("profile", String.valueOf(peak))).andExpect(status().isOk())
                .andExpect(content().string(containsString("Открыт профиль «Пик»"))).andExpect(content().string(containsString("BASE=peak2")))
                .andExpect(content().string(containsString("data-from-profile")));
        assertThat(load.toForm(s.getId()).getEnvText()).contains("BASE=saved");
        assertThat(load.activeOperations(s.getId()).get(0).getRatePerSec()).isEqualTo(10.0);
    }

    @Test
    void openingAProfileWorksFromThePageShownAfterSavingOne() throws Exception {
        Script s = script();
        // после сохранения страница показывается по адресу POST: список профилей должен вести на форму нагрузки, а не на /load/profile
        mvc.perform(saveProfile(s, "Пик", 40, "A=1")).andExpect(status().isOk())
                .andExpect(content().string(containsString("data-open-url=\"/scripts/" + s.getId() + "/load\"")));
        long id = profiles.list(s.getId()).get(0).id();
        // старая ссылка вида /load/profile?profile=N больше не даёт 405
        mvc.perform(get("/scripts/" + s.getId() + "/load/profile").param("profile", String.valueOf(id)))
                .andExpect(status().is3xxRedirection()).andExpect(redirectedUrl("/scripts/" + s.getId() + "/load?profile=" + id));
    }

    @Test
    void invalidProfileIsRejectedAndOneCanBeDeleted() throws Exception {
        Script s = script();
        mvc.perform(saveProfile(s, "  ", 40, "A=1")).andExpect(status().isOk()).andExpect(content().string(containsString("Название профиля")));
        mvc.perform(saveProfileJson(s, "Плохой", "{не json", "A=1")).andExpect(status().isOk())
                .andExpect(content().string(containsString("некорректный JSON")));
        assertThat(profiles.list(s.getId())).isEmpty();

        mvc.perform(saveProfile(s, "Лишний", 40, "A=1")).andExpect(status().isOk());
        long id = profiles.list(s.getId()).get(0).id();
        mvc.perform(post("/scripts/" + s.getId() + "/load/profile/delete").with(csrf()).param("profile", String.valueOf(id)))
                .andExpect(status().is3xxRedirection()).andExpect(redirectedUrl("/scripts/" + s.getId() + "/load"));
        assertThat(profiles.list(s.getId())).isEmpty();
        mvc.perform(get("/scripts/" + s.getId() + "/load").param("profile", String.valueOf(id))).andExpect(status().isOk())
                .andExpect(content().string(containsString("Профиль не найден")));
    }
}
