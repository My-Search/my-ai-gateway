package logsvc

import (
	"context"
	"database/sql"
	"fmt"
	"sync"
	"testing"

	_ "modernc.org/sqlite"

	"github.com/my-search/my-ai-gateway/internal/store"
)

// fakeConfigReader returns a fixed save level (and the default for anything else).
type fakeConfigReader struct{ level string }

func (f fakeConfigReader) GetValue(_ context.Context, key, def string) string {
	if key == "request_data_save_level" && f.level != "" {
		return f.level
	}
	return def
}

// newWriterStore opens an in-memory SQLite database carrying the request_logs
// columns the writer touches. One connection is kept so the schema and rows stay
// visible.
func newWriterStore(t *testing.T) *store.Store {
	t.Helper()
	db, err := sql.Open("sqlite", "file::memory:?_pragma=busy_timeout(30000)")
	if err != nil {
		t.Fatal(err)
	}
	db.SetMaxOpenConns(1)
	t.Cleanup(func() { db.Close() })
	if _, err := db.Exec(`CREATE TABLE request_logs (
		id INTEGER PRIMARY KEY AUTOINCREMENT,
		trace_id TEXT NOT NULL,
		api_key_name TEXT DEFAULT '',
		model_name TEXT DEFAULT '',
		channel_model_name TEXT DEFAULT '',
		channel_name TEXT DEFAULT '',
		phase TEXT NOT NULL,
		status TEXT DEFAULT 'pending',
		message TEXT DEFAULT '',
		response_time_ms INTEGER DEFAULT 0,
		first_byte_ms INTEGER,
		created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
		retry_index INTEGER DEFAULT 0,
		prompt_tokens INTEGER DEFAULT 0,
		completion_tokens INTEGER DEFAULT 0,
		total_tokens INTEGER DEFAULT 0,
		request_headers TEXT,
		request_body TEXT,
		gateway_api_key_id INTEGER,
		reasoning_effort TEXT DEFAULT '')`); err != nil {
		t.Fatal(err)
	}
	return store.New(db, nil)
}

// savedBody reports the request_body written to the trace's start row.
func savedBody(t *testing.T, st *store.Store, traceID string) (string, bool) {
	t.Helper()
	row, err := st.QueryOne(context.Background(),
		"SELECT request_body FROM request_logs WHERE trace_id = ? AND phase = 'start'", traceID)
	if err != nil || row == nil {
		t.Fatalf("start row for %s not found: %v", traceID, err)
	}
	v := row.StrPtr("request_body")
	if v == nil {
		return "", false
	}
	return *v, true
}

func TestParseSaveLevel(t *testing.T) {
	cases := []struct {
		in   string
		want requestDataSaveLevel
	}{
		{"info", saveLevelInfo},
		{"warn", saveLevelWarn},
		{"error", saveLevelError},
		{"none", saveLevelNone},
		{"", saveLevelInfo},
		{"bogus", saveLevelInfo},
	}
	for _, c := range cases {
		if got := parseSaveLevel(c.in); got != c.want {
			t.Errorf("parseSaveLevel(%q) = %d, want %d", c.in, got, c.want)
		}
	}
}

