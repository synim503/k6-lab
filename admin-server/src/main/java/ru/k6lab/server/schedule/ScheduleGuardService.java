package ru.k6lab.server.schedule;

import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import ru.k6lab.server.audit.AuditService;
import ru.k6lab.server.common.SecretMasker;
import ru.k6lab.server.metrics.PrometheusClient;
import ru.k6lab.server.run.RunOrchestrator;
import ru.k6lab.server.settings.SettingsService;
import ru.k6lab.server.ssh.ResolvedCredential;
import ru.k6lab.server.ssh.SshConnection;
import ru.k6lab.server.ssh.SshConnector;
import ru.k6lab.server.ssh.SshCredentialService;
import ru.k6lab.server.ssh.SshExceptions;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Автопроверки планировщика: запрос метрики из Prometheus и автоматическое выполнение SSH-скрипта
 * при срабатывании порога (например, очистка диска между прогонами в очереди).
 */
@Service
public class ScheduleGuardService {

    private static final Logger log = LoggerFactory.getLogger(ScheduleGuardService.class);
    private static final Duration SSH_CONNECT_TIMEOUT = Duration.ofSeconds(15);

    public record MetricEval(Double value, String series, boolean triggered, String error) {
    }

    public record Outcome(ScheduleGuardStore.Guard guard, ScheduleGuardStore.LastStatus status, Double value,
                          String series, boolean triggered, boolean sshExecuted, Integer exitCode, String output,
                          String message, SshExceptions.HostKeyUnconfirmed unconfirmedHostKey) {
        public boolean failed() {
            return status == ScheduleGuardStore.LastStatus.SSH_ERROR || status == ScheduleGuardStore.LastStatus.PROM_ERROR;
        }
    }

    public record BeforeStartResult(boolean blocked, String reason, int executedCount) {
    }

