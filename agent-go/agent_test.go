package main

import (
	"archive/zip"
	"encoding/json"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"
)

func TestJournalSurvivesRestartAndAcks(t *testing.T) {
	dir := t.TempDir()
	j, _ := NewJournal(dir, 1<<20)
	for i := 0; i < 3; i++ {
		j.Append(func(seq int64) any { return Prepared{Type: "PREPARED", Seq: seq, Ts: 1, RunID: "r"} })
	}
	if j.LastSeq() != 3 || len(j.Unacked()) != 3 {
		t.Fatalf("seq=%d unacked=%d", j.LastSeq(), len(j.Unacked()))
	}
	j.Ack(2)
	j2, _ := NewJournal(dir, 1<<20) // рестарт агента
	if j2.LastSeq() != 3 || j2.AckedSeq() != 2 {
		t.Fatalf("после рестарта seq=%d acked=%d", j2.LastSeq(), j2.AckedSeq())
	}
	un := j2.Unacked()
	if len(un) != 1 || !strings.Contains(string(un[0]), `"seq":3`) {
		t.Fatalf("ожидалось одно событие seq=3: %v", un)
	}
	j2.Ack(100) // не дальше lastSeq
	if j2.AckedSeq() != 3 || len(j2.Unacked()) != 0 {
		t.Fatal("ack за пределами lastSeq")
	}
	e := j2.Append(func(seq int64) any { return Prepared{Type: "PREPARED", Seq: seq} })
	if !strings.Contains(string(e), `"seq":4`) {
		t.Fatalf("seq не должен откатываться: %s", e)
	}
}

func TestJournalEvictsLogsBeforeOtherEvents(t *testing.T) {
	j, _ := NewJournal(t.TempDir(), 600)
	j.Append(func(seq int64) any { return Exit{Type: "EXIT", Seq: seq, RunID: "r", Code: 0} })
	for i := 0; i < 10; i++ {
		j.Append(func(seq int64) any {
			return LogBatch{Type: "LOG_BATCH", Seq: seq, RunID: "r", Lines: []LogLine{{Ts: 1, Stream: "stdout", Line: strings.Repeat("x", 100)}}}
		})
	}
	var types []string
	for _, e := range j.Unacked() {
		typ, _ := decodeType(e)
		types = append(types, typ)
	}
	if types[0] != "EXIT" {
		t.Fatalf("EXIT вытеснен: %v", types)
	}
}

func TestProtocolMatchesServerFormat(t *testing.T) {
	// сообщения сервера разбираются, а исходящие содержат поле type и имена полей Java-протокола
	typ, err := decodeType([]byte(`{"type":"PREPARE","cmdId":"c1","runId":"7"}`))
	if err != nil || typ != "PREPARE" {
		t.Fatalf("%v %v", typ, err)
	}
	var p Prepare
	raw := `{"type":"PREPARE","cmdId":"c1","runId":"7","artifactUrl":"http://x","k6Config":{"scenarios":{"a":{}}},"env":{"A":"1"},
		"executionSegment":"0:1/3","k6ApiPort":6565,"dataRules":[{"path":"d.csv","mode":"SPLIT","hasHeader":true}],"unknownField":1}`
	if err := json.Unmarshal([]byte(raw), &p); err != nil || p.RunID != "7" || p.ExecutionSegment != "0:1/3" || !p.DataRules[0].HasHeader {
		t.Fatalf("prepare: %+v %v", p, err)
	}
	b, _ := json.Marshal(Hello{Type: "HELLO", ProtocolVersion: 1, AgentVersion: "v", Runs: []AgentRun{}})
	for _, f := range []string{`"type":"HELLO"`, `"protocolVersion":1`, `"lastAckedSeq":0`, `"runs":[]`} {
		if !strings.Contains(string(b), f) {
			t.Fatalf("в HELLO нет %s: %s", f, b)
		}
	}
	b, _ = json.Marshal(ackErr("c", "boom"))
	if !strings.Contains(string(b), `"ok":false`) || !strings.Contains(string(b), `"error":"boom"`) {
		t.Fatal(string(b))
	}
}

