package ru.k6lab.server;

import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import ru.k6lab.core.protocol.Message;
import ru.k6lab.core.protocol.Messages;
import ru.k6lab.core.protocol.ProtocolCodec;
import ru.k6lab.server.agent.AgentSessionRegistry;
import ru.k6lab.server.agent.TokenService;
import ru.k6lab.server.generator.Generator;
import ru.k6lab.server.generator.GeneratorRepository;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/** Протокол агент-сервер на реальном порту агентов: аутентификация, регистрация, HELLO, идемпотентность, часы. */
class AgentProtocolIT extends AbstractIT {

    @Autowired
    GeneratorRepository generators;
    @Autowired
    TokenService tokens;
    @Autowired
    AgentSessionRegistry registry;
    @Autowired
    JdbcTemplate jdbc;

    /** Тестовый «агент». */
    static class TestAgent extends WebSocketClient {
        final List<Message> inbox = new CopyOnWriteArrayList<>();
        volatile boolean errored;

        TestAgent(int port, String token) {
            super(URI.create("ws://127.0.0.1:" + port + "/agent/ws"), Map.of("Authorization", "Bearer " + token));
        }

        @Override
        public void onOpen(ServerHandshake h) {
        }

        @Override
        public void onMessage(String text) {
            inbox.add(ProtocolCodec.decode(text));
        }

        @Override
        public void onClose(int code, String reason, boolean remote) {
        }

        @Override
        public void onError(Exception ex) {
            errored = true;
        }

        void send(Message m) {
            send(ProtocolCodec.encode(m));
        }

        <T extends Message> T first(Class<T> type) {
            return inbox.stream().filter(type::isInstance).map(type::cast).findFirst().orElse(null);
        }
    }

    private Generator newGenerator() {
        Generator g = new Generator();
        g.setName("it-" + UUID.randomUUID().toString().substring(0, 8));
        g.setHost("10.0.0.1");
        g.setInstallDir("/opt/k6-agent");
        g.setServerUrl("ws://localhost:" + AGENT_PORT + "/agent/ws");
        g.setStatus(Generator.Status.INSTALLING);
        return generators.save(g);
    }

    private Messages.Hello hello(long lastSeq) {
        return new Messages.Hello(Message.PROTOCOL_VERSION, "0.1.0-test", "1.7.1", System.currentTimeMillis(), 0, lastSeq, List.of());
    }

