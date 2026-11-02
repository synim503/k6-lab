package main

import (
	"context"
	"fmt"
	"io"
	"log"
	"math/big"
	"os"
	"os/signal"
	"path/filepath"
	"syscall"
)

var (
	logger  = log.New(os.Stderr, "", log.LstdFlags|log.Lmicroseconds)
	verbose = false
)

func logf(format string, a ...any)   { logger.Printf("INFO  "+format, a...) }
func debugf(format string, a ...any) {
	if verbose {
		logger.Printf("DEBUG "+format, a...)
	}
}

func bigInt(n int64) *big.Int { return big.NewInt(n) }

func main() {
	if len(os.Args) > 1 && (os.Args[1] == "--version" || os.Args[1] == "version") {
		fmt.Println("k6-lab-agent", version)
		return
	}
	cfg, err := loadConfig(os.Args[1:])
	if err != nil {
		fmt.Fprintln(os.Stderr, "Ошибка конфигурации:", err)
		os.Exit(2)
	}
	setupLogging(cfg)
	journal, err := NewJournal(filepath.Join(cfg.Home, "data"), cfg.JournalMaxBytes)
	if err != nil {
		logf("Не удалось открыть журнал событий: %v", err)
		os.Exit(1)
	}
	var conn *Conn
	runs := NewRunManager(cfg, func(factory func(seq int64) any) {
		raw := journal.Append(factory)
		if conn != nil {
			conn.Publish(raw)
		}
	})
	conn = NewConn(cfg, journal, runs, NewSystemMetrics(cfg.Home))

	ctx, cancel := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer cancel()
	conn.Run(ctx)
	runs.Shutdown()
}

// setupLogging: в stderr (journald под systemd) и, если задан logging.file.name, в файл с усечением при > 10 МБ.
func setupLogging(cfg *Config) {
	verbose = os.Getenv("AGENT_DEBUG") != ""
	if cfg.LogFile == "" {
		return
	}
	if err := os.MkdirAll(filepath.Dir(cfg.LogFile), 0o755); err != nil {
		return
	}
	if st, err := os.Stat(cfg.LogFile); err == nil && st.Size() > 10<<20 {
		_ = os.Rename(cfg.LogFile, cfg.LogFile+".1")
	}
	f, err := os.OpenFile(cfg.LogFile, os.O_CREATE|os.O_APPEND|os.O_WRONLY, 0o644)
	if err != nil {
		return
	}
	logger.SetOutput(io.MultiWriter(os.Stderr, f))
}
