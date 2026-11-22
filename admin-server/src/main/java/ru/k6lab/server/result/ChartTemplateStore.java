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

    public java.util.Optional<Template> get(long scriptId, long id) {
        List<Template> r = jdbc.query("select * from chart_template where script_id = ? and id = ?", ROWS, scriptId, id);
        return r.isEmpty() ? java.util.Optional.empty() : java.util.Optional.of(r.get(0));
    }

    /** Новый график или обновление существующего с тем же именем. */
    public void save(long scriptId, String name, List<Query> queries, String unit, String user) {
        save(scriptId, null, name, queries, unit, true, user);
    }

    /** Сохранение графика: при заданном id — обновление (включая переименование), иначе — создание или обновление по имени. */
    public Template save(long scriptId, Long id, String name, List<Query> queries, String unit, Boolean enabled, String user) {
        String first = queries.get(0).promql();
        String json = toJson(queries);
        if (id != null) {
            Integer clash = jdbc.queryForObject("select count(*) from chart_template where script_id = ? and name = ? and id <> ?",
                    Integer.class, scriptId, name, id);
            if (clash != null && clash > 0) {
                throw new IllegalArgumentException("График с именем «" + name + "» уже существует");
            }
            int upd;
            if (enabled != null) {
                upd = jdbc.update("update chart_template set name = ?, promql = ?, queries = ?, unit = ?, enabled = ? where script_id = ? and id = ?",
                        name, first, json, unit, enabled, scriptId, id);
            } else {
                upd = jdbc.update("update chart_template set name = ?, promql = ?, queries = ?, unit = ? where script_id = ? and id = ?",
                        name, first, json, unit, scriptId, id);
            }
            if (upd == 0) {
                throw new IllegalArgumentException("График не найден (возможно, был удалён)");
            }
            return get(scriptId, id).orElseThrow();
        }
        boolean en = enabled == null || enabled;
        int upd = jdbc.update("update chart_template set promql = ?, queries = ?, unit = ?, enabled = ? where script_id = ? and name = ?",
                first, json, unit, en, scriptId, name);
        if (upd == 0) {
            int pos = jdbc.queryForObject("select coalesce(max(position), 0) + 1 from chart_template where script_id = ?", Integer.class, scriptId);
            Long newId = jdbc.queryForObject(
                    "insert into chart_template (script_id, name, promql, queries, unit, position, enabled, created_by) values (?, ?, ?, ?, ?, ?, ?, ?) returning id",
                    Long.class, scriptId, name, first, json, unit, pos, en, user);
            return get(scriptId, newId).orElseThrow();
        }
        List<Template> found = jdbc.query("select * from chart_template where script_id = ? and name = ?", ROWS, scriptId, name);
        return found.get(0);
    }

    /** Создаёт копию существующего графика сразу после оригинала. */
    public Template duplicate(long scriptId, long id, String user) {
        Template src = get(scriptId, id).orElseThrow(() -> new IllegalArgumentException("График не найден"));
        java.util.Set<String> existing = new java.util.HashSet<>();
        list(scriptId).forEach(t -> existing.add(t.name()));
        String base = src.name();
        String copyName = truncateName(base, " (копия)");
        for (int i = 2; existing.contains(copyName); i++) {
            copyName = truncateName(base, " (копия " + i + ")");
        }
        jdbc.update("update chart_template set position = position + 1 where script_id = ? and position > ?", scriptId, src.position());
        Long newId = jdbc.queryForObject(
                "insert into chart_template (script_id, name, promql, queries, unit, position, enabled, created_by) values (?, ?, ?, ?, ?, ?, ?, ?) returning id",
                Long.class, scriptId, copyName, src.promql(), toJson(src.queries()), src.unit(), src.position() + 1, src.enabled(), user);
        return get(scriptId, newId).orElseThrow();
    }

    private static String truncateName(String base, String suffix) {
        int maxBase = Math.max(1, 120 - suffix.length());
        return (base.length() > maxBase ? base.substring(0, maxBase) : base) + suffix;
    }

    /** Сохраняет порядок графиков отчёта (position = 1..N). */
    public void reorder(long scriptId, List<Long> orderedIds) {
        if (orderedIds == null || orderedIds.isEmpty()) {
            return;
        }
        List<Long> finalOrder = new ArrayList<>();
        java.util.Set<Long> seen = new java.util.LinkedHashSet<>();
        List<Template> current = list(scriptId);
        java.util.Set<Long> valid = new java.util.HashSet<>();
        current.forEach(t -> valid.add(t.id()));
        for (Long id : orderedIds) {
            if (id != null && valid.contains(id) && seen.add(id)) {
                finalOrder.add(id);
            }
        }
        for (Template t : current) {
            if (seen.add(t.id())) {
                finalOrder.add(t.id());
            }
        }
        int pos = 1;
        for (Long id : finalOrder) {
            jdbc.update("update chart_template set position = ? where script_id = ? and id = ?", pos++, scriptId, id);
        }
    }

    public void setEnabled(long scriptId, long id, boolean enabled) {
        jdbc.update("update chart_template set enabled = ? where script_id = ? and id = ?", enabled, scriptId, id);
    }

    /** Включает или выключает выбранные графики скрипта; возвращает количество обновлённых. */
    public int setEnabledMany(long scriptId, java.util.Collection<Long> ids, boolean enabled) {
        if (ids == null || ids.isEmpty()) {
            return 0;
        }
        String marks = String.join(",", java.util.Collections.nCopies(ids.size(), "?"));
        Object[] args = new Object[ids.size() + 2];
        args[0] = enabled;
        args[1] = scriptId;
        int i = 2;
        for (Long id : ids) {
            args[i++] = id;
        }
        return jdbc.update("update chart_template set enabled = ? where script_id = ? and id in (" + marks + ")", args);
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
