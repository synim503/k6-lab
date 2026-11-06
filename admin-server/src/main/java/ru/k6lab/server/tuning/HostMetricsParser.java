package ru.k6lab.server.tuning;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Разбор вывода скрипта профилирования (tuning/profile.sh) в {@link HostMetrics}: разделы @@meta, @@sysctl, @@limits, @@s1, @@s2. */
final class HostMetricsParser {

    private HostMetricsParser() {
    }

    /** Один снимок счётчиков. */
    private static final class Snap {
        double time;
        long[] cpu = new long[0];
        long softnetDropped;
        long softnetSqueezed;
        Map<String, Long> snmp = new HashMap<>();
        Map<String, Long> netstat = new HashMap<>();
        Map<String, Long> sockstat = new HashMap<>();
        Map<String, Long> nic = new HashMap<>();
        List<String> snmpNames;
        List<String> netstatNames;
    }

    static HostMetrics parse(String output, double requestedWindowSec) {
        HostMetrics m = new HostMetrics();
        Snap s1 = new Snap(), s2 = new Snap();
        String section = "";
        for (String raw : output.split("\\R")) {
            String line = raw.strip();
            if (line.isEmpty()) {
                continue;
            }
            if (line.startsWith("@@")) {
                section = line.substring(2);
                continue;
            }
            switch (section) {
                case "meta" -> meta(m, line);
                case "sysctl" -> {
                    int eq = line.indexOf('=');
                    if (eq > 0) {
                        m.sysctl.put(line.substring(0, eq), line.substring(eq + 1).strip().replaceAll("\\s+", " "));
                    }
                }
                case "limits" -> {
                    String[] t = line.split("\\s+");
                    if (t.length >= 5 && t[0].equals("k6")) {
                        m.k6.add(new HostMetrics.K6Proc(num(t[1]), numOrMax(t[2]), numOrMax(t[3]), num(t[4])));
                    }
                }
                case "s1" -> snap(s1, line);
                case "s2" -> snap(s2, line);
                default -> { }
            }
        }
        window(m, s1, s2, requestedWindowSec);
        return m;
    }

    private static void meta(HostMetrics m, String line) {
        String[] t = line.split("\\s+", 2);
        String v = t.length > 1 ? t[1].strip() : "";
        switch (t[0]) {
            case "nproc" -> m.cpus = (int) num(v);
            case "mem_total_kb" -> m.memTotalKb = num(v);
            case "mem_avail_kb" -> m.memAvailKb = num(v);
            case "swap_total_kb" -> m.swapTotalKb = num(v);
            case "swap_free_kb" -> m.swapFreeKb = num(v);
            case "kernel" -> m.kernel = v;
            case "iface" -> m.iface = v.isEmpty() ? null : v;
            case "speed_mbit" -> m.speedMbit = v.isEmpty() || num(v) <= 0 ? null : num(v);
            case "thp" -> m.thp = v.isEmpty() ? null : v;
            case "governor" -> m.governor = v.isEmpty() ? null : v;
            case "conntrack_count" -> m.conntrackCount = v.isEmpty() ? null : num(v);
            case "conntrack_max" -> m.conntrackMax = v.isEmpty() ? null : num(v);
            case "psi_cpu" -> m.psiCpu = dbl(v);
            case "psi_mem" -> m.psiMem = dbl(v);
            default -> { }
        }
    }

    private static void snap(Snap s, String line) {
        String[] t = line.split("\\s+");
        switch (t[0]) {
            case "time" -> s.time = t.length > 1 ? Double.parseDouble(t[1]) : 0;
            case "stat" -> {
                // stat cpu user nice system idle iowait irq softirq steal ...
                if (t.length > 3) {
                    s.cpu = new long[t.length - 2];
                    for (int i = 2; i < t.length; i++) {
                        s.cpu[i - 2] = num(t[i]);
                    }
                }
            }
            case "softnet" -> {
                if (t.length >= 4) {
                    s.softnetDropped += hex(t[2]);
                    s.softnetSqueezed += hex(t[3]);
                }
            }
            case "snmp" -> table(line, "Tcp:", s.snmp, s, true);
            case "netstat" -> table(line, "TcpExt:", s.netstat, s, false);
            case "sockstat" -> {
                for (int i = 2; i + 1 < t.length; i += 2) {
                    s.sockstat.put(t[i], num(t[i + 1]));
                }
            }
            case "nic" -> {
                if (t.length >= 3) {
                    s.nic.put(t[1], num(t[2]));
                }
            }
            default -> { }
        }
    }

