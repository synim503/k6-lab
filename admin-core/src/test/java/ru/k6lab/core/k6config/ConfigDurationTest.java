package ru.k6lab.core.k6config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class ConfigDurationTest {

    private static final ObjectMapper M = new ObjectMapper();

    @Test
    void parsesK6Durations() {
        assertThat(ConfigDuration.parse("90s", -1)).isEqualTo(90);
        assertThat(ConfigDuration.parse("1h30m", -1)).isEqualTo(5400);
        assertThat(ConfigDuration.parse("1m30s", -1)).isEqualTo(90);
        assertThat(ConfigDuration.parse("500ms", -1)).isCloseTo(0.5, within(1e-9));
        assertThat(ConfigDuration.parse("1.5m", -1)).isEqualTo(90);
        assertThat(ConfigDuration.parse("abc", -1)).isEqualTo(-1);
        assertThat(ConfigDuration.parse("10x", -1)).isEqualTo(-1);
        assertThat(ConfigDuration.parse("", 7)).isEqualTo(7);
    }

    @Test
    void endOfTheLongestScenarioDefinesThePlannedEnd() throws Exception {
        var cfg = M.readTree("""
                {"scenarios": {
                  "profile": {"executor": "ramping-arrival-rate", "stages": [{"duration": "0s", "target": 5}, {"duration": "300s", "target": 5}], "gracefulStop": "30s"},
                  "soak": {"executor": "constant-vus", "vus": 5, "duration": "10m", "startTime": "30s", "gracefulStop": "45s"},
                  "burst": {"executor": "ramping-vus", "stages": [{"duration": "1m", "target": 10}, {"duration": "2m", "target": 0}]},
                  "once": {"executor": "shared-iterations", "vus": 1, "iterations": 3}
                }}""");
        var span = ConfigDuration.of(cfg);
        // soak: 30 с + 10 мин = 630; once: maxDuration по умолчанию 10 мин = 600; burst: 180 + 30 (gracefulRampDown) = 210
        assertThat(span.totalSec()).isEqualTo(630);
        assertThat(span.gracefulStopSec()).isEqualTo(45);
    }

    @Test
    void emptyOrMissingConfigGivesZero() throws Exception {
        assertThat(ConfigDuration.of(null)).isEqualTo(new ConfigDuration.Span(0, 0));
        assertThat(ConfigDuration.of(M.readTree("{}"))).isEqualTo(new ConfigDuration.Span(0, 0));
    }
}
