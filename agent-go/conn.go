package main

import (
	"context"
	"encoding/json"
	"net/http"
	"os/exec"
	"regexp"
	"strings"
	"sync"
	"time"

	"github.com/gorilla/websocket"
)

var version = "go-dev" // задаётся при сборке: -ldflags "-X main.version=..."

// Conn - WebSocket-соединение с сервером. Инициирует агент; при обрыве переподключается с exponential backoff.
// Работа k6 от состояния связи не зависит.
type Conn struct {
	cfg     *Config
	journal *Journal
	runs    *RunManager
	metrics *SystemMetrics
	backoff *Backoff

	mu     sync.Mutex // защищает ws, запись и порядок seq при Emit/Replay
	ws     *websocket.Conn
	recMu  sync.Mutex // защищает дедупликацию cmdId
	recent []string
	recSet map[string]bool

	k6mu      sync.Mutex
	k6ver     *string
	k6checked time.Time
}

func NewConn(cfg *Config, j *Journal, runs *RunManager, metrics *SystemMetrics) *Conn {
	return &Conn{cfg: cfg, journal: j, runs: runs, metrics: metrics, backoff: NewBackoff(cfg.ReconnectMin, cfg.ReconnectMax),
		recSet: map[string]bool{}}
}

// Emit атомарно записывает событие в журнал (назначая следующий seq) и, если соединение готово после HELLO/replay,
// немедленно отправляет его в том же порядке seq.
func (c *Conn) Emit(factory func(seq int64) any) {
	c.mu.Lock()
	defer c.mu.Unlock()
	raw := c.journal.Append(factory)
	if raw == nil || c.ws == nil {
		return
	}
	c.writeLocked(c.ws, raw)
}

// Publish - событие уже записано в журнал, здесь только отправка при наличии связи.
func (c *Conn) Publish(raw []byte) {
	if raw != nil {
		c.send(raw)
	}
}

func (c *Conn) writeLocked(ws *websocket.Conn, raw []byte) error {
	_ = ws.SetWriteDeadline(time.Now().Add(15 * time.Second))
	if err := ws.WriteMessage(websocket.TextMessage, raw); err != nil {
		debugf("Ошибка отправки: %v", err)
		_ = ws.Close()
		if c.ws == ws {
			c.ws = nil
		}
		return err
	}
	return nil
}

func (c *Conn) send(raw []byte) {
	c.mu.Lock()
	defer c.mu.Unlock()
	if c.ws == nil {
		return
	}
	_ = c.writeLocked(c.ws, raw)
}

func (c *Conn) sendJSON(v any) {
	if b, err := json.Marshal(v); err == nil {
		c.send(b)
	}
}

// Run крутит цикл подключения до отмены контекста.
func (c *Conn) Run(ctx context.Context) {
	if strings.TrimSpace(c.cfg.ServerURL) == "" {
		logf("agent.server-url не задан - агент не может подключиться")
		<-ctx.Done()
		return
	}
	logf("Агент %s стартовал, сервер %s", c.cfg.AgentID, c.cfg.ServerURL)
	for ctx.Err() == nil {
		c.session(ctx)
		if ctx.Err() != nil {
			return
		}
		d := c.backoff.Next()
		logf("Повторное подключение через %d мс", d.Milliseconds())
		select {
		case <-time.After(d):
		case <-ctx.Done():
			return
		}
	}
}

func (c *Conn) session(ctx context.Context) {
	token := c.cfg.CurrentToken()
	if token == "" {
		logf("Токен агента не задан")
		return
	}
	h := http.Header{}
	h.Set("Authorization", "Bearer "+token)
	h.Set("X-Agent-Id", c.cfg.AgentID)
	d := websocket.Dialer{HandshakeTimeout: 10 * time.Second}
	ws, _, err := d.DialContext(ctx, c.cfg.ServerURL, h)
	if err != nil {
		logf("Не удалось подключиться к серверу: %v", err)
		return
	}
	c.backoff.Reset()
	logf("Соединение с сервером установлено")
	defer func() {
		c.mu.Lock()
		if c.ws == ws {
			c.ws = nil
		}
		c.mu.Unlock()
		_ = ws.Close()
	}()

	readTimeout := 3 * c.cfg.HeartbeatInterval
	if readTimeout < 90*time.Second {
		readTimeout = 90 * time.Second
	}
	ws.SetPongHandler(func(string) error {
		_ = ws.SetReadDeadline(time.Now().Add(readTimeout))
		return nil
	})

	// Отправляем HELLO и все неподтверждённые события ДО публикации c.ws и запуска heartbeat,
	// удерживая c.mu: параллельный Emit не вклинится между HELLO и replay и не нарушит порядок seq.
	if err := c.sendHelloAndReplayLocked(ws); err != nil {
		logf("Ошибка отправки HELLO/replay: %v", err)
		return
	}

	stop := make(chan struct{})
	defer close(stop)
	go func() { // heartbeat
		t := time.NewTicker(c.cfg.HeartbeatInterval)
		defer t.Stop()
		for {
			select {
			case <-t.C:
				c.sendJSON(c.metrics.Snapshot(c.k6Version(), version, c.runs.ActiveRunIDs()))
			case <-stop:
				return
			case <-ctx.Done():
				_ = ws.Close()
				return
			}
		}
	}()

	for {
		_ = ws.SetReadDeadline(time.Now().Add(readTimeout))
		_, data, err := ws.ReadMessage()
		if err != nil {
			if ce, ok := err.(*websocket.CloseError); ok {
				logf("Сервер закрыл соединение: %d %s", ce.Code, ce.Text)
			} else {
				logf("Ошибка соединения: %v", err)
			}
			return
		}
		received := time.Now().UnixMilli()
		if c.onMessage(data, received) { // true - нужно переподключиться (например, после регистрации)
			return
		}
	}
}

