package ru.k6lab.agent.ws;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import ru.k6lab.agent.config.AgentProperties;
import ru.k6lab.agent.config.TokenStore;
import ru.k6lab.agent.journal.EventJournal;
import ru.k6lab.agent.sys.SystemMetrics;
import ru.k6lab.agent.sys.VersionInfo;
import ru.k6lab.core.protocol.Message;
import ru.k6lab.core.protocol.Messages;
import ru.k6lab.core.protocol.ProtocolCodec;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * WebSocket-соединение с сервером. Инициирует агент; при обрыве переподключается с exponential backoff.
 * Работа k6 от состояния связи не зависит.
 */
@Component
public class AgentConnection {

    private static final Logger log = LoggerFactory.getLogger(AgentConnection.class);

    private final AgentProperties props;
    private final TokenStore tokens;
    private final EventJournal journal;
    private final SystemMetrics metrics;
    private final VersionInfo versions;
    private final CommandHandler commands;
    private final RunStateProvider runState;
    private final Backoff backoff;

    // единственный НЕ daemon-поток: держит JVM живой, т.к. веб-сервера у агента нет
    private final ScheduledExecutorService exec = Executors.newScheduledThreadPool(2, r -> {
        Thread t = new Thread(r, "agent-ws");
        t.setDaemon(false);
        return t;
    });

    private final HttpClient http = HttpClient.newHttpClient();
    private volatile WebSocket socket;
    private CompletableFuture<?> sendChain = CompletableFuture.completedFuture(null);
    private final Deque<String> recentCommands = new ArrayDeque<>();
    private final Set<String> recentCommandSet = new HashSet<>();

    public AgentConnection(AgentProperties props, TokenStore tokens, EventJournal journal, SystemMetrics metrics,
                           VersionInfo versions, CommandHandler commands, RunStateProvider runState) {
        this.props = props;
        this.tokens = tokens;
        this.journal = journal;
        this.metrics = metrics;
        this.versions = versions;
        this.commands = commands;
        this.runState = runState;
        this.backoff = new Backoff(props.getReconnectMin(), props.getReconnectMax());
    }

    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        if (props.getServerUrl() == null || props.getServerUrl().isBlank()) {
            log.error("agent.server-url не задан - агент не может подключиться");
            return;
        }
        log.info("Агент {} стартовал, сервер {}", props.getAgentId(), props.getServerUrl());
        exec.execute(this::connect);
        long hb = props.getHeartbeatInterval().toMillis();
        exec.scheduleWithFixedDelay(this::heartbeat, hb, hb, TimeUnit.MILLISECONDS);
    }

    /** Публикует событие: оно уже записано в журнал, здесь только отправка при наличии связи. */
    public void publish(Message.Sequenced event) {
        send(event);
    }

    public boolean isConnected() {
        return socket != null;
    }

    // ------------------------------------------------------------------ соединение

    private void connect() {
        String token = tokens.current();
        if (token == null || token.isBlank()) {
            log.error("Токен агента не задан");
            scheduleReconnect();
            return;
        }
        http.newWebSocketBuilder()
                .header("Authorization", "Bearer " + token)
                .header("X-Agent-Id", props.getAgentId())
                .connectTimeout(Duration.ofSeconds(10))
                .buildAsync(URI.create(props.getServerUrl()), new Listener())
                .whenComplete((ws, ex) -> {
                    if (ex != null) {
                        Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
                        log.warn("Не удалось подключиться к серверу: {}", cause.toString());
                        scheduleReconnect();
                    }
                });
    }

    private void scheduleReconnect() {
        Duration d = backoff.next();
        log.info("Повторное подключение через {} мс", d.toMillis());
        exec.schedule(this::connect, d.toMillis(), TimeUnit.MILLISECONDS);
    }

    private void heartbeat() {
        if (socket == null) {
            return;
        }
        try {
            send(metrics.snapshot(versions.k6Version(), versions.agentVersion(), runState.activeRunIds()));
        } catch (RuntimeException e) {
            log.warn("Не удалось собрать heartbeat: {}", e.toString());
        }
    }

    private synchronized void send(Message message) {
        WebSocket ws = socket;
        if (ws == null) {
            return;
        }
        String json = ProtocolCodec.encode(message);
        sendChain = sendChain.handle((r, t) -> null).thenCompose(x -> ws.sendText(json, true))
                .exceptionally(t -> {
                    log.debug("Ошибка отправки: {}", t.toString());
                    return null;
                });
    }

    private void onMessage(String text, long receivedAt) {
        Message msg;
        try {
            msg = ProtocolCodec.decode(text);
        } catch (IllegalArgumentException e) {
            log.warn("Получено некорректное сообщение: {}", e.getMessage());
            return;
        }
        switch (msg) {
            case Messages.Registered r -> {
                tokens.savePermanent(r.permanentToken());
                log.info("Агент зарегистрирован на сервере, переподключаемся с постоянным токеном");
                // Переподключение доказывает серверу, что постоянный токен сохранён и работает; только после этого
                // сервер гасит одноразовый токен.
                WebSocket ws = socket;
                if (ws != null) {
                    ws.sendClose(WebSocket.NORMAL_CLOSURE, "re-authenticate");
                }
            }
            case Messages.Ack a -> journal.ack(a.upToSeq());
            case Messages.Ping p -> send(new Messages.Pong(p.pingId(), p.t1(), receivedAt, System.currentTimeMillis()));
            case Message.Command c -> handleCommand(c);
            default -> log.debug("Игнорируем сообщение {}", msg.getClass().getSimpleName());
        }
    }

    private void handleCommand(Message.Command c) {
        synchronized (recentCommands) {
            if (!recentCommandSet.add(c.cmdId())) {
                // повторная доставка: команду не выполняем, подтверждаем успешно
                send(new Messages.CmdAck(c.cmdId(), true, null));
                return;
            }
            recentCommands.addLast(c.cmdId());
            while (recentCommands.size() > 1000) {
                recentCommandSet.remove(recentCommands.removeFirst());
            }
        }
        Messages.CmdAck ack;
        try {
            ack = commands.handle(c);
        } catch (RuntimeException e) {
            log.error("Ошибка обработки команды {}", c.getClass().getSimpleName(), e);
            ack = new Messages.CmdAck(c.cmdId(), false, e.toString());
        }
        send(ack);
    }

    private void sendHelloAndReplay() {
        send(new Messages.Hello(Message.PROTOCOL_VERSION, versions.agentVersion(), versions.k6Version(),
                System.currentTimeMillis(), journal.ackedSeq(), journal.lastSeq(), runState.runs()));
        for (Message.Sequenced e : journal.unacked()) {
            send(e);
        }
    }

    private final class Listener implements WebSocket.Listener {
        private final StringBuilder buf = new StringBuilder();

        @Override
        public void onOpen(WebSocket webSocket) {
            socket = webSocket;
            backoff.reset();
            log.info("Соединение с сервером установлено");
            webSocket.request(1);
            exec.execute(AgentConnection.this::sendHelloAndReplay);
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            long receivedAt = System.currentTimeMillis();
            buf.append(data);
            if (last) {
                String text = buf.toString();
                buf.setLength(0);
                exec.execute(() -> onMessage(text, receivedAt));
            }
            webSocket.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            log.warn("Сервер закрыл соединение: {} {}", statusCode, reason);
            lost(webSocket);
            return null;
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            log.warn("Ошибка соединения: {}", error.toString());
            lost(webSocket);
        }

        private void lost(WebSocket ws) {
            if (socket == ws) {
                socket = null;
                scheduleReconnect();
            }
        }
    }
}
