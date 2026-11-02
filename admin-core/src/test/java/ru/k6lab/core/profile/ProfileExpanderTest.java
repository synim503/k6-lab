package ru.k6lab.core.profile;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import ru.k6lab.core.util.ExprEval;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProfileExpanderTest {

    private static final ObjectMapper M = new ObjectMapper();

    @Test
    void maxSearchBuildsLinearSteps() {
        var steps = ProfileExpander.expand(ProfileType.MAX_SEARCH,
                Map.of("startPct", 50, "stepPct", 10, "steps", 5, "rampSec", 60, "holdSec", 300), null);
        assertThat(steps).extracting(StepPlan::levelPct).containsExactly(50.0, 60.0, 70.0, 80.0, 90.0);
        assertThat(steps).extracting(StepPlan::no).containsExactly(1, 2, 3, 4, 5);
        assertThat(steps).allMatch(s -> s.rampSec() == 60 && s.holdSec() == 300);
    }

    @Test
    void maxSearchIsCappedButStressIsNot() {
        Map<String, Object> p = Map.of("startPct", 100, "stepPct", 200, "steps", 10, "rampSec", 10, "holdSec", 60);
        assertThatThrownBy(() -> ProfileExpander.expand(ProfileType.MAX_SEARCH, p, null)).hasMessageContaining("превышает");
        var steps = ProfileExpander.expand(ProfileType.STRESS, p, null);
        assertThat(steps).hasSize(10);
        assertThat(steps.get(9).levelPct()).isEqualTo(1900.0);
    }

    @Test
    void confirmationAndReliabilityAreSingleStep() {
        var c = ProfileExpander.expand(ProfileType.CONFIRMATION, Map.of("levelPct", 85, "rampSec", 120, "holdSec", 600), null);
        assertThat(c).singleElement().satisfies(s -> {
            assertThat(s.levelPct()).isEqualTo(85);
            assertThat(s.holdSec()).isEqualTo(600);
        });
        var r = ProfileExpander.expand(ProfileType.RELIABILITY, Map.of("levelPct", 70, "rampSec", 60, "durationSec", 14400), null);
        assertThat(r.get(0).holdSec()).isEqualTo(14400);
    }

    @Test
    void spikeAlternatesBaseAndPeak() {
        var s = ProfileExpander.expand(ProfileType.SPIKE, Map.of("basePct", 30, "peakPct", 120, "peakDurationSec", 60,
                "peaks", 2, "intervalSec", 300, "spikeRampSec", 5), null);
        assertThat(s).extracting(StepPlan::levelPct).containsExactly(30.0, 120.0, 30.0, 120.0, 30.0);
        assertThat(s).extracting(StepPlan::holdSec).containsExactly(300, 60, 300, 60, 300);
        assertThat(s.get(1).label()).isEqualTo("Пик 1");
        assertThatThrownBy(() -> ProfileExpander.expand(ProfileType.SPIKE, Map.of("basePct", 50, "peakPct", 40,
                "peakDurationSec", 60, "peaks", 1, "intervalSec", 60), null)).hasMessageContaining("пика");
    }

    @Test
    void customTakesStepTable() {
        var s = ProfileExpander.expand(ProfileType.CUSTOM, Map.of("steps", List.of(
                Map.of("levelPct", 10, "rampSec", 5, "holdSec", 30),
                Map.of("levelPct", "20,5", "rampSec", 5, "holdSec", 30, "label", "вторая"))), null);
        assertThat(s).extracting(StepPlan::levelPct).containsExactly(10.0, 20.5);
        assertThat(s.get(1).label()).isEqualTo("вторая");
        assertThatThrownBy(() -> ProfileExpander.expand(ProfileType.CUSTOM, Map.of(), null)).hasMessageContaining("хотя бы одну");
    }

    @Test
    void validatesInput() {
        assertThatThrownBy(() -> ProfileExpander.expand(ProfileType.CONFIRMATION, Map.of("levelPct", 85, "rampSec", 1, "holdSec", 0), null))
                .hasMessageContaining("нет удержания");
        assertThatThrownBy(() -> ProfileExpander.expand(ProfileType.CONFIRMATION, Map.of("levelPct", "abc", "holdSec", 5), null))
                .hasMessageContaining("числом");
        assertThatThrownBy(() -> ProfileExpander.expand(ProfileType.MAX_SEARCH, Map.of("startPct", 10, "stepPct", 0, "steps", 3, "holdSec", 5), null))
                .hasMessageContaining("Шаг");
    }

    @Test
    void parametrisedTemplateExpandsViaSafeExpressions() throws Exception {
        JsonNode def = M.readTree("""
                {"params":[{"name":"start","default":20},{"name":"inc","default":15},{"name":"n","default":3},
                           {"name":"ramp","default":30},{"name":"hold","default":120}],
                 "blocks":[
                   {"steps":[{"level":"10","ramp":"0","hold":"60","label":"прогрев"}]},
                   {"repeat":{"count":"n","var":"i"},"steps":[
                      {"level":"start+i*inc","ramp":"ramp","hold":"hold","label":"рост"},
                      {"level":"(start+i*inc)/2","ramp":"ramp","hold":"hold/2","label":"откат"}]}]}
                """);
        var steps = ProfileExpander.expand(ProfileType.TEMPLATE, Map.of("n", 2, "inc", 10), def);
        assertThat(steps).extracting(StepPlan::levelPct).containsExactly(10.0, 20.0, 10.0, 30.0, 15.0);
        assertThat(steps).extracting(StepPlan::holdSec).containsExactly(60, 120, 60, 120, 60);
        assertThat(steps).extracting(StepPlan::no).containsExactly(1, 2, 3, 4, 5);
        assertThatThrownBy(() -> ProfileExpander.expand(ProfileType.TEMPLATE, Map.of(), M.readTree("{}"))).hasMessageContaining("блоков");
        JsonNode evil = M.readTree("{\"blocks\":[{\"steps\":[{\"level\":\"Runtime.exec(1)\",\"hold\":\"5\"}]}]}");
        assertThatThrownBy(() -> ProfileExpander.expand(ProfileType.TEMPLATE, Map.of(), evil)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void timelineHasContiguousWindows() {
        var steps = List.of(new StepPlan(1, 10, 30, 60, "a"), new StepPlan(2, 20, 10, 50, "b"));
        var w = StepWindow.of(steps);
        assertThat(w.get(0)).isEqualTo(new StepWindow(1, 10, 0, 30, 90, "a"));
        assertThat(w.get(1)).isEqualTo(new StepWindow(2, 20, 90, 100, 150, "b"));
        assertThat(StepWindow.totalSec(steps)).isEqualTo(150);
    }

    @Test
    void expressionEvaluator() {
        Map<String, Double> v = Map.of("a", 4.0, "b", 3.0);
        assertThat(ExprEval.eval("a + b * 2", v)).isEqualTo(10.0);
        assertThat(ExprEval.eval("(a + b) * 2", v)).isEqualTo(14.0);
        assertThat(ExprEval.eval("-a + 10", v)).isEqualTo(6.0);
        assertThat(ExprEval.eval("2 ^ 3 ^ 2", v)).isEqualTo(512.0);
        assertThat(ExprEval.eval("max(a, b) + min(a, b) + round(2.6) + ceil(1.1) + floor(1.9) + abs(0-5)", v)).isEqualTo(4 + 3 + 3 + 2 + 1 + 5.0);
        assertThatThrownBy(() -> ExprEval.eval("1/0", v)).hasMessageContaining("деление");
        assertThatThrownBy(() -> ExprEval.eval("x + 1", v)).hasMessageContaining("переменная");
        assertThatThrownBy(() -> ExprEval.eval("1 +", v)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ExprEval.eval("foo(1)", v)).hasMessageContaining("функция");
        assertThatThrownBy(() -> ExprEval.eval("1 2", v)).hasMessageContaining("лишние");
    }
}
