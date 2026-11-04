package main

import (
	"strings"
	"testing"
)

func TestBuildArgsAddsOnlyLongK6Flags(t *testing.T) {
	p := Prepare{Entrypoint: "main.js", K6Flags: []string{"--http-debug=full", "--no-thresholds", "rm -rf /", "-x", "--bad flag"}}
	got := strings.Join(buildArgs(p, "cfg.json", "sum.json", 6565), " ")
	for _, want := range []string{"--http-debug=full", "--no-thresholds"} {
		if !strings.Contains(got, want) {
			t.Errorf("в аргументах нет %q: %s", want, got)
		}
	}
	for _, bad := range []string{"rm -rf", "-x ", "--bad flag"} {
		if strings.Contains(got, bad) {
			t.Errorf("в аргументы попало лишнее %q: %s", bad, got)
		}
	}
	if !strings.HasSuffix(got, "main.js") {
		t.Errorf("скрипт должен быть последним аргументом: %s", got)
	}
}
