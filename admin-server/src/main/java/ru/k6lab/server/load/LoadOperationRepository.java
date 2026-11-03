package ru.k6lab.server.load;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface LoadOperationRepository extends JpaRepository<LoadOperation, Long> {

    List<LoadOperation> findByScriptIdOrderByPositionAsc(Long scriptId);

    void deleteByScriptId(Long scriptId);
}
