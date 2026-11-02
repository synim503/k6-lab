package ru.k6lab.server.script;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface ScriptVersionRepository extends JpaRepository<ScriptVersion, Long> {

    List<ScriptVersion> findByScriptIdOrderByNumberDesc(Long scriptId);

    Optional<ScriptVersion> findFirstByScriptIdOrderByNumberDesc(Long scriptId);

    Optional<ScriptVersion> findFirstByScriptIdAndGitBranchOrderByNumberDesc(Long scriptId, String branch);

    Optional<ScriptVersion> findByScriptIdAndCommitHash(Long scriptId, String commitHash);

    @Query("select coalesce(max(v.number), 0) from ScriptVersion v where v.scriptId = :scriptId")
    int maxNumber(Long scriptId);

    long countByScriptId(Long scriptId);
}
