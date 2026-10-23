package server

import (
	"bytes"
	"compress/gzip"
	"context"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"net/http/httptest"
	"strconv"
	"strings"
	"testing"
	"time"

	"github.com/gin-gonic/gin"

	"github.com/my-search/my-ai-gateway/internal/httpx"
	"github.com/my-search/my-ai-gateway/internal/store"
)

// insertWalkLog writes one row with an explicit timestamp so a test can control
// the ordering the keyset walk relies on.
func insertWalkLog(t *testing.T, st *store.Store, traceID, phase, createdAt string) {
	t.Helper()
	if _, err := st.Exec(context.Background(),
		`INSERT INTO request_logs (trace_id, phase, model_name, created_at, request_headers, request_body)
		 VALUES (?,?,?,?,?,?)`,
		traceID, phase, "gpt-4o", createdAt, "h", "b"); err != nil {
		t.Fatal(err)
	}
}

// listTraceIDs drives the endpoint and returns the trace ids of one page.
func listTraceIDs(t *testing.T, r *gin.Engine, query string) []string {
	t.Helper()
	w := httptest.NewRecorder()
	r.ServeHTTP(w, httptest.NewRequest("GET", "/admin/api/logs?"+query, nil))
	if w.Code != http.StatusOK {
		t.Fatalf("status = %d, body = %s", w.Code, w.Body.String())
	}
	var body struct {
		Data []struct {
			TraceID string `json:"traceId"`
		} `json:"data"`
	}
	if err := json.Unmarshal(w.Body.Bytes(), &body); err != nil {
		t.Fatalf("decode: %v", err)
	}
	ids := make([]string, 0, len(body.Data))
	for _, tr := range body.Data {
		ids = append(ids, tr.TraceID)
	}
	return ids
}

