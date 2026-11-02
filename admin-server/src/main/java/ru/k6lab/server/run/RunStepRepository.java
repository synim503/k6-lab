package ru.k6lab.server.run;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface RunStepRepository extends JpaRepository<RunStep, Long> {

    List<RunStep> findByRunIdOrderByStepNoAsc(Long runId);

    void deleteByRunId(Long runId);
}
