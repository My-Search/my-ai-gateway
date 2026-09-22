package server

import (
	"context"
	"database/sql"
	"encoding/json"
	"net/http/httptest"
	"testing"
	"time"

	"github.com/gin-gonic/gin"
	_ "modernc.org/sqlite"

	"github.com/my-search/my-ai-gateway/internal/jtime"
	"github.com/my-search/my-ai-gateway/internal/store"
)

// --- helpers ---------------------------------------------------------------

// dashTestCtx builds a gin context carrying the given query string so
// parseDashRange can be exercised without a full router.
func dashTestCtx(rawQuery string) *gin.Context {
	gin.SetMode(gin.TestMode)
	c, _ := gin.CreateTestContext(httptest.NewRecorder())
	c.Request = httptest.NewRequest("GET", "/admin/api/dashboard/stats?"+rawQuery, nil)
	return c
}

// shanghai builds a UTC instant from a Shanghai wall-clock description, so test
// expectations read in the same timezone the feature is defined in.
func shanghai(y int, mo time.Month, d, h, mi, s int) time.Time {
	return time.Date(y, mo, d, h, mi, s, 0, jtime.Shanghai).UTC()
}

func newDashboardTestStore(t *testing.T) *store.Store {
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
		channel_model_name TEXT, total_tokens INTEGER DEFAULT 0,
		first_byte_ms INTEGER, gateway_api_key_id INTEGER, created_at TEXT)`); err != nil {
		t.Fatal(err)
	}
	// The dashboard queries pin this index with INDEXED BY, so it must exist.
	if _, err := db.Exec(
		`CREATE INDEX idx_request_logs_created_at_phase_trace ON request_logs(created_at, phase, trace_id)`); err != nil {
		t.Fatal(err)
	}
	// The key ranking joins api_keys for the display name.
	if _, err := db.Exec(
		`CREATE TABLE api_keys (id INTEGER PRIMARY KEY AUTOINCREMENT, key_name TEXT NOT NULL)`); err != nil {
		t.Fatal(err)
	}
	return store.New(db, nil)
}

// insertLog writes one request_logs row the way the relay writer does, using
// the 9-digit fractional-second layout.
func insertLog(t *testing.T, st *store.Store, traceID, phase string, at time.Time) {
	t.Helper()
	if _, err := st.Exec(context.Background(),
		`INSERT INTO request_logs (trace_id, phase, model_name, channel_name, channel_model_name, created_at)
		 VALUES (?,?,?,?,?,?)`,
		traceID, phase, "m1", "c1", "cm1", at.UTC().Format("2006-01-02T15:04:05.000000000")); err != nil {
		t.Fatal(err)
	}
}

// insertKeyLog writes a request_logs row attributed to a gateway API key, with
// the first-byte / token columns the key ranking aggregates. firstByteMs is
// written verbatim (0 rows are excluded from avg_time by the >0 predicate).
func insertKeyLog(t *testing.T, st *store.Store, traceID, phase string, at time.Time,
	keyID, firstByteMs, tokens int64) {
	t.Helper()
	if _, err := st.Exec(context.Background(),
		`INSERT INTO request_logs
		   (trace_id, phase, model_name, channel_name, channel_model_name,
		    gateway_api_key_id, first_byte_ms, total_tokens, created_at)
		 VALUES (?,?,?,?,?,?,?,?,?)`,
		traceID, phase, "m1", "c1", "cm1", keyID, firstByteMs, tokens,
		at.UTC().Format("2006-01-02T15:04:05.000000000")); err != nil {
		t.Fatal(err)
	}
}

// insertAPIKey creates an api_keys row with an explicit id so a test can point
// logs at it (and at ids that intentionally have no row).
func insertAPIKey(t *testing.T, st *store.Store, id int64, name string) {
	t.Helper()
	if _, err := st.Exec(context.Background(),
		"INSERT INTO api_keys (id, key_name) VALUES (?, ?)", id, name); err != nil {
		t.Fatal(err)
	}
}

// --- parseDashRange --------------------------------------------------------

func TestParseDashRangeDateOnlyKeepsWholeDaySemantics(t *testing.T) {
	dr := parseDashRange(dashTestCtx("range=custom&from=2026-09-01&to=2026-09-17"))

	if got, want := dr.key, "custom"; got != want {
		t.Errorf("key = %q, want %q", got, want)
	}
	if want := shanghai(2026, 9, 1, 0, 0, 0); !dr.since.Equal(want) {
		t.Errorf("since = %v, want %v", dr.since.In(jtime.Shanghai), want.In(jtime.Shanghai))
	}
	// The end date means the whole day: until = 2026-09-18 00:00 (exclusive).
	if want := shanghai(2026, 9, 18, 0, 0, 0); !dr.until.Equal(want) {
		t.Errorf("until = %v, want %v (to + 1 day)", dr.until.In(jtime.Shanghai), want.In(jtime.Shanghai))
	}
	// Previous window is the immediately preceding equal-length span.
	if !dr.prevUntil.Equal(dr.since) {
		t.Errorf("prevUntil = %v, want %v", dr.prevUntil, dr.since)
	}
	if got, want := dr.prevUntil.Sub(dr.prevSince), dr.until.Sub(dr.since); got != want {
		t.Errorf("prev span = %v, want %v", got, want)
	}
}

func TestParseDashRangeCustomWithTime(t *testing.T) {
	cases := []struct {
		name      string
		query     string
		wantSince time.Time
		wantUntil time.Time
	}{
		{
			name:      "seconds precision",
			query:     "range=custom&from=2026-09-01T09:30:00&to=2026-09-01T12:00:00",
			wantSince: shanghai(2026, 9, 1, 9, 30, 0),
			// The end second itself is included, so until = to + 1s.
			wantUntil: shanghai(2026, 9, 1, 12, 0, 1),
		},
		{
			name:      "minutes precision",
			query:     "range=custom&from=2026-09-01T09:30&to=2026-09-01T12:00",
			wantSince: shanghai(2026, 9, 1, 9, 30, 0),
			wantUntil: shanghai(2026, 9, 1, 12, 0, 1),
		},
		{
			name:      "space separator",
			query:     "range=custom&from=2026-09-01%2009:30:00&to=2026-09-01%2012:00:00",
			wantSince: shanghai(2026, 9, 1, 9, 30, 0),
			wantUntil: shanghai(2026, 9, 1, 12, 0, 1),
		},
		{
			name:      "explicit offset honoured",
			query:     "range=custom&from=2026-09-01T01:30:00Z&to=2026-09-01T04:00:00Z",
			wantSince: shanghai(2026, 9, 1, 9, 30, 0),
			wantUntil: shanghai(2026, 9, 1, 12, 0, 1),
		},
		{
			name:      "end at 23:59:59 covers the whole day",
			query:     "range=custom&from=2026-09-01T00:00:00&to=2026-09-01T23:59:59",
			wantSince: shanghai(2026, 9, 1, 0, 0, 0),
			wantUntil: shanghai(2026, 9, 2, 0, 0, 0),
		},
		{
			name:      "date-only start with 23:59:59 end",
			query:     "range=custom&from=2026-09-01&to=2026-09-01T23:59:59",
			wantSince: shanghai(2026, 9, 1, 0, 0, 0),
			wantUntil: shanghai(2026, 9, 2, 0, 0, 0),
		},
		{
			name:      "missing to means a single instant",
			query:     "range=custom&from=2026-09-01T09:30:00",
			wantSince: shanghai(2026, 9, 1, 9, 30, 0),
			wantUntil: shanghai(2026, 9, 1, 9, 30, 1),
		},
		{
			name:      "invalid to falls back to from",
			query:     "range=custom&from=2026-09-01&to=garbage",
			wantSince: shanghai(2026, 9, 1, 0, 0, 0),
			wantUntil: shanghai(2026, 9, 2, 0, 0, 0),
		},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			dr := parseDashRange(dashTestCtx(tc.query))
			if !dr.since.Equal(tc.wantSince) {
				t.Errorf("since = %v, want %v", dr.since.In(jtime.Shanghai), tc.wantSince.In(jtime.Shanghai))
			}
			if !dr.until.Equal(tc.wantUntil) {
				t.Errorf("until = %v, want %v", dr.until.In(jtime.Shanghai), tc.wantUntil.In(jtime.Shanghai))
			}
			// Custom windows compare against the immediately preceding equal span.
			if got, want := dr.prevUntil.Sub(dr.prevSince), dr.until.Sub(dr.since); got != want {
				t.Errorf("prev span = %v, want %v", got, want)
			}
			if !dr.prevUntil.Equal(dr.since) {
				t.Errorf("prevUntil = %v, want %v", dr.prevUntil, dr.since)
			}
		})
	}
}

func TestParseDashRangeRejectsBadCustomInput(t *testing.T) {
	// These must fall back to today rather than querying a bogus window.
	for _, q := range []string{
		"range=custom",
		"range=custom&from=garbage",
		"range=custom&from=2026-09-17T10:00:00&to=2026-09-01T10:00:00", // inverted
	} {
		t.Run(q, func(t *testing.T) {
			dr := parseDashRange(dashTestCtx(q))
			if dr.key != "today" {
				t.Errorf("key = %q, want today", dr.key)
			}
			// "today" is a whole Shanghai day.
			if got := dr.until.Sub(dr.since); got != 24*time.Hour {
				t.Errorf("span = %v, want 24h", got)
			}
			sh := dr.since.In(jtime.Shanghai)
			if sh.Hour() != 0 || sh.Minute() != 0 || sh.Second() != 0 {
				t.Errorf("since = %v, want Shanghai midnight", sh)
			}
		})
	}
}

func TestParseDashRangeClampsToMaxSpan(t *testing.T) {
	dr := parseDashRange(dashTestCtx("range=custom&from=2020-01-01T00:00:00&to=2026-09-17T12:00:00"))
	maxSpan := time.Duration(dashMaxRangeDays) * 24 * time.Hour
	if got := dr.until.Sub(dr.since); got != maxSpan {
		t.Errorf("span = %v, want clamped to %v", got, maxSpan)
	}
	// The clamp keeps the end fixed and moves the start forward.
	if want := shanghai(2026, 9, 17, 12, 0, 1); !dr.until.Equal(want) {
		t.Errorf("until = %v, want %v", dr.until.In(jtime.Shanghai), want.In(jtime.Shanghai))
	}
}

func TestDashboardRangeMetaReportsSubDayBounds(t *testing.T) {
	dr := parseDashRange(dashTestCtx("range=custom&from=2026-09-01T09:30:00&to=2026-09-01T12:00:00"))
	meta := dr.meta()

	if got, want := meta["start"], "2026-09-01"; got != want {
		t.Errorf("start = %v, want %v", got, want)
	}
	if got, want := meta["end"], "2026-09-01"; got != want {
		t.Errorf("end = %v, want %v", got, want)
	}
	// startAt/endAt keep time-of-day precision (end is inclusive).
	if got, want := meta["startAt"], "2026-09-01T09:30:00"; got != want {
		t.Errorf("startAt = %v, want %v", got, want)
	}
	if got, want := meta["endAt"], "2026-09-01T12:00:00"; got != want {
		t.Errorf("endAt = %v, want %v", got, want)
	}

	// Date-only windows report whole-day bounds.
	day := parseDashRange(dashTestCtx("range=custom&from=2026-09-01&to=2026-09-17")).meta()
	if got, want := day["startAt"], "2026-09-01T00:00:00"; got != want {
		t.Errorf("startAt = %v, want %v", got, want)
	}
	if got, want := day["endAt"], "2026-09-17T23:59:59"; got != want {
		t.Errorf("endAt = %v, want %v", got, want)
	}

	// Shortcut ranges keep whole-day metadata as well.
	today := parseDashRange(dashTestCtx("range=today")).meta()
	if got, want := today["endAt"], dashDate(time.Now().UTC())+"T23:59:59"; got != want {
		t.Errorf("today endAt = %v, want %v", got, want)
	}
}

func TestDashParseInstant(t *testing.T) {
	cases := []struct {
		in       string
		wantTime time.Time
		wantHas  bool
		wantOK   bool
	}{
		{"2026-09-01", shanghai(2026, 9, 1, 0, 0, 0), false, true},
		{"  2026-09-01  ", shanghai(2026, 9, 1, 0, 0, 0), false, true},
		{"2026-09-01T09:30", shanghai(2026, 9, 1, 9, 30, 0), true, true},
		{"2026-09-01T09:30:15", shanghai(2026, 9, 1, 9, 30, 15), true, true},
		{"2026-09-01 09:30:15", shanghai(2026, 9, 1, 9, 30, 15), true, true},
		{"2026-09-01T01:30:15Z", shanghai(2026, 9, 1, 9, 30, 15), true, true},
		{"2026-09-01T09:30:15+08:00", shanghai(2026, 9, 1, 9, 30, 15), true, true},
		{"", time.Time{}, false, false},
		{"nonsense", time.Time{}, false, false},
		{"2026-13-45", time.Time{}, false, false},
	}
	for _, tc := range cases {
		got, hasTime, ok := dashParseInstant(tc.in)
		if ok != tc.wantOK {
			t.Errorf("dashParseInstant(%q) ok = %v, want %v", tc.in, ok, tc.wantOK)
			continue
		}
		if !ok {
			continue
		}
		if hasTime != tc.wantHas {
			t.Errorf("dashParseInstant(%q) hasTime = %v, want %v", tc.in, hasTime, tc.wantHas)
		}
		if !got.Equal(tc.wantTime) {
			t.Errorf("dashParseInstant(%q) = %v, want %v", tc.in, got, tc.wantTime)
		}
	}
}

// --- trend bucketing -------------------------------------------------------

// trendBuckets runs the real trend endpoint against the given store and returns
// the response bucket labels plus per-bucket counts for the named series keys.
// "all" mode exposes success/fail; entry/channel modes key series by model.
func trendBuckets(t *testing.T, st *store.Store, dr dashRange, mode string, seriesKeys ...string) ([]string, map[string]int64) {
	t.Helper()
	router := gin.New()
	registerDashboardRoutes(router.Group("/admin/api"), Deps{Store: st})

	url := "/admin/api/dashboard/today-trend?mode=" + mode +
		"&range=custom&from=" + dashDateTime(dr.since) +
		"&to=" + dashDateTime(dr.until.Add(-time.Second))
	rec := httptest.NewRecorder()
	router.ServeHTTP(rec, httptest.NewRequest("GET", url, nil))
	if rec.Code != 200 {
		t.Fatalf("status = %d, body = %s", rec.Code, rec.Body.String())
	}

	var body struct {
		Buckets []string           `json:"buckets"`
		Series  map[string][]int64 `json:"series"`
	}
	if err := json.Unmarshal(rec.Body.Bytes(), &body); err != nil {
		t.Fatal(err)
	}
	if len(seriesKeys) == 0 {
		seriesKeys = []string{"success"}
	}
	out := make(map[string]int64, len(body.Buckets))
	for _, key := range seriesKeys {
		for i, b := range body.Buckets {
			if i < len(body.Series[key]) {
				out[b] += body.Series[key][i]
			}
		}
	}
	return body.Buckets, out
}

// A window longer than 24h must not fold two different days that share the same
// clock time into a single label (the bug a wall-clock grid would introduce).
func TestTrendBucketsMultiDayWindowKeepsDaysApart(t *testing.T) {
	st := newDashboardTestStore(t)
	insertLog(t, st, "t1", "start", shanghai(2026, 9, 1, 10, 3, 0))
	insertLog(t, st, "t1", "success", shanghai(2026, 9, 1, 10, 3, 5))
	insertLog(t, st, "t2", "start", shanghai(2026, 9, 2, 10, 3, 0))
	insertLog(t, st, "t2", "success", shanghai(2026, 9, 2, 10, 3, 5))

	// 09:30 day1 → 15:00 day2 = 29.5h.
	dr := dashRange{key: "custom",
		since: shanghai(2026, 9, 1, 9, 30, 0), until: shanghai(2026, 9, 2, 15, 0, 0)}
	buckets, counts := trendBuckets(t, st, dr, "all")

	if len(buckets) != 2 {
		t.Fatalf("buckets = %v, want 2 daily buckets", buckets)
	}
	// Buckets start at the window origin, so labels carry the 09:30 offset.
	if buckets[0] != "2026-09-01 09:30" || buckets[1] != "2026-09-02 09:30" {
		t.Errorf("buckets = %v, want 09:30-anchored labels", buckets)
	}
	for _, b := range buckets {
		if got, want := counts[b], int64(1); got != want {
			t.Errorf("counts[%q] = %d, want %d", b, got, want)
		}
	}
}

func TestTrendBucketsSubDayWindowUsesTenMinuteGrid(t *testing.T) {
	st := newDashboardTestStore(t)
	// 09:30 → 12:00, buckets every 10 minutes from 09:30.
	insertLog(t, st, "t1", "start", shanghai(2026, 9, 1, 9, 35, 0))
	insertLog(t, st, "t1", "success", shanghai(2026, 9, 1, 9, 35, 1))
	insertLog(t, st, "t2", "start", shanghai(2026, 9, 1, 10, 5, 0))
	insertLog(t, st, "t2", "success", shanghai(2026, 9, 1, 10, 5, 1))
	// Outside the window: must not appear.
	insertLog(t, st, "t3", "start", shanghai(2026, 9, 1, 13, 0, 0))
	insertLog(t, st, "t3", "success", shanghai(2026, 9, 1, 13, 0, 1))

	dr := parseDashRange(dashTestCtx(
		"range=custom&from=2026-09-01T09:30:00&to=2026-09-01T12:00:00"))
	buckets, counts := trendBuckets(t, st, dr, "all")

	// 10-minute slots anchored at 09:30, covering the requested span.
	if len(buckets) < 15 {
		t.Fatalf("got %d buckets (%v), want at least 15", len(buckets), buckets)
	}
	if buckets[0] != "09:30" {
		t.Errorf("first bucket = %q, want 09:30", buckets[0])
	}
	if got := counts["09:30"]; got != 1 {
		t.Errorf("counts[09:30] = %d, want 1", got)
	}
	if got := counts["10:00"]; got != 1 {
		t.Errorf("counts[10:00] = %d, want 1", got)
	}
	var total int64
	for _, v := range counts {
		total += v
	}
	if total != 2 {
		t.Errorf("total success = %d, want 2 (out-of-window row leaked?)", total)
	}
}

func TestTrendBucketsDateOnlyCustomMatchesWholeDay(t *testing.T) {
	st := newDashboardTestStore(t)
	insertLog(t, st, "t1", "start", shanghai(2026, 9, 1, 0, 0, 0))
	insertLog(t, st, "t1", "success", shanghai(2026, 9, 1, 0, 0, 1))
	insertLog(t, st, "t2", "start", shanghai(2026, 9, 1, 23, 59, 59))
	insertLog(t, st, "t2", "success", shanghai(2026, 9, 1, 23, 59, 59))
	// Next day: outside a single-day window.
	insertLog(t, st, "t3", "start", shanghai(2026, 9, 2, 0, 0, 0))
	insertLog(t, st, "t3", "success", shanghai(2026, 9, 2, 0, 0, 1))

	dr := parseDashRange(dashTestCtx("range=custom&from=2026-09-01&to=2026-09-01"))
	buckets, counts := trendBuckets(t, st, dr, "all")

	if len(buckets) != 144 {
		t.Fatalf("got %d buckets, want 144 (00:00..23:50)", len(buckets))
	}
	if buckets[0] != "00:00" || buckets[143] != "23:50" {
		t.Errorf("bucket edges = %q..%q, want 00:00..23:50", buckets[0], buckets[143])
	}
	var total int64
	for _, v := range counts {
		total += v
	}
	if total != 2 {
		t.Errorf("total success = %d, want 2", total)
	}
}

func TestTrendBucketsEntryModeUsesOffsetGrid(t *testing.T) {
	st := newDashboardTestStore(t)
	insertLog(t, st, "t1", "start", shanghai(2026, 9, 1, 9, 35, 0))
	insertLog(t, st, "t2", "start", shanghai(2026, 9, 1, 11, 57, 0))

	dr := dashRange{key: "custom",
		since: shanghai(2026, 9, 1, 9, 30, 0), until: shanghai(2026, 9, 1, 12, 0, 1)}
	_, counts := trendBuckets(t, st, dr, "entry", "m1")

	if got := counts["09:30"]; got != 1 {
		t.Errorf("counts[09:30] = %d, want 1", got)
	}
	if got := counts["11:50"]; got != 1 {
		t.Errorf("counts[11:50] = %d, want 1", got)
	}
}

// Legacy clients send date-only params; that request shape must keep working
// byte-for-byte (whole-day window, 144 ten-minute buckets).
func TestTrendBucketsLegacyDateOnlyRequest(t *testing.T) {
	st := newDashboardTestStore(t)
	insertLog(t, st, "t1", "start", shanghai(2026, 9, 1, 8, 0, 0))
	insertLog(t, st, "t1", "success", shanghai(2026, 9, 1, 8, 0, 1))

	router := gin.New()
	registerDashboardRoutes(router.Group("/admin/api"), Deps{Store: st})
	rec := httptest.NewRecorder()
	router.ServeHTTP(rec, httptest.NewRequest("GET",
		"/admin/api/dashboard/today-trend?mode=all&range=custom&from=2026-09-01&to=2026-09-01", nil))
	if rec.Code != 200 {
		t.Fatalf("status = %d, body = %s", rec.Code, rec.Body.String())
	}
	var body struct {
		Buckets    []string           `json:"buckets"`
		BucketUnit string             `json:"bucketUnit"`
		Series     map[string][]int64 `json:"series"`
	}
	if err := json.Unmarshal(rec.Body.Bytes(), &body); err != nil {
		t.Fatal(err)
	}
	if body.BucketUnit != "10m" {
		t.Errorf("bucketUnit = %q, want 10m", body.BucketUnit)
	}
	if len(body.Buckets) != 144 {
		t.Fatalf("got %d buckets, want 144", len(body.Buckets))
	}
	if body.Buckets[48] != "08:00" {
		t.Errorf("bucket[48] = %q, want 08:00", body.Buckets[48])
	}
	if got := body.Series["success"][48]; got != 1 {
		t.Errorf("series.success[08:00] = %d, want 1", got)
	}
}

// The stats endpoint only needs the store for aggregation queries; with an
// empty log table it must still answer 200 and report the requested window.
func TestDashboardStatsEmptyStoreReportsWindow(t *testing.T) {
	st := newDashboardTestStore(t)
	router := gin.New()
	registerDashboardRoutes(router.Group("/admin/api"), Deps{Store: st})
	rec := httptest.NewRecorder()
	router.ServeHTTP(rec, httptest.NewRequest("GET",
		"/admin/api/dashboard/stats?range=custom&from=2026-09-01T09:30:00&to=2026-09-01T12:00:00", nil))
	if rec.Code != 200 {
		t.Fatalf("status = %d, body = %s", rec.Code, rec.Body.String())
	}
	var body struct {
		Range struct {
			Key     string `json:"key"`
			StartAt string `json:"startAt"`
			EndAt   string `json:"endAt"`
			Prev    struct {
				StartAt string `json:"startAt"`
				EndAt   string `json:"endAt"`
			} `json:"prev"`
		} `json:"range"`
		Totals struct {
			Requests int64 `json:"requests"`
		} `json:"totals"`
	}
	if err := json.Unmarshal(rec.Body.Bytes(), &body); err != nil {
		t.Fatal(err)
	}
	if body.Range.Key != "custom" {
		t.Errorf("range.key = %q, want custom", body.Range.Key)
	}
	if body.Range.StartAt != "2026-09-01T09:30:00" || body.Range.EndAt != "2026-09-01T12:00:00" {
		t.Errorf("range = %s..%s, want 09:30:00..12:00:00", body.Range.StartAt, body.Range.EndAt)
	}
	// Previous window is the same number of seconds immediately before the
	// current one: [09:30:00, 12:00:01) is 9001s, so prev is [06:59:59, 09:30:00).
	if body.Range.Prev.StartAt != "2026-09-01T06:59:59" || body.Range.Prev.EndAt != "2026-09-01T09:29:59" {
		t.Errorf("prev = %s..%s, want 06:59:59..09:29:59",
			body.Range.Prev.StartAt, body.Range.Prev.EndAt)
	}
	if body.Totals.Requests != 0 {
		t.Errorf("totals.requests = %d, want 0", body.Totals.Requests)
	}
}

// The dashboard must ship a per-gateway-key ranking. request_logs only carries
// gateway_api_key_id (api_key_name holds the *channel* key name), so the
// handler has to join api_keys for the display name — and a key deleted after
// its traffic must still appear under a placeholder rather than silently
// dropping its requests from the window.
func TestDashboardStatsKeyRankJoinsKeyNames(t *testing.T) {
	st := newDashboardTestStore(t)
	insertAPIKey(t, st, 1, "alpha-key")

	// alpha-key: two started traces, one succeeded (avg first byte 100ms).
	insertKeyLog(t, st, "k1", "start", shanghai(2026, 9, 1, 9, 35, 0), 1, 0, 0)
	insertKeyLog(t, st, "k1", "success", shanghai(2026, 9, 1, 9, 35, 5), 1, 100, 50)
	insertKeyLog(t, st, "k2", "start", shanghai(2026, 9, 1, 10, 0, 0), 1, 0, 0)
	// Key 99 no longer exists in api_keys: still ranked, under a placeholder.
	insertKeyLog(t, st, "k3", "start", shanghai(2026, 9, 1, 11, 0, 0), 99, 0, 0)
	insertKeyLog(t, st, "k3", "success", shanghai(2026, 9, 1, 11, 0, 1), 99, 300, 20)
	// Rows without a gateway key must not surface in the ranking.
	insertLog(t, st, "k4", "start", shanghai(2026, 9, 1, 11, 30, 0))

	router := gin.New()
	registerDashboardRoutes(router.Group("/admin/api"), Deps{Store: st})
	rec := httptest.NewRecorder()
	router.ServeHTTP(rec, httptest.NewRequest("GET",
		"/admin/api/dashboard/stats?range=custom&from=2026-09-01T09:30:00&to=2026-09-01T12:00:00", nil))
	if rec.Code != 200 {
		t.Fatalf("status = %d, body = %s", rec.Code, rec.Body.String())
	}
	var body struct {
		KeyRank []struct {
			Name        string `json:"name"`
			Requests    int64  `json:"requests"`
			Success     int64  `json:"success"`
			TotalTokens int64  `json:"totalTokens"`
			AvgTime     int64  `json:"avgTime"`
		} `json:"keyRank"`
	}
	if err := json.Unmarshal(rec.Body.Bytes(), &body); err != nil {
		t.Fatal(err)
	}
	if len(body.KeyRank) != 2 {
		t.Fatalf("keyRank = %+v, want 2 rows (ranked by requests desc)", body.KeyRank)
	}
	alpha := body.KeyRank[0]
	if alpha.Name != "alpha-key" || alpha.Requests != 2 || alpha.Success != 1 {
		t.Errorf("keyRank[0] = %+v, want alpha-key with 2 requests / 1 success", alpha)
	}
	if alpha.TotalTokens != 50 || alpha.AvgTime != 100 {
		t.Errorf("keyRank[0] tokens/avg = %d/%d, want 50/100", alpha.TotalTokens, alpha.AvgTime)
	}
	deleted := body.KeyRank[1]
	if deleted.Name != "已删除密钥 #99" || deleted.Requests != 1 || deleted.Success != 1 {
		t.Errorf("keyRank[1] = %+v, want 已删除密钥 #99 with 1 request / 1 success", deleted)
	}
}
