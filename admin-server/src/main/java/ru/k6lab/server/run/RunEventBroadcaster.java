package ru.k6lab.server.run;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Live-данные run для SSE-подписчиков из памяти: ни одно событие не читается из БД. Для логов держится кольцевой буфер
 * (2000 строк) - новые подписчики получают недавнее прошлое сразу.
 */
@Component
public class RunEventBroadcaster {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int BUFFER = 2000;

    public record Line(long ts, Long generatorId, String generator, String level, String text) {
    }

    private static final class Channel {
        final Deque<Line> logs = new ArrayDeque<>();
        final List<SseEmitter> logSubs = new CopyOnWriteArrayList<>();
        final List<SseEmitter> statusSubs = new CopyOnWriteArrayList<>();
        final List<SseEmitter> metricSubs = new CopyOnWriteArrayList<>();
        volatile String lastMetrics;
    }

    private final Map<Long, Channel> channels = new ConcurrentHashMap<>();

    private Channel ch(long runId) {
        return channels.computeIfAbsent(runId, k -> new Channel());
    }

    // ------------------------------------------------------------------ публикация

    public void publishLog(long runId, List<Line> lines) {
        if (lines.isEmpty()) {
            return;
        }
        Channel c = ch(runId);
        synchronized (c.logs) {
            for (Line l : lines) {
                c.logs.addLast(l);
                if (c.logs.size() > BUFFER) {
                    c.logs.removeFirst();
                }
            }
        }
        send(c.logSubs, "log", toJson(lines));
    }

    /** Сигнал «статус run/генераторов изменился»: страница перечитывает фрагмент. */
    public void publishStatus(long runId, String status) {
        ObjectNode n = MAPPER.createObjectNode();
        n.put("status", status);
        send(ch(runId).statusSubs, "status", n.toString());
    }

    public void publishMetrics(long runId, String json) {
        Channel c = ch(runId);
        c.lastMetrics = json;
        send(c.metricSubs, "metrics", json);
    }

    // ------------------------------------------------------------------ подписка

    public SseEmitter subscribeLogs(long runId, List<Line> fallbackFromDb) {
        Channel c = ch(runId);
        SseEmitter e = new SseEmitter(0L);
        register(c.logSubs, e);
        List<Line> backlog;
        synchronized (c.logs) {
            backlog = new ArrayList<>(c.logs);
        }
        if (backlog.isEmpty()) {
            backlog = fallbackFromDb;
        }
        try {
            if (!backlog.isEmpty()) {
                e.send(SseEmitter.event().name("log").data(toJson(backlog)));
            }
        } catch (IOException | IllegalStateException ex) {
            c.logSubs.remove(e);
        }
        return e;
    }

    public SseEmitter subscribeStatus(long runId) {
        SseEmitter e = new SseEmitter(0L);
        register(ch(runId).statusSubs, e);
        return e;
    }

    public SseEmitter subscribeMetrics(long runId) {
        Channel c = ch(runId);
        SseEmitter e = new SseEmitter(0L);
        register(c.metricSubs, e);
        String last = c.lastMetrics;
        if (last != null) {
            try {
                e.send(SseEmitter.event().name("metrics").data(last));
            } catch (IOException | IllegalStateException ex) {
                c.metricSubs.remove(e);
            }
        }
        return e;
    }

    /** Освобождает память завершённого run (подписчики закрываются). */
    public void close(long runId) {
        Channel c = channels.remove(runId);
        if (c == null) {
            return;
        }
        for (List<SseEmitter> subs : List.of(c.logSubs, c.statusSubs, c.metricSubs)) {
            subs.forEach(SseEmitter::complete);
            subs.clear();
        }
    }

    /** Keep-alive: SSE-соединения не должны молчать, иначе прокси их рвут. */
    @Scheduled(fixedDelay = 20_000)
    public void keepAlive() {
        for (Channel c : channels.values()) {
            for (List<SseEmitter> subs : List.of(c.logSubs, c.statusSubs, c.metricSubs)) {
                for (SseEmitter e : subs) {
                    try {
                        e.send(SseEmitter.event().comment("ka"));
                    } catch (IOException | IllegalStateException ex) {
                        subs.remove(e);
                    }
                }
            }
        }
    }

    private void register(List<SseEmitter> subs, SseEmitter e) {
        subs.add(e);
        e.onCompletion(() -> subs.remove(e));
        e.onTimeout(() -> subs.remove(e));
        e.onError(t -> subs.remove(e));
    }

    private void send(List<SseEmitter> subs, String event, String data) {
        for (SseEmitter e : subs) {
            try {
                e.send(SseEmitter.event().name(event).data(data));
            } catch (IOException | IllegalStateException ex) {
                subs.remove(e);
            }
        }
    }

    static String toJson(List<Line> lines) {
        ArrayNode arr = MAPPER.createArrayNode();
        for (Line l : lines) {
            ObjectNode o = arr.addObject();
            o.put("t", l.ts());
            if (l.generator() != null) {
                o.put("g", l.generator());
            }
            o.put("l", l.level());
            o.put("m", l.text());
        }
        return arr.toString();
    }
}
