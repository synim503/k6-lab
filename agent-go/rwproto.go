package main

import (
	"encoding/binary"
	"errors"
	"math"
	"sort"
)

// Минимальный разбор и сборка Prometheus remote write (prompb.WriteRequest) без внешних зависимостей: нужны только метки,
// сэмплы и native histograms. Остальные поля (metadata, exemplars) пропускаются.

type Label struct{ Name, Value string }

type Sample struct {
	Value float64
	Ts    int64
}

type BucketSpan struct {
	Offset int32
	Length uint32
}

// NativeHistogram - exponential histogram Prometheus (целочисленный или float).
type NativeHistogram struct {
	Count         float64
	Sum           float64
	Schema        int32
	ZeroThreshold float64
	ZeroCount     float64
	PosSpans      []BucketSpan
	PosCounts     []float64 // абсолютные значения корзин (дельты уже развёрнуты)
	Ts            int64
}

type TimeSeries struct {
	Labels     []Label
	Samples    []Sample
	Histograms []NativeHistogram
}

var errProto = errors.New("некорректный protobuf")

type pbReader struct {
	b []byte
	i int
}

func (r *pbReader) eof() bool { return r.i >= len(r.b) }

func (r *pbReader) varint() (uint64, error) {
	v, n := binary.Uvarint(r.b[r.i:])
	if n <= 0 {
		return 0, errProto
	}
	r.i += n
	return v, nil
}

func (r *pbReader) fixed64() (float64, error) {
	if r.i+8 > len(r.b) {
		return 0, errProto
	}
	v := math.Float64frombits(binary.LittleEndian.Uint64(r.b[r.i:]))
	r.i += 8
	return v, nil
}

func (r *pbReader) bytes() ([]byte, error) {
	n, err := r.varint()
	if err != nil || r.i+int(n) > len(r.b) || n > uint64(len(r.b)) {
		return nil, errProto
	}
	out := r.b[r.i : r.i+int(n)]
	r.i += int(n)
	return out, nil
}

func (r *pbReader) skip(wire uint64) error {
	switch wire {
	case 0:
		_, err := r.varint()
		return err
	case 1:
		r.i += 8
	case 2:
		_, err := r.bytes()
		return err
	case 5:
		r.i += 4
	default:
		return errProto
	}
	if r.i > len(r.b) {
		return errProto
	}
	return nil
}

func zigzag(v uint64) int64 { return int64(v>>1) ^ -int64(v&1) }

// parseWriteRequest разбирает несжатое тело remote write.
func parseWriteRequest(b []byte) ([]TimeSeries, error) {
	var out []TimeSeries
	r := &pbReader{b: b}
	for !r.eof() {
		key, err := r.varint()
		if err != nil {
			return nil, err
		}
		field, wire := key>>3, key&7
		if field == 1 && wire == 2 {
			body, err := r.bytes()
			if err != nil {
				return nil, err
			}
			ts, err := parseTimeSeries(body)
			if err != nil {
				return nil, err
			}
			out = append(out, ts)
			continue
		}
		if err := r.skip(wire); err != nil {
			return nil, err
		}
	}
	return out, nil
}

func parseTimeSeries(b []byte) (TimeSeries, error) {
	var ts TimeSeries
	r := &pbReader{b: b}
	for !r.eof() {
		key, err := r.varint()
		if err != nil {
			return ts, err
		}
		field, wire := key>>3, key&7
		if wire != 2 || (field != 1 && field != 2 && field != 4) {
			if err := r.skip(wire); err != nil {
				return ts, err
			}
			continue
		}
		body, err := r.bytes()
		if err != nil {
			return ts, err
		}
		switch field {
		case 1:
			l, err := parseLabel(body)
			if err != nil {
				return ts, err
			}
			ts.Labels = append(ts.Labels, l)
		case 2:
			s, err := parseSample(body)
			if err != nil {
				return ts, err
			}
			ts.Samples = append(ts.Samples, s)
		case 4:
			h, err := parseHistogram(body)
			if err != nil {
				return ts, err
			}
			ts.Histograms = append(ts.Histograms, h)
		}
	}
	return ts, nil
}

func parseLabel(b []byte) (Label, error) {
	var l Label
	r := &pbReader{b: b}
	for !r.eof() {
		key, err := r.varint()
		if err != nil {
			return l, err
		}
		field, wire := key>>3, key&7
		if wire == 2 && (field == 1 || field == 2) {
			s, err := r.bytes()
			if err != nil {
				return l, err
			}
			if field == 1 {
				l.Name = string(s)
			} else {
				l.Value = string(s)
			}
			continue
		}
		if err := r.skip(wire); err != nil {
			return l, err
		}
	}
	return l, nil
}

func parseSample(b []byte) (Sample, error) {
	var s Sample
	r := &pbReader{b: b}
	for !r.eof() {
		key, err := r.varint()
		if err != nil {
			return s, err
		}
		field, wire := key>>3, key&7
		switch {
		case field == 1 && wire == 1:
			if s.Value, err = r.fixed64(); err != nil {
				return s, err
			}
		case field == 2 && wire == 0:
			v, err := r.varint()
			if err != nil {
				return s, err
			}
			s.Ts = int64(v)
		default:
			if err := r.skip(wire); err != nil {
				return s, err
			}
		}
	}
	return s, nil
}

