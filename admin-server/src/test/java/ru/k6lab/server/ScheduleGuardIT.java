package ru.k6lab.server;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import ru.k6lab.server.audit.AuditService;
import ru.k6lab.server.metrics.PrometheusClient;
import ru.k6lab.server.schedule.ScheduleGuardService;
import ru.k6lab.server.schedule.ScheduleGuardStore;
import ru.k6lab.server.schedule.ScheduledRunService;
import ru.k6lab.server.settings.SettingsService;
import ru.k6lab.server.ssh.KnownHostService;
import ru.k6lab.server.ssh.SshConnection;
import ru.k6lab.server.ssh.SshConnector;
import ru.k6lab.server.ssh.SshCredential;
import ru.k6lab.server.ssh.SshCredentialService;
import ru.k6lab.server.ssh.SshExceptions;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Автопроверки планировщика: метрика Prometheus -> SSH-скрипт, cooldown, blockOnFail, подтверждение host key, RBAC. */
@WithMockUser(roles = "ADMIN")
class ScheduleGuardIT extends AbstractIT {

    @Autowired
    MockMvc mvc;
    @Autowired
    ScheduleGuardService guardService;
    @Autowired
    ScheduleGuardStore guardStore;
    @Autowired
    PrometheusClient prometheusClient;
    @Autowired
    AuditService auditService;
    @Autowired
    SshCredentialService sshCredentials;
    @Autowired
    KnownHostService knownHosts;
    @Autowired
    SettingsService settings;
    @Autowired
    ScheduledRunService scheduledRuns;

    private FakePrometheus prom;
    private Long credId;

    @BeforeEach
    void setUp() throws Exception {
        prom = new FakePrometheus();
        settings.savePrometheus(prom.baseUrl(), prom.rwUrl(), SettingsService.AuthType.NONE, "", "", "");
        String suffix = UUID.randomUUID().toString().substring(0, 6);
        SshCredential c = sshCredentials.save(new SshCredentialService.Form(
                null, "guard-cred-" + suffix, "ops", SshCredential.AuthType.PASSWORD,
                "MySecretPass123", null, "MySudoPass456", null, null, null, false, false));
        credId = c.getId();
    }

    @AfterEach
    void tearDown() {
        for (var g : guardStore.list()) {
            guardStore.delete(g.id());
        }
        if (prom != null) {
            prom.close();
        }
        if (scheduledRuns.paused()) {
            settings.delete(ScheduledRunService.PAUSED_KEY);
            settings.delete(ScheduledRunService.PAUSE_REASON_KEY);
        }
    }

