package server

import (
	"context"
	"database/sql"
	"encoding/json"
	"net/http/httptest"
	"strings"
	"testing"

	"github.com/gin-gonic/gin"
	_ "modernc.org/sqlite"

	"github.com/my-search/my-ai-gateway/internal/store"
)

// newUsageChartTestStore builds the subset of request_logs the usage-chart
// aggregation touches. The column set is intentionally wider than
// newDashboardTestStore's: the entry-model query aggregates total_tokens and
// groups by model_name, so both must exist or the query errors at runtime.
//
// The real index (idx_usage_entry, migration v1.40.0) is created here too, so
// the test exercises the same access path production uses rather than a
// full-scan fallback that would hide a broken predicate.
func newUsageChartTestStore(t *testing.T) *store.Store {
	t.Helper()
	db, err := sql.Open("sqlite", "file::memory:?_pragma=busy_timeout(30000)")
	if err != nil {
		t.Fatal(err)
	}
	// A plain ":memory:" database is per-connection; keep one connection so the
	// schema and rows created below stay visible.
	db.SetMaxOpenConns(1)
	t.Cleanup(func() { db.Close() })
	if _, err := db.Exec(`CREATE TABLE request_logs (
		id INTEGER PRIMARY KEY AUTOINCREMENT,
		trace_id TEXT, phase TEXT, model_name TEXT, channel_name TEXT,
		channel_model_name TEXT, total_tokens INTEGER DEFAULT 0, created_at TEXT)`); err != nil {
		t.Fatal(err)
	}
	if _, err := db.Exec(`CREATE INDEX idx_usage_entry
		ON request_logs(created_at, model_name, phase, total_tokens, trace_id)
		WHERE model_name IS NOT NULL AND model_name != ''`); err != nil {
		t.Fatal(err)
	}
	return store.New(db, nil)
}

// insertUsageRow writes one request_logs row with an explicit timestamp string,
// so a test can choose between the two representations found in production:
// "2026-09-01T10:00:00.000000000" (app writes) and "2026-09-01 10:00:00"
// (SQL DEFAULT CURRENT_TIMESTAMP).
func insertUsageRow(t *testing.T, st *store.Store, traceID, phase, modelName, createdAt string, tokens int64) {
	t.Helper()
	if _, err := st.Exec(context.Background(),
		`INSERT INTO request_logs (trace_id, phase, model_name, channel_name, channel_model_name, total_tokens, created_at)
		 VALUES (?,?,?,?,?,?,?)`,
		traceID, phase, modelName, "c1", "cm1", tokens, createdAt); err != nil {
		t.Fatal(err)
	}
}

// fetchUsageChart serves one usage-chart request through the real route
// registration and decodes the response. The handler writes its fields at the
// top level (there is no {data: ...} envelope — the front-end reads them off
// the axios response body directly).
func fetchUsageChart(t *testing.T, st *store.Store, query string) map[string]any {
	t.Helper()
	gin.SetMode(gin.TestMode)
	r := gin.New()
	registerLogRoutes(r.Group("/admin/api"), Deps{Store: st})

	w := httptest.NewRecorder()
	req := httptest.NewRequest("GET", "/admin/api/logs/usage-chart?"+query, nil)
	r.ServeHTTP(w, req)

	if w.Code != 200 {
		t.Fatalf("status = %d, body = %s", w.Code, w.Body.String())
	}
	var body map[string]any
	if err := json.Unmarshal(w.Body.Bytes(), &body); err != nil {
		t.Fatalf("decode response: %v (body=%s)", err, w.Body.String())
	}
	return body
}

// asFloatSlice reads one model's per-day array out of a {model: [..]} object,
// which decodes as map[string]any with []any values.
func asFloatSlice(t *testing.T, obj map[string]any, model string) []float64 {
	t.Helper()
	raw, ok := obj[model]
	if !ok {
		t.Fatalf("model %q missing from %v", model, obj)
	}
	arr, ok := raw.([]any)
	if !ok {
		t.Fatalf("model %q is %T, want array", model, raw)
	}
	out := make([]float64, len(arr))
	for i, v := range arr {
		f, ok := v.(float64)
		if !ok {
			t.Fatalf("model %q[%d] is %T, want number", model, i, v)
		}
		out[i] = f
	}
	return out
}

func sum(nums []float64) float64 {
	var s float64
	for _, n := range nums {
		s += n
	}
	return s
}

