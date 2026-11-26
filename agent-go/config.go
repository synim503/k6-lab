package main

import (
	"bufio"
	"fmt"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"sync"
	"time"
)

// Config - настройки агента. Источники (по возрастанию приоритета): agent.properties в home, аргументы --agent.key=value.
type Config struct {
	Home              string
	ServerURL         string
	Token             string
	AgentID           string
	K6Path            string
	HeartbeatInterval time.Duration
	ReconnectMin      time.Duration
	ReconnectMax      time.Duration
	JournalMaxBytes   int64
	LogFile           string

	mu sync.Mutex
}

func defaultConfig() *Config {
	home, _ := os.Getwd()
	return &Config{
		Home: home, AgentID: "unknown", HeartbeatInterval: 5 * time.Second,
		ReconnectMin: time.Second, ReconnectMax: 60 * time.Second, JournalMaxBytes: 256 << 20,
	}
}

func parseArgs(args []string) map[string]string {
	m := map[string]string{}
	for _, a := range args {
		if !strings.HasPrefix(a, "--") {
			continue
		}
		kv := strings.TrimPrefix(a, "--")
		if i := strings.Index(kv, "="); i > 0 {
			m[kv[:i]] = kv[i+1:]
		}
	}
	return m
}

func readProps(path string) map[string]string {
	m := map[string]string{}
	f, err := os.Open(path)
	if err != nil {
		return m
	}
	defer f.Close()
	sc := bufio.NewScanner(f)
	for sc.Scan() {
		l := strings.TrimSpace(sc.Text())
		if l == "" || strings.HasPrefix(l, "#") || strings.HasPrefix(l, "!") {
			continue
		}
		if i := strings.IndexAny(l, "=:"); i > 0 {
			m[strings.TrimSpace(l[:i])] = strings.TrimSpace(l[i+1:])
		}
	}
	return m
}

func loadConfig(args []string) (*Config, error) {
	cli := parseArgs(args)
	c := defaultConfig()
	if h := cli["agent.home"]; h != "" {
		c.Home = h
	}
	abs, err := filepath.Abs(c.Home)
	if err != nil {
		return nil, err
	}
	c.Home = abs
	props := readProps(filepath.Join(c.Home, "agent.properties"))
	for k, v := range cli {
		props[k] = v
	}
	c.ServerURL = props["agent.server-url"]
	c.Token = props["agent.token"]
	if v := props["agent.agent-id"]; v != "" {
		c.AgentID = v
	}
	c.K6Path = props["agent.k6-path"]
	if c.K6Path == "" {
		name := "k6"
		if isWindows() {
			name = "k6.exe"
		}
		c.K6Path = filepath.Join(c.Home, "bin", name)
	}
	c.LogFile = props["logging.file.name"]
	var perr error
	dur := func(key string, dst *time.Duration) {
		if v := props[key]; v != "" {
			d, e := parseDuration(v)
			if e != nil {
				perr = fmt.Errorf("%s: %w", key, e)
				return
			}
			*dst = d
		}
	}
	dur("agent.heartbeat-interval", &c.HeartbeatInterval)
	dur("agent.reconnect-min", &c.ReconnectMin)
	dur("agent.reconnect-max", &c.ReconnectMax)
	if v := props["agent.journal-max-bytes"]; v != "" {
		if n, e := strconv.ParseInt(v, 10, 64); e == nil {
			c.JournalMaxBytes = n
		} else if perr == nil {
			perr = fmt.Errorf("agent.journal-max-bytes: %w", e)
		}
	}
	if perr == nil {
		if c.HeartbeatInterval <= 0 {
			perr = fmt.Errorf("agent.heartbeat-interval должен быть положительным")
		} else if c.ReconnectMin <= 0 {
			perr = fmt.Errorf("agent.reconnect-min должен быть положительным")
		} else if c.ReconnectMax <= 0 {
			perr = fmt.Errorf("agent.reconnect-max должен быть положительным")
		} else {
			if c.ReconnectMax < c.ReconnectMin {
				c.ReconnectMax = c.ReconnectMin
			}
			if c.JournalMaxBytes < 1<<20 {
				c.JournalMaxBytes = 1 << 20
			}
		}
	}
	return c, perr
}

// parseDuration понимает "5s", "500ms", ISO-8601 вида PT5S (формат Spring) и просто число секунд.
func parseDuration(s string) (time.Duration, error) {
	s = strings.TrimSpace(s)
	if strings.HasPrefix(strings.ToUpper(s), "PT") {
		s = strings.ToLower(s[2:])
	}
	if n, err := strconv.ParseFloat(s, 64); err == nil {
		return time.Duration(n * float64(time.Second)), nil
	}
	return time.ParseDuration(s)
}

func (c *Config) CurrentToken() string {
	c.mu.Lock()
	defer c.mu.Unlock()
	return c.Token
}

// SavePermanent записывает постоянный токен в agent.properties (атомарно, права 600) и только после успеха обновляет память.
func (c *Config) SavePermanent(token string) error {
	if strings.TrimSpace(token) == "" {
		return fmt.Errorf("получен пустой постоянный токен")
	}
	c.mu.Lock()
	defer c.mu.Unlock()
	path := filepath.Join(c.Home, "agent.properties")
	var out []string
	replaced := false
	fRead, err := os.Open(path)
	if err == nil {
		sc := bufio.NewScanner(fRead)
		for sc.Scan() {
			l := sc.Text()
			if strings.HasPrefix(l, "agent.token=") {
				out = append(out, "agent.token="+token)
				replaced = true
			} else {
				out = append(out, l)
			}
		}
		scanErr := sc.Err()
		_ = fRead.Close()
		if scanErr != nil {
			return fmt.Errorf("read %s: %w", path, scanErr)
		}
	} else if !os.IsNotExist(err) {
		return fmt.Errorf("open %s: %w", path, err)
	}
	if !replaced {
		out = append(out, "agent.token="+token)
	}
	tmp := path + ".tmp"
	f, err := os.OpenFile(tmp, os.O_CREATE|os.O_WRONLY|os.O_TRUNC, 0o600)
	if err != nil {
		return fmt.Errorf("create tmp: %w", err)
	}
	if _, err := f.WriteString(strings.Join(out, "\n") + "\n"); err != nil {
		f.Close()
		_ = os.Remove(tmp)
		return fmt.Errorf("write tmp: %w", err)
	}
	if err := f.Sync(); err != nil {
		f.Close()
		_ = os.Remove(tmp)
		return fmt.Errorf("sync tmp: %w", err)
	}
	if err := f.Close(); err != nil {
		_ = os.Remove(tmp)
		return fmt.Errorf("close tmp: %w", err)
	}
	if err := os.Rename(tmp, path); err != nil {
		_ = os.Remove(tmp)
		return fmt.Errorf("rename tmp: %w", err)
	}
	_ = os.Chmod(path, 0o600)
	c.Token = token
	logf("Постоянный токен сохранён в %s", path)
	return nil
}
