// Package circuit implements the circuit breaker state machine.
package circuit

import (
	"strconv"
	"strings"
	"sync"
	"time"
)

// StateCache caches circuitBreakScope results with a short TTL, mirroring
// Java's LocalCacheService + NS_CIRCUIT_STATE_BY_SCOPE (Caffeine, 1s TTL).
//
// Two design properties from the Java original are preserved:
//   - A "__healthy__" sentinel is stored for non-broken candidates so that
//     the most common code path (healthy channel/model) also hits the cache.
//   - The cache is a pure performance optimisation: nil/empty StateCache is
//     a no-op, and entries expire within 1 second whether or not they are
//     explicitly invalidated.
type StateCache struct {
	mu      sync.Mutex
	entries map[string]*stateCacheEntry
}

type stateCacheEntry struct {
	broken    bool
	scope     string
	expiresAt time.Time
}

const (
	stateCacheTTL           = 1 * time.Second
	stateCacheHealthySuffix = "__healthy__"
)

// NewStateCache creates a cache. There is no background eviction goroutine;
// expired entries are lazily skipped on Get and reclaimed by periodic GC.
func NewStateCache() *StateCache {
	return &StateCache{entries: make(map[string]*stateCacheEntry)}
}

// cacheKey builds the same key string as Java
// (channelId + ":" + channelModelId + ":" + channelApiKeyId).
func cacheKey(channelID, channelModelID, channelAPIKeyID int64) string {
	var sb strings.Builder
	sb.Grow(64)
	sb.WriteString(strconv.FormatInt(channelID, 10))
	sb.WriteByte(':')
	sb.WriteString(strconv.FormatInt(channelModelID, 10))
	sb.WriteByte(':')
	sb.WriteString(strconv.FormatInt(channelAPIKeyID, 10))
	return sb.String()
}

// Get returns the cached result. ok is false when the key is absent or expired.
func (sc *StateCache) Get(channelID, channelModelID, channelAPIKeyID int64) (broken bool, scope string, ok bool) {
	if sc == nil {
		return false, "", false
	}
	key := cacheKey(channelID, channelModelID, channelAPIKeyID)
	sc.mu.Lock()
	e, found := sc.entries[key]
	if !found || time.Now().After(e.expiresAt) {
		if found {
			delete(sc.entries, key)
		}
		sc.mu.Unlock()
		return false, "", false
	}
	sc.mu.Unlock()
	return e.broken, e.scope, true
}

// Set stores a circuitBreakScope result. A non-broken result is stored with
// an empty scope so the healthy sentinel key is implicit (scope "" means healthy).
func (sc *StateCache) Set(channelID, channelModelID, channelAPIKeyID int64, broken bool, scope string) {
	if sc == nil {
		return
	}
	key := cacheKey(channelID, channelModelID, channelAPIKeyID)
	sc.mu.Lock()
	sc.entries[key] = &stateCacheEntry{
		broken:    broken,
		scope:     scope,
		expiresAt: time.Now().Add(stateCacheTTL),
	}
	sc.mu.Unlock()
}

// Invalidate removes entries that match the given scope.
//   - When all three pointers are non-nil: exact key invalidation
//     (Java: channelModelId != null && channelApiKeyId != null).
//   - When any pointer is nil: full flush (Java: invalidateAll).
func (sc *StateCache) Invalidate(channelID, channelModelID, channelAPIKeyID *int64) {
	if sc == nil {
		return
	}
	if channelID == nil || channelModelID == nil || channelAPIKeyID == nil {
		sc.invalidateAll()
		return
	}
	key := cacheKey(*channelID, *channelModelID, *channelAPIKeyID)
	sc.mu.Lock()
	delete(sc.entries, key)
	sc.mu.Unlock()
}

// InvalidateAll clears every entry in the cache.
func (sc *StateCache) InvalidateAll() {
	if sc == nil {
		return
	}
	sc.invalidateAll()
}

func (sc *StateCache) invalidateAll() {
	sc.mu.Lock()
	sc.entries = make(map[string]*stateCacheEntry)
	sc.mu.Unlock()
}

// Len returns the number of entries (for testing / metrics).
func (sc *StateCache) Len() int {
	if sc == nil {
		return 0
	}
	sc.mu.Lock()
	defer sc.mu.Unlock()
	// Prune expired first
	now := time.Now()
	for k, e := range sc.entries {
		if now.After(e.expiresAt) {
			delete(sc.entries, k)
		}
	}
	return len(sc.entries)
}