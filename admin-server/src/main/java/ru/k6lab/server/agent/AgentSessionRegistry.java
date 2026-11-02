package ru.k6lab.server.agent;

import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class AgentSessionRegistry {

    private final Map<Long, AgentSession> byGenerator = new ConcurrentHashMap<>();

    /** Регистрирует сессию; прежняя сессия того же генератора закрывается. */
    public void register(AgentSession session) {
        AgentSession old = byGenerator.put(session.generatorId(), session);
        if (old != null && old != session) {
            old.close("replaced by newer connection");
        }
    }

    /** Удаляет сессию, только если она всё ещё актуальна. Возвращает true, если удалена. */
    public boolean unregister(AgentSession session) {
        return byGenerator.remove(session.generatorId(), session);
    }

    public Optional<AgentSession> get(long generatorId) {
        return Optional.ofNullable(byGenerator.get(generatorId));
    }

    public boolean isConnected(long generatorId) {
        AgentSession s = byGenerator.get(generatorId);
        return s != null && s.helloReceived();
    }

    public Collection<AgentSession> all() {
        return byGenerator.values();
    }
}
