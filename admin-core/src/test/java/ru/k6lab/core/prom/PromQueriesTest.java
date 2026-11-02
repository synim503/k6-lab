package ru.k6lab.core.prom;

import org.junit.jupiter.api.Test;
import ru.k6lab.core.prom.PromQueries.FailureSource;
import ru.k6lab.core.prom.PromQueries.Names;
import ru.k6lab.core.util.PromQlTemplate;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class PromQueriesTest {

    private final Names n = Names.defaults();

    @Test
    void percentilesAreComputedFromHistogramsAfterSummingAllGenerators() {
        String q = PromQueries.percentileMs(n, true, 95, 42, 60);
        // сначала sum по всем генераторам (increase гистограмм), потом histogram_quantile - не усреднение перцентилей
        assertThat(q).isEqualTo("histogram_quantile(0.95, sum by (scenario) (increase(k6_op_duration_seconds{test_run_id=\"42\"}[60s]))) * 1000");
        assertThat(PromQueries.percentileMs(n, false, 99, 7, 30)).contains("k6_iteration_duration_seconds").contains("0.99").contains("[30s]");
        assertThat(q).doesNotContain("avg(").doesNotContain("avg by");
    }

    @Test
    void rateUsesIncreaseOverTheWindow() {
        assertThat(PromQueries.ratePerSec(n, 5, 120)).isEqualTo(
                "sum by (scenario) (increase(k6_iterations_total{test_run_id=\"5\"}[120s])) / 120");
    }

    @Test
    void errorPercentFromCountersForHelperAndFromRatesOtherwise() {
        String helper = PromQueries.errorPct(n, FailureSource.HELPER, 1, 60);
        assertThat(helper).contains("k6_op_errors_total").contains("k6_op_count_total").contains("/ sum by (scenario)");
        assertThat(PromQueries.errorPct(n, FailureSource.HTTP, 1, 60)).contains("k6_http_req_failed_rate").contains("avg_over_time");
        assertThat(PromQueries.errorPct(n, FailureSource.CHECKS, 1, 60)).contains("1 - avg by (scenario)").contains("k6_checks_rate");
    }

    @Test
    void droppedAndVusAndWindowIsAtLeastOneSecond() {
        assertThat(PromQueries.dropped(n, 3, 0)).contains("k6_dropped_iterations_total").contains("[1s]");
        assertThat(PromQueries.totalVus(n, 3)).isEqualTo("sum(k6_vus{test_run_id=\"3\"})");
    }

    @Test
    void metricNamesAreConfigurable() {
        Names custom = new Names("x_dur", "x_iter_dur", "x_iters", "x_drop", "x_cnt", "x_err", "x_failed", "x_checks", "x_vus");
        assertThat(PromQueries.percentileMs(custom, true, 90, 1, 15)).contains("x_dur{");
        assertThat(PromQueries.ratePerSec(custom, 1, 15)).contains("x_iters{");
    }

    @Test
    void placeholdersAreSubstituted() {
        Instant start = Instant.parse("2026-01-01T10:00:00Z");
        Instant end = Instant.parse("2026-01-01T10:05:00Z");
        String q = PromQlTemplate.render("avg(rate(cpu[{{range}}])) + {{start}} + {{end}}", start, end);
        assertThat(q).isEqualTo("avg(rate(cpu[300s])) + " + start.getEpochSecond() + " + " + end.getEpochSecond());
        assertThat(PromQlTemplate.renderUrl("https://g/d?from={{start}}&to={{end}}&run={{run_id}}", start, end, 9))
                .isEqualTo("https://g/d?from=" + start.toEpochMilli() + "&to=" + end.toEpochMilli() + "&run=9");
    }
}
