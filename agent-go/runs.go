package main

import (
	"archive/zip"
	"bufio"
	"bytes"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"io"
	"math/rand"
	"net"
	"net/http"
	"os"
	"os/exec"
	"path/filepath"
	"regexp"
	"sort"
	"strings"
	"sync"
	"syscall"
	"time"
)

// RunManager - жизненный цикл run на агенте: PREPARE (скачать и проверить архив, нарезать данные, собрать окружение),
// START в заданное время, логи, статус k6, STOP (SIGINT), ABORT (SIGKILL), PAUSE/RESUME/SCALE_VUS через REST API k6.
// Потеря связи с сервером тест не прерывает: события копятся в дисковом журнале.
type RunManager struct {
	cfg   *Config
	emit  func(factory func(seq int64) any)
	mu    sync.Mutex
	runs  map[string]*Run
	httpc *http.Client
}

type Run struct {
	id   string
	prep Prepare
	dir  string

	mu            sync.Mutex
	work          string
	state         string
	cmd           *exec.Cmd
	env           map[string]string
	masker        *Masker
	notes         []string
	logs          *LogBatcher
	startTimer    *time.Timer
	pollStop      chan struct{}
	cancelled     bool
	stopRequested bool
	aborted       bool
	exitCode      *int
	apiPort       int
	api           *K6Api
}

func NewRunManager(cfg *Config, emit func(factory func(seq int64) any)) *RunManager {
	return &RunManager{cfg: cfg, emit: emit, runs: map[string]*Run{}, httpc: &http.Client{}}
}

func (m *RunManager) get(id string) *Run {
	m.mu.Lock()
	defer m.mu.Unlock()
	return m.runs[id]
}

func (m *RunManager) remove(r *Run) {
	m.mu.Lock()
	if m.runs[r.id] == r {
		delete(m.runs, r.id)
	}
	m.mu.Unlock()
}

func (r *Run) getState() string {
	r.mu.Lock()
	defer r.mu.Unlock()
	return r.state
}

func (r *Run) setState(s string) {
	r.mu.Lock()
	r.state = s
	r.mu.Unlock()
}

func (r *Run) pid() *int64 {
	r.mu.Lock()
	defer r.mu.Unlock()
	if r.cmd == nil || r.cmd.Process == nil {
		return nil
	}
	p := int64(r.cmd.Process.Pid)
	return &p
}

// AgentRuns / ActiveRunIDs - состояние для HELLO и heartbeat.
func (m *RunManager) AgentRuns() []AgentRun {
	m.mu.Lock()
	defer m.mu.Unlock()
	out := []AgentRun{}
	for _, r := range m.runs {
		r.mu.Lock()
		ar := AgentRun{RunID: r.id, State: r.state, ExitCode: r.exitCode}
		if r.cmd != nil && r.cmd.Process != nil {
			p := int64(r.cmd.Process.Pid)
			ar.Pid = &p
		}
		r.mu.Unlock()
		out = append(out, ar)
	}
	return out
}

func (m *RunManager) ActiveRunIDs() []string {
	out := []string{}
	for _, r := range m.AgentRuns() {
		if r.State == stRunning || r.State == stStopping {
			out = append(out, r.RunID)
		}
	}
	return out
}

func (m *RunManager) Shutdown() {
	m.mu.Lock()
	defer m.mu.Unlock()
	for _, r := range m.runs {
		r.killQuietly()
	}
}

// ------------------------------------------------------------------ команды