    private final ScheduleGuardStore store;
    private final PrometheusClient prom;
    private final SettingsService settings;
    private final SshConnector ssh;
    private final SshCredentialService credentials;
    private final AuditService audit;
    private final Object execLock = new Object();
    private final ExecutorService afterRunExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "schedule-guard-worker");
        t.setDaemon(true);
        return t;
    });

    public ScheduleGuardService(ScheduleGuardStore store, PrometheusClient prom, SettingsService settings,
                                SshConnector ssh, SshCredentialService credentials, AuditService audit) {
        this.store = store;
        this.prom = prom;
        this.settings = settings;
        this.ssh = ssh;
        this.credentials = credentials;
        this.audit = audit;
    }

    @PreDestroy
    void shutdown() {
        afterRunExecutor.shutdownNow();
    }

    // ------------------------------------------------------------------ CRUD

    public List<ScheduleGuardStore.Guard> list() {
        return store.list();
    }

    public ScheduleGuardStore.Guard get(long id) {
        return store.find(id).orElseThrow(() -> new IllegalArgumentException("Автопроверка #" + id + " не найдена"));
    }

    public long create(ScheduleGuardStore.GuardInput in, String user) {
        ScheduleGuardStore.GuardInput clean = validate(in);
        long id = store.create(clean, user);
        audit.record("schedule.guard_create", "schedule_guard", id,
                Map.of("name", clean.name(), "host", clean.sshHost(), "by", String.valueOf(user)));
        return id;
    }

    public void update(long id, ScheduleGuardStore.GuardInput in, String user) {
        get(id);
        ScheduleGuardStore.GuardInput clean = validate(in);
        store.update(id, clean);
        audit.record("schedule.guard_update", "schedule_guard", id,
                Map.of("name", clean.name(), "host", clean.sshHost(), "by", String.valueOf(user)));
    }

    public void setEnabled(long id, boolean enabled, String user) {
        ScheduleGuardStore.Guard g = get(id);
        store.setEnabled(id, enabled);
        audit.record("schedule.guard_toggle", "schedule_guard", id,
                Map.of("name", g.name(), "enabled", enabled, "by", String.valueOf(user)));
    }

    public void delete(long id, String user) {
        ScheduleGuardStore.Guard g = get(id);
        store.delete(id);
        audit.record("schedule.guard_delete", "schedule_guard", id,
                Map.of("name", g.name(), "by", String.valueOf(user)));
    }

    private ScheduleGuardStore.GuardInput validate(ScheduleGuardStore.GuardInput in) {
        String name = in.name() == null ? "" : in.name().strip();
        if (name.isEmpty() || name.length() > 120) {
            throw new IllegalArgumentException("Название автопроверки: от 1 до 120 символов");
        }
        String promql = in.promql() == null ? "" : in.promql().strip();
        if (promql.isEmpty()) {
            throw new IllegalArgumentException("Укажите PromQL-запрос для проверки метрики");
        }
        if (!Double.isFinite(in.threshold())) {
            throw new IllegalArgumentException("Порог срабатывания должен быть числом");
        }
        String unit = in.unit() == null ? "" : in.unit().strip();
        if (unit.length() > 32) {
            throw new IllegalArgumentException("Единицы измерения: не более 32 символов");
        }
        String host = in.sshHost() == null ? "" : in.sshHost().strip();
        if (host.isEmpty() || host.length() > 255 || host.chars().anyMatch(Character::isWhitespace)) {
            throw new IllegalArgumentException("Укажите корректный хост или IP сервера для SSH (без пробелов)");
        }
        int port = in.sshPort() <= 0 ? 22 : in.sshPort();
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("SSH-порт: от 1 до 65535");
        }
        credentials.get(in.sshCredentialId());
        String script = in.script() == null ? "" : in.script().replace("\r\n", "\n").replace('\r', '\n').strip();
        if (script.isEmpty()) {
            throw new IllegalArgumentException("Укажите SSH-скрипт, который нужно выполнить при срабатывании");
        }
        int timeout = in.timeoutSec() <= 0 ? 120 : in.timeoutSec();
        if (timeout < 10 || timeout > 1800) {
            throw new IllegalArgumentException("Таймаут SSH-скрипта: от 10 до 1800 секунд");
        }
        int cooldown = Math.max(0, in.cooldownSec());
        if (cooldown > 86400) {
            throw new IllegalArgumentException("Пауза между повторными запусками (cooldown): от 0 до 86400 секунд");
        }
        return new ScheduleGuardStore.GuardInput(
                name, in.enabled(), promql,
                in.cmp() == null ? ScheduleGuardStore.Cmp.GT : in.cmp(),
                in.threshold(), unit,
                in.runWhen() == null ? ScheduleGuardStore.RunWhen.BOTH : in.runWhen(),
                host, port, in.sshCredentialId(), in.useSudo(), script, timeout, cooldown, in.blockOnFail()
        );
    }

    // ------------------------------------------------------------------ жизненный цикл планировщика

    /** После завершения любого прогона асинхронно проверяет правила с runWhen = AFTER_RUN или BOTH. */
    @EventListener
    public void onRunFinished(RunOrchestrator.RunFinished ev) {
        List<ScheduleGuardStore.Guard> list = store.enabled().stream()
                .filter(g -> g.runWhen().onAfterRun())
                .toList();
        if (list.isEmpty()) {
            return;
        }
        afterRunExecutor.submit(() -> {
            for (ScheduleGuardStore.Guard g : list) {
                try {
                    checkAndRun(g.id(), true, false, false, "after_run#" + ev.runId());
                } catch (RuntimeException e) {
                    log.warn("Автопроверка #{} («{}») после run #{} завершилась ошибкой: {}", g.id(), g.name(), ev.runId(), e.toString());
                }
            }
        });
    }

    /**
     * Перед стартом запланированного теста синхронно проверяет правила с runWhen = BEFORE_START или BOTH.
     * Если сработало правило, SSH-скрипт выполняется до старта теста; если включён blockOnFail и произошла ошибка —
     * возвращает blocked=true, чтобы планировщик встал на паузу и не запустил тест.
     */
    public BeforeStartResult runBeforeStartGuards(long scheduleId) {
        List<ScheduleGuardStore.Guard> list = store.enabled().stream()
                .filter(g -> g.runWhen().onBeforeStart())
                .toList();
        if (list.isEmpty()) {
            return new BeforeStartResult(false, null, 0);
        }
        int executed = 0;
        for (ScheduleGuardStore.Guard g : list) {
            Outcome out = checkAndRun(g.id(), true, false, false, "before_schedule#" + scheduleId);
            if (out.sshExecuted()) {
                executed++;
            }
            if (g.blockOnFail() && out.failed()) {
                return new BeforeStartResult(true, "Автопроверка «" + g.name() + "»: " + out.message(), executed);
            }
        }
        return new BeforeStartResult(false, null, executed);
    }

    // ------------------------------------------------------------------ проверка и выполнение

    /** Проверка метрики (и опциональный запуск SSH) для одного правила. */
    public Outcome checkAndRun(long guardId, boolean executeSshIfTriggered, boolean forceSsh,
                               boolean ignoreCooldown, String reason) {
        synchronized (execLock) {
            ScheduleGuardStore.Guard g = get(guardId);
            Instant now = Instant.now();

            Double metricVal = null;
            String series = null;
            boolean triggered = false;
            if (!forceSsh) {
                MetricEval eval = evaluateMetric(g, now);
                if (eval.error() != null) {
                    store.recordCheck(g.id(), now, null, null, null, ScheduleGuardStore.LastStatus.PROM_ERROR, eval.error());
                    log.warn("Автопроверка #{} («{}», {}): {}", g.id(), g.name(), reason, eval.error());
                    return new Outcome(g, ScheduleGuardStore.LastStatus.PROM_ERROR, null, null, false, false, null,
                            eval.error(), eval.error(), null);
                }
                metricVal = eval.value();
                series = eval.series();
                triggered = eval.triggered();

                if (!triggered) {
                    String msg = "Метрика в норме: " + formatVal(metricVal, g.unit())
                            + (series == null || series.isBlank() ? "" : " [" + series + "]")
                            + " (порог срабатывания " + g.formatThreshold() + ")";
                    store.recordCheck(g.id(), now, metricVal, series, null, ScheduleGuardStore.LastStatus.OK, msg);
                    return new Outcome(g, ScheduleGuardStore.LastStatus.OK, metricVal, series, false, false, null,
                            msg, msg, null);
                }

                if (!executeSshIfTriggered) {
                    String msg = "Порог превышен: " + formatVal(metricVal, g.unit())
                            + (series == null || series.isBlank() ? "" : " [" + series + "]")
                            + " (" + g.formatThreshold() + "). SSH-скрипт не запускался (режим проверки метрики).";
                    store.recordCheck(g.id(), now, metricVal, series, null, ScheduleGuardStore.LastStatus.OK, msg);
                    return new Outcome(g, ScheduleGuardStore.LastStatus.OK, metricVal, series, true, false, null,
                            msg, msg, null);
                }

                if (!ignoreCooldown && g.cooldownSec() > 0 && g.lastTriggeredAt() != null) {
                    long since = Duration.between(g.lastTriggeredAt(), now).getSeconds();
                    if (since >= 0 && since < g.cooldownSec()) {
                        long left = g.cooldownSec() - since;
                        String msg = "Порог сработал (" + formatVal(metricVal, g.unit()) + "), но действует пауза cooldown (осталось " + left + " с)";
                        store.recordCheck(g.id(), now, metricVal, series, null, ScheduleGuardStore.LastStatus.SKIPPED_COOLDOWN, msg);
                        return new Outcome(g, ScheduleGuardStore.LastStatus.SKIPPED_COOLDOWN, metricVal, series, true, false,
                                null, msg, msg, null);
                    }
                }
            } else {
                // Принудительный запуск SSH из UI: пробуем получить текущую метрику справочно, но не блокируемся, если Prometheus недоступен
                MetricEval eval = evaluateMetric(g, now);
                if (eval.error() == null) {
                    metricVal = eval.value();
                    series = eval.series();
                    triggered = eval.triggered();
                }
            }

            return runSshAndRecord(g, now, metricVal, series, triggered || forceSsh, reason);
        }
    }

    /** Оценивает PromQL-запрос и выбирает наихудшую серию для направления сравнения (> max, < min). */
    public MetricEval evaluateMetric(ScheduleGuardStore.Guard g, Instant at) {
        SettingsService.Prometheus p = settings.prometheus();
        if (p == null || !p.configured()) {
            return new MetricEval(null, null, false, "Prometheus не настроен (укажите URL в разделе «Настройки»)");
        }
        try {
            List<PrometheusClient.Sample> samples = prom.query(p, g.promql(), at);
            if (samples.isEmpty()) {
                return new MetricEval(null, null, false, "Prometheus не вернул данных по запросу: " + g.promql());
            }
            PrometheusClient.Sample worst = samples.get(0);
            boolean high = g.cmp().triggersHigh();
            for (int i = 1; i < samples.size(); i++) {
                PrometheusClient.Sample s = samples.get(i);
                if (high ? s.value() > worst.value() : s.value() < worst.value()) {
                    worst = s;
                }
            }
            String series = formatSeries(worst.labels());
            boolean triggered = g.cmp().matches(worst.value(), g.threshold());
            return new MetricEval(worst.value(), series, triggered, null);
        } catch (IOException | RuntimeException e) {
            return new MetricEval(null, null, false, "Ошибка запроса Prometheus: " + (e.getMessage() == null ? e.toString() : e.getMessage()));
        }
    }

    private Outcome runSshAndRecord(ScheduleGuardStore.Guard g, Instant now, Double metricVal, String series,
                                    boolean triggered, String reason) {
        ResolvedCredential cred;
        try {
            cred = credentials.resolve(g.sshCredentialId());
        } catch (RuntimeException e) {
            String err = "SSH-учётка недоступна: " + e.getMessage();
            store.recordCheck(g.id(), now, metricVal, series, now, ScheduleGuardStore.LastStatus.SSH_ERROR, err);
            return new Outcome(g, ScheduleGuardStore.LastStatus.SSH_ERROR, metricVal, series, triggered, false, null, err, err, null);
        }

        SecretMasker masker = maskerFor(cred);
        String script = g.script().replace("\r\n", "\n").replace('\r', '\n');
        Duration timeout = Duration.ofSeconds(Math.max(10, g.timeoutSec()));

        try (SshConnection conn = ssh.open(g.sshHost(), g.sshPort(), cred, SSH_CONNECT_TIMEOUT)) {
            SshConnection.ExecResult res = g.useSudo()
                    ? conn.sudo(script, timeout)
                    : conn.exec("sh -c " + SshConnection.quote(script), timeout);
            String combined = formatExecOutput(masker, res);
            ScheduleGuardStore.LastStatus st = res.ok()
                    ? ScheduleGuardStore.LastStatus.EXECUTED
                    : ScheduleGuardStore.LastStatus.SSH_ERROR;
            String summary = res.ok()
                    ? "SSH-скрипт выполнен на " + g.sshHost() + " (код 0)"
                            + (metricVal != null ? ", метрика " + formatVal(metricVal, g.unit()) : "")
                    : "SSH-скрипт на " + g.sshHost() + " завершился с кодом " + res.exitCode();
            store.recordCheck(g.id(), now, metricVal, series, now, st, combined);
            recordAudit(g, st, metricVal, res.exitCode(), reason);
            log.info("Автопроверка #{} («{}», {}): {}", g.id(), g.name(), reason, summary);
            return new Outcome(g, st, metricVal, series, triggered, true, res.exitCode(), combined, summary, null);
        } catch (SshExceptions.HostKeyUnconfirmed e) {
            String err = masker.mask(e.getMessage());
            store.recordCheck(g.id(), now, metricVal, series, now, ScheduleGuardStore.LastStatus.SSH_ERROR, err);
            recordAudit(g, ScheduleGuardStore.LastStatus.SSH_ERROR, metricVal, null, reason);
            log.warn("Автопроверка #{} («{}»): {}", g.id(), g.name(), err);
            return new Outcome(g, ScheduleGuardStore.LastStatus.SSH_ERROR, metricVal, series, triggered, false, null, err, err, e);
        } catch (RuntimeException e) {
            String err = masker.mask(e.getMessage() == null ? e.toString() : e.getMessage());
            store.recordCheck(g.id(), now, metricVal, series, now, ScheduleGuardStore.LastStatus.SSH_ERROR, err);
            recordAudit(g, ScheduleGuardStore.LastStatus.SSH_ERROR, metricVal, null, reason);
            log.warn("Автопроверка #{} («{}», {}): ошибка SSH на {}: {}", g.id(), g.name(), reason, g.sshHost(), err);
            return new Outcome(g, ScheduleGuardStore.LastStatus.SSH_ERROR, metricVal, series, triggered, false, null, err, err, null);
        }
    }

    private void recordAudit(ScheduleGuardStore.Guard g, ScheduleGuardStore.LastStatus st, Double val, Integer code, String reason) {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("name", g.name());
        d.put("host", g.sshHost());
        d.put("status", st.name());
        d.put("reason", reason);
        if (val != null) {
            d.put("value", val);
        }
        if (code != null) {
            d.put("exitCode", code);
        }
        audit.record("schedule.guard_exec", "schedule_guard", g.id(), d);
    }

    private static SecretMasker maskerFor(ResolvedCredential c) {
        SecretMasker m = new SecretMasker();
        ResolvedCredential cur = c;
        while (cur != null) {
            m.add(cur.secret(), cur.passphrase(), cur.sudoPassword());
            cur = cur.jump();
        }
        return m;
    }

    private static String formatExecOutput(SecretMasker masker, SshConnection.ExecResult res) {
        StringBuilder sb = new StringBuilder();
        sb.append("exit=").append(res.exitCode());
        String out = res.stdout() == null ? "" : masker.mask(res.stdout()).strip();
        String err = res.stderr() == null ? "" : masker.mask(res.stderr()).strip();
        if (!out.isEmpty()) {
            sb.append("\n--- stdout ---\n").append(out);
        }
        if (!err.isEmpty()) {
            sb.append("\n--- stderr ---\n").append(err);
        }
        return sb.toString();
    }

    private static String formatSeries(Map<String, String> labels) {
        if (labels == null || labels.isEmpty()) {
            return "";
        }
        List<String> parts = new ArrayList<>();
        for (String key : List.of("instance", "host", "mountpoint", "device", "pod", "job")) {
            if (labels.containsKey(key)) {
                parts.add(key + "=" + labels.get(key));
            }
        }
        if (parts.isEmpty()) {
            labels.forEach((k, v) -> {
                if (!"__name__".equals(k) && parts.size() < 4) {
                    parts.add(k + "=" + v);
                }
            });
        }
        return String.join(", ", parts);
    }

    private static String formatVal(Double v, String unit) {
        if (v == null) {
            return "—";
        }
        return ScheduleGuardStore.Guard.formatNum(v) + (unit == null || unit.isBlank() ? "" : " " + unit);
    }
}
