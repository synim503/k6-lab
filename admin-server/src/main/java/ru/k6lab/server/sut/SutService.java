package ru.k6lab.server.sut;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.k6lab.server.audit.AuditService;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class SutService {

    private final SutRepository repo;
    private final JdbcTemplate jdbc;
    private final AuditService audit;

    public SutService(SutRepository repo, JdbcTemplate jdbc, AuditService audit) {
        this.repo = repo;
        this.jdbc = jdbc;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public List<Sut> list() {
        return repo.findAllByOrderByNameAsc();
    }

    @Transactional(readOnly = true)
    public Sut get(Long id) {
        return repo.findById(id).orElseThrow(() -> new IllegalArgumentException("SUT не найден"));
    }

    @Transactional
    public Sut save(SutForm f) {
        if (f.getName() == null || f.getName().isBlank()) {
            throw new IllegalArgumentException("Укажите имя SUT");
        }
        Sut s;
        if (f.getId() == null) {
            if (repo.existsByNameIgnoreCase(f.getName().trim())) {
                throw new IllegalArgumentException("SUT с таким именем уже есть");
            }
            s = new Sut();
        } else {
            s = get(f.getId());
            if (!s.getName().equalsIgnoreCase(f.getName().trim()) && repo.existsByNameIgnoreCase(f.getName().trim())) {
                throw new IllegalArgumentException("SUT с таким именем уже есть");
            }
        }
        s.setName(f.getName().trim());
        s.setDescription(f.getDescription() == null || f.getDescription().isBlank() ? null : f.getDescription().trim());
        s.setGrafanaUrlTpl(f.getGrafanaUrlTpl() == null || f.getGrafanaUrlTpl().isBlank() ? null : f.getGrafanaUrlTpl().trim());
        s.getMetrics().clear();
        int pos = 0;
        Set<String> names = new HashSet<>();
        for (SutForm.MetricForm m : f.getMetrics()) {
            if (m == null || m.blank()) {
                continue;
            }
            if (m.getName() == null || m.getName().isBlank() || m.getPromql() == null || m.getPromql().isBlank()) {
                throw new IllegalArgumentException("У метрики должны быть имя и PromQL-запрос");
            }
            if (!names.add(m.getName().trim())) {
                throw new IllegalArgumentException("Имя метрики «" + m.getName() + "» повторяется");
            }
            SutMetric sm = new SutMetric();
            sm.setSut(s);
            sm.setPosition(pos++);
            sm.setName(m.getName().trim());
            sm.setPromql(m.getPromql().trim());
            sm.setUnit(m.getUnit() == null || m.getUnit().isBlank() ? null : m.getUnit().trim());
            sm.setAggregation(m.getAggregation());
            sm.setWarnThreshold(m.getWarnThreshold());
            sm.setWarnDirection(m.getWarnDirection());
            s.getMetrics().add(sm);
        }
        Sut saved = repo.save(s);
        audit.record(f.getId() == null ? "sut.create" : "sut.update", "sut", saved.getId(), Map.of("name", saved.getName()));
        return saved;
    }

    @Transactional
    public void delete(Long id) {
        Integer n = jdbc.queryForObject("select count(*) from scenario where sut_id = ?", Integer.class, id);
        if (n != null && n > 0) {
            throw new IllegalStateException("SUT используется в сценариях");
        }
        repo.deleteById(id);
        audit.record("sut.delete", "sut", id, null);
    }

    @Transactional(readOnly = true)
    public SutForm toForm(Long id) {
        Sut s = get(id);
        SutForm f = new SutForm();
        f.setId(s.getId());
        f.setName(s.getName());
        f.setDescription(s.getDescription());
        f.setGrafanaUrlTpl(s.getGrafanaUrlTpl());
        for (SutMetric m : s.getMetrics()) {
            SutForm.MetricForm mf = new SutForm.MetricForm();
            mf.setName(m.getName());
            mf.setPromql(m.getPromql());
            mf.setUnit(m.getUnit());
            mf.setAggregation(m.getAggregation());
            mf.setWarnThreshold(m.getWarnThreshold());
            mf.setWarnDirection(m.getWarnDirection());
            f.getMetrics().add(mf);
        }
        return f;
    }
}
