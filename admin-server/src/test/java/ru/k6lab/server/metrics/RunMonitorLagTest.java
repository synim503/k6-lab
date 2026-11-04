package ru.k6lab.server.metrics;

import org.junit.jupiter.api.Test;
import ru.k6lab.server.run.RunStep;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/** План на разгоне растёт линейно с нуля (метрики показываются на момент данных, а не «сейчас»). */
class RunMonitorLagTest {

    private static RunStep step(int no, Instant ramp, Instant hold) {
        RunStep s = new RunStep();
        s.setStepNo(no);
        s.setRampStartAt(ramp);
        s.setHoldStartAt(hold);
        return s;
    }

    @Test
    void planRampsLinearlyAndStaysAtTargetAfterwards() {
        Instant t0 = Instant.parse("2026-11-04T10:00:00Z");
        RunStep s = step(1, t0, t0.plusSeconds(30));
        assertThat(RunMonitor.rampFraction(s, t0.minusSeconds(5))).isZero();
        assertThat(RunMonitor.rampFraction(s, t0.plusSeconds(15))).isCloseTo(0.5, within(1e-9));
        assertThat(RunMonitor.rampFraction(s, t0.plusSeconds(30))).isEqualTo(1.0);
        assertThat(RunMonitor.rampFraction(s, t0.plusSeconds(100))).isEqualTo(1.0);
    }

    @Test
    void noRampOrNoStepMeansFullLevel() {
        Instant t0 = Instant.parse("2026-11-04T10:00:00Z");
        assertThat(RunMonitor.rampFraction(null, t0)).isEqualTo(1.0);
        assertThat(RunMonitor.rampFraction(step(1, t0, t0), t0)).isEqualTo(1.0);
        assertThat(RunMonitor.rampFraction(step(2, t0, t0.plusSeconds(30)), t0.plusSeconds(5))).isEqualTo(1.0);
    }
}
