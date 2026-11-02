package ru.k6lab.server.testdata;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ScenarioDataFileRepository extends JpaRepository<ScenarioDataFile, Long> {

    List<ScenarioDataFile> findByScenarioIdOrderByPathAsc(Long scenarioId);

    void deleteByScenarioId(Long scenarioId);
}
