package ru.k6lab.server.load;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
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
    private int rampSec = 0;
    private double holdMinutes = 5;
    /** Число генераторов, которое подставляется при запуске; null - не менять (форма нагрузки его не передаёт, задаётся на форме запуска). */
    private Integer generatorCount;
    private String envText;
    private List<DataForm> dataFiles = new ArrayList<>();
    /** Автостоп: сервер останавливает тест, когда он «пошёл по одному месту» (интенсивность ниже цели, dropped, ошибки, время ответа). */
    private StopForm stop = new StopForm();

    @Getter
    @Setter
    public static class StopForm {
        private boolean rateEnabled;
        private double minRatePct = 90;
        private boolean droppedEnabled;
        private boolean errorsEnabled;
        private boolean percentileEnabled;
        private double slaMultiplier = 1.5;
        /** Сколько проверок подряд (раз в 15 с) должно нарушаться, чтобы остановить тест. */
        private int consecutive = 4;
        /** Окно, по которому считаются показатели, секунд. */
        private int windowSec = 60;

        public Map<String, Object> toMap() {
            return LoadCriteria.stopToMap(new ru.k6lab.core.analysis.StopCriteriaEvaluator.Config(percentileEnabled, slaMultiplier, errorsEnabled,
                    rateEnabled, minRatePct, droppedEnabled, consecutive, windowSec));
        }

        public static StopForm of(Map<String, Object> m) {
            var c = LoadCriteria.stop(m);
            StopForm s = new StopForm();
            s.rateEnabled = c.rateEnabled();
            s.minRatePct = c.minRatePct();
            s.droppedEnabled = c.droppedEnabled();
            s.errorsEnabled = c.errorsEnabled();
            s.percentileEnabled = c.percentileEnabled();
            s.slaMultiplier = c.slaMultiplier();
            s.consecutive = m == null || !m.containsKey("consecutive") ? 4 : c.consecutive();
            s.windowSec = c.windowSec();
            return s;
        }

        public void validate() {
            if (minRatePct < 1 || minRatePct > 100) {
                throw new IllegalArgumentException("Автостоп: порог интенсивности от 1 до 100 %");
            }
            if (slaMultiplier < 1 || slaMultiplier > 100) {
                throw new IllegalArgumentException("Автостоп: множитель SLA от 1 до 100");
            }
            if (consecutive < 1 || consecutive > 100) {
                throw new IllegalArgumentException("Автостоп: число проверок подряд от 1 до 100");
            }
            if (windowSec < 15 || windowSec > 3600) {
                throw new IllegalArgumentException("Автостоп: окно от 15 до 3600 секунд");
            }
        }
    }

    @Getter
    @Setter
    public static class OpForm {
        private String name;
        private String execFn;
        /** Операций в секунду; пусто или 0 - операция в нагрузке не участвует. */
        private Double ratePerSec;
        /** Операция участвует в запуске; выключенные строки не сохраняются. */
        private boolean enabled = true;
        private int slaPercentile = 95;
        private Double slaThresholdMs = 1000.0;
        private Double maxErrorPct = 1.0;
        private Double expectedDurationSec = 0.3;
        private OperationSpec.FailureSource failureSource = OperationSpec.FailureSource.HTTP;
        /** Исполнитель k6 (ramping-arrival-rate - по профилю нагрузки: оп/с, разгон, удержание). */
        private String executor = OperationSpec.PROFILE_EXECUTOR;
        /** Параметры сценария JSON-объектом, как в options.scenarios (для формы - текстом). */
        @JsonIgnore
        private String scenarioJson;

        private static final ObjectMapper MAPPER = new ObjectMapper();

        /** Параметры сценария для конфига (JSON-экспорт); при ошибке в тексте - пусто, ошибку покажет {@link #parseScenario()}. */
        @JsonProperty("scenario")
        public Map<String, Object> getScenario() {
            try {
                return parseScenario();
            } catch (IllegalArgumentException e) {
                return new LinkedHashMap<>();
            }
        }

        @JsonProperty("scenario")
        public void setScenario(Map<String, Object> scenario) {
            try {
                scenarioJson = scenario == null || scenario.isEmpty() ? "" : MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(scenario);
            } catch (JsonProcessingException e) {
                throw new IllegalArgumentException("Параметры сценария: " + e.getOriginalMessage());
            }
        }

        /** Текст параметров сценария -> объект; пусто - без параметров. */
        public Map<String, Object> parseScenario() {
            if (scenarioJson == null || scenarioJson.isBlank()) {
                return new LinkedHashMap<>();
            }
            try {
                Object v = MAPPER.readValue(scenarioJson, Object.class);
                if (!(v instanceof Map<?, ?> m)) {
                    throw new IllegalArgumentException("Параметры сценария должны быть JSON-объектом: {\"vus\": 10, \"duration\": \"5m\"}");
                }
                Map<String, Object> out = new LinkedHashMap<>();
                m.forEach((k, val) -> out.put(String.valueOf(k), val));
                return out;
            } catch (JsonProcessingException e) {
                throw new IllegalArgumentException("Параметры сценария: некорректный JSON (" + e.getOriginalMessage() + ")");
            }
        }

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
