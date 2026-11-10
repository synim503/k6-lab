package ru.k6lab.server.run;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import ru.k6lab.core.runstate.RunStatus;

import java.util.List;

/**
 * Диспетчер очереди: нехватка генераторов -> run в QUEUED. Раз в 2 секунды пытаемся захватить генераторы
 * для самых старых run; порядок - FIFO по времени постановки.
 */
@Component
public class RunDispatcher {

    private static final Logger log = LoggerFactory.getLogger(RunDispatcher.class);

    private final RunRepository runs;
    private final RunReservation reservation;
    private final RunOrchestrator orchestrator;

    public RunDispatcher(RunRepository runs, RunReservation reservation, RunOrchestrator orchestrator) {
        this.runs = runs;
        this.reservation = reservation;
        this.orchestrator = orchestrator;
    }

    @Scheduled(fixedDelay = 2000, initialDelay = 5000)
    public void dispatch() {
        List<Run> queued = runs.findByStatusOrderByQueuedAtAsc(RunStatus.QUEUED);
        for (Run r : queued) {
            MDC.put("run_id", String.valueOf(r.getId()));
            try {
                var reserved = reservation.reserve(r.getId());
                if (reserved.isPresent()) {
                    log.info("Run {}: захвачено генераторов {}, подготовка", r.getId(), reserved.get().size());
                    orchestrator.begin(r.getId());
                } else if (!reservation.missingGenerators(r.getId()).isEmpty()) {
                    // генератор удалён, пока run ждал: очередь не дождётся его никогда
                    orchestrator.failQuietly(r.getId(), "Выбранный генератор удалён, пока запуск ждал в очереди");
                }
            } catch (RuntimeException e) {
                log.error("Не удалось запустить run {}", r.getId(), e);
                orchestrator.failQuietly(r.getId(), "Внутренняя ошибка диспетчера: " + e);
            } finally {
                MDC.remove("run_id");
            }
        }
    }
}
