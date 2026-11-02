package ru.k6lab.server.loadmodel;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface LoadModelRepository extends JpaRepository<LoadModel, Long> {

    List<LoadModel> findAllByOrderByNameAsc();

    List<LoadModel> findByScriptIdOrderByNameAsc(Long scriptId);

    boolean existsByNameIgnoreCase(String name);

    boolean existsByScriptId(Long scriptId);
}
