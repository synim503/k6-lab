package ru.k6lab.server.agent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import ru.k6lab.core.protocol.Messages;
import ru.k6lab.server.generator.GeneratorStateService;

import java.time.Duration;
import java.time.Instant;

/** Периодика соединений с агентами: ACK событий, замер часов, пометка пропавших генераторов. */
@Component
public class AgentMaintenanceJobs {

    private static final Logger log = LoggerFactory.getLogger(AgentMaintenanceJobs.class);
    private static final Duration HEARTBEAT_TIMEOUT = Duration.ofSeconds(20);

    private final AgentSessionRegistry registry;
    private final GeneratorStateService state;
    private final org.springframework.context.ApplicationEventPublisher publisher;

    public AgentMaintenanceJobs(AgentSessionRegistry registry, GeneratorStateService state,
                                org.springframework.context.ApplicationEventPublisher publisher) {
        this.registry = registry;
        this.state = state;
        this.publisher = publisher;
    }

    /** Подтверждаем агентам принятые события (раз в секунду): сначала фиксируем last_seq в БД, затем шлём ACK. */
    @Scheduled(fixedDelay = 1000)
    public void sendAcks() {
        for (AgentSession s : registry.all()) {
            if (!s.helloReceived()) {
                continue;
            }
            long seq = s.lastSeq();
            if (seq > s.lastAckedToAgent()) {
                try {
                    state.saveLastSeq(s.generatorId(), seq);
                    s.send(new Messages.Ack(seq, System.currentTimeMillis()));
                    s.lastAckedToAgent(seq);
                } catch (RuntimeException e) {
                    log.debug("ACK не отправлен: {}", e.getMessage());
                }
            }
        }
    }

    /** Каждые 30 с замеряем смещение часов (PING/PONG). При ошибке записи закрываем мёртвую сессию. */
    @Scheduled(fixedDelay = 30_000, initialDelay = 10_000)
    public void pingAgents() {
        for (AgentSession s : registry.all()) {
            if (s.helloReceived()) {
                try {
                    s.send(new Messages.Ping("p-" + System.nanoTime(), System.currentTimeMillis()));
                } catch (RuntimeException e) {
                    log.warn("PING агенту {} не отправлен ({}): закрываем сессию", s.generatorName(), e.getMessage());
                    dropDeadSession(s, "ping failed");
                }
            }
        }
    }

    @Scheduled(fixedDelay = 5000, initialDelay = 15_000)
    public void markStale() {
        Instant cutoff = Instant.now().minus(HEARTBEAT_TIMEOUT);
        for (AgentSession s : registry.all()) {
            Instant last = s.lastHeartbeatAt() != null ? s.lastHeartbeatAt() : s.connectedAt();
            if (last.isBefore(cutoff)) {
                log.warn("Агент {} не присылает heartbeat > {} с — закрываем залипшую сессию",
                        s.generatorName(), HEARTBEAT_TIMEOUT.toSeconds());
                dropDeadSession(s, "heartbeat timeout");
            }
        }
        state.markStaleOffline(cutoff, registry::isConnected)
                .forEach(g -> log.warn("Генератор {} не присылает heartbeat - OFFLINE", g.getName()));
    }

    private void dropDeadSession(AgentSession s, String reason) {
        s.close(reason);
        if (registry.unregister(s)) {
            state.markOffline(s.generatorId());
            publisher.publishEvent(new AgentLifecycleEvents.Disconnected(s.generatorId(), s.generatorName()));
        }
    }
}
