package ru.k6lab.server.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.session.SessionRegistry;
import org.springframework.security.core.session.SessionRegistryImpl;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    SessionRegistry sessionRegistry() {
        return new SessionRegistryImpl();
    }

    /** Публикует события уничтожения HTTP-сессий Tomcat, чтобы SessionRegistryImpl не тёк памятью. */
    @Bean
    org.springframework.security.web.session.HttpSessionEventPublisher httpSessionEventPublisher() {
        return new org.springframework.security.web.session.HttpSessionEventPublisher();
    }

    /** Порт агентов: аутентификация токеном выполняется в handshake WebSocket, CSRF и сессии не нужны. */
    @Bean
    @Order(1)
    SecurityFilterChain agentChain(HttpSecurity http) throws Exception {
        http.securityMatcher("/agent/**")
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(a -> a.anyRequest().permitAll());
        return http.build();
    }

    @Bean
    @Order(2)
    SecurityFilterChain webChain(HttpSecurity http, SessionRegistry sessionRegistry) throws Exception {
        http.authorizeHttpRequests(a -> a
                        .requestMatchers("/login", "/css/**", "/js/**", "/webjars/**", "/favicon.ico",
                                "/app/**", "/api/v1/auth/**",
                                "/actuator/health", "/actuator/health/**").permitAll()
                        .requestMatchers("/distributions/**", "/ssh-credentials/**", "/users/**", "/settings/**",
                                "/audit/**", "/schedule/guards/**",
                                "/api/v1/distributions/**", "/api/v1/ssh-credentials/**", "/api/v1/users/**",
                                "/api/v1/settings/**", "/api/v1/audit/**", "/api/v1/schedule/guards/**",
                                "/api/v1/tuning/**", "/api/v1/sandbox/**").hasRole("ADMIN")
                        .anyRequest().authenticated())
                .formLogin(f -> f.loginPage("/login").defaultSuccessUrl("/", true).permitAll())
                .logout(l -> l.logoutSuccessUrl("/login?logout"))
                .exceptionHandling(eh -> eh
                        .authenticationEntryPoint((req, res, ex) -> {
                            if (req.getRequestURI() != null && req.getRequestURI().startsWith("/api/")) {
                                res.setStatus(401);
                                res.setContentType("application/json;charset=UTF-8");
                                res.getWriter().write("{\"ok\":false,\"error\":\"Требуется вход в систему\"}");
                            } else {
                                new org.springframework.security.web.authentication.LoginUrlAuthenticationEntryPoint("/login")
                                        .commence(req, res, ex);
                            }
                        })
                        .accessDeniedHandler((req, res, ex) -> {
                            if (req.getRequestURI() != null && req.getRequestURI().startsWith("/api/")) {
                                res.setStatus(403);
                                res.setContentType("application/json;charset=UTF-8");
                                res.getWriter().write("{\"ok\":false,\"error\":\"Недостаточно прав\"}");
                            } else {
                                new org.springframework.security.web.access.AccessDeniedHandlerImpl()
                                        .handle(req, res, ex);
                            }
                        }))
                .sessionManagement(s -> s.maximumSessions(-1).sessionRegistry(sessionRegistry))
                // CSRF включён; htmx и React SPA передают токен заголовком X-CSRF-TOKEN
                .headers(h -> h.frameOptions(fo -> fo.sameOrigin()));
        return http.build();
    }
}
