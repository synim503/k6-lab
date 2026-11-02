package ru.k6lab.server.loadmodel;

import lombok.Getter;
import lombok.Setter;
import ru.k6lab.core.model.OperationSpec;
import ru.k6lab.core.model.RateUnit;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Форма модели нагрузки. Интенсивность вводится абсолютом (оп/сек, оп/мин, оп/час) либо долями от общей. */
@Getter
@Setter
public class ModelForm {

    public enum InputMode { ABS, SHARE }

    public enum ClosedInput { RATE_PACING, VU_PACING }

    private Long id;
    private String name;
    private String description;
    private Long scriptId;
    private boolean template;
    private InputMode inputMode = InputMode.ABS;
    /** Общая интенсивность (для режима SHARE). */
    private Double totalValue;
    private RateUnit totalUnit = RateUnit.PER_HOUR;
    private List<OpForm> ops = new ArrayList<>();

    @Getter
    @Setter
    public static class OpForm {
        private String name;
        private String execFn;
        private OperationSpec.Mode mode = OperationSpec.Mode.OPEN;
        private Double intensityValue;
        private RateUnit intensityUnit = RateUnit.PER_HOUR;
        private Double sharePct;
        private Double expectedDurationSec;
        private Integer pacingMs;
        private ClosedInput closedInput = ClosedInput.RATE_PACING;
        private Integer vusOverride;
        private Integer preAllocOverride;
        private Integer maxVusOverride;
        private int slaPercentile = 95;
        private Double slaThresholdMs;
        private Double maxErrorPct;
        private OperationSpec.FailureSource failureSource = OperationSpec.FailureSource.HTTP;
        private String envText;
        private String tagsText;
        private boolean enabled = true;
        private Double fixedLevelPct;

        public boolean blank() {
            return (name == null || name.isBlank()) && (execFn == null || execFn.isBlank());
        }
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
