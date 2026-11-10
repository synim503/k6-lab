package ru.k6lab.core.k6config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ScenarioPlanTest {

    private static JsonNode json(String s) {
        try {
            return new ObjectMapper().readTree(s);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void rampingArrivalRateIsAPiecewiseLinePlan() {
        // прогрев 5m до 125 с нуля, затем мгновенный скачок до 250, потом час на 250
        JsonNode sc = json("{\"executor\":\"ramping-arrival-rate\",\"startRate\":0,\"timeUnit\":\"1s\",\"stages\":["
                + "{\"duration\":\"5m\",\"target\":125},{\"duration\":\"0s\",\"target\":250},{\"duration\":\"1h\",\"target\":250}]}");
        assertThat(ScenarioPlan.ratePerSec(sc, 0)).isEqualTo(0.0);
        assertThat(ScenarioPlan.ratePerSec(sc, 150)).isEqualTo(62.5);              // середина линейного разгона
        assertThat(ScenarioPlan.ratePerSec(sc, 299)).isBetween(124.0, 125.0);
        assertThat(ScenarioPlan.ratePerSec(sc, 300)).isEqualTo(250.0);             // скачок на границе стадий
        assertThat(ScenarioPlan.ratePerSec(sc, 1000)).isEqualTo(250.0);
        assertThat(ScenarioPlan.ratePerSec(sc, 300 + 3600 + 5)).isEqualTo(0.0);    // сценарий закончился
        assertThat(ScenarioPlan.polyline(sc)).hasSize(6);
    }

    @Test
    void constantArrivalRateRespectsStartTimeAndTimeUnit() {
        JsonNode sc = json("{\"executor\":\"constant-arrival-rate\",\"rate\":600,\"timeUnit\":\"1m\",\"duration\":\"2m\",\"startTime\":\"30s\"}");
        assertThat(ScenarioPlan.ratePerSec(sc, 10)).isEqualTo(0.0);
        assertThat(ScenarioPlan.ratePerSec(sc, 60)).isEqualTo(10.0);   // 600 в минуту = 10 в секунду
        assertThat(ScenarioPlan.ratePerSec(sc, 200)).isEqualTo(0.0);
    }

    @Test
    void scenariosNotByRateHaveNoPlan() {
        assertThat(ScenarioPlan.ratePerSec(json("{\"executor\":\"constant-vus\",\"vus\":10,\"duration\":\"1m\"}"), 5)).isNull();
        assertThat(ScenarioPlan.ratePerSec(json("{\"executor\":\"ramping-vus\",\"stages\":[]}"), 5)).isNull();
        assertThat(ScenarioPlan.ratePerSec(null, 5)).isNull();
    }

    @Test
    void averageRateIsTheAreaUnderThePlan() {
        JsonNode sc = json("{\"executor\":\"ramping-arrival-rate\",\"startRate\":0,\"timeUnit\":\"1s\",\"stages\":["
                + "{\"duration\":\"100s\",\"target\":100},{\"duration\":\"100s\",\"target\":100}]}");
        assertThat(ScenarioPlan.averageRate(sc, 0, 100)).isEqualTo(50.0);     // линейный разгон: в среднем половина
        assertThat(ScenarioPlan.averageRate(sc, 100, 200)).isEqualTo(100.0);  // удержание
        assertThat(ScenarioPlan.averageRate(sc, 50, 150)).isEqualTo(87.5);    // половина разгона (75) и половина удержания (100)
        assertThat(ScenarioPlan.averageRate(sc, 150, 250)).isEqualTo(50.0);   // после конца сценария плана нет - ноль
        assertThat(ScenarioPlan.averageRate(json("{\"executor\":\"constant-vus\",\"vus\":1,\"duration\":\"1m\"}"), 0, 10)).isNull();
    }
}