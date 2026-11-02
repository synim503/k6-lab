package ru.k6lab.server.agent;

import ru.k6lab.core.protocol.Messages;

/** События жизненного цикла соединения агента (публикуются через ApplicationEventPublisher). */
public final class AgentLifecycleEvents {

    private AgentLifecycleEvents() {
    }

    /** Агент прислал HELLO (подключился/переподключился) и сообщил, какие run у него на руках. */
    public record Connected(long generatorId, String generatorName, Messages.Hello hello) {
    }

    /** Соединение с агентом потеряно. */
    public record Disconnected(long generatorId, String generatorName) {
    }
}
