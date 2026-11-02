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

    public AgentMaintenanceJobs(AgentSessionRegistry registry, GeneratorStateService state) {
        this.registry = registry;
        this.state = state;
    }

    /** Подтверждаем агентам принятые события (раз в секунду) и сохраняем last_seq. */
    @Scheduled(fixedDelay = 1000)
    public void sendAcks() {
        for (AgentSession s : registry.all()) {
            if (!s.helloReceived()) {
                continue;
            }
            long seq = s.lastSeq();
            if (seq > s.lastAckedToAgent()) {
                try {
                    s.send(new Messages.Ack(seq, System.currentTimeMillis()));
                    s.lastAckedToAgent(seq);
                    state.saveLastSeq(s.generatorId(), seq);
                } catch (RuntimeException e) {
                    log.debug("ACK не отправлен: {}", e.getMessage());
                }
            }
        }
    }

    /** Каждые 30 с замеряем смещение часов (PING/PONG). */
    @Scheduled(fixedDelay = 30_000, initialDelay = 10_000)
    public void pingAgents() {
        for (AgentSession s : registry.all()) {
            if (s.helloReceived()) {
                try {
                    s.send(new Messages.Ping("p-" + System.nanoTime(), System.currentTimeMillis()));
                } catch (RuntimeException e) {
                    log.debug("PING не отправлен: {}", e.getMessage());
                }
            }
        }
    }

    @Scheduled(fixedDelay = 5000, initialDelay = 15_000)
    public void markStale() {
        state.markStaleOffline(Instant.now().minus(HEARTBEAT_TIMEOUT), registry::isConnected)
                .forEach(g -> log.warn("Генератор {} не присылает heartbeat - OFFLINE", g.getName()));
    }
}