    @Test
    void crudAndMetricBelowVsAboveThresholdExecutesSshAndMasksSecrets() throws Exception {
        SshConnector sshMock = mock(SshConnector.class);
        ScheduleGuardService svc = new ScheduleGuardService(guardStore, prometheusClient, settings, sshMock, sshCredentials, auditService);

        // 1. Создание автопроверки через HTTP-форму на /schedule
        mvc.perform(post("/schedule/guards").with(csrf())
                        .param("name", "Очистка диска > 80%")
                        .param("enabled", "true")
                        .param("promql", "disk_used_pct")
                        .param("cmp", "GT")
                        .param("threshold", "80")
                        .param("unit", "%")
                        .param("runWhen", "BOTH")
                        .param("sshHost", "10.0.0.50")
                        .param("sshPort", "22")
                        .param("sshCredentialId", String.valueOf(credId))
                        .param("useSudo", "true")
                        .param("script", "rm -rf /tmp/test-logs && echo done")
                        .param("timeoutSec", "60")
                        .param("cooldownSec", "120")
                        .param("blockOnFail", "false"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/schedule#guards"));

        var list = guardStore.list();
        assertThat(list).hasSize(1);
        long gid = list.get(0).id();

        // Учётку, используемую автопроверкой, нельзя удалить
        assertThatThrownBy(() -> sshCredentials.delete(credId))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("автопроверками");

        // 2. Метрика ниже порога (65% <= 80%) -> проверка через HTTP и через сервис: SSH не вызывается
        prom.respond((q, t) -> Map.of("host1", 50.0, "host2", 65.0));
        mvc.perform(post("/schedule/guards/" + gid + "/check").with(csrf()).param("runSsh", "false"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/schedule#guards"))
                .andExpect(flash().attributeExists("info"));

        var okOut = svc.checkAndRun(gid, true, false, false, "test");
        assertThat(okOut.status()).isEqualTo(ScheduleGuardStore.LastStatus.OK);
        assertThat(okOut.value()).isEqualTo(65.0);
        assertThat(okOut.triggered()).isFalse();
        assertThat(okOut.sshExecuted()).isFalse();
        verify(sshMock, never()).open(anyString(), anyInt(), any(), any());

        // 3. Метрика выше порога (92.5% > 80%) -> выполняется SSH через sudo и маскируются пароли
        prom.respond((q, t) -> Map.of("host1", 70.0, "host2", 92.5));
        SshConnection conn = mock(SshConnection.class);
        when(sshMock.open(eq("10.0.0.50"), eq(22), any(), any(Duration.class))).thenReturn(conn);
        when(conn.sudo(eq("rm -rf /tmp/test-logs && echo done"), eq(Duration.ofSeconds(60))))
                .thenReturn(new SshConnection.ExecResult(0, "cleaned ok, leak MySudoPass456", ""));

        var execOut = svc.checkAndRun(gid, true, false, false, "test");
        assertThat(execOut.status()).isEqualTo(ScheduleGuardStore.LastStatus.EXECUTED);
        assertThat(execOut.value()).isEqualTo(92.5);
        assertThat(execOut.triggered()).isTrue();
        assertThat(execOut.sshExecuted()).isTrue();
        assertThat(execOut.output()).contains("cleaned ok").doesNotContain("MySudoPass456").contains("******");
        verify(sshMock, times(1)).open(eq("10.0.0.50"), eq(22), any(), any());

        // 4. Повторная проверка в пределах cooldown -> SKIPPED_COOLDOWN, SSH повторно не запускается
        var coolOut = svc.checkAndRun(gid, true, false, false, "test-again");
        assertThat(coolOut.status()).isEqualTo(ScheduleGuardStore.LastStatus.SKIPPED_COOLDOWN);
        assertThat(coolOut.sshExecuted()).isFalse();
        verify(sshMock, times(1)).open(eq("10.0.0.50"), eq(22), any(), any());

        // 5. На странице /schedule отображается карточка автопроверки и её статус
        String html = mvc.perform(get("/schedule"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(html)
                .contains("Очистка диска &gt; 80%")
                .contains("10.0.0.50")
                .contains("sudo");

        // 6. Выключение и удаление через HTTP
        mvc.perform(post("/schedule/guards/" + gid + "/toggle").with(csrf()).param("enabled", "false"))
                .andExpect(status().is3xxRedirection());
        assertThat(guardStore.find(gid).orElseThrow().enabled()).isFalse();

        mvc.perform(post("/schedule/guards/" + gid + "/delete").with(csrf()))
                .andExpect(status().is3xxRedirection());
        assertThat(guardStore.find(gid)).isEmpty();
    }

    @Test
    void blockOnFailStopsBeforeStartWhenSshFailsAndHostKeyConfirmWorks() throws Exception {
        SshConnector sshMock = mock(SshConnector.class);
        ScheduleGuardService svc = new ScheduleGuardService(guardStore, prometheusClient, settings, sshMock, sshCredentials, auditService);

        long gid = guardService.create(new ScheduleGuardStore.GuardInput(
                "Критическая очистка", true, "disk_free_gb", ScheduleGuardStore.Cmp.LT, 10.0, "ГБ",
                ScheduleGuardStore.RunWhen.BEFORE_START, "10.0.0.99", 22, credId, false,
                "cleanup.sh", 30, 0, true), "admin");

        // Свободно 4 ГБ (< 10 ГБ), SSH возвращает код 1 -> runBeforeStartGuards блокирует старт
        prom.respond((q, t) -> Map.of("root", 4.0));
        SshConnection conn = mock(SshConnection.class);
        when(sshMock.open(eq("10.0.0.99"), eq(22), any(), any(Duration.class))).thenReturn(conn);
        when(conn.exec(anyString(), any(Duration.class)))
                .thenReturn(new SshConnection.ExecResult(1, "", "permission denied"));

        var res = svc.runBeforeStartGuards(77L);
        assertThat(res.blocked()).isTrue();
        assertThat(res.reason()).contains("Критическая очистка").contains("кодом 1");

        // Неподтверждённый ключ хоста -> возвращается в Outcome -> подтверждение через endpoint сохраняет в known_host
        when(sshMock.open(eq("10.0.0.99"), eq(22), any(), any(Duration.class)))
                .thenThrow(new SshExceptions.HostKeyUnconfirmed("10.0.0.99", 22, "ssh-ed25519", "SHA256:abc123", "AAAAC3Nza"));
        var hkOut = svc.checkAndRun(gid, true, true, true, "test-hk");
        assertThat(hkOut.unconfirmedHostKey()).isNotNull();
        assertThat(hkOut.unconfirmedHostKey().fingerprint()).isEqualTo("SHA256:abc123");

        mvc.perform(post("/schedule/guards/confirm-host-key").with(csrf())
                        .param("host", "10.0.0.99")
                        .param("port", "22")
                        .param("keyType", "ssh-ed25519")
                        .param("fingerprint", "SHA256:abc123")
                        .param("publicKey", "AAAAC3Nza"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/schedule#guards"));

        assertThat(knownHosts.find("10.0.0.99", 22)).isPresent();
    }

    @Test
    void testPromqlAndMultiServerSshDryRunAndPartialFailure() throws Exception {
        SshConnector sshMock = mock(SshConnector.class);
        ScheduleGuardService svc = new ScheduleGuardService(guardStore, prometheusClient, settings, sshMock, sshCredentials, auditService);

        // 1. Тестовая проверка PromQL через HTTP JSON endpoint (до сохранения правила)
        prom.respond((q, t) -> Map.of("srv1", 62.0, "srv2", 88.5));
        String promJson = mvc.perform(post("/schedule/guards/test-promql").with(csrf())
                        .param("promql", "disk_used_pct")
                        .param("cmp", "GT")
                        .param("threshold", "80")
                        .param("unit", "%"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(promJson)
                .contains("\"ok\":true")
                .contains("\"triggered\":true")
                .contains("88.50 %")
                .contains("srv2");

        // 2. Выполнение скрипта на списке серверов (переносы строк + запятые + свой порт 2222)
        SshConnection c1 = mock(SshConnection.class);
        SshConnection c2 = mock(SshConnection.class);
        SshConnection c3 = mock(SshConnection.class);
        when(sshMock.open(eq("10.0.0.10"), eq(22), any(), any(Duration.class))).thenReturn(c1);
        when(sshMock.open(eq("10.0.0.11"), eq(2222), any(), any(Duration.class))).thenReturn(c2);
        when(sshMock.open(eq("10.0.0.12"), eq(22), any(), any(Duration.class))).thenReturn(c3);
        when(c1.exec(anyString(), any(Duration.class))).thenReturn(new SshConnection.ExecResult(0, "cleaned srv1", ""));
        when(c2.exec(anyString(), any(Duration.class))).thenReturn(new SshConnection.ExecResult(1, "", "disk busy"));
        when(c3.exec(anyString(), any(Duration.class))).thenReturn(new SshConnection.ExecResult(0, "cleaned srv3", ""));

        var testRes = svc.testSsh("10.0.0.10\n10.0.0.11:2222, 10.0.0.12", 22, credId, false, "echo clean", 30, "admin");
        assertThat(testRes.ok()).isFalse();
        assertThat(testRes.hosts()).hasSize(3);
        assertThat(testRes.hosts().get(0).ok()).isTrue();
        assertThat(testRes.hosts().get(0).port()).isEqualTo(22);
        assertThat(testRes.hosts().get(1).ok()).isFalse();
        assertThat(testRes.hosts().get(1).port()).isEqualTo(2222);
        assertThat(testRes.hosts().get(2).ok()).isTrue();
        assertThat(testRes.stdout()).contains("10.0.0.10").contains("10.0.0.11:2222").contains("10.0.0.12");

        // Все три сервера успешно -> ok=true
        when(c2.exec(anyString(), any(Duration.class))).thenReturn(new SshConnection.ExecResult(0, "cleaned srv2", ""));
        var okAll = svc.testSsh("10.0.0.10\n10.0.0.11:2222\n10.0.0.12", 22, credId, false, "echo clean", 30, "admin");
        assertThat(okAll.ok()).isTrue();
        assertThat(okAll.message()).contains("3/3");

        // Сохранение правила со списком серверов через форму и отображение hostSummary на /schedule
        mvc.perform(post("/schedule/guards").with(csrf())
                        .param("name", "Очистка кластера")
                        .param("enabled", "true")
                        .param("promql", "disk_used_pct")
                        .param("cmp", "GT")
                        .param("threshold", "80")
                        .param("unit", "%")
                        .param("runWhen", "BOTH")
                        .param("sshHost", "10.0.0.10\n10.0.0.11:2222, 10.0.0.12")
                        .param("sshPort", "22")
                        .param("sshCredentialId", String.valueOf(credId))
                        .param("script", "echo clean"))
                .andExpect(status().is3xxRedirection());

        String html = mvc.perform(get("/schedule"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(html).contains("3 серв.: 10.0.0.10, 10.0.0.11:2222, 10.0.0.12");

        // JSON подтверждение ключа хоста из модалки
        String hkJson = mvc.perform(post("/schedule/guards/confirm-host-key-json").with(csrf())
                        .param("host", "10.0.0.11")
                        .param("port", "2222")
                        .param("keyType", "ssh-ed25519")
                        .param("fingerprint", "SHA256:multi123")
                        .param("publicKey", "AAAAC3NzaMulti"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(hkJson).contains("\"ok\":true");
        assertThat(knownHosts.find("10.0.0.11", 2222)).isPresent();
    }

    @Test
    @WithMockUser(roles = "ENGINEER")
    void nonAdminCannotModifyOrRunGuards() throws Exception {
        mvc.perform(post("/schedule/guards").with(csrf())
                        .param("name", "Unauthorized")
                        .param("promql", "up")
                        .param("threshold", "1")
                        .param("sshHost", "10.0.0.1")
                        .param("sshCredentialId", String.valueOf(credId))
                        .param("script", "echo hi"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/schedule/guards/test-promql").with(csrf())
                        .param("promql", "up").param("threshold", "1"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/schedule/guards/test-ssh").with(csrf())
                        .param("sshHost", "10.0.0.1").param("script", "echo hi"))
                .andExpect(status().isForbidden());
    }
}
