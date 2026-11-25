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

    public record SeriesPoint(String series, double value, String formattedValue, boolean triggered) {
    }

    public record PromqlTestResult(boolean ok, Double worstValue, String formattedValue, String worstSeries,
                                   boolean triggered, String thresholdText, List<SeriesPoint> samples,
                                   String message, String error) {
    }

    public record SshTarget(String host, int port) {
        public String label() {
            return port == 22 ? host : host + ":" + port;
        }
    }

    public record HostExecResult(String host, int port, boolean ok, Integer exitCode, long durationMs,
                                 String stdout, String stderr, String error,
                                 SshExceptions.HostKeyUnconfirmed unconfirmedHostKey) {
        public String label() {
            return port == 22 ? host : host + ":" + port;
        }
    }

    public record SshTestResult(boolean ok, Integer exitCode, long durationMs, String stdout, String stderr,
                                String message, String error, SshExceptions.HostKeyUnconfirmed unconfirmedHostKey,
                                List<HostExecResult> hosts) {
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
        int port = in.sshPort() <= 0 ? 22 : in.sshPort();
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("SSH-порт: от 1 до 65535");
        }
        List<SshTarget> targets = parseTargets(in.sshHost(), port);
        String normalizedHosts = formatTargets(targets, port);
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
                normalizedHosts, port, in.sshCredentialId(), in.useSudo(), script, timeout, cooldown, in.blockOnFail()
        );
    }

    /** Разбирает список серверов (по строкам, запятым, точкам с запятой или пробелам); поддерживает host и host:port. */
    public static List<SshTarget> parseTargets(String rawHosts, int defaultPort) {
        if (rawHosts == null || rawHosts.isBlank()) {
            throw new IllegalArgumentException("Укажите хотя бы один хост или IP сервера для SSH");
        }
        String[] tokens = rawHosts.split("[\\r\\n,;\\s]+");
        List<SshTarget> out = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (String raw : tokens) {
            String tok = raw.strip();
            if (tok.isEmpty()) {
                continue;
            }
            String host = tok;
            int port = defaultPort;
            int colon = tok.lastIndexOf(':');
            if (colon > 0 && tok.indexOf(':') == colon) {
                String pPart = tok.substring(colon + 1);
                try {
                    port = Integer.parseInt(pPart);
                    host = tok.substring(0, colon).strip();
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException("Некорректный порт в адресе «" + tok + "»");
                }
            }
            if (host.isEmpty() || host.length() > 255 || host.chars().anyMatch(Character::isWhitespace)) {
                throw new IllegalArgumentException("Некорректный хост: «" + tok + "»");
            }
            if (port < 1 || port > 65535) {
                throw new IllegalArgumentException("SSH-порт должен быть от 1 до 65535 («" + tok + "»)");
            }
            String key = host + ":" + port;
            if (seen.add(key)) {
                out.add(new SshTarget(host, port));
            }
        }
        if (out.isEmpty()) {
            throw new IllegalArgumentException("Укажите хотя бы один хост или IP сервера для SSH");
        }
        if (out.size() > 100) {
            throw new IllegalArgumentException("Слишком много серверов в одном правиле (максимум 100)");
        }
        return out;
    }

    private static String formatTargets(List<SshTarget> targets, int defaultPort) {
        return targets.stream()
                .map(t -> t.port() == defaultPort ? t.host() : t.host() + ":" + t.port())
                .collect(java.util.stream.Collectors.joining("\n"));
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

    /** Тестовая проверка PromQL-запроса прямо из модалки (до сохранения правила): возвращает все серии и вердикт по порогу. */
    public PromqlTestResult testPromql(String promql, ScheduleGuardStore.Cmp cmp, double threshold, String unit) {
        String q = promql == null ? "" : promql.strip();
        if (q.isEmpty()) {
            return new PromqlTestResult(false, null, null, null, false, "", List.of(), null, "Введите PromQL-запрос");
        }
        if (!Double.isFinite(threshold)) {
            return new PromqlTestResult(false, null, null, null, false, "", List.of(), null, "Порог должен быть числом");
        }
        ScheduleGuardStore.Cmp c = cmp == null ? ScheduleGuardStore.Cmp.GT : cmp;
        String u = unit == null ? "" : unit.strip();
        String thrText = c.symbol() + " " + ScheduleGuardStore.Guard.formatNum(threshold) + (u.isEmpty() ? "" : " " + u);
        SettingsService.Prometheus p = settings.prometheus();
        if (p == null || !p.configured()) {
            return new PromqlTestResult(false, null, null, null, false, thrText, List.of(), null,
                    "Prometheus не настроен (укажите URL в разделе «Настройки»)");
        }
        try {
            List<PrometheusClient.Sample> raw = prom.query(p, q, Instant.now());
            if (raw.isEmpty()) {
                return new PromqlTestResult(false, null, null, null, false, thrText, List.of(), null,
                        "Prometheus не вернул данных по запросу (пустой результат)");
            }
            boolean high = c.triggersHigh();
            List<PrometheusClient.Sample> sorted = new ArrayList<>(raw);
            sorted.sort((a, b) -> high ? Double.compare(b.value(), a.value()) : Double.compare(a.value(), b.value()));
            PrometheusClient.Sample worst = sorted.get(0);
            String worstSeries = formatSeries(worst.labels());
            boolean triggered = c.matches(worst.value(), threshold);
            String formattedWorst = formatVal(worst.value(), u);
            List<SeriesPoint> pts = new ArrayList<>();
            int limit = Math.min(sorted.size(), 12);
            for (int i = 0; i < limit; i++) {
                var s = sorted.get(i);
                pts.add(new SeriesPoint(formatSeries(s.labels()), s.value(), formatVal(s.value(), u), c.matches(s.value(), threshold)));
            }
            String msg = triggered
                    ? "Порог СРАБОТАЕТ: " + formattedWorst
                            + (worstSeries.isEmpty() ? "" : " [" + worstSeries + "]")
                            + " удовлетворяет условию " + thrText + " — SSH-скрипт будет выполнен."
                    : "В норме: " + formattedWorst
                            + (worstSeries.isEmpty() ? "" : " [" + worstSeries + "]")
                            + " (порог срабатывания " + thrText + ") — SSH-скрипт не нужен.";
            return new PromqlTestResult(true, worst.value(), formattedWorst, worstSeries, triggered, thrText, pts, msg, null);
        } catch (IOException | RuntimeException e) {
            return new PromqlTestResult(false, null, null, null, false, thrText, List.of(), null,
                    "Ошибка запроса Prometheus: " + (e.getMessage() == null ? e.toString() : e.getMessage()));
        }
    }

    /** Тестовый прогон SSH-скрипта прямо из модалки (до сохранения правила) на одном или списке серверов. */
    public SshTestResult testSsh(String sshHost, int sshPort, Long sshCredentialId, boolean useSudo,
                                 String scriptRaw, int timeoutSec, String user) {
        int port = sshPort <= 0 ? 22 : sshPort;
        if (port < 1 || port > 65535) {
            return new SshTestResult(false, null, 0, "", "", null, "SSH-порт: от 1 до 65535", null, List.of());
        }
        List<SshTarget> targets;
        try {
            targets = parseTargets(sshHost, port);
        } catch (IllegalArgumentException e) {
            return new SshTestResult(false, null, 0, "", "", null, e.getMessage(), null, List.of());
        }
        if (sshCredentialId == null) {
            return new SshTestResult(false, null, 0, "", "", null, "Выберите SSH-учётку", null, List.of());
        }
        String script = scriptRaw == null ? "" : scriptRaw.replace("\r\n", "\n").replace('\r', '\n').strip();
        if (script.isEmpty()) {
            return new SshTestResult(false, null, 0, "", "", null, "Введите SSH-скрипт для тестового прогона", null, List.of());
        }
        int tSec = timeoutSec <= 0 ? 120 : Math.max(10, Math.min(timeoutSec, 1800));
        ResolvedCredential cred;
        try {
            cred = credentials.resolve(sshCredentialId);
        } catch (RuntimeException e) {
            return new SshTestResult(false, null, 0, "", "", null, "SSH-учётка недоступна: " + e.getMessage(), null, List.of());
        }
        SecretMasker masker = maskerFor(cred);
        log.info("Тестовый прогон SSH-скрипта (пользователь {}, хостов: {}, sudo={}, timeout={} с)",
                user, targets.size(), useSudo, tSec);
        long totalStart = System.currentTimeMillis();
        List<HostExecResult> results = executeOnTargets(targets, cred, masker, useSudo, script, Duration.ofSeconds(tSec));
        long totalElapsed = System.currentTimeMillis() - totalStart;
        log.info("Тестовый прогон SSH-скрипта завершён за {} мс (успешно {}/{})",
                totalElapsed, results.stream().filter(HostExecResult::ok).count(), results.size());

        int okCount = (int) results.stream().filter(HostExecResult::ok).count();
        boolean allOk = okCount == results.size();
        Integer exitCode = results.size() == 1 ? results.get(0).exitCode() : Integer.valueOf(allOk ? 0 : 1);
        SshExceptions.HostKeyUnconfirmed firstHk = results.stream()
                .map(HostExecResult::unconfirmedHostKey).filter(java.util.Objects::nonNull).findFirst().orElse(null);

        audit.record("schedule.guard_test_ssh", "schedule_guard", 0L,
                Map.of("hosts", targets.stream().map(SshTarget::label).toList(), "ok", okCount, "total", results.size(), "by", String.valueOf(user)));

        String msg;
        if (results.size() == 1) {
            HostExecResult r = results.get(0);
            if (r.ok()) {
                msg = "Скрипт выполнен успешно на " + r.label() + " (код 0, " + r.durationMs() + " мс)";
            } else if (r.exitCode() != null) {
                msg = "Скрипт на " + r.label() + " завершился с кодом " + r.exitCode() + " (" + r.durationMs() + " мс)";
            } else {
                msg = r.error();
            }
        } else {
            msg = allOk
                    ? "Скрипт выполнен успешно на всех серверах (" + okCount + "/" + results.size() + ", " + totalElapsed + " мс)"
                    : "Выполнено успешно: " + okCount + " из " + results.size() + " серверов (" + totalElapsed + " мс)";
        }

        String combinedOut = results.size() == 1 ? results.get(0).stdout() : formatMultiHostOutput(results);
        String combinedErr = results.size() == 1 ? results.get(0).stderr() : "";
        return new SshTestResult(allOk, exitCode, totalElapsed, combinedOut, combinedErr, msg, allOk ? null : msg, firstHk, results);
    }

    private List<HostExecResult> executeOnTargets(List<SshTarget> targets, ResolvedCredential cred, SecretMasker masker,
                                                  boolean useSudo, String script, Duration timeout) {
        if (targets.size() <= 1) {
            List<HostExecResult> out = new ArrayList<>(targets.size());
            for (SshTarget t : targets) {
                out.add(executeOnTarget(t, cred, masker, useSudo, script, timeout));
            }
            return out;
        }
        int poolSize = Math.min(targets.size(), 8);
        ExecutorService pool = Executors.newFixedThreadPool(poolSize, r -> {
            Thread th = new Thread(r, "schedule-guard-ssh");
            th.setDaemon(true);
            return th;
        });
        try {
            List<java.util.concurrent.Future<HostExecResult>> futures = new ArrayList<>(targets.size());
            for (SshTarget t : targets) {
                futures.add(pool.submit(() -> executeOnTarget(t, cred, masker, useSudo, script, timeout)));
            }
            List<HostExecResult> out = new ArrayList<>(targets.size());
            for (int i = 0; i < targets.size(); i++) {
                SshTarget t = targets.get(i);
                try {
                    out.add(futures.get(i).get());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    out.add(new HostExecResult(t.host(), t.port(), false, null, 0, "", "", "Выполнение прервано", null));
                } catch (java.util.concurrent.ExecutionException e) {
                    Throwable cause = e.getCause() == null ? e : e.getCause();
                    String msg = masker.mask(cause.getMessage() == null ? cause.toString() : cause.getMessage());
                    out.add(new HostExecResult(t.host(), t.port(), false, null, 0, "", "", msg, null));
                }
            }
            return out;
        } finally {
            pool.shutdownNow();
        }
    }

    private HostExecResult executeOnTarget(SshTarget t, ResolvedCredential cred, SecretMasker masker,
                                           boolean useSudo, String script, Duration timeout) {
        long started = System.currentTimeMillis();
        try (SshConnection conn = ssh.open(t.host(), t.port(), cred, SSH_CONNECT_TIMEOUT)) {
            SshConnection.ExecResult res = useSudo
                    ? conn.sudo(script, timeout)
                    : conn.exec("sh -c " + SshConnection.quote(script), timeout);
            long elapsed = System.currentTimeMillis() - started;
            String stdout = truncateOutput(res.stdout() == null ? "" : masker.mask(res.stdout()).strip());
            String stderr = truncateOutput(res.stderr() == null ? "" : masker.mask(res.stderr()).strip());
            String err = res.ok() ? null : "код выхода " + res.exitCode();
            return new HostExecResult(t.host(), t.port(), res.ok(), res.exitCode(), elapsed, stdout, stderr, err, null);
        } catch (SshExceptions.HostKeyUnconfirmed e) {
            long elapsed = System.currentTimeMillis() - started;
            return new HostExecResult(t.host(), t.port(), false, null, elapsed, "", "", masker.mask(e.getMessage()), e);
        } catch (SshExceptions.CommandTimeout e) {
            long elapsed = System.currentTimeMillis() - started;
            String stdout = truncateOutput(masker.mask(e.stdout()).strip());
            String stderr = truncateOutput(masker.mask(e.stderr()).strip());
            return new HostExecResult(t.host(), t.port(), false, null, elapsed, stdout, stderr, masker.mask(e.getMessage()), null);
        } catch (RuntimeException e) {
            long elapsed = System.currentTimeMillis() - started;
            String msg = masker.mask(e.getMessage() == null ? e.toString() : e.getMessage());
            return new HostExecResult(t.host(), t.port(), false, null, elapsed, "", "", msg, null);
        }
    }

    private static String formatMultiHostOutput(List<HostExecResult> results) {
        StringBuilder sb = new StringBuilder();
        for (HostExecResult r : results) {
            if (sb.length() > 0) {
                sb.append("\n\n");
            }
            sb.append("=== ").append(r.label()).append(" (")
                    .append(r.ok() ? "OK, exit=0" : (r.exitCode() != null ? "ОШИБКА, exit=" + r.exitCode() : "ОШИБКА"))
                    .append(", ").append(r.durationMs()).append(" мс) ===");
            if (r.error() != null && r.exitCode() == null) {
                sb.append("\n").append(r.error());
            }
            if (r.stdout() != null && !r.stdout().isBlank()) {
                sb.append("\n--- stdout ---\n").append(r.stdout());
            }
            if (r.stderr() != null && !r.stderr().isBlank()) {
                sb.append("\n--- stderr ---\n").append(r.stderr());
            }
        }
        return truncateOutput(sb.toString());
    }

    private static String truncateOutput(String s) {
        if (s == null || s.length() <= 8000) {
            return s == null ? "" : s;
        }
        return s.substring(0, 8000) + "\n… (вывод обрезан)";
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

        List<SshTarget> targets;
        try {
            targets = parseTargets(g.sshHost(), g.sshPort());
        } catch (IllegalArgumentException e) {
            String err = e.getMessage();
            store.recordCheck(g.id(), now, metricVal, series, now, ScheduleGuardStore.LastStatus.SSH_ERROR, err);
            return new Outcome(g, ScheduleGuardStore.LastStatus.SSH_ERROR, metricVal, series, triggered, false, null, err, err, null);
        }

        SecretMasker masker = maskerFor(cred);
        String script = g.script().replace("\r\n", "\n").replace('\r', '\n');
        Duration timeout = Duration.ofSeconds(Math.max(10, g.timeoutSec()));

        List<HostExecResult> results = executeOnTargets(targets, cred, masker, g.useSudo(), script, timeout);
        int okCount = (int) results.stream().filter(HostExecResult::ok).count();
        boolean allOk = okCount == results.size();
        boolean anyExec = results.stream().anyMatch(r -> r.exitCode() != null);
        SshExceptions.HostKeyUnconfirmed firstHk = results.stream()
                .map(HostExecResult::unconfirmedHostKey).filter(java.util.Objects::nonNull).findFirst().orElse(null);

        String combined = results.size() == 1 && results.get(0).exitCode() != null
                ? formatExecOutput(masker, new SshConnection.ExecResult(results.get(0).exitCode(), results.get(0).stdout(), results.get(0).stderr()))
                : (results.size() == 1 ? results.get(0).error() : formatMultiHostOutput(results));

        ScheduleGuardStore.LastStatus st = allOk
                ? ScheduleGuardStore.LastStatus.EXECUTED
                : ScheduleGuardStore.LastStatus.SSH_ERROR;
        Integer exitCode = results.size() == 1 ? results.get(0).exitCode() : Integer.valueOf(allOk ? 0 : 1);

        String summary;
        if (results.size() == 1) {
            HostExecResult r = results.get(0);
            if (r.ok()) {
                summary = "SSH-скрипт выполнен на " + r.label() + " (код 0)"
                        + (metricVal != null ? ", метрика " + formatVal(metricVal, g.unit()) : "");
            } else if (r.exitCode() != null) {
                summary = "SSH-скрипт на " + r.label() + " завершился с кодом " + r.exitCode();
            } else {
                summary = r.error();
            }
        } else {
            summary = allOk
                    ? "SSH-скрипт выполнен на всех серверах (" + okCount + "/" + results.size() + ")"
                            + (metricVal != null ? ", метрика " + formatVal(metricVal, g.unit()) : "")
                    : "SSH-скрипт: успешно на " + okCount + " из " + results.size() + " серверов";
        }

        store.recordCheck(g.id(), now, metricVal, series, now, st, combined);
        recordAudit(g, st, metricVal, exitCode, reason);
        if (allOk) {
            log.info("Автопроверка #{} («{}», {}): {}", g.id(), g.name(), reason, summary);
        } else {
            log.warn("Автопроверка #{} («{}», {}): {}", g.id(), g.name(), reason, summary);
        }
        return new Outcome(g, st, metricVal, series, triggered, anyExec, exitCode, combined, summary, firstHk);
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