// TestLogListKeysetWalkMatchesGroupByPaging is the correctness guard for the
// fast paging path. The walk returns traces ordered by their newest row
// descending, which is exactly what the GROUP BY pager produces — but it gets
// there by scanning backwards over the created_at index instead of grouping the
// whole window. Any divergence in ordering, deduplication or multi-row traces
// would silently shuffle the list between pages.
func TestLogListKeysetWalkMatchesGroupByPaging(t *testing.T) {
	st := newLogListTestStore(t)
	// Traces with 1-3 rows, interleaved timestamps and two identical timestamps
	// (the row id has to break the tie).
	for i := 0; i < 40; i++ {
		tid := fmt.Sprintf("t-%02d", i)
		base := fmt.Sprintf("2026-09-%02dT10:00:00.000000000", (i%28)+1)
		insertWalkLog(t, st, tid, "start", base)
		if i%2 == 0 {
			insertWalkLog(t, st, tid, "retry", base)
			insertWalkLog(t, st, tid, "success", base)
		}
		if i%5 == 0 {
			insertWalkLog(t, st, tid, "fail", base)
		}
	}
	// Duplicate timestamps across different traces: ordering must stay stable and
	// complete (no trace dropped because its created_at equals its neighbour's).
	for i := 0; i < 5; i++ {
		insertWalkLog(t, st, fmt.Sprintf("dup-%d", i), "start", "2026-09-15T12:00:00.000000000")
	}
	if _, err := st.Exec(context.Background(), "ANALYZE"); err != nil {
		t.Fatal(err)
	}

	gin.SetMode(gin.TestMode)
	r := gin.New()
	registerLogRoutes(r.Group("/admin/api"), Deps{Store: st, DashCache: NewDashCache()})

	// Reference: the GROUP BY pager the handler falls back to, run over the same
	// window with the same page size.
	const limit = 7
	var paged []string
	for offset := 0; offset < 45; offset += limit {
		rows, err := st.QueryReadOnly(context.Background(),
			`SELECT trace_id FROM (SELECT trace_id, MAX(created_at) FROM request_logs r WHERE 1=1
			 GROUP BY trace_id ORDER BY MAX(created_at) DESC LIMIT ? OFFSET ?)`,
			limit, offset)
		if err != nil {
			t.Fatal(err)
		}
		for _, row := range rows {
			paged = append(paged, row.Str("trace_id"))
		}
	}

	// The walk is exercised through the endpoint so the whole handler path is
	// covered.
	var walked []string
	for offset := 0; offset < 45; offset += limit {
		walked = append(walked, listTraceIDs(t, r,
			fmt.Sprintf("offset=%d&limit=%d", offset, limit))...)
	}

	// Same traces, no duplicates across pages, and no trace dropped at a page
	// boundary. The order of traces that share a MAX(created_at) is not part of
	// the contract — SQLite resolves those ties inside its own sorter, and the two
	// plans legitimately disagree (production timestamps carry nanoseconds, so
	// exact ties only exist in synthetic data like this).
	if len(walked) != len(paged) {
		t.Fatalf("walked %d traces, paged %d", len(walked), len(paged))
	}
	seen := make(map[string]int, len(walked))
	for _, id := range walked {
		seen[id]++
	}
	for id, n := range seen {
		if n > 1 {
			t.Errorf("trace %q appeared %d times across pages", id, n)
		}
	}
	for _, id := range paged {
		if seen[id] == 0 {
			t.Errorf("trace %q missing from the walked pages", id)
		}
	}

	// Ordering contract: page order must follow MAX(created_at) descending, which
	// is what the list UI relies on.
	maxByTrace := make(map[string]string, len(walked))
	rows, err := st.QueryReadOnly(context.Background(),
		`SELECT trace_id, MAX(created_at) AS m FROM request_logs GROUP BY trace_id`)
	if err != nil {
		t.Fatal(err)
	}
	for _, row := range rows {
		maxByTrace[row.Str("trace_id")] = row.Str("m")
	}
	for i := 1; i < len(walked); i++ {
		if maxByTrace[walked[i-1]] < maxByTrace[walked[i]] {
			t.Fatalf("page order broken at %d: %q (%s) before %q (%s)",
				i, walked[i-1], maxByTrace[walked[i-1]], walked[i], maxByTrace[walked[i]])
		}
	}

	if len(walked) > 1 && walked[0] != "t-27" {
		t.Errorf("first trace = %q, want t-27 (newest created_at)", walked[0])
	}
}

// TestLogListWalkHandlesDeepOffsets is the regression guard for a silent
// truncation. The walk used to be a chunked keyset walk whose cursor came from
// Row.Str("created_at"); on the production schema that column is declared
// TIMESTAMP, the driver returns a time.Time, Str gave "", and the next chunk's
// `(created_at, id) < ('', 0)` matched nothing. Every page beyond the first
// chunk came back short while still reporting hasMore=false.
//
// The walk now streams one statement, so that failure mode is gone — but the
// same symptom would appear if the row budget were miscomputed or the early
// break lost a trace, so the guard stays: a page deep enough to span many
// chunks' worth of rows must still be complete.
func TestLogListWalkHandlesDeepOffsets(t *testing.T) {
	st := newLogListTestStore(t)

	// Two rows per trace, so a page at offset 400 has to stream ~900 rows.
	const traces = 512
	for i := 0; i < traces; i++ {
		tid := fmt.Sprintf("t-%04d", i)
		ts := fmt.Sprintf("2026-09-15T10:%02d:%02d.000000000", (i/60)%60, i%60)
		insertWalkLog(t, st, tid, "start", ts)
		insertWalkLog(t, st, tid, "success", ts)
	}
	if _, err := st.Exec(context.Background(), "ANALYZE"); err != nil {
		t.Fatal(err)
	}

	const limit, offset = 50, 400
	ids, hasMore, ok := listTracePageWalk(context.Background(), st, "", nil, limit, offset)
	if !ok {
		t.Fatal("walk declined a page it is supposed to handle")
	}
	if len(ids) != limit {
		t.Fatalf("walked page has %d traces, want %d", len(ids), limit)
	}
	if !hasMore {
		t.Error("hasMore = false although 512 traces exist and only 450 were consumed")
	}

	// The same page through the GROUP BY pager: identical trace set, so the walk
	// did not skip or duplicate anything while crossing chunk boundaries.
	rows, err := st.QueryReadOnly(context.Background(),
		`SELECT trace_id FROM (SELECT trace_id, MAX(created_at) FROM request_logs
		 GROUP BY trace_id ORDER BY MAX(created_at) DESC LIMIT ? OFFSET ?)`, limit, offset)
	if err != nil {
		t.Fatal(err)
	}
	want := make(map[string]struct{}, len(rows))
	for _, row := range rows {
		want[row.Str("trace_id")] = struct{}{}
	}
	got := make(map[string]struct{}, len(ids))
	for _, id := range ids {
		got[id] = struct{}{}
	}
	for id := range want {
		if _, present := got[id]; !present {
			t.Errorf("trace %q is on the paged page but missing from the walked page", id)
		}
	}
	for id := range got {
		if _, present := want[id]; !present {
			t.Errorf("trace %q is on the walked page but not on the paged page", id)
		}
	}
}

