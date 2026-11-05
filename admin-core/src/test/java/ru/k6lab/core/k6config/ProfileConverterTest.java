package ru.k6lab.core.k6config;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class ProfileConverterTest {

    @Test
    void wholeRatePerSecondStaysPerSecond() {
        var c = ProfileConverter.toConstantArrivalRate(800, 100, 2100, 0.3, null);
        assertThat(c.scenario()).containsEntry("rate", 800L).containsEntry("timeUnit", "1s").containsEntry("duration", "2100s");
        assertThat(c.scenario().get("preAllocatedVUs")).isEqualTo(360L); // 800 * 0,3 * 1,5
        assertThat(c.scenario().get("maxVUs")).isEqualTo(1080L);
        assertThat(c.ratePerSec()).isEqualTo(800.0);
    }

    @Test
    void levelScalesTheRate() {
        var c = ProfileConverter.toConstantArrivalRate(10, 50, 60, 0.1, null);
        assertThat(c.scenario()).containsEntry("rate", 5L).containsEntry("timeUnit", "1s");
        assertThat(c.ratePerSec()).isEqualTo(5.0);
    }

    @Test
    void fractionalRateGetsALongerIntervalInsteadOfRounding() {
        var c = ProfileConverter.toConstantArrivalRate(0.5, 100, 600, 1, null);
        assertThat(c.scenario()).containsEntry("rate", 1L).containsEntry("timeUnit", "2s");
        assertThat(c.ratePerSec()).isCloseTo(0.5, within(1e-9));
        var slow = ProfileConverter.toConstantArrivalRate(0.0014, 100, 600, 1, null); // ~5 в час
        assertThat(slow.scenario().get("timeUnit")).isEqualTo("3600s");
        assertThat(slow.ratePerSec()).isCloseTo(0.0014, within(0.0004));
    }

    @Test
    void ownValuesOverrideCalculatedOnes() {
        var c = ProfileConverter.toConstantArrivalRate(5, 100, 60, 0.2, Map.of("maxVUs", 40, "startTime", "10s"));
        assertThat(c.scenario()).containsEntry("maxVUs", 40).containsEntry("startTime", "10s").containsEntry("rate", 5L);
    }
}
