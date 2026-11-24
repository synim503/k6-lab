package ru.k6lab.server.schedule;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/** Хранилище автопроверок планировщика (метрика Prometheus -> выполнение SSH-скрипта). */
@Repository
public class ScheduleGuardStore {

    public enum Cmp {
        GT(">"), GTE("≥"), LT("<"), LTE("≤");

        private final String symbol;

        Cmp(String symbol) {
            this.symbol = symbol;
        }

        public String symbol() {
            return symbol;
        }

        public boolean matches(double value, double threshold) {
            return switch (this) {
                case GT -> value > threshold;
                case GTE -> value >= threshold;
                case LT -> value < threshold;
                case LTE -> value <= threshold;
            };
        }

        /** true, если тревожное направление — рост метрики (> или >=); иначе снижение (< или <=). */
        public boolean triggersHigh() {
            return this == GT || this == GTE;
        }

        public static Cmp parse(String raw) {
            if (raw == null || raw.isBlank()) {
                return GT;
            }
            return switch (raw.trim().toUpperCase(Locale.ROOT)) {
                case "GT", ">" -> GT;
                case "GTE", ">=", "≥" -> GTE;
                case "LT", "<" -> LT;
                case "LTE", "<=", "≤" -> LTE;
                default -> throw new IllegalArgumentException("Неизвестное условие сравнения: " + raw);
            };
        }
    }

    public enum RunWhen {
        BOTH("После прогона и перед стартом"),
        AFTER_RUN("После каждого прогона"),
        BEFORE_START("Перед стартом из очереди");

        private final String title;

        RunWhen(String title) {
            this.title = title;
        }

        public String title() {
            return title;
        }

        public boolean onAfterRun() {
            return this == BOTH || this == AFTER_RUN;
        }

        public boolean onBeforeStart() {
            return this == BOTH || this == BEFORE_START;
        }

        public static RunWhen parse(String raw) {
            if (raw == null || raw.isBlank()) {
                return BOTH;
            }
            try {
                return RunWhen.valueOf(raw.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                return BOTH;
            }
        }
    }

    public enum LastStatus {
        OK("В норме"),
        EXECUTED("Выполнен SSH"),
        SSH_ERROR("Ошибка SSH"),
        PROM_ERROR("Ошибка Prometheus"),
        SKIPPED_COOLDOWN("Пауза (cooldown)");

        private final String title;

        LastStatus(String title) {
            this.title = title;
        }

        public String title() {
            return title;
        }
    }

    public record Guard(long id, String name, boolean enabled, String promql, Cmp cmp, double threshold, String unit,
                        RunWhen runWhen, String sshHost, int sshPort, long sshCredentialId, boolean useSudo,
                        String script, int timeoutSec, int cooldownSec, boolean blockOnFail,
                        Instant lastCheckedAt, Double lastValue, String lastSeries, Instant lastTriggeredAt,
                        LastStatus lastStatus, String lastOutput, String createdBy, Instant createdAt, Instant updatedAt) {

        public String formatThreshold() {
            String num = formatNum(threshold);
            return cmp.symbol() + " " + num + (unit == null || unit.isBlank() ? "" : " " + unit);
        }

        public String formatValue() {
            if (lastValue == null) {
                return "—";
            }
            return formatNum(lastValue) + (unit == null || unit.isBlank() ? "" : " " + unit);
        }

        public List<String> hostEntries() {
            if (sshHost == null || sshHost.isBlank()) {
                return List.of();
            }
            return java.util.Arrays.stream(sshHost.split("[\\r\\n,;\\s]+"))
                    .map(String::strip)
                    .filter(s -> !s.isEmpty())
                    .distinct()
                    .toList();
        }

        public String hostSummary() {
            List<String> list = hostEntries();
            if (list.isEmpty()) {
                return sshHost == null ? "" : sshHost;
            }
            if (list.size() == 1) {
                String h = list.get(0);
                return h.contains(":") || sshPort == 22 ? h : h + ":" + sshPort;
            }
            return list.size() + " серв.: " + String.join(", ", list);
        }

        public static String formatNum(double v) {
            if (Math.abs(v - Math.rint(v)) < 1e-9 && Math.abs(v) < 1e12) {
                return Long.toString(Math.round(v));
            }
            return String.format(Locale.ROOT, "%.2f", v);
        }
    }

    public record GuardInput(String name, boolean enabled, String promql, Cmp cmp, double threshold, String unit,
                             RunWhen runWhen, String sshHost, int sshPort, long sshCredentialId, boolean useSudo,
                             String script, int timeoutSec, int cooldownSec, boolean blockOnFail) {
    }

    private final JdbcTemplate jdbc;

