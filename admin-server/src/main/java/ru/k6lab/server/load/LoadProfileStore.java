package ru.k6lab.server.load;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** Профили нагрузки скрипта: имя и JSON формы «Нагрузка» (операции, сценарии, SLA, переменные, файлы данных). */
@Repository
public class LoadProfileStore {

    public record Profile(long id, long scriptId, String name, String data, Instant createdAt, String createdBy, Instant updatedAt, String updatedBy) {
    }

    private static final RowMapper<Profile> ROWS = (rs, n) -> new Profile(rs.getLong("id"), rs.getLong("script_id"), rs.getString("name"),
            rs.getString("data"), rs.getTimestamp("created_at").toInstant(), rs.getString("created_by"),
            rs.getTimestamp("updated_at").toInstant(), rs.getString("updated_by"));

    private final JdbcTemplate jdbc;

    public LoadProfileStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<Profile> list(long scriptId) {
        return jdbc.query("select * from load_profile where script_id = ? order by lower(name)", ROWS, scriptId);
    }

    public Optional<Profile> find(long scriptId, long id) {
        return jdbc.query("select * from load_profile where script_id = ? and id = ?", ROWS, scriptId, id).stream().findFirst();
    }

    /** Новый профиль или обновление существующего с тем же именем; возвращает id и признак «обновлён». */
    public record Saved(long id, boolean updated) {
    }

    public Saved save(long scriptId, String name, String data, String user) {
        List<Long> existing = jdbc.queryForList("select id from load_profile where script_id = ? and name = ?", Long.class, scriptId, name);
        if (!existing.isEmpty()) {
            jdbc.update("update load_profile set data = ?, updated_at = now(), updated_by = ? where id = ?", data, user, existing.get(0));
            return new Saved(existing.get(0), true);
        }
        long id = jdbc.queryForObject("insert into load_profile (script_id, name, data, created_by, updated_by) values (?, ?, ?, ?, ?) returning id",
                Long.class, scriptId, name, data, user, user);
        return new Saved(id, false);
    }

    public boolean delete(long scriptId, long id) {
        return jdbc.update("delete from load_profile where script_id = ? and id = ?", scriptId, id) == 1;
    }
}