func (m *RunManager) HandleCommand(typ string, raw []byte) CmdAck {
	var base struct {
		CmdID string `json:"cmdId"`
	}
	_ = json.Unmarshal(raw, &base)
	switch typ {
	case "PREPARE":
		var p Prepare
		if err := json.Unmarshal(raw, &p); err != nil {
			return ackErr(base.CmdID, err.Error())
		}
		return m.prepare(p)
	case "START":
		var s Start
		if err := json.Unmarshal(raw, &s); err != nil {
			return ackErr(base.CmdID, err.Error())
		}
		return m.start(s)
	case "CANCEL_PREPARE":
		var c RunCmd
		_ = json.Unmarshal(raw, &c)
		return m.cancelPrepare(c)
	case "STOP", "ABORT", "PAUSE", "RESUME", "SCALE_VUS":
		var c RunCmd
		if err := json.Unmarshal(raw, &c); err != nil {
			return ackErr(base.CmdID, err.Error())
		}
		r := m.get(c.RunID)
		if r == nil {
			return ackErr(c.CmdID, "run "+c.RunID+" не найден на агенте")
		}
		var err error
		switch typ {
		case "STOP":
			err = m.stop(r)
		case "ABORT":
			r.mu.Lock()
			r.aborted = true
			r.mu.Unlock()
			r.killQuietly()
		case "PAUSE", "RESUME", "SCALE_VUS":
			var api *K6Api
			if api, err = r.apiClient(); err == nil {
				switch typ {
				case "PAUSE":
					err = api.Pause(true)
				case "RESUME":
					err = api.Pause(false)
				default:
					err = api.ScaleVus(c.Vus)
				}
			}
		}
		if err != nil {
			return ackErr(c.CmdID, err.Error())
		}
		return ackOK(c.CmdID)
	}
	return ackErr(base.CmdID, "Неизвестная команда "+typ)
}

func (r *Run) apiClient() (*K6Api, error) {
	r.mu.Lock()
	defer r.mu.Unlock()
	if r.state != stRunning && r.state != stStopping {
		return nil, fmt.Errorf("k6 не запущен (состояние %s)", r.state)
	}
	if r.api == nil {
		r.api = NewK6Api(r.apiPort)
	}
	return r.api, nil
}

func (m *RunManager) prepare(p Prepare) CmdAck {
	m.mu.Lock()
	if _, ok := m.runs[p.RunID]; ok {
		m.mu.Unlock()
		return ackOK(p.CmdID) // повторная доставка PREPARE: не готовим заново
	}
	r := &Run{id: p.RunID, prep: p, dir: filepath.Join(m.cfg.Home, "runs", p.RunID), state: stPreparing,
		env: map[string]string{}, masker: NewMasker(nil)}
	m.runs[p.RunID] = r
	m.mu.Unlock()
	go m.doPrepare(r)
	return ackOK(p.CmdID)
}

func (m *RunManager) start(s Start) CmdAck {
	r := m.get(s.RunID)
	if r == nil {
		return ackErr(s.CmdID, "run "+s.RunID+" не подготовлен")
	}
	switch st := r.getState(); st {
	case stRunning:
		return ackOK(s.CmdID)
	case stPrepared:
	default:
		return ackErr(s.CmdID, "run в состоянии "+st+", START возможен только после PREPARED")
	}
	delay := s.StartAt - time.Now().UnixMilli()
	if delay < -2000 {
		return ackErr(s.CmdID, fmt.Sprintf("Время старта уже прошло на %d мс", -delay))
	}
	if delay < 0 {
		delay = 0
	}
	r.mu.Lock()
	r.startTimer = time.AfterFunc(time.Duration(delay)*time.Millisecond, func() { m.launch(r) })
	r.mu.Unlock()
	return ackOK(s.CmdID)
}

func (m *RunManager) cancelPrepare(c RunCmd) CmdAck {
	r := m.get(c.RunID)
	if r == nil {
		return ackOK(c.CmdID)
	}
	if st := r.getState(); st == stRunning || st == stStopping {
		return ackErr(c.CmdID, "run уже запущен - используйте STOP/ABORT")
	}
	r.mu.Lock()
	r.cancelled = true
	if r.startTimer != nil {
		r.startTimer.Stop()
	}
	r.state = stExited
	r.mu.Unlock()
	m.remove(r)
	_ = os.RemoveAll(r.dir)
	return ackOK(c.CmdID)
}

// ------------------------------------------------------------------ PREPARE

