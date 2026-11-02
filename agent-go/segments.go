package main

import (
	"bufio"
	"fmt"
	"io"
	"math/big"
	"os"
	"strings"
)

// Рациональные границы execution segment (без ошибок округления double) и нарезка данных SPLIT.

type Fraction struct{ num, den *big.Int }

func parseFraction(s string) (Fraction, error) {
	t := strings.TrimSpace(s)
	num, den := t, "1"
	if i := strings.Index(t, "/"); i >= 0 {
		num, den = strings.TrimSpace(t[:i]), strings.TrimSpace(t[i+1:])
	}
	n, ok1 := new(big.Int).SetString(num, 10)
	d, ok2 := new(big.Int).SetString(den, 10)
	if !ok1 || !ok2 || d.Sign() == 0 {
		return Fraction{}, fmt.Errorf("некорректная дробь %q", s)
	}
	return Fraction{n, d}, nil
}

// floorTimes = floor(num*rows/den) (границы неотрицательные).
func (f Fraction) floorTimes(rows int64) int64 {
	x := new(big.Int).Mul(f.num, big.NewInt(rows))
	return new(big.Int).Div(x, f.den).Int64()
}

func parseSegment(seg string) (Fraction, Fraction, error) {
	p := strings.Split(seg, ":")
	if len(p) != 2 {
		return Fraction{}, Fraction{}, fmt.Errorf("сегмент должен иметь вид a:b")
	}
	a, err := parseFraction(p[0])
	if err != nil {
		return a, a, err
	}
	b, err := parseFraction(p[1])
	return a, b, err
}

func countRows(path string, header bool) (int64, error) {
	f, err := os.Open(path)
	if err != nil {
		return 0, err
	}
	defer f.Close()
	var lines int64
	var last byte
	var any bool
	buf := make([]byte, 1<<16)
	for {
		n, err := f.Read(buf)
		for _, b := range buf[:n] {
			if b == '\n' {
				lines++
			}
			last = b
			any = true
		}
		if err == io.EOF {
			break
		}
		if err != nil {
			return 0, err
		}
	}
	if any && last != '\n' {
		lines++
	}
	if header {
		if lines < 1 {
			return 0, nil
		}
		return lines - 1, nil
	}
	return lines, nil
}

// splitData перезаписывает файл: заголовок (если есть) + строки данных своего сегмента [floor(start*N), floor(end*N)).
func splitData(path string, start, end Fraction, header bool) (total, from, to int64, err error) {
	total, err = countRows(path, header)
	if err != nil {
		return
	}
	from, to = start.floorTimes(total), end.floorTimes(total)
	in, err := os.Open(path)
	if err != nil {
		return
	}
	defer in.Close()
	tmp := path + ".split.tmp"
	out, err := os.Create(tmp)
	if err != nil {
		return
	}
	w := bufio.NewWriterSize(out, 1<<16)
	r := bufio.NewReaderSize(in, 1<<16)
	var idx int64
	first := true
	for {
		line, rerr := r.ReadBytes('\n') // терминаторы строк сохраняются как есть
		if len(line) > 0 {
			if first && header {
				_, _ = w.Write(line)
			} else {
				if idx >= from && idx < to {
					_, _ = w.Write(line)
				}
				idx++
			}
			first = false
		}
		if rerr != nil {
			break
		}
	}
	if err = w.Flush(); err != nil {
		out.Close()
		return
	}
	out.Close()
	in.Close()
	err = os.Rename(tmp, path)
	return
}
