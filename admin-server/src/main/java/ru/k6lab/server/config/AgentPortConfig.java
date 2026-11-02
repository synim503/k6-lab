package ru.k6lab.server.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.apache.catalina.connector.Connector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Отдельный (по умолчанию 47331) порт для агентов - дополнительный коннектор Tomcat. На нём доступны только
 * пути /agent/**, а UI и API основного порта не видны с генераторов; и наоборот.
 */
@Configuration
public class AgentPortConfig {

    private static final Logger log = LoggerFactory.getLogger(AgentPortConfig.class);

    @Bean
    WebServerFactoryCustomizer<TomcatServletWebServerFactory> agentConnectorCustomizer(AppProperties props) {
        return factory -> {
            Connector connector = new Connector(TomcatServletWebServerFactory.DEFAULT_PROTOCOL);
            connector.setPort(props.getAgentPort());
            factory.addAdditionalTomcatConnectors(connector);
            log.info("Порт для агентов: {}", props.getAgentPort());
        };
    }

    @Bean
    FilterRegistrationBean<PortSeparationFilter> portSeparationFilter(AppProperties props) {
        FilterRegistrationBean<PortSeparationFilter> reg = new FilterRegistrationBean<>(new PortSeparationFilter(props));
        reg.setOrder(Ordered.HIGHEST_PRECEDENCE);
        return reg;
    }

    static class PortSeparationFilter extends OncePerRequestFilter {

        private final AppProperties props;

        PortSeparationFilter(AppProperties props) {
            this.props = props;
        }

        @Override
        protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
                throws ServletException, IOException {
            boolean agentPort = request.getLocalPort() == props.getAgentPort();
            boolean agentPath = request.getRequestURI().startsWith("/agent/");
            if (agentPort != agentPath) {
                // не sendError: error-dispatch на /error ушёл бы в цепочку безопасности и дал редирект на /login
                response.setStatus(HttpServletResponse.SC_NOT_FOUND);
                return;
            }
            chain.doFilter(request, response);
        }
    }
}
