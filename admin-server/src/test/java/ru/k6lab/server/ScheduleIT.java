package ru.k6lab.server;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import ru.k6lab.server.generator.Generator;
import ru.k6lab.server.generator.GeneratorRepository;
import ru.k6lab.server.load.LoadForm;
import ru.k6lab.server.load.LoadService;
import ru.k6lab.server.run.Run;
import ru.k6lab.server.run.RunService;
import ru.k6lab.server.schedule.ScheduledRunService;
import ru.k6lab.server.schedule.ScheduledRunStore;
import ru.k6lab.server.script.Script;
import ru.k6lab.server.script.ScriptService;
import ru.k6lab.server.script.ScriptVersion;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Запуск по расписанию: проверки при планировании, создание запуска в назначенное время с переменными и интенсивностью, пропуск и отмена. */
@WithMockUser(roles = "ADMIN")
class ScheduleIT extends AbstractIT {

    @Autowired
    ScriptService scripts;
    @Autowired
    LoadService load;
    @Autowired
    RunService runService;
    @Autowired
    GeneratorRepository generators;
    @Autowired
    ScheduledRunService schedule;
    @Autowired
    ScheduledRunStore store;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    MockMvc mvc;

    private static final String SCRIPT = """
            import http from 'k6/http';
            export function work() { http.get('http://localhost/a'); }
            export default function () {}
            """;

    private Script scriptWithLoad() throws Exception {
        String n = UUID.randomUUID().toString().substring(0, 8);
        ScriptVersion v = scripts.createFromUpload("sch-" + n, "", "s.js", new ByteArrayInputStream(SCRIPT.getBytes(StandardCharsets.UTF_8)), "", null);
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
        f.setEnvText("BASE=script");
        load.save(v.getScriptId(), f);
        return scripts.get(v.getScriptId());
    }

    private Generator generator() {
        Generator g = new Generator();
        g.setName("sch-" + UUID.randomUUID().toString().substring(0, 6));
        g.setHost("127.0.0.1");
        g.setInstallDir("/opt/k6-agent");
        g.setServerUrl("ws://127.0.0.1:1/agent/ws");
        g.setStatus(Generator.Status.ONLINE);
        return generators.save(g);
    }

    private void makeDue(long id, long secondsAgo) {
        jdbc.update("update scheduled_run set run_at = ? where id = ?", java.sql.Timestamp.from(Instant.now().minusSeconds(secondsAgo)), id);
    }

    @Test
    void dueScheduleCreatesARunWithItsOwnVariablesAndIntensity() throws Exception {
        Script s = scriptWithLoad();
        Generator g = generator();
        long id = schedule.schedule(s.getId(), Instant.now().plusSeconds(120), List.of(g.getId()), null, Map.of("BASE", "sched", "EXTRA", "1"),
                Map.of("work", 150.0), "tester");
        assertThat(schedule.pendingOf(s.getId())).extracting(ScheduledRunStore.Item::id).containsExactly(id);
        schedule.tick();
        assertThat(store.find(id).orElseThrow().status()).as("до назначенного времени запуск не создаётся").isEqualTo(ScheduledRunStore.Status.PENDING);

        makeDue(id, 1);
        schedule.tick();
        ScheduledRunStore.Item done = store.find(id).orElseThrow();
        assertThat(done.status()).as(String.valueOf(done.error())).isEqualTo(ScheduledRunStore.Status.STARTED);
        Run run = runService.get(done.runId());
        // интенсивность умножена в конфиге k6, в цели итогов и в снимке; переменные - заданные при планировании, а не скрипта
        assertThat(run.getK6Config().path("scenarios").path("work").path("rate").asLong()).isEqualTo(15);
        var snap = runService.snapshot(run);
        assertThat(snap.operations().get(0).ratePerSec()).isEqualTo(15.0);
        assertThat(snap.env()).containsEntry("BASE", "sched").containsEntry("EXTRA", "1");
        assertThat(snap.warnings()).anyMatch(w -> w.contains("по расписанию") && w.contains("work 150%"));
        assertThat(run.getRequest()).containsEntry("scheduleId", (int) id);
        // повторный проход запуск не дублирует
        schedule.tick();
        assertThat(store.find(id).orElseThrow().runId()).isEqualTo(done.runId());
    }

    @Test
    void withoutOverridesTheScriptsOwnLoadAndVariablesAreUsed() throws Exception {
        Script s = scriptWithLoad();
        Generator g = generator();
        long id = schedule.schedule(s.getId(), Instant.now().plusSeconds(120), List.of(g.getId()), null, null, Map.of(), "tester");
        makeDue(id, 1);
        schedule.tick();
        Run run = runService.get(store.find(id).orElseThrow().runId());
        assertThat(run.getK6Config().path("scenarios").path("work").path("rate").asLong()).isEqualTo(10);
        assertThat(runService.snapshot(run).env()).containsEntry("BASE", "script");
    }

