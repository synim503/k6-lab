package ru.k6lab.server.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import org.springframework.web.socket.server.standard.ServletServerContainerFactoryBean;
import ru.k6lab.server.agent.AgentHandshakeInterceptor;
import ru.k6lab.server.agent.AgentWebSocketHandler;

@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

    private final AgentWebSocketHandler handler;
    private final AgentHandshakeInterceptor interceptor;

    public WebSocketConfig(AgentWebSocketHandler handler, AgentHandshakeInterceptor interceptor) {
        this.handler = handler;
        this.interceptor = interceptor;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        // агенты - не браузеры, заголовка Origin нет
        registry.addHandler(handler, "/agent/ws").addInterceptors(interceptor).setAllowedOriginPatterns("*");
    }

    @Bean
    ServletServerContainerFactoryBean webSocketContainer() {
        ServletServerContainerFactoryBean c = new ServletServerContainerFactoryBean();
        c.setMaxTextMessageBufferSize(4 * 1024 * 1024);
        c.setMaxSessionIdleTimeout(0L);
        return c;
    }
}
