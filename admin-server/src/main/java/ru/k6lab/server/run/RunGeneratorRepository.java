package ru.k6lab.server.run;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface RunGeneratorRepository extends JpaRepository<RunGenerator, Long> {

    List<RunGenerator> findByRunIdOrderBySeqIndexAsc(Long runId);

    List<RunGenerator> findByRunIdIn(Collection<Long> runIds);

    Optional<RunGenerator> findByRunIdAndGeneratorId(Long runId, Long generatorId);

    Optional<RunGenerator> findByRunIdAndArtifactTokenHash(Long runId, String hash);
}
