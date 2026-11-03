package main

import (
	"encoding/json"
	"fmt"
	"math"
	"net/http"
	"net/url"
	"os"
	"os/exec"
	"path/filepath"
	"strconv"
	"strings"
	"testing"
	"time"
)

// Проверка на настоящих k6 и Prometheus (БЕЗ --enable-feature=native-histograms): k6 -> приёмник агента -> Prometheus.
// Запуск: K6_BIN=путь\к\k6 PROM_URL=http://127.0.0.1:9095 go test -run RealK6 -v
func TestRealK6ThroughProxyToPlainPrometheus(t *testing.T) {
	k6bin, promURL := os.Getenv("K6_BIN"), os.Getenv("PROM_URL")
	if k6bin == "" || promURL == "" {
		t.Skip("нужны K6_BIN и PROM_URL")
	}
	dir := t.TempDir()
	script := filepath.Join(dir, "t.js")
	_ = os.WriteFile(script, []byte(`
import { sleep } from 'k6';
export const options = { scenarios: { work: { executor: 'constant-arrival-rate', rate: 20, timeUnit: '1s', duration: '12s', preAllocatedVUs: 5 } } };
export default function () { sleep(0.03 + Math.random() * 0.05); }
`), 0o644)
	proxy, err := StartRWProxy(promURL+"/api/v1/write", nil)
	if err != nil {
		t.Fatal(err)
	}
	defer proxy.Close()
	runID := fmt.Sprintf("real-%d", time.Now().Unix())
	cmd := exec.Command(k6bin, "run", "--quiet", "--out", "experimental-prometheus-rw", "--tag", "test_run_id="+runID, script)
	cmd.Env = append(os.Environ(), "K6_PROMETHEUS_RW_SERVER_URL="+proxy.URL, "K6_PROMETHEUS_RW_TREND_AS_NATIVE_HISTOGRAM=true",
		"K6_PROMETHEUS_RW_PUSH_INTERVAL=2s", "K6_NO_USAGE_REPORT=true", "K6_AUTO_EXTENSION_RESOLUTION=false")
	if out, err := cmd.CombinedOutput(); err != nil {
		t.Fatalf("k6: %v\n%s", err, out)
	}
	time.Sleep(4 * time.Second)
	t.Logf("приёмник: преобразовано гистограмм %d, запросов %d, ошибок %d", proxy.Converted.Load(), proxy.Forwarded.Load(), proxy.Failed.Load())
	if proxy.Converted.Load() == 0 || proxy.Forwarded.Load() == 0 || proxy.Failed.Load() != 0 {
		t.Fatalf("k6 не прислал native histograms либо Prometheus отклонил запрос")
	}

	q := func(expr string) []map[string]any {
		resp, err := http.Get(promURL + "/api/v1/query?query=" + url.QueryEscape(expr) + "&time=" + strconv.FormatInt(time.Now().Unix(), 10))
		if err != nil {
			t.Fatal(err)
		}
		defer resp.Body.Close()
		var d struct {
			Data struct {
				Result []map[string]any `json:"result"`
			} `json:"data"`
		}
		_ = json.NewDecoder(resp.Body).Decode(&d)
		return d.Data.Result
	}
	// те же запросы, что строит админка в классическом режиме
	names := q(fmt.Sprintf(`count by (__name__) ({test_run_id=%q})`, runID))
	t.Logf("серий с тегом: %d", len(names))
	res := q(fmt.Sprintf(`histogram_quantile(0.95, sum by (scenario, le) (increase(k6_iteration_duration_seconds_bucket{test_run_id=%q}[1m]))) * 1000`, runID))
	if len(res) == 0 {
		t.Fatalf("histogram_quantile по классическим корзинам ничего не вернул; метрики: %v", names)
	}
	val := res[0]["value"].([]any)[1].(string)
	p95, _ := strconv.ParseFloat(val, 64)
	t.Logf("p95 по классическим корзинам = %.1f мс", p95)
	// sleep 30..80 мс + накладные расходы: p95 должен быть в разумных пределах (с учётом шага корзин ~9%)
	if math.IsNaN(p95) || p95 < 60 || p95 > 140 {
		t.Fatalf("p95 = %.1f мс вне ожидаемого диапазона 60..140", p95)
	}
	cnt := q(fmt.Sprintf(`sum(increase(k6_iteration_duration_seconds_count{test_run_id=%q}[1m]))`, runID))
	if len(cnt) == 0 || !strings.Contains(fmt.Sprint(cnt[0]["value"]), ".") && false {
		t.Fatalf("нет _count")
	}
}
