package main

import (
	"bytes"
	"context"
	"fmt"
	"io"
	"math"
	"net"
	"net/http"
	"path"
	"strconv"
	"sync/atomic"
	"time"

	"github.com/golang/snappy"
)

// Режим «классические гистограммы»: k6 отправляет метрики не в Prometheus, а локальному приёмнику агента. Приёмник заменяет
// native histograms обычными сериями <имя>_bucket{le=...}, <имя>_count, <имя>_sum и пересылает запрос в Prometheus.
// Классические серии принимает любой Prometheus без флагов, а перцентили по ним считаются тем же histogram_quantile
// по суммированным (между генераторами) корзинам - то есть корректно.
//
// Границы le образуют сетку 2^(m/8): они совпадают с границами корзин native histogram любой схемы <= 3 (по умолчанию в k6
// схема 3, фактор ~1.09), поэтому пересчёт точный, без размазывания счётчиков между корзинами.

const (
	gridMin = -112 // 2^-14 ~ 61 мкс (значения меньше попадают в первую корзину)
	gridMax = 56   // 2^7 = 128 с (больше - только в +Inf)
)

var leBounds = func() []float64 {
	out := make([]float64, 0, gridMax-gridMin+1)
	for m := gridMin; m <= gridMax; m++ {
		out = append(out, math.Pow(2, float64(m)/8))
	}
	return out
}()

var leLabels = func() []string {
	out := make([]string, len(leBounds))
	for i, b := range leBounds {
		out[i] = strconv.FormatFloat(b, 'g', 6, 64)
	}
	return out
}()

// bucketsToClassic: накопительные счётчики для сетки le (без +Inf).
func bucketsToClassic(h NativeHistogram) []float64 {
	per := make([]float64, len(leBounds))
	per[0] += h.ZeroCount
	idx := int64(0)
	pos := 0
	for _, sp := range h.PosSpans {
		idx += int64(sp.Offset)
		for k := uint32(0); k < sp.Length && pos < len(h.PosCounts); k++ {
			c := h.PosCounts[pos]
			pos++
			// верхняя граница корзины idx: 2^(idx/2^schema) = 2^(m/8), m = idx*8/2^schema
			m := float64(idx) * 8 / math.Pow(2, float64(h.Schema))
			cell := int(math.Ceil(m-1e-9)) - gridMin
			switch {
			case cell < 0:
				cell = 0
				per[cell] += c
			case cell >= len(per):
				// выше сетки: учитывается только в +Inf (через общий count)
			default:
				per[cell] += c
			}
			idx++
		}
	}
	cum := 0.0
	for i := range per {
		cum += per[i]
		per[i] = cum
	}
	return per
}

// convertSeries заменяет native histograms классическими сериями; остальные ряды возвращает как есть (без exemplars).
// Гистограммы, которых нет в keep, отбрасываются: каждая даёт ~170 серий (сетка le), а админке нужны только перцентили
// времени итерации/операции. keep == nil - оставить все.
func convertSeries(in []TimeSeries, keep map[string]bool) (out []TimeSeries, converted int) {
	return convertSeriesDrop(in, keep, nil)
}

// dropped: подходит ли имя метрики под одну из масок (* - любая последовательность символов).
func dropped(name string, patterns []string) bool {
	for _, p := range patterns {
		if ok, _ := path.Match(p, name); ok {
			return true
		}
	}
	return false
}

// convertSeriesDrop - как convertSeries, но метрики, подходящие под маски drop, не попадают в результат вовсе.
func convertSeriesDrop(in []TimeSeries, keep map[string]bool, drop []string) (out []TimeSeries, converted int) {
	for _, ts := range in {
		if len(ts.Histograms) == 0 {
			if len(ts.Samples) > 0 {
				if len(drop) > 0 {
					skip := false
					for _, l := range ts.Labels {
						if l.Name == "__name__" && dropped(l.Value, drop) {
							skip = true
							break
						}
					}
					if skip {
						continue
					}
				}
				out = append(out, TimeSeries{Labels: ts.Labels, Samples: ts.Samples})
			}
			continue
		}
		name := ""
		var base []Label
		for _, l := range ts.Labels {
			if l.Name == "__name__" {
				name = l.Value
			} else {
				base = append(base, l)
			}
		}
		if keep != nil && !keep[name] {
			continue
		}
		if dropped(name, drop) {
			continue
		}
		for _, h := range ts.Histograms {
			t := h.Ts
			if t == 0 && len(ts.Samples) > 0 {
				t = ts.Samples[0].Ts
			}
			cum := bucketsToClassic(h)
			for i := range cum {
				lbl := append(append([]Label(nil), base...), Label{"__name__", name + "_bucket"}, Label{"le", leLabels[i]})
				out = append(out, TimeSeries{Labels: lbl, Samples: []Sample{{cum[i], t}}})
			}
			inf := append(append([]Label(nil), base...), Label{"__name__", name + "_bucket"}, Label{"le", "+Inf"})
			out = append(out, TimeSeries{Labels: inf, Samples: []Sample{{h.Count, t}}})
			out = append(out, TimeSeries{Labels: append(append([]Label(nil), base...), Label{"__name__", name + "_count"}), Samples: []Sample{{h.Count, t}}})
			out = append(out, TimeSeries{Labels: append(append([]Label(nil), base...), Label{"__name__", name + "_sum"}), Samples: []Sample{{h.Sum, t}}})
			converted++
		}
	}
	return out, converted
}

