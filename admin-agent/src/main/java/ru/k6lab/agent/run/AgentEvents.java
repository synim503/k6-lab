package ru.k6lab.agent.run;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import ru.k6lab.agent.journal.EventJournal;
import ru.k6lab.agent.ws.AgentConnection;
import ru.k6lab.core.protocol.Message;

import java.util.function.LongFunction;

/**
 * Единая точка отправки событий агента: событие сначала пишется в дисковый журнал (переживает обрыв связи
 * и рестарт агента), затем отправляется, если связь есть.
 */
@Component
public class AgentEvents {

    private final EventJournal journal;
    private final ObjectProvider<AgentConnection> connection;

    public AgentEvents(EventJournal journal, ObjectProvider<AgentConnection> connection) {
        this.journal = journal;
        this.connection = connection;
    }

    public <T extends Message.Sequenced> T emit(LongFunction<T> factory) {
        T event = journal.append(factory);
        AgentConnection c = connection.getIfAvailable();
        if (c != null) {
            c.publish(event);
        }
        return event;
    }
}
