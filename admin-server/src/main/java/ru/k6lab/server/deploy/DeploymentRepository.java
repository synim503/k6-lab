package ru.k6lab.server.deploy;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface DeploymentRepository extends JpaRepository<Deployment, Long> {

    List<Deployment> findTop50ByGeneratorIdOrderByStartedAtDesc(Long generatorId);

    List<Deployment> findTop50ByOrderByStartedAtDesc();

    Optional<Deployment> findFirstByGeneratorIdAndStatusIn(Long generatorId, List<Deployment.Status> statuses);

    List<Deployment> findByStatusIn(List<Deployment.Status> statuses);
}
