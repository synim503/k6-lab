package ru.k6lab.server.generator;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.k6lab.core.protocol.Messages;
import ru.k6lab.server.agent.AgentSession;

import java.time.Instant;
import java.util.List;

/** Короткие транзакции изменения состояния генератора из потока событий агента. */
@Service
public class GeneratorStateService {

    private final GeneratorRepository generators;
    private final java.util.concurrent.ConcurrentHashMap<Long, Object> statusLocks = new java.util.concurrent.ConcurrentHashMap<>();

    public GeneratorStateService(GeneratorRepository generators) {
        this.generators = generators;
    }

    private Object lockFor(Long generatorId) {
        return statusLocks.computeIfAbsent(generatorId, k -> new Object());
    }

    /** Возвращает seq, с которого сервер продолжает принимать события агента. */
    @Transactional
    public long onHello(Long generatorId, Messages.Hello hello, AgentSession.TokenKind kind) {
        synchronized (lockFor(generatorId)) {
            Generator g = generators.findById(generatorId).orElseThrow();
            // если журнал агента был обнулён (переустановка), seq начинается заново - иначе все события сочтутся дублями
            long lastSeq = Math.min(g.getLastSeq(), hello.lastSeq());
            g.setLastSeq(lastSeq);
            if (g.getStatus() != Generator.Status.REMOVING) {
                g.setStatus(Generator.Status.ONLINE);
            }
            g.setAgentVersion(hello.agentVersion());
            g.setK6Version(hello.k6Version());
            g.setLastHelloAt(Instant.now());
            g.setLastHeartbeatAt(Instant.now());
            if (kind == AgentSession.TokenKind.PERMANENT) {
                g.setOneTimeTokenHash(null);
                g.setOneTimeExpires(null);
            }
            return lastSeq;
        }
    }

    @Transactional
    public void onHeartbeat(Long generatorId, Messages.Heartbeat hb) {
        generators.touchHeartbeat(generatorId, Instant.now(), hb.k6Version(), hb.agentVersion());
    }

    @Transactional
    public void saveLastSeq(Long generatorId, long seq) {
        generators.updateLastSeq(generatorId, seq);
    }

    @Transactional
    public void saveClockOffset(Long generatorId, long offsetMs) {
        generators.updateClockOffset(generatorId, offsetMs);
    }

    @Transactional
    public void markOffline(Long generatorId) {
        synchronized (lockFor(generatorId)) {
            generators.findById(generatorId).ifPresent(g -> {
                if (g.getStatus() == Generator.Status.ONLINE) {
                    g.setStatus(Generator.Status.OFFLINE);
                }
            });
        }
    }

    /** Переводит генератор в OFFLINE только если за время гонки не успела подключиться новая сессия. */
    @Transactional
    public boolean markOfflineIfDisconnected(Long generatorId, java.util.function.LongPredicate isConnected) {
        synchronized (lockFor(generatorId)) {
            if (isConnected.test(generatorId)) {
                return false;
            }
            generators.findById(generatorId).ifPresent(g -> {
                if (g.getStatus() == Generator.Status.ONLINE) {
                    g.setStatus(Generator.Status.OFFLINE);
                }
            });
            return true;
        }
    }

    /** ONLINE без heartbeat дольше порога и без живой сессии -> OFFLINE. */
    @Transactional
    public List<Generator> markStaleOffline(Instant threshold, java.util.function.LongPredicate hasSession) {
        List<Generator> stale = generators.findStaleOnline(threshold).stream()
                .filter(g -> !hasSession.test(g.getId())).toList();
        stale.forEach(g -> g.setStatus(Generator.Status.OFFLINE));
        return stale;
    }
}
