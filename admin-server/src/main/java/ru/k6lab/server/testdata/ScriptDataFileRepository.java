package ru.k6lab.server.testdata;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ScriptDataFileRepository extends JpaRepository<ScriptDataFile, Long> {

    List<ScriptDataFile> findByScriptIdOrderByPathAsc(Long scriptId);

    void deleteByScriptId(Long scriptId);
}