// TestFlushPendingRequestDataMatrix pins the save-level semantics:
//
//	info  -> always save; warn -> retry or final failure; error -> final failure only;
//	none  -> never save.
func TestFlushPendingRequestDataMatrix(t *testing.T) {
	type scenario struct {
		name     string
		retry    bool
		fail     bool
		wantSave bool
	}
	// wantSave is resolved per level below; this table only describes the request.
	scenarios := []scenario{
		{name: "plain-success", retry: false, fail: false},
		{name: "success-after-retry", retry: true, fail: false},
		{name: "final-failure", retry: false, fail: true},
	}

	// expectation[level][scenario] = saved?
	expectation := map[string]map[string]bool{
		"info":  {"plain-success": true, "success-after-retry": true, "final-failure": true},
		"warn":  {"plain-success": false, "success-after-retry": true, "final-failure": true},
		"error": {"plain-success": false, "success-after-retry": false, "final-failure": true},
		"none":  {"plain-success": false, "success-after-retry": false, "final-failure": false},
	}

	for _, level := range []string{"info", "warn", "error", "none"} {
		for _, sc := range scenarios {
			t.Run(level+"/"+sc.name, func(t *testing.T) {
				st := newWriterStore(t)
				w := NewLogWriter(st, nil, fakeConfigReader{level: level})
				ctx := context.Background()
				trace := "trace-1"

				w.WriteStart(ctx, trace, "m", 1, "H", "B", "")
				if sc.retry {
					w.WriteCandidatePhase(ctx, trace, "k", "m", "cm", "c", 1,
						"retry", "pending", "第 1 次失败", 0, nil, "")
				}
				if sc.fail {
					w.WriteFail(ctx, trace, "m", 1, "error", "失败", 10, nil, 0)
				} else {
					w.WriteSuccess(ctx, trace, "k", "m", "cm", "c", 1, "请求成功", 10, nil, 0, 1, 1, 2)
				}

				body, ok := savedBody(t, st, trace)
				want := expectation[level][sc.name]
				if ok != want {
					t.Fatalf("level=%s scenario=%s: saved=%v (body=%q), want saved=%v",
						level, sc.name, ok, body, want)
				}
				if want && body != "B" {
					t.Errorf("level=%s scenario=%s: body=%q, want %q", level, sc.name, body, "B")
				}
			})
		}
	}
}

// TestFlushIsDetachedFromCancelledContext is a regression test: the terminal
// write often runs after a client disconnect has cancelled the request context
// (common for streams). The raw data must still be persisted.
func TestFlushIsDetachedFromCancelledContext(t *testing.T) {
	st := newWriterStore(t)
	w := NewLogWriter(st, nil, fakeConfigReader{level: "info"})

	ctx, cancel := context.WithCancel(context.Background())
	w.WriteStart(ctx, "trace-1", "m", 1, "H", "B", "")
	cancel() // simulate the client going away before the request completes
	w.WriteFail(ctx, "trace-1", "m", 1, "error", "失败", 10, nil, 0)

	if body, ok := savedBody(t, st, "trace-1"); !ok || body != "B" {
		t.Fatalf("raw data lost on cancelled context: saved=%v body=%q", ok, body)
	}
}

// TestConcurrentTracesDoNotRace is a regression test for the unsynchronized
// pending map: the writer is shared by every concurrent request, and concurrent
// map access previously crashed the process (and dropped entries, which made
// "warn" lose data).
func TestConcurrentTracesDoNotRace(t *testing.T) {
	st := newWriterStore(t)
	w := NewLogWriter(st, nil, fakeConfigReader{level: "warn"})
	ctx := context.Background()

	const n = 200
	var wg sync.WaitGroup
	for i := 0; i < n; i++ {
		wg.Add(1)
		go func(i int) {
			defer wg.Done()
			trace := fmt.Sprintf("trace-%d", i)
			w.WriteStart(ctx, trace, "m", 1, "H", "B", "")
			w.WriteCandidatePhase(ctx, trace, "k", "m", "cm", "c", 1,
				"retry", "pending", "第 1 次失败", 0, nil, "")
			w.WriteSuccess(ctx, trace, "k", "m", "cm", "c", 1, "请求成功", 1, nil, 1, 1, 1, 2)
		}(i)
	}
	wg.Wait()

	row, err := st.QueryOne(ctx,
		"SELECT COUNT(*) c FROM request_logs WHERE phase='start' AND request_body IS NOT NULL")
	if err != nil || row == nil {
		t.Fatalf("count query failed: %v", err)
	}
	if got := row.I64("c", 0); got != n {
		t.Errorf("warn with a real retry must save every trace: got %d, want %d", got, n)
	}
}
