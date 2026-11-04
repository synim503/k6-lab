package main

import (
	"bytes"
	"encoding/binary"
	"io"
	"math"
	"net/http"
	"net/http/httptest"
	"sort"
	"testing"

	"github.com/golang/snappy"
)

// ---- помощники теста: сборка native histogram из «наблюдений» и запроса remote write

func nativeFromValues(vals []float64, schema int32) NativeHistogram {
	counts := map[int64]float64{}
	sum := 0.0
	for _, v := range vals {
		idx := int64(math.Ceil(math.Log2(v) * math.Pow(2, float64(schema))))
		counts[idx]++
		sum += v
	}
	idxs := make([]int64, 0, len(counts))
	for i := range counts {
		idxs = append(idxs, i)
	}
	sort.Slice(idxs, func(i, j int) bool { return idxs[i] < idxs[j] })
	h := NativeHistogram{Count: float64(len(vals)), Sum: sum, Schema: schema, Ts: 1000}
	// спаны: непрерывные участки индексов
	prev := int64(0)
	for i := 0; i < len(idxs); {
		j := i
		for j+1 < len(idxs) && idxs[j+1] == idxs[j]+1 {
			j++
		}
		h.PosSpans = append(h.PosSpans, BucketSpan{Offset: int32(idxs[i] - prev), Length: uint32(j - i + 1)})
		for k := i; k <= j; k++ {
			h.PosCounts = append(h.PosCounts, counts[idxs[k]])
		}
		prev = idxs[j] + 1
		i = j + 1
	}
	return h
}

func appendSint(w *pbWriter, v int64) { w.varint(uint64((v << 1) ^ (v >> 63))) }

// encodeNative кодирует TimeSeries с native histogram так же, как отправляет k6 (целые счётчики с дельтами).
func encodeNative(labels []Label, h NativeHistogram) []byte {
	var hw pbWriter
	hw.key(1, 0)
	hw.varint(uint64(h.Count))
	hw.key(3, 1)
	hw.b = binary.LittleEndian.AppendUint64(hw.b, math.Float64bits(h.Sum))
	hw.key(4, 0)
	appendSint(&hw, int64(h.Schema))
	for _, sp := range h.PosSpans {
		var sw pbWriter
		sw.key(1, 0)
		appendSint(&sw, int64(sp.Offset))
		sw.key(2, 0)
		sw.varint(uint64(sp.Length))
		hw.bytes(11, sw.b)
	}
	var dw pbWriter
	prev := int64(0)
	for _, c := range h.PosCounts {
		appendSint(&dw, int64(c)-prev)
		prev = int64(c)
	}
	hw.bytes(12, dw.b)
	hw.key(15, 0)
	hw.varint(uint64(h.Ts))

	var tw pbWriter
	for _, l := range labels {
		var lw pbWriter
		lw.str(1, l.Name)
		lw.str(2, l.Value)
		tw.bytes(1, lw.b)
	}
	tw.bytes(4, hw.b)
	var req pbWriter
	req.bytes(1, tw.b)
	return req.b
}

// quantile - как histogram_quantile для классических корзин (линейная интерполяция внутри корзины).
func quantile(q float64, cum []float64, total float64) float64 {
	rank := q * total
	prevB, prevC := 0.0, 0.0
	for i, c := range cum {
		if c >= rank {
			if c == prevC {
				return leBounds[i]
			}
			return prevB + (leBounds[i]-prevB)*(rank-prevC)/(c-prevC)
		}
		prevB, prevC = leBounds[i], c
	}
	return leBounds[len(leBounds)-1]
}

func TestNativeHistogramIsConvertedToClassicBucketsWithCorrectPercentiles(t *testing.T) {
	var vals []float64
	for i := 0; i < 900; i++ {
		vals = append(vals, 0.05+float64(i%100)*0.001) // 50..149 мс
	}
	for i := 0; i < 100; i++ {
		vals = append(vals, 1.0+float64(i)*0.02) // 1..3 с
	}
	for _, schema := range []int32{3, 2, 1} { // k6 может уменьшать схему при большом числе корзин
		h := nativeFromValues(vals, schema)
		body := encodeNative([]Label{{"__name__", "k6_op_duration_seconds"}, {"scenario", "pay"}, {"test_run_id", "7"}}, h)
		series, err := parseWriteRequest(body)
		if err != nil || len(series) != 1 || len(series[0].Histograms) != 1 {
			t.Fatalf("разбор: %v %+v", err, series)
		}
		got := series[0].Histograms[0]
		if got.Count != 1000 || got.Schema != schema || len(got.PosCounts) != len(h.PosCounts) {
			t.Fatalf("гистограмма разобрана неверно: %+v", got)
		}
		out, n := convertSeries(series, nil)
		if n != 1 {
			t.Fatalf("n=%d", n)
		}
		cum := bucketsToClassic(got)
		for i := 1; i < len(cum); i++ {
			if cum[i] < cum[i-1] {
				t.Fatalf("накопительные счётчики должны не убывать: %v", cum)
			}
		}
		if cum[len(cum)-1] != 1000 {
			t.Fatalf("в сетке должны уместиться все наблюдения (до 128 с): %v", cum[len(cum)-1])
		}
		sorted := append([]float64(nil), vals...)
		sort.Float64s(sorted)
		for _, q := range []float64{0.5, 0.9, 0.95, 0.99} {
			want := sorted[int(q*float64(len(sorted)))-1]
			have := quantile(q, cum, 1000)
			tol := 0.12 * math.Pow(2, float64(3-schema)) // допуск растёт с укрупнением схемы
			if math.Abs(have-want)/want > tol {
				t.Errorf("schema %d: p%.0f = %.4f, ожидалось ~%.4f (допуск %.0f%%)", schema, q*100, have, want, tol*100)
			}
		}
		// состав серий: bucket x (сетка + Inf), count, sum; метки сохранены
		var buckets, count, sum int
		for _, ts := range out {
			var name string
			hasRun := false
			for _, l := range ts.Labels {
				if l.Name == "__name__" {
					name = l.Value
				}
				if l.Name == "test_run_id" && l.Value == "7" {
					hasRun = true
				}
			}
			if !hasRun {
				t.Fatalf("потеряна метка test_run_id: %+v", ts.Labels)
			}
			switch name {
			case "k6_op_duration_seconds_bucket":
				buckets++
			case "k6_op_duration_seconds_count":
				count++
			case "k6_op_duration_seconds_sum":
				sum++
			}
		}
		if buckets != len(leBounds)+1 || count != 1 || sum != 1 {
			t.Fatalf("серии: bucket=%d count=%d sum=%d", buckets, count, sum)
		}
	}
}

