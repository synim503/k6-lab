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

    /** Замер смещения часов: смещение (агент минус сервер, мс), круговая задержка и время замера. */
    private record OffsetSample(long offsetMs, long rttMs, long atMs) {
    }

    private final Deque<OffsetSample> samples = new ConcurrentLinkedDeque<>();
    private volatile long lastPingAtMs;

    /**
     * Добавляет замер смещения часов и возвращает оценку: по замеру с наименьшей круговой задержкой из последних 8
     * (как в NTP: чем короче путь, тем меньше ошибка из-за несимметричной задержки). Одиночный медленный ответ не искажает оценку.
     */
    public long addOffsetSample(long offsetMs, long rttMs) {
        samples.addLast(new OffsetSample(offsetMs, Math.max(0, rttMs), System.currentTimeMillis()));
        while (samples.size() > 8) {
            samples.pollFirst();
        }
        return clockOffsetMs();
    }

    /** Совместимость: замер без измеренной задержки. */
    public long addOffsetSample(long offsetMs) {
        return addOffsetSample(offsetMs, 0);
    }

    /** Текущая оценка смещения часов агента (агент минус сервер, мс) либо null, если замеров ещё нет. */
    public Long clockOffsetMs() {
        return samples.stream().min(java.util.Comparator.comparingLong(OffsetSample::rttMs).thenComparing(
                java.util.Comparator.comparingLong(OffsetSample::atMs).reversed())).map(OffsetSample::offsetMs).orElse(null);
    }

    /** Сколько мс назад был последний замер; Long.MAX_VALUE, если замеров нет. */
    public long lastSampleAgeMs() {
        OffsetSample last = samples.peekLast();
        return last == null ? Long.MAX_VALUE : System.currentTimeMillis() - last.atMs();
    }

    /** Забывает прежние замеры (часы могли уйти или быть выровнены после них). */
    public void resetOffsetSamples() {
        samples.clear();
    }

    public long lastPingAtMs() {
        return lastPingAtMs;
    }

    public void markPing() {
        lastPingAtMs = System.currentTimeMillis();
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
