package ru.k6lab.server.generator;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.k6lab.core.protocol.Messages;
import ru.k6lab.server.agent.AgentSessionRegistry;
import ru.k6lab.server.distribution.DistributionArtifact;
import ru.k6lab.server.distribution.DistributionArtifact.Arch;
import ru.k6lab.server.distribution.DistributionArtifact.Kind;
import ru.k6lab.server.distribution.DistributionService;

import java.util.List;
import java.util.Optional;

@Service
public class GeneratorViewService {

    /** CPU выше порога означает, что сам генератор стал узким местом. */
    public static final double CPU_HOT_PCT = 80.0;

    private final GeneratorRepository generators;
    private final GeneratorMetricsService metrics;
    private final AgentSessionRegistry registry;
    private final DistributionService distributions;

    public GeneratorViewService(GeneratorRepository generators, GeneratorMetricsService metrics, AgentSessionRegistry registry,
                                DistributionService distributions) {
        this.generators = generators;
        this.metrics = metrics;
        this.registry = registry;
        this.distributions = distributions;
    }

    @Transactional(readOnly = true)
    public List<GeneratorView> all() {
        return generators.findAllByOrderByNameAsc().stream().map(this::view).toList();
    }

    @Transactional(readOnly = true)
    public GeneratorView one(Long id) {
        Generator g = generators.findById(id).orElseThrow(() -> new IllegalArgumentException("Генератор не найден"));
        return view(g);
    }

    private GeneratorView view(Generator g) {
        String label;
        String badge;
        if (g.isMaintenance()) {
            label = "Обслуживание";
            badge = "text-bg-warning";
        } else if (g.getStatus() == Generator.Status.ONLINE && g.isBusy()) {
            label = "Занят";
            badge = "text-bg-primary";
        } else {
            label = g.getStatus().getTitle();
            badge = switch (g.getStatus()) {
                case ONLINE -> "text-bg-success";
                case OFFLINE -> "text-bg-secondary";
                case ERROR -> "text-bg-danger";
                case INSTALLING, REMOVING -> "text-bg-info";
            };
        }
        boolean connected = registry.isConnected(g.getId());
        GeneratorMetricsService.Row m = connected ? metrics.latest(g.getId()) : null;
        Messages.Heartbeat hb = m == null ? null : m.hb();
        Double memPct = hb == null || hb.ramTotalBytes() == 0 ? null : 100.0 * hb.ramUsedBytes() / hb.ramTotalBytes();
        boolean hot = hb != null && hb.cpuPct() > CPU_HOT_PCT;
        Long off = g.getClockOffsetMs();
        boolean clockBad = off != null && Math.abs(off) > 500;
        boolean clockWarn = off != null && Math.abs(off) > 250 && !clockBad;

        Arch arch = g.getArch() == null ? null : Arch.valueOf(g.getArch());
        Optional<DistributionArtifact> defAgent = distributions.defaultFor(Kind.AGENT, Arch.ANY);
        Optional<DistributionArtifact> defK6 = arch == null ? Optional.empty() : distributions.defaultFor(Kind.K6, arch);
        boolean agentDrift = defAgent.isPresent() && g.getAgentVersion() != null
                && !norm(defAgent.get().getVersion()).equals(norm(g.getAgentVersion()));
        boolean k6Drift = defK6.isPresent() && g.getK6Version() != null
                && !norm(defK6.get().getVersion()).equals(norm(g.getK6Version()));
        return new GeneratorView(g, label, badge, connected,
                hb == null ? null : hb.cpuPct(), memPct, hb == null ? null : hb.netRxBytesPerSec(),
                hb == null ? null : hb.netTxBytesPerSec(), hb == null ? null : hb.load1(),
                hb == null ? null : hb.diskFreeBytes() / (1024 * 1024 * 1024), hb == null ? null : hb.openSockets(),
                hot, off, clockWarn, clockBad, agentDrift, k6Drift, defAgent.map(DistributionArtifact::getVersion).orElse(null),
                defK6.map(DistributionArtifact::getVersion).orElse(null));
    }

    private static String norm(String v) {
        String s = v.trim();
        return s.startsWith("v") ? s.substring(1) : s;
    }
}
