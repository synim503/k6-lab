package main

import "testing"

func TestConvertSeriesDropsHistogramsNotInKeep(t *testing.T) {
	h := nativeFromValues([]float64{0.1, 0.2, 0.3}, 3)
	var in []TimeSeries
	for _, name := range []string{"k6_iteration_duration_seconds", "k6_http_req_blocked_seconds", "k6_http_req_duration_seconds"} {
		series, err := parseWriteRequest(encodeNative([]Label{{"__name__", name}, {"scenario", "pay"}}, h))
		if err != nil {
			t.Fatal(err)
		}
		in = append(in, series...)
	}
	// обычная серия (счётчик) проходит всегда
	in = append(in, TimeSeries{Labels: []Label{{"__name__", "k6_iterations_total"}, {"scenario", "pay"}}, Samples: []Sample{{5, 1000}}})

	out, conv := convertSeries(in, map[string]bool{"k6_iteration_duration_seconds": true, "k6_http_req_duration_seconds": true})
	if conv != 2 {
		t.Fatalf("преобразованных гистограмм %d, ожидалось 2", conv)
	}
	names := map[string]int{}
	for _, ts := range out {
		for _, l := range ts.Labels {
			if l.Name == "__name__" {
				names[l.Value]++
			}
		}
	}
	if names["k6_http_req_blocked_seconds_bucket"] != 0 || names["k6_http_req_blocked_seconds_count"] != 0 {
		t.Fatalf("лишняя гистограмма не отброшена: %v", names)
	}
	if names["k6_iteration_duration_seconds_bucket"] != len(leBounds)+1 || names["k6_iterations_total"] != 1 {
		t.Fatalf("нужные серии потеряны: %v", names)
	}

	// без keep остаются все три
	_, all := convertSeries(in, nil)
	if all != 3 {
		t.Fatalf("без фильтра ожидалось 3 гистограммы, получено %d", all)
	}
}

func TestConvertSeriesSendsOnlyAllowedMetrics(t *testing.T) {
	h := nativeFromValues([]float64{0.1, 0.2}, 2)
	var in []TimeSeries
	for _, name := range []string{"k6_iteration_duration_seconds", "k6_http_req_blocked_seconds"} {
		series, err := parseWriteRequest(encodeNative([]Label{{"__name__", name}}, h))
		if err != nil {
			t.Fatal(err)
		}
		in = append(in, series...)
	}
	for _, name := range []string{"k6_iterations_total", "k6_data_sent_total", "k6_data_received_total", "k6_vus"} {
		in = append(in, TimeSeries{Labels: []Label{{"__name__", name}}, Samples: []Sample{{1, 1000}}})
	}
	// белый список: остальное отбрасывается, в том числе гистограммы
	out, _ := convertSeriesAllow(in, nil, []string{"k6_iteration_duration_seconds", "k6_iterations_total", "k6_vus*"})
	names := map[string]int{}
	for _, ts := range out {
		for _, l := range ts.Labels {
			if l.Name == "__name__" {
				names[l.Value]++
			}
		}
	}
	for _, gone := range []string{"k6_data_sent_total", "k6_data_received_total", "k6_http_req_blocked_seconds_bucket", "k6_http_req_blocked_seconds_count"} {
		if names[gone] != 0 {
			t.Fatalf("%s не в белом списке, но отправлена: %v", gone, names)
		}
	}
	if names["k6_iterations_total"] != 1 || names["k6_vus"] != 1 || names["k6_iteration_duration_seconds_count"] != 1 {
		t.Fatalf("метрики из белого списка потеряны: %v", names)
	}
	// пустой список - отправляется всё
	all, _ := convertSeriesAllow(in, nil, nil)
	if len(all) <= len(out) {
		t.Fatalf("без белого списка должно уходить больше серий: %d <= %d", len(all), len(out))
	}
	if !allowed("k6_vus", []string{"k6_*"}) || allowed("k6_vus", []string{"vus"}) || allowed("k6_vus", nil) {
		t.Fatal("маска сопоставляется неверно")
	}
}
