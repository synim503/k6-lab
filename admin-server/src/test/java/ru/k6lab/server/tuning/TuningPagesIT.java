package ru.k6lab.server.tuning;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import ru.k6lab.server.AbstractIT;
import ru.k6lab.server.generator.Generator;
import ru.k6lab.server.generator.GeneratorRepository;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Страницы профилирования с настоящими данными профиля и хранение истории тюнинга (без SSH: подключение к хостам проверяется на стенде). */
class TuningPagesIT extends AbstractIT {

    private static final String OUTPUT = """
            @@meta
            nproc 4
            mem_total_kb 8000000
            mem_avail_kb 4000000
            iface eth0
            speed_mbit 1000
            @@sysctl
            net.ipv4.ip_local_port_range=32768 60999
            net.ipv4.tcp_tw_reuse=0
            @@limits
            k6 77 1024 4096 100
            @@s1
            time 10.0
            stat cpu  100 0 100 800 0 0 0 0 0 0
            sockstat TCP: inuse 100 orphan 0 tw 10 alloc 1 mem 1
            @@s2
            time 20.0
            stat cpu  900 0 100 900 0 0 0 0 0 0
            sockstat TCP: inuse 30000 orphan 0 tw 5000 alloc 1 mem 1
            """;

    @Autowired
    MockMvc mvc;
    @Autowired
    GeneratorRepository generators;
    @Autowired
    HostTuningStore store;
    @Autowired
    TuningProfileStore profiles;
    @Autowired
    ObjectMapper mapper;
    @Autowired
    ru.k6lab.server.ssh.SshCredentialService sshCredentials;
    @Autowired
    org.springframework.jdbc.core.JdbcTemplate jdbc;

