package ru.k6lab.server.agent;

import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import ru.k6lab.core.protocol.Message;
import ru.k6lab.core.protocol.Messages;
import ru.k6lab.core.protocol.ProtocolCodec;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedDeque;

/** Живое WebSocket-соединение с агентом конкретного генератора. */
public class AgentSession {

    public enum TokenKind { ONE_TIME, PERMANENT }

    private final long generatorId;
    private final String generatorName;
    private final TokenKind tokenKind;
    private final WebSocketSession ws;
    private final Instant connectedAt = Instant.now();
    private final Deque<Long> offsetSamples = new ConcurrentLinkedDeque<>();

    private volatile long lastSeq;
    private volatile long lastAckedToAgent;
    private volatile boolean helloReceived;
    private volatile Messages.Heartbeat lastHeartbeat;
    private volatile Instant lastHeartbeatAt;

    public AgentSession(long generatorId, String generatorName, TokenKind tokenKind, WebSocketSession ws) {
        this.generatorId = generatorId;
        this.generatorName = generatorName;
        this.tokenKind = tokenKind;
        this.ws = ws;
    }

    public void send(Message message) {
        try {
            ws.sendMessage(new TextMessage(ProtocolCodec.encode(message)));
        } catch (IOException | RuntimeException e) {
            throw new IllegalStateException("Не удалось отправить сообщение агенту " + generatorName + ": " + e, e);
        }
    }

    public void close(String reason) {
        try {
            ws.close(new org.springframework.web.socket.CloseStatus(4000, reason));
        } catch (IOException | RuntimeException ignored) {
            // уже закрыто
        }
    }

    /** Добавляет замер смещения часов (агент минус сервер, мс) и возвращает медиану последних 5. */
    public long addOffsetSample(long offsetMs) {
        offsetSamples.addLast(offsetMs);
        while (offsetSamples.size() > 5) {
            offsetSamples.pollFirst();
        }
        List<Long> sorted = new ArrayList<>(offsetSamples);
        sorted.sort(Long::compare);
        return sorted.get(sorted.size() / 2);
    }

    /** Текущая медианная оценка смещения часов агента (агент минус сервер, мс) либо null, если замеров ещё нет. */
    public Long clockOffsetMs() {
        if (offsetSamples.isEmpty()) {
            return null;
        }
        List<Long> sorted = new ArrayList<>(offsetSamples);
        sorted.sort(Long::compare);
        return sorted.get(sorted.size() / 2);
    }

    public long generatorId() {
        return generatorId;
    }

    public String generatorName() {
        return generatorName;
    }

    public TokenKind tokenKind() {
        return tokenKind;
    }

    public WebSocketSession socket() {
        return ws;
    }

    public Instant connectedAt() {
        return connectedAt;
    }

    public long lastSeq() {
        return lastSeq;
    }

    public void lastSeq(long v) {
        this.lastSeq = v;
    }

    public long lastAckedToAgent() {
        return lastAckedToAgent;
    }

    public void lastAckedToAgent(long v) {
        this.lastAckedToAgent = v;
    }

    public boolean helloReceived() {
        return helloReceived;
    }

    public void helloReceived(boolean v) {
        this.helloReceived = v;
    }

    public Messages.Heartbeat lastHeartbeat() {
        return lastHeartbeat;
    }

    public Instant lastHeartbeatAt() {
        return lastHeartbeatAt;
    }

    public void heartbeat(Messages.Heartbeat hb) {
        this.lastHeartbeat = hb;
        this.lastHeartbeatAt = Instant.now();
    }

    @Override
    public String toString() {
        return "AgentSession[" + generatorName + ", " + Arrays.asList(tokenKind) + "]";
    }
}
