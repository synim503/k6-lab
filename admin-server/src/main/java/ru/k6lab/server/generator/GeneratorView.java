package ru.k6lab.server.generator;

/** Представление генератора для UI: статус с учётом maintenance/занятости, ресурсы, расхождения версий. */
public record GeneratorView(Generator g, String statusLabel, String badgeClass, boolean connected,
                            Double cpu, Double memPct, Long netRx, Long netTx, Double load1, Long diskFreeGb,
                            Integer sockets, boolean cpuHot, Long clockOffsetMs, boolean clockWarn, boolean clockBad,
                            boolean agentDrift, boolean k6Drift, String defaultAgent, String defaultK6) {

    public boolean hasDrift() {
        return agentDrift || k6Drift;
    }
}
