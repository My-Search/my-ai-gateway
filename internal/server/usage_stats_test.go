package server

import (
	"context"
	"fmt"
	"strings"
	"testing"
	"time"

	"github.com/my-search/my-ai-gateway/internal/jtime"
	"github.com/my-search/my-ai-gateway/internal/store"
)

// The usage-stats scans read exactly these request_logs columns, so the shared
// log-list fixture (which declares created_at as TIMESTAMP, like production) is
// enough. Rows are inserted through jtime.FormatDefault instants, the same text
// the log writer produces.

func insertUsageLog(t *testing.T, st *store.Store, traceID, channel string, keyID any,
	model, phase string, at time.Time, prompt, completion, total, firstByte, respMs int64) {
	t.Helper()
	if _, err := st.Exec(context.Background(),
		`INSERT INTO request_logs (trace_id, channel_name, gateway_api_key_id, channel_model_name, phase,
		                            created_at, prompt_tokens, completion_tokens, total_tokens,
		                            first_byte_ms, response_time_ms)
		 VALUES (?,?,?,?,?,?,?,?,?,?,?)`,
		traceID, channel, keyID, model, phase, jtime.FormatDefault(at),
		prompt, completion, total, firstByte, respMs); err != nil {
		t.Fatal(err)
	}
}

// windowIncluded reproduces the SQL predicate `created_at >= ?` on the stored
// text so the expectation is exact whatever weekday/month-day "now" falls on.
func windowIncluded(row, start time.Time) bool {
	return jtime.FormatDefault(row) >= jtime.FormatDefault(start)
}

func usageWindow(row, start time.Time, prompt, total int64) usagePeriod {
	if windowIncluded(row, start) {
		return usagePeriod{RequestCount: 1, PromptTokens: prompt, CompletionTokens: 0, TotalTokens: total}
	}
	return usagePeriod{}
}

// TestChannelUsageStatsPeriodWindowsAreExact pins the per-period scans: every
// window must contain exactly the success rows at or after its own start and
// nothing else. Rows are placed one second into each window plus one month back,
// and expectations are derived from the same string comparison the SQL performs,
// so the test is stable no matter which day of week or month it runs on.
func TestChannelUsageStatsPeriodWindowsAreExact(t *testing.T) {
	st := newLogListTestStore(t)
	now := time.Now().UTC().In(jtime.Shanghai)
	todayStart := jtime.ShanghaiStart(now)
	weekStart := jtime.ShanghaiWeekStart(now)
	monthStart := jtime.ShanghaiMonthStart(now)

	type row struct {
		model string
		at    time.Time
		tokens int64
		phase  string
	}
	rows := []row{
		{"m-today", todayStart.Add(time.Second), 10, "success"},
		{"m-today2", todayStart.Add(3 * time.Hour), 50, "success"},
		{"m-week", weekStart.Add(time.Second), 20, "success"},
		{"m-month", monthStart.Add(time.Second), 30, "success"},
		{"m-stale", monthStart.AddDate(0, -1, 0), 40, "success"},
		// A failed row for m-today: phase='success' aggregates must not see it.
		{"m-today", todayStart.Add(time.Minute), 999, "fail"},
	}
	for i, r := range rows {
		insertUsageLog(t, st, "t-"+r.model+string(rune('0'+i)), "chan-x", nil, r.model, r.phase, r.at, r.tokens, 0, r.tokens, 100, 500)
	}

	usage := getChannelModelUsageStats(context.Background(), st, "chan-x")
	if len(usage.ModelStats) != 5 {
		t.Fatalf("expected 5 models (fail rows share a model), got %d: %+v", len(usage.ModelStats), usage.ModelStats)
	}
	byModel := make(map[string]modelUsageStat)
	for _, s := range usage.ModelStats {
		byModel[s.ModelName] = s
	}

	// All-time totals ignore phase-999 fail rows and count success only.
	mt := byModel["m-today"]
	if mt.RequestCount != 1 || mt.TotalTokens != 10 {
		t.Errorf("m-today all-time = %+v/%d, want 1 row / 10 tokens (fail row excluded)", mt.RequestCount, mt.TotalTokens)
	}
	// m-stale is older than every window: all period columns zero.
	ms := byModel["m-stale"]
	if ms.Today != (usagePeriod{}) || ms.Week != (usagePeriod{}) || ms.Month != (usagePeriod{}) {
		t.Errorf("m-stale must be zero in every window: %+v", ms)
	}
	for _, r := range rows {
		if r.phase != "success" {
			continue
		}
		s := byModel[r.model]
		if want := usageWindow(r.at, todayStart, r.tokens, r.tokens); s.Today != want {
			t.Errorf("%s today = %+v, want %+v", r.model, s.Today, want)
		}
		if want := usageWindow(r.at, weekStart, r.tokens, r.tokens); s.Week != want {
			t.Errorf("%s week = %+v, want %+v", r.model, s.Week, want)
		}
		if want := usageWindow(r.at, monthStart, r.tokens, r.tokens); s.Month != want {
			t.Errorf("%s month = %+v, want %+v", r.model, s.Month, want)
		}
	}
}

