package ru.k6lab.server.tuning;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TuningAnalyzerTest {

    /** Вывод скрипта: два снимка с разницей, чтобы проверить расчёт показателей за окно. */
    private static final String OUTPUT = """
            @@meta
            nproc 8
            mem_total_kb 16000000
            mem_avail_kb 1200000
            swap_total_kb 2000000
            swap_free_kb 1500000
            kernel 5.15.0
            iface eth0
            speed_mbit 1000
            thp always
            governor powersave
            conntrack_count 63000
            conntrack_max 65536
            @@sysctl
            net.core.somaxconn=128
            net.core.netdev_max_backlog=1000
            net.ipv4.ip_local_port_range=32768 60999
            net.ipv4.tcp_tw_reuse=0
            net.ipv4.tcp_fin_timeout=60
            net.core.rmem_max=212992
            net.core.wmem_max=212992
            net.ipv4.tcp_rmem=4096 131072 6291456
            fs.file-max=100000
            vm.swappiness=60
            @@limits
            k6 4242 1024 524288 1000
            @@s1
            time 1000.00
            stat cpu  1000 0 500 8000 100 0 200 100 0 0
            softnet 00000010 00000000 00000000
            softnet 00000020 00000000 00000001
            snmp Tcp: RtoAlgorithm OutSegs RetransSegs AttemptFails EstabResets
            snmp Tcp: 1 1000 10 0 0
            netstat TcpExt: ListenOverflows ListenDrops
            netstat TcpExt: 0 0
            sockstat TCP: inuse 10 orphan 0 tw 100 alloc 12 mem 1
            nic rx_bytes 1000000
            nic tx_bytes 2000000
            nic rx_errors 0
            nic tx_errors 0
            nic rx_dropped 0
            nic tx_dropped 0
            @@s2
            time 1010.00
            stat cpu  1900 0 900 8100 100 0 600 100 0 0
            softnet 00000010 00000000 00000000
            softnet 00000020 00000005 00000003
            snmp Tcp: RtoAlgorithm OutSegs RetransSegs AttemptFails EstabResets
            snmp Tcp: 1 2000 110 0 0
            netstat TcpExt: ListenOverflows ListenDrops
            netstat TcpExt: 7 2
            sockstat TCP: inuse 20000 orphan 0 tw 9000 alloc 12 mem 1
            nic rx_bytes 126000000
            nic tx_bytes 127000000
            nic rx_errors 0
            nic tx_errors 3
            nic rx_dropped 0
            nic tx_dropped 0
            """;

    @Test
    void parserComputesWindowMetricsFromTwoSnapshots() {
        HostMetrics m = HostMetricsParser.parse(OUTPUT, 10);
        assertThat(m.windowSec).isEqualTo(10.0);
        assertThat(m.cpus).isEqualTo(8);
        // CPU: за окно user 900, system 400, idle 100, softirq 400, steal 0 => всего 1800, занято 1700 (94,4%)
        assertThat(m.cpuBusyPct).isEqualTo(94.4);
        assertThat(m.cpuSoftirqPct).isEqualTo(22.2);
        assertThat(m.softnetDropped).isEqualTo(5);
        assertThat(m.softnetSqueezed).isEqualTo(2);
        assertThat(m.tcpRetransPct).isEqualTo(10.0);   // 100 из 1000 сегментов
        assertThat(m.listenOverflows).isEqualTo(7);
        assertThat(m.tcpTimeWait).isEqualTo(9000);
        assertThat(m.rxMbit).isEqualTo(100.0);         // 125 МБ за 10 с
        assertThat(m.nicErrors).isEqualTo(3);
        assertThat(m.sysctl).containsEntry("net.ipv4.ip_local_port_range", "32768 60999");
        assertThat(m.k6).hasSize(1);
        assertThat(m.k6.get(0).soft()).isEqualTo(1024);
    }

    @Test
    void analyzerFindsBottlenecksAndRecommendsOnlyWhitelistedSettings() {
        TuningAnalyzer.Analysis a = TuningAnalyzer.analyze(HostMetricsParser.parse(OUTPUT, 10));
        List<String> titles = a.findings().stream().map(TuningAnalyzer.Finding::title).toList();
        assertThat(titles).anyMatch(t -> t.contains("Процессор генератора почти исчерпан"));
        assertThat(titles).anyMatch(t -> t.contains("Мал лимит открытых файлов у k6"));
        assertThat(titles).anyMatch(t -> t.contains("Заканчиваются локальные порты"));
        assertThat(titles).anyMatch(t -> t.contains("отбрасывает пакеты в очереди приёма"));
        assertThat(titles).anyMatch(t -> t.contains("повторных передач TCP"));
        assertThat(titles).anyMatch(t -> t.contains("conntrack") || t.contains("отслеживания соединений"));
        // рекомендации по настройкам
        assertThat(a.recommended()).containsEntry("net.ipv4.ip_local_port_range", "1024 65535")
                .containsEntry("net.ipv4.tcp_tw_reuse", "1")
                .containsEntry("net.core.netdev_max_backlog", "5000")
                .containsEntry("net.netfilter.nf_conntrack_max", "131072")
                .containsEntry("vm.swappiness", "10")
                .containsEntry("fs.file-max", "1048576");
        assertThat(TuningAnalyzer.allKeys()).containsAll(a.recommended().keySet());
        // THP и режим частоты теперь тоже предлагаются к применению (запись в /sys)
        assertThat(a.recommended()).containsEntry(TuningAnalyzer.THP, "madvise").containsEntry(TuningAnalyzer.GOVERNOR, "performance")
                .containsEntry("net.core.netdev_budget", "600");
        // лимит файлов k6 - не sysctl: советом, без применяемого значения
        assertThat(a.findings().stream().filter(f -> f.title().contains("лимит открытых файлов у k6")).findFirst().orElseThrow().hasSetting()).isFalse();
    }

    @Test
    void healthyIdleHostGetsOnlyAHintToProfileUnderLoad() {
        HostMetrics m = new HostMetrics();
        m.cpuBusyPct = 1.0;
        m.rxMbit = 0.1;
        m.txMbit = 0.1;
        TuningAnalyzer.Analysis a = TuningAnalyzer.analyze(m);
        assertThat(a.findings()).hasSize(1);
        assertThat(a.findings().get(0).title()).contains("нагрузки почти не было");
        assertThat(a.recommended()).isEmpty();
    }

    @Test
    void settingsAlreadyAtRecommendedValueAreNotSuggestedAgain() {
        HostMetrics m = new HostMetrics();
        m.cpuBusyPct = 50.0;
        m.rxMbit = 400.0;
        m.txMbit = 400.0;
        m.speedMbit = 10_000L;
        m.sysctl.put("net.ipv4.ip_local_port_range", "1024 65535");
        m.sysctl.put("net.core.rmem_max", "16777216");
        m.sysctl.put("net.core.wmem_max", "16777216");
        m.sysctl.put("net.ipv4.tcp_rmem", "4096 87380 16777216");
        m.sysctl.put("net.ipv4.tcp_wmem", "4096 65536 16777216");
        assertThat(TuningAnalyzer.analyze(m).recommended()).isEmpty();
    }

    @Test
    void swapAndSqueezeAreFixableEvenWhenTheCurrentValueWasNotRead() {
        HostMetrics m = new HostMetrics();
        m.cpuBusyPct = 50.0;
        m.rxMbit = 10.0;
        m.txMbit = 10.0;
        m.memTotalKb = 8_000_000;
        m.memAvailKb = 4_000_000;
        m.swapTotalKb = 1_000_000;
        m.swapFreeKb = 500_000;
        m.softnetSqueezed = 5L;
        // параметры ядра не прочитаны (например, sysctl не нашёлся в PATH): рекомендация всё равно предлагается
        TuningAnalyzer.Analysis a = TuningAnalyzer.analyze(m);
        assertThat(a.recommended()).containsEntry("vm.swappiness", "10").containsEntry("net.core.netdev_budget", "600");
        // swappiness уже низкий: рекомендации нет, а в находке сказано почему
        m.sysctl.put("vm.swappiness", "10");
        TuningAnalyzer.Analysis b = TuningAnalyzer.analyze(m);
        assertThat(b.recommended()).doesNotContainKey("vm.swappiness");
        assertThat(b.findings().stream().filter(f -> f.title().contains("swap")).findFirst().orElseThrow().detail()).contains("уже равен 10");
    }

    @Test
    void settingValidationAcceptsOnlyWhitelistedKeysAndSafeValues() {
        assertThat(TuningAnalyzer.valid("net.core.somaxconn", "4096")).isTrue();
        assertThat(TuningAnalyzer.valid("net.ipv4.ip_local_port_range", "1024  65535")).isTrue();
        assertThat(TuningAnalyzer.valid("net.core.somaxconn", "4096; rm -rf /")).isFalse();
        assertThat(TuningAnalyzer.valid("kernel.panic", "1")).isFalse();
        assertThat(TuningAnalyzer.valid(TuningAnalyzer.THP, "madvise")).isTrue();
        assertThat(TuningAnalyzer.valid(TuningAnalyzer.THP, "1")).isFalse();
        assertThat(TuningAnalyzer.valid(TuningAnalyzer.GOVERNOR, "performance")).isTrue();
        assertThat(TuningAnalyzer.valid(TuningAnalyzer.GOVERNOR, "performance; x")).isFalse();
        TuningAnalyzer.allKeys().forEach(k -> assertThat(TuningAnalyzer.describe(k)).as(k).isNotBlank());
    }
}