func (m *RunManager) doPrepare(r *Run) {
	p := r.prep
	err := m.prepareSteps(r)
	if err == nil {
		r.mu.Lock()
		cancelled := r.cancelled
		r.mu.Unlock()
		if cancelled {
			return
		}
		r.setState(stPrepared)
		m.emit(func(seq int64) any { return Prepared{Type: "PREPARED", Seq: seq, Ts: nowMs(), RunID: p.RunID} })
		logf("run %s подготовлен", p.RunID)
		return
	}
	logf("PREPARE run %s не удался: %v", p.RunID, err)
	r.setState(stExited)
	m.remove(r)
	_ = os.RemoveAll(r.dir)
	r.mu.Lock()
	cancelled := r.cancelled
	r.mu.Unlock()
	if !cancelled {
		reason := err.Error()
		m.emit(func(seq int64) any {
			return PrepareFailed{Type: "PREPARE_FAILED", Seq: seq, Ts: nowMs(), RunID: p.RunID, Reason: reason}
		})
	}
}

func (m *RunManager) prepareSteps(r *Run) error {
	p := r.prep
	if err := os.MkdirAll(r.dir, 0o755); err != nil {
		return err
	}
	archive := filepath.Join(r.dir, "script.zip")
	if err := m.download(p.ArtifactURL, archive); err != nil {
		return err
	}
	sum, err := sha256File(archive)
	if err != nil {
		return err
	}
	if p.ArtifactSha256 != "" && !strings.EqualFold(p.ArtifactSha256, sum) {
		return fmt.Errorf("sha256 архива не совпадает (получен %s, ожидался %s)", sum, p.ArtifactSha256)
	}
	work := filepath.Join(r.dir, "work")
	if err := extractZip(archive, work); err != nil {
		return err
	}
	_ = os.Remove(archive)
	if st, err := os.Stat(filepath.Join(work, filepath.FromSlash(p.Entrypoint))); err != nil || st.IsDir() {
		return fmt.Errorf("Entrypoint %s не найден в архиве", p.Entrypoint)
	}
	r.mu.Lock()
	r.work = work
	r.mu.Unlock()

	cfgJSON := []byte("{}")
	if len(p.K6Config) > 0 && string(p.K6Config) != "null" {
		var buf bytes.Buffer
		if json.Indent(&buf, p.K6Config, "", "  ") == nil {
			cfgJSON = buf.Bytes()
		}
	}
	if err := os.WriteFile(filepath.Join(r.dir, "config.json"), cfgJSON, 0o644); err != nil {
		return err
	}

	// нарезка данных (SPLIT) пропорционально своему execution segment
	var notes []string
	if len(p.DataRules) > 0 {
		start, end := Fraction{bigInt(0), bigInt(1)}, Fraction{bigInt(1), bigInt(1)}
		if strings.TrimSpace(p.ExecutionSegment) != "" {
			if start, end, err = parseSegment(p.ExecutionSegment); err != nil {
				return err
			}
		}
		for _, rule := range p.DataRules {
			if !strings.EqualFold(rule.Mode, "SPLIT") {
				continue
			}
			file := filepath.Join(work, filepath.FromSlash(rule.Path))
			if rel, e := filepath.Rel(work, file); e != nil || strings.HasPrefix(rel, "..") {
				return fmt.Errorf("Файл данных %s не найден в архиве", rule.Path)
			}
			if st, e := os.Stat(file); e != nil || st.IsDir() {
				return fmt.Errorf("Файл данных %s не найден в архиве", rule.Path)
			}
			total, from, to, e := splitData(file, start, end, rule.HasHeader)
			if e != nil {
				return e
			}
			notes = append(notes, fmt.Sprintf("Данные %s: SPLIT, строки %d..%d из %d", rule.Path, from, to-1, total))
		}
	}

	resolved, err := resolveSecrets(p.Env, p.Secrets)
	if err != nil {
		return err
	}
	env := buildEnv(p, resolved)
	var secretValues []string
	for _, v := range p.Secrets {
		secretValues = append(secretValues, v)
	}
	if p.Prometheus != nil {
		for _, v := range p.Prometheus.Headers {
			secretValues = append(secretValues, v)
		}
	}
	if st, err := os.Stat(m.cfg.K6Path); err != nil || st.IsDir() {
		return fmt.Errorf("k6 не найден или не исполняемый: %s", m.cfg.K6Path)
	}
	r.mu.Lock()
	r.env, r.masker, r.notes = env, NewMasker(secretValues), notes
	r.mu.Unlock()
	return nil
}