// TestAPIKeyUsageStatsMergesPeriods mirrors the channel test for the key-scoped
// scans, and checks that a second key's traffic cannot leak into key 1.
func TestAPIKeyUsageStatsMergesPeriods(t *testing.T) {
	st := newLogListTestStore(t)
	now := time.Now().UTC().In(jtime.Shanghai)
	todayStart := jtime.ShanghaiStart(now)
	monthStart := jtime.ShanghaiMonthStart(now)

	at := map[string]time.Time{
		"km-today": todayStart.Add(time.Second),
		"km-month": monthStart.Add(time.Second),
		"km-old":   monthStart.AddDate(0, -1, 0),
	}
	tokens := map[string]int64{"km-today": 7, "km-month": 8, "km-old": 9}
	for model, at := range at {
		insertUsageLog(t, st, "k-"+model, "chan-z", int64(1), model, "success", at, tokens[model], 0, tokens[model], 100, 500)
	}
	// Same windows under a different key: must never appear.
	insertUsageLog(t, st, "k-other", "chan-z", int64(2), "km-today", "success", todayStart.Add(time.Minute), 100, 0, 100, 100, 500)

	usage := getAPIKeyUsageStats(context.Background(), st, 1)
	byModel := make(map[string]modelUsageStat)
	for _, s := range usage.ModelStats {
		byModel[s.ModelName] = s
	}
	if len(byModel) != 3 {
		t.Fatalf("key 1 must see only its own 3 models, got %+v", byModel)
	}
	for model := range at {
		s := byModel[model]
		if want := usageWindow(at[model], todayStart, tokens[model], tokens[model]); s.Today != want {
			t.Errorf("%s today = %+v, want %+v", model, s.Today, want)
		}
		if want := usageWindow(at[model], monthStart, tokens[model], tokens[model]); s.Month != want {
			t.Errorf("%s month = %+v, want %+v", model, s.Month, want)
		}
	}
}

