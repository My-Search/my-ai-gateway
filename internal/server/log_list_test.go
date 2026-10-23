package server

import (
	"context"
	"database/sql"
	"encoding/json"
	"fmt"
	"net/http"
	"net/http/httptest"
	"strconv"
	"strings"
	"testing"
	"time"

	"github.com/gin-gonic/gin"
	_ "modernc.org/sqlite"

	"github.com/my-search/my-ai-gateway/internal/store"
)

// newLogListTestStore builds the request_logs subset the list endpoint reads.
// It also creates the production indexes so the EXPLAIN guard exercises the
// same access path the real database uses.
//
// created_at is declared TIMESTAMP, exactly like update.sql. That matters: the
// driver turns a TIMESTAMP column into a time.Time, so Row.Str returns "" for it.
// A TEXT column here would hide that (and once did: the keyset walk's cursor
// silently degraded to "" and truncated every page after the first chunk).
func newLogListTestStore(t *testing.T) *store.Store {
	t.Helper()
	db, err := sql.Open("sqlite", "file::memory:?_pragma=busy_timeout(30000)")
	if err != nil {
		t.Fatal(err)
	}
	db.SetMaxOpenConns(1)
	t.Cleanup(func() { db.Close() })
	if _, err := db.Exec(`CREATE TABLE request_logs (
		id INTEGER PRIMARY KEY AUTOINCREMENT,
		trace_id TEXT NOT NULL, api_key_name TEXT, gateway_api_key_id INTEGER,
		model_name TEXT, channel_model_name TEXT, channel_name TEXT,
		phase TEXT NOT NULL, status TEXT, message TEXT, retry_index INTEGER DEFAULT 0,
		response_time_ms INTEGER, first_byte_ms INTEGER,
		prompt_tokens INTEGER DEFAULT 0, completion_tokens INTEGER DEFAULT 0,
		total_tokens INTEGER DEFAULT 0, reasoning_effort TEXT, route_source TEXT,
		protocol TEXT, request_headers TEXT, request_body TEXT,
		created_at TIMESTAMP)`); err != nil {
		t.Fatal(err)
	}
	for _, ddl := range []string{
		`CREATE INDEX idx_request_logs_created_at_phase_trace ON request_logs(created_at, phase, trace_id)`,
		`CREATE INDEX idx_request_logs_trace_id_created_at ON request_logs(trace_id, created_at)`,
		// The two covering indexes from v1.47.0. Without them the recent-30
		// arms degrade to a scan plus a temp B-tree sort, which is exactly the
		// regression TestRecent30QueryUsesEqualityPhaseArms guards against.
		`CREATE INDEX idx_request_logs_channel_phase_created ON request_logs(channel_name, phase, created_at, id, channel_model_name, prompt_tokens, completion_tokens, total_tokens)`,
		`CREATE INDEX idx_request_logs_key_phase_created ON request_logs(gateway_api_key_id, phase, created_at, id, channel_model_name, prompt_tokens, completion_tokens, total_tokens)`,
	} {
		if _, err := db.Exec(ddl); err != nil {
			t.Fatal(err)
		}
	}
	return store.New(db, nil)
}

func insertListLog(t *testing.T, st *store.Store, traceID, phase, createdAt string) {
	t.Helper()
	if _, err := st.Exec(context.Background(),
		`INSERT INTO request_logs (trace_id, phase, model_name, created_at, request_headers, request_body)
		 VALUES (?,?,?,?,?,?)`,
		traceID, phase, "gpt-4o", createdAt, strings.Repeat("h", 2000), strings.Repeat("b", 2000)); err != nil {
		t.Fatal(err)
	}
}

