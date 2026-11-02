package main

import (
	"os"
	"strconv"
	"strings"
	"sync"
	"time"
)

// Снимок ресурсов хоста из /proc (Linux). На других ОС недоступные значения равны 0.
type SystemMetrics struct {
	mu        sync.Mutex
	home      string
	prevTotal int64
	prevIdle  int64
	prevRx    int64
	prevTx    int64
	prevAt    time.Time
	havePrev  bool
}

func NewSystemMetrics(home string) *SystemMetrics {
	return &SystemMetrics{home: home, prevTotal: -1}
}

func (s *SystemMetrics) Snapshot(k6 *string, agentVersion string, active []string) Heartbeat {
	s.mu.Lock()
	defer s.mu.Unlock()
	used, total := memory()
	rx, tx := s.netRates()
	l1, l5, l15 := loadAvg()
	if active == nil {
		active = []string{}
	}
	return Heartbeat{
		Type: "HEARTBEAT", AgentTime: time.Now().UnixMilli(), CPUPct: s.cpuPct(), RAMUsedBytes: used, RAMTotalBytes: total,
		NetRxBytesPerSec: rx, NetTxBytesPerSec: tx, Load1: l1, Load5: l5, Load15: l15, DiskFreeBytes: diskFree(s.home),
		OpenSockets: sockets(), K6Version: k6, AgentVersion: agentVersion, ActiveRuns: active,
	}
}

func readLines(path string) []string {
	b, err := os.ReadFile(path)
	if err != nil {
		return nil
	}
	return strings.Split(string(b), "\n")
}

func (s *SystemMetrics) cpuPct() float64 {
	lines := readLines("/proc/stat")
	if len(lines) == 0 {
		return 0
	}
	p := strings.Fields(lines[0])
	if len(p) < 5 {
		return 0
	}
	var total int64
	for i := 1; i < len(p); i++ {
		n, _ := strconv.ParseInt(p[i], 10, 64)
		total += n
	}
	idle, _ := strconv.ParseInt(p[4], 10, 64)
	if len(p) > 5 {
		iow, _ := strconv.ParseInt(p[5], 10, 64)
		idle += iow
	}
	pct := 0.0
	if s.prevTotal >= 0 && total > s.prevTotal {
		pct = 100.0 * (1.0 - float64(idle-s.prevIdle)/float64(total-s.prevTotal))
	}
	s.prevTotal, s.prevIdle = total, idle
	if pct < 0 {
		return 0
	}
	if pct > 100 {
		return 100
	}
	return pct
}

func memory() (used, total int64) {
	var avail int64
	for _, l := range readLines("/proc/meminfo") {
		f := strings.Fields(l)
		if len(f) < 2 {
			continue
		}
		n, _ := strconv.ParseInt(f[1], 10, 64)
		switch f[0] {
		case "MemTotal:":
			total = n * 1024
		case "MemAvailable:":
			avail = n * 1024
		}
	}
	used = total - avail
	if used < 0 {
		used = 0
	}
	return
}

func (s *SystemMetrics) netRates() (int64, int64) {
	var rx, tx int64
	for _, l := range readLines("/proc/net/dev") {
		c := strings.Index(l, ":")
		if c < 0 {
			continue
		}
		if strings.TrimSpace(l[:c]) == "lo" {
			continue
		}
		p := strings.Fields(l[c+1:])
		if len(p) < 9 {
			continue
		}
		a, _ := strconv.ParseInt(p[0], 10, 64)
		b, _ := strconv.ParseInt(p[8], 10, 64)
		rx += a
		tx += b
	}
	now := time.Now()
	var orx, otx int64
	if s.havePrev && now.After(s.prevAt) {
		sec := now.Sub(s.prevAt).Seconds()
		orx = max64(0, int64(float64(rx-s.prevRx)/sec))
		otx = max64(0, int64(float64(tx-s.prevTx)/sec))
	}
	s.prevRx, s.prevTx, s.prevAt, s.havePrev = rx, tx, now, true
	return orx, otx
}

func loadAvg() (float64, float64, float64) {
	l := readLines("/proc/loadavg")
	if len(l) == 0 {
		return 0, 0, 0
	}
	p := strings.Fields(l[0])
	if len(p) < 3 {
		return 0, 0, 0
	}
	a, _ := strconv.ParseFloat(p[0], 64)
	b, _ := strconv.ParseFloat(p[1], 64)
	c, _ := strconv.ParseFloat(p[2], 64)
	return a, b, c
}

func sockets() int {
	for _, l := range readLines("/proc/net/sockstat") {
		if strings.HasPrefix(l, "sockets:") {
			f := strings.Fields(l)
			if len(f) > 2 {
				n, _ := strconv.Atoi(f[2])
				return n
			}
		}
	}
	return 0
}

func max64(a, b int64) int64 {
	if a > b {
		return a
	}
	return b
}
