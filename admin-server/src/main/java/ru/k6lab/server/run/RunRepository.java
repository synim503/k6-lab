package ru.k6lab.server.run;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import ru.k6lab.core.runstate.RunStatus;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface RunRepository extends JpaRepository<Run, Long> {

    List<Run> findByStatusOrderByQueuedAtAsc(RunStatus status);

    List<Run> findByStatusIn(Collection<RunStatus> statuses);

    List<Run> findTop200ByOrderByIdDesc();

    List<Run> findByScenarioIdOrderByIdDesc(Long scenarioId);

    Optional<Run> findFirstByScenarioIdAndBaselineTrue(Long scenarioId);

    @Query("select r from Run r where (:scenarioId is null or r.scenarioId = :scenarioId) "
            + "and (:status is null or r.status = :status) order by r.id desc")
    List<Run> search(Long scenarioId, RunStatus status, org.springframework.data.domain.Pageable page);
}