func parseSpan(b []byte) (BucketSpan, error) {
	var sp BucketSpan
	r := &pbReader{b: b}
	for !r.eof() {
		key, err := r.varint()
		if err != nil {
			return sp, err
		}
		field, wire := key>>3, key&7
		if wire == 0 && (field == 1 || field == 2) {
			v, err := r.varint()
			if err != nil {
				return sp, err
			}
			if field == 1 {
				sp.Offset = int32(zigzag(v))
			} else {
				sp.Length = uint32(v)
			}
			continue
		}
		if err := r.skip(wire); err != nil {
			return sp, err
		}
	}
	return sp, nil
}

// readSints читает repeated sint64: упакованно (wire 2) либо по одному (wire 0).
func readSints(r *pbReader, wire uint64, dst *[]int64) error {
	if wire == 0 {
		v, err := r.varint()
		*dst = append(*dst, zigzag(v))
		return err
	}
	body, err := r.bytes()
	if err != nil {
		return err
	}
	pr := &pbReader{b: body}
	for !pr.eof() {
		v, err := pr.varint()
		if err != nil {
			return err
		}
		*dst = append(*dst, zigzag(v))
	}
	return nil
}

func readDoubles(r *pbReader, wire uint64, dst *[]float64) error {
	if wire == 1 {
		v, err := r.fixed64()
		*dst = append(*dst, v)
		return err
	}
	body, err := r.bytes()
	if err != nil {
		return err
	}
	pr := &pbReader{b: body}
	for !pr.eof() {
		v, err := pr.fixed64()
		if err != nil {
			return err
		}
		*dst = append(*dst, v)
	}
	return nil
}

func parseHistogram(b []byte) (NativeHistogram, error) {
	var h NativeHistogram
	var posDeltas []int64
	var posFloat []float64
	r := &pbReader{b: b}
	for !r.eof() {
		key, err := r.varint()
		if err != nil {
			return h, err
		}
		field, wire := key>>3, key&7
		switch {
		case field == 1 && wire == 0: // count_int
			v, err := r.varint()
			if err != nil {
				return h, err
			}
			h.Count = float64(v)
		case field == 2 && wire == 1: // count_float
			if h.Count, err = r.fixed64(); err != nil {
				return h, err
			}
		case field == 3 && wire == 1:
			if h.Sum, err = r.fixed64(); err != nil {
				return h, err
			}
		case field == 4 && wire == 0:
			v, err := r.varint()
			if err != nil {
				return h, err
			}
			h.Schema = int32(zigzag(v))
		case field == 5 && wire == 1:
			if h.ZeroThreshold, err = r.fixed64(); err != nil {
				return h, err
			}
		case field == 6 && wire == 0: // zero_count_int
			v, err := r.varint()
			if err != nil {
				return h, err
			}
			h.ZeroCount = float64(v)
		case field == 7 && wire == 1: // zero_count_float
			if h.ZeroCount, err = r.fixed64(); err != nil {
				return h, err
			}
		case field == 11 && wire == 2: // positive_spans
			body, err := r.bytes()
			if err != nil {
				return h, err
			}
			sp, err := parseSpan(body)
			if err != nil {
				return h, err
			}
			h.PosSpans = append(h.PosSpans, sp)
		case field == 12 && (wire == 2 || wire == 0): // positive_deltas
			if err := readSints(r, wire, &posDeltas); err != nil {
				return h, err
			}
		case field == 13 && (wire == 2 || wire == 1): // positive_counts
			if err := readDoubles(r, wire, &posFloat); err != nil {
				return h, err
			}
		case field == 15 && wire == 0:
			v, err := r.varint()
			if err != nil {
				return h, err
			}
			h.Ts = int64(v)
		default: // отрицательные корзины, reset_hint, custom_values - для задержек не нужны
			if err := r.skip(wire); err != nil {
				return h, err
			}
		}
	}
	if len(posFloat) > 0 {
		h.PosCounts = posFloat
	} else {
		var cur int64
		for _, d := range posDeltas {
			cur += d
			h.PosCounts = append(h.PosCounts, float64(cur))
		}
	}
	return h, nil
}

// ---- сборка запроса (метки + сэмплы)

type pbWriter struct{ b []byte }

func (w *pbWriter) varint(v uint64) { w.b = binary.AppendUvarint(w.b, v) }
func (w *pbWriter) key(field, wire uint64) { w.varint(field<<3 | wire) }
func (w *pbWriter) bytes(field uint64, p []byte) {
	w.key(field, 2)
	w.varint(uint64(len(p)))
	w.b = append(w.b, p...)
}
func (w *pbWriter) str(field uint64, s string) { w.bytes(field, []byte(s)) }

func encodeWriteRequest(series []TimeSeries) []byte {
	var out pbWriter
	for _, ts := range series {
		var t pbWriter
		labels := append([]Label(nil), ts.Labels...)
		sort.Slice(labels, func(i, j int) bool { return labels[i].Name < labels[j].Name }) // Prometheus требует отсортированные метки
		for _, l := range labels {
			var lw pbWriter
			lw.str(1, l.Name)
			lw.str(2, l.Value)
			t.bytes(1, lw.b)
		}
		for _, s := range ts.Samples {
			var sw pbWriter
			sw.key(1, 1)
			sw.b = binary.LittleEndian.AppendUint64(sw.b, math.Float64bits(s.Value))
			sw.key(2, 0)
			sw.varint(uint64(s.Ts))
			t.bytes(2, sw.b)
		}
		out.bytes(1, t.b)
	}
	return out.b
}