// TestUsageChartCountsBothTimestampFormats is the regression guard for the
// sargable-rewrite boundary bug. The range bounds are now bare date prefixes
// compared against the raw column; a naive rewrite passing a full
// "2026-09-01T00:00:00" bound instead would compare greater than the
// space-separated form and silently drop every such row from the month.
func TestUsageChartCountsBothTimestampFormats(t *testing.T) {
	st := newUsageChartTestStore(t)

	// T-format (written by the relay log writer).
	insertUsageRow(t, st, "t-t-format", "success", "alpha", "2026-09-01T10:00:00.000000000", 100)
	// Space-format (SQL DEFAULT CURRENT_TIMESTAMP), mid-month.
	insertUsageRow(t, st, "t-space", "success", "alpha", "2026-09-15 10:00:00", 200)
	// Space-format landing exactly on the month's first instant: the case a
	// T-suffixed lower bound would have excluded.
	insertUsageRow(t, st, "t-space-midnight", "success", "gamma", "2026-09-01 00:00:00", 7)

	data := fetchUsageChart(t, st, "year=2026&month=9&modelType=entry")

	tokenValues, ok := data["tokenValues"].(map[string]any)
	if !ok {
		t.Fatalf("tokenValues is %T, want object", data["tokenValues"])
	}

	alpha := asFloatSlice(t, tokenValues, "alpha")
	if got := sum(alpha); got != 300 {
		t.Errorf("alpha total = %v, want 300 (T-format 100 + space-format 200); per-day=%v", got, alpha)
	}
	if alpha[0] != 100 {
		t.Errorf("alpha[day 1] = %v, want 100", alpha[0])
	}
	if alpha[14] != 200 {
		t.Errorf("alpha[day 15] = %v, want 200 (space-format row was dropped)", alpha[14])
	}

	gamma := asFloatSlice(t, tokenValues, "gamma")
	if gamma[0] != 7 {
		t.Errorf("gamma[day 1] = %v, want 7 (space-format row at month start was dropped)", gamma[0])
	}
}

// TestUsageChartMonthBoundaries pins the half-open [since, until) window: the
// last instant of the month is inside, the first instant of the next month is
// not.
func TestUsageChartMonthBoundaries(t *testing.T) {
	st := newUsageChartTestStore(t)

	insertUsageRow(t, st, "t-last", "success", "beta", "2026-09-30T23:59:59.000000000", 50)
	insertUsageRow(t, st, "t-next", "success", "beta", "2026-10-01T00:00:00.000000000", 999)
	insertUsageRow(t, st, "t-prev", "success", "beta", "2026-08-31T23:59:59.000000000", 888)

	data := fetchUsageChart(t, st, "year=2026&month=9&modelType=entry")
	tokenValues := data["tokenValues"].(map[string]any)

	beta := asFloatSlice(t, tokenValues, "beta")
	if got := sum(beta); got != 50 {
		t.Errorf("beta total = %v, want 50 (only the 09-30 row; 10-01 and 08-31 excluded); per-day=%v", got, beta)
	}
	if len(beta) != 30 {
		t.Errorf("beta has %d days, want 30 for September", len(beta))
	}
	if beta[29] != 50 {
		t.Errorf("beta[day 30] = %v, want 50", beta[29])
	}
}

// TestUsageChartTotalsMatchTokenValues checks maxValue and totalValue are
// derived from tokenValues, the single bar-height measure.
func TestUsageChartTotalsMatchTokenValues(t *testing.T) {
	st := newUsageChartTestStore(t)

	// Day 1 stacks 100+7=107; day 15 is 200 (the tallest); day 10 contributes
	// nothing because the only row is a non-success 'start' phase.
	insertUsageRow(t, st, "t-1a", "success", "alpha", "2026-09-01T10:00:00.000000000", 100)
	insertUsageRow(t, st, "t-1b", "success", "gamma", "2026-09-01 10:00:00", 7)
	insertUsageRow(t, st, "t-2", "success", "alpha", "2026-09-15 10:00:00", 200)
	insertUsageRow(t, st, "t-3", "start", "alpha", "2026-09-10T10:00:00.000000000", 0)

	data := fetchUsageChart(t, st, "year=2026&month=9&modelType=entry")
	tokenValues := data["tokenValues"].(map[string]any)

	var grand float64
	day0, day14 := 0.0, 0.0
	for _, model := range []string{"alpha", "gamma"} {
		arr := asFloatSlice(t, tokenValues, model)
		grand += sum(arr)
		day0 += arr[0]
		day14 += arr[14]
	}

	wantMax := day0
	if day14 > wantMax {
		wantMax = day14
	}
	if got := data["maxValue"].(float64); got != wantMax {
		t.Errorf("maxValue = %v, want %v (tallest stacked day)", got, wantMax)
	}
	if got := data["totalValue"].(float64); got != grand {
		t.Errorf("totalValue = %v, want %v", got, grand)
	}
}

