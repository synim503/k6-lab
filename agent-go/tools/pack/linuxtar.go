package main

import (
	"archive/tar"
	"compress/gzip"
	"fmt"
	"io"
	"os"
	"path/filepath"
	"strings"
)

// buildLinuxTar собирает tar.gz для Linux с встроенной JRE: админка запускается без установленной Java (start.sh берёт jre/bin/java).
func buildLinuxTar(root, jreTgz, out string) (retErr error) {
	f, err := os.Create(out)
	if err != nil {
		return err
	}
	defer func() {
		if retErr != nil {
			_ = f.Close()
			_ = os.Remove(out)
		}
	}()
	gz, err := gzip.NewWriterLevel(f, gzip.BestSpeed)
	if err != nil {
		return err
	}
	tw := tar.NewWriter(gz)
	base := filepath.Join(root, "k6-lab")
	err = filepath.Walk(base, func(p string, info os.FileInfo, err error) error {
		if err != nil || info.IsDir() {
			return err
		}
		rel, _ := filepath.Rel(root, p)
		name := filepath.ToSlash(rel)
		mode := int64(0o644)
		if strings.HasSuffix(name, ".sh") || strings.Contains(name, "/dist/agent/") || strings.HasSuffix(name, "/k6") {
			mode = 0o755
		}
		data, err := os.ReadFile(p)
		if err != nil {
			return err
		}
		if strings.HasSuffix(name, ".sh") { // скрипты Linux - только с LF
			data = []byte(strings.ReplaceAll(string(data), "\r\n", "\n"))
		}
		if err := tw.WriteHeader(&tar.Header{Name: name, Mode: mode, Size: int64(len(data)), ModTime: info.ModTime(), Typeflag: tar.TypeReg}); err != nil {
			return err
		}
		_, err = tw.Write(data)
		return err
	})
	if err != nil {
		return err
	}
	// JRE: переносим записи архива Temurin, заменяя верхний каталог jdk-17.x-jre на k6-lab/jre
	jf, err := os.Open(jreTgz)
	if err != nil {
		return err
	}
	defer jf.Close()
	jg, err := gzip.NewReader(jf)
	if err != nil {
		return err
	}
	defer jg.Close()
	tr := tar.NewReader(jg)
	for {
		h, err := tr.Next()
		if err == io.EOF {
			break
		}
		if err != nil {
			return err
		}
		cleanName := strings.TrimPrefix(h.Name, "./")
		i := strings.Index(cleanName, "/")
		if i < 0 {
			continue
		}
		h.Name = "k6-lab/jre/" + cleanName[i+1:]
		if h.Typeflag == tar.TypeSymlink || h.Typeflag == tar.TypeLink {
			if h.Typeflag == tar.TypeLink {
				cleanLink := strings.TrimPrefix(h.Linkname, "./")
				if j := strings.Index(cleanLink, "/"); j >= 0 {
					h.Linkname = "k6-lab/jre/" + cleanLink[j+1:]
				}
			}
		}
		if h.Name == "k6-lab/jre/" {
			continue
		}
		if err := tw.WriteHeader(h); err != nil {
			return err
		}
		if h.Typeflag == tar.TypeReg {
			if _, err := io.Copy(tw, tr); err != nil {
				return err
			}
		}
	}
	if err := tw.Close(); err != nil {
		return err
	}
	if err := gz.Close(); err != nil {
		return err
	}
	if err := f.Close(); err != nil {
		return err
	}
	st, _ := os.Stat(out)
	fmt.Printf("Готово: %s (%d МБ, с JRE 17)\n", out, st.Size()/1e6)
	return nil
}
