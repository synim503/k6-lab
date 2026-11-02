package ru.k6lab.agent;

import org.java_websocket.WebSocket;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.server.WebSocketServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.boot.info.BuildProperties;
import ru.k6lab.agent.config.AgentProperties;
import ru.k6lab.agent.config.TokenStore;
import ru.k6lab.agent.journal.EventJournal;
import ru.k6lab.agent.sys.SystemMetrics;
import ru.k6lab.agent.sys.VersionInfo;
import ru.k6lab.agent.ws.AgentConnection;
import ru.k6lab.agent.ws.UnsupportedCommandHandler;
import ru.k6lab.core.protocol.Message;
import ru.k6lab.core.protocol.Messages;
import ru.k6lab.core.protocol.ProtocolCodec;

import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/** Агент против мок-сервера WebSocket: регистрация, HELLO, ACK, PING/PONG, команды, реконнект, досылка журнала. */
class AgentConnectionTest {

    @TempDir
    Path home;

    MockServer server;
    AgentConnection connection;

    @BeforeEach
    void setUp() throws Exception {
        server = new MockServer();
        server.setReuseAddr(true);
        server.start();
        await().atMost(Duration.ofSeconds(5)).until(() -> server.getPort() > 0);
    }

    @AfterEach
    void tearDown() throws Exception {
        server.stop(500);
    }

    private AgentConnection agent(EventJournal journal) throws Exception {
        AgentProperties props = new AgentProperties();
        props.setHome(home);
        props.setServerUrl("ws://127.0.0.1:" + server.getPort() + "/agent/ws");
        props.setToken("one-time");
        props.setAgentId("gen-1");
        props.setReconnectMin(Duration.ofMillis(100));
        props.setReconnectMax(Duration.ofMillis(400));
        props.setHeartbeatInterval(Duration.ofMillis(200));
        Files.writeString(home.resolve("agent.properties"), "agent.token=one-time\nagent.agent-id=gen-1\n");
        TokenStore tokens = new TokenStore(props);
        VersionInfo versions = new VersionInfo(new StaticListableBeanFactory().getBeanProvider(BuildProperties.class), props);
        AgentConnection c = new AgentConnection(props, tokens, journal, new SystemMetrics(props), versions,
                new UnsupportedCommandHandler(), ru.k6lab.agent.ws.RunStateProvider.NONE);
        c.start();
        return c;
    }

