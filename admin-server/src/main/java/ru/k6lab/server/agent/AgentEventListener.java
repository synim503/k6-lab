package ru.k6lab.server.agent;

import ru.k6lab.core.protocol.Message;

/**
 * Подписчик на события агентов, прошедшие дедупликацию по seq (PREPARED, LOG_BATCH, EXIT, ...) и ответы на команды.
 * Реализации появляются с этапа 4 (запуск тестов).
 */
public interface AgentEventListener {

    void onEvent(long generatorId, Message message);
}