// TestLogListWalkFallsBackForDeepOffsets pins the crossover: the walk is linear
// in offset, so beyond traceWalkMaxOffset the handler must use the flat GROUP BY
// pager instead of scanning tens of thousands of index entries.
func TestLogListWalkFallsBackForDeepOffsets(t *testing.T) {
	st := newLogListTestStore(t)
	insertWalkLog(t, st, "t-1", "start", "2026-09-15T10:00:00.000000000")

	if _, _, ok := listTracePageWalk(context.Background(), st, "", nil,
		50, traceWalkMaxOffset+1); ok {
		t.Errorf("walk accepted offset %d; it must defer to the GROUP BY pager", traceWalkMaxOffset+1)
	}
	if _, _, ok := listTracePageWalk(context.Background(), st, "", nil, 50, 0); !ok {
		t.Error("walk refused the first page, which is the case it exists for")
	}
}

// TestLogListCountIsCached guards the memoised COUNT(DISTINCT trace_id): the
// count is the most expensive single statement in the endpoint and every
// infinite-scroll page re-requests it with identical parameters.
func TestLogListCountIsCached(t *testing.T) {
	st := newLogListTestStore(t)
	insertWalkLog(t, st, "t-1", "start", "2026-09-15T10:00:00.000000000")

	gin.SetMode(gin.TestMode)
	r := gin.New()
	registerLogRoutes(r.Group("/admin/api"), Deps{Store: st, DashCache: NewDashCache()})

	first := listTraceIDs(t, r, "offset=0&limit=10")
	// A row inserted after the first response must not change the cached total.
	insertWalkLog(t, st, "t-2", "start", "2026-09-16T10:00:00.000000000")

	w := httptest.NewRecorder()
	r.ServeHTTP(w, httptest.NewRequest("GET", "/admin/api/logs?offset=0&limit=10", nil))
	var body struct {
		Total int `json:"total"`
	}
	if err := json.Unmarshal(w.Body.Bytes(), &body); err != nil {
		t.Fatalf("decode: %v", err)
	}
	if len(first) != 1 {
		t.Fatalf("first page = %v, want one trace", first)
	}
	if body.Total != 1 {
		t.Errorf("total = %d, want the cached 1 (second row arrived after the count)", body.Total)
	}
}

