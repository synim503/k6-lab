package ru.k6lab.server.tuning;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Правила поиска узких мест генератора по {@link HostMetrics}. Каждая находка - что видно, почему мешает нагрузке и, если это
 * настройка sysctl, какое значение рекомендуется. Применять можно только параметры из {@link #KEYS}: произвольных команд нет.
 */
public final class TuningAnalyzer {

    public enum Severity { CRIT, WARN, INFO }

    /** area: cpu, memory, network, tcp, ports, limits, conntrack, system. key/current/recommended - только для настроек sysctl. */
    public record Finding(Severity severity, String area, String title, String detail, String key, String current, String recommended) {
        public boolean hasSetting() {
            return key != null && recommended != null;
        }
    }

    public record Analysis(List<Finding> findings, Map<String, String> recommended) {
    }

    /** Параметры, которые админка читает и (по кнопке) меняет. */
    public static final List<String> KEYS = List.of(
            "net.core.somaxconn", "net.core.netdev_max_backlog", "net.ipv4.tcp_max_syn_backlog", "net.ipv4.ip_local_port_range",
            "net.ipv4.tcp_tw_reuse", "net.ipv4.tcp_fin_timeout", "net.core.rmem_max", "net.core.wmem_max", "net.ipv4.tcp_rmem",
            "net.ipv4.tcp_wmem", "net.netfilter.nf_conntrack_max", "fs.file-max", "fs.nr_open", "vm.swappiness");

    private static final long MIB16 = 16L * 1024 * 1024;

    private TuningAnalyzer() {
    }

    public static Analysis analyze(HostMetrics m) {
        List<Finding> out = new ArrayList<>();
        cpu(m, out);
        memory(m, out);
        network(m, out);
        tcp(m, out);
        ports(m, out);
        limits(m, out);
        conntrack(m, out);
        buffers(m, out);
        system(m, out);
        if (idleHost(m)) {
            out.add(0, new Finding(Severity.INFO, "system", "Во время профилирования нагрузки почти не было",
                    "CPU занят " + f(m.cpuBusyPct) + "%, сеть около " + f((m.rxMbit == null ? 0 : m.rxMbit) + (m.txMbit == null ? 0 : m.txMbit))
                            + " Мбит/с. Узкие места видны только под нагрузкой: запускайте профилирование, пока идёт тест.", null, null, null));
        } else if (out.stream().noneMatch(x -> x.severity() != Severity.INFO)) {
            out.add(0, new Finding(Severity.INFO, "system", "Узких мест на генераторе не найдено",
                    "По измеренным показателям ограничений, которые мешали бы нагрузке, не видно.", null, null, null));
        }
        Map<String, String> rec = new LinkedHashMap<>();
        for (Finding x : out) {
            if (x.hasSetting()) {
                rec.put(x.key(), x.recommended());
            }
        }
        return new Analysis(List.copyOf(out), rec);
    }

    private static boolean idleHost(HostMetrics m) {
        double net = (m.rxMbit == null ? 0 : m.rxMbit) + (m.txMbit == null ? 0 : m.txMbit);
        return m.cpuBusyPct != null && m.cpuBusyPct < 5 && net < 5;
    }

    // ------------------------------------------------------------------ правила

    private static void cpu(HostMetrics m, List<Finding> out) {
        if (m.cpuBusyPct != null) {
            if (m.cpuBusyPct >= 90) {
                out.add(new Finding(Severity.CRIT, "cpu", "Процессор генератора почти исчерпан (" + f(m.cpuBusyPct) + "%)",
                        "Нагрузка упирается в генератор, а не в тестируемую систему: добавьте генераторы, уменьшите число VU, размер тел запросов или объём логирования в скрипте.",
                        null, null, null));
            } else if (m.cpuBusyPct >= 75) {
                out.add(new Finding(Severity.WARN, "cpu", "Высокая загрузка процессора (" + f(m.cpuBusyPct) + "%)",
                        "Запас по CPU небольшой: при росте нагрузки генератор станет узким местом и начнёт искажать время ответа.", null, null, null));
            }
        }
        if (m.cpuStealPct != null && m.cpuStealPct >= 5) {
            out.add(new Finding(Severity.WARN, "cpu", "Гипервизор забирает процессор (steal " + f(m.cpuStealPct) + "%)",
                    "Виртуальная машина не получает заявленные ядра: время ответа будет плавать. Переместите генератор на менее загруженный хост виртуализации.",
                    null, null, null));
        }
        if (m.cpuSoftirqPct != null && m.cpuSoftirqPct >= 20) {
            out.add(new Finding(Severity.WARN, "network", "Много времени уходит на сетевые прерывания (softirq " + f(m.cpuSoftirqPct) + "%)",
                    "Обработка пакетов упирается в одно-два ядра. Включите несколько очередей сетевой карты и распределение прерываний (irqbalance, RPS/RSS).",
                    null, null, null));
        }
        if (m.cpuIowaitPct != null && m.cpuIowaitPct >= 10) {
            out.add(new Finding(Severity.WARN, "system", "Ожидание диска (iowait " + f(m.cpuIowaitPct) + "%)",
                    "Генератор ждёт диск: проверьте запись логов и временных файлов во время теста.", null, null, null));
        }
    }

    private static void memory(HostMetrics m, List<Finding> out) {
        if (m.memTotalKb > 0) {
            double free = m.memAvailKb * 100.0 / m.memTotalKb;
            if (free < 10) {
                out.add(new Finding(Severity.CRIT, "memory", "Заканчивается оперативная память (свободно " + f(free) + "%)",
                        "Каждый VU k6 держит свою копию данных скрипта. Уменьшите maxVUs и размер данных в памяти скрипта либо добавьте память.", null, null, null));
            } else if (free < 20) {
                out.add(new Finding(Severity.WARN, "memory", "Мало свободной памяти (" + f(free) + "%)",
                        "При росте числа VU память генератора закончится раньше, чем нагрузка достигнет цели.", null, null, null));
            }
        }
        if (m.swapTotalKb > 0 && m.swapTotalKb - m.swapFreeKb > 0) {
            String cur = m.sysctl.get("vm.swappiness");
            out.add(setting(Severity.WARN, "memory", "Генератор использует swap",
                    "Своп на нагрузочном хосте резко увеличивает задержки. Снижение swappiness уменьшает вытеснение в своп.", "vm.swappiness", cur, 10,
                    cur != null && parseLong(cur) > 10));
        }
    }

    private static void network(HostMetrics m, List<Finding> out) {
        if (m.speedMbit != null && m.speedMbit > 0 && m.rxMbit != null && m.txMbit != null) {
            double util = Math.max(m.rxMbit, m.txMbit) * 100.0 / m.speedMbit;
            if (util >= 90) {
                out.add(new Finding(Severity.CRIT, "network", "Сетевая карта насыщена (" + f(util) + "% от " + m.speedMbit + " Мбит/с)",
                        "Канал генератора исчерпан: растёт время ответа и очередь, а k6 добавляет VU. Распределите нагрузку по большему числу генераторов или уменьшите размер запросов.",
                        null, null, null));
            } else if (util >= 70) {
                out.add(new Finding(Severity.WARN, "network", "Высокая загрузка сетевой карты (" + f(util) + "% от " + m.speedMbit + " Мбит/с)",
                        "До насыщения канала остаётся небольшой запас.", null, null, null));
            }
        }
        if (m.nicErrors != null && m.nicErrors > 0 || m.nicDrops != null && m.nicDrops > 0) {
            out.add(new Finding(Severity.WARN, "network", "Сетевой интерфейс теряет пакеты",
                    "За время измерения ошибок: " + (m.nicErrors == null ? 0 : m.nicErrors) + ", отброшено: " + (m.nicDrops == null ? 0 : m.nicDrops)
                            + ". Проверьте размер кольцевых буферов карты (ethtool -g), драйвер и качество канала.", null, null, null));
        }
        if (m.softnetDropped != null && m.softnetDropped > 0) {
            String cur = m.sysctl.get("net.core.netdev_max_backlog");
            long now = cur == null ? 1000 : parseLong(cur);
            out.add(setting(Severity.CRIT, "network", "Ядро отбрасывает пакеты в очереди приёма (" + m.softnetDropped + " за окно)",
                    "Очередь сетевых пакетов переполняется раньше, чем их успевают обработать.", "net.core.netdev_max_backlog", cur,
                    Math.min(65536, Math.max(now * 2, 5000)), now < 65536));
        }
        if (m.softnetSqueezed != null && m.softnetSqueezed > 0) {
            out.add(new Finding(Severity.WARN, "network", "Обработка пакетов не успевает за бюджет (time_squeeze " + m.softnetSqueezed + ")",
                    "Ядро прерывает цикл обработки пакетов по лимиту времени. Увеличьте net.core.netdev_budget вручную и проверьте распределение прерываний.",
                    null, null, null));
        }
    }

    private static void tcp(HostMetrics m, List<Finding> out) {
        long over = (m.listenOverflows == null ? 0 : m.listenOverflows) + (m.listenDrops == null ? 0 : m.listenDrops);
        if (over > 0) {
            String cur = m.sysctl.get("net.core.somaxconn");
            out.add(setting(Severity.WARN, "tcp", "Переполняется очередь входящих соединений (" + over + " за окно)",
                    "Для нагрузочного генератора это редкость: возможно, на хосте работает ещё и принимающий сервис.", "net.core.somaxconn", cur, 4096,
                    cur != null && parseLong(cur) < 4096));
            String syn = m.sysctl.get("net.ipv4.tcp_max_syn_backlog");
            if (syn != null && parseLong(syn) < 4096) {
                out.add(setting(Severity.WARN, "tcp", "Мала очередь полуоткрытых соединений", "Поднимается вместе с somaxconn.",
                        "net.ipv4.tcp_max_syn_backlog", syn, 8192, true));
            }
        }
        if (m.tcpRetransPct != null) {
            if (m.tcpRetransPct >= 5) {
                out.add(new Finding(Severity.CRIT, "tcp", "Много повторных передач TCP (" + f(m.tcpRetransPct) + "%)",
                        "Пакеты теряются на пути до тестируемой системы или на ней самой: время ответа искажено сетью, а не приложением.", null, null, null));
            } else if (m.tcpRetransPct >= 2) {
                out.add(new Finding(Severity.WARN, "tcp", "Повышенные повторные передачи TCP (" + f(m.tcpRetransPct) + "%)",
                        "Проверьте канал и нагрузку на принимающей стороне.", null, null, null));
            }
        }
    }

    private static void ports(HostMetrics m, List<Finding> out) {
        String range = m.sysctl.get("net.ipv4.ip_local_port_range");
        long lo = 32768, hi = 60999;
        if (range != null) {
            String[] r = range.split("\\s+");
            if (r.length == 2) {
                lo = parseLong(r[0]);
                hi = parseLong(r[1]);
            }
        }
        long size = Math.max(1, hi - lo + 1);
        long used = (m.tcpInUse == null ? 0 : m.tcpInUse) + (m.tcpTimeWait == null ? 0 : m.tcpTimeWait);
        double share = used * 100.0 / size;
        boolean narrow = range != null && size < 40000;
        if (share >= 50) {
            out.add(new Finding(Severity.CRIT, "ports", "Заканчиваются локальные порты (занято " + used + " из " + size + ")",
                    "Новые соединения не получат порт: появятся ошибки подключения. Расширьте диапазон портов, включите повторное использование TIME_WAIT.",
                    "net.ipv4.ip_local_port_range", range, "1024 65535"));
        } else if (narrow) {
            out.add(new Finding(Severity.WARN, "ports", "Узкий диапазон локальных портов (" + size + ")",
                    "Один хост может открыть к одному адресу не больше соединений, чем портов в диапазоне.", "net.ipv4.ip_local_port_range", range, "1024 65535"));
        }
        String reuse = m.sysctl.get("net.ipv4.tcp_tw_reuse");
        if ((share >= 30 || (m.tcpTimeWait != null && m.tcpTimeWait >= 5000)) && reuse != null && !reuse.equals("1")) {
            out.add(new Finding(Severity.WARN, "ports", "Много соединений в TIME_WAIT (" + (m.tcpTimeWait == null ? 0 : m.tcpTimeWait) + ")",
                    "Повторное использование TIME_WAIT для исходящих соединений освобождает порты быстрее.", "net.ipv4.tcp_tw_reuse", reuse, "1"));
            String fin = m.sysctl.get("net.ipv4.tcp_fin_timeout");
            if (fin != null && parseLong(fin) > 30) {
                out.add(new Finding(Severity.INFO, "ports", "Долгий FIN-таймаут", "Сокращает время, которое порт занят после закрытия.",
                        "net.ipv4.tcp_fin_timeout", fin, "15"));
            }
        }
    }

    private static void limits(HostMetrics m, List<Finding> out) {
        for (HostMetrics.K6Proc p : m.k6) {
            if (p.soft() < 65535) {
                out.add(new Finding(Severity.CRIT, "limits", "Мал лимит открытых файлов у k6 (pid " + p.pid() + "): " + p.soft(),
                        "Каждое соединение занимает файловый дескриптор: при нескольких сотнях VU k6 упрётся в лимит и начнёт получать ошибки. "
                                + "Задайте LimitNOFILE=1048576 для сервиса агента (systemd drop-in) и перезапустите агент.", null, null, null));
            } else if (p.soft() != Long.MAX_VALUE && p.fds() * 100.0 / p.soft() >= 70) {
                out.add(new Finding(Severity.WARN, "limits", "k6 (pid " + p.pid() + ") использует " + p.fds() + " из " + p.soft() + " файловых дескрипторов",
                        "Запас лимита заканчивается.", null, null, null));
            }
        }
        String fm = m.sysctl.get("fs.file-max");
        if (fm != null && parseLong(fm) < 500_000) {
            out.add(setting(Severity.WARN, "limits", "Мал системный лимит открытых файлов (" + fm + ")", "Ограничивает число соединений всех процессов хоста.",
                    "fs.file-max", fm, 1_048_576, true));
        }
        String no = m.sysctl.get("fs.nr_open");
        if (no != null && parseLong(no) < 1_048_576 && !m.k6.isEmpty()) {
            out.add(setting(Severity.INFO, "limits", "Потолок fs.nr_open меньше 1 048 576", "Выше него процессу нельзя поднять лимит открытых файлов.",
                    "fs.nr_open", no, 1_048_576, true));
        }
    }

    private static void conntrack(HostMetrics m, List<Finding> out) {
        if (m.conntrackMax != null && m.conntrackMax > 0 && m.conntrackCount != null) {
            double share = m.conntrackCount * 100.0 / m.conntrackMax;
            if (share >= 70) {
                out.add(setting(share >= 90 ? Severity.CRIT : Severity.WARN, "conntrack",
                        "Таблица отслеживания соединений заполнена на " + f(share) + "%",
                        "При переполнении ядро отбрасывает новые соединения (сообщение «nf_conntrack: table full»).",
                        "net.netfilter.nf_conntrack_max", String.valueOf(m.conntrackMax), m.conntrackMax * 2, true));
            }
        }
    }

    private static void buffers(HostMetrics m, List<Finding> out) {
        double net = (m.rxMbit == null ? 0 : m.rxMbit) + (m.txMbit == null ? 0 : m.txMbit);
        boolean heavy = net >= 100 || (m.speedMbit != null && m.speedMbit >= 10_000);
        if (!heavy) {
            return;
        }
        for (String k : List.of("net.core.rmem_max", "net.core.wmem_max")) {
            String cur = m.sysctl.get(k);
            if (cur != null && parseLong(cur) < MIB16) {
                out.add(setting(Severity.INFO, "network", "Малый максимум сетевого буфера (" + k + ")",
                        "На быстром канале маленькие буферы ограничивают скорость одного соединения.", k, cur, MIB16, true));
            }
        }
        for (String k : List.of("net.ipv4.tcp_rmem", "net.ipv4.tcp_wmem")) {
            String cur = m.sysctl.get(k);
            String[] p = cur == null ? new String[0] : cur.split("\\s+");
            if (p.length == 3 && parseLong(p[2]) < MIB16) {
                out.add(new Finding(Severity.INFO, "network", "Малый максимум автонастройки буфера TCP (" + k + ")",
                        "Автонастройка не вырастает до размера, нужного быстрому каналу.", k, cur, p[0] + " " + p[1] + " " + MIB16));
            }
        }
    }

    private static void system(HostMetrics m, List<Finding> out) {
        if ("always".equals(m.thp)) {
            out.add(new Finding(Severity.INFO, "system", "Включены прозрачные большие страницы (THP=always)",
                    "Могут давать задержки при росте памяти k6. Рекомендуется madvise (echo madvise > /sys/kernel/mm/transparent_hugepage/enabled).", null, null, null));
        }
        if (m.governor != null && !m.governor.equals("performance")) {
            out.add(new Finding(Severity.INFO, "system", "Режим частоты процессора: " + m.governor,
                    "Для стабильных измерений лучше performance (cpupower frequency-set -g performance).", null, null, null));
        }
    }

    // ------------------------------------------------------------------ помощники

    /** Рекомендация числом: добавляется, только если действительно меняет значение (needsChange). */
    private static Finding setting(Severity sev, String area, String title, String detail, String key, String current, long recommended, boolean needsChange) {
        if (!needsChange) {
            return new Finding(sev, area, title, detail, null, null, null);
        }
        return new Finding(sev, area, title, detail, key, current, String.valueOf(recommended));
    }

    private static long parseLong(String s) {
        try {
            return Long.parseLong(s.strip());
        } catch (NumberFormatException | NullPointerException e) {
            return 0;
        }
    }

    private static String f(Double v) {
        return v == null ? "—" : String.format(Locale.ROOT, "%.1f", v);
    }

    private static String f(double v) {
        return String.format(Locale.ROOT, "%.1f", v);
    }
}
