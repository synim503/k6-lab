package ru.k6lab.server.deploy;

import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import ru.k6lab.server.common.SecretMasker;

import java.io.IOException;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** Журнал одной операции: маскирует секреты, копит текст и стримит подписчикам SSE. */
public class DeployLog {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss");
    private static final int MAX_CHARS = 2_000_000;

    private final long deploymentId;
    private final SecretMasker masker;
    private final StringBuilder text;
    private final List<SseEmitter> subscribers = new CopyOnWriteArrayList<>();
    private volatile boolean finished;

    public DeployLog(long deploymentId, SecretMasker masker, String existing) {
        this.deploymentId = deploymentId;
        this.masker = masker;
        this.text = new StringBuilder(existing == null ? "" : existing);
    }

    public long deploymentId() {
        return deploymentId;
    }

    public SecretMasker masker() {
        return masker;
    }

    public void line(String message) {
        for (String l : masker.mask(message).stripTrailing().split("\\R", -1)) {
            append("[" + LocalTime.now().format(TIME) + "] " + l);
        }
    }

    public void command(String command) {
        line("$ " + command);
    }

    /** Вывод удалённой команды без временной метки. */
    public void output(String out) {
        if (out == null || out.isBlank()) {
            return;
        }
        for (String l : masker.mask(out).stripTrailing().split("\\R")) {
            append("    " + l);
        }
    }

    private void append(String line) {
        synchronized (text) {
            if (text.length() < MAX_CHARS) {
                text.append(line).append('\n');
            }
        }
        push("log", line);
    }

    public String snapshot() {
        synchronized (text) {
            return text.toString();
        }
    }

    /** Подписка: сначала весь накопленный текст, дальше - живые строки. */
    public SseEmitter subscribe() {
        SseEmitter emitter = new SseEmitter(60L * 60 * 1000);
        emitter.onCompletion(() -> subscribers.remove(emitter));
        emitter.onTimeout(() -> subscribers.remove(emitter));
        emitter.onError(e -> subscribers.remove(emitter));
        try {
            String snap = snapshot();
            if (!snap.isEmpty()) {
                emitter.send(SseEmitter.event().name("log").data(snap.stripTrailing()));
            }
            if (finished) {
                emitter.send(SseEmitter.event().name("done").data("done"));
                emitter.complete();
                return emitter;
            }
        } catch (IOException e) {
            emitter.completeWithError(e);
            return emitter;
        }
        subscribers.add(emitter);
        return emitter;
    }

    /** Сигнал «изменился статус/шаги» - страница обновляет блок статуса. */
    public void stepChanged() {
        push("step", "changed");
    }

    public void finish() {
        finished = true;
        push("done", "done");
        for (SseEmitter e : subscribers) {
            e.complete();
        }
        subscribers.clear();
    }

    public boolean isFinished() {
        return finished;
    }

    private void push(String event, String data) {
        for (SseEmitter e : subscribers) {
            try {
                e.send(SseEmitter.event().name(event).data(data));
            } catch (IOException | IllegalStateException ex) {
                subscribers.remove(e);
            }
        }
    }
}
