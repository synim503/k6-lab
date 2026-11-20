package ru.k6lab.server.result;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;

/** Свои графики отчёта: имя, PromQL и единицы; привязаны к скрипту. */
@Repository
public class ChartTemplateStore {

    public record Template(long id, long scriptId, String name, String promql, String unit, int position, boolean enabled) {
    }

    private static final RowMapper<Template> ROWS = (rs, n) -> new Template(rs.getLong("id"), rs.getLong("script_id"), rs.getString("name"),
            rs.getString("promql"), rs.getString("unit"), rs.getInt("position"), rs.getBoolean("enabled"));

    private final JdbcTemplate jdbc;

    public ChartTemplateStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<Template> list(long scriptId) {
        return jdbc.query("select * from chart_template where script_id = ? order by position, id", ROWS, scriptId);
    }

    public List<Template> enabled(long scriptId) {
        return jdbc.query("select * from chart_template where script_id = ? and enabled order by position, id", ROWS, scriptId);
    }

    /** Новый график или обновление существующего с тем же именем. */
    public void save(long scriptId, String name, String promql, String unit, String user) {
        int upd = jdbc.update("update chart_template set promql = ?, unit = ?, enabled = true where script_id = ? and name = ?",
                promql, unit, scriptId, name);
        if (upd == 0) {
            int pos = jdbc.queryForObject("select coalesce(max(position), 0) + 1 from chart_template where script_id = ?", Integer.class, scriptId);
            jdbc.update("insert into chart_template (script_id, name, promql, unit, position, created_by) values (?, ?, ?, ?, ?, ?)",
                    scriptId, name, promql, unit, pos, user);
        }
    }

    public void setEnabled(long scriptId, long id, boolean enabled) {
        jdbc.update("update chart_template set enabled = ? where script_id = ? and id = ?", enabled, scriptId, id);
    }

    public boolean delete(long scriptId, long id) {
        return jdbc.update("delete from chart_template where script_id = ? and id = ?", scriptId, id) == 1;
    }
}