    @Test
    void registersThenReconnectsWithPermanentTokenAndReplaysJournal() throws Exception {
        EventJournal journal = new EventJournal(home.resolve("data"), 1_000_000);
        journal.append(seq -> new Messages.Prepared(seq, 1, "run-1"));
        connection = agent(journal);

        // первое подключение: одноразовый токен, HELLO, досылка журнала
        await().atMost(Duration.ofSeconds(10)).until(() -> server.received(Messages.Hello.class) != null);
        assertThat(server.authHeaders.get(0)).isEqualTo("Bearer one-time");
        Messages.Hello hello = server.received(Messages.Hello.class);
        assertThat(hello.protocolVersion()).isEqualTo(Message.PROTOCOL_VERSION);
        await().atMost(Duration.ofSeconds(5)).until(() -> server.received(Messages.Prepared.class) != null);

        // heartbeat идёт
        await().atMost(Duration.ofSeconds(5)).until(() -> server.received(Messages.Heartbeat.class) != null);

        // сервер выдаёт постоянный токен -> агент сохраняет его и сам переподключается с ним
        server.sendToAll(new Messages.Registered("permanent-token", "gen-1"));
        await().atMost(Duration.ofSeconds(10)).until(() -> server.authHeaders.size() >= 2);
        assertThat(server.authHeaders.get(1)).isEqualTo("Bearer permanent-token");
        await().atMost(Duration.ofSeconds(10)).until(() -> server.all(Messages.Hello.class).size() >= 2);
        await().atMost(Duration.ofSeconds(5)).until(() -> server.sockets.size() == 1);

        // дальше: подтверждение событий, PING и команды на новом соединении
        server.sendToAll(new Messages.Ack(1, System.currentTimeMillis()));
        server.sendToAll(new Messages.Ping("ping-1", 111));
        server.sendToAll(new Messages.Start("cmd-1", "run-1", 1));

        await().atMost(Duration.ofSeconds(5)).until(() -> server.received(Messages.Pong.class) != null);
        Messages.Pong pong = server.received(Messages.Pong.class);
        assertThat(pong.pingId()).isEqualTo("ping-1");
        assertThat(pong.t1()).isEqualTo(111);
        assertThat(pong.t3()).isGreaterThanOrEqualTo(pong.t2());

        await().atMost(Duration.ofSeconds(5)).until(() -> server.received(Messages.CmdAck.class) != null);
        assertThat(server.received(Messages.CmdAck.class).ok()).isFalse();

        // повторная доставка той же команды подтверждается успешно и не выполняется
        server.sendToAll(new Messages.Start("cmd-1", "run-1", 1));
        await().atMost(Duration.ofSeconds(5)).until(
                () -> server.all(Messages.CmdAck.class).stream().anyMatch(Messages.CmdAck::ok));

        await().atMost(Duration.ofSeconds(5)).until(() -> journal.unacked().isEmpty());
        await().atMost(Duration.ofSeconds(5)).until(
                () -> Files.readString(home.resolve("agent.properties")).contains("agent.token=permanent-token"));

        // обрыв связи: агент переподключается с постоянным токеном
        server.closeAll();
        await().atMost(Duration.ofSeconds(10)).until(() -> server.authHeaders.size() >= 3);
        assertThat(server.authHeaders.get(2)).isEqualTo("Bearer permanent-token");
        await().atMost(Duration.ofSeconds(5)).until(() -> server.all(Messages.Hello.class).size() >= 3);
    }

    @Test
    void keepsTryingWhileServerIsUnavailable() throws Exception {
        int port = server.getPort();
        server.stop(200);
        EventJournal journal = new EventJournal(home.resolve("data"), 1_000_000);
        AgentProperties unused = new AgentProperties();
        connection = agent(journal);
        Thread.sleep(600);
        assertThat(connection.isConnected()).isFalse();

        server = new MockServer(port);
        server.setReuseAddr(true);
        server.start();
        await().atMost(Duration.ofSeconds(15)).until(() -> server.received(Messages.Hello.class) != null);
        assertThat(connection.isConnected()).isTrue();
    }

    static class MockServer extends WebSocketServer {
        final List<Message> inbox = new CopyOnWriteArrayList<>();
        final List<String> authHeaders = new CopyOnWriteArrayList<>();
        final List<WebSocket> sockets = new CopyOnWriteArrayList<>();
        final AtomicReference<Throwable> error = new AtomicReference<>();

        MockServer() {
            this(0);
        }

        MockServer(int port) {
            super(new InetSocketAddress("127.0.0.1", port));
        }

        @Override
        public void onStart() {
        }

        @Override
        public void onOpen(WebSocket conn, ClientHandshake handshake) {
            authHeaders.add(handshake.getFieldValue("Authorization"));
            sockets.add(conn);
        }

        @Override
        public void onClose(WebSocket conn, int code, String reason, boolean remote) {
            sockets.remove(conn);
        }

        @Override
        public void onMessage(WebSocket conn, String message) {
            inbox.add(ProtocolCodec.decode(message));
        }

        @Override
        public void onError(WebSocket conn, Exception ex) {
            error.set(ex);
        }

        void sendToAll(Message m) {
            String json = ProtocolCodec.encode(m);
            sockets.forEach(s -> s.send(json));
        }

        void closeAll() {
            sockets.forEach(WebSocket::close);
        }

        <T extends Message> T received(Class<T> type) {
            return inbox.stream().filter(type::isInstance).map(type::cast).findFirst().orElse(null);
        }

        <T extends Message> List<T> all(Class<T> type) {
            return inbox.stream().filter(type::isInstance).map(type::cast).toList();
        }
    }
}
