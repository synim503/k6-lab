package ru.k6lab.server.run;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface RunGeneratorRepository extends JpaRepository<RunGenerator, Long> {

    List<RunGenerator> findByRunIdOrderBySeqIndexAsc(Long runId);

    List<RunGenerator> findByRunIdIn(Collection<Long> runIds);

    Optional<RunGenerator> findByRunIdAndGeneratorId(Long runId, Long generatorId);

    Optional<RunGenerator> findByRunIdAndArtifactTokenHash(Long runId, String hash);

    @Query("select rg.k6ApiPort from RunGenerator rg where rg.generatorId = :genId and rg.status not in :done")
    List<Integer> activePortsByGeneratorId(@Param("genId") Long genId, @Param("done") Collection<RunGenerator.Status> done);

    @Modifying
    @Query("update RunGenerator rg set rg.artifactConsumedAt = :now where rg.id = :id and rg.artifactConsumedAt is null")
    int consumeArtifactIfUnused(@Param("id") Long id, @Param("now") Instant now);
}