func TestBackoffGrowsWithinBoundsAndResets(t *testing.T) {
	b := NewBackoff(time.Second, 8*time.Second)
	prevMax := time.Duration(0)
	for i := 0; i < 8; i++ {
		d := b.Next()
		if d > 8*time.Second || d <= 0 {
			t.Fatalf("вне границ: %v", d)
		}
		if d > prevMax {
			prevMax = d
		}
	}
	if prevMax < 6*time.Second {
		t.Fatalf("backoff не растёт: %v", prevMax)
	}
	b.Reset()
	if d := b.Next(); d > 1300*time.Millisecond {
		t.Fatalf("после reset ожидается ~min: %v", d)
	}
}

func TestSplitDataIsDisjointAndKeepsHeader(t *testing.T) {
	dir := t.TempDir()
	var sb strings.Builder
	sb.WriteString("login,pwd\r\n")
	for i := 0; i < 20; i++ {
		sb.WriteString("u" + string(rune('a'+i)) + ",p\r\n")
	}
	var parts []string
	segs := [][2]string{{"0", "1/4"}, {"1/4", "3/4"}, {"3/4", "1"}}
	for i, s := range segs {
		f := filepath.Join(dir, "d"+string(rune('0'+i))+".csv")
		_ = os.WriteFile(f, []byte(sb.String()), 0o644)
		a, _ := parseFraction(s[0])
		b, _ := parseFraction(s[1])
		total, _, _, err := splitData(f, a, b, true)
		if err != nil || total != 20 {
			t.Fatalf("%v total=%d", err, total)
		}
		data, _ := os.ReadFile(f)
		lines := strings.Split(strings.TrimRight(string(data), "\r\n"), "\r\n")
		if lines[0] != "login,pwd" {
			t.Fatalf("заголовок потерян: %q", lines[0])
		}
		parts = append(parts, lines[1:]...)
	}
	if len(parts) != 20 { // без пересечений и без потерь
		t.Fatalf("строк %d, ожидалось 20", len(parts))
	}
	seen := map[string]bool{}
	for _, p := range parts {
		if seen[p] {
			t.Fatalf("дубль %s", p)
		}
		seen[p] = true
	}
}

func TestSegmentsAndSecrets(t *testing.T) {
	a, b, err := parseSegment("1/3:2/3")
	if err != nil || a.floorTimes(9) != 3 || b.floorTimes(9) != 6 {
		t.Fatalf("%v", err)
	}
	if _, _, err := parseSegment("oops"); err == nil {
		t.Fatal("ожидалась ошибка")
	}
	res, err := resolveSecrets(map[string]string{"A": "x-${secret:TOKEN}-y", "B": "plain"}, map[string]string{"TOKEN": "s3cr3t"})
	if err != nil || res["A"] != "x-s3cr3t-y" || res["B"] != "plain" {
		t.Fatalf("%v %v", res, err)
	}
	if _, err := resolveSecrets(map[string]string{"A": "${secret:NOPE}"}, nil); err == nil {
		t.Fatal("неизвестный секрет должен быть ошибкой")
	}
	m := NewMasker([]string{"s3cr3t", "ab"}) // короче 3 символов не маскируем
	if got := m.Mask("token=s3cr3t ab"); got != "token=****** ab" {
		t.Fatal(got)
	}
}