func (m *RunManager) download(url, target string) error {
	c := &http.Client{Timeout: 10 * time.Minute}
	resp, err := c.Get(url)
	if err != nil {
		return fmt.Errorf("Не удалось скачать архив скрипта: %w", err)
	}
	defer resp.Body.Close()
	if resp.StatusCode != 200 {
		return fmt.Errorf("Не удалось скачать архив скрипта: HTTP %d", resp.StatusCode)
	}
	f, err := os.Create(target)
	if err != nil {
		return err
	}
	defer f.Close()
	_, err = io.Copy(f, resp.Body)
	return err
}

// ------------------------------------------------------------------ запуск k6

func (m *RunManager) launch(r *Run) {
	r.mu.Lock()
	if r.cancelled || r.state != stPrepared {
		r.mu.Unlock()
		return
	}
	r.mu.Unlock()
	p := r.prep
	summary := filepath.Join(r.dir, "summary.json")
	port, err := freePort(p.K6ApiPort)
	if err == nil {
		r.mu.Lock()
		r.apiPort = port
		r.mu.Unlock()
		r.mu.Lock()
		work, env, masker, notes := r.work, r.env, r.masker, r.notes
		r.mu.Unlock()
		args := buildArgs(p, filepath.Join(r.dir, "config.json"), summary, port)
		cmd := exec.Command(m.cfg.K6Path, args...)
		cmd.Dir = work
		cmd.Env = os.Environ()
		for k, v := range env {
			cmd.Env = append(cmd.Env, k+"="+v)
		}
		lps := p.LogLinesPerSecond
		if lps <= 0 {
			lps = 200
		}
		logs := NewLogBatcher(p.RunID, lps, masker, m.emit)
		for _, n := range notes {
			logs.Add("agent", n)
		}
		r.mu.Lock()
		r.logs = logs
		r.mu.Unlock()
		var stdout, stderr io.ReadCloser
		if stdout, err = cmd.StdoutPipe(); err == nil {
			if stderr, err = cmd.StderrPipe(); err == nil {
				err = cmd.Start()
			}
		}
		if err == nil {
			pid := int64(cmd.Process.Pid)
			r.mu.Lock()
			r.cmd = cmd
			r.state = stRunning
			r.pollStop = make(chan struct{})
			r.mu.Unlock()
			m.emit(func(seq int64) any {
				return Status{Type: "STATUS", Seq: seq, Ts: nowMs(), RunID: p.RunID, State: stRunning, Pid: &pid}
			})
			logf("run %s: k6 запущен, pid %d", p.RunID, pid)
			var wg sync.WaitGroup
			wg.Add(2)
			go pump(&wg, logs, stdout, "stdout")
			go pump(&wg, logs, stderr, "stderr")
			go m.poll(r)
			go m.awaitExit(r, cmd, &wg, summary)
			return
		}
	}
	logf("Не удалось запустить k6 для run %s: %v", p.RunID, err)
	code := 127
	r.mu.Lock()
	r.state, r.exitCode = stExited, &code
	logs := r.logs
	r.mu.Unlock()
	m.emit(func(seq int64) any { return Exit{Type: "EXIT", Seq: seq, Ts: nowMs(), RunID: p.RunID, Code: 127} })
	if logs != nil {
		logs.Add("agent", fmt.Sprintf("Не удалось запустить k6: %v", err))
		logs.Close()
	}
	m.scheduleCleanup(r)
}

