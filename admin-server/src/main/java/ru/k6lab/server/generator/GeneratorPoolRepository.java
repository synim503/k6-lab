package ru.k6lab.server.generator;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface GeneratorPoolRepository extends JpaRepository<GeneratorPool, Long> {

    List<GeneratorPool> findAllByOrderByNameAsc();

    boolean existsByNameIgnoreCase(String name);
}
