package ru.k6lab.agent;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.k6lab.agent.journal.EventJournal;
import ru.k6lab.agent.ws.Backoff;
import ru.k6lab.core.protocol.Message;
import ru.k6lab.core.protocol.Messages;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class BackoffAndJournalTest {

    @Test
    void backoffDoublesAndIsCapped() {
        Backoff b = new Backoff(Duration.ofSeconds(1), Duration.ofSeconds(60), () -> 0.5); // jitter = 1.0
        assertThat(b.next()).isEqualTo(Duration.ofSeconds(1));
        assertThat(b.next()).isEqualTo(Duration.ofSeconds(2));
        assertThat(b.next()).isEqualTo(Duration.ofSeconds(4));
        for (int i = 0; i < 10; i++) {
            b.next();
        }
        assertThat(b.next()).isEqualTo(Duration.ofSeconds(60));
        b.reset();
        assertThat(b.next()).isEqualTo(Duration.ofSeconds(1));
    }

    @Test
    void backoffJitterStaysWithinTwentyPercent() {
        Backoff low = new Backoff(Duration.ofSeconds(10), Duration.ofMinutes(10), () -> 0.0);
        Backoff high = new Backoff(Duration.ofSeconds(10), Duration.ofMinutes(10), () -> 1.0);
        assertThat(low.next()).isEqualTo(Duration.ofSeconds(8));
        assertThat(high.next()).isEqualTo(Duration.ofSeconds(12));
    }

    @Test
    void journalKeepsUnackedAcrossRestartAndTrimsAfterAck(@TempDir Path dir) {
        EventJournal j = new EventJournal(dir, 10_000_000);
        j.append(seq -> new Messages.Prepared(seq, 1, "r1"));
        j.append(seq -> new Messages.LogBatch(seq, 2, "r1", List.of(), 0));
        j.append(seq -> new Messages.Exit(seq, 3, "r1", 0, null));
        assertThat(j.lastSeq()).isEqualTo(3);

        EventJournal restarted = new EventJournal(dir, 10_000_000);
        assertThat(restarted.lastSeq()).isEqualTo(3);
        assertThat(restarted.unacked()).extracting(Message.Sequenced::seq).containsExactly(1L, 2L, 3L);

        restarted.ack(2);
        assertThat(restarted.unacked()).extracting(Message.Sequenced::seq).containsExactly(3L);

        restarted.ack(3);
        assertThat(restarted.unacked()).isEmpty();
        // seq не откатывается после полной очистки журнала
        Messages.Prepared next = restarted.append(seq -> new Messages.Prepared(seq, 4, "r2"));
        assertThat(next.seq()).isEqualTo(4);
        assertThat(new EventJournal(dir, 10_000_000).lastSeq()).isEqualTo(4);
    }

    @Test
    void journalDropsLogBatchesFirstWhenOverLimit(@TempDir Path dir) {
        EventJournal j = new EventJournal(dir, 600);
        j.append(seq -> new Messages.Prepared(seq, 1, "r1"));
        for (int i = 0; i < 20; i++) {
            j.append(seq -> new Messages.LogBatch(seq, 2, "r1",
                    List.of(new Messages.LogLine(1, "stdout", "x".repeat(100))), 0));
        }
        j.append(seq -> new Messages.Exit(seq, 3, "r1", 0, null));
        List<Message.Sequenced> left = j.unacked();
        assertThat(left).anyMatch(e -> e instanceof Messages.Prepared);
        assertThat(left).anyMatch(e -> e instanceof Messages.Exit);
        assertThat(left.stream().filter(e -> e instanceof Messages.LogBatch).count()).isLessThan(20);
    }
}