func pump(wg *sync.WaitGroup, logs *LogBatcher, in io.Reader, stream string) {
	defer wg.Done()
	rd := bufio.NewReaderSize(in, 64*1024)
	for {
		line, err := rd.ReadString('\n')
		line = strings.TrimRight(line, "\r\n")
		if line != "" || err == nil {
			logs.Add(stream, line)
		}
		if err != nil {
			return
		}
	}
}

func (m *RunManager) poll(r *Run) {
	t := time.NewTicker(2 * time.Second)
	defer t.Stop()
	r.mu.Lock()
	stop := r.pollStop
	r.mu.Unlock()
	for {
		select {
		case <-t.C:
			st := r.getState()
			if st != stRunning && st != stStopping {
				return
			}
			api, err := r.apiClient()
			if err != nil {
				continue
			}
			s, err := api.Status()
			if err != nil {
				continue // API ещё не поднялось или k6 завершается - пропускаем такт
			}
			it, dr := api.Counter("iterations"), api.Counter("dropped_iterations")
			text := "running"
			if !s.Running {
				text = "stopped"
			} else if s.Paused {
				text = "paused"
			}
			m.emit(func(seq int64) any {
				return K6Status{Type: "K6_STATUS", Seq: seq, Ts: nowMs(), RunID: r.id, Vus: s.Vus, VusMax: s.VusMax, Iterations: it,
					DroppedIterations: dr, Status: text, Paused: s.Paused, Running: s.Running}
			})
		case <-stop:
			return
		}
	}
}

func (m *RunManager) awaitExit(r *Run, cmd *exec.Cmd, wg *sync.WaitGroup, summaryFile string) {
	wg.Wait() // вывод дочитан до EOF (процесс завершился)
	_ = cmd.Wait()
	code, sig := exitInfo(cmd)
	r.mu.Lock()
	if r.pollStop != nil {
		close(r.pollStop)
	}
	r.exitCode = &code
	logs := r.logs
	aborted, stopReq := r.aborted, r.stopRequested
	r.mu.Unlock()
	if logs != nil {
		logs.Close()
		if d := logs.TotalDropped(); d > 0 {
			logf("run %s: отброшено %d строк лога по лимиту", r.id, d)
		}
	}
	// summary (--summary-export): кумулятивные перцентили, только справочно
	if b, err := os.ReadFile(summaryFile); err == nil && json.Valid(b) {
		m.emit(func(seq int64) any {
			return Summary{Type: "SUMMARY", Seq: seq, Ts: nowMs(), RunID: r.id, Summary: json.RawMessage(b)}
		})
	}
	var signal *string
	if aborted {
		signal = strp("SIGKILL")
	} else if sig > 0 && !stopReq {
		signal = strp(fmt.Sprintf("SIG%d", sig))
	}
	r.setState(stExited)
	m.emit(func(seq int64) any {
		return Exit{Type: "EXIT", Seq: seq, Ts: nowMs(), RunID: r.id, Code: code, Signal: signal}
	})
	logf("run %s: k6 завершился с кодом %d", r.id, code)
	m.scheduleCleanup(r)
}

// exitInfo: код выхода (для убитого сигналом - 128+N) и номер сигнала.
func exitInfo(cmd *exec.Cmd) (code int, sig int) {
	ps := cmd.ProcessState
	if ps == nil {
		return -1, 0
	}
	if ws, ok := ps.Sys().(syscall.WaitStatus); ok && ws.Signaled() {
		return 128 + int(ws.Signal()), int(ws.Signal())
	}
	return ps.ExitCode(), 0
}

func (m *RunManager) scheduleCleanup(r *Run) {
	// каталог run чистим через 10 минут, затем забываем run
	time.AfterFunc(10*time.Minute, func() {
		m.remove(r)
		_ = os.RemoveAll(r.dir)
	})
}