    private Generator generator(String prefix) {
        Generator g = new Generator();
        g.setName(prefix + "-" + UUID.randomUUID().toString().substring(0, 6));
        g.setHost("10.0.0.5");
        g.setInstallDir("/opt/k6-agent");
        g.setServerUrl("ws://127.0.0.1:1/agent/ws");
        g.setStatus(Generator.Status.ONLINE);
        return generators.save(g);
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void profilePagesShowFindingsAndTuningHistoryIsStoredWithOriginalValues() throws Exception {
        Generator g = generator("tune");
        HostMetrics m = HostMetricsParser.parse(OUTPUT, 10);
        TuningAnalyzer.Analysis a = TuningAnalyzer.analyze(m);
        long pid = store.createProfile(g.getId(), g.getName(), 10);
        store.finishProfile(pid, "DONE", null, m, a.findings());

        mvc.perform(get("/tuning")).andExpect(status().isOk())
                .andExpect(content().string(containsString(g.getName())))
                .andExpect(content().string(containsString("можно применить")))
                .andExpect(content().string(containsString("/tuning/profile/" + pid)));
        mvc.perform(get("/tuning/profile/" + pid)).andExpect(status().isOk())
                .andExpect(content().string(containsString("Заканчиваются локальные порты")))
                .andExpect(content().string(containsString("1024 65535")))
                .andExpect(content().string(containsString("Мал лимит открытых файлов у k6")));
        mvc.perform(get("/tuning/profile/999999")).andExpect(status().is3xxRedirection()).andExpect(redirectedUrl("/tuning"));
        // рекомендации берутся из находок профиля и только для известных параметров
        assertThat(HostTuningService.recommended(store.latestDone(g.getId()).orElseThrow()))
                .containsEntry("net.ipv4.ip_local_port_range", "1024 65535").containsEntry("net.ipv4.tcp_tw_reuse", "1");

        // история: исходные значения сохраняются, применение заменяет предыдущее, откат закрывает запись
        store.insertTuning(g.getId(), g.getName(), "admin", "APPLIED", java.util.List.of(
                java.util.Map.of("key", "net.ipv4.tcp_tw_reuse", "previous", "0", "requested", "1", "applied", "1", "ok", true)), null);
        assertThat(store.activeTunings(g.getId())).hasSize(1);
        assertThat(store.activeTunings(g.getId()).get(0).changes().get(0).path("previous").asText()).isEqualTo("0");
        mvc.perform(get("/tuning")).andExpect(status().isOk()).andExpect(content().string(containsString("tcp_tw_reuse")));
        store.markReplaced(g.getId());
        assertThat(store.activeTunings(g.getId())).isEmpty();
        store.insertTuning(g.getId(), g.getName(), "admin", "APPLIED", java.util.List.of(), null);
        store.markRolledBack(g.getId());
        assertThat(store.activeTunings(g.getId())).isEmpty();
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void startRequiresHostsAndValidDurationAndSkipsHostsWithoutSshCredential() throws Exception {
        mvc.perform(post("/tuning/profile").with(csrf()).param("seconds", "30"))
                .andExpect(status().is3xxRedirection()).andExpect(redirectedUrl("/tuning"));
        Generator g = generator("nossh");
        mvc.perform(post("/tuning/profile").with(csrf()).param("generatorIds", String.valueOf(g.getId())).param("seconds", "3"))
                .andExpect(status().is3xxRedirection()).andExpect(redirectedUrl("/tuning"));
        mvc.perform(post("/tuning/profile").with(csrf()).param("generatorIds", String.valueOf(g.getId())).param("seconds", "30"))
                .andExpect(status().is3xxRedirection());
        // без SSH-учётки профиль не создаётся
        assertThat(store.latestProfiles().stream().filter(p -> p.generatorId() == g.getId())).isEmpty();
        // применение без профиля ничего не меняет
        mvc.perform(post("/tuning/apply").with(csrf()).param("generatorIds", String.valueOf(g.getId())))
                .andExpect(status().is3xxRedirection());
        assertThat(store.activeTunings(g.getId())).isEmpty();
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void referenceProfileWithMostChangesIsOfferedAndRolloutAndRollbackAllAreWired() throws Exception {
        Generator small = generator("small"), big = generator("big");
        // у «big» больше рекомендаций (порты, tw_reuse, swap), у «small» только порты
        String bigOut = OUTPUT.replace("net.ipv4.tcp_tw_reuse=0", "net.ipv4.tcp_tw_reuse=0\nvm.swappiness=60")
                .replace("mem_avail_kb 4000000", "mem_avail_kb 4000000\nswap_total_kb 2000000\nswap_free_kb 1500000");
        String smallOut = OUTPUT.replace("net.ipv4.tcp_tw_reuse=0", "net.ipv4.tcp_tw_reuse=1").replace("tw 5000", "tw 10");
        for (var e : java.util.Map.of(big, bigOut, small, smallOut).entrySet()) {
            HostMetrics m = HostMetricsParser.parse(e.getValue(), 10);
            long pid = store.createProfile(e.getKey().getId(), e.getKey().getName(), 10);
            store.finishProfile(pid, "DONE", null, m, TuningAnalyzer.analyze(m).findings());
        }
        int bigCount = HostTuningService.recommended(store.latestDone(big.getId()).orElseThrow()).size();
        int smallCount = HostTuningService.recommended(store.latestDone(small.getId()).orElseThrow()).size();
        assertThat(bigCount).isGreaterThan(smallCount);
        // по умолчанию эталон - хост с наибольшим числом изменений; параметры и кнопки раскатки/возврата на странице
        var page = mvc.perform(get("/tuning")).andExpect(status().isOk())
                .andExpect(content().string(containsString("Эталонная настройка")))
                .andExpect(content().string(containsString("Раскатить на отмеченные")))
                .andExpect(content().string(containsString("Вернуть всё в исходное")))
                .andReturn().getResponse().getContentAsString();
        assertThat(page).contains("value=\"" + big.getId() + "\" selected").contains(big.getName() + " — изменений: " + bigCount);
        // другой эталон выбирается параметром
        mvc.perform(get("/tuning").param("std", String.valueOf(small.getId())))
                .andExpect(content().string(containsString("value=\"" + small.getId() + "\" selected")));
        // раскатка и возврат без SSH-хостов не падают; возврат всего без применённого тюнинга сообщает об этом
        mvc.perform(post("/tuning/rollout").with(csrf()).param("sourceId", String.valueOf(big.getId())))
                .andExpect(status().is3xxRedirection()).andExpect(redirectedUrl("/tuning"));
        mvc.perform(post("/tuning/rollout").with(csrf()).param("sourceId", "999999"))
                .andExpect(status().is3xxRedirection()).andExpect(redirectedUrl("/tuning"));
        mvc.perform(post("/tuning/rollback-all").with(csrf())).andExpect(status().is3xxRedirection()).andExpect(redirectedUrl("/tuning"));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void profilingIsRecordedInTheOperationsJournal() throws Exception {
        var cred = sshCredentials.save(new ru.k6lab.server.ssh.SshCredentialService.Form(null, "it-tune-" + UUID.randomUUID().toString().substring(0, 6),
                "deploy", ru.k6lab.server.ssh.SshCredential.AuthType.PASSWORD, "pw", null, null, null, null, null, false, false));
        Generator g = generator("journal");
        g.setHost("127.0.0.1");
        g.setSshPort(1); // порт закрыт: подключение сразу отказывает
        g.setSshCredentialId(cred.getId());
        g = generators.save(g);
        mvc.perform(post("/tuning/profile").with(csrf()).param("generatorIds", String.valueOf(g.getId())).param("seconds", "10"))
                .andExpect(status().is3xxRedirection());
        long gid = g.getId();
        org.awaitility.Awaitility.await().atMost(java.time.Duration.ofSeconds(30)).until(() -> {
            Integer n = jdbc.queryForObject("select count(*) from deployment where generator_id = ? and operation = 'PROFILE' and status = 'FAILED'", Integer.class, gid);
            return n != null && n == 1;
        });
        mvc.perform(get("/deployments")).andExpect(status().isOk()).andExpect(content().string(containsString("Профилирование хоста")));
        assertThat(store.latestProfiles().stream().filter(p -> p.generatorId() == gid).findFirst().orElseThrow().status()).isEqualTo("FAILED");
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void optimizationProfileIsSavedEditedAndAppliedFromTheSavedSet() throws Exception {
        Generator g = generator("opt");
        HostMetrics m = HostMetricsParser.parse(OUTPUT, 10);
        long pid = store.createProfile(g.getId(), g.getName(), 10);
        store.finishProfile(pid, "DONE", null, m, TuningAnalyzer.analyze(m).findings());
        String name = "prof-" + UUID.randomUUID().toString().substring(0, 6);
        // сохранение рекомендаций профиля хоста как профиля оптимизации
        var res = mvc.perform(post("/tuning/profiles").with(csrf()).param("name", name).param("fromHost", String.valueOf(g.getId())))
                .andExpect(status().is3xxRedirection()).andReturn();
        String loc = res.getResponse().getRedirectedUrl();
        assertThat(loc).startsWith("/tuning/profiles/");
        long id = Long.parseLong(loc.substring(loc.lastIndexOf('/') + 1));
        var saved = profiles.find(id).orElseThrow();
        assertThat(saved.name()).isEqualTo(name);
        assertThat(saved.settings()).containsEntry("net.ipv4.ip_local_port_range", "1024 65535");
        // повторное имя отклоняется
        mvc.perform(post("/tuning/profiles").with(csrf()).param("name", name).param("fromHost", String.valueOf(g.getId())))
                .andExpect(redirectedUrl("/tuning"));
        // страницы: список и редактор
        mvc.perform(get("/tuning")).andExpect(status().isOk()).andExpect(content().string(containsString(name)))
                .andExpect(content().string(containsString("Профили оптимизации")));
        mvc.perform(get("/tuning/profiles/" + id)).andExpect(status().isOk())
                .andExpect(content().string(containsString("net.ipv4.ip_local_port_range"))).andExpect(content().string(containsString("добавить параметр")));
        // редактирование: меняем значение, добавляем параметр и убираем другой
        mvc.perform(post("/tuning/profiles/" + id).with(csrf()).param("name", name + "-v2").param("description", "для логов")
                        .param("key", "net.ipv4.ip_local_port_range").param("value", "10240 65535")
                        .param("key", "net.ipv4.tcp_tw_reuse").param("value", "1").param("remove", "net.ipv4.tcp_tw_reuse")
                        .param("newKey", "vm.swappiness").param("newValue", "10"))
                .andExpect(redirectedUrl("/tuning/profiles/" + id));
        var edited = profiles.find(id).orElseThrow();
        assertThat(edited.name()).isEqualTo(name + "-v2");
        assertThat(edited.settings()).containsEntry("net.ipv4.ip_local_port_range", "10240 65535").containsEntry("vm.swappiness", "10")
                .doesNotContainKey("net.ipv4.tcp_tw_reuse");
        // недопустимое значение не сохраняется
        mvc.perform(post("/tuning/profiles/" + id).with(csrf()).param("name", name).param("key", "net.core.somaxconn").param("value", "1; reboot"))
                .andExpect(redirectedUrl("/tuning/profiles/" + id));
        assertThat(profiles.find(id).orElseThrow().name()).isEqualTo(name + "-v2");
        // раскатка без SSH-хостов не падает; удаление
        mvc.perform(post("/tuning/profiles/" + id + "/apply").with(csrf())).andExpect(redirectedUrl("/tuning"));
        mvc.perform(post("/tuning/profiles/" + id + "/delete").with(csrf())).andExpect(redirectedUrl("/tuning"));
        assertThat(profiles.find(id)).isEmpty();
    }
}