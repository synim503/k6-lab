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


    private static OperationSpec custom(String name, String fn, String executor, Map<String, Object> scenario) {
        return new OperationSpec(name, fn, Mode.OPEN, 0, 0.3, null, null, null, null, 95, 800, 1.0, FailureSource.HTTP, Map.of(),
                Map.of(), true, null, executor, scenario);
    }

    @Test
    void customExecutorsGoToTheConfigAsIs() {
        var cfg = K6ConfigGenerator.generate(new ConfigInput(List.of(
                openPerSec("api", 5, 0.2),
                custom("soak", "work", "constant-vus", Map.of("vus", 10, "duration", "5m", "startTime", "30s", "env", Map.of("MODE", "soak"))),
                custom("burst", "work", "ramping-arrival-rate", Map.of("startRate", 1, "timeUnit", "1s", "preAllocatedVUs", 5, "maxVUs", 50,
                        "stages", List.of(Map.of("duration", "1m", "target", 100)))),
                custom("once", "setupOnce", "shared-iterations", Map.of("vus", 1, "iterations", 1, "maxDuration", "1m"))),
                steps(100), 30, 30, 1, 1.5, 3, true));
        JsonNode sc = cfg.config().path("scenarios");
        // несколько сценариев на одну функцию: exec общий, имена разные
        assertThat(sc.path("soak").path("executor").asText()).isEqualTo("constant-vus");
        assertThat(sc.path("soak").path("exec").asText()).isEqualTo("work");
        assertThat(sc.path("soak").path("vus").asInt()).isEqualTo(10);
        assertThat(sc.path("soak").path("startTime").asText()).isEqualTo("30s");
        assertThat(sc.path("soak").path("env").path("MODE").asText()).isEqualTo("soak");
        assertThat(sc.path("burst").path("exec").asText()).isEqualTo("work");
        // свой ramping-arrival-rate: stages как задал пользователь, профиль не применяется
        assertThat(sc.path("burst").path("stages").path(0).path("target").asInt()).isEqualTo(100);
        assertThat(sc.path("once").path("executor").asText()).isEqualTo("shared-iterations");
        // обычная операция по профилю не затронута
        assertThat(sc.path("api").path("executor").asText()).isEqualTo("ramping-arrival-rate");
        // SLA-пороги строятся для всех сценариев
        assertThat(cfg.config().path("thresholds").has("op_duration{scenario:soak}")).isTrue();
        assertThat(cfg.operations()).hasSize(4);
    }

    @Test
    void profileScenarioAcceptsStartTimeAndVuOverrides() {
        var op = custom("api", "api", "ramping-arrival-rate", Map.of("startTime", "10s", "gracefulStop", "5s"));
        var cfg = K6ConfigGenerator.generate(new ConfigInput(List.of(new OperationSpec("api", "api", Mode.OPEN, 5, 0.2, null, null, 7, 70, 95, 800, 1.0,
                FailureSource.HTTP, Map.of(), Map.of(), true, null, "ramping-arrival-rate", op.scenario())), steps(100), 30, 30, 1, 1.5, 3, true));
        JsonNode sc = cfg.config().path("scenarios").path("api");
        assertThat(sc.path("startTime").asText()).isEqualTo("10s");
        assertThat(sc.path("gracefulStop").asText()).isEqualTo("5s");
        assertThat(sc.path("preAllocatedVUs").asInt()).isEqualTo(7);
        assertThat(sc.path("maxVUs").asInt()).isEqualTo(70);
    }

    @Test
    void unknownExecutorAndForeignFieldsAreRejectedWithAClearMessage() {
        assertThatThrownBy(() -> K6ConfigGenerator.generate(new ConfigInput(List.of(custom("x", "x", "magic", Map.of())), steps(100), 30, 30, 1, 1.5, 3, true)))
                .hasMessageContaining("неизвестный исполнитель").hasMessageContaining("constant-vus");
        assertThatThrownBy(() -> K6ConfigGenerator.generate(new ConfigInput(List.of(custom("x", "x", "constant-vus", Map.of("rate", 5))), steps(100), 30, 30, 1, 1.5, 3, true)))
                .hasMessageContaining("«rate»").hasMessageContaining("constant-vus").hasMessageContaining("duration");
        assertThatThrownBy(() -> K6ConfigGenerator.generate(new ConfigInput(List.of(custom("x", "x", "ramping-arrival-rate", Map.of("rate", 5))), steps(100), 30, 30, 1, 1.5, 3, true)))
                .hasMessageContaining("«rate»");
        assertThat(K6ConfigGenerator.executors()).startsWith("ramping-arrival-rate").contains("constant-vus", "externally-controlled");
    }

    private static Map<String, Object> warm(String dur, Number target) {
        return Map.of("duration", dur, "target", target);
    }

    @Test
    void warmupKeepsExecutorsAndOnlyRampingScenariosGetOwnWarmupStage() {
        var ops = List.of(
                custom("soak", "work", "constant-vus", Map.of("vus", 10, "duration", "5m", "warmup", Map.of("duration", "2m"))),
                custom("car", "work", "constant-arrival-rate", Map.of("rate", 100, "timeUnit", "1s", "duration", "5m",
                        "preAllocatedVUs", 20, "maxVUs", 60, "warmup", Map.of("duration", "90s"))),
                custom("rv", "work", "ramping-vus", Map.of("startVUs", 5, "stages", List.of(Map.of("duration", "1m", "target", 40)),
                        "warmup", warm("30s", 10))),
                custom("rar", "work", "ramping-arrival-rate", Map.of("startRate", 0, "timeUnit", "1s", "preAllocatedVUs", 5, "maxVUs", 20,
                        "stages", List.of(Map.of("duration", "1m", "target", 50)), "warmup", warm("45s", 4))),
                custom("rarPlain", "work", "ramping-arrival-rate", Map.of("startRate", 1, "timeUnit", "1s", "preAllocatedVUs", 5, "maxVUs", 20,
                        "stages", List.of(Map.of("duration", "1m", "target", 50)), "warmup", Map.of("duration", "20s"))),
                custom("once", "setup", "shared-iterations", Map.of("vus", 1, "iterations", 3, "maxDuration", "1m")));
        var cfg = K6ConfigGenerator.generate(new ConfigInput(ops, steps(100), 30, 30, 1, 1.5, 3, true));
        JsonNode sc = cfg.config().path("scenarios");
        assertThat(sc.size()).isEqualTo(6);
        // constant-*: исполнитель и параметры прежние, прогрев - только отрезок времени (метка фазы в метриках)
        JsonNode soak = sc.path("soak");
        assertThat(soak.path("executor").asText()).isEqualTo("constant-vus");
        assertThat(soak.path("vus").asInt()).isEqualTo(10);
        assertThat(soak.path("duration").asText()).isEqualTo("5m");
        assertThat(soak.has("stages")).isFalse();
        assertThat(soak.has("warmup")).isFalse();
        assertThat(soak.path("env").path(K6ConfigGenerator.WARMUP_END_ENV).asText()).isEqualTo("120000");
        assertThat(sc.path("car").path("executor").asText()).isEqualTo("constant-arrival-rate");
        assertThat(sc.path("car").path("env").path(K6ConfigGenerator.WARMUP_END_ENV).asText()).isEqualTo("90000");
        // ramping-* со своей интенсивностью: первая стадия, секундный переход к прежнему началу, свои стадии; исполнитель прежний
        JsonNode rvStages = sc.path("rv").path("stages");
        assertThat(sc.path("rv").path("executor").asText()).isEqualTo("ramping-vus");
        assertThat(sc.path("rv").path("startVUs").asInt()).isEqualTo(10);
        assertThat(rvStages.size()).isEqualTo(3);
        assertThat(rvStages.get(0).path("duration").asText()).isEqualTo("30s");
        assertThat(rvStages.get(0).path("target").asInt()).isEqualTo(10);
        assertThat(rvStages.get(1).path("duration").asText()).isEqualTo("1s");
        assertThat(rvStages.get(1).path("target").asInt()).isEqualTo(5);
        assertThat(rvStages.get(2).path("target").asInt()).isEqualTo(40);
        assertThat(sc.path("rar").path("executor").asText()).isEqualTo("ramping-arrival-rate");
        assertThat(sc.path("rar").path("stages").get(0).path("target").asInt()).isEqualTo(4);
        assertThat(sc.path("rar").path("stages").get(1).path("target").asInt()).isEqualTo(0);
        // ramping-* без своей интенсивности: стадии не меняются, прогрев - отрезок времени
        assertThat(sc.path("rarPlain").path("stages").size()).isEqualTo(1);
        assertThat(sc.path("rarPlain").path("startRate").asInt()).isEqualTo(1);
        // исполнители без прогрева не меняются и тега фазы не получают
        assertThat(sc.path("once").has("env")).isFalse();
        // длительность теста: constant-* - как была, ramping-* с прогревом - плюс прогрев и секундный переход
        assertThat(ConfigDuration.of(cfg.config()).totalSec()).isEqualTo(300);
        // пороги k6 с helper - только по основной фазе; без прогрева - как раньше
        String th = cfg.config().path("thresholds").toString();
        assertThat(th).contains("op_duration{scenario:soak,phase:main}").contains("op_failed{scenario:rar,phase:main}")
                .contains("op_duration{scenario:once}").doesNotContain("scenario:once,phase");
        // границы и интенсивность прогрева для планирования запуска
        assertThat(K6ConfigGenerator.warmupEndSec(ops.get(0))).isEqualTo(120);
        assertThat(K6ConfigGenerator.warmupRatePerSec(ops.get(3))).isEqualTo(4);
        assertThat(K6ConfigGenerator.warmupRatePerSec(ops.get(1))).isZero();
    }

    @Test
    void warmupAsFirstStagesOfRampingScenarioKeepsStagesAsTheyAreAndMarksTheWindow() {
        var stages = List.of(Map.of("duration", "5m", "target", 125), Map.of("duration", "30s", "target", 250), Map.of("duration", "1h", "target", 250));
        var rar = custom("rar", "work", "ramping-arrival-rate", Map.of("startRate", 0, "timeUnit", "1s", "preAllocatedVUs", 5, "maxVUs", 20,
                "stages", stages, "warmup", Map.of("stages", 2), "startTime", "10s"));
        var cfg = K6ConfigGenerator.generate(new ConfigInput(List.of(rar), steps(100), 30, 30, 1, 1.5, 3, false));
        JsonNode sc = cfg.config().path("scenarios").path("rar");
        // стадии не переписываются, прогрев - первые две: 5m + 30s после старта сценария
        assertThat(sc.path("stages").size()).isEqualTo(3);
        assertThat(sc.has("warmup")).isFalse();
        assertThat(K6ConfigGenerator.warmupEndSec(rar)).isEqualTo(10 + 300 + 30);
        assertThat(sc.path("env").path(K6ConfigGenerator.WARMUP_END_ENV).asText()).isEqualTo("340000");
        assertThat(K6ConfigGenerator.warmupRatePerSec(rar)).isEqualTo(125);
        // число стадий прогрева - от 1 до числа стадий
        for (int bad : new int[]{0, 4}) {
            var op = custom("x", "work", "ramping-arrival-rate", Map.of("startRate", 0, "timeUnit", "1s", "preAllocatedVUs", 5, "maxVUs", 20,
                    "stages", stages, "warmup", Map.of("stages", bad)));
            assertThatThrownBy(() -> K6ConfigGenerator.generate(new ConfigInput(List.of(op), steps(100), 30, 30, 1, 1.5, 3, false)))
                    .hasMessageContaining("первые стадии");
        }
        // у constant-* стадий нет
        assertThatThrownBy(() -> K6ConfigGenerator.generate(new ConfigInput(List.of(custom("c", "work", "constant-vus",
                Map.of("vus", 2, "duration", "1m", "warmup", Map.of("stages", 1)))), steps(100), 30, 30, 1, 1.5, 3, false)))
                .hasMessageContaining("первые стадии");
    }

    @Test
    void preAllocatedAndMaxVusAreSetPerGeneratorAndMultipliedByGeneratorCount() {
        var op = custom("car", "work", "constant-arrival-rate", Map.of("rate", 500, "timeUnit", "1s", "duration", "5m",
                "preAllocatedVUs", 5, "maxVUs", 200));
        JsonNode one = K6ConfigGenerator.generate(new ConfigInput(List.of(op), steps(100), 30, 30, 1, 1.5, 3, false)).config().path("scenarios").path("car");
        JsonNode ten = K6ConfigGenerator.generate(new ConfigInput(List.of(op), steps(100), 30, 30, 10, 1.5, 3, false)).config().path("scenarios").path("car");
        assertThat(one.path("preAllocatedVUs").asInt()).isEqualTo(5);
        assertThat(one.path("maxVUs").asInt()).isEqualTo(200);
        // k6 делит значения между генераторами: на каждом остаётся ровно заданный запас
        assertThat(ten.path("preAllocatedVUs").asInt()).isEqualTo(50);
        assertThat(ten.path("maxVUs").asInt()).isEqualTo(2000);
        // сама нагрузка (rate, vus) на генераторы не умножается
        assertThat(ten.path("rate").asInt()).isEqualTo(500);
        var vus = custom("soak", "work", "constant-vus", Map.of("vus", 3, "duration", "1m"));
        assertThat(K6ConfigGenerator.generate(new ConfigInput(List.of(vus), steps(100), 30, 30, 10, 1.5, 3, false))
                .config().path("scenarios").path("soak").path("vus").asInt()).isEqualTo(10); // не меньше одного VU на генератор
    }

    @Test
    void scenarioWithWarmupGetsNoK6ThresholdsWithoutHelper() {
        var ops = List.of(custom("soak", "work", "constant-vus", Map.of("vus", 3, "duration", "1m", "warmup", Map.of("duration", "20s"))),
                custom("plain", "work", "constant-vus", Map.of("vus", 3, "duration", "1m")));
        var cfg = K6ConfigGenerator.generate(new ConfigInput(ops, steps(100), 30, 30, 1, 1.5, 3, false));
        // без helper тега фазы нет: порог k6 захватил бы прогрев, поэтому у сценария с прогревом порогов нет (SLA оценивает админка по времени)
        assertThat(cfg.config().path("thresholds").toString()).doesNotContain("scenario:soak").contains("scenario:plain}").doesNotContain("phase");
    }

    @Test
    void warmupStageDurationMayBeCompound() {
        var op = custom("rar", "work", "ramping-arrival-rate", Map.of("startRate", 10, "timeUnit", "1s", "preAllocatedVUs", 5,
                "stages", List.of(Map.of("duration", "1m30s", "target", 10), Map.of("duration", "5m", "target", 50)), "warmup", Map.of("stages", 1)));
        assertThat(K6ConfigGenerator.warmupEndSec(op)).isEqualTo(90);
    }

    @Test
    void warmupKeepsVusAtLeastTheGeneratorCountAndHonoursStartTime() {
        var ops = List.of(custom("rv", "work", "ramping-vus", Map.of("startVUs", 1, "stages", List.of(Map.of("duration", "1m", "target", 10)),
                "startTime", "30s", "warmup", warm("1m", 2))));
        var cfg = K6ConfigGenerator.generate(new ConfigInput(ops, steps(100), 30, 30, 6, 1.5, 3, true));
        JsonNode rv = cfg.config().path("scenarios").path("rv");
        assertThat(rv.path("startVUs").asInt()).isGreaterThanOrEqualTo(6);   // 2 VU на 6 генераторов не делятся: минимум по VU на генератор
        assertThat(rv.path("startTime").asText()).isEqualTo("30s");          // прогрев идёт со старта сценария
        assertThat(K6ConfigGenerator.warmupEndSec(ops.get(0))).isEqualTo(90);
        assertThat(rv.path("env").path(K6ConfigGenerator.WARMUP_END_ENV).asText()).isEqualTo("90000");
    }

    @Test
    void invalidWarmupIsRejected() {
        assertThatThrownBy(() -> K6ConfigGenerator.generate(new ConfigInput(List.of(custom("x", "x", "constant-vus",
                Map.of("vus", 5, "duration", "1m", "warmup", Map.of("duration", "1s")))), steps(100), 30, 30, 1, 1.5, 3, true)))
                .hasMessageContaining("прогрева");
        assertThatThrownBy(() -> K6ConfigGenerator.generate(new ConfigInput(List.of(custom("x", "x", "ramping-vus",
                Map.of("startVUs", 1, "stages", List.of(Map.of("duration", "1m", "target", 5)), "warmup", warm("1m", 0)))), steps(100), 30, 30, 1, 1.5, 3, true)))
                .hasMessageContaining("интенсивность прогрева");
        assertThatThrownBy(() -> K6ConfigGenerator.generate(new ConfigInput(List.of(custom("x", "x", "constant-vus",
                Map.of("vus", 5, "duration", "1m", "warmup", warm("1m", 3)))), steps(100), 30, 30, 1, 1.5, 3, true)))
                .hasMessageContaining("только у ramping-*");
        assertThatThrownBy(() -> K6ConfigGenerator.generate(new ConfigInput(List.of(custom("x", "x", "shared-iterations",
                Map.of("vus", 1, "iterations", 3, "warmup", Map.of("duration", "1m")))), steps(100), 30, 30, 1, 1.5, 3, true)))
                .hasMessageContaining("не поддерживается");
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
        assertThat(th.path("http_req_duration{scenario:h}").get(0).asText()).isEqualTo("p(95)<800");
        assertThat(th.path("http_req_failed{scenario:h}").get(0).asText()).isEqualTo("rate<0.01");
        assertThat(th.path("http_req_duration{scenario:c}").get(0).asText()).isEqualTo("p(99)<1200");
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
