package ru.k6lab.server.agent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import ru.k6lab.core.protocol.Message;
import ru.k6lab.core.protocol.Messages;
import ru.k6lab.server.generator.GeneratorMetricsService;
import ru.k6lab.server.generator.GeneratorStateService;

import java.util.List;

/**
 * Разбор сообщений агента. События с seq обрабатываются идемпотентно: seq не больше последнего принятого
 * считается дублем (агент мог досылать журнал после реконнекта) и пропускается, но подтверждается ACK.
 */
@Service
public class AgentMessageService {

    private static final Logger log = LoggerFactory.getLogger(AgentMessageService.class);

    private final GeneratorStateService state;
    private final GeneratorMetricsService metrics;
    private final List<AgentEventListener> listeners;
    private final org.springframework.context.ApplicationEventPublisher publisher;

    public AgentMessageService(GeneratorStateService state, GeneratorMetricsService metrics,
                               List<AgentEventListener> listeners, org.springframework.context.ApplicationEventPublisher publisher) {
        this.state = state;
        this.metrics = metrics;
        this.listeners = listeners;
        this.publisher = publisher;
    }

    public void handle(AgentSession session, Message message) {
        switch (message) {
            case Messages.Hello h -> onHello(session, h);
            case Messages.Heartbeat hb -> onHeartbeat(session, hb);
            case Messages.Pong p -> onPong(session, p);
            case Message.Sequenced s -> onSequenced(session, s);
            case Messages.CmdAck a -> publish(session, a);
            default -> log.warn("Неожиданное сообщение от агента {}: {}", session.generatorName(),
                    message.getClass().getSimpleName());
        }
    }

    private void onHello(AgentSession session, Messages.Hello hello) {
        long lastSeq = state.onHello(session.generatorId(), hello, session.tokenKind());
        session.lastSeq(lastSeq);
        session.lastAckedToAgent(lastSeq);
        session.helloReceived(true);
        session.send(new Messages.Ack(lastSeq, System.currentTimeMillis()));
        session.send(new Messages.Ping("hello-" + System.nanoTime(), System.currentTimeMillis()));
        log.info("Агент {} подключён (agent {}, k6 {}), продолжаем с seq {}", session.generatorName(),
                hello.agentVersion(), hello.k6Version(), lastSeq);
        publisher.publishEvent(new AgentLifecycleEvents.Connected(session.generatorId(), session.generatorName(), hello));
    }

    private void onHeartbeat(AgentSession session, Messages.Heartbeat hb) {
        if (!session.helloReceived()) {
            return;
        }
        session.heartbeat(hb);
        state.onHeartbeat(session.generatorId(), hb);
        metrics.record(session.generatorId(), hb, hb.activeRuns() == null ? 0 : hb.activeRuns().size());
    }

    private void onPong(AgentSession session, Messages.Pong pong) {
        long t4 = System.currentTimeMillis();
        // NTP-формула: offset = ((t2 - t1) + (t3 - t4)) / 2 - смещение часов агента относительно сервера
        long offset = ((pong.t2() - pong.t1()) + (pong.t3() - t4)) / 2;
        long median = session.addOffsetSample(offset);
        state.saveClockOffset(session.generatorId(), median);
    }

    private void onSequenced(AgentSession session, Message.Sequenced event) {
        if (!session.helloReceived()) {
            return;
        }
        synchronized (session) {
            if (event.seq() <= session.lastSeq()) {
                log.debug("Дубль события {} seq={} от {}", event.getClass().getSimpleName(), event.seq(),
                        session.generatorName());
                return;
            }
            if (event.seq() > session.lastSeq() + 1) {
                log.warn("Пропуск seq у {}: ожидали {}, пришло {} (события вытеснены из журнала агента)",
                        session.generatorName(), session.lastSeq() + 1, event.seq());
            }
            publish(session, event);
            session.lastSeq(event.seq());
        }
    }

    private void publish(AgentSession session, Message message) {
        for (AgentEventListener l : listeners) {
            try {
                l.onEvent(session.generatorId(), message);
            } catch (RuntimeException e) {
                log.error("Ошибка обработчика события {}", message.getClass().getSimpleName(), e);
            }
        }
    }
}
