package ru.k6lab.server;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import ru.k6lab.core.protocol.Message;
import ru.k6lab.server.agent.AgentEventListener;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** Записывает события агентов, дошедшие до подписчиков (после дедупликации по seq). */
@TestConfiguration
public class RecordingConfig {

    public static final List<Message> EVENTS = new CopyOnWriteArrayList<>();

    @Bean
    AgentEventListener recordingListener() {
        return (generatorId, message) -> EVENTS.add(message);
    }
}
