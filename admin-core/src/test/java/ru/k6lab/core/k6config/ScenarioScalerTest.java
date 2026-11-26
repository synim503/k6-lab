package ru.k6lab.core.k6config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ScenarioScalerTest {

    private static ObjectNode json(String s) {
        try {
            return (ObjectNode) new ObjectMapper().readTree(s);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void arrivalRateScalesRatesAndStageTargetsAndGrowsVuReserve() {
        ObjectNode sc = json("{\"executor\":\"ramping-arrival-rate\",\"startRate\":10,\"timeUnit\":\"1s\",\"preAllocatedVUs\":20,\"maxVUs\":100,"
                + "\"stages\":[{\"duration\":\"2m\",\"target\":100},{\"duration\":\"10m\",\"target\":300}]}");
        assertThat(ScenarioScaler.scale(sc, 1.5)).isTrue();
        assertThat(sc.path("startRate").asLong()).isEqualTo(15);
        assertThat(sc.path("stages").get(0).path("target").asLong()).isEqualTo(150);
        assertThat(sc.path("stages").get(1).path("target").asLong()).isEqualTo(450);
        assertThat(sc.path("stages").get(1).path("duration").asText()).isEqualTo("10m");   // длительности не меняются
        assertThat(sc.path("preAllocatedVUs").asLong()).isEqualTo(30);
        assertThat(sc.path("maxVUs").asLong()).isEqualTo(150);
    }

    @Test
    void loweringKeepsVuReserveAndAdjustsTimeUnitForFractionalRates() {
        ObjectNode sc = json("{\"executor\":\"constant-arrival-rate\",\"rate\":3,\"timeUnit\":\"1s\",\"duration\":\"5m\",\"preAllocatedVUs\":20,\"maxVUs\":100}");
        ScenarioScaler.scale(sc, 0.1);
        // 3/1s * 0.1 = 0.3/s -> 3/10s (точное масштабирование через переподбор timeUnit)
        assertThat(sc.path("rate").asLong()).isEqualTo(3);
        assertThat(sc.path("timeUnit").asText()).isEqualTo("10s");
        assertThat(sc.path("preAllocatedVUs").asLong()).isEqualTo(20);
        assertThat(sc.path("maxVUs").asLong()).isEqualTo(100);
    }

    @Test
    void vuAndIterationExecutorsAndNoOp() {
        ObjectNode vus = json("{\"executor\":\"constant-vus\",\"vus\":10,\"duration\":\"1m\"}");
        ScenarioScaler.scale(vus, 2);
        assertThat(vus.path("vus").asLong()).isEqualTo(20);
        ObjectNode it = json("{\"executor\":\"shared-iterations\",\"vus\":5,\"iterations\":200,\"maxDuration\":\"10m\"}");
        ScenarioScaler.scale(it, 0.5);
        assertThat(it.path("iterations").asLong()).isEqualTo(100);
        assertThat(it.path("vus").asLong()).isEqualTo(5);
        assertThat(ScenarioScaler.scale(json("{\"executor\":\"constant-vus\",\"vus\":10}"), 1.0)).isFalse();
    }
}