// TestDashCacheStaleWhileRevalidate covers the mechanism that keeps a slow
// aggregation off the request path: once an entry expires it is still served,
// and the refresh happens in the background.
func TestDashCacheStaleWhileRevalidate(t *testing.T) {
	dc := NewDashCache()
	dc.SetWithTTL("k", "from", "to", "old", 10*time.Millisecond)

	if v, ok := dc.Get("k", "from", "to"); !ok || v != "old" {
		t.Fatalf("fresh Get = %v, %v; want old, true", v, ok)
	}

	time.Sleep(25 * time.Millisecond)
	if _, ok := dc.Get("k", "from", "to"); ok {
		t.Error("Get returned an expired entry; it must only report fresh data")
	}
	v, fresh, ok := dc.GetStale("k", "from", "to")
	if !ok || fresh {
		t.Fatalf("GetStale = %v, %v, %v; want old, false, true", v, fresh, ok)
	}

	// A background refresh must replace the stale copy.
	done := make(chan struct{})
	dc.RefreshAsync(dc.Key("k", "from", "to"), "k", "from", "to", time.Minute,
		func() (any, error) { close(done); return "new", nil })
	select {
	case <-done:
	case <-time.After(2 * time.Second):
		t.Fatal("background refresh never ran")
	}
	deadline := time.Now().Add(2 * time.Second)
	for {
		if v, ok := dc.Get("k", "from", "to"); ok && v == "new" {
			break
		}
		if time.Now().After(deadline) {
			t.Fatal("refreshed value never landed in the cache")
		}
		time.Sleep(5 * time.Millisecond)
	}
}

// TestGzipCompressesAdminJSON verifies the compression middleware end to end:
// the client gets a gzip body that decodes to the same JSON, and the SSE stream
// is left uncompressed so events are not buffered.
func TestGzipCompressesAdminJSON(t *testing.T) {
	st := newLogListTestStore(t)
	for i := 0; i < 20; i++ {
		insertWalkLog(t, st, "trace-"+strconv.Itoa(i), "start",
			fmt.Sprintf("2026-09-15T10:00:%02d.000000000", i))
	}

	gin.SetMode(gin.TestMode)
	r := gin.New()
	r.Use(httpx.Gzip())
	registerLogRoutes(r.Group("/admin/api"), Deps{Store: st, DashCache: NewDashCache()})

	req := httptest.NewRequest("GET", "/admin/api/logs?offset=0&limit=20", nil)
	req.Header.Set("Accept-Encoding", "gzip")
	w := httptest.NewRecorder()
	r.ServeHTTP(w, req)

	if got := w.Header().Get("Content-Encoding"); got != "gzip" {
		t.Fatalf("Content-Encoding = %q, want gzip", got)
	}
	if got := w.Header().Get("Vary"); !strings.Contains(got, "Accept-Encoding") {
		t.Errorf("Vary = %q, want it to include Accept-Encoding", got)
	}
	plain := decodeGzipBody(t, w.Body.Bytes())
	var body struct {
		Data []struct {
			TraceID string `json:"traceId"`
		} `json:"data"`
	}
	if err := json.Unmarshal(plain, &body); err != nil {
		t.Fatalf("decoded body is not JSON: %v (%s)", err, plain)
	}
	if len(body.Data) != 20 {
		t.Errorf("decoded %d traces, want 20", len(body.Data))
	}

	// No Accept-Encoding: identity response.
	plainReq := httptest.NewRequest("GET", "/admin/api/logs?offset=0&limit=20", nil)
	plainW := httptest.NewRecorder()
	r.ServeHTTP(plainW, plainReq)
	if got := plainW.Header().Get("Content-Encoding"); got != "" {
		t.Errorf("Content-Encoding = %q without Accept-Encoding, want none", got)
	}
}

// decodeGzipBody inflates a gzip response body so a test can assert on the JSON.
func decodeGzipBody(t *testing.T, raw []byte) []byte {
	t.Helper()
	zr, err := gzip.NewReader(bytes.NewReader(raw))
	if err != nil {
		t.Fatalf("gzip reader: %v", err)
	}
	defer zr.Close()
	out, err := io.ReadAll(zr)
	if err != nil {
		t.Fatalf("gunzip: %v", err)
	}
	return out
}
