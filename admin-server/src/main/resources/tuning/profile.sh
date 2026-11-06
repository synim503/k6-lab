#!/bin/sh
# Профилирование хоста-генератора под нагрузкой: только чтение (ничего не меняет). Выводит разделы @@meta, @@sysctl, @@limits
# и два снимка счётчиков @@s1 / @@s2 с паузой между ними; разницу считает админка.
# Подстановки: @SECS@ - пауза между снимками, @KEYS@ - список sysctl-параметров.
SECS=@SECS@
IF=$(ip route show default 2>/dev/null | awk '{for(i=1;i<=NF;i++) if($i=="dev"){print $(i+1); exit}}')

echo "@@meta"
echo "nproc $(nproc 2>/dev/null || grep -c '^processor' /proc/cpuinfo)"
awk '/^MemTotal:/{print "mem_total_kb", $2} /^MemAvailable:/{print "mem_avail_kb", $2} /^SwapTotal:/{print "swap_total_kb", $2} /^SwapFree:/{print "swap_free_kb", $2}' /proc/meminfo 2>/dev/null
echo "kernel $(uname -r)"
echo "iface $IF"
if [ -n "$IF" ]; then echo "speed_mbit $(cat /sys/class/net/$IF/speed 2>/dev/null)"; fi
echo "thp $(sed -n 's/.*\[\(.*\)\].*/\1/p' /sys/kernel/mm/transparent_hugepage/enabled 2>/dev/null)"
echo "governor $(cat /sys/devices/system/cpu/cpu0/cpufreq/scaling_governor 2>/dev/null)"
echo "conntrack_count $(cat /proc/sys/net/netfilter/nf_conntrack_count 2>/dev/null)"
echo "conntrack_max $(cat /proc/sys/net/netfilter/nf_conntrack_max 2>/dev/null)"
echo "psi_cpu $(sed -n 's/^some .*avg10=\([0-9.]*\).*/\1/p' /proc/pressure/cpu 2>/dev/null)"
echo "psi_mem $(sed -n 's/^some .*avg10=\([0-9.]*\).*/\1/p' /proc/pressure/memory 2>/dev/null)"

echo "@@sysctl"
for k in @KEYS@; do
  v=$(sysctl -n "$k" 2>/dev/null | tr '\t' ' ')
  if [ -n "$v" ]; then echo "$k=$v"; fi
done

echo "@@limits"
for p in $(pgrep -x k6 2>/dev/null); do
  soft=$(awk '/Max open files/{print $4}' /proc/$p/limits 2>/dev/null)
  hard=$(awk '/Max open files/{print $5}' /proc/$p/limits 2>/dev/null)
  fds=$(ls /proc/$p/fd 2>/dev/null | wc -l)
  echo "k6 $p $soft $hard $fds"
done

snap() {
  echo "@@$1"
  echo "time $(cut -d' ' -f1 /proc/uptime)"
  head -1 /proc/stat | sed 's/^/stat /'
  awk '{print "softnet", $1, $2, $3}' /proc/net/softnet_stat 2>/dev/null
  grep '^Tcp:' /proc/net/snmp 2>/dev/null | sed 's/^/snmp /'
  grep '^TcpExt:' /proc/net/netstat 2>/dev/null | sed 's/^/netstat /'
  grep '^TCP:' /proc/net/sockstat 2>/dev/null | sed 's/^/sockstat /'
  if [ -n "$IF" ]; then
    for f in rx_bytes tx_bytes rx_errors tx_errors rx_dropped tx_dropped; do
      echo "nic $f $(cat /sys/class/net/$IF/statistics/$f 2>/dev/null)"
    done
  fi
}
snap s1
sleep "$SECS"
snap s2
