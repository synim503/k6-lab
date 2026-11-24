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
    ru.k6lab.server.run.RunRepository runRepo;
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

    @Test
    void profileCanBeDuplicatedAndRenamed() throws Exception {
        Script s = script();
        mvc.perform(saveProfile(s, "Базовый", 30, "ENV=dev")).andExpect(status().isOk());
        long origId = profiles.list(s.getId()).get(0).id();

        mvc.perform(post("/scripts/" + s.getId() + "/load/profile/" + origId + "/duplicate").with(csrf()))
                .andExpect(status().is3xxRedirection());
        assertThat(profiles.list(s.getId())).extracting(LoadProfileStore.Profile::name)
                .containsExactlyInAnyOrder("Базовый", "Базовый (копия)");

        long copyId = profiles.list(s.getId()).stream().filter(p -> p.name().equals("Базовый (копия)")).findFirst().orElseThrow().id();
        mvc.perform(post("/scripts/" + s.getId() + "/load/profile/" + copyId + "/rename").with(csrf()).param("name", "Ночной прогон"))
                .andExpect(status().is3xxRedirection());
        assertThat(profiles.find(s.getId(), copyId)).get().extracting(LoadProfileStore.Profile::name).isEqualTo("Ночной прогон");
    }

    @Test
    void bottomSaveButtonTargetsOpenedProfileAndPreservesProfileVariablesWithBaseOverrides() throws Exception {
        Script s = script();
        mvc.perform(saveProfile(s, "Стрим-10", 30, "BATCH=10\nSTREAMS=5")).andExpect(status().isOk());
        long pid = profiles.list(s.getId()).get(0).id();

        // Включаем базовый профиль с общей переменной STAND=prod
        LoadForm baseForm = load.toForm(s.getId());
        baseForm.setBaseConfigSubmitted(true);
        baseForm.setBaseProfileActive(true);
        baseForm.setBaseOverrideEnv(true);
        baseForm.setEnvText("STAND=prod");
        load.save(s.getId(), baseForm);

        // Открываем профиль: нижняя основная кнопка сохранения должна вести на /load/profile,
        // а переменные должны объединять базовые (STAND=prod) и собственные переменные профиля (BATCH=10, STREAMS=5)
        String html = mvc.perform(get("/scripts/" + s.getId() + "/load").param("profile", String.valueOf(pid)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(html)
                .contains("id=\"ld-primary-save\"")
                .contains("formaction=\"/scripts/" + s.getId() + "/load/profile\"")
                .contains("Сохранить профиль")
                .contains("STAND=prod")
                .contains("BATCH=10")
                .contains("STREAMS=5");

        // Сохраняем обновлённую переменную профиля через POST /load/profile и проверяем, что она сохранилась
        mvc.perform(saveProfile(s, "Стрим-10", 35, "BATCH=20\nSTREAMS=8\nCUSTOM_FLAG=yes")).andExpect(status().isOk());
        var built = load.fromProfile(s.getId(), pid);
        assertThat(built.env())
                .containsEntry("STAND", "prod")
                .containsEntry("BATCH", "20")
                .containsEntry("STREAMS", "8")
                .containsEntry("CUSTOM_FLAG", "yes");
    }

    @Test
    void scriptDetailPaginatesRunsAndFiltersByStatus() throws Exception {
        Script s = script();
        long verId = scripts.latest(s.getId()).orElseThrow().getId();
        java.util.List<Long> createdIds = new java.util.ArrayList<>();
        for (int i = 1; i <= 12; i++) {
            ru.k6lab.server.run.Run r = new ru.k6lab.server.run.Run();
            r.setScriptId(s.getId());
            r.setScriptVersionId(verId);
            r.setScriptName(s.getName());
            r.setStatus(i % 4 == 0 ? ru.k6lab.core.runstate.RunStatus.FAILED : ru.k6lab.core.runstate.RunStatus.FINISHED);
            r.setQueuedAt(java.time.Instant.now().minusSeconds((13 - i) * 60L));
            r.setRequest(java.util.Map.of("loadProfileName", "Профиль #" + i));
            createdIds.add(runRepo.save(r).getId());
        }
        long oldestId = createdIds.get(0);
        long newestId = createdIds.get(createdIds.size() - 1);

        // Страница 0 (size=10): показаны 10 последних, есть пагинация «Страница 1 из 2»
        String page0 = mvc.perform(get("/scripts/" + s.getId()).param("page", "0").param("size", "10"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(page0)
                .contains("показано 1–10 из 12")
                .contains("Страница 1 из 2")
                .contains("#" + newestId)
                .contains("Профиль #12")
                .doesNotContain("/runs/" + oldestId + "\"");

        // Страница 1 (size=10): показаны 2 старых запуска (включая oldestId), но карточка «Последний запуск» всё ещё показывает newestId
        String page1 = mvc.perform(get("/scripts/" + s.getId()).param("page", "1").param("size", "10"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(page1)
                .contains("показано 11–12 из 12")
                .contains("Страница 2 из 2")
                .contains("#" + oldestId)
                .contains("#" + newestId);

        // Фильтр по статусу FAILED: 3 запуска из 12
        String failedOnly = mvc.perform(get("/scripts/" + s.getId()).param("status", "FAILED").param("size", "10"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(failedOnly)
                .contains("показано 1–3 из 3")
                .contains("Профиль #12");
    }
}