// TestUsageChartOmitsRedundantValuesField guards the payload slimming: `values`
// was always identical to `tokenValues`, so shipping it tripled the size of the
// largest part of the response for no information.
func TestUsageChartOmitsRedundantValuesField(t *testing.T) {
	st := newUsageChartTestStore(t)
	insertUsageRow(t, st, "t-1", "success", "alpha", "2026-09-01T10:00:00.000000000", 100)

	data := fetchUsageChart(t, st, "year=2026&month=9&modelType=entry")

	if _, present := data["values"]; present {
		t.Error("response still carries the redundant `values` field")
	}
	if _, present := data["tokenValues"]; !present {
		t.Error("response is missing `tokenValues`")
	}
}

// TestUsageChartQueryUsesCoveringIndex is the guard for the access path itself.
// A partial index is only usable when the query's WHERE implies the index's
// WHERE, and "covering" additionally requires every referenced column to be in
// the index. Both properties are invisible in the query's result set, so
// without this test a change to the predicate or a column in the projection
// silently reintroduces the full scan this work set out to remove.
func TestUsageChartQueryUsesCoveringIndex(t *testing.T) {
	st := newUsageChartTestStore(t)
	insertUsageRow(t, st, "t-1", "success", "alpha", "2026-09-01T10:00:00.000000000", 100)
	if _, err := st.Exec(context.Background(), "ANALYZE"); err != nil {
		t.Fatal(err)
	}

	// Mirrors the entry-model branch of the handler verbatim.
	plan := explainQueryPlan(t, st,
		`SELECT DATE(created_at) AS date, model_name,
		        COALESCE(SUM(CASE WHEN phase = 'success' THEN COALESCE(total_tokens, 0) ELSE 0 END), 0) AS total_tokens,
		        COUNT(DISTINCT CASE WHEN phase = 'success' THEN trace_id END) AS request_count
		   FROM request_logs
		  WHERE created_at >= ? AND created_at < ?
		    AND model_name IS NOT NULL AND model_name != ''
		  GROUP BY DATE(created_at), model_name
		  ORDER BY date ASC, total_tokens DESC`,
		"2026-09-01", "2026-10-01")

	if !strings.Contains(plan, "COVERING INDEX") {
		t.Errorf("usage-chart entry query is not using a covering index; plan = %s", plan)
	}
	if strings.Contains(plan, "SCAN request_logs") {
		t.Errorf("usage-chart entry query falls back to a full table scan; plan = %s", plan)
	}
}

// explainQueryPlan runs EXPLAIN QUERY PLAN and returns the concatenated detail
// lines.
func explainQueryPlan(t *testing.T, st *store.Store, query string, args ...any) string {
	t.Helper()
	rows, err := st.Query(context.Background(), "EXPLAIN QUERY PLAN "+query, args...)
	if err != nil {
		t.Fatalf("EXPLAIN QUERY PLAN: %v", err)
	}
	var b strings.Builder
	for _, r := range rows {
		b.WriteString(r.Str("detail"))
		b.WriteString("\n")
	}
	return b.String()
}

// TestUsageChartCachesRepeatedRequests verifies the DashCache wiring: a second
// identical request is served from cache, so the 15s front-end poll does not
// re-run the aggregation each time.
func TestUsageChartCachesRepeatedRequests(t *testing.T) {
	st := newUsageChartTestStore(t)
	insertUsageRow(t, st, "t-1", "success", "alpha", "2026-09-01T10:00:00.000000000", 100)

	gin.SetMode(gin.TestMode)
	r := gin.New()
	registerLogRoutes(r.Group("/admin/api"), Deps{Store: st, DashCache: NewDashCache()})

	get := func() map[string]any {
		w := httptest.NewRecorder()
		r.ServeHTTP(w, httptest.NewRequest("GET",
			"/admin/api/logs/usage-chart?year=2026&month=9&modelType=entry", nil))
		var body map[string]any
		if err := json.Unmarshal(w.Body.Bytes(), &body); err != nil {
			t.Fatalf("decode: %v", err)
		}
		return body
	}

	first := get()
	// Mutate the table directly: a cache miss would pick this up, a cache hit
	// returns the previously computed snapshot.
	insertUsageRow(t, st, "t-2", "success", "alpha", "2026-09-02T10:00:00.000000000", 500)
	second := get()

	if got, want := second["totalValue"].(float64), first["totalValue"].(float64); got != want {
		t.Errorf("second request totalValue = %v, want cached %v", got, want)
	}
}
