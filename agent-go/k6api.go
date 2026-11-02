package main

import (
	"bytes"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"time"
)

// REST API k6 (--address 127.0.0.1:port): статус, счётчики и управление (pause/resume/scale).
type K6Api struct {
	base string
	http *http.Client
}

type K6State struct {
	Running bool
	Paused  bool
	Vus     int64
	VusMax  int64
}

func NewK6Api(port int) *K6Api {
	return &K6Api{base: fmt.Sprintf("http://127.0.0.1:%d", port), http: &http.Client{Timeout: 3 * time.Second}}
}

func (a *K6Api) get(path string, out any) error {
	r, err := a.http.Get(a.base + path)
	if err != nil {
		return err
	}
	defer r.Body.Close()
	if r.StatusCode != 200 {
		return fmt.Errorf("k6 API %s: HTTP %d", path, r.StatusCode)
	}
	return json.NewDecoder(r.Body).Decode(out)
}

func (a *K6Api) Status() (K6State, error) {
	var v struct {
		Data struct {
			Attributes struct {
				Running bool  `json:"running"`
				Paused  bool  `json:"paused"`
				Vus     int64 `json:"vus"`
				VusMax  int64 `json:"vus-max"`
			} `json:"attributes"`
		} `json:"data"`
	}
	if err := a.get("/v1/status", &v); err != nil {
		return K6State{}, err
	}
	t := v.Data.Attributes
	return K6State{Running: t.Running, Paused: t.Paused, Vus: t.Vus, VusMax: t.VusMax}, nil
}

// Counter - накопительное значение счётчика (count) либо 0, если метрики ещё нет.
func (a *K6Api) Counter(metric string) int64 {
	var v struct {
		Data struct {
			Attributes struct {
				Sample struct {
					Count float64 `json:"count"`
				} `json:"sample"`
			} `json:"attributes"`
		} `json:"data"`
	}
	if err := a.get("/v1/metrics/"+metric, &v); err != nil {
		return 0
	}
	return int64(v.Data.Attributes.Sample.Count)
}

func (a *K6Api) patch(body string) error {
	req, _ := http.NewRequest(http.MethodPatch, a.base+"/v1/status", bytes.NewBufferString(body))
	req.Header.Set("Content-Type", "application/json")
	c := &http.Client{Timeout: 5 * time.Second}
	r, err := c.Do(req)
	if err != nil {
		return err
	}
	defer r.Body.Close()
	if r.StatusCode >= 300 {
		b, _ := io.ReadAll(r.Body)
		return fmt.Errorf("k6 API PATCH: HTTP %d %s", r.StatusCode, string(b))
	}
	return nil
}

func (a *K6Api) Pause(paused bool) error {
	return a.patch(fmt.Sprintf(`{"data":{"type":"status","id":"default","attributes":{"paused":%t}}}`, paused))
}

func (a *K6Api) ScaleVus(vus int) error {
	return a.patch(fmt.Sprintf(`{"data":{"type":"status","id":"default","attributes":{"vus":%d}}}`, vus))
}