// stop - graceful: SIGINT (k6 корректно завершает итерации и пушит метрики).
func (m *RunManager) stop(r *Run) error {
	r.mu.Lock()
	cmd := r.cmd
	alive := cmd != nil && cmd.Process != nil && r.state != stExited
	state := r.state
	r.mu.Unlock()
	if !alive {
		if state == stPrepared {
			// остановка до старта: просто отменяем; сервер ждёт EXIT каждого генератора
			r.mu.Lock()
			r.cancelled = true
			if r.startTimer != nil {
				r.startTimer.Stop()
			}
			r.state = stExited
			r.mu.Unlock()
			m.remove(r)
			_ = os.RemoveAll(r.dir)
			m.emit(func(seq int64) any {
				return Exit{Type: "EXIT", Seq: seq, Ts: nowMs(), RunID: r.id, Code: 0, Signal: strp("CANCELLED")}
			})
		}
		return nil
	}
	r.mu.Lock()
	r.stopRequested = true
	r.state = stStopping
	r.mu.Unlock()
	pid := int64(cmd.Process.Pid)
	m.emit(func(seq int64) any {
		return Status{Type: "STATUS", Seq: seq, Ts: nowMs(), RunID: r.id, State: stStopping, Pid: &pid}
	})
	if isWindows() {
		return cmd.Process.Kill() // dev на Windows: сигналов нет
	}
	if err := cmd.Process.Signal(os.Interrupt); err != nil {
		return cmd.Process.Kill()
	}
	return nil
}

func (r *Run) killQuietly() {
	r.mu.Lock()
	cmd := r.cmd
	r.mu.Unlock()
	if cmd != nil && cmd.Process != nil {
		_ = cmd.Process.Kill()
	}
}

// ------------------------------------------------------------------ командная строка и окружение k6

var secretRef = regexp.MustCompile(`\$\{secret:([A-Za-z0-9_.-]+)\}`)

func buildArgs(p Prepare, config, summary string, apiPort int) []string {
	a := []string{"run", "--quiet", "--no-color", "--config", config}
	if seg := strings.TrimSpace(p.ExecutionSegment); seg != "" && seg != "0:1" {
		a = append(a, "--execution-segment", seg)
		if s := strings.TrimSpace(p.ExecutionSegmentSequence); s != "" {
			a = append(a, "--execution-segment-sequence", s)
		}
	}
	if p.Prometheus != nil && strings.TrimSpace(p.Prometheus.URL) != "" {
		a = append(a, "--out", "experimental-prometheus-rw")
	}
	keys := make([]string, 0, len(p.Tags))
	for k := range p.Tags {
		keys = append(keys, k)
	}
	sort.Strings(keys)
	for _, k := range keys {
		if k == "scenario" || strings.TrimSpace(k) == "" { // системный тег scenario (= операция) не переопределяем
			continue
		}
		a = append(a, "--tag", k+"="+p.Tags[k])
	}
	return append(a, "--address", fmt.Sprintf("127.0.0.1:%d", apiPort), "--summary-export", summary, p.Entrypoint)
}

func buildEnv(p Prepare, resolved map[string]string) map[string]string {
	env := map[string]string{}
	for k, v := range resolved {
		env[k] = v
	}
	env["K6_NO_USAGE_REPORT"] = "true"
	env["K6_NO_VERSION_CHECK"] = "true"
	// у генераторов нет интернета: k6 не должен пытаться докачивать расширения (зависание на старте)
	env["K6_AUTO_EXTENSION_RESOLUTION"] = "false"
	if pr := p.Prometheus; pr != nil && strings.TrimSpace(pr.URL) != "" {
		env["K6_PROMETHEUS_RW_SERVER_URL"] = pr.URL
		env["K6_PROMETHEUS_RW_TREND_AS_NATIVE_HISTOGRAM"] = fmt.Sprintf("%t", pr.TrendAsNativeHistogram)
		push := pr.PushInterval
		if push == "" {
			push = "5s"
		}
		env["K6_PROMETHEUS_RW_PUSH_INTERVAL"] = push
		for k, v := range pr.Headers {
			env["K6_PROMETHEUS_RW_HEADERS_"+k] = v
		}
	}
	return env
}

