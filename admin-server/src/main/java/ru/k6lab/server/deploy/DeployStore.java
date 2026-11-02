package ru.k6lab.server.deploy;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.k6lab.server.generator.Generator;
import ru.k6lab.server.generator.GeneratorRepository;

import java.time.Instant;
import java.util.List;
import java.util.function.Consumer;

/** Короткие транзакции для сохранения прогресса операций (вызываются из рабочих потоков). */
@Service
public class DeployStore {

    private final DeploymentRepository deployments;
    private final GeneratorRepository generators;

    public DeployStore(DeploymentRepository deployments, GeneratorRepository generators) {
        this.deployments = deployments;
        this.generators = generators;
    }

    @Transactional
    public Deployment create(Deployment d) {
        return deployments.save(d);
    }

    @Transactional(readOnly = true)
    public Deployment load(Long id) {
        return deployments.findById(id).orElseThrow();
    }

    @Transactional
    public void markRunning(Long id) {
        Deployment d = deployments.findById(id).orElseThrow();
        d.setStatus(Deployment.Status.RUNNING);
        d.setError(null);
        d.setHostKeyFingerprint(null);
        d.setHostKeyPublic(null);
        d.setHostKeyType(null);
        d.setHostKeyHost(null);
        d.setHostKeyPort(null);
        d.setFinishedAt(null);
    }

    @Transactional
    public void saveProgress(Long id, List<Deployment.StepInfo> steps, String log) {
        Deployment d = deployments.findById(id).orElseThrow();
        d.setSteps(List.copyOf(steps));
        d.setLog(log);
    }

    @Transactional
    public void finish(Long id, Deployment.Status status, String error, List<Deployment.StepInfo> steps, String log) {
        Deployment d = deployments.findById(id).orElseThrow();
        d.setStatus(status);
        d.setError(error);
        d.setSteps(List.copyOf(steps));
        d.setLog(log);
        d.setFinishedAt(Instant.now());
    }

    @Transactional
    public void waitHostKey(Long id, String host, int port, String type, String fingerprint, String publicKey,
                            List<Deployment.StepInfo> steps, String log) {
        Deployment d = deployments.findById(id).orElseThrow();
        d.setStatus(Deployment.Status.WAITING_HOST_KEY);
        d.setHostKeyHost(host);
        d.setHostKeyPort(port);
        d.setHostKeyType(type);
        d.setHostKeyFingerprint(fingerprint);
        d.setHostKeyPublic(publicKey);
        d.setSteps(List.copyOf(steps));
        d.setLog(log);
        d.setFinishedAt(Instant.now());
    }

    @Transactional
    public void updateGenerator(Long id, Consumer<Generator> change) {
        generators.findById(id).ifPresent(change);
    }

    @Transactional(readOnly = true)
    public boolean hasActive(Long generatorId) {
        return deployments.findFirstByGeneratorIdAndStatusIn(generatorId,
                List.of(Deployment.Status.PENDING, Deployment.Status.RUNNING)).isPresent();
    }

    @Transactional(readOnly = true)
    public Generator loadGenerator(Long id) {
        return generators.findById(id).orElseThrow();
    }

    @Transactional
    public void deleteGenerator(Long id) {
        generators.findById(id).ifPresent(generators::delete);
    }

    /** При старте сервера операции, «зависшие» в RUNNING от прошлого процесса, помечаются как прерванные. */
    @Transactional
    public int failOrphans() {
        List<Deployment> orphans = deployments.findByStatusIn(List.of(Deployment.Status.RUNNING, Deployment.Status.PENDING));
        for (Deployment d : orphans) {
            d.setStatus(Deployment.Status.FAILED);
            d.setError("Операция прервана перезапуском сервера");
            d.setFinishedAt(Instant.now());
        }
        return orphans.size();
    }
}
