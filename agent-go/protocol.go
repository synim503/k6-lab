package main

import (
	"encoding/json"
	"fmt"
)

// Протокол агент <-> сервер: JSON, полиморфизм по полю "type". Совместим с Java-реализацией (admin-core/protocol).

const protocolVersion = 1

const (
	stPreparing = "PREPARING"
	stPrepared  = "PREPARED"
	stRunning   = "RUNNING"
	stStopping  = "STOPPING"
	stExited    = "EXITED"
)

type AgentRun struct {
	RunID    string `json:"runId"`
	State    string `json:"state"`
	Pid      *int64 `json:"pid"`
	ExitCode *int   `json:"exitCode"`
}

type LogLine struct {
	Ts     int64  `json:"ts"`
	Stream string `json:"stream"`
	Line   string `json:"line"`
}

type PrometheusParams struct {
	URL                   string            `json:"url"`
	Headers               map[string]string `json:"headers"`
	PushInterval          string            `json:"pushInterval"`
	TrendAsNativeHistogram bool             `json:"trendAsNativeHistogram"`
}

type DataRule struct {
	Path      string `json:"path"`
	Mode      string `json:"mode"`
	HasHeader bool   `json:"hasHeader"`
}

// ---- агент -> сервер

type Hello struct {
	Type            string     `json:"type"`
	ProtocolVersion int        `json:"protocolVersion"`
	AgentVersion    string     `json:"agentVersion"`
	K6Version       *string    `json:"k6Version"`
	AgentTime       int64      `json:"agentTime"`
	LastAckedSeq    int64      `json:"lastAckedSeq"`
	LastSeq         int64      `json:"lastSeq"`
	Runs            []AgentRun `json:"runs"`
}

type Heartbeat struct {
	Type              string   `json:"type"`
	AgentTime         int64    `json:"agentTime"`
	CPUPct            float64  `json:"cpuPct"`
	RAMUsedBytes      int64    `json:"ramUsedBytes"`
	RAMTotalBytes     int64    `json:"ramTotalBytes"`
	NetRxBytesPerSec  int64    `json:"netRxBytesPerSec"`
	NetTxBytesPerSec  int64    `json:"netTxBytesPerSec"`
	Load1             float64  `json:"load1"`
	Load5             float64  `json:"load5"`
	Load15            float64  `json:"load15"`
	DiskFreeBytes     int64    `json:"diskFreeBytes"`
	OpenSockets       int      `json:"openSockets"`
	K6Version         *string  `json:"k6Version"`
	AgentVersion      string   `json:"agentVersion"`
	ActiveRuns        []string `json:"activeRuns"`
}

type Pong struct {
	Type   string `json:"type"`
	PingID string `json:"pingId"`
	T1     int64  `json:"t1"`
	T2     int64  `json:"t2"`
	T3     int64  `json:"t3"`
}

type CmdAck struct {
	Type  string  `json:"type"`
	CmdID string  `json:"cmdId"`
	Ok    bool    `json:"ok"`
	Error *string `json:"error"`
}

func ackOK(cmdID string) CmdAck { return CmdAck{Type: "CMD_ACK", CmdID: cmdID, Ok: true} }
func ackErr(cmdID, msg string) CmdAck {
	return CmdAck{Type: "CMD_ACK", CmdID: cmdID, Ok: false, Error: &msg}
}

type Prepared struct {
	Type  string `json:"type"`
	Seq   int64  `json:"seq"`
	Ts    int64  `json:"ts"`
	RunID string `json:"runId"`
}

type PrepareFailed struct {
	Type   string `json:"type"`
	Seq    int64  `json:"seq"`
	Ts     int64  `json:"ts"`
	RunID  string `json:"runId"`
	Reason string `json:"reason"`
}

type Status struct {
	Type  string `json:"type"`
	Seq   int64  `json:"seq"`
	Ts    int64  `json:"ts"`
	RunID string `json:"runId"`
	State string `json:"state"`
	Pid   *int64 `json:"pid"`
}

type LogBatch struct {
	Type    string    `json:"type"`
	Seq     int64     `json:"seq"`
	Ts      int64     `json:"ts"`
	RunID   string    `json:"runId"`
	Lines   []LogLine `json:"lines"`
	Dropped int64     `json:"dropped"`
}

type K6Status struct {
	Type              string `json:"type"`
	Seq               int64  `json:"seq"`
	Ts                int64  `json:"ts"`
	RunID             string `json:"runId"`
	Vus               int64  `json:"vus"`
	VusMax            int64  `json:"vusMax"`
	Iterations        int64  `json:"iterations"`
	DroppedIterations int64  `json:"droppedIterations"`
	Status            string `json:"status"`
	Paused            bool   `json:"paused"`
	Running           bool   `json:"running"`
}

type Summary struct {
	Type    string          `json:"type"`
	Seq     int64           `json:"seq"`
	Ts      int64           `json:"ts"`
	RunID   string          `json:"runId"`
	Summary json.RawMessage `json:"summary"`
}

type Exit struct {
	Type   string  `json:"type"`
	Seq    int64   `json:"seq"`
	Ts     int64   `json:"ts"`
	RunID  string  `json:"runId"`
	Code   int     `json:"code"`
	Signal *string `json:"signal"`
}

// ---- сервер -> агент

type Registered struct {
	PermanentToken string `json:"permanentToken"`
	AgentID        string `json:"agentId"`
}

type Ack struct {
	UpToSeq    int64 `json:"upToSeq"`
	ServerTime int64 `json:"serverTime"`
}

type Ping struct {
	PingID string `json:"pingId"`
	T1     int64  `json:"t1"`
}

type Prepare struct {
	CmdID                    string            `json:"cmdId"`
	RunID                    string            `json:"runId"`
	ArtifactURL              string            `json:"artifactUrl"`
	ArtifactSha256           string            `json:"artifactSha256"`
	K6Config                 json.RawMessage   `json:"k6Config"`
	Env                      map[string]string `json:"env"`
	Secrets                  map[string]string `json:"secrets"`
	ExecutionSegment         string            `json:"executionSegment"`
	ExecutionSegmentSequence string            `json:"executionSegmentSequence"`
	Tags                     map[string]string `json:"tags"`
	DataRules                []DataRule        `json:"dataRules"`
	Prometheus               *PrometheusParams `json:"prometheus"`
	K6ApiPort                int               `json:"k6ApiPort"`
	SystemTags               []string          `json:"systemTags"`
	Entrypoint               string            `json:"entrypoint"`
	LogLinesPerSecond        int               `json:"logLinesPerSecond"`
}

type Start struct {
	CmdID   string `json:"cmdId"`
	RunID   string `json:"runId"`
	StartAt int64  `json:"startAt"`
}

type RunCmd struct {
	CmdID string `json:"cmdId"`
	RunID string `json:"runId"`
	Vus   int    `json:"vus"`
}

// decodeType читает значение поля type.
func decodeType(raw []byte) (string, error) {
	var t struct {
		Type string `json:"type"`
	}
	if err := json.Unmarshal(raw, &t); err != nil {
		return "", fmt.Errorf("некорректное сообщение протокола: %w", err)
	}
	return t.Type, nil
}

func strp(s string) *string { return &s }
