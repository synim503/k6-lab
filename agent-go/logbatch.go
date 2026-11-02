package main

import (
	"sort"
	"strings"
	"sync"
	"time"
)

// Masker маскирует значения секретов в строках лога k6.
type Masker struct{ secrets []string }

func NewMasker(values []string) *Masker {
	var s []string
	for _, v := range values {
		if len(v) >= 3 {
			s = append(s, v)
		}
	}
	sort.Slice(s, func(i, j int) bool { return len(s[i]) > len(s[j]) })
	return &Masker{secrets: s}
}

func (m *Masker) Mask(line string) string {
	for _, s := range m.secrets {
		line = strings.ReplaceAll(line, s, "******")
	}
	return line
}

// LogBatcher: батчи stdout/stderr k6, отправка раз в 500 мс, лимит строк в секунду - остальное отбрасывается, а число
// отброшенных строк уходит вместе с батчем. Секреты маскируются до отправки.
type LogBatcher struct {
	runID  string
	limit  int
	masker *Masker
	emit   func(factory func(seq int64) any)

	mu           sync.Mutex
	buf          []LogLine
	dropped      int64
	totalDropped int64
	windowStart  time.Time
	used         int
	stop         chan struct{}
	done         chan struct{}
	closeOnce    sync.Once
}

func NewLogBatcher(runID string, linesPerSecond int, m *Masker, emit func(factory func(seq int64) any)) *LogBatcher {
	if linesPerSecond < 1 {
		linesPerSecond = 1
	}
	b := &LogBatcher{runID: runID, limit: linesPerSecond, masker: m, emit: emit, stop: make(chan struct{}), done: make(chan struct{})}
	go func() {
		defer close(b.done)
		t := time.NewTicker(500 * time.Millisecond)
		defer t.Stop()
		for {
			select {
			case <-t.C:
				b.Flush()
			case <-b.stop:
				return
			}
		}
	}()
	return b
}

func (b *LogBatcher) Add(stream, line string) {
	now := time.Now()
	b.mu.Lock()
	defer b.mu.Unlock()
	if now.Sub(b.windowStart) >= time.Second {
		b.windowStart = now
		b.used = 0
	}
	if b.used >= b.limit {
		b.dropped++
		b.totalDropped++
		return
	}
	b.used++
	masked := b.masker.Mask(line)
	if r := []rune(masked); len(r) > 8000 {
		masked = string(r[:8000]) + "…"
	}
	b.buf = append(b.buf, LogLine{Ts: now.UnixMilli(), Stream: stream, Line: masked})
}

func (b *LogBatcher) TotalDropped() int64 {
	b.mu.Lock()
	defer b.mu.Unlock()
	return b.totalDropped
}

func (b *LogBatcher) Flush() {
	b.mu.Lock()
	if len(b.buf) == 0 && b.dropped == 0 {
		b.mu.Unlock()
		return
	}
	batch, d := b.buf, b.dropped
	b.buf, b.dropped = nil, 0
	b.mu.Unlock()
	b.emit(func(seq int64) any {
		return LogBatch{Type: "LOG_BATCH", Seq: seq, Ts: time.Now().UnixMilli(), RunID: b.runID, Lines: batch, Dropped: d}
	})
}

func (b *LogBatcher) Close() {
	b.closeOnce.Do(func() {
		close(b.stop)
		<-b.done
		b.Flush()
	})
}
