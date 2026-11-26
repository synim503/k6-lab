package main

import (
	"bufio"
	"encoding/json"
	"fmt"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"sync"
)

// Journal - дисковый журнал событий (JSON на строку): события удаляются после ACK сервера. seq монотонный и переживает
// рестарт. При переполнении сначала вытесняются LOG_BATCH, затем K6_STATUS; остальные события не вытесняются никогда.
type Journal struct {
	mu             sync.Mutex
	file           string
	meta           string
	maxBytes       int64
	lastSeq        int64
	acked          int64
	lastCompactAck int64
}

type journalLine struct {
	raw []byte
	seq int64
	typ string
}

func NewJournal(dir string, maxBytes int64) (*Journal, error) {
	if err := os.MkdirAll(dir, 0o755); err != nil {
		return nil, err
	}
	j := &Journal{file: filepath.Join(dir, "journal.jsonl"), meta: filepath.Join(dir, "journal.meta"), maxBytes: maxBytes}
	j.loadMeta()
	return j, nil
}

func (j *Journal) loadMeta() {
	if b, err := os.ReadFile(j.meta); err == nil {
		parts := strings.Split(strings.TrimSpace(string(b)), ",")
		if len(parts) > 0 {
			j.lastSeq, _ = strconv.ParseInt(parts[0], 10, 64)
		}
		if len(parts) > 1 {
			j.acked, _ = strconv.ParseInt(parts[1], 10, 64)
		}
	}
	for _, l := range j.readAll() { // seq не должен откатываться, даже если meta отстала от файла
		if l.seq > j.lastSeq {
			j.lastSeq = l.seq
		}
	}
}

func writeFileAtomic(path string, data []byte, perm os.FileMode) error {
	tmp := path + ".tmp"
	f, err := os.OpenFile(tmp, os.O_CREATE|os.O_WRONLY|os.O_TRUNC, perm)
	if err != nil {
		return err
	}
	if _, err := f.Write(data); err != nil {
		f.Close()
		_ = os.Remove(tmp)
		return err
	}
	if err := f.Sync(); err != nil {
		f.Close()
		_ = os.Remove(tmp)
		return err
	}
	if err := f.Close(); err != nil {
		_ = os.Remove(tmp)
		return err
	}
	if err := os.Rename(tmp, path); err != nil {
		_ = os.Remove(tmp)
		return err
	}
	return nil
}

func (j *Journal) saveMeta() {
	if err := writeFileAtomic(j.meta, []byte(fmt.Sprintf("%d,%d", j.lastSeq, j.acked)), 0o644); err != nil {
		logf("Не удалось записать meta журнала: %v", err)
	}
}

// Append создаёт событие со следующим seq, пишет его на диск и возвращает JSON.
func (j *Journal) Append(factory func(seq int64) any) []byte {
	j.mu.Lock()
	defer j.mu.Unlock()
	j.lastSeq++
	raw, err := json.Marshal(factory(j.lastSeq))
	if err != nil {
		j.lastSeq--
		logf("Не удалось сериализовать событие: %v", err)
		return nil
	}
	f, err := os.OpenFile(j.file, os.O_CREATE|os.O_APPEND|os.O_WRONLY, 0o644)
	if err != nil {
		logf("Не удалось записать событие в журнал: %v", err)
		return raw
	}
	line := append(append([]byte{}, raw...), '\n')
	if _, werr := f.Write(line); werr != nil {
		logf("Ошибка записи события в журнал (возможно, переполнен диск): %v", werr)
		f.Close()
		return raw
	}
	st, _ := f.Stat()
	f.Close()
	j.saveMeta()
	if st != nil && st.Size() > j.maxBytes {
		j.compact()
	}
	return raw
}

// Unacked - неподтверждённые события по возрастанию seq.
func (j *Journal) Unacked() [][]byte {
	j.mu.Lock()
	defer j.mu.Unlock()
	var out [][]byte
	for _, l := range j.readAll() {
		if l.seq > j.acked {
			out = append(out, l.raw)
		}
	}
	return out
}

func (j *Journal) Ack(upTo int64) {
	j.mu.Lock()
	defer j.mu.Unlock()
	if upTo <= j.acked {
		return
	}
	if upTo > j.lastSeq {
		upTo = j.lastSeq
	}
	j.acked = upTo
	j.saveMeta()
	if j.acked >= j.lastSeq {
		_ = os.Remove(j.file)
		j.lastCompactAck = j.acked
	} else if st, err := os.Stat(j.file); err == nil && st.Size() > 1<<20 && (j.acked-j.lastCompactAck >= 256 || st.Size() > j.maxBytes/2) {
		j.compact()
	}
}

func (j *Journal) LastSeq() int64  { j.mu.Lock(); defer j.mu.Unlock(); return j.lastSeq }
func (j *Journal) AckedSeq() int64 { j.mu.Lock(); defer j.mu.Unlock(); return j.acked }

func (j *Journal) compact() {
	var keep []journalLine
	for _, l := range j.readAll() {
		if l.seq > j.acked {
			keep = append(keep, l)
		}
	}
	size := estimate(keep)
	if size > j.maxBytes {
		keep = filterOut(keep, "LOG_BATCH")
		size = estimate(keep)
	}
	if size > j.maxBytes {
		keep = filterOut(keep, "K6_STATUS")
	}
	var sb strings.Builder
	for _, l := range keep {
		sb.Write(l.raw)
		sb.WriteByte('\n')
	}
	if err := writeFileAtomic(j.file, []byte(sb.String()), 0o644); err != nil {
		logf("Не удалось сжать журнал: %v", err)
		return
	}
	j.lastCompactAck = j.acked
}

func filterOut(in []journalLine, typ string) []journalLine {
	var out []journalLine
	for _, l := range in {
		if l.typ != typ {
			out = append(out, l)
		}
	}
	return out
}

func estimate(in []journalLine) int64 {
	var n int64
	for _, l := range in {
		n += int64(len(l.raw)) + 1
	}
	return n
}

func (j *Journal) readAll() []journalLine {
	f, err := os.Open(j.file)
	if err != nil {
		return nil
	}
	defer f.Close()
	var out []journalLine
	r := bufio.NewReaderSize(f, 1<<20)
	for {
		line, err := r.ReadBytes('\n')
		line = []byte(strings.TrimSpace(string(line)))
		if len(line) > 0 {
			var h struct {
				Seq  int64  `json:"seq"`
				Type string `json:"type"`
			}
			if json.Unmarshal(line, &h) == nil && h.Seq > 0 {
				out = append(out, journalLine{raw: line, seq: h.Seq, typ: h.Type})
			} else {
				logf("Пропущена повреждённая запись журнала")
			}
		}
		if err != nil {
			break
		}
	}
	return out
}
