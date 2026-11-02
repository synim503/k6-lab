package ru.k6lab.server.scenario;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ScenarioRepository extends JpaRepository<Scenario, Long> {

    List<Scenario> findAllByOrderByNameAsc();

    boolean existsByNameIgnoreCase(String name);

    boolean existsByScriptId(Long scriptId);
}
