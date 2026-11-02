package ru.k6lab.server.scenario;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

public interface SecretRepository extends JpaRepository<Secret, Long> {
    List<Secret> findAllByOrderByNameAsc();

    List<Secret> findByNameIn(Collection<String> names);

    boolean existsByNameIgnoreCase(String name);
}
