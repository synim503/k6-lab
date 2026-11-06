package ru.k6lab.server.tuning;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** Хранилище профилей хостов и записей о применённом тюнинге (прямой JDBC: записи небольшие, JSON-поля читаются как есть). */
@Repository
public class HostTuningStore {

    public record Profile(long id, long generatorId, String generatorName, Instant startedAt, Instant finishedAt, int durationSec,
                          String status, String error, JsonNode metrics, JsonNode findings) {
    }

    public record Tuning(long id, long generatorId, String generatorName, Instant appliedAt, String appliedBy, Instant rolledBackAt,
                         String status, JsonNode changes, String error) {
    }

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public HostTuningStore(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    // ------------------------------------------------------------------ профили

    public long createProfile(long generatorId, String name, int durationSec) {
        return jdbc.queryForObject("insert into host_profile (generator_id, generator_name, duration_sec, status) values (?, ?, ?, 'RUNNING') returning id",
                Long.class, generatorId, name, durationSec);
    }

    public void finishProfile(long id, String status, String error, Object metrics, Object findings) {
        jdbc.update("update host_profile set finished_at = now(), status = ?, error = ?, metrics = ?::jsonb, findings = ?::jsonb where id = ?",
                status, error, metrics == null ? null : json(metrics), findings == null ? null : json(findings), id);
    }

    /** Профиль, который завис в RUNNING (перезапуск админки во время измерения), считается неудачным. */
    public void failStaleProfiles() {
        jdbc.update("update host_profile set status = 'FAILED', finished_at = now(), error = 'Прервано перезапуском админки' where status = 'RUNNING'");
    }

    public Optional<Profile> profile(long id) {
        return jdbc.query("select * from host_profile where id = ?", PROFILE, id).stream().findFirst();
    }

    /** Последний профиль каждого генератора. */
    public List<Profile> latestProfiles() {
        return jdbc.query("select p.* from host_profile p where p.id = (select max(x.id) from host_profile x where x.generator_id = p.generator_id)", PROFILE);
    }

    public Optional<Profile> latestDone(long generatorId) {
        return jdbc.query("select * from host_profile where generator_id = ? and status = 'DONE' order by id desc limit 1", PROFILE, generatorId).stream().findFirst();
    }

    // ------------------------------------------------------------------ тюнинг

    public long insertTuning(long generatorId, String name, String user, String status, Object changes, String error) {
        return jdbc.queryForObject("insert into host_tuning (generator_id, generator_name, applied_by, status, changes, error) values (?, ?, ?, ?, ?::jsonb, ?) returning id",
                Long.class, generatorId, name, user, status, json(changes), error);
    }

    /** Действующие (не откатанные) применения тюнинга генератора, от старых к новым. */
    public List<Tuning> activeTunings(long generatorId) {
        return jdbc.query("select * from host_tuning where generator_id = ? and status = 'APPLIED' order by id", TUNING, generatorId);
    }

    public void markRolledBack(long generatorId) {
        jdbc.update("update host_tuning set status = 'ROLLED_BACK', rolled_back_at = now() where generator_id = ? and status = 'APPLIED'", generatorId);
    }

    /** Новое применение заменяет прежние (исходные значения перенесены в него): прежние записи остаются в истории. */
    public void markReplaced(long generatorId) {
        jdbc.update("update host_tuning set status = 'REPLACED' where generator_id = ? and status = 'APPLIED'", generatorId);
    }

    /** Последняя запись о тюнинге каждого генератора (для списка). */
    public List<Tuning> latestTunings() {
        return jdbc.query("select t.* from host_tuning t where t.id = (select max(x.id) from host_tuning x where x.generator_id = t.generator_id)", TUNING);
    }

    public List<Tuning> history(int limit) {
        return jdbc.query("select * from host_tuning order by id desc limit ?", TUNING, limit);
    }

    // ------------------------------------------------------------------ JSON

    private String json(Object o) {
        try {
            return mapper.writeValueAsString(o);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private JsonNode node(String s) {
        try {
            return s == null ? null : mapper.readTree(s);
        } catch (JsonProcessingException e) {
            return null;
        }
    }

    private final RowMapper<Profile> PROFILE = (ResultSet rs, int i) -> profile(rs);
    private final RowMapper<Tuning> TUNING = (ResultSet rs, int i) -> tuning(rs);

    private Profile profile(ResultSet rs) throws SQLException {
        return new Profile(rs.getLong("id"), rs.getLong("generator_id"), rs.getString("generator_name"),
                rs.getTimestamp("started_at").toInstant(), instant(rs, "finished_at"), rs.getInt("duration_sec"),
                rs.getString("status"), rs.getString("error"), node(rs.getString("metrics")), node(rs.getString("findings")));
    }

    private Tuning tuning(ResultSet rs) throws SQLException {
        return new Tuning(rs.getLong("id"), rs.getLong("generator_id"), rs.getString("generator_name"),
                rs.getTimestamp("applied_at").toInstant(), rs.getString("applied_by"), instant(rs, "rolled_back_at"),
                rs.getString("status"), node(rs.getString("changes")), rs.getString("error"));
    }

    private static Instant instant(ResultSet rs, String col) throws SQLException {
        var ts = rs.getTimestamp(col);
        return ts == null ? null : ts.toInstant();
    }
}