    @Test
    void registrationHandshakeAndIdempotentEvents() throws Exception {
        Generator g = newGenerator();
        String oneTime = tokens.issueOneTime(g.getId());

        // 1) первое подключение по одноразовому токену -> REGISTERED с постоянным токеном
        TestAgent a1 = new TestAgent(AGENT_PORT, oneTime);
        assertThat(a1.connectBlocking(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        await().atMost(Duration.ofSeconds(10)).until(() -> a1.first(Messages.Registered.class) != null);
        String permanent = a1.first(Messages.Registered.class).permanentToken();
        assertThat(permanent).isNotBlank().isNotEqualTo(oneTime);

        a1.send(hello(0));
        await().atMost(Duration.ofSeconds(10)).until(() -> registry.isConnected(g.getId()));
        assertThat(generators.findById(g.getId()).orElseThrow().getStatus()).isEqualTo(Generator.Status.ONLINE);
        assertThat(generators.findById(g.getId()).orElseThrow().getAgentVersion()).isEqualTo("0.1.0-test");
        // в БД только хеши токенов
        assertThat(jdbc.queryForObject("select agent_token_hash from generator where id = ?", String.class, g.getId()))
                .hasSize(64).isNotEqualTo(permanent);

        // сервер подтвердил HELLO (ACK) и запросил замер часов (PING)
        await().atMost(Duration.ofSeconds(10)).until(() -> a1.first(Messages.Ack.class) != null
                && a1.first(Messages.Ping.class) != null);

        // 2) PONG: агент спешит на 1000 мс -> смещение около +1000 (минус половина задержки ответа теста)
        Messages.Ping ping = a1.first(Messages.Ping.class);
        a1.send(new Messages.Pong(ping.pingId(), ping.t1(), ping.t1() + 1000, ping.t1() + 1000));
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            Long off = generators.findById(g.getId()).orElseThrow().getClockOffsetMs();
            assertThat(off).isNotNull().isBetween(700L, 1100L);
        });

        // 3) события с seq: дубль и повторная отправка не обрабатываются дважды
        RecordingConfig.EVENTS.clear();
        a1.send(new Messages.Prepared(1, 1, "run-1"));
        a1.send(new Messages.Prepared(1, 1, "run-1"));
        a1.send(new Messages.LogBatch(2, 2, "run-1", List.of(new Messages.LogLine(1, "stdout", "x")), 0));
        a1.send(new Messages.LogBatch(2, 2, "run-1", List.of(new Messages.LogLine(1, "stdout", "x")), 0));
        a1.send(new Messages.Exit(3, 3, "run-1", 99, null));
        await().atMost(Duration.ofSeconds(10)).until(() -> RecordingConfig.EVENTS.size() >= 3);
        Thread.sleep(500);
        assertThat(RecordingConfig.EVENTS).hasSize(3);
        assertThat(RecordingConfig.EVENTS.get(2)).isInstanceOfSatisfying(Messages.Exit.class, e -> assertThat(e.code()).isEqualTo(99));
        // сервер подтверждает seq=3, и last_seq сохраняется
        await().atMost(Duration.ofSeconds(10)).until(() -> a1.inbox.stream()
                .anyMatch(m -> m instanceof Messages.Ack ack && ack.upToSeq() == 3));
        await().atMost(Duration.ofSeconds(10)).until(() -> generators.findById(g.getId()).orElseThrow().getLastSeq() == 3);

        // 4) heartbeat -> метрики попадают в БД батчем
        a1.send(new Messages.Heartbeat(System.currentTimeMillis(), 55.5, 100, 200, 1, 2, 0.5, 0.4, 0.3, 1_000_000_000L, 42,
                "1.7.1", "0.1.0-test", List.of()));
        await().atMost(Duration.ofSeconds(15)).until(() -> jdbc.queryForObject(
                "select count(*) from generator_metrics where generator_id = ?", Integer.class, g.getId()) > 0);

        // 5) реконнект с постоянным токеном; одноразовый токен гасится; одноразовым больше не войти
        a1.closeBlocking();
        TestAgent a2 = new TestAgent(AGENT_PORT, permanent);
        assertThat(a2.connectBlocking(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        a2.send(hello(3));
        await().atMost(Duration.ofSeconds(10)).until(() -> registry.isConnected(g.getId()));
        assertThat(a2.first(Messages.Registered.class)).isNull();
        assertThat(jdbc.queryForObject("select one_time_token_hash from generator where id = ?", String.class, g.getId())).isNull();
        TestAgent stale = new TestAgent(AGENT_PORT, oneTime);
        assertThat(stale.connectBlocking(10, java.util.concurrent.TimeUnit.SECONDS)).isFalse();

        // 6) повторный HELLO агента с обнулённым журналом сбрасывает last_seq (иначе все события сочтутся дублями)
        a2.closeBlocking();
        TestAgent a3 = new TestAgent(AGENT_PORT, permanent);
        assertThat(a3.connectBlocking(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        a3.send(hello(0));
        await().atMost(Duration.ofSeconds(10)).until(() -> a3.inbox.stream()
                .anyMatch(m -> m instanceof Messages.Ack ack && ack.upToSeq() == 0));
        RecordingConfig.EVENTS.clear();
        a3.send(new Messages.Prepared(1, 1, "run-2"));
        await().atMost(Duration.ofSeconds(10)).until(() -> !RecordingConfig.EVENTS.isEmpty());

        // 7) разрыв соединения -> OFFLINE
        a3.closeBlocking();
        await().atMost(Duration.ofSeconds(10)).until(
                () -> generators.findById(g.getId()).orElseThrow().getStatus() == Generator.Status.OFFLINE);
    }

    @Test
    void badOrMissingTokenIsRejected() throws Exception {
        TestAgent bad = new TestAgent(AGENT_PORT, "no-such-token");
        assertThat(bad.connectBlocking(10, java.util.concurrent.TimeUnit.SECONDS)).isFalse();
        TestAgent empty = new TestAgent(AGENT_PORT, "");
        assertThat(empty.connectBlocking(10, java.util.concurrent.TimeUnit.SECONDS)).isFalse();
    }

    @Test
    void expiredOneTimeTokenIsRejected() throws Exception {
        Generator g = newGenerator();
        String token = tokens.issueOneTime(g.getId());
        jdbc.update("update generator set one_time_expires = now() - interval '1 minute' where id = ?", g.getId());
        TestAgent a = new TestAgent(AGENT_PORT, token);
        assertThat(a.connectBlocking(10, java.util.concurrent.TimeUnit.SECONDS)).isFalse();
    }

    @Test
    void agentPortServesOnlyAgentPathsAndMainPortHidesThem() throws Exception {
        HttpClient http = HttpClient.newHttpClient();
        HttpResponse<String> ui = http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + AGENT_PORT + "/login")).build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(ui.statusCode()).isEqualTo(404);
        HttpResponse<String> health = http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + AGENT_PORT + "/actuator/health")).build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(health.statusCode()).isEqualTo(404);
        HttpResponse<String> hidden = http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + httpPort + "/agent/ws")).build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(hidden.statusCode()).isEqualTo(404);
        HttpResponse<String> main = http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + httpPort + "/actuator/health")).build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(main.statusCode()).isEqualTo(200);
    }
}
