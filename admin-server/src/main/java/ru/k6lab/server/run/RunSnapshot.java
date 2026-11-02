package ru.k6lab.server.run;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;
import java.util.Map;

/**
 * Snapshot сценария на момент запуска: модель, профиль, ступени, SLA, критерии, SUT. Результаты и отчёты
 * строятся по нему, поэтому не зависят от последующих правок сценария, модели и профиля.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record RunSnapshot(ScenarioInfo scenario, ScriptInfo script, ModelInfo model, ProfileInfo profile,
                          List<StepInfo> steps, List<DataFileInfo> dataFiles, Map<String, Object> stopCriteria,
                          Map<String, Object> successCriteria, SutInfo sut, boolean usesHelper,
                          List<GeneratorInfo> generators, String segmentSequence, List<String> warnings) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ScenarioInfo(Long id, String name, String description, Map<String, String> env, boolean expertMode,
                               boolean followBranch) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ScriptInfo(Long id, String name, Long versionId, int versionNumber, String sha256, String entrypoint,
                             String source, String commit, String branch) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ModelInfo(Long id, String name, List<OpInfo> operations) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record OpInfo(String name, String execFn, String mode, double ratePerSec, double expectedDurationSec, Integer pacingMs,
                         int slaPercentile, double slaThresholdMs, double maxErrorPct, String failureSource, boolean enabled,
                         Double fixedLevelPct) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ProfileInfo(Long id, String name, String type, Map<String, Object> params, int gracefulStopSec,
                              int gracefulRampDownSec) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record StepInfo(int no, String label, double levelPct, int rampSec, int holdSec) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record DataFileInfo(String path, String mode, boolean hasHeader, boolean consumed) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record SutInfo(Long id, String name, String grafanaUrlTpl, List<SutMetricInfo> metrics) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record SutMetricInfo(String name, String promql, String unit, String aggregation, Double warnThreshold,
                                String warnDirection) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record GeneratorInfo(Long id, String name, int weight, String segment) {
    }
}
