// pack - формирует dist/manifest.json и zip-архив портативной сборки (замена python-скрипта: Go и так нужен для сборки агента).
//
//	go run ./tools/pack <каталог portable> <версия k6> <версия агента>
package main

import (
	"archive/zip"
	"encoding/json"
	"fmt"
	"io"
	"os"
	"path/filepath"
	"strings"
)

type item struct {
	Kind    string `json:"kind"`
	Label   string `json:"label"`
	Version string `json:"version"`
	Arch    string `json:"arch"`
	File    string `json:"file"`
	Default bool   `json:"default"`
}

func exists(p string) bool {
	st, err := os.Stat(p)
	return err == nil && !st.IsDir()
}

func main() {
	if len(os.Args) < 3 {
		fmt.Fprintln(os.Stderr, "usage: pack <portable-dir> <k6-version> [agent-version]")
		os.Exit(2)
	}
	root, k6v := os.Args[1], os.Args[2]
	agentV := "portable"
	if len(os.Args) > 3 {
		agentV = os.Args[3]
	}
	dist := filepath.Join(root, "k6-lab", "dist")
	arch := [][2]string{{"linux-amd64", "LINUX_AMD64"}, {"linux-arm64", "LINUX_ARM64"}}
	var items []item
	for _, a := range arch {
		d, e := a[0], a[1]
		if exists(filepath.Join(dist, "agent", d, "k6-agent")) {
			items = append(items, item{"AGENT", "", agentV, e, "agent/" + d + "/k6-agent", true})
		}
		if exists(filepath.Join(dist, "k6", d, "k6")) {
			items = append(items, item{"K6", "", k6v, e, "k6/" + d + "/k6", true})
		}
		if exists(filepath.Join(dist, "xk6", "full", d, "k6")) {
			items = append(items, item{"XK6", "full", k6v + "-ext", e, "xk6/full/" + d + "/k6", false})
		}
		if exists(filepath.Join(dist, "jre", d, "temurin-jre21.tar.gz")) {
			items = append(items, item{"JRE", "", "21", e, "jre/" + d + "/temurin-jre21.tar.gz", true})
		}
	}
	mf, _ := json.MarshalIndent(map[string]any{"items": items}, "", "  ")
	if err := os.WriteFile(filepath.Join(dist, "manifest.json"), mf, 0o644); err != nil {
		fmt.Fprintln(os.Stderr, err)
		os.Exit(1)
	}
	out := filepath.Join(root, "k6-lab-portable.zip")
	f, err := os.Create(out)
	if err != nil {
		fmt.Fprintln(os.Stderr, err)
		os.Exit(1)
	}
	zw := zip.NewWriter(f)
	err = filepath.Walk(filepath.Join(root, "k6-lab"), func(p string, info os.FileInfo, err error) error {
		if err != nil || info.IsDir() {
			return err
		}
		rel, _ := filepath.Rel(root, p)
		h, err := zip.FileInfoHeader(info)
		if err != nil {
			return err
		}
		h.Name = filepath.ToSlash(rel)
		h.Method = zip.Deflate
		mode := os.FileMode(0o644)
		// исполняемые файлы: скрипты запуска (.sh) и бинари для Linux внутри dist/
		if strings.HasSuffix(h.Name, ".sh") || strings.Contains(h.Name, "/dist/agent/") || strings.HasSuffix(h.Name, "/k6") {
			mode = 0o755
		}
		h.SetMode(mode)
		w, err := zw.CreateHeader(h)
		if err != nil {
			return err
		}
		in, err := os.Open(p)
		if err != nil {
			return err
		}
		defer in.Close()
		_, err = io.Copy(w, in)
		return err
	})
	if err == nil {
		err = zw.Close()
	}
	if err == nil {
		err = f.Close()
	}
	if err != nil {
		fmt.Fprintln(os.Stderr, err)
		os.Exit(1)
	}
	st, _ := os.Stat(out)
	fmt.Printf("Готово: %s (%d МБ, дистрибутивов: %d)\n", out, st.Size()/1e6, len(items))
}
