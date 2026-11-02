package ru.k6lab.agent.run;

import ru.k6lab.core.protocol.Messages;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Батчи stdout/stderr k6: отправка раз в 500 мс, лимит строк в секунду (по умолчанию 200) - остальное
 * отбрасывается, а число отброшенных строк уходит вместе с батчем. Секреты маскируются до отправки.
 */
public class LogBatcher implements AutoCloseable {

    private final String runId;
    private final int linesPerSecond;
    private final Masker masker;
    private final AgentEvents events;
    private final ScheduledFuture<?> task;

    private final Object lock = new Object();
    private List<Messages.LogLine> buffer = new ArrayList<>();
    private long dropped;
    private long totalDropped;
    private long windowStart;
    private int usedInWindow;

    public LogBatcher(String runId, int linesPerSecond, Masker masker, AgentEvents events, ScheduledExecutorService scheduler) {
        this.runId = runId;
        this.linesPerSecond = Math.max(1, linesPerSecond);
        this.masker = masker;
        this.events = events;
        this.task = scheduler.scheduleWithFixedDelay(this::flush, 500, 500, TimeUnit.MILLISECONDS);
    }

    public void add(String stream, String line) {
        long now = System.currentTimeMillis();
        synchronized (lock) {
            if (now - windowStart >= 1000) {
                windowStart = now;
                usedInWindow = 0;
            }
            if (usedInWindow >= linesPerSecond) {
                dropped++;
                totalDropped++;
                return;
            }
            usedInWindow++;
            String masked = masker.mask(line);
            buffer.add(new Messages.LogLine(now, stream, masked.length() > 8000 ? masked.substring(0, 8000) + "…" : masked));
        }
    }

    public long totalDropped() {
        synchronized (lock) {
            return totalDropped;
        }
    }

    public void flush() {
        List<Messages.LogLine> batch;
        long d;
        synchronized (lock) {
            if (buffer.isEmpty() && dropped == 0) {
                return;
            }
            batch = buffer;
            buffer = new ArrayList<>();
            d = dropped;
            dropped = 0;
        }
        events.emit(seq -> new Messages.LogBatch(seq, System.currentTimeMillis(), runId, batch, d));
    }

    @Override
    public void close() {
        task.cancel(false);
        flush();
    }
}
