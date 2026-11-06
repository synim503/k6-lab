package ru.k6lab.server.tuning;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Профили оптимизации: именованные наборы настроек хоста, сохраняются для повторной раскатки и редактирования. */
@Repository
public class TuningProfileStore {

    public record OptProfile(long id, String name, String description, Map<String, String> settings, String createdBy,
                             Instant createdAt, Instant updatedAt) {
    }

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public TuningProfileStore(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    public List<OptProfile> list() {
        return jdbc.query("select * from tuning_profile order by lower(name)", mapperRow());
    }

    public Optional<OptProfile> find(long id) {
        return jdbc.query("select * from tuning_profile where id = ?", mapperRow(), id).stream().findFirst();
    }

    public long create(String name, String description, Map<String, String> settings, String user) {
        return jdbc.queryForObject("insert into tuning_profile (name, description, settings, created_by) values (?, ?, ?::jsonb, ?) returning id",
                Long.class, name, description, json(settings), user);
    }

    public void update(long id, String name, String description, Map<String, String> settings) {
        jdbc.update("update tuning_profile set name = ?, description = ?, settings = ?::jsonb, updated_at = now() where id = ?",
                name, description, json(settings), id);
    }

    public void delete(long id) {
        jdbc.update("delete from tuning_profile where id = ?", id);
    }

    public boolean nameTaken(String name, Long exceptId) {
        Integer n = jdbc.queryForObject("select count(*) from tuning_profile where lower(name) = lower(?) and id <> ?", Integer.class, name,
                exceptId == null ? -1L : exceptId);
        return n != null && n > 0;
    }

    private String json(Object o) {
        try {
            return mapper.writeValueAsString(o);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private RowMapper<OptProfile> mapperRow() {
        return (rs, i) -> {
            Map<String, String> settings;
            try {
                settings = mapper.readValue(rs.getString("settings"), new TypeReference<LinkedHashMap<String, String>>() { });
            } catch (JsonProcessingException e) {
                settings = new LinkedHashMap<>();
            }
            return new OptProfile(rs.getLong("id"), rs.getString("name"), rs.getString("description"), settings, rs.getString("created_by"),
                    rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("updated_at").toInstant());
        };
    }
}
