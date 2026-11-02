package ru.k6lab.server.generator;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.k6lab.server.audit.AuditService;

import java.util.List;
import java.util.Map;

/** Операции над записью генератора, не требующие SSH. */
@Service
public class GeneratorService {

    private final GeneratorRepository generators;
    private final GeneratorPoolRepository pools;
    private final AuditService audit;

    public GeneratorService(GeneratorRepository generators, GeneratorPoolRepository pools, AuditService audit) {
        this.generators = generators;
        this.pools = pools;
        this.audit = audit;
    }

    @Transactional
    public void setMaintenance(Long id, boolean on) {
        Generator g = generators.findById(id).orElseThrow();
        g.setMaintenance(on);
        audit.record(on ? "generator.maintenance_on" : "generator.maintenance_off", "generator", id, Map.of("name", g.getName()));
    }

    @Transactional
    public void update(Long id, Long poolId, List<String> labels, int maxConcurrentRuns) {
        if (maxConcurrentRuns < 1) {
            throw new IllegalArgumentException("Максимум одновременных run должен быть не меньше 1");
        }
        Generator g = generators.findById(id).orElseThrow();
        g.setPoolId(poolId);
        g.setLabels(labels);
        g.setMaxConcurrentRuns(maxConcurrentRuns);
        audit.record("generator.edit", "generator", id, Map.of("pool", String.valueOf(poolId), "maxRuns", maxConcurrentRuns));
    }

    @Transactional
    public GeneratorPool createPool(String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Укажите имя пула");
        }
        if (pools.existsByNameIgnoreCase(name.trim())) {
            throw new IllegalArgumentException("Пул с таким именем уже есть");
        }
        GeneratorPool p = new GeneratorPool();
        p.setName(name.trim());
        GeneratorPool saved = pools.save(p);
        audit.record("pool.create", "pool", saved.getId(), Map.of("name", saved.getName()));
        return saved;
    }

    @Transactional
    public void deletePool(Long id) {
        generators.findByPoolIdOrderByNameAsc(id).forEach(g -> g.setPoolId(null));
        pools.deleteById(id);
        audit.record("pool.delete", "pool", id, null);
    }

    @Transactional(readOnly = true)
    public List<GeneratorPool> pools() {
        return pools.findAllByOrderByNameAsc();
    }

    @Transactional(readOnly = true)
    public Generator get(Long id) {
        return generators.findById(id).orElseThrow(() -> new IllegalArgumentException("Генератор не найден"));
    }
}