// TestChannelUsageStatsRecent30IsTopN guards the ROW_NUMBER() → LIMIT rewrites:
// the channel-level average must cover exactly the newest 30 rows of the whole
// channel, and a model with fewer rows must average all of them.
//
// The rows deliberately alternate success/fail so the per-phase arms cannot be
// concatenated instead of merged: taking the newest 30 of success followed by
// the newest 30 of fail would average 30 rows of 1000 and 30 rows of 1 and give
// 500, not 1000.
func TestChannelUsageStatsRecent30IsTopN(t *testing.T) {
	st := newLogListTestStore(t)
	base := time.Now().UTC().In(jtime.Shanghai)

	// 35 rows for m-rt: the 5 oldest carry first_byte_ms=1, the newest 30 carry
	// 1000. An average over all 35 would be well below 1000.
	for i := 0; i < 35; i++ {
		fbm := int64(1)
		if i >= 5 {
			fbm = 1000
		}
		phase := "success"
		if i%2 == 1 {
			phase = "fail"
		}
		insertUsageLog(t, st, fmt.Sprintf("rt-%02d", i), "chan-rt", nil, "m-rt", phase,
			base.Add(time.Duration(i-40)*time.Hour), 1, 1, 2, fbm, 500)
	}
	// A model whose rows are all older than every m-rt row, 3 samples of 300.
	for i := 0; i < 3; i++ {
		insertUsageLog(t, st, fmt.Sprintf("rt2-%02d", i), "chan-rt", nil, "m-rt2", "success",
			base.Add(time.Duration(i-60)*time.Hour), 1, 1, 2, 300, 500)
	}

	usage := getChannelModelUsageStats(context.Background(), st, "chan-rt")

	if usage.ChannelAvgResponseTimeRecent30 != 1000 {
		t.Errorf("channelAvgResponseTimeRecent30 = %d, want 1000 (newest 30 only)", usage.ChannelAvgResponseTimeRecent30)
	}
	// speed = completion_tokens * 1000 / response_time_ms = 1000*1/500 = 2.0
	if usage.ChannelAvgOutputSpeedRecent30 != 2.0 {
		t.Errorf("channelAvgOutputSpeedRecent30 = %v, want 2.0", usage.ChannelAvgOutputSpeedRecent30)
	}

	var m2 *modelUsageStat
	for i := range usage.ModelStats {
		if usage.ModelStats[i].ModelName == "m-rt2" {
			m2 = &usage.ModelStats[i]
		}
	}
	if m2 == nil {
		t.Fatal("m-rt2 missing from usage stats")
	}
	if m2.AvgResponseTimeRecent30 != 300 {
		t.Errorf("m-rt2 avgResponseTimeRecent30 = %d, want 300 (all 3 rows)", m2.AvgResponseTimeRecent30)
	}
}

// TestRecent30QueryUsesEqualityPhaseArms is the guard on the discovery that made
// the LIMIT rewrite actually pay off. `phase IN ('success','fail')` is not an
// equality constraint, so it leaves a hole in the (channel_name, phase,
// created_at, id) index prefix: SQLite cannot satisfy ORDER BY created_at DESC,
// id DESC from the index, falls back to a channel-wide scan plus a temp B-tree
// sort, and the query costs the same as the window function it replaced. Every
// arm must therefore carry `phase = ?`.
func TestRecent30QueryUsesEqualityPhaseArms(t *testing.T) {
	st := newLogListTestStore(t)
	insertUsageLog(t, st, "a", "chan-a", nil, "m", "success",
		time.Now().UTC().In(jtime.Shanghai).Add(-time.Hour), 1, 1, 2, 100, 500)
	if _, err := st.Exec(context.Background(), "ANALYZE"); err != nil {
		t.Fatal(err)
	}
	where, args := channelRecent30("chan-a", "m")
	for _, s := range recent30Query(context.Background(), st, where, args, "") {
		_ = s
	}
	for _, phase := range recent30Phases {
		plan := explainQueryPlan(t, st,
			`SELECT first_byte_ms, completion_tokens, response_time_ms,
			        CAST(created_at AS TEXT) created_at_txt, id
			   FROM request_logs
			  WHERE phase = ? AND channel_name = ? AND channel_model_name = ?
			  ORDER BY created_at DESC, id DESC LIMIT 30`, phase, "chan-a", "m")
		if strings.Contains(plan, "TEMP B-TREE") {
			t.Errorf("phase %q arm still sorts via a temp B-tree; the covering index is not used: %s", phase, plan)
		}
	}
	// The IN-list form must NOT appear anywhere in the arm queries: it is the
	// exact shape that costs a full scan.
	if strings.Contains(where, "phase IN") {
		t.Errorf("recent-30 scope must not use phase IN (...): %s", where)
	}
}
