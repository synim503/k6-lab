package ru.k6lab.agent.journal;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.k6lab.core.protocol.Message;
import ru.k6lab.core.protocol.Messages;
import ru.k6lab.core.protocol.ProtocolCodec;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.function.LongFunction;

/**
 * Дисковый журнал событий агента. События пишутся append-only (JSON на строку), удаляются после ACK сервера.
 * Нужен, чтобы обрыв связи не терял PREPARED/EXIT/SUMMARY и пр.
 * <p>
 * Номер события (seq) монотонный и переживает рестарт агента. При переполнении лимита сначала вытесняются
 * LOG_BATCH, затем K6_STATUS; остальные события не вытесняются никогда.
 */
public class EventJournal {

    private static final Logger log = LoggerFactory.getLogger(EventJournal.class);

    private final Path file;
    private final Path meta;
    private final long maxBytes;
    private long lastSeq;
    private long ackedSeq;

    public EventJournal(Path dataDir, long maxBytes) {
        try {
            Files.createDirectories(dataDir);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        this.file = dataDir.resolve("journal.jsonl");
        this.meta = dataDir.resolve("journal.meta");
        this.maxBytes = maxBytes;
        loadMeta();
    }

    private void loadMeta() {
        try {
            if (Files.exists(meta)) {
                String[] parts = Files.readString(meta, StandardCharsets.UTF_8).trim().split(",");
                lastSeq = Long.parseLong(parts[0]);
                ackedSeq = parts.length > 1 ? Long.parseLong(parts[1]) : 0;
            }
            // seq не должен откатываться, даже если meta отстала от файла
            for (Message m : readAll()) {
                if (m instanceof Message.Sequenced s) {
                    lastSeq = Math.max(lastSeq, s.seq());
                }
            }
        } catch (IOException | RuntimeException e) {
            log.warn("Журнал событий повреждён, начинаем заново: {}", e.toString());
            lastSeq = Math.max(lastSeq, ackedSeq);
        }
    }

    private void saveMeta() {
        try {
            Files.writeString(meta, lastSeq + "," + ackedSeq, StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.warn("Не удалось записать meta журнала: {}", e.toString());
        }
    }

    /** Создаёт событие со следующим seq, пишет его на диск и возвращает его. */
    public synchronized <T extends Message.Sequenced> T append(LongFunction<T> factory) {
        T event = factory.apply(++lastSeq);
        try {
            Files.writeString(file, ProtocolCodec.encode(event) + "\n", StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            saveMeta();
            if (Files.size(file) > maxBytes) {
                compact();
            }
        } catch (IOException e) {
            log.error("Не удалось записать событие в журнал: {}", e.toString());
        }
        return event;
    }

    /** Все неподтверждённые события по возрастанию seq. */
    public synchronized List<Message.Sequenced> unacked() {
        List<Message.Sequenced> out = new ArrayList<>();
        for (Message m : readAll()) {
            if (m instanceof Message.Sequenced s && s.seq() > ackedSeq) {
                out.add(s);
            }
        }
        return out;
    }

    public synchronized void ack(long upToSeq) {
        if (upToSeq <= ackedSeq) {
            return;
        }
        ackedSeq = Math.min(upToSeq, lastSeq);
        saveMeta();
        try {
            if (ackedSeq >= lastSeq) {
                Files.deleteIfExists(file);
            } else if (Files.exists(file) && Files.size(file) > 1024 * 1024) {
                compact();
            }
        } catch (IOException e) {
            log.warn("Не удалось усечь журнал: {}", e.toString());
        }
    }

    public synchronized long lastSeq() {
        return lastSeq;
    }

    public synchronized long ackedSeq() {
        return ackedSeq;
    }

    private void compact() throws IOException {
        List<Message> all = readAll();
        List<Message> keep = new ArrayList<>();
        for (Message m : all) {
            if (m instanceof Message.Sequenced s && s.seq() > ackedSeq) {
                keep.add(m);
            }
        }
        long size = estimate(keep);
        if (size > maxBytes) {
            keep.removeIf(m -> m instanceof Messages.LogBatch);
            size = estimate(keep);
        }
        if (size > maxBytes) {
            keep.removeIf(m -> m instanceof Messages.K6Status);
        }
        StringBuilder sb = new StringBuilder();
        for (Message m : keep) {
            sb.append(ProtocolCodec.encode(m)).append('\n');
        }
        Files.writeString(file, sb.toString(), StandardCharsets.UTF_8);
    }

    private static long estimate(List<Message> messages) {
        long n = 0;
        for (Message m : messages) {
            n += ProtocolCodec.encode(m).length() + 1L;
        }
        return n;
    }

    private List<Message> readAll() {
        List<Message> out = new ArrayList<>();
        if (!Files.exists(file)) {
            return out;
        }
        try {
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                if (line.isBlank()) {
                    continue;
                }
                try {
                    out.add(ProtocolCodec.decode(line));
                } catch (IllegalArgumentException e) {
                    log.warn("Пропущена повреждённая запись журнала");
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out;
    }
}
