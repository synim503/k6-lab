package ru.k6lab.server.sut;

import lombok.Getter;
import lombok.Setter;

import java.util.ArrayList;
import java.util.List;

@Getter
@Setter
public class SutForm {
    private Long id;
    private String name;
    private String description;
    private String grafanaUrlTpl;
    private List<MetricForm> metrics = new ArrayList<>();

    @Getter
    @Setter
    public static class MetricForm {
        private String name;
        private String promql;
        private String unit;
        private SutMetric.Aggregation aggregation = SutMetric.Aggregation.AVG;
        private Double warnThreshold;
        private SutMetric.Direction warnDirection = SutMetric.Direction.ABOVE;

        public boolean blank() {
            return (name == null || name.isBlank()) && (promql == null || promql.isBlank());
        }
    }
}
