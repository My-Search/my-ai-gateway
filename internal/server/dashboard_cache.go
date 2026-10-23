package server

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"log/slog"
	"net/http"
	"sync"
	"time"

	"github.com/gin-gonic/gin"

	"github.com/my-search/my-ai-gateway/internal/httpx"
)

// dashCacheTTL is how long a dashboard stats snapshot stays fresh.
const dashCacheTTL = 60 * time.Second

// dashCacheStaleWindow is how long an expired snapshot stays readable for
// stale-while-revalidate. A request that arrives just after expiry is answered
// from the stale copy immediately while a background refresh replaces it, so a
// multi-second aggregation never blocks a poll or trips the browser timeout.
const dashCacheStaleWindow = 30 * time.Minute

// dashStatsQueryTimeout bounds the detached dashboard aggregation, mirroring
// usageChartQueryTimeout: the query must outlive a browser timeout so it can
// still populate DashCache for the next poll instead of being cancelled and
// re-run from scratch.
const dashStatsQueryTimeout = 2 * time.Minute

// dashCache is a simple TTL-based memory cache keyed by a hash of the range
// parameters, so repeated requests for the same time window skip all 6+ SQL
// aggregation queries.
type dashCache struct {
	mu      sync.RWMutex
	entries map[string]*dashCacheEntry
	// refreshing tracks keys with a background refresh in flight, so N
	// concurrent stale hits spawn one aggregation instead of N.
	refreshing map[string]struct{}
}

type dashCacheEntry struct {
	data      any
	expiresAt time.Time
	staleUntil time.Time
}

