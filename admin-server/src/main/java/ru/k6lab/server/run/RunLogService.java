package ru.k6lab.server.run;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import ru.k6lab.core.protocol.Messages;
import ru.k6lab.server.settings.SettingsService;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Логи run. Выбор хранилища: партиционированная по дням таблица run_log. Поток ограничен лимитом агента
 * (200 строк/с на генератор), пакетная запись JdbcTemplate.batchUpdate раз в секунду. Retention - DROP PARTITION.
 * Для live-стрима есть in-memory буфер (см. {@link RunEventBroadcaster}), БД на каждое событие не читается.
 */
@Service
public class RunLogService {

    private static final Logger log = LoggerFactory.getLogger(RunLogService.class);
    private static final Pattern LEVEL = Pattern.compile("level=(debug|info|warn|warning|error|fatal)", Pattern.CASE_INSENSITIVE);
    private static final DateTimeFormatter PART = DateTimeFormatter.ofPattern("yyyyMMdd");

    public record Row(long runId, Long generatorId, Instant ts, String level, String line) {
    }

    private final JdbcTemplate jdbc;
    private final SettingsService settings;
    private final Queue<Row> queue = new ConcurrentLinkedQueue<>();

    public RunLogService(JdbcTemplate jdbc, SettingsService settings) {
        this.jdbc = jdbc;
        this.settings = settings;
    }

    /** Преобразует строки агента в записи лога (уровень определяется по k6-формату level=...). */
    public List<Row> add(long runId, Long generatorId, List<Messages.LogLine> lines) {
        List<Row> rows = new ArrayList<>(lines.size());
        for (Messages.LogLine l : lines) {
            Row r = new Row(runId, generatorId, Instant.ofEpochMilli(l.ts()), level(l), l.line());
            queue.add(r);
            rows.add(r);
        }
        return rows;
    }

    public Row addSystem(long runId, String level, String line) {
        Row r = new Row(runId, null, Instant.now(), level, line);
        queue.add(r);
        return r;
    }

    static String level(Messages.LogLine l) {
        Matcher m = LEVEL.matcher(l.line());
        if (m.find()) {
            String v = m.group(1).toLowerCase();
            return switch (v) {
                case "warn", "warning" -> "WARN";
                case "error", "fatal" -> "ERROR";
                case "debug" -> "DEBUG";
                default -> "INFO";
            };
        }
        return "INFO";
    }

    @Scheduled(fixedDelay = 1000)
    public void flush() {
        List<Row> batch = new ArrayList<>();
        Row r;
        while (batch.size() < 10_000 && (r = queue.poll()) != null) {
            batch.add(r);
        }
        if (batch.isEmpty()) {
            return;
        }
        try {
            jdbc.batchUpdate("insert into run_log (run_id, generator_id, ts, level, line) values (?, ?, ?, ?, ?)", batch, batch.size(),
                    (ps, row) -> {
                        ps.setLong(1, row.runId());
                        if (row.generatorId() == null) {
                            ps.setNull(2, java.sql.Types.BIGINT);
                        } else {
                            ps.setLong(2, row.generatorId());
                        }
                        ps.setTimestamp(3, Timestamp.from(row.ts()));
                        ps.setString(4, row.level());
                        ps.setString(5, row.line().replace("\u0000", ""));
                    });
        } catch (RuntimeException e) {
            log.warn("Не удалось записать {} строк лога: {}", batch.size(), e.toString());
        }
    }

    /** Последние N строк (для страницы, если run уже не в памяти). */
    public List<Row> tail(long runId, int limit) {
        flush();
        List<Row> rows = jdbc.query("select run_id, generator_id, ts, level, line from run_log where run_id = ? order by ts desc, id desc limit ?",
                (rs, i) -> new Row(rs.getLong(1), (Long) rs.getObject(2), rs.getTimestamp(3).toInstant(), rs.getString(4), rs.getString(5)), runId, limit);
        java.util.Collections.reverse(rows);
        return rows;
    }

    /** Полный лог потоком (скачивание): читаем из БД курсором, не загружая всё в память. */
    @org.springframework.transaction.annotation.Transactional(readOnly = true)
    public void writeFull(long runId, OutputStream out) throws IOException {
        flush();
        try {
            jdbc.query(con -> {
                var ps = con.prepareStatement("select ts, generator_id, level, line from run_log where run_id = ? order by ts, id",
                        java.sql.ResultSet.TYPE_FORWARD_ONLY, java.sql.ResultSet.CONCUR_READ_ONLY);
                ps.setLong(1, runId);
                ps.setFetchSize(2000);
                return ps;
            }, rs -> {
                try {
                    out.write((rs.getTimestamp(1).toInstant() + " [" + rs.getString(3) + "] gen=" + rs.getObject(2) + " "
                            + rs.getString(4) + "\n").getBytes(StandardCharsets.UTF_8));
                } catch (IOException e) {
                    throw new java.io.UncheckedIOException(e);
                }
            });
        } catch (java.io.UncheckedIOException e) {
            throw e.getCause();
        }
    }

    // ------------------------------------------------------------------ партиции и retention

    /** Создаёт партиции на ближайшие дни и удаляет старше retention. */
    @Scheduled(cron = "0 5 0 * * *")
    public void maintainPartitions() {
        ensurePartitions(LocalDate.now(ZoneOffset.UTC).minusDays(1), 14);
        dropOld(settings.logRetentionDays());
    }

    @org.springframework.context.event.EventListener(org.springframework.boot.context.event.ApplicationReadyEvent.class)
    public void onStart() {
        try {
            maintainPartitions();
        } catch (RuntimeException e) {
            log.warn("Не удалось обслужить партиции run_log: {}", e.toString());
        }
    }

    void ensurePartitions(LocalDate from, int days) {
        for (int i = 0; i <= days + 1; i++) {
            LocalDate d = from.plusDays(i);
            jdbc.execute("create table if not exists run_log_" + d.format(PART) + " partition of run_log for values from ('"
                    + d + "') to ('" + d.plusDays(1) + "')");
        }
    }

    int dropOld(int retentionDays) {
        LocalDate border = LocalDate.now(ZoneOffset.UTC).minusDays(retentionDays);
        List<String> parts = jdbc.queryForList("select c.relname from pg_inherits i join pg_class c on c.oid = i.inhrelid "
                + "join pg_class p on p.oid = i.inhparent where p.relname = 'run_log' and c.relname ~ '^run_log_[0-9]{8}$'", String.class);
        int dropped = 0;
        for (String name : parts) {
            LocalDate d = LocalDate.parse(name.substring("run_log_".length()), PART);
            if (d.isBefore(border)) {
                jdbc.execute("drop table if exists " + name);
                dropped++;
            }
        }
        jdbc.update("delete from run_log_default where ts < ?", Timestamp.from(Instant.now().minusSeconds(retentionDays * 86400L)));
        if (dropped > 0) {
            log.info("Удалено партиций логов старше {} дней: {}", retentionDays, dropped);
        }
        return dropped;
    }
}