    public ScheduleGuardStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private final RowMapper<Guard> rows = new RowMapper<>() {
        @Override
        public Guard mapRow(ResultSet rs, int rowNum) throws SQLException {
            Timestamp checked = rs.getTimestamp("last_checked_at");
            Timestamp triggered = rs.getTimestamp("last_triggered_at");
            String stRaw = rs.getString("last_status");
            LastStatus st = null;
            if (stRaw != null && !stRaw.isBlank()) {
                try {
                    st = LastStatus.valueOf(stRaw);
                } catch (IllegalArgumentException ignored) {
                }
            }
            Object rawVal = rs.getObject("last_value");
            Double val = rawVal instanceof Number n ? n.doubleValue() : null;
            return new Guard(
                    rs.getLong("id"),
                    rs.getString("name"),
                    rs.getBoolean("enabled"),
                    rs.getString("promql"),
                    Cmp.parse(rs.getString("cmp")),
                    rs.getDouble("threshold"),
                    rs.getString("unit"),
                    RunWhen.parse(rs.getString("run_when")),
                    rs.getString("ssh_host"),
                    rs.getInt("ssh_port"),
                    rs.getLong("ssh_credential_id"),
                    rs.getBoolean("use_sudo"),
                    rs.getString("script"),
                    rs.getInt("timeout_sec"),
                    rs.getInt("cooldown_sec"),
                    rs.getBoolean("block_on_fail"),
                    checked == null ? null : checked.toInstant(),
                    val,
                    rs.getString("last_series"),
                    triggered == null ? null : triggered.toInstant(),
                    st,
                    rs.getString("last_output"),
                    rs.getString("created_by"),
                    rs.getTimestamp("created_at").toInstant(),
                    rs.getTimestamp("updated_at").toInstant()
            );
        }
    };

    public List<Guard> list() {
        return jdbc.query("select * from schedule_guard order by enabled desc, id asc", rows);
    }

    public List<Guard> enabled() {
        return jdbc.query("select * from schedule_guard where enabled order by id asc", rows);
    }

    public Optional<Guard> find(long id) {
        List<Guard> found = jdbc.query("select * from schedule_guard where id = ?", rows, id);
        return found.isEmpty() ? Optional.empty() : Optional.of(found.get(0));
    }

    public long create(GuardInput in, String user) {
        return jdbc.queryForObject("""
                insert into schedule_guard (name, enabled, promql, cmp, threshold, unit, run_when,
                    ssh_host, ssh_port, ssh_credential_id, use_sudo, script, timeout_sec, cooldown_sec,
                    block_on_fail, created_by)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) returning id
                """, Long.class,
                in.name(), in.enabled(), in.promql(), in.cmp().name(), in.threshold(), in.unit(), in.runWhen().name(),
                in.sshHost(), in.sshPort(), in.sshCredentialId(), in.useSudo(), in.script(),
                in.timeoutSec(), in.cooldownSec(), in.blockOnFail(), user);
    }

    public boolean update(long id, GuardInput in) {
        return jdbc.update("""
                update schedule_guard
                set name = ?, enabled = ?, promql = ?, cmp = ?, threshold = ?, unit = ?, run_when = ?,
                    ssh_host = ?, ssh_port = ?, ssh_credential_id = ?, use_sudo = ?, script = ?,
                    timeout_sec = ?, cooldown_sec = ?, block_on_fail = ?, updated_at = now()
                where id = ?
                """,
                in.name(), in.enabled(), in.promql(), in.cmp().name(), in.threshold(), in.unit(), in.runWhen().name(),
                in.sshHost(), in.sshPort(), in.sshCredentialId(), in.useSudo(), in.script(),
                in.timeoutSec(), in.cooldownSec(), in.blockOnFail(), id) == 1;
    }

    public boolean setEnabled(long id, boolean enabled) {
        return jdbc.update("update schedule_guard set enabled = ?, updated_at = now() where id = ?", enabled, id) == 1;
    }

    public boolean delete(long id) {
        return jdbc.update("delete from schedule_guard where id = ?", id) == 1;
    }

    public boolean usesCredential(long credentialId) {
        Integer n = jdbc.queryForObject("select count(*) from schedule_guard where ssh_credential_id = ?", Integer.class, credentialId);
        return n != null && n > 0;
    }

    public void recordCheck(long id, Instant checkedAt, Double value, String series, Instant triggeredAt,
                            LastStatus status, String output) {
        String trimmedOutput = output == null ? null : (output.length() > 8000 ? output.substring(0, 8000) + "\n… (вывод обрезан)" : output);
        if (triggeredAt != null) {
            jdbc.update("""
                    update schedule_guard
                    set last_checked_at = ?, last_value = ?, last_series = ?, last_triggered_at = ?,
                        last_status = ?, last_output = ?
                    where id = ?
                    """, Timestamp.from(checkedAt), value, series, Timestamp.from(triggeredAt),
                    status == null ? null : status.name(), trimmedOutput, id);
        } else {
            jdbc.update("""
                    update schedule_guard
                    set last_checked_at = ?, last_value = ?, last_series = ?,
                        last_status = ?, last_output = ?
                    where id = ?
                    """, Timestamp.from(checkedAt), value, series,
                    status == null ? null : status.name(), trimmedOutput, id);
        }
    }
}
