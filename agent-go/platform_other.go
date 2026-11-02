//go:build !linux

package main

import "runtime"

func isWindows() bool { return runtime.GOOS == "windows" }

func diskFree(path string) int64 { return 0 }
