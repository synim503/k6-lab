package ru.k6lab.server.result;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Переменные своих графиков отчёта: имя -> значение на скрипт; в запросах пишутся как $имя или ${имя}. */
@Repository
public class ChartVariableStore {

    public record Variable(long id, long scriptId, String name, String value) {
    }

    private static final RowMapper<Variable> ROWS = (rs, n) -> new Variable(rs.getLong("id"), rs.getLong("script_id"),
            rs.getString("name"), rs.getString("value"));

    private final JdbcTemplate jdbc;

    public ChartVariableStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<Variable> list(long scriptId) {
        return jdbc.query("select * from chart_variable where script_id = ? order by lower(name)", ROWS, scriptId);
    }

    public Map<String, String> map(long scriptId) {
        Map<String, String> out = new LinkedHashMap<>();
        list(scriptId).forEach(v -> out.put(v.name(), v.value()));
        return out;
    }

    /** Задаёт значение (создаёт или обновляет). */
    public void save(long scriptId, String name, String value) {
        int upd = jdbc.update("update chart_variable set value = ? where script_id = ? and name = ?", value, scriptId, name);
        if (upd == 0) {
            jdbc.update("insert into chart_variable (script_id, name, value) values (?, ?, ?)", scriptId, name, value);
        }
    }

    /** При импорте дашборда: добавляет, только если переменной ещё нет (правки пользователя не затираются). Возвращает true, если добавлена. */
    public boolean addIfAbsent(long scriptId, String name, String value) {
        return jdbc.update("insert into chart_variable (script_id, name, value) values (?, ?, ?) on conflict (script_id, name) do nothing",
                scriptId, name, value) == 1;
    }

    public boolean delete(long scriptId, long id) {
        return jdbc.update("delete from chart_variable where script_id = ? and id = ?", scriptId, id) == 1;
    }
}
