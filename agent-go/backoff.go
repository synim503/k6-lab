package main

import (
	"math/rand"
	"sync"
	"time"
)

// Backoff: exponential с jitter +-20%: min, 2*min, 4*min ... max.
type Backoff struct {
	mu      sync.Mutex
	min     time.Duration
	max     time.Duration
	attempt int
}

func NewBackoff(min, max time.Duration) *Backoff { return &Backoff{min: min, max: max} }

func (b *Backoff) Next() time.Duration {
	b.mu.Lock()
	defer b.mu.Unlock()
	shift := b.attempt
	if shift > 20 {
		shift = 20
	}
	base := b.min << uint(shift)
	b.attempt++
	if base > b.max || base <= 0 {
		base = b.max
	}
	d := time.Duration(float64(base) * (0.8 + 0.4*rand.Float64()))
	if d > b.max {
		d = b.max
	}
	if d < time.Millisecond {
		d = time.Millisecond
	}
	return d
}

func (b *Backoff) Reset() {
	b.mu.Lock()
	b.attempt = 0
	b.mu.Unlock()
}
