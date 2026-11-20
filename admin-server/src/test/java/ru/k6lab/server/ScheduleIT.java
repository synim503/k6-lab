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

    private long saveProfile(Script s, String name, int rate, String env) throws Exception {
        mvc.perform(post("/scripts/" + s.getId() + "/load/profile").with(csrf()).param("profileName", name)
                .param("ops[0].execFn", "work").param("ops[0].name", "work").param("ops[0].enabled", "true").param("ops[0].executor", "constant-arrival-rate")
                .param("ops[0].scenarioJson", "{\"rate\":" + rate + ",\"timeUnit\":\"1s\",\"duration\":\"3m\",\"preAllocatedVUs\":2,\"maxVUs\":10}")
                .param("ops[0].slaPercentile", "95").param("ops[0].slaThresholdMs", "800").param("ops[0].maxErrorPct", "1")
                .param("ops[0].expectedDurationSec", "0.3").param("holdMinutes", "1").param("levelPct", "100").param("envText", env))
                .andExpect(status().isOk());
        return jdbc.queryForObject("select id from load_profile where script_id = ? and name = ?", Long.class, s.getId(), name);
    }

    @Test
    void scheduleWithAProfileRunsTheProfilesLoadAndVariablesAndKeepsTheScriptsLoad() throws Exception {
        Script s = scriptWithLoad();           // нагрузка скрипта: work 10 оп/с, BASE=script
        long profile = saveProfile(s, "Пик", 40, "BASE=profile\nZONE=eu");
        Generator g = generator();
        // профиль без своих переменных и процентов: операции, сценарий и переменные - из профиля
        long a = schedule.schedule(s.getId(), Instant.now().plusSeconds(120), List.of(g.getId()), null, null, Map.of(), profile, "tester");
        makeDue(a, 1);
        schedule.tick();
        ScheduledRunStore.Item done = store.find(a).orElseThrow();
        assertThat(done.status()).as(String.valueOf(done.error())).isEqualTo(ScheduledRunStore.Status.STARTED);
        assertThat(done.profileName()).isEqualTo("Пик");
        Run run = runService.get(done.runId());
        assertThat(run.getK6Config().path("scenarios").path("work").path("rate").asLong()).isEqualTo(40);
        var snap = runService.snapshot(run);
        assertThat(snap.env()).containsEntry("BASE", "profile").containsEntry("ZONE", "eu");
        assertThat(snap.warnings()).anyMatch(w -> w.contains("профиль нагрузки «Пик»"));
        assertThat(run.getRequest()).containsEntry("loadProfileId", (int) profile);

        // проценты применяются к операциям профиля, а явные переменные расписания важнее переменных профиля
        Generator g2 = generator();
        long b = schedule.schedule(s.getId(), Instant.now().plusSeconds(120), List.of(g2.getId()), null, Map.of("BASE", "custom"),
                Map.of("work", 150.0), profile, "tester");
        makeDue(b, 1);
        schedule.tick();
        Run run2 = runService.get(store.find(b).orElseThrow().runId());
        assertThat(run2.getK6Config().path("scenarios").path("work").path("rate").asLong()).isEqualTo(60);
        assertThat(runService.snapshot(run2).env()).containsEntry("BASE", "custom").doesNotContainKey("ZONE");

        // сохранённая нагрузка скрипта профилем не тронута
        assertThat(load.activeOperations(s.getId()).get(0).getRatePerSec()).isEqualTo(10.0);
        assertThat(load.toForm(s.getId()).getEnvText()).contains("BASE=script");
    }

    @Test
    void immediateRunWithAProfileUsesTheProfilesLoadAndVariables() throws Exception {
        Script s = scriptWithLoad();
        long profile = saveProfile(s, "Сразу", 40, "BASE=profile\nZONE=eu");
        Generator g = generator();
        // форма запуска показывает выбор профиля
        mvc.perform(get("/scripts/" + s.getId() + "/run")).andExpect(status().isOk()).andExpect(content().string(containsString("id=\"run-profile\"")));
        mvc.perform(post("/scripts/" + s.getId() + "/run").with(csrf()).param("generatorId", String.valueOf(g.getId())).param("profile", String.valueOf(profile)))
                .andExpect(status().is3xxRedirection());
        Run run = runService.search(s.getId(), null, 1).get(0);
        assertThat(run.getK6Config().path("scenarios").path("work").path("rate").asLong()).isEqualTo(40);
        var snap = runService.snapshot(run);
        assertThat(snap.env()).containsEntry("BASE", "profile").containsEntry("ZONE", "eu");
        assertThat(snap.warnings()).anyMatch(w -> w.contains("профилем нагрузки «Сразу»"));
        assertThat(run.getRequest()).containsEntry("loadProfileId", (int) profile).containsEntry("loadProfileName", "Сразу");
        // профиль виден в шапке страницы запуска
        mvc.perform(get("/runs/" + run.getId())).andExpect(status().isOk())
                .andExpect(content().string(containsString("профиль нагрузки:"))).andExpect(content().string(containsString("Сразу")));
        // без профиля - нагрузка скрипта
        Generator g2 = generator();
        mvc.perform(post("/scripts/" + s.getId() + "/run").with(csrf()).param("generatorId", String.valueOf(g2.getId())))
                .andExpect(status().is3xxRedirection());
        Run plain = runService.search(s.getId(), null, 1).get(0);
        assertThat(plain.getK6Config().path("scenarios").path("work").path("rate").asLong()).isEqualTo(10);
        // удалённый профиль - ошибка на форме, а не запуск
        mvc.perform(post("/scripts/" + s.getId() + "/run").with(csrf()).param("generatorId", String.valueOf(generator().getId())).param("profile", "987654"))
                .andExpect(status().isOk()).andExpect(content().string(containsString("Профиль нагрузки не найден")));
    }

    @Test
    void autoStopCriteriaAreSavedWithTheLoadAndTakenByRunsAndProfiles() throws Exception {
        Script s = scriptWithLoad();
        // по умолчанию автостоп выключен, страница нагрузки его показывает
        assertThat(load.toForm(s.getId()).getStop().isRateEnabled()).isFalse();
        mvc.perform(get("/scripts/" + s.getId() + "/load")).andExpect(status().isOk()).andExpect(content().string(containsString("Автостоп")));

        LoadForm f = load.toForm(s.getId());
        f.getStop().setRateEnabled(true);
        f.getStop().setMinRatePct(80);
        f.getStop().setDroppedEnabled(true);
        f.getStop().setConsecutive(2);
        load.save(s.getId(), f);
        var saved = load.toForm(s.getId()).getStop();
        assertThat(saved.isRateEnabled()).isTrue();
        assertThat(saved.isDroppedEnabled()).isTrue();
        assertThat(saved.isErrorsEnabled()).isFalse();
        assertThat(saved.getMinRatePct()).isEqualTo(80.0);
        assertThat(saved.getConsecutive()).isEqualTo(2);

        Generator g = generator();
        Run run = runService.create(new RunService.CreateRequest(s.getId(), List.of(new RunService.GeneratorSel(g.getId(), 1)),
                Run.FailurePolicy.CONTINUE, 120));
        assertThat(runService.snapshot(run).stopCriteria()).containsEntry("rateEnabled", true).containsEntry("droppedEnabled", true)
                .containsEntry("errorsEnabled", false).containsEntry("consecutive", 2);

        // неверные значения отклоняются сразу
        LoadForm bad = load.toForm(s.getId());
        bad.getStop().setWindowSec(5);
        assertThatThrownBy(() -> load.save(s.getId(), bad)).hasMessageContaining("окно");

        // профиль несёт свой автостоп, нагрузка скрипта остаётся со своим
        mvc.perform(post("/scripts/" + s.getId() + "/load/profile").with(csrf()).param("profileName", "Строгий")
                .param("ops[0].execFn", "work").param("ops[0].name", "work").param("ops[0].enabled", "true").param("ops[0].executor", "constant-arrival-rate")
                .param("ops[0].scenarioJson", "{\"rate\":20,\"timeUnit\":\"1s\",\"duration\":\"3m\",\"preAllocatedVUs\":2,\"maxVUs\":10}")
                .param("ops[0].slaPercentile", "95").param("ops[0].slaThresholdMs", "800").param("ops[0].maxErrorPct", "1")
                .param("ops[0].expectedDurationSec", "0.3").param("holdMinutes", "1").param("levelPct", "100")
                .param("stop.errorsEnabled", "true").param("stop.consecutive", "5").param("stop.windowSec", "90"))
                .andExpect(status().isOk());
        long profile = jdbc.queryForObject("select id from load_profile where script_id = ? and name = ?", Long.class, s.getId(), "Строгий");
        Run pr = runService.create(new RunService.CreateRequest(s.getId(), List.of(new RunService.GeneratorSel(generator().getId(), 1)),
                Run.FailurePolicy.CONTINUE, 120, List.of(), new RunService.Overrides(null, Map.of(), null, null, profile, "Строгий")));
        assertThat(runService.snapshot(pr).stopCriteria()).containsEntry("errorsEnabled", true).containsEntry("rateEnabled", false)
                .containsEntry("consecutive", 5).containsEntry("windowSec", 90);
    }

    @Test
    void scheduleWithAProfileIsCheckedAtPlanningAndFailsIfTheProfileIsDeleted() throws Exception {
        Script s = scriptWithLoad();
        long profile = saveProfile(s, "Временный", 20, "A=1");
        assertThatThrownBy(() -> schedule.schedule(s.getId(), Instant.now().plusSeconds(120), List.of(generator().getId()), null, null, Map.of(), 987654L, "t"))
                .hasMessageContaining("Профиль нагрузки не найден");
        // интенсивность проверяется по операциям профиля
        assertThatThrownBy(() -> schedule.schedule(s.getId(), Instant.now().plusSeconds(120), List.of(generator().getId()), null, null,
                Map.of("nope", 120.0), profile, "t")).hasMessageContaining("nope");
        long id = schedule.schedule(s.getId(), Instant.now().plusSeconds(120), List.of(generator().getId()), null, null, Map.of(), profile, "t");
        jdbc.update("delete from load_profile where id = ?", profile);
        makeDue(id, 1);
        schedule.tick();
        ScheduledRunStore.Item item = store.find(id).orElseThrow();
        assertThat(item.status()).isEqualTo(ScheduledRunStore.Status.FAILED);
        assertThat(item.error()).contains("Профиль нагрузки не найден");
        // форма запуска знает профили скрипта
        mvc.perform(get("/scripts/" + s.getId() + "/run")).andExpect(status().isOk());
    }

    @Test
    void notEnoughFreeGeneratorsAndMissedTimesAreMarkedAsErrors() throws Exception {
        Script s = scriptWithLoad();
        // свободных не хватает - отказ сразу при планировании, а не в назначенный час
        assertThatThrownBy(() -> schedule.schedule(s.getId(), Instant.now().plusSeconds(120), List.of(), 999, null, Map.of(), "tester"))
                .hasMessageContaining("свободно генераторов");
        long late = schedule.schedule(s.getId(), Instant.now().plusSeconds(120), List.of(generator().getId()), null, null, Map.of(), "tester");
        makeDue(late, 600);
        schedule.tick();
        ScheduledRunStore.Item b = store.find(late).orElseThrow();
        assertThat(b.status()).isEqualTo(ScheduledRunStore.Status.FAILED);
        assertThat(b.error()).contains("Пропущено");
    }

    @Test
    void anyFreeGeneratorsAreReservedWhenPlanningAndNotGivenTwice() throws Exception {
        Script s = scriptWithLoad();
        generator();
        generator();
        Instant at = Instant.now().plus(java.time.Duration.ofDays(30));
        long online = generators.findAll().stream().filter(g -> g.getStatus() == Generator.Status.ONLINE && !g.isMaintenance()).count();
        long first = schedule.schedule(s.getId(), at, List.of(), 1, null, Map.of(), "t");
        var item = store.find(first).orElseThrow();
        assertThat(item.generatorIds()).hasSize(1).as("генератор закреплён за записью");
        // все остальные свободные берутся, а ещё один - уже нет
        long rest = schedule.schedule(s.getId(), at, List.of(), (int) online - 1, null, Map.of(), "t");
        assertThat(store.find(rest).orElseThrow().generatorIds()).doesNotContainAnyElementsOf(item.generatorIds());
        assertThatThrownBy(() -> schedule.schedule(s.getId(), at, List.of(), 1, null, Map.of(), "t")).hasMessageContaining("свободно генераторов: 0");
        // после отмены генератор снова свободен
        assertThat(schedule.cancel(first, "t")).isTrue();
        assertThat(schedule.schedule(s.getId(), at, List.of(), 1, null, Map.of(), "t")).isPositive();
    }

    @Test
    void shiftingScheduleMovesForwardWhenGeneratorIsFreeAndOthersStayPut() throws Exception {
        Script s = scriptWithLoad();
        Generator g = generator();
        Generator g2 = generator();
        Instant at = Instant.now().plus(java.time.Duration.ofDays(30));
        long shifting = schedule.schedule(s.getId(), at, List.of(g.getId()), null, null, Map.of(), null, "t", true);
        long fixed = schedule.schedule(s.getId(), at, List.of(g2.getId()), null, null, Map.of(), null, "t", false);
        schedule.tick();
        var moved = store.find(shifting).orElseThrow();
        assertThat(moved.runAt()).isBefore(Instant.now().plusSeconds(600));
        assertThat(moved.status()).isEqualTo(ScheduledRunStore.Status.PENDING);
        assertThat(store.find(fixed).orElseThrow().runAt().toEpochMilli()).isCloseTo(at.toEpochMilli(), org.assertj.core.api.Assertions.within(1000L));
        // пауза между запусками на одном генераторе: следующая запись не встаёт встык
        Instant end = moved.runAt().plusSeconds(60);
        assertThatThrownBy(() -> schedule.schedule(s.getId(), end, List.of(g.getId()), null, null, Map.of(), "t")).isInstanceOf(RuntimeException.class);
    }

    @Test
    void withoutATimeTheNearestAvailableSlotIsTaken() throws Exception {
        Script s = scriptWithLoad();
        Generator g = generator();
        long a = schedule.schedule(s.getId(), null, List.of(g.getId()), null, null, Map.of(), "t");
        var first = store.find(a).orElseThrow();
        assertThat(first.runAt()).isBefore(Instant.now().plusSeconds(180)).isAfter(Instant.now().plusSeconds(30));
        // тот же генератор: следующая запись без времени встаёт после первой с паузой
        long b = schedule.schedule(s.getId(), null, List.of(g.getId()), null, null, Map.of(), "t");
        assertThat(store.find(b).orElseThrow().runAt()).isAfter(first.runAt().plusSeconds(300));
        // форма без времени тоже работает
        mvc.perform(post("/scripts/" + s.getId() + "/schedule").with(csrf()).param("runAt", "").param("generatorId", String.valueOf(g.getId())))
                .andExpect(status().is3xxRedirection()).andExpect(redirectedUrl("/schedule"));
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

    @Test
    void timelineShowsGeneratorsAndPendingSchedulesWithTheirDuration() throws Exception {
        Script s = scriptWithLoad();
        Generator g = generator();
        Instant at = Instant.now().plusSeconds(3600);
        long fixed = schedule.schedule(s.getId(), at, List.of(g.getId()), null, null, Map.of(), "t");
        long pool = schedule.schedule(s.getId(), at.plusSeconds(7200), List.of(), 1, null, Map.of(), "t");
        String json = mvc.perform(get("/scripts/" + s.getId() + "/schedule/timeline")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        var root = new com.fasterxml.jackson.databind.ObjectMapper().readTree(json);
        assertThat(root.path("durationSec").asInt()).isGreaterThan(0);
        assertThat(root.path("generators")).anyMatch(n -> n.path("id").asLong() == g.getId());
        var bars = root.path("bars");
        var fixedBar = java.util.stream.StreamSupport.stream(bars.spliterator(), false).filter(b -> b.path("scheduleId").asLong() == fixed).findFirst().orElseThrow();
        assertThat(fixedBar.path("generatorId").asLong()).isEqualTo(g.getId());
        assertThat(fixedBar.path("to").asLong() - fixedBar.path("from").asLong()).isGreaterThanOrEqualTo(root.path("durationSec").asLong() * 1000);
        var poolBar = java.util.stream.StreamSupport.stream(bars.spliterator(), false).filter(b -> b.path("scheduleId").asLong() == pool).findFirst().orElseThrow();
        // «любые свободные» бронируются сразу: на полосе конкретный генератор
        assertThat(poolBar.path("generatorId").isNull()).isFalse();
        assertThat(poolBar.path("count").asInt()).isEqualTo(1);
        // страница запуска содержит таймлайн
        mvc.perform(get("/scripts/" + s.getId() + "/run")).andExpect(status().isOk()).andExpect(content().string(containsString("tl-root")));
        // общий обзор для страницы расписания: те же полосы с подробностями (профиль, кто назначил, интенсивность)
        mvc.perform(get("/schedule")).andExpect(status().isOk()).andExpect(content().string(containsString("tl-root")));
        var all = new com.fasterxml.jackson.databind.ObjectMapper().readTree(
                mvc.perform(get("/schedule/timeline")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8));
        assertThat(all.has("durationSec")).isFalse();
        var fixedAll = java.util.stream.StreamSupport.stream(all.path("bars").spliterator(), false).filter(b -> b.path("scheduleId").asLong() == fixed).findFirst().orElseThrow();
        assertThat(fixedAll.path("by").asText()).isEqualTo("t");
        assertThat(fixedAll.path("script").asText()).isEqualTo(s.getName());
        assertThat(fixedAll.path("intensity").asText()).isEqualTo("как в скрипте");
        assertThat(fixedAll.path("genNames").asText()).isNotBlank();
    }

    @Autowired
    ru.k6lab.server.schedule.ScheduleImportService importer;

    @Test
    void importedTestsWithProfilesAreQueuedOneAfterAnotherAndAnErrorSchedulesNothing() throws Exception {
        Script s = scriptWithLoad();
        saveProfile(s, "Максимум", 40, "");
        saveProfile(s, "Стабильность", 20, "");
        Generator g = generator();
        String json = "{\"tests\": [{\"script\": \"" + s.getName() + "\", \"profile\": \"Максимум\", \"generators\": [\"" + g.getName() + "\"]},"
                + "{\"script\": \"" + s.getName() + "\", \"profile\": \"Стабильность\", \"generators\": [" + g.getId() + "]}]}";
        var created = importer.importTests(json, "tester");
        assertThat(created).hasSize(2);
        ScheduledRunStore.Item first = store.find(created.get(0).scheduleId()).orElseThrow();
        ScheduledRunStore.Item second = store.find(created.get(1).scheduleId()).orElseThrow();
        assertThat(first.profileName()).isEqualTo("Максимум");
        assertThat(second.profileName()).isEqualTo("Стабильность");
        assertThat(first.shiftForward()).isTrue();
        // один генератор: второй тест встаёт после первого с паузой
        assertThat(second.runAt()).isAfter(first.runAt().plusSeconds(60));

        int pendingBefore = schedule.pendingOf(s.getId()).size();
        assertThat(pendingBefore).isEqualTo(2);
        String bad = "[{\"script\": \"" + s.getName() + "\", \"profile\": \"Максимум\", \"generators\": [" + g.getId() + "]},"
                + "{\"script\": \"" + s.getName() + "\", \"profile\": \"Нет такого\"}]";
        assertThatThrownBy(() -> importer.importTests(bad, "tester")).hasMessageContaining("Тест №2").hasMessageContaining("Нет такого");
        // первый тест из неудачного импорта отменён: ожидающих записей скрипта по-прежнему две
        assertThat(schedule.pendingOf(s.getId())).hasSize(pendingBefore);
        assertThatThrownBy(() -> importer.importTests("не json", "tester")).isInstanceOf(IllegalArgumentException.class);

        mvc.perform(post("/schedule/import").with(csrf()).param("json", "[]")).andExpect(status().is3xxRedirection());
        mvc.perform(get("/schedule")).andExpect(status().isOk()).andExpect(content().string(containsString("Импорт списка тестов")));
    }
}
