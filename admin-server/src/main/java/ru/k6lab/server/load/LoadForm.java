package ru.k6lab.server.load;

import lombok.Getter;
import lombok.Setter;
import ru.k6lab.core.model.OperationSpec;
import ru.k6lab.server.testdata.ScriptDataFile;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Форма «Нагрузка» скрипта: операции с интенсивностью и SLA, длительность, переменные окружения, режимы файлов данных. */
@Getter
@Setter
public class LoadForm {

    private List<OpForm> ops = new ArrayList<>();
    private double levelPct = 100;
    private int rampSec = 30;
    private double holdMinutes = 5;
    private String envText;
    private List<DataForm> dataFiles = new ArrayList<>();

    @Getter
    @Setter
    public static class OpForm {
        private String name;
        private String execFn;
        /** Операций в секунду; пусто или 0 - операция в нагрузке не участвует. */
        private Double ratePerSec;
        private int slaPercentile = 95;
        private Double slaThresholdMs = 1000.0;
        private Double maxErrorPct = 1.0;
        private Double expectedDurationSec = 0.3;
        private OperationSpec.FailureSource failureSource = OperationSpec.FailureSource.HTTP;

        public boolean blank() {
            return (name == null || name.isBlank()) && (execFn == null || execFn.isBlank());
        }
    }

    @Getter
    @Setter
    public static class DataForm {
        private String path;
        private ScriptDataFile.Mode mode = ScriptDataFile.Mode.SHARED;
        private boolean hasHeader;
        private boolean consumed = true;
    }

    /** Строки KEY=VALUE -> Map (пустые и комментарии # пропускаются). */
    public static Map<String, String> parseKv(String text) {
        Map<String, String> out = new LinkedHashMap<>();
        if (text == null) {
            return out;
        }
        for (String raw : text.split("\\R")) {
            String line = raw.strip();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            int eq = line.indexOf('=');
            if (eq <= 0) {
                throw new IllegalArgumentException("Ожидается KEY=VALUE, получено: " + line);
            }
            out.put(line.substring(0, eq).strip(), line.substring(eq + 1).strip());
        }
        return out;
    }

    public static String formatKv(Map<String, String> map) {
        StringBuilder sb = new StringBuilder();
        map.forEach((k, v) -> sb.append(k).append('=').append(v).append('\n'));
        return sb.toString().stripTrailing();
    }
}
