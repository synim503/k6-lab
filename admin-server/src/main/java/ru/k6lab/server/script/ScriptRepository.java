package ru.k6lab.server.script;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ScriptRepository extends JpaRepository<Script, Long> {

    List<Script> findAllByOrderByNameAsc();

    boolean existsByNameIgnoreCase(String name);
}
