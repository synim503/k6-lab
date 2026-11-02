package ru.k6lab.server.distribution;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface DistributionRepository extends JpaRepository<DistributionArtifact, Long> {

    List<DistributionArtifact> findAllByOrderByKindAscCreatedAtDesc();

    List<DistributionArtifact> findByKindOrderByCreatedAtDesc(DistributionArtifact.Kind kind);

    @Query("select a from DistributionArtifact a where a.kind = :kind and a.isDefault = true "
            + "and (a.arch = :arch or a.arch = ru.k6lab.server.distribution.DistributionArtifact.Arch.ANY)")
    List<DistributionArtifact> findDefaults(DistributionArtifact.Kind kind, DistributionArtifact.Arch arch);

    @Query("select a from DistributionArtifact a where a.kind = :kind and a.arch = :arch and a.isDefault = true")
    List<DistributionArtifact> findDefaultsExact(DistributionArtifact.Kind kind, DistributionArtifact.Arch arch);

    Optional<DistributionArtifact> findFirstByKindAndVersionAndArchAndLabel(
            DistributionArtifact.Kind kind, String version, DistributionArtifact.Arch arch, String label);
}