// resolveSecrets подставляет ${secret:NAME}; неизвестный секрет - ошибка (PREPARE_FAILED).
func resolveSecrets(env, secrets map[string]string) (map[string]string, error) {
	out := map[string]string{}
	for k, v := range env {
		var failed string
		res := secretRef.ReplaceAllStringFunc(v, func(m string) string {
			name := secretRef.FindStringSubmatch(m)[1]
			s, ok := secrets[name]
			if !ok {
				failed = name
				return m
			}
			return s
		})
		if failed != "" {
			return nil, fmt.Errorf("Секрет %s не передан агенту (env %s)", failed, k)
		}
		out[k] = res
	}
	return out, nil
}

// ------------------------------------------------------------------ утилиты

// freePort - свободный порт на loopback, начиная с желаемого со случайным смещением (несколько агентов на одном
// хосте одновременно проверяют порты и не должны выбрать один и тот же).
func freePort(preferred int) (int, error) {
	if preferred < 1024 {
		preferred = 1024
	}
	base := preferred + rand.Intn(200)
	for port := base; port < base+500 && port < 65535; port++ {
		l, err := net.Listen("tcp", fmt.Sprintf("127.0.0.1:%d", port))
		if err == nil {
			l.Close()
			return port, nil
		}
	}
	return 0, fmt.Errorf("Не нашёлся свободный порт для REST API k6 начиная с %d", preferred)
}

func sha256File(path string) (string, error) {
	f, err := os.Open(path)
	if err != nil {
		return "", err
	}
	defer f.Close()
	h := sha256.New()
	if _, err := io.Copy(h, f); err != nil {
		return "", err
	}
	return hex.EncodeToString(h.Sum(nil)), nil
}

const (
	maxZipEntries = 50000
	maxZipBytes   = int64(2) << 30
)

// extractZip - безопасная распаковка: без абсолютных путей и .., с лимитами числа файлов и размера.
func extractZip(archive, dest string) error {
	zr, err := zip.OpenReader(archive)
	if err != nil {
		return fmt.Errorf("архив скрипта не читается: %w", err)
	}
	defer zr.Close()
	root, err := filepath.Abs(dest)
	if err != nil {
		return err
	}
	if err := os.MkdirAll(root, 0o755); err != nil {
		return err
	}
	if len(zr.File) > maxZipEntries {
		return fmt.Errorf("В архиве слишком много файлов")
	}
	var total int64
	for _, e := range zr.File {
		name := strings.ReplaceAll(e.Name, "\\", "/")
		for strings.HasPrefix(name, "./") {
			name = name[2:]
		}
		bad := name == "" || strings.HasPrefix(name, "/") || regexp.MustCompile(`^[A-Za-z]:`).MatchString(name)
		for _, seg := range strings.Split(name, "/") {
			if seg == ".." {
				bad = true
			}
		}
		if bad {
			return fmt.Errorf("Недопустимый путь в архиве: %s", e.Name)
		}
		target := filepath.Join(root, filepath.FromSlash(name))
		if rel, err := filepath.Rel(root, target); err != nil || strings.HasPrefix(rel, "..") {
			return fmt.Errorf("Недопустимый путь в архиве: %s", e.Name)
		}
		if e.FileInfo().IsDir() {
			if err := os.MkdirAll(target, 0o755); err != nil {
				return err
			}
			continue
		}
		if err := os.MkdirAll(filepath.Dir(target), 0o755); err != nil {
			return err
		}
		rc, err := e.Open()
		if err != nil {
			return err
		}
		out, err := os.Create(target)
		if err != nil {
			rc.Close()
			return err
		}
		n, err := io.Copy(out, io.LimitReader(rc, maxZipBytes-total+1))
		out.Close()
		rc.Close()
		if err != nil {
			return err
		}
		total += n
		if total > maxZipBytes {
			return fmt.Errorf("Архив слишком большой после распаковки")
		}
	}
	return nil
}

func nowMs() int64 { return time.Now().UnixMilli() }
