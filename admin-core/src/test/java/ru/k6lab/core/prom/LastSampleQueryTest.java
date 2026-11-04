package ru.k6lab.core.prom;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LastSampleQueryTest {

    @Test
    void lastSampleTimeQueriesNewestSampleOfTheRun() {
        assertThat(PromQueries.lastSampleTime(PromQueries.Names.defaults(), 42))
                .isEqualTo("max(timestamp(k6_iterations_total{test_run_id=\"42\"}))");
    }
}