// RWProxy - локальный приёмник remote write на время одного run.
type RWProxy struct {
	URL       string // куда k6 должен отправлять метрики
	keep      map[string]bool
	drop      []string
	upstream  string
	headers   map[string]string
	srv       *http.Server
	client    *http.Client
	Converted atomic.Int64
	Forwarded atomic.Int64
	Failed    atomic.Int64
}

// keepHistograms - имена гистограмм, которые пересылать (пусто - все).
func StartRWProxy(upstream string, headers map[string]string, keepHistograms ...string) (*RWProxy, error) {
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		return nil, err
	}
	p := &RWProxy{upstream: upstream, headers: headers, client: &http.Client{Timeout: 20 * time.Second}}
	if len(keepHistograms) > 0 {
		p.keep = make(map[string]bool, len(keepHistograms))
		for _, n := range keepHistograms {
			p.keep[n] = true
		}
	}
	p.URL = fmt.Sprintf("http://%s/api/v1/write", ln.Addr().String())
	mux := http.NewServeMux()
	mux.HandleFunc("/", p.handle)
	p.srv = &http.Server{Handler: mux, ReadHeaderTimeout: 10 * time.Second}
	go func() { _ = p.srv.Serve(ln) }()
	return p, nil
}

// SetDrop задаёт маски метрик, которые не пересылаются в Prometheus; вызывать до начала работы k6.
func (p *RWProxy) SetDrop(patterns []string) {
	p.drop = patterns
}

func (p *RWProxy) Close() {
	ctx, cancel := context.WithTimeout(context.Background(), 3*time.Second)
	defer cancel()
	_ = p.srv.Shutdown(ctx)
}

func (p *RWProxy) handle(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
		return
	}
	raw, err := io.ReadAll(io.LimitReader(r.Body, 64<<20))
	if err != nil {
		http.Error(w, err.Error(), http.StatusBadRequest)
		return
	}
	body, err := snappy.Decode(nil, raw)
	if err != nil {
		http.Error(w, "snappy: "+err.Error(), http.StatusBadRequest)
		return
	}
	series, err := parseWriteRequest(body)
	if err != nil {
		http.Error(w, err.Error(), http.StatusBadRequest)
		return
	}
	out, conv := convertSeriesDrop(series, p.keep, p.drop)
	p.Converted.Add(int64(conv))
	if len(out) == 0 {
		w.WriteHeader(http.StatusNoContent)
		return
	}
	payload := snappy.Encode(nil, encodeWriteRequest(out))
	req, _ := http.NewRequestWithContext(r.Context(), http.MethodPost, p.upstream, bytes.NewReader(payload))
	req.Header.Set("Content-Encoding", "snappy")
	req.Header.Set("Content-Type", "application/x-protobuf")
	req.Header.Set("X-Prometheus-Remote-Write-Version", "0.1.0")
	for k, v := range p.headers {
		req.Header.Set(k, v)
	}
	resp, err := p.client.Do(req)
	if err != nil {
		p.Failed.Add(1)
		http.Error(w, "upstream: "+err.Error(), http.StatusBadGateway)
		return
	}
	defer resp.Body.Close()
	msg, _ := io.ReadAll(io.LimitReader(resp.Body, 2048))
	if resp.StatusCode/100 != 2 {
		p.Failed.Add(1)
		logf("remote write в Prometheus отклонён: HTTP %d %s", resp.StatusCode, string(bytes.TrimSpace(msg)))
		// 4xx - данные некорректны (повтор бессмыслен), 5xx - пусть k6 повторит
		w.WriteHeader(resp.StatusCode)
		_, _ = w.Write(msg)
		return
	}
	p.Forwarded.Add(1)
	w.WriteHeader(http.StatusNoContent)
}
