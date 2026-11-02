package ru.k6lab.server.agent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

import java.util.Map;

/** Аутентификация агента токеном из заголовка handshake: Authorization: Bearer ... */
@Component
public class AgentHandshakeInterceptor implements HandshakeInterceptor {

    static final String ATTR_GENERATOR_ID = "generatorId";
    static final String ATTR_GENERATOR_NAME = "generatorName";
    static final String ATTR_TOKEN_KIND = "tokenKind";

    private static final Logger log = LoggerFactory.getLogger(AgentHandshakeInterceptor.class);

    private final TokenService tokens;

    public AgentHandshakeInterceptor(TokenService tokens) {
        this.tokens = tokens;
    }

    @Override
    public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response, WebSocketHandler wsHandler,
                                   Map<String, Object> attributes) {
        String header = request.getHeaders().getFirst("Authorization");
        String token = header != null && header.regionMatches(true, 0, "Bearer ", 0, 7) ? header.substring(7).trim() : null;
        var resolved = tokens.resolve(token);
        if (resolved.isEmpty()) {
            log.warn("Отклонено подключение агента с {}: неверный токен", request.getRemoteAddress());
            response.setStatusCode(HttpStatus.UNAUTHORIZED);
            return false;
        }
        attributes.put(ATTR_GENERATOR_ID, resolved.get().generator().getId());
        attributes.put(ATTR_GENERATOR_NAME, resolved.get().generator().getName());
        attributes.put(ATTR_TOKEN_KIND, resolved.get().kind());
        return true;
    }

    @Override
    public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response, WebSocketHandler wsHandler,
                               Exception exception) {
        // ничего
    }
}
