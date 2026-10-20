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

	"github.com/gin-gonic/gin"
	_ "modernc.org/sqlite"

	"github.com/my-search/my-ai-gateway/internal/store"
)

// newLogListTestStore builds the request_logs subset the list endpoint reads.
// It also creates the production indexes so the EXPLAIN guard exercises the
// same access path the real database uses.
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
		created_at TEXT)`); err != nil {
		t.Fatal(err)
	}
	for _, ddl := range []string{
		`CREATE INDEX idx_request_logs_created_at_phase_trace ON request_logs(created_at, phase, trace_id)`,
		`CREATE INDEX idx_request_logs_trace_id_created_at ON request_logs(trace_id, created_at)`,
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
