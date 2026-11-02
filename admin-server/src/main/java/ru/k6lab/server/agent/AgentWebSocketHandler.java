package ru.k6lab.server.agent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import ru.k6lab.core.protocol.Message;
import ru.k6lab.core.protocol.Messages;
import ru.k6lab.core.protocol.ProtocolCodec;
import ru.k6lab.server.generator.GeneratorStateService;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class AgentWebSocketHandler extends TextWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(AgentWebSocketHandler.class);

    private final AgentSessionRegistry registry;
    private final AgentMessageService messages;
    private final TokenService tokens;
    private final GeneratorStateService state;
    private final org.springframework.context.ApplicationEventPublisher publisher;
    private final Map<String, AgentSession> byWsId = new ConcurrentHashMap<>();

    public AgentWebSocketHandler(AgentSessionRegistry registry, AgentMessageService messages, TokenService tokens,
                                 GeneratorStateService state, org.springframework.context.ApplicationEventPublisher publisher) {
        this.registry = registry;
        this.messages = messages;
        this.tokens = tokens;
        this.state = state;
        this.publisher = publisher;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession raw) {
        long id = (Long) raw.getAttributes().get(AgentHandshakeInterceptor.ATTR_GENERATOR_ID);
        String name = (String) raw.getAttributes().get(AgentHandshakeInterceptor.ATTR_GENERATOR_NAME);
        AgentSession.TokenKind kind = (AgentSession.TokenKind) raw.getAttributes().get(AgentHandshakeInterceptor.ATTR_TOKEN_KIND);
        WebSocketSession safe = new ConcurrentWebSocketSessionDecorator(raw, 10_000, 4 * 1024 * 1024);
        AgentSession session = new AgentSession(id, name, kind, safe);
        byWsId.put(raw.getId(), session);
        registry.register(session);
        log.info("WebSocket агента {} открыт ({})", name, kind);
        if (kind == AgentSession.TokenKind.ONE_TIME) {
            String permanent = tokens.issuePermanent(id);
            session.send(new Messages.Registered(permanent, name));
        }
    }

    @Override
    protected void handleTextMessage(WebSocketSession raw, TextMessage text) {
        AgentSession session = byWsId.get(raw.getId());
        if (session == null) {
            return;
        }
        Message message;
        try {
            message = ProtocolCodec.decode(text.getPayload());
        } catch (IllegalArgumentException e) {
            log.warn("Некорректное сообщение от {}: {}", session.generatorName(), e.getMessage());
            return;
        }
        try {
            messages.handle(session, message);
        } catch (RuntimeException e) {
            log.error("Ошибка обработки {} от {}", message.getClass().getSimpleName(), session.generatorName(), e);
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession raw, CloseStatus status) {
        AgentSession session = byWsId.remove(raw.getId());
        if (session == null) {
            return;
        }
        // если соединение уже заменено новым, статус не трогаем
        if (registry.unregister(session)) {
            state.markOffline(session.generatorId());
            publisher.publishEvent(new AgentLifecycleEvents.Disconnected(session.generatorId(), session.generatorName()));
            log.info("Агент {} отключился ({})", session.generatorName(), status);
        }
    }

    @Override
    public void handleTransportError(WebSocketSession raw, Throwable exception) {
        log.warn("Ошибка транспорта WebSocket {}: {}", raw.getId(), exception.toString());
    }
}