    @Test
    void notEnoughFreeGeneratorsAndMissedTimesAreMarkedAsErrors() throws Exception {
        Script s = scriptWithLoad();
        long few = schedule.schedule(s.getId(), Instant.now().plusSeconds(120), List.of(), 999, null, Map.of(), "tester");
        makeDue(few, 1);
        long late = schedule.schedule(s.getId(), Instant.now().plusSeconds(120), List.of(generator().getId()), null, null, Map.of(), "tester");
        makeDue(late, 600);
        schedule.tick();
        ScheduledRunStore.Item a = store.find(few).orElseThrow();
        assertThat(a.status()).isEqualTo(ScheduledRunStore.Status.FAILED);
        assertThat(a.error()).contains("свободных генераторов");
        assertThat(a.runId()).isNull();
        ScheduledRunStore.Item b = store.find(late).orElseThrow();
        assertThat(b.status()).isEqualTo(ScheduledRunStore.Status.FAILED);
        assertThat(b.error()).contains("Пропущено");
    }

    @Test
    void pendingScheduleCanBeCancelledAndNeverStarts() throws Exception {
        Script s = scriptWithLoad();
        long id = schedule.schedule(s.getId(), Instant.now().plusSeconds(120), List.of(generator().getId()), null, null, Map.of(), "tester");
        assertThat(schedule.cancel(id, "tester")).isTrue();
        makeDue(id, 1);
        schedule.tick();
        assertThat(store.find(id).orElseThrow().status()).isEqualTo(ScheduledRunStore.Status.CANCELLED);
        assertThat(schedule.cancel(id, "tester")).as("отменить второй раз нельзя").isFalse();
    }

    @Test
    void invalidScheduleIsRejectedWhenPlanning() throws Exception {
        Script s = scriptWithLoad();
        Generator g = generator();
        Instant ok = Instant.now().plusSeconds(120);
        assertThatThrownBy(() -> schedule.schedule(s.getId(), Instant.now().minusSeconds(5), List.of(g.getId()), null, null, Map.of(), "t"))
                .hasMessageContaining("в будущем");
        assertThatThrownBy(() -> schedule.schedule(s.getId(), ok, List.of(g.getId()), null, null, Map.of("work", 0.0), "t")).hasMessageContaining("от 1 до");
        assertThatThrownBy(() -> schedule.schedule(s.getId(), ok, List.of(g.getId()), null, null, Map.of("nope", 120.0), "t")).hasMessageContaining("nope");
        assertThatThrownBy(() -> schedule.schedule(s.getId(), ok, List.of(g.getId()), null, Map.of("1BAD", "x"), Map.of(), "t")).hasMessageContaining("имя переменной");
        assertThatThrownBy(() -> schedule.schedule(s.getId(), ok, List.of(g.getId()), null, Map.of("K", "${secret:no_such_secret}"), Map.of(), "t"))
                .isInstanceOf(RuntimeException.class);
    }

    @Test
    void formCreatesAScheduleAndThePagesRender() throws Exception {
        Script s = scriptWithLoad();
        String at = Instant.now().plusSeconds(3600).toString();
        mvc.perform(post("/scripts/" + s.getId() + "/schedule").with(csrf()).param("runAt", at).param("count", "1")
                        .param("pct_work", "125").param("envMode", "custom").param("envText", "BASE=form\nTOKEN=abc"))
                .andExpect(status().is3xxRedirection()).andExpect(redirectedUrl("/schedule"));
        var item = schedule.pendingOf(s.getId()).get(0);
        assertThat(item.intensity()).containsEntry("work", 125.0);
        assertThat(item.env()).containsEntry("BASE", "form").containsEntry("TOKEN", "abc");
        mvc.perform(get("/schedule")).andExpect(status().isOk()).andExpect(content().string(containsString(s.getName())))
                .andExpect(content().string(containsString("125")));
        mvc.perform(get("/scripts/" + s.getId() + "/run")).andExpect(status().isOk()).andExpect(content().string(containsString("Запланировать запуск")))
                .andExpect(content().string(containsString("pct_work")));
        // ошибка в форме - возврат на страницу запуска с сообщением
        mvc.perform(post("/scripts/" + s.getId() + "/schedule").with(csrf()).param("runAt", "не время"))
                .andExpect(status().is3xxRedirection()).andExpect(redirectedUrl("/scripts/" + s.getId() + "/run#schedule"));
        mvc.perform(post("/schedule/" + item.id() + "/cancel").with(csrf())).andExpect(status().is3xxRedirection());
        assertThat(store.find(item.id()).orElseThrow().status()).isEqualTo(ScheduledRunStore.Status.CANCELLED);
    }
}
