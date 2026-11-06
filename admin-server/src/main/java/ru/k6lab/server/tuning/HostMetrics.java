package ru.k6lab.server.tuning;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Результат профилирования хоста: статические параметры ядра и ресурсы плюс показатели за окно измерения
 * (разница двух снимков счётчиков). Хранится в БД как JSON; поля без данных остаются null.
 */
public final class HostMetrics {

    public record K6Proc(long pid, long soft, long hard, long fds) {
    }

    // ---- статика
    public int cpus;
    public long memTotalKb;
    public long memAvailKb;
    public long swapTotalKb;
    public long swapFreeKb;
    public String kernel;
    public String iface;
    public Long speedMbit;
    public String thp;
    public String governor;
    public Long conntrackCount;
    public Long conntrackMax;
    public Double psiCpu;
    public Double psiMem;
    public Map<String, String> sysctl = new LinkedHashMap<>();
    public List<K6Proc> k6 = new ArrayList<>();

    // ---- за окно измерения
    public double windowSec;
    public Double cpuBusyPct;
    public Double cpuUserPct;
    public Double cpuSystemPct;
    public Double cpuSoftirqPct;
    public Double cpuStealPct;
    public Double cpuIowaitPct;
    public Long softnetDropped;
    public Long softnetSqueezed;
    public Double tcpOutSegsPerSec;
    public Double tcpRetransPct;
    public Long tcpAttemptFails;
    public Long tcpEstabResets;
    public Long listenOverflows;
    public Long listenDrops;
    public Long tcpInUse;
    public Long tcpTimeWait;
    public Long tcpOrphan;
    public Double rxMbit;
    public Double txMbit;
    public Long nicErrors;
    public Long nicDrops;
}