    /** Строки вида «snmp Tcp: имена...» и «snmp Tcp: значения...»: первая - заголовок, вторая - значения. */
    private static void table(String line, String proto, Map<String, Long> out, Snap s, boolean snmp) {
        String[] t = line.split("\\s+");
        if (t.length < 3 || !t[1].equals(proto)) {
            return;
        }
        List<String> cells = new ArrayList<>(List.of(t).subList(2, t.length));
        List<String> names = snmp ? s.snmpNames : s.netstatNames;
        if (names == null) {
            if (snmp) {
                s.snmpNames = cells;
            } else {
                s.netstatNames = cells;
            }
            return;
        }
        for (int i = 0; i < Math.min(names.size(), cells.size()); i++) {
            out.put(names.get(i), num(cells.get(i)));
        }
    }

    private static void window(HostMetrics m, Snap a, Snap b, double requested) {
        double dt = b.time - a.time;
        m.windowSec = dt > 0 ? dt : requested;
        // CPU: доли времени за окно
        if (a.cpu.length >= 8 && b.cpu.length >= 8) {
            long[] d = new long[8];
            long total = 0;
            for (int i = 0; i < 8; i++) {
                d[i] = Math.max(0, b.cpu[i] - a.cpu[i]);
                total += d[i];
            }
            if (total > 0) {
                double t = total;
                m.cpuUserPct = round((d[0] + d[1]) * 100 / t);
                m.cpuSystemPct = round(d[2] * 100 / t);
                m.cpuIowaitPct = round(d[4] * 100 / t);
                m.cpuSoftirqPct = round((d[5] + d[6]) * 100 / t);
                m.cpuStealPct = round(d[7] * 100 / t);
                m.cpuBusyPct = round((total - d[3] - d[4]) * 100 / t);
            }
        }
        m.softnetDropped = Math.max(0, b.softnetDropped - a.softnetDropped);
        m.softnetSqueezed = Math.max(0, b.softnetSqueezed - a.softnetSqueezed);
        long out = delta(a.snmp, b.snmp, "OutSegs"), retr = delta(a.snmp, b.snmp, "RetransSegs");
        m.tcpOutSegsPerSec = m.windowSec > 0 ? round(out / m.windowSec) : null;
        m.tcpRetransPct = out > 0 ? round(retr * 100.0 / out) : null;
        m.tcpAttemptFails = delta(a.snmp, b.snmp, "AttemptFails");
        m.tcpEstabResets = delta(a.snmp, b.snmp, "EstabResets");
        m.listenOverflows = delta(a.netstat, b.netstat, "ListenOverflows");
        m.listenDrops = delta(a.netstat, b.netstat, "ListenDrops");
        m.tcpInUse = b.sockstat.get("inuse");
        m.tcpTimeWait = b.sockstat.get("tw");
        m.tcpOrphan = b.sockstat.get("orphan");
        if (m.windowSec > 0 && b.nic.containsKey("rx_bytes") && a.nic.containsKey("rx_bytes")) {
            m.rxMbit = round((b.nic.get("rx_bytes") - a.nic.get("rx_bytes")) * 8 / 1e6 / m.windowSec);
            m.txMbit = round((b.nic.getOrDefault("tx_bytes", 0L) - a.nic.getOrDefault("tx_bytes", 0L)) * 8 / 1e6 / m.windowSec);
            m.nicErrors = delta(a.nic, b.nic, "rx_errors") + delta(a.nic, b.nic, "tx_errors");
            m.nicDrops = delta(a.nic, b.nic, "rx_dropped") + delta(a.nic, b.nic, "tx_dropped");
        }
    }

    private static long delta(Map<String, Long> a, Map<String, Long> b, String key) {
        return Math.max(0, b.getOrDefault(key, 0L) - a.getOrDefault(key, 0L));
    }

    private static double round(double v) {
        return Math.round(v * 10) / 10.0;
    }

    private static long num(String s) {
        try {
            return Long.parseLong(s.strip());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** «unlimited» и пустое значение - без ограничения. */
    private static long numOrMax(String s) {
        return s == null || s.isBlank() || s.equals("unlimited") ? Long.MAX_VALUE : num(s);
    }

    private static long hex(String s) {
        try {
            return Long.parseLong(s, 16);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static Double dbl(String s) {
        try {
            return s.isBlank() ? null : Double.parseDouble(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
