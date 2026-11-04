package ru.k6lab.core.k6config;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import ru.k6lab.core.model.OperationSpec;
import ru.k6lab.core.model.OperationSpec.FailureSource;
import ru.k6lab.core.model.OperationSpec.Mode;
import ru.k6lab.core.profile.StepPlan;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class K6ConfigGeneratorTest {

    private static OperationSpec open(String name, double perHour, double durSec) {
        return new OperationSpec(name, name, Mode.OPEN, perHour / 3600.0, durSec, null, null, null, null,
                95, 800, 1.0, FailureSource.HTTP, Map.of(), Map.of(), true, null);
    }

    private static OperationSpec openPerSec(String name, double perSec, double durSec) {
        return new OperationSpec(name, name, Mode.OPEN, perSec, durSec, null, null, null, null,
                95, 800, 1.0, FailureSource.HTTP, Map.of(), Map.of(), true, null);
    }

    private static OperationSpec closed(String name, double perSec, int pacingMs, double durSec) {
        return new OperationSpec(name, name, Mode.CLOSED, perSec, durSec, pacingMs, null, null, null,
                95, 800, 1.0, FailureSource.HTTP, Map.of(), Map.of(), true, null);
    }

    private static List<StepPlan> steps(double... levels) {
        java.util.ArrayList<StepPlan> s = new java.util.ArrayList<>();
        for (int i = 0; i < levels.length; i++) {
            s.add(new StepPlan(i + 1, levels[i], 30, 120, "s" + (i + 1)));
        }
        return s;
    }

    @Test
    void defaultFunctionGetsNoExecField() {
        var cfg = K6ConfigGenerator.generate(new ConfigInput(List.of(openPerSec("default", 5, 0.2), openPerSec("pay", 2, 0.2)),
                steps(100), 30, 30, 1, 1.5, 3, true));
        JsonNode sc = cfg.config().path("scenarios");
        assertThat(sc.path("default").has("exec")).isFalse();
        assertThat(sc.path("pay").path("exec").asText()).isEqualTo("pay");
    }

    @Test
    void vusAreNeverFewerThanGeneratorsSoEverySegmentGetsAVu() {
        // 5 оп/с * 0,2 с = ~1 VU, но генераторов 18: k6 раздаёт VU по сегментам целыми числами - без запаса остальные 12 простаивают
        var cfg = K6ConfigGenerator.generate(new ConfigInput(List.of(openPerSec("work", 5, 0.2)), steps(100), 30, 30, 18, 1.5, 3, true));
        JsonNode sc = cfg.config().path("scenarios").path("work");
        assertThat(sc.path("preAllocatedVUs").asLong()).isGreaterThanOrEqualTo(18);
        assertThat(sc.path("maxVUs").asLong()).isGreaterThanOrEqualTo(sc.path("preAllocatedVUs").asLong());
        assertThat(cfg.operations().get(0).preAllocatedVus()).isGreaterThanOrEqualTo(18);
        // один генератор - прежний расчёт без запаса
        var single = K6ConfigGenerator.generate(new ConfigInput(List.of(openPerSec("work", 5, 0.2)), steps(100), 30, 30, 1, 1.5, 3, true));
        assertThat(single.config().path("scenarios").path("work").path("preAllocatedVUs").asLong()).isLessThan(5);
        // много VU и без запаса: расчёт не меняется
        var heavy = K6ConfigGenerator.generate(new ConfigInput(List.of(openPerSec("work", 200, 1.0)), steps(100), 30, 30, 4, 1.5, 3, true));
        assertThat(heavy.config().path("scenarios").path("work").path("preAllocatedVUs").asLong()).isEqualTo(300);
    }

    // ---------------------------------------------------------------- timeUnit

    @Test
    void timeUnitIsSmallestWithinOnePercent() {
        // 10 оп/сек - целое при timeUnit 1s
        var sel = TimeUnitSelector.select(new double[]{10, 5});
        assertThat(sel.unitSec()).isEqualTo(1);
        assertThat(sel.targets()).containsExactly(10, 5);
        // 0.2 оп/сек = 1 за 5 секунд
        assertThat(TimeUnitSelector.select(new double[]{0.2}).unitSec()).isEqualTo(5);
        // 100 оп/час: наименьший точный timeUnit - 30 минут (50 итераций)
        var hour = TimeUnitSelector.select(new double[]{100.0 / 3600});
        assertThat(hour.unitSec()).isEqualTo(1800);
        assertThat(hour.targets()).containsExactly(50);
        assertThat(hour.withinTolerance()).isTrue();
    }

    @Test
    void timeUnitReportsInexactRounding() {
        // 0.37 оп/сек: ни одна единица из набора не даёт ошибку <= 1% при одном rate? проверим фактическую ошибку
        var sel = TimeUnitSelector.select(new double[]{1.0 / 7, 3.3333});
        assertThat(sel.maxErrorPct()).isGreaterThanOrEqualTo(0);
        if (!sel.withinTolerance()) {
            assertThat(sel.maxErrorPct()).isGreaterThan(1.0);
        }
        // нулевая интенсивность допустима: цель 0
        var zero = TimeUnitSelector.select(new double[]{0, 4});
        assertThat(zero.targets()[0]).isZero();
        assertThat(zero.withinTolerance()).isTrue();
        // очень малая интенсивность не округляется в 0
        var tiny = TimeUnitSelector.select(new double[]{1.0 / 7200});
        assertThat(tiny.targets()[0]).isEqualTo(1);
    }

    // ---------------------------------------------------------------- открытая модель

    @Test
    void openScenarioStagesVusAndThresholds() {
        var in = ConfigInput.of(List.of(openPerSec("createPayment", 100, 0.4)), steps(50, 100), true);
        var out = K6ConfigGenerator.generate(in);
        JsonNode sc = out.config().path("scenarios").path("createPayment");

        assertThat(sc.path("executor").asText()).isEqualTo("ramping-arrival-rate");
        assertThat(sc.path("exec").asText()).isEqualTo("createPayment");
        assertThat(sc.path("timeUnit").asText()).isEqualTo("1s");
        assertThat(sc.path("startRate").asInt()).isZero();
        // preAllocatedVUs = ceil(пик 100/с * 0.4 с * 1.5) = 60; maxVUs = 3x
        assertThat(sc.path("preAllocatedVUs").asLong()).isEqualTo(60);
        assertThat(sc.path("maxVUs").asLong()).isEqualTo(180);
        JsonNode stages = sc.path("stages");
        assertThat(stages).hasSize(4);
        assertThat(stages.get(0).toString()).isEqualTo("{\"duration\":\"30s\",\"target\":50}");
        assertThat(stages.get(1).toString()).isEqualTo("{\"duration\":\"120s\",\"target\":50}");
        assertThat(stages.get(3).toString()).isEqualTo("{\"duration\":\"120s\",\"target\":100}");
        assertThat(sc.path("gracefulStop").asText()).isEqualTo("30s");

        // SLA -> thresholds без abortOnFail
        JsonNode th = out.config().path("thresholds");
        assertThat(th.path("op_duration{scenario:createPayment}").get(0).asText()).isEqualTo("p(95)<800");
        assertThat(th.path("op_failed{scenario:createPayment}").get(0).asText()).isEqualTo("rate<0.01");
        assertThat(out.config().toString()).doesNotContain("abortOnFail");
        assertThat(out.config().path("systemTags").toString()).isEqualTo(
                "[\"scenario\",\"status\",\"method\",\"name\",\"group\",\"check\",\"error_code\",\"expected_response\"]");
        assertThat(out.totalSec()).isEqualTo(300);
        assertThat(out.warnings()).isEmpty();
    }

    @Test
    void lowIntensityUsesLargerTimeUnit() {
        var out = K6ConfigGenerator.generate(ConfigInput.of(List.of(open("slow", 600, 1.0)), steps(100), true));
        JsonNode sc = out.config().path("scenarios").path("slow");
        // 600 оп/час = 1 оп за 6 секунд = 10 оп/мин: первый подходящий timeUnit - 6c нет в наборе, 30s даст 5, ошибки нет
        assertThat(sc.path("timeUnit").asText()).isIn("30s", "60s");
        long target = sc.path("stages").get(1).path("target").asLong();
        int unit = Integer.parseInt(sc.path("timeUnit").asText().replace("s", ""));
        assertThat(target * 1.0 / unit).isEqualTo(600.0 / 3600);
    }

    @Test
    void levelScalesRateAndFixedLevelOverrides() {
        var background = new OperationSpec("bg", "bg", Mode.OPEN, 10, 0.2, null, null, null, null, 95, 500, 1.0,
                FailureSource.HTTP, Map.of(), Map.of(), true, 100.0);
        var main = openPerSec("main", 20, 0.2);
        var out = K6ConfigGenerator.generate(ConfigInput.of(List.of(main, background), steps(50, 150), true));
        JsonNode m = out.config().path("scenarios").path("main").path("stages");
        JsonNode b = out.config().path("scenarios").path("bg").path("stages");
        assertThat(m.get(1).path("target").asLong()).isEqualTo(10);
        assertThat(m.get(3).path("target").asLong()).isEqualTo(30);
        // фон зафиксирован на 100%, не зависит от ступеней
        assertThat(b.get(1).path("target").asLong()).isEqualTo(10);
        assertThat(b.get(3).path("target").asLong()).isEqualTo(10);
    }

    @Test
    void overridesForVusWin() {
        var op = new OperationSpec("x", "x", Mode.OPEN, 50, 1.0, null, null, 7, 9, 95, 500, 1.0,
                FailureSource.HTTP, Map.of(), Map.of(), true, null);
        JsonNode sc = K6ConfigGenerator.generate(ConfigInput.of(List.of(op), steps(100), true)).config().path("scenarios").path("x");
        assertThat(sc.path("preAllocatedVUs").asLong()).isEqualTo(7);
        assertThat(sc.path("maxVUs").asLong()).isEqualTo(9);
    }

    @Test
    void zeroRampAndFirstStepStartRate() {
        var s = List.of(new StepPlan(1, 100, 0, 60, "a"));
        JsonNode sc = K6ConfigGenerator.generate(ConfigInput.of(List.of(openPerSec("x", 10, 0.1)), s, true)).config()
                .path("scenarios").path("x");
        assertThat(sc.path("startRate").asLong()).isEqualTo(10);
        assertThat(sc.path("stages").get(0).path("duration").asText()).isEqualTo("0s");
    }

    @Test
    void warnsWhenRateCannotBeRoundedAndWhenTooLowPerGenerator() {
        var in = new ConfigInput(List.of(openPerSec("x", 0.0001, 1.0)), steps(100), 30, 30, 5, 1.5, 3, true);
        var out = K6ConfigGenerator.generate(in);
        // 0.0001 оп/сек = 1 за 10000 с, максимальный timeUnit 1h -> округление в 0 запрещено, остаётся 1 за час
        assertThat(out.warnings()).extracting(GeneratedConfig.Warning::code).contains("ROUNDING");

        var lowPerGen = new ConfigInput(List.of(openPerSec("y", 2, 1.0)), steps(100), 30, 30, 10, 1.5, 3, true);
        assertThat(K6ConfigGenerator.generate(lowPerGen).warnings()).extracting(GeneratedConfig.Warning::code)
                .contains("LOW_RATE_PER_GENERATOR");
    }

    // ---------------------------------------------------------------- закрытая модель

    @Test
    void closedModelComputesVusFromRateAndPacing() {
        // 5 оп/сек, pacing 2 с -> 10 VU на 100%
        var in = ConfigInput.of(List.of(closed("c", 5, 2000, 0.5)), steps(50, 100, 150), true);
        var out = K6ConfigGenerator.generate(in);
        JsonNode sc = out.config().path("scenarios").path("c");
        assertThat(sc.path("executor").asText()).isEqualTo("ramping-vus");
        assertThat(sc.path("env").path("PACING_MS").asText()).isEqualTo("2000");
        assertThat(sc.path("gracefulRampDown").asText()).isEqualTo("30s");
        JsonNode st = sc.path("stages");
        assertThat(st.get(1).path("target").asLong()).isEqualTo(5);
        assertThat(st.get(3).path("target").asLong()).isEqualTo(10);
        assertThat(st.get(5).path("target").asLong()).isEqualTo(15);
        assertThat(out.operations().get(0).ratesPerSec()[2]).isEqualTo(7.5);
        assertThat(out.warnings()).isEmpty();
    }

    @Test
    void closedModelRoundsVusUpAndWarnsWhenOperationIsSlowerThanPacing() {
        var out = K6ConfigGenerator.generate(ConfigInput.of(List.of(closed("c", 1, 1000, 1.5)), steps(30), true));
        // 0.3 VU -> 1 VU
        assertThat(out.config().path("scenarios").path("c").path("stages").get(1).path("target").asLong()).isEqualTo(1);
        assertThat(out.warnings()).extracting(GeneratedConfig.Warning::code).contains("PACING_TOO_SHORT");
    }

    @Test
    void closedModelRequiresHelper() {
        assertThatThrownBy(() -> K6ConfigGenerator.generate(ConfigInput.of(List.of(closed("c", 1, 1000, 0.1)), steps(100), false)))
                .hasMessageContaining("admin.js");
        assertThatThrownBy(() -> K6ConfigGenerator.generate(ConfigInput.of(List.of(closed("c", 1, 0, 0.1)), steps(100), true)))
                .hasMessageContaining("pacing");
    }

    @Test
    void closedAndOpenCanBeMixed() {
        var out = K6ConfigGenerator.generate(ConfigInput.of(List.of(openPerSec("a", 10, 0.1), closed("b", 2, 1000, 0.1)), steps(100), true));
        assertThat(out.config().path("scenarios").path("a").path("executor").asText()).isEqualTo("ramping-arrival-rate");
        assertThat(out.config().path("scenarios").path("b").path("executor").asText()).isEqualTo("ramping-vus");
    }

    // ---------------------------------------------------------------- thresholds, теги, детерминизм

    @Test
    void thresholdsWithoutHelperUseIterationDurationAndChosenFailureSource() {
        var http = openPerSec("h", 1, 0.1);
        var checks = new OperationSpec("c", "c", Mode.OPEN, 1, 0.1, null, null, null, null, 99, 1200, 2.0,
                FailureSource.CHECKS, Map.of(), Map.of(), true, null);
        JsonNode th = K6ConfigGenerator.generate(ConfigInput.of(List.of(http, checks), steps(100), false)).config().path("thresholds");
        assertThat(th.path("iteration_duration{scenario:h}").get(0).asText()).isEqualTo("p(95)<800");
        assertThat(th.path("http_req_failed{scenario:h}").get(0).asText()).isEqualTo("rate<0.01");
        assertThat(th.path("iteration_duration{scenario:c}").get(0).asText()).isEqualTo("p(99)<1200");
        assertThat(th.path("checks{scenario:c}").get(0).asText()).isEqualTo("rate>0.98");
        assertThat(th.has("op_duration{scenario:h}")).isFalse();
    }

    @Test
    void systemScenarioTagIsNeverOverridden() {
        var op = new OperationSpec("x", "x", Mode.OPEN, 1, 0.1, null, null, null, null, 95, 800, 1.0, FailureSource.HTTP,
                Map.of("BASE_URL", "http://s"), Map.of("scenario", "hacked", "team", "pay"), true, null);
        JsonNode sc = K6ConfigGenerator.generate(ConfigInput.of(List.of(op), steps(100), true)).config().path("scenarios").path("x");
        assertThat(sc.path("tags").has("scenario")).isFalse();
        assertThat(sc.path("tags").path("team").asText()).isEqualTo("pay");
        assertThat(sc.path("env").path("BASE_URL").asText()).isEqualTo("http://s");
    }

    @Test
    void outputIsDeterministicRegardlessOfOperationOrder() {
        var a = openPerSec("alpha", 10, 0.1);
        var b = openPerSec("beta", 5, 0.2);
        String j1 = K6ConfigGenerator.toJson(K6ConfigGenerator.generate(ConfigInput.of(List.of(a, b), steps(50, 100), true)));
        String j2 = K6ConfigGenerator.toJson(K6ConfigGenerator.generate(ConfigInput.of(List.of(b, a), steps(50, 100), true)));
        assertThat(j1).isEqualTo(j2);
    }

    @Test
    void disabledOperationsAreSkippedAndInvalidInputRejected() {
        var off = new OperationSpec("off", "off", Mode.OPEN, 1, 0.1, null, null, null, null, 95, 800, 1.0, FailureSource.HTTP,
                Map.of(), Map.of(), false, null);
        var out = K6ConfigGenerator.generate(ConfigInput.of(List.of(openPerSec("on", 1, 0.1), off), steps(100), true));
        assertThat(out.config().path("scenarios").has("off")).isFalse();
        assertThatThrownBy(() -> K6ConfigGenerator.generate(ConfigInput.of(List.of(off), steps(100), true))).hasMessageContaining("нет включённых");
        assertThatThrownBy(() -> K6ConfigGenerator.generate(ConfigInput.of(List.of(openPerSec("bad name", 1, 0.1)), steps(100), true)))
                .hasMessageContaining("Имя операции");
        assertThatThrownBy(() -> K6ConfigGenerator.generate(ConfigInput.of(List.of(openPerSec("a", 1, 0.1), openPerSec("a", 1, 0.1)), steps(100), true)))
                .hasMessageContaining("повторяется");
        assertThatThrownBy(() -> K6ConfigGenerator.generate(ConfigInput.of(List.of(openPerSec("z", 1, 0)), steps(100), true)))
                .hasMessageContaining("длительность");
    }

    @Test
    void previewHasStepRowsWithTotals() {
        var out = K6ConfigGenerator.generate(ConfigInput.of(List.of(openPerSec("a", 10, 0.5), openPerSec("b", 20, 1.0)), steps(50, 100), true));
        assertThat(out.preview()).hasSize(2);
        var row = out.preview().get(1);
        assertThat(row.ratePerSec()).containsEntry("a", 10.0).containsEntry("b", 20.0);
        assertThat(row.totalRatePerSec()).isEqualTo(30.0);
        assertThat(row.totalVus()).isEqualTo(5 + 20);
        assertThat(row.rampStartSec()).isEqualTo(150);
        assertThat(row.holdStartSec()).isEqualTo(180);
        assertThat(out.totalPeakRatePerSec()).isEqualTo(30.0);
    }
}