// TestLogListTimeFilterUsesIndex is the performance guard for the list
// endpoint. The exact bound must go through datetime() (mixed timestamp
// formats), which is not sargable, so a coarse bare-date bound has to be
// present for SQLite to open the created_at index. Without it the page and
// count queries degrade to a full scan over every log row.
func TestLogListTimeFilterUsesIndex(t *testing.T) {
	st := newLogListTestStore(t)
	// Spread rows over a year so the month range is selective; with a single
	// row the planner would just pick whichever covering index is cheapest.
	for m := 1; m <= 12; m++ {
		for d := 1; d <= 20; d++ {
			insertListLog(t, st, "t-"+strconv.Itoa(m)+"-"+strconv.Itoa(d), "success",
				fmt.Sprintf("2026-%02d-%02dT10:00:00.000000000", m, d))
		}
	}
	if _, err := st.Exec(context.Background(), "ANALYZE"); err != nil {
		t.Fatal(err)
	}

	// Mirrors the count query the handler builds for a month filter.
	plan := explainQueryPlan(t, st,
		`SELECT COUNT(DISTINCT r.trace_id) as total FROM request_logs r
		  WHERE 1=1 AND r.created_at >= ? AND r.created_at < ?
		    AND datetime(r.created_at) >= datetime(?)
		    AND datetime(r.created_at) <= datetime(?)`,
		"2026-09-01", "2026-10-01", "2026-09-01T00:00:00.000Z", "2026-09-30T23:59:59.999Z")

	if strings.Contains(plan, "SCAN request_logs") {
		t.Errorf("log list query falls back to a full table scan; plan = %s", plan)
	}
	if !strings.Contains(plan, "idx_request_logs_created_at_phase_trace") {
		t.Errorf("log list query does not use the created_at index; plan = %s", plan)
	}
}

// TestLogListCountPinsRangeIndexForNarrowWindows covers the count query's
// index hint. The planner answers a bounded month by walking the whole
// idx_request_logs_trace_id_created_at index (3.9s at 4M rows) because it is
// covering; pinning the created_at index makes it a range scan instead (1.0s).
// Over a year the pin is a 1.8x regression, so the hint must stay conditional.
func TestLogListCountPinsRangeIndexForNarrowWindows(t *testing.T) {
	st := newLogListTestStore(t)
	for m := 1; m <= 12; m++ {
		for d := 1; d <= 20; d++ {
			insertListLog(t, st, "t-"+strconv.Itoa(m)+"-"+strconv.Itoa(d), "success",
				fmt.Sprintf("2026-%02d-%02dT10:00:00.000000000", m, d))
		}
	}
	if _, err := st.Exec(context.Background(), "ANALYZE"); err != nil {
		t.Fatal(err)
	}

	plan := explainQueryPlan(t, st,
		`SELECT COUNT(DISTINCT r.trace_id) as total
		   FROM request_logs r INDEXED BY idx_request_logs_created_at_phase_trace
		  WHERE 1=1 AND r.created_at >= ? AND r.created_at < ?
		    AND datetime(r.created_at) >= datetime(?)
		    AND datetime(r.created_at) <= datetime(?)`,
		"2026-09-01", "2026-10-01", "2026-09-01T00:00:00.000Z", "2026-09-30T23:59:59.999Z")

	if strings.Contains(plan, "idx_request_logs_trace_id_created_at") {
		t.Errorf("pinned count still walks the trace_id index; plan = %s", plan)
	}
	if !strings.Contains(plan, "SEARCH r USING COVERING INDEX idx_request_logs_created_at_phase_trace") {
		t.Errorf("pinned count is not a created_at range scan; plan = %s", plan)
	}
}

// TestCountUsesRangeIndex pins the decision boundary. Getting this wrong is a
// silent 1.8-3.4x slowdown, not an error, so the thresholds are worth asserting.
func TestCountUsesRangeIndex(t *testing.T) {
	now := time.Now()
	cases := []struct {
		name       string
		start, end string
		want       bool
	}{
		{"no filter", "", "", false},
		{"start only, long history", "2020-01-01T00:00:00.000Z", "", false},
		{"one day", "2026-09-15T00:00:00.000Z", "2026-09-16T00:00:00.000Z", true},
		{"one month", "2026-09-01T00:00:00.000Z", "2026-09-30T23:59:59.999Z", true},
		{"one year", "2026-01-01T00:00:00.000Z", "2026-12-31T23:59:59.999Z", false},
		{"start only, recent", now.Add(-24 * time.Hour).Format(time.RFC3339), "", true},
		{"end before start", "2026-09-30T00:00:00.000Z", "2026-09-01T00:00:00.000Z", false},
		{"unparseable start", "not-a-date", "2026-09-30T00:00:00.000Z", false},
	}
	for _, tc := range cases {
		if got := countUsesRangeIndex(tc.start, tc.end); got != tc.want {
			t.Errorf("%s: countUsesRangeIndex(%q, %q) = %v, want %v",
				tc.name, tc.start, tc.end, got, tc.want)
		}
	}
}

