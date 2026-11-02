package ru.k6lab.agent.sys;

import org.springframework.stereotype.Component;
import ru.k6lab.agent.config.AgentProperties;
import ru.k6lab.core.protocol.Messages;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** Снимок ресурсов хоста из /proc (Linux). На других ОС недоступные значения равны 0. */
@Component
public class SystemMetrics {

    private final AgentProperties props;
    private long prevCpuTotal = -1;
    private long prevCpuIdle = -1;
    private long prevNetRx = -1;
    private long prevNetTx = -1;
    private long prevNetAt;

    public SystemMetrics(AgentProperties props) {
        this.props = props;
    }

    public synchronized Messages.Heartbeat snapshot(String k6Version, String agentVersion, List<String> activeRuns) {
        double cpu = cpuPct();
        long[] mem = memory();
        long[] net = netRates();
        double[] load = loadAvg();
        long disk = 0;
        try {
            disk = Files.getFileStore(props.getHome()).getUsableSpace();
        } catch (IOException ignored) {
            // нет данных
        }
        return new Messages.Heartbeat(System.currentTimeMillis(), cpu, mem[0], mem[1], net[0], net[1],
                load[0], load[1], load[2], disk, sockets(), k6Version, agentVersion, activeRuns);
    }

    private double cpuPct() {
        List<String> lines = readLines("/proc/stat");
        if (lines.isEmpty()) {
            return 0;
        }
        String[] p = lines.get(0).trim().split("\\s+");
        long total = 0;
        for (int i = 1; i < p.length; i++) {
            total += Long.parseLong(p[i]);
        }
        long idle = Long.parseLong(p[4]) + (p.length > 5 ? Long.parseLong(p[5]) : 0);
        double pct = 0;
        if (prevCpuTotal >= 0 && total > prevCpuTotal) {
            pct = 100.0 * (1.0 - (double) (idle - prevCpuIdle) / (total - prevCpuTotal));
        }
        prevCpuTotal = total;
        prevCpuIdle = idle;
        return Math.max(0, Math.min(100, pct));
    }

    private long[] memory() {
        long total = 0;
        long avail = 0;
        for (String l : readLines("/proc/meminfo")) {
            if (l.startsWith("MemTotal:")) {
                total = kb(l);
            } else if (l.startsWith("MemAvailable:")) {
                avail = kb(l);
            }
        }
        return new long[]{Math.max(0, total - avail), total};
    }

    private static long kb(String line) {
        String[] p = line.split("\\s+");
        return Long.parseLong(p[1]) * 1024;
    }

    private long[] netRates() {
        long rx = 0;
        long tx = 0;
        for (String l : readLines("/proc/net/dev")) {
            int c = l.indexOf(':');
            if (c < 0) {
                continue;
            }
            String iface = l.substring(0, c).trim();
            if (iface.equals("lo")) {
                continue;
            }
            String[] p = l.substring(c + 1).trim().split("\\s+");
            rx += Long.parseLong(p[0]);
            tx += Long.parseLong(p[8]);
        }
        long now = System.nanoTime();
        long[] out = {0, 0};
        if (prevNetRx >= 0 && now > prevNetAt) {
            double sec = (now - prevNetAt) / 1e9;
            out[0] = Math.max(0, (long) ((rx - prevNetRx) / sec));
            out[1] = Math.max(0, (long) ((tx - prevNetTx) / sec));
        }
        prevNetRx = rx;
        prevNetTx = tx;
        prevNetAt = now;
        return out;
    }

    private double[] loadAvg() {
        List<String> l = readLines("/proc/loadavg");
        if (l.isEmpty()) {
            return new double[]{0, 0, 0};
        }
        String[] p = l.get(0).split("\\s+");
        return new double[]{Double.parseDouble(p[0]), Double.parseDouble(p[1]), Double.parseDouble(p[2])};
    }

    private int sockets() {
        for (String l : readLines("/proc/net/sockstat")) {
            if (l.startsWith("sockets:")) {
                return Integer.parseInt(l.split("\\s+")[2]);
            }
        }
        return 0;
    }

    private static List<String> readLines(String path) {
        try {
            Path p = Path.of(path);
            return Files.exists(p) ? Files.readAllLines(p, StandardCharsets.UTF_8) : List.of();
        } catch (IOException | RuntimeException e) {
            return List.of();
        }
    }
}
