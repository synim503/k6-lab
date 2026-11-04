package ru.k6lab.core.protocol;

import com.fasterxml.jackson.databind.JsonNode;
import ru.k6lab.core.protocol.Message.Command;
import ru.k6lab.core.protocol.Message.Sequenced;

import java.util.List;
import java.util.Map;

/** Все сообщения протокола. Имена типов заданы в {@link Message}. */
public final class Messages {

    private Messages() {
    }

    // ---------------------------------------------------------------- общие типы

    /** Состояние run на стороне агента. */
    public enum AgentRunState { PREPARING, PREPARED, RUNNING, STOPPING, EXITED }

    public record AgentRun(String runId, AgentRunState state, Long pid, Integer exitCode) {
    }

    public record LogLine(long ts, String stream, String line) {
    }

    /**
     * classicHistograms: агент принимает remote write k6 локально и отправляет в Prometheus классические серии
     * (_bucket{le}, _count, _sum) вместо native histograms - для Prometheus без --enable-feature=native-histograms.
     */
    public record PrometheusParams(String url, Map<String, String> headers, String pushInterval,
                                   boolean trendAsNativeHistogram, boolean classicHistograms, List<String> keepHistograms) {

        public PrometheusParams(String url, Map<String, String> headers, String pushInterval, boolean trendAsNativeHistogram) {
            this(url, headers, pushInterval, trendAsNativeHistogram, false, List.of());
        }

        public PrometheusParams(String url, Map<String, String> headers, String pushInterval,
                                boolean trendAsNativeHistogram, boolean classicHistograms) {
            this(url, headers, pushInterval, trendAsNativeHistogram, classicHistograms, List.of());
        }
    }

    public record DataRule(String path, String mode, boolean hasHeader) {
    }

    // ---------------------------------------------------------------- агент -> сервер

    /** Первое сообщение после handshake: что у агента на руках. */
    public record Hello(int protocolVersion, String agentVersion, String k6Version, long agentTime,
                        long lastAckedSeq, long lastSeq, List<AgentRun> runs) implements Message {
    }

    public record Heartbeat(long agentTime, double cpuPct, long ramUsedBytes, long ramTotalBytes,
                            long netRxBytesPerSec, long netTxBytesPerSec, double load1, double load5,
                            double load15, long diskFreeBytes, int openSockets, String k6Version,
                            String agentVersion, List<String> activeRuns) implements Message {
    }

    /** Ответ на PING: t1 - отправка PING сервером, t2 - приём агентом, t3 - отправка PONG агентом. */
    public record Pong(String pingId, long t1, long t2, long t3) implements Message {
    }

    public record CmdAck(String cmdId, boolean ok, String error) implements Message {
    }

    public record Prepared(long seq, long ts, String runId) implements Sequenced {
    }

    public record PrepareFailed(long seq, long ts, String runId, String reason) implements Sequenced {
    }

    public record Status(long seq, long ts, String runId, AgentRunState state, Long pid) implements Sequenced {
    }

    public record LogBatch(long seq, long ts, String runId, List<LogLine> lines, long dropped) implements Sequenced {
    }

    /** Только счётчики и gauge из REST API k6; перцентили здесь намеренно отсутствуют. */
    public record K6Status(long seq, long ts, String runId, long vus, long vusMax, long iterations,
                           long droppedIterations, String status, boolean paused, boolean running)
            implements Sequenced {
    }

    public record Summary(long seq, long ts, String runId, JsonNode summary) implements Sequenced {
    }

    /** Код 99 - thresholds crossed (не авария). */
    public record Exit(long seq, long ts, String runId, int code, String signal) implements Sequenced {
        public static final int THRESHOLDS_FAILED = 99;
    }

    // ---------------------------------------------------------------- сервер -> агент

    public record Registered(String permanentToken, String agentId) implements Message {
    }

    /** Подтверждение событий до upToSeq включительно; агент вправе удалить их из журнала. */
    public record Ack(long upToSeq, long serverTime) implements Message {
    }

    public record Ping(String pingId, long t1) implements Message {
    }

    public record Prepare(String cmdId, String runId, String artifactUrl, String artifactSha256,
                          JsonNode k6Config, Map<String, String> env, Map<String, String> secrets,
                          String executionSegment, String executionSegmentSequence,
                          Map<String, String> tags, List<DataRule> dataRules,
                          PrometheusParams prometheus, int k6ApiPort, List<String> systemTags,
                          String entrypoint, int logLinesPerSecond, List<String> k6Flags) implements Command {

        /** Без дополнительных флагов k6 (прежняя форма сообщения). */
        public Prepare(String cmdId, String runId, String artifactUrl, String artifactSha256,
                       JsonNode k6Config, Map<String, String> env, Map<String, String> secrets,
                       String executionSegment, String executionSegmentSequence,
                       Map<String, String> tags, List<DataRule> dataRules,
                       PrometheusParams prometheus, int k6ApiPort, List<String> systemTags,
                       String entrypoint, int logLinesPerSecond) {
            this(cmdId, runId, artifactUrl, artifactSha256, k6Config, env, secrets, executionSegment, executionSegmentSequence,
                    tags, dataRules, prometheus, k6ApiPort, systemTags, entrypoint, logLinesPerSecond, List.of());
        }
    }

    /**
     * startAt - абсолютное время старта (epoch ms) в шкале ЧАСОВ АГЕНТА: сервер сам переводит общий момент старта в
     * локальное время каждого генератора по замеренному смещению часов, поэтому агенту пересчитывать ничего не нужно.
     */
    public record Start(String cmdId, String runId, long startAt) implements Command {
    }

    public record Stop(String cmdId, String runId) implements Command {
    }

    public record Abort(String cmdId, String runId) implements Command {
    }

    public record Pause(String cmdId, String runId) implements Command {
    }

    public record Resume(String cmdId, String runId) implements Command {
    }

    public record ScaleVus(String cmdId, String runId, int vus) implements Command {
    }

    public record CancelPrepare(String cmdId, String runId) implements Command {
    }
}
