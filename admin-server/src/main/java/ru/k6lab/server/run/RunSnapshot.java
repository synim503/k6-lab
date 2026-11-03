package ru.k6lab.server.run;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;
import java.util.Map;

/**
 * Snapshot запуска: версия скрипта, операции, ступени, SLA, критерии. Результаты и отчёты строятся по нему,
 * поэтому не зависят от последующих правок нагрузки скрипта.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record RunSnapshot(ScriptInfo script, Map<String, String> env, List<OpInfo> operations, List<StepInfo> steps,
                          int gracefulStopSec, int gracefulRampDownSec, List<DataFileInfo> dataFiles,
                          Map<String, Object> stopCriteria, Map<String, Object> successCriteria, boolean usesHelper,
                          List<GeneratorInfo> generators, String segmentSequence, List<String> warnings) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ScriptInfo(Long id, String name, Long versionId, int versionNumber, String sha256, String entrypoint,
                             String source, String commit, String branch) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record OpInfo(String name, String execFn, double ratePerSec, double expectedDurationSec, int slaPercentile,
                         double slaThresholdMs, double maxErrorPct, String failureSource, boolean enabled) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record StepInfo(int no, String label, double levelPct, int rampSec, int holdSec) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record DataFileInfo(String path, String mode, boolean hasHeader, boolean consumed) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record GeneratorInfo(Long id, String name, int weight, String segment) {
    }
}
