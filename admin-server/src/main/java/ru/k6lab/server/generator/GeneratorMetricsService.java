package ru.k6lab.server.generator;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import ru.k6lab.core.protocol.Messages;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Метрики ресурсов генераторов (heartbeat). Последний снимок держим в памяти для UI, историю пишем батчами.
 * Retention - 7 дней.
 */
@Service
public class GeneratorMetricsService {

    private static final Logger log = LoggerFactory.getLogger(GeneratorMetricsService.class);
    private static final Duration RETENTION = Duration.ofDays(7);

    public record Row(long generatorId, Instant ts, Messages.Heartbeat hb, int activeRuns) {
    }

    private final JdbcTemplate jdbc;
    private final Queue<Row> buffer = new ConcurrentLinkedQueue<>();
    private final Map<Long, Row> latest = new ConcurrentHashMap<>();

    public GeneratorMetricsService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void record(long generatorId, Messages.Heartbeat hb, int activeRuns) {
        Row row = new Row(generatorId, Instant.now(), hb, activeRuns);
        latest.put(generatorId, row);
        buffer.add(row);
    }

    public Row latest(long generatorId) {
        return latest.get(generatorId);
    }

    public void forget(long generatorId) {
        latest.remove(generatorId);
    }

    @Scheduled(fixedDelay = 5000)
    public void flush() {
        List<Row> batch = new ArrayList<>();
        Row r;
        while ((r = buffer.poll()) != null && batch.size() < 5000) {
            batch.add(r);
        }
        if (batch.isEmpty()) {
            return;
        }
        try {
            jdbc.batchUpdate("insert into generator_metrics (generator_id, ts, cpu, mem_used, mem_total, net_rx, net_tx,"
                            + " load1, disk_free, sockets, active_runs) values (?,?,?,?,?,?,?,?,?,?,?)",
                    batch, batch.size(), (ps, row) -> {
                        Messages.Heartbeat hb = row.hb();
                        ps.setLong(1, row.generatorId());
                        ps.setTimestamp(2, Timestamp.from(row.ts()));
                        ps.setFloat(3, (float) hb.cpuPct());
                        ps.setLong(4, hb.ramUsedBytes());
                        ps.setLong(5, hb.ramTotalBytes());
                        ps.setLong(6, hb.netRxBytesPerSec());
                        ps.setLong(7, hb.netTxBytesPerSec());
                        ps.setFloat(8, (float) hb.load1());
                        ps.setLong(9, hb.diskFreeBytes());
                        ps.setInt(10, hb.openSockets());
                        ps.setInt(11, row.activeRuns());
                    });
        } catch (RuntimeException e) {
            log.warn("Не удалось записать метрики генераторов ({} строк): {}", batch.size(), e.toString());
        }
    }

    @Scheduled(cron = "0 17 * * * *")
    public void purgeOld() {
        int n = jdbc.update("delete from generator_metrics where ts < ?",
                Timestamp.from(Instant.now().minus(RETENTION)));
        if (n > 0) {
            log.info("Удалено {} старых строк generator_metrics", n);
        }
    }
}