func TestProxyConvertsAndForwardsWithHeadersAndPassesPlainSeriesThrough(t *testing.T) {
	var gotBody []byte
	var gotAuth, gotVer, gotEnc string
	up := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		gotAuth, gotVer, gotEnc = r.Header.Get("Authorization"), r.Header.Get("X-Prometheus-Remote-Write-Version"), r.Header.Get("Content-Encoding")
		raw, _ := io.ReadAll(r.Body)
		gotBody, _ = snappy.Decode(nil, raw)
		w.WriteHeader(http.StatusNoContent)
	}))
	defer up.Close()
	proxy, err := StartRWProxy(up.URL+"/api/v1/write", map[string]string{"Authorization": "Bearer secret"})
	if err != nil {
		t.Fatal(err)
	}
	defer proxy.Close()

	h := nativeFromValues([]float64{0.01, 0.02, 0.5}, 3)
	body := encodeNative([]Label{{"__name__", "k6_http_req_duration_seconds"}, {"test_run_id", "1"}}, h)
	// плюс обычный счётчик в том же запросе
	var plain pbWriter
	var ts pbWriter
	for _, l := range []Label{{"__name__", "k6_iterations_total"}, {"test_run_id", "1"}} {
		var lw pbWriter
		lw.str(1, l.Name)
		lw.str(2, l.Value)
		ts.bytes(1, lw.b)
	}
	var sw pbWriter
	sw.key(1, 1)
	sw.b = binary.LittleEndian.AppendUint64(sw.b, math.Float64bits(42))
	sw.key(2, 0)
	sw.varint(1000)
	ts.bytes(2, sw.b)
	plain.bytes(1, ts.b)
	body = append(body, plain.b...)

	req, _ := http.NewRequest(http.MethodPost, proxy.URL, bytes.NewReader(snappy.Encode(nil, body)))
	resp, err := http.DefaultClient.Do(req)
	if err != nil || resp.StatusCode != http.StatusNoContent {
		t.Fatalf("ответ приёмника: %v %v", resp, err)
	}
	if gotAuth != "Bearer secret" || gotVer != "0.1.0" || gotEnc != "snappy" {
		t.Fatalf("заголовки: %q %q %q", gotAuth, gotVer, gotEnc)
	}
	series, err := parseWriteRequest(gotBody)
	if err != nil {
		t.Fatal(err)
	}
	var names = map[string]int{}
	for _, s := range series {
		if len(s.Histograms) != 0 {
			t.Fatal("в Prometheus не должны уходить native histograms")
		}
		for _, l := range s.Labels {
			if l.Name == "__name__" {
				names[l.Value]++
			}
		}
	}
	if names["k6_http_req_duration_seconds_bucket"] != len(leBounds)+1 || names["k6_http_req_duration_seconds_count"] != 1 || names["k6_iterations_total"] != 1 {
		t.Fatalf("состав серий: %v", names)
	}
	if proxy.Converted.Load() != 1 || proxy.Forwarded.Load() != 1 {
		t.Fatalf("счётчики: %d %d", proxy.Converted.Load(), proxy.Forwarded.Load())
	}
	// метки отсортированы (требование Prometheus)
	for _, s := range series {
		for i := 1; i < len(s.Labels); i++ {
			if s.Labels[i-1].Name > s.Labels[i].Name {
				t.Fatalf("метки не отсортированы: %v", s.Labels)
			}
		}
	}
}

func TestProxyPropagatesUpstreamErrors(t *testing.T) {
	up := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		http.Error(w, "out of order sample", http.StatusBadRequest)
	}))
	defer up.Close()
	proxy, _ := StartRWProxy(up.URL, nil)
	defer proxy.Close()
	body := encodeNative([]Label{{"__name__", "m"}}, nativeFromValues([]float64{0.1}, 3))
	resp, err := http.Post(proxy.URL, "application/x-protobuf", bytes.NewReader(snappy.Encode(nil, body)))
	if err != nil || resp.StatusCode != http.StatusBadRequest || proxy.Failed.Load() != 1 {
		t.Fatalf("%v %v %d", resp, err, proxy.Failed.Load())
	}
	// мусор вместо snappy - 400, а не паника
	resp, _ = http.Post(proxy.URL, "application/x-protobuf", bytes.NewReader([]byte("не snappy")))
	if resp.StatusCode != http.StatusBadRequest {
		t.Fatal(resp.StatusCode)
	}
}
