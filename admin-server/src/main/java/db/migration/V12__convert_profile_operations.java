package db.migration;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;
import ru.k6lab.core.k6config.ProfileConverter;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Режим «по профилю» (оп/с + удержание + уровень) убран: операции описываются только конструктором сценариев.
 * Сохранённые операции «по профилю» становятся constant-arrival-rate с той же интенсивностью, длительностью и уровнем.
 */
public class V12__convert_profile_operations extends BaseJavaMigration {

    private record Row(long id, double rate, double expected, String scenario, double level, int holdSec) {
    }

    @Override
    public void migrate(Context context) throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        List<Row> rows = new ArrayList<>();
        try (var st = context.getConnection().createStatement();
             ResultSet rs = st.executeQuery("select o.id, o.rate_per_sec, o.expected_duration_sec, o.scenario::text, s.level_pct, s.hold_sec "
                     + "from load_operation o join script s on s.id = o.script_id where o.executor = 'ramping-arrival-rate'")) {
            while (rs.next()) {
                rows.add(new Row(rs.getLong(1), rs.getDouble(2), rs.getDouble(3), rs.getString(4), rs.getDouble(5), rs.getInt(6)));
            }
        }
        try (PreparedStatement up = context.getConnection().prepareStatement(
                "update load_operation set executor = 'constant-arrival-rate', scenario = ?::jsonb, rate_per_sec = ? where id = ?")) {
            for (Row r : rows) {
                Map<String, Object> extras = r.scenario() == null ? new LinkedHashMap<>()
                        : mapper.readValue(r.scenario(), new TypeReference<LinkedHashMap<String, Object>>() { });
                if (extras.containsKey("stages") || extras.containsKey("startRate") || extras.containsKey("timeUnit")) {
                    continue; // свой ramping-arrival-rate со стадиями - обычный сценарий, не «по профилю»
                }
                var c = ProfileConverter.toConstantArrivalRate(r.rate(), r.level(), r.holdSec(), r.expected(), extras);
                up.setString(1, mapper.writeValueAsString(c.scenario()));
                up.setDouble(2, c.ratePerSec());
                up.setLong(3, r.id());
                up.addBatch();
            }
            up.executeBatch();
        }
    }
}