// NewDashCache creates a new dashCache instance.
func NewDashCache() *dashCache {
	return &dashCache{
		entries:    make(map[string]*dashCacheEntry),
		refreshing: make(map[string]struct{}),
	}
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

// Key exposes the cache key so callers can use it for their own single-flight
// bookkeeping. It is only useful together with BeginRefresh/EndRefresh.
func (dc *dashCache) Key(key, from, to string) string {
	if dc == nil {
		return ""
	}
	return dc.cacheKey(key, from, to)
}

// Get returns cached data if a fresh entry exists.
func (dc *dashCache) Get(key, from, to string) (any, bool) {
	data, fresh, ok := dc.GetStale(key, from, to)
	if !ok || !fresh {
		return nil, false
	}
	return data, true
}

// GetStale returns data even after it stopped being fresh, as long as it is
// still inside dashCacheStaleWindow. fresh reports whether the entry is still
// within its TTL; ok is false when nothing (or nothing usable) is cached.
func (dc *dashCache) GetStale(key, from, to string) (data any, fresh bool, ok bool) {
	if dc == nil {
		return nil, false, false
	}
	ck := dc.cacheKey(key, from, to)
	now := time.Now()
	dc.mu.RLock()
	e, found := dc.entries[ck]
	dc.mu.RUnlock()
	if !found || now.After(e.staleUntil) {
		return nil, false, false
	}
	return e.data, now.Before(e.expiresAt), true
}

// Set stores data with the default TTL.
func (dc *dashCache) Set(key, from, to string, data any) {
	dc.SetWithTTL(key, from, to, data, dashCacheTTL)
}

// SetWithTTL stores data with an explicit freshness TTL. The stale window is
// always dashCacheStaleWindow on top of it.
func (dc *dashCache) SetWithTTL(key, from, to string, data any, ttl time.Duration) {
	if dc == nil {
		return
	}
	ck := dc.cacheKey(key, from, to)
	dc.mu.Lock()
	now := time.Now()
	// Sweep expired entries while we hold the write lock: a miss never removes
	// its entry, so without this the map would grow with every distinct
	// (month, filter) combination forever. Entries past the stale window are
	// already unreachable (GetStale refuses them), so this is behavior-neutral.
	for k, e := range dc.entries {
		if now.After(e.staleUntil) {
			delete(dc.entries, k)
		}
	}
	dc.entries[ck] = &dashCacheEntry{
		data:       data,
		expiresAt:  now.Add(ttl),
		staleUntil: now.Add(ttl + dashCacheStaleWindow),
	}
	dc.mu.Unlock()
}

// BeginRefresh claims the background refresh slot for ck. It returns false when
// another goroutine already owns it, so concurrent stale hits coalesce into a
// single aggregation.
func (dc *dashCache) BeginRefresh(ck string) bool {
	if dc == nil || ck == "" {
		return false
	}
	dc.mu.Lock()
	defer dc.mu.Unlock()
	if _, busy := dc.refreshing[ck]; busy {
		return false
	}
	dc.refreshing[ck] = struct{}{}
	return true
}

// EndRefresh releases the background refresh slot claimed by BeginRefresh.
func (dc *dashCache) EndRefresh(ck string) {
	if dc == nil || ck == "" {
		return
	}
	dc.mu.Lock()
	delete(dc.refreshing, ck)
	dc.mu.Unlock()
}

// RefreshAsync runs fn in the background at most once per key, then stores the
// result under the key with the given TTL. It is used for stale-while-revalidate:
// the caller has already answered from the stale snapshot and must not wait.
func (dc *dashCache) RefreshAsync(ck, key, from, to string, ttl time.Duration, fn func() (any, error)) {
	if dc == nil || !dc.BeginRefresh(ck) {
		return
	}
	go func() {
		defer dc.EndRefresh(ck)
		defer func() {
			// A panic here must not take the process down: this goroutine is not
			// tied to any request and there is nobody to return the error to.
			if r := recover(); r != nil {
				slog.Error("后台刷新缓存失败", "key", key, "panic", r)
			}
		}()
		data, err := fn()
		if err != nil {
			slog.Warn("后台刷新缓存失败", "key", key, "error", err)
			return
		}
		dc.SetWithTTL(key, from, to, data, ttl)
	}()
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

// serveCached answers an expensive aggregation through DashCache with
// stale-while-revalidate semantics:
//
//  1. fresh entry   -> answer immediately;
//  2. stale entry   -> answer immediately, refresh in the background;
//  3. cold cache    -> one request computes, concurrent ones wait for its result
//     instead of starting identical scans.
//
// Step 2 is what removes the failure mode behind "使用历史加载失败": a poll that
// lands just after expiry used to block on a multi-second (sometimes tens of
// seconds) scan and could exceed the browser timeout, whereas now it is served
// from the last snapshot and the refresh happens off the request path.
//
// ctx must outlive the request (the handler detaches it) so a background refresh
// is not cancelled when the browser goes away.
func (d Deps) serveCached(c *gin.Context, ctx context.Context, key, from, to string,
	ttl time.Duration, errMsg string, compute func(context.Context) (any, error)) {
	if d.DashCache == nil {
		out, err := compute(ctx)
		if err != nil {
			httpx.JSON(c, http.StatusInternalServerError,
				httpx.NewOrderedMap().Set("error", errMsg))
			return
		}
		httpx.OK(c, out)
		return
	}

	ck := d.DashCache.Key(key, from, to)

	if cached, ok := d.DashCache.Get(key, from, to); ok {
		httpx.OK(c, cached)
		return
	}

	if cached, _, ok := d.DashCache.GetStale(key, from, to); ok {
		d.DashCache.RefreshAsync(ck, key, from, to, ttl, func() (any, error) {
			// The handler's ctx carries a deferred cancel (and the request context
			// is already detached). Strip cancellation again so the background
			// refresh survives the handler returning, and give it its own deadline
			// so a pathological query cannot pin a read-pool connection forever.
			bctx, bcancel := context.WithTimeout(context.WithoutCancel(ctx), dashStatsQueryTimeout)
			defer bcancel()
			return compute(bctx)
		})
		httpx.OK(c, cached)
		return
	}

	if d.DashCache.BeginRefresh(ck) {
		out, err := compute(ctx)
		d.DashCache.EndRefresh(ck)
		if err != nil {
			httpx.JSON(c, http.StatusInternalServerError,
				httpx.NewOrderedMap().Set("error", errMsg))
			return
		}
		d.DashCache.SetWithTTL(key, from, to, out, ttl)
		httpx.OK(c, out)
		return
	}

	// Another request owns the cold computation. Wait for its result rather than
	// duplicating the scan; if it takes unreasonably long, compute it ourselves.
	deadline := time.NewTimer(25 * time.Second)
	defer deadline.Stop()
	poll := time.NewTicker(100 * time.Millisecond)
	defer poll.Stop()
	for {
		select {
		case <-c.Request.Context().Done():
			// The browser gave up; the owner's scan still populates the cache.
			return
		case <-poll.C:
			if cached, _, ok := d.DashCache.GetStale(key, from, to); ok {
				httpx.OK(c, cached)
				return
			}
		case <-deadline.C:
			out, err := compute(ctx)
			if err != nil {
				httpx.JSON(c, http.StatusInternalServerError,
					httpx.NewOrderedMap().Set("error", errMsg))
				return
			}
			d.DashCache.SetWithTTL(key, from, to, out, ttl)
			httpx.OK(c, out)
			return
		}
	}
}