func (c *Conn) sendHelloAndReplayLocked(ws *websocket.Conn) error {
	helloBytes, err := json.Marshal(Hello{Type: "HELLO", ProtocolVersion: protocolVersion, AgentVersion: version, K6Version: c.k6Version(),
		AgentTime: time.Now().UnixMilli(), LastAckedSeq: c.journal.AckedSeq(), LastSeq: c.journal.LastSeq(), Runs: c.runs.AgentRuns()})
	if err != nil {
		return err
	}
	c.mu.Lock()
	defer c.mu.Unlock()
	if err := c.writeLocked(ws, helloBytes); err != nil {
		return err
	}
	for _, e := range c.journal.Unacked() {
		if err := c.writeLocked(ws, e); err != nil {
			return err
		}
	}
	c.ws = ws
	return nil
}

// onMessage возвращает true, если соединение нужно закрыть.
func (c *Conn) onMessage(data []byte, receivedAt int64) bool {
	typ, err := decodeType(data)
	if err != nil {
		logf("Получено некорректное сообщение: %v", err)
		return false
	}
	switch typ {
	case "REGISTERED":
		var r Registered
		_ = json.Unmarshal(data, &r)
		if err := c.cfg.SavePermanent(r.PermanentToken); err != nil {
			logf("Не удалось сохранить постоянный токен, остаёмся на одноразовом для повтора: %v", err)
			return false
		}
		logf("Агент зарегистрирован на сервере, переподключаемся с постоянным токеном")
		// Переподключение доказывает серверу, что постоянный токен сохранён и работает; только после этого сервер гасит одноразовый.
		c.mu.Lock()
		if c.ws != nil {
			_ = c.ws.WriteControl(websocket.CloseMessage, websocket.FormatCloseMessage(websocket.CloseNormalClosure, "re-authenticate"),
				time.Now().Add(2*time.Second))
		}
		c.mu.Unlock()
		return true
	case "ACK":
		var a Ack
		_ = json.Unmarshal(data, &a)
		c.journal.Ack(a.UpToSeq)
	case "PING":
		var p Ping
		_ = json.Unmarshal(data, &p)
		c.sendJSON(Pong{Type: "PONG", PingID: p.PingID, T1: p.T1, T2: receivedAt, T3: time.Now().UnixMilli()})
	case "PREPARE", "START", "STOP", "ABORT", "PAUSE", "RESUME", "SCALE_VUS", "CANCEL_PREPARE":
		go c.handleCommand(typ, data)
	default:
		debugf("Игнорируем сообщение %s", typ)
	}
	return false
}

func (c *Conn) handleCommand(typ string, data []byte) {
	var base struct {
		CmdID string `json:"cmdId"`
	}
	_ = json.Unmarshal(data, &base)
	c.recMu.Lock()
	if c.recSet[base.CmdID] {
		c.recMu.Unlock()
		c.sendJSON(ackOK(base.CmdID)) // повторная доставка: команду не выполняем, подтверждаем успешно
		return
	}
	c.recSet[base.CmdID] = true
	c.recent = append(c.recent, base.CmdID)
	for len(c.recent) > 1000 {
		delete(c.recSet, c.recent[0])
		c.recent = c.recent[1:]
	}
	c.recMu.Unlock()
	c.sendJSON(c.runs.HandleCommand(typ, data))
}

var k6VersionRe = regexp.MustCompile(`k6(?:\.exe)?\s+v?(\S+)`)

// k6Version - версия установленного k6 (кеш на 60 секунд); nil, если k6 не установлен.
func (c *Conn) k6Version() *string {
	c.k6mu.Lock()
	defer c.k6mu.Unlock()
	if time.Since(c.k6checked) < 60*time.Second {
		return c.k6ver
	}
	c.k6checked = time.Now()
	c.k6ver = nil
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	out, err := exec.CommandContext(ctx, c.cfg.K6Path, "version").CombinedOutput()
	if err != nil && len(out) == 0 {
		return nil
	}
	line := strings.TrimSpace(strings.SplitN(string(out), "\n", 2)[0])
	if line == "" {
		return nil
	}
	v := line
	if m := k6VersionRe.FindStringSubmatch(line); m != nil {
		v = m[1]
	}
	c.k6ver = &v
	return c.k6ver
}
