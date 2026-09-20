package server

import (
	"crypto/sha256"
	"encoding/hex"
	"sync"
	"time"
)

// dashCacheTTL is how long a dashboard stats snapshot stays fresh.
const dashCacheTTL = 60 * time.Second

// dashCache is a simple TTL-based memory cache keyed by a hash of the range
// parameters, so repeated requests for the same time window skip all 6+ SQL
// aggregation queries.
type dashCache struct {
	mu      sync.RWMutex
	entries map[string]*dashCacheEntry
}

type dashCacheEntry struct {
	data      any
	expiresAt time.Time
}

// NewDashCache creates a new dashCache instance.
func NewDashCache() *dashCache {
	return &dashCache{entries: make(map[string]*dashCacheEntry)}
}

// cacheKey builds a deterministic hash from the range query parameters.
func (dc *dashCache) cacheKey(key, from, to string) string {
	h := sha256.New()
	h.Write([]byte(key))
	h.Write([]byte("\x00"))
	h.Write([]byte(from))
	h.Write([]byte("\x00"))
	h.Write([]byte(to))
	return hex.EncodeToString(h.Sum(nil))
}

// Get returns cached data if a fresh entry exists.
func (dc *dashCache) Get(key, from, to string) (any, bool) {
	if dc == nil {
		return nil, false
	}
	ck := dc.cacheKey(key, from, to)
	dc.mu.RLock()
	e, ok := dc.entries[ck]
	if !ok || time.Now().After(e.expiresAt) {
		dc.mu.RUnlock()
		return nil, false
	}
	dc.mu.RUnlock()
	return e.data, true
}

// Set stores data with the configured TTL.
func (dc *dashCache) Set(key, from, to string, data any) {
	if dc == nil {
		return
	}
	ck := dc.cacheKey(key, from, to)
	dc.mu.Lock()
	dc.entries[ck] = &dashCacheEntry{
		data:      data,
		expiresAt: time.Now().Add(dashCacheTTL),
	}
	dc.mu.Unlock()
}

// Invalidate clears all entries.
func (dc *dashCache) Invalidate() {
	if dc == nil {
		return
	}
	dc.mu.Lock()
	dc.entries = make(map[string]*dashCacheEntry)
	dc.mu.Unlock()
}
