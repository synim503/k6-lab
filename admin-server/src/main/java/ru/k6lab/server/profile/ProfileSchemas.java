package ru.k6lab.server.profile;

import com.fasterxml.jackson.databind.JsonNode;
import ru.k6lab.core.profile.ProfileType;

import java.util.ArrayList;
import java.util.List;

/** Описание полей профиля по типу: по нему htmx подгружает форму параметров. */
public final class ProfileSchemas {

    private ProfileSchemas() {
    }

    public record Param(String name, String label, String defaultValue, String hint) {
    }

    public static List<Param> forType(ProfileType type, JsonNode templateDefinition) {
        return switch (type) {
            case MAX_SEARCH, STRESS -> List.of(
                    new Param("startPct", "Стартовый уровень, %", "50", "процент от интенсивности модели"),
                    new Param("stepPct", "Шаг, %", "10", ""),
                    new Param("steps", "Число ступеней", "10", type == ProfileType.STRESS ? "верхней границы уровня нет" : "уровень не выше 1000%"),
                    new Param("rampSec", "Разгон на ступень, с", "60", ""),
                    new Param("holdSec", "Удержание, с", "300", "на этом участке считаются результаты ступени"));
            case CONFIRMATION -> List.of(
                    new Param("levelPct", "Уровень, %", "100", "найденный максимум"),
                    new Param("rampSec", "Разгон, с", "120", ""),
                    new Param("holdSec", "Удержание, с", "900", ""));
            case RELIABILITY -> List.of(
                    new Param("levelPct", "Уровень, %", "70", ""),
                    new Param("rampSec", "Разгон, с", "300", ""),
                    new Param("durationSec", "Длительность, с", "14400", "4 часа = 14400"));
            case SPIKE -> List.of(
                    new Param("basePct", "Базовый уровень, %", "30", ""),
                    new Param("peakPct", "Пик, %", "120", ""),
                    new Param("peakDurationSec", "Длительность пика, с", "60", ""),
                    new Param("peaks", "Число пиков", "3", ""),
                    new Param("intervalSec", "Интервал между пиками, с", "300", "удержание базового уровня"),
                    new Param("spikeRampSec", "Разгон до пика / спад, с", "5", "резкость скачка"),
                    new Param("rampSec", "Разгон до базового, с", "60", ""));
            case CUSTOM -> List.of(new Param("steps", "Ступени", "10;60;300\n20;60;300\n30;60;300",
                    "по строке на ступень: уровень %;разгон с;удержание с[;метка]"));
            case TEMPLATE -> {
                List<Param> out = new ArrayList<>();
                if (templateDefinition != null) {
                    for (JsonNode p : templateDefinition.path("params")) {
                        out.add(new Param(p.path("name").asText(), p.path("label").asText(p.path("name").asText()),
                                p.has("default") ? p.get("default").asText() : "", p.path("hint").asText("")));
                    }
                }
                yield out;
            }
        };
    }
}
