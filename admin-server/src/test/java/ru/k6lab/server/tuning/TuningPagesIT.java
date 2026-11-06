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
    ObjectMapper mapper;

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
}
