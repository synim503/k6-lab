package ru.k6lab.core.protocol;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import ru.k6lab.core.protocol.Messages.*;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProtocolCodecTest {

    private static JsonNode json(String s) throws Exception {
        return ProtocolCodec.mapper().readTree(s);
    }

    @Test
    void typeFieldIsWrittenAndRoundTrips() throws Exception {
        List<Message> all = List.of(
                new Hello(1, "0.1.0", "1.7.1", 10L, 5L, 7L, List.of(new AgentRun("r1", AgentRunState.RUNNING, 42L, null))),
                new Heartbeat(1L, 12.5, 1, 2, 3, 4, 0.1, 0.2, 0.3, 5, 6, "1.7.1", "0.1.0", List.of("r1")),
                new Pong("p", 1, 2, 3),
                new CmdAck("c", false, "boom"),
                new Prepared(1, 2, "r1"),
                new PrepareFailed(2, 3, "r1", "sha mismatch"),
                new Status(3, 4, "r1", AgentRunState.PREPARED, 5L),
                new LogBatch(4, 5, "r1", List.of(new LogLine(1, "stdout", "hi")), 3),
                new K6Status(5, 6, "r1", 10, 20, 100, 0, "running", false, true),
                new Summary(6, 7, "r1", json("{\"a\":1}")),
                new Exit(7, 8, "r1", 99, null),
                new Registered("tok", "gen-1"),
                new Ack(7, 99),
                new Ping("p", 1),
                new Prepare("c1", "r1", "http://x/a", "abc", json("{\"scenarios\":{}}"), Map.of("A", "b"),
                        Map.of(), "0:1/3", "0,1/3,1", Map.of("t", "v"),
                        List.of(new DataRule("data/u.csv", "SPLIT", true)),
                        new PrometheusParams("http://prom/api/v1/write", Map.of(), "5s", true), 6565,
                        List.of("scenario", "status"), "script.js", 200),
                new Start("c2", "r1", 123456L), new Stop("c3", "r1"), new Abort("c4", "r1"),
                new Pause("c5", "r1"), new Resume("c6", "r1"), new ScaleVus("c7", "r1", 50),
                new CancelPrepare("c8", "r1"));

        for (Message m : all) {
            String wire = ProtocolCodec.encode(m);
            assertThat(wire).contains("\"type\":\"");
            assertThat(ProtocolCodec.decode(wire)).isEqualTo(m);
        }
    }

    @Test
    void wireNamesMatchSpecification() {
        assertThat(ProtocolCodec.encode(new Exit(1, 1, "r", 0, null))).contains("\"type\":\"EXIT\"");
        assertThat(ProtocolCodec.encode(new LogBatch(1, 1, "r", List.of(), 0))).contains("\"type\":\"LOG_BATCH\"");
        assertThat(ProtocolCodec.encode(new CancelPrepare("c", "r"))).contains("\"type\":\"CANCEL_PREPARE\"");
        assertThat(ProtocolCodec.encode(new ScaleVus("c", "r", 1))).contains("\"type\":\"SCALE_VUS\"");
    }

    @Test
    void unknownFieldsAreIgnored() {
        Message m = ProtocolCodec.decode("{\"type\":\"ACK\",\"upToSeq\":5,\"serverTime\":1,\"future\":true}");
        assertThat(m).isEqualTo(new Ack(5, 1));
    }

    @Test
    void unknownTypeIsRejected() {
        assertThatThrownBy(() -> ProtocolCodec.decode("{\"type\":\"NOPE\"}"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void sequencedAndCommandMarkers() {
        assertThat(new Prepared(1, 1, "r")).isInstanceOf(Message.Sequenced.class);
        assertThat(new Start("c", "r", 1)).isInstanceOf(Message.Command.class);
        assertThat(new Heartbeat(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, null, null, List.of()))
                .isNotInstanceOf(Message.Sequenced.class);
    }
}