func TestK6CommandNeverOverridesScenarioTagAndEnvDisablesExtensionResolution(t *testing.T) {
	p := Prepare{Entrypoint: "main.js", ExecutionSegment: "0:1/2", ExecutionSegmentSequence: "0,1/2,1",
		Tags: map[string]string{"scenario": "evil", "test_run_id": "5", "generator": "g1"},
		Prometheus: &PrometheusParams{URL: "http://p/write", PushInterval: "2s", TrendAsNativeHistogram: true, Headers: map[string]string{"Authorization": "Bearer x"}}}
	args := strings.Join(buildArgs(p, "/c.json", "/s.json", 6600), " ")
	for _, want := range []string{"--execution-segment 0:1/2", "--execution-segment-sequence 0,1/2,1", "--out experimental-prometheus-rw",
		"--tag generator=g1", "--tag test_run_id=5", "--address 127.0.0.1:6600", "--summary-export /s.json"} {
		if !strings.Contains(args, want) {
			t.Errorf("нет %q в %s", want, args)
		}
	}
	if strings.Contains(args, "scenario=") {
		t.Fatalf("тег scenario нельзя переопределять: %s", args)
	}
	env := buildEnv(p, map[string]string{"X": "1"})
	if env["K6_AUTO_EXTENSION_RESOLUTION"] != "false" || env["K6_PROMETHEUS_RW_TREND_AS_NATIVE_HISTOGRAM"] != "true" ||
		env["K6_PROMETHEUS_RW_PUSH_INTERVAL"] != "2s" || env["K6_PROMETHEUS_RW_HEADERS_Authorization"] != "Bearer x" || env["X"] != "1" {
		t.Fatalf("%v", env)
	}
	// единственный сегмент 0:1 не передаётся
	p.ExecutionSegment = "0:1"
	if strings.Contains(strings.Join(buildArgs(p, "c", "s", 1), " "), "--execution-segment") {
		t.Fatal("0:1 не должен передаваться")
	}
}

func TestExtractZipRejectsPathTraversal(t *testing.T) {
	dir := t.TempDir()
	mk := func(name string) string {
		p := filepath.Join(dir, strings.ReplaceAll(name, "/", "_")+".zip")
		f, _ := os.Create(p)
		w := zip.NewWriter(f)
		e, _ := w.Create(name)
		_, _ = e.Write([]byte("x"))
		_ = w.Close()
		_ = f.Close()
		return p
	}
	if err := extractZip(mk("../evil.txt"), filepath.Join(dir, "out1")); err == nil {
		t.Fatal("ожидался отказ для ../")
	}
	if err := extractZip(mk("/abs.txt"), filepath.Join(dir, "out2")); err == nil {
		t.Fatal("ожидался отказ для абсолютного пути")
	}
	if err := extractZip(mk("ok/main.js"), filepath.Join(dir, "out3")); err != nil {
		t.Fatal(err)
	}
	if _, err := os.Stat(filepath.Join(dir, "out3", "ok", "main.js")); err != nil {
		t.Fatal(err)
	}
}

func TestConfigFromPropertiesAndArgsAndPermanentToken(t *testing.T) {
	home := t.TempDir()
	_ = os.WriteFile(filepath.Join(home, "agent.properties"), []byte("# c\nagent.server-url=ws://h:1/agent/ws\nagent.token=one\nagent.agent-id=g1\nagent.reconnect-max=PT30S\n"), 0o600)
	c, err := loadConfig([]string{"--agent.home=" + home, "--agent.reconnect-min=500ms"})
	if err != nil || c.ServerURL != "ws://h:1/agent/ws" || c.Token != "one" || c.AgentID != "g1" ||
		c.ReconnectMin != 500*time.Millisecond || c.ReconnectMax != 30*time.Second {
		t.Fatalf("%+v %v", c, err)
	}
	c.SavePermanent("perm")
	b, _ := os.ReadFile(filepath.Join(home, "agent.properties"))
	if !strings.Contains(string(b), "agent.token=perm") || strings.Contains(string(b), "agent.token=one") || !strings.Contains(string(b), "agent.agent-id=g1") {
		t.Fatalf("%s", b)
	}
	if c.CurrentToken() != "perm" {
		t.Fatal("токен в памяти")
	}
}
