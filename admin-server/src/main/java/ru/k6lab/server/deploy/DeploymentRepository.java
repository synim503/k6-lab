package ru.k6lab.server.deploy;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface DeploymentRepository extends JpaRepository<Deployment, Long> {

    List<Deployment> findTop50ByGeneratorIdOrderByStartedAtDesc(Long generatorId);

    List<Deployment> findTop50ByOrderByStartedAtDesc();

    Optional<Deployment> findFirstByGeneratorIdAndStatusIn(Long generatorId, List<Deployment.Status> statuses);

    List<Deployment> findByStatusIn(List<Deployment.Status> statuses);

    @Modifying
    @Query("update Deployment d set d.status = ru.k6lab.server.deploy.Deployment.Status.PENDING "
            + "where d.id = :id and d.status = ru.k6lab.server.deploy.Deployment.Status.WAITING_HOST_KEY")
    int claimHostKeyConfirm(@Param("id") Long id);
}
