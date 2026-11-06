package db.migration;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Прогрев теперь свойство сценария (scenario.warmup = {duration[, target]}): длительность, а у ramping-* ещё и своя интенсивность,
 * а не общая доля нагрузки скрипта. Сохранённый прогрев (длительность и % из V13) переносится в сценарии: у ramping-* цель = доля от основной
 * интенсивности (VU или итераций за интервал), не меньше 1; constant-* получают только длительность.
 */
public class V14__warmup_to_stage extends BaseJavaMigration {

    private record Row(long id, String executor, String scenario, int warmSec, int pct) {
    }

    @Override
    public void migrate(Context context) throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        List<Row> rows = new ArrayList<>();
        try (var st = context.getConnection().createStatement();
             ResultSet rs = st.executeQuery("select o.id, o.executor, o.scenario::text, s.warmup_sec, s.warmup_pct "
                     + "from load_operation o join script s on s.id = o.script_id where s.warmup_sec > 0 "
                     + "and o.executor in ('constant-vus', 'ramping-vus', 'constant-arrival-rate', 'ramping-arrival-rate')")) {
            while (rs.next()) {
                rows.add(new Row(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getInt(4), rs.getInt(5)));
            }
        }
        try (PreparedStatement up = context.getConnection().prepareStatement("update load_operation set scenario = ?::jsonb where id = ?")) {
            for (Row r : rows) {
                Map<String, Object> sc = r.scenario() == null ? new LinkedHashMap<>()
                        : mapper.readValue(r.scenario(), new TypeReference<LinkedHashMap<String, Object>>() { });
                Map<String, Object> warm = new LinkedHashMap<>();
                warm.put("duration", r.warmSec() % 60 == 0 ? (r.warmSec() / 60) + "m" : r.warmSec() + "s");
                if (r.executor().startsWith("ramping-")) {
                    // своя интенсивность прогрева - только у ramping-*; у constant-* прогрев остаётся отрезком времени
                    double main = mainValue(r.executor(), sc);
                    warm.put("target", Math.max(1, Math.round(main * r.pct() / 100.0)));
                }
                sc.put("warmup", warm);
                up.setString(1, mapper.writeValueAsString(sc));
                up.setLong(2, r.id());
                up.addBatch();
            }
            up.executeBatch();
        }
        try (var st = context.getConnection().createStatement()) {
            st.executeUpdate("update script set warmup_sec = 0 where warmup_sec > 0");
        }
    }

    /** Основная интенсивность сценария: VU или итераций за интервал (первая стадия для ramping-*). */
    private static double mainValue(String executor, Map<String, Object> sc) {
        return switch (executor) {
            case "constant-vus" -> num(sc.get("vus"), 1);
            case "constant-arrival-rate" -> num(sc.get("rate"), 1);
            default -> {
                double first = 0;
                if (sc.get("stages") instanceof List<?> l && !l.isEmpty() && l.get(0) instanceof Map<?, ?> m) {
                    first = num(m.get("target"), 0);
                }
                yield first > 0 ? first : Math.max(1, num(sc.get(executor.equals("ramping-vus") ? "startVUs" : "startRate"), 1));
            }
        };
    }

    private static double num(Object o, double def) {
        return o instanceof Number n ? n.doubleValue() : def;
    }
}
