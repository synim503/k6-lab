package ru.k6lab.server.generator;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.k6lab.server.audit.AuditService;

import java.util.Map;

/** Операции над записью генератора, не требующие SSH. */
@Service
public class GeneratorService {

    private final GeneratorRepository generators;
    private final AuditService audit;

    public GeneratorService(GeneratorRepository generators, AuditService audit) {
        this.generators = generators;
        this.audit = audit;
    }

    @Transactional
    public void setMaintenance(Long id, boolean on) {
        Generator g = generators.findById(id).orElseThrow();
        g.setMaintenance(on);
        audit.record(on ? "generator.maintenance_on" : "generator.maintenance_off", "generator", id, Map.of("name", g.getName()));
    }

    @Transactional
    public void update(Long id, int maxConcurrentRuns) {
        if (maxConcurrentRuns < 1) {
            throw new IllegalArgumentException("Максимум одновременных запусков должен быть не меньше 1");
        }
        Generator g = generators.findById(id).orElseThrow();
        g.setMaxConcurrentRuns(maxConcurrentRuns);
        audit.record("generator.edit", "generator", id, Map.of("maxRuns", maxConcurrentRuns));
    }

    @Transactional(readOnly = true)
    public Generator get(Long id) {
        return generators.findById(id).orElseThrow(() -> new IllegalArgumentException("Генератор не найден"));
    }
}
