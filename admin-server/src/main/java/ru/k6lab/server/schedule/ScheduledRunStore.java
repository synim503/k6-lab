package ru.k6lab.server.schedule;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Хранилище запланированных запусков (прямой JDBC: записи небольшие, переменные и интенсивность - JSON). */
@Repository
public class ScheduledRunStore {

    public enum Status { PENDING, STARTING, STARTED, FAILED, CANCELLED }

    /** env == null - переменные как в скрипте на момент запуска; intensity - проценты по операциям (только отличные от 100). */
    public record Item(long id, long scriptId, String scriptName, Instant runAt, Instant createdAt, String createdBy, List<Long> generatorIds,
                       Integer generatorCount, Map<String, String> env, Map<String, Double> intensity, Status status, Long runId,
                       String error, Instant finishedAt, Long profileId, String profileName, boolean shiftForward) {

        public boolean pending() {
            return status == Status.PENDING;
        }
    }

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public ScheduledRunStore(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    private final RowMapper<Item> rows = new RowMapper<>() {
        @Override
        public Item mapRow(ResultSet rs, int n) throws SQLException {
            Timestamp fin = rs.getTimestamp("finished_at");
            return new Item(rs.getLong("id"), rs.getLong("script_id"), rs.getString("script_name"), rs.getTimestamp("run_at").toInstant(),
                    rs.getTimestamp("created_at").toInstant(), rs.getString("created_by"),
                    read(rs.getString("generator_ids"), new TypeReference<List<Long>>() { }),
                    rs.getObject("generator_count") == null ? null : rs.getInt("generator_count"),
                    rs.getString("env") == null ? null : read(rs.getString("env"), new TypeReference<Map<String, String>>() { }),
                    read(rs.getString("intensity"), new TypeReference<Map<String, Double>>() { }),
                    Status.valueOf(rs.getString("status")), rs.getObject("run_id") == null ? null : rs.getLong("run_id"),
                    rs.getString("error"), fin == null ? null : fin.toInstant(),
                    rs.getObject("profile_id") == null ? null : rs.getLong("profile_id"), rs.getString("profile_name"),
                    rs.getBoolean("shift_forward"));
        }
    };

    private <T> T read(String json, TypeReference<T> type) {
        try {
            return mapper.readValue(json, type);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private String json(Object o) {
        try {
            return mapper.writeValueAsString(o);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    public long create(long scriptId, String scriptName, Instant runAt, String user, List<Long> generatorIds, Integer generatorCount,
                       Map<String, String> env, Map<String, Double> intensity, Long profileId, String profileName, boolean shiftForward) {
        return jdbc.queryForObject("insert into scheduled_run (script_id, script_name, run_at, created_by, generator_ids, generator_count, env, intensity, "
                        + "profile_id, profile_name, shift_forward) values (?, ?, ?, ?, ?::jsonb, ?, ?::jsonb, ?::jsonb, ?, ?, ?) returning id", Long.class, scriptId, scriptName,
                Timestamp.from(runAt), user, json(generatorIds), generatorCount, env == null ? null : json(env), json(intensity), profileId, profileName,
                shiftForward);
    }

    /** Ожидающие записи, которые можно сдвигать вперёд. */
    public List<Item> pendingShifting() {
        return jdbc.query("select * from scheduled_run where status = 'PENDING' and shift_forward order by run_at", rows);
    }

    /** Переносит ожидающую запись на другое время. */
    public boolean reschedule(long id, Instant runAt) {
        return jdbc.update("update scheduled_run set run_at = ? where id = ? and status = 'PENDING'", Timestamp.from(runAt), id) == 1;
    }

    public Optional<Item> find(long id) {
        return jdbc.query("select * from scheduled_run where id = ?", rows, id).stream().findFirst();
    }

    /** Все активные записи расписания (PENDING и STARTING) по времени запуска — без ограничения limit. */
    public List<Item> active() {
        return jdbc.query("select * from scheduled_run where status in ('PENDING', 'STARTING') order by run_at asc", rows);
    }

    /** Последние записи: сначала ожидающие по времени запуска, затем остальные от новых к старым. */
    public List<Item> recent(int limit) {
        return jdbc.query("select * from scheduled_run order by (status = 'PENDING') desc, "
                + "case when status = 'PENDING' then run_at end asc, run_at desc limit ?", rows, limit);
    }

    public List<Item> pendingOfScript(long scriptId) {
        return jdbc.query("select * from scheduled_run where script_id = ? and status = 'PENDING' order by run_at", rows, scriptId);
    }

    public List<Item> due(Instant now) {
        return jdbc.query("select * from scheduled_run where status = 'PENDING' and run_at <= ? order by run_at", rows, Timestamp.from(now));
    }

    /** Захват записи на запуск: true, только если она ещё ожидала (параллельно сработавший второй поток её не возьмёт). */
    public boolean claim(long id) {
        return jdbc.update("update scheduled_run set status = 'STARTING' where id = ? and status = 'PENDING'", id) == 1;
    }

    public boolean cancel(long id) {
        return jdbc.update("update scheduled_run set status = 'CANCELLED', finished_at = now() where id = ? and status = 'PENDING'", id) == 1;
    }

    public void started(long id, long runId) {
        jdbc.update("update scheduled_run set status = 'STARTED', run_id = ?, finished_at = now() where id = ?", runId, id);
    }

    public void failed(long id, String error) {
        jdbc.update("update scheduled_run set status = 'FAILED', error = ?, finished_at = now() where id = ?", error, id);
    }

    /** После рестарта сервера записи, застрявшие в STARTING (сервер упал посреди запуска), помечаются ошибкой. */
    public void failStarting() {
        jdbc.update("update scheduled_run set status = 'FAILED', error = 'Сервер перезапущен во время запуска: статус неизвестен, проверьте список запусков', "
                + "finished_at = now() where status = 'STARTING'");
    }
}
