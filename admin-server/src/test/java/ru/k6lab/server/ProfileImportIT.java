package ru.k6lab.server;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import ru.k6lab.server.load.LoadForm;
import ru.k6lab.server.load.LoadProfileStore;
import ru.k6lab.server.load.LoadService;
import ru.k6lab.server.load.ProfileImportService;
import ru.k6lab.server.result.ChartTemplateStore;
import ru.k6lab.server.script.Script;
import ru.k6lab.server.script.ScriptService;
import ru.k6lab.server.script.ScriptVersion;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Импорт профилей нагрузки из JSON и свои графики отчёта с несколькими запросами. */
@WithMockUser(roles = "ADMIN")
class ProfileImportIT extends AbstractIT {

    @Autowired
    ScriptService scripts;
    @Autowired
    LoadService load;
    @Autowired
    LoadProfileStore profiles;
    @Autowired
    ProfileImportService importer;
    @Autowired
    ChartTemplateStore charts;
    @Autowired
    ru.k6lab.server.result.ChartVariableStore vars;
    @Autowired
    MockMvc mvc;

    private static final String SCRIPT = """
            import http from 'k6/http';
            export function work() { http.get('http://localhost/a'); }
            export default function () {}
            """;

    private Script script() throws Exception {
        String n = UUID.randomUUID().toString().substring(0, 8);
        ScriptVersion v = scripts.createFromUpload("imp-" + n, "", "s.js", new ByteArrayInputStream(SCRIPT.getBytes(StandardCharsets.UTF_8)), "", null);
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

    private static String op(String name, int rate) {
        return op(name, "work", rate);
    }

    private static String op(String name, String fn, int rate) {
        return "{\"name\": \"" + name + "\", \"execFn\": \"" + fn + "\", \"ratePerSec\": " + rate
                + ", \"expectedDurationSec\": 0.1, \"slaThresholdMs\": 500, \"slaPercentile\": 95, \"maxErrorPct\": 1}";
    }

    @Test
    void profilesAreImportedUpdatedExportedAndNothingIsSavedIfOneIsBad() throws Exception {
        Script s = script();
        String json = "{\"profiles\": [{\"name\": \"Максимум\", \"operations\": [" + op("work", 40) + "], \"env\": {\"BASE\": \"x\"}},"
                + "{\"name\": \"Базовая\", \"operations\": [" + op("work", 5) + "]}]}";
        var done = importer.importProfiles(s.getId(), json, "tester");
        assertThat(done).hasSize(2).allMatch(i -> !i.updated());
        assertThat(profiles.list(s.getId())).extracting(LoadProfileStore.Profile::name).containsExactlyInAnyOrder("Максимум", "Базовая");
        // профиль годится для запуска/планирования: собирается как при запуске, переменные и интенсивность из JSON
        long id = profiles.list(s.getId()).stream().filter(p -> p.name().equals("Максимум")).findFirst().orElseThrow().id();
        var built = load.fromProfile(s.getId(), id);
        assertThat(built.ops().get(0).getRatePerSec()).isEqualTo(40.0);
        assertThat(built.env()).containsEntry("BASE", "x");

        // повторный импорт с тем же именем обновляет
        var again = importer.importProfiles(s.getId(), "[{\"name\": \"Максимум\", \"operations\": [" + op("work", 60) + "]}]", "tester");
        assertThat(again.get(0).updated()).isTrue();
        assertThat(load.fromProfile(s.getId(), id).ops().get(0).getRatePerSec()).isEqualTo(60.0);

        // экспорт -> импорт на другом скрипте даёт те же профили
        String exported = importer.export(s.getId());
        Script other = script();
        assertThat(importer.importProfiles(other.getId(), exported, "tester")).hasSize(2);

        // одна ошибка - не сохраняется ни один профиль
        int before = profiles.list(s.getId()).size();
        String bad = "[{\"name\": \"Новый\", \"operations\": [" + op("work", 7) + "]}, {\"name\": \"Плохой\", \"operations\": [" + op("nope", "missingFn", 7) + "]}]";
        assertThatThrownBy(() -> importer.importProfiles(s.getId(), bad, "tester")).hasMessageContaining("Профиль №2")
                .hasMessageContaining("missingFn").hasMessageContaining("Доступные функции").hasMessageContaining("work");
        assertThat(profiles.list(s.getId())).hasSize(before);
        assertThatThrownBy(() -> importer.importProfiles(s.getId(), "[{\"operations\": []}]", "tester")).hasMessageContaining("name");
        assertThatThrownBy(() -> importer.importProfiles(s.getId(), "не json", "tester")).isInstanceOf(IllegalArgumentException.class);

        // общий импорт: скрипт задан полем script
        var global = importer.importProfiles(null, "[{\"script\": \"" + s.getName() + "\", \"name\": \"Общий\", \"operations\": [" + op("work", 9) + "]}]", "tester");
        assertThat(global.get(0).script()).isEqualTo(s.getName());
        assertThatThrownBy(() -> importer.importProfiles(null, "[{\"script\": \"нет такого\", \"name\": \"x\", \"operations\": [" + op("work", 9) + "]}]", "tester"))
                .hasMessageContaining("не найден");

        mvc.perform(get("/scripts/" + s.getId() + "/load")).andExpect(status().isOk()).andExpect(content().string(containsString("Импорт и экспорт профилей")));
        mvc.perform(get("/scripts/" + s.getId() + "/load/profiles.json")).andExpect(status().isOk()).andExpect(content().string(containsString("Максимум")));
        mvc.perform(post("/scripts/" + s.getId() + "/load/profiles/import").with(csrf()).param("json", "[{\"name\": \"Через форму\", \"operations\": [" + op("work", 3) + "]}]"))
                .andExpect(status().is3xxRedirection());
        assertThat(profiles.list(s.getId())).extracting(LoadProfileStore.Profile::name).contains("Через форму");
        mvc.perform(get("/schedule")).andExpect(status().isOk()).andExpect(content().string(containsString("Импорт профилей нагрузки")));
    }

    @Test
    void oneChartCanHaveSeveralQueriesAndCommasInPromqlSurvive() throws Exception {
        Script s = script();
        String q1 = "histogram_quantile(0.95, sum by (le) (rate(x_bucket{test_run_id=\"{run_id}\"}[{range}])))";
        mvc.perform(post("/scripts/" + s.getId() + "/charts").with(csrf()).param("name", "CPU и память").param("unit", "%")
                .param("promql", q1).param("legend", "p95")
                .param("promql", "avg(node_memory_used)").param("legend", "память"))
                .andExpect(status().is3xxRedirection());
        var t = charts.list(s.getId());
        assertThat(t).hasSize(1);
        assertThat(t.get(0).queries()).hasSize(2);
        assertThat(t.get(0).queries().get(0).promql()).isEqualTo(q1);   // запятые не порезаны
        assertThat(t.get(0).queries().get(0).legend()).isEqualTo("p95");
        assertThat(t.get(0).queries().get(1).legend()).isEqualTo("память");
        // один запрос с запятыми тоже не режется
        mvc.perform(post("/scripts/" + s.getId() + "/charts").with(csrf()).param("name", "Один").param("promql", q1)).andExpect(status().is3xxRedirection());
        assertThat(charts.list(s.getId()).get(1).queries()).hasSize(1);
        assertThat(charts.list(s.getId()).get(1).queries().get(0).promql()).isEqualTo(q1);
        mvc.perform(get("/scripts/" + s.getId() + "/charts")).andExpect(status().isOk()).andExpect(content().string(containsString("Ещё запрос")));

        // импорт дашборда Grafana: панель -> график с запросами и подписью; повторный импорт обновляет, а не дублирует
        String dash = "{\"title\": \"d\", \"panels\": [{\"type\": \"timeseries\", \"title\": \"Задержка SUT\", "
                + "\"fieldConfig\": {\"defaults\": {\"unit\": \"ms\"}}, \"targets\": [{\"expr\": \"avg(app_latency{run=\\\"$__all\\\",a=\\\"b\\\"})\", \"legendFormat\": \"avg\"}]}]}";
        for (int i = 0; i < 2; i++) {
            mvc.perform(post("/scripts/" + s.getId() + "/charts/import-grafana").with(csrf()).param("json", dash)).andExpect(status().is3xxRedirection());
        }
        var imported = charts.list(s.getId()).stream().filter(c -> c.name().equals("Задержка SUT")).toList();
        assertThat(imported).hasSize(1);
        assertThat(imported.get(0).unit()).isEqualTo("мс");
        assertThat(imported.get(0).queries().get(0).legend()).isEqualTo("avg");
        mvc.perform(post("/scripts/" + s.getId() + "/charts/import-grafana").with(csrf()).param("json", "{\"x\": 1}")).andExpect(status().is3xxRedirection());
        assertThat(charts.list(s.getId())).hasSize(3);

        // переменные дашборда стали переменными скрипта; правка руками не затирается повторным импортом
        String dash2 = "{\"title\": \"d\", \"templating\": {\"list\": [{\"name\": \"inst\", \"current\": {\"value\": \"app-1\"}}]}, \"panels\": ["
                + "{\"type\": \"timeseries\", \"title\": \"С переменной\", \"targets\": [{\"expr\": \"up{instance=\\\"$inst\\\"}\"}]}]}";
        mvc.perform(post("/scripts/" + s.getId() + "/charts/import-grafana").with(csrf()).param("json", dash2)).andExpect(status().is3xxRedirection());
        assertThat(vars.map(s.getId())).containsEntry("inst", "app-1");
        mvc.perform(post("/scripts/" + s.getId() + "/charts/variables").with(csrf()).param("name", "$inst").param("value", "app-2|app-3"))
                .andExpect(status().is3xxRedirection());
        mvc.perform(post("/scripts/" + s.getId() + "/charts/import-grafana").with(csrf()).param("json", dash2)).andExpect(status().is3xxRedirection());
        assertThat(vars.map(s.getId())).containsEntry("inst", "app-2|app-3");
        mvc.perform(get("/scripts/" + s.getId() + "/charts")).andExpect(status().isOk()).andExpect(content().string(containsString("app-2|app-3")));
        mvc.perform(post("/scripts/" + s.getId() + "/charts/variables").with(csrf()).param("name", "1bad").param("value", "x")).andExpect(status().is3xxRedirection());
        assertThat(vars.map(s.getId())).doesNotContainKey("1bad");
        long vid = vars.list(s.getId()).get(0).id();
        mvc.perform(post("/scripts/" + s.getId() + "/charts/variables/" + vid + "/delete").with(csrf())).andExpect(status().is3xxRedirection());
        assertThat(vars.map(s.getId())).isEmpty();

        // удаление графиков: выбранные и все
        var all = charts.list(s.getId());
        assertThat(all.size()).isGreaterThanOrEqualTo(3);
        mvc.perform(post("/scripts/" + s.getId() + "/charts/delete-selected").with(csrf())
                .param("ids", String.valueOf(all.get(0).id())).param("ids", String.valueOf(all.get(1).id()))).andExpect(status().is3xxRedirection());
        assertThat(charts.list(s.getId())).hasSize(all.size() - 2).extracting(ChartTemplateStore.Template::id)
                .doesNotContain(all.get(0).id(), all.get(1).id());
        mvc.perform(post("/scripts/" + s.getId() + "/charts/delete-selected").with(csrf())).andExpect(status().is3xxRedirection()); // ничего не выбрано
        assertThat(charts.list(s.getId())).hasSize(all.size() - 2);
        mvc.perform(get("/scripts/" + s.getId() + "/charts")).andExpect(status().isOk()).andExpect(content().string(containsString("Удалить выбранные")));
        mvc.perform(post("/scripts/" + s.getId() + "/charts/delete-all").with(csrf())).andExpect(status().is3xxRedirection());
        assertThat(charts.list(s.getId())).isEmpty();
    }
}