// TestLogListCoarseBoundsKeepExactResults verifies the sargable rewrite is
// result-preserving: rows just outside the requested instant window must stay
// excluded, while both stored timestamp shapes inside it stay included.
func TestLogListCoarseBoundsKeepExactResults(t *testing.T) {
	st := newLogListTestStore(t)
	insertListLog(t, st, "t-inside-t", "start", "2026-09-15T10:00:00.000000000")
	insertListLog(t, st, "t-inside-space", "start", "2026-09-15 10:00:00")
	insertListLog(t, st, "t-before", "start", "2026-08-31T23:59:59.000000000")
	insertListLog(t, st, "t-after", "start", "2026-10-01T00:00:01.000000000")

	gin.SetMode(gin.TestMode)
	r := gin.New()
	registerLogRoutes(r.Group("/admin/api"), Deps{Store: st, DashCache: NewDashCache()})

	w := httptest.NewRecorder()
	req := httptest.NewRequest("GET",
		"/admin/api/logs?startTime=2026-09-01T00:00:00.000Z&endTime=2026-09-30T23:59:59.999Z", nil)
	r.ServeHTTP(w, req)

	if w.Code != http.StatusOK {
		t.Fatalf("status = %d, body = %s", w.Code, w.Body.String())
	}
	var body struct {
		Total int `json:"total"`
		Data  []struct {
			TraceID string `json:"traceId"`
		} `json:"data"`
	}
	if err := json.Unmarshal(w.Body.Bytes(), &body); err != nil {
		t.Fatalf("decode: %v", err)
	}
	if body.Total != 2 {
		t.Errorf("total = %d, want 2 (both timestamp formats inside September)", body.Total)
	}
	if len(body.Data) != 2 {
		t.Fatalf("data length = %d, want 2", len(body.Data))
	}
	for _, tr := range body.Data {
		if tr.TraceID == "t-before" || tr.TraceID == "t-after" {
			t.Errorf("trace %q leaked outside the requested window", tr.TraceID)
		}
	}
}

// TestLogListReturnsErrorOnQueryFailure makes sure a broken query surfaces as
// a 500 instead of an empty page, so the UI does not claim there are no logs.
func TestLogListReturnsErrorOnQueryFailure(t *testing.T) {
	st := newLogListTestStore(t)
	if _, err := st.Exec(context.Background(), "DROP TABLE request_logs"); err != nil {
		t.Fatal(err)
	}

	gin.SetMode(gin.TestMode)
	r := gin.New()
	registerLogRoutes(r.Group("/admin/api"), Deps{Store: st, DashCache: NewDashCache()})

	w := httptest.NewRecorder()
	r.ServeHTTP(w, httptest.NewRequest("GET", "/admin/api/logs", nil))

	if w.Code != http.StatusInternalServerError {
		t.Fatalf("status = %d, want 500; body = %s", w.Code, w.Body.String())
	}
}

// TestLogListOmitsRequestPayloads ensures the list query never reads the large
// raw request columns: they are loaded on demand and would otherwise dominate
// the response time at scale.
func TestLogListOmitsRequestPayloads(t *testing.T) {
	st := newLogListTestStore(t)
	insertListLog(t, st, "t-1", "start", "2026-09-15T10:00:00.000000000")

	gin.SetMode(gin.TestMode)
	r := gin.New()
	registerLogRoutes(r.Group("/admin/api"), Deps{Store: st, DashCache: NewDashCache()})

	w := httptest.NewRecorder()
	r.ServeHTTP(w, httptest.NewRequest("GET", "/admin/api/logs", nil))

	var body struct {
		Data []struct {
			Logs []map[string]any `json:"logs"`
		} `json:"data"`
	}
	if err := json.Unmarshal(w.Body.Bytes(), &body); err != nil {
		t.Fatalf("decode: %v", err)
	}
	if len(body.Data) != 1 || len(body.Data[0].Logs) != 1 {
		t.Fatalf("unexpected payload shape: %s", w.Body.String())
	}
	for _, key := range []string{"requestHeaders", "requestBody"} {
		if v, ok := body.Data[0].Logs[0][key]; ok && v != nil {
			t.Errorf("%s must not be returned by the list API, got %v", key, v)
		}
	}
}
