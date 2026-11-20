package ru.k6lab.server.result;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.List;

/** Свои графики отчёта: имя, один или несколько PromQL-запросов (у каждого своя подпись в легенде) и единицы; привязаны к скрипту. */
@Repository
public class ChartTemplateStore {

    /** legend - подпись ряда в легенде (пусто - по меткам результата). */
    public record Query(String promql, String legend) {
    }

    public record Template(long id, long scriptId, String name, List<Query> queries, String unit, int position, boolean enabled) {

        /** Первый запрос (для краткого показа). */
        public String promql() {
            return queries.isEmpty() ? "" : queries.get(0).promql();
        }
    }

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final RowMapper<Template> ROWS = (rs, n) -> new Template(rs.getLong("id"), rs.getLong("script_id"), rs.getString("name"),
            parse(rs.getString("queries"), rs.getString("promql")), rs.getString("unit"), rs.getInt("position"), rs.getBoolean("enabled"));

    static List<Query> parse(String json, String single) {
        List<Query> out = new ArrayList<>();
        if (json != null && !json.isBlank()) {
            try {
                for (JsonNode q : MAPPER.readTree(json)) {
                    out.add(new Query(q.path("promql").asText(""), q.path("legend").asText("")));
                }
            } catch (JsonProcessingException e) {
                // битый JSON - ниже останется запрос из promql
            }
        }
        if (out.isEmpty() && single != null) {
            out.add(new Query(single, ""));
        }
        return out;
    }

    private static String toJson(List<Query> queries) {
        var arr = MAPPER.createArrayNode();
        queries.forEach(q -> arr.addObject().put("promql", q.promql()).put("legend", q.legend() == null ? "" : q.legend()));
        return arr.toString();
    }

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
    public void save(long scriptId, String name, List<Query> queries, String unit, String user) {
        String first = queries.get(0).promql();
        int upd = jdbc.update("update chart_template set promql = ?, queries = ?, unit = ?, enabled = true where script_id = ? and name = ?",
                first, toJson(queries), unit, scriptId, name);
        if (upd == 0) {
            int pos = jdbc.queryForObject("select coalesce(max(position), 0) + 1 from chart_template where script_id = ?", Integer.class, scriptId);
            jdbc.update("insert into chart_template (script_id, name, promql, queries, unit, position, created_by) values (?, ?, ?, ?, ?, ?, ?)",
                    scriptId, name, first, toJson(queries), unit, pos, user);
        }
    }

    public void setEnabled(long scriptId, long id, boolean enabled) {
        jdbc.update("update chart_template set enabled = ? where script_id = ? and id = ?", enabled, scriptId, id);
    }

    /** Удаляет выбранные графики скрипта; возвращает, сколько удалено. */
    public int deleteMany(long scriptId, java.util.Collection<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return 0;
        }
        String marks = String.join(",", java.util.Collections.nCopies(ids.size(), "?"));
        Object[] args = new Object[ids.size() + 1];
        args[0] = scriptId;
        int i = 1;
        for (Long id : ids) {
            args[i++] = id;
        }
        return jdbc.update("delete from chart_template where script_id = ? and id in (" + marks + ")", args);
    }

    public int deleteAll(long scriptId) {
        return jdbc.update("delete from chart_template where script_id = ?", scriptId);
    }

    public boolean delete(long scriptId, long id) {
        return jdbc.update("delete from chart_template where script_id = ? and id = ?", scriptId, id) == 1;
    }
}
