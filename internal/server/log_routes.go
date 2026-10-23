package server

import (
	"context"
	"encoding/json"
	"fmt"
	"log/slog"
	"net/http"
	"sort"
	"strconv"
	"strings"
	"time"

	"github.com/gin-gonic/gin"
	"github.com/my-search/my-ai-gateway/internal/db"
	"github.com/my-search/my-ai-gateway/internal/httpx"
	"github.com/my-search/my-ai-gateway/internal/jtime"
	"github.com/my-search/my-ai-gateway/internal/models"
	"github.com/my-search/my-ai-gateway/internal/store"
)

// usageChartQueryTimeout bounds the detached aggregation. The query must
// outlive a browser timeout (axios gives up after 30s) so a slow first request
// can still populate DashCache for the next poll instead of being cancelled
// and re-run from scratch.
const usageChartQueryTimeout = 2 * time.Minute

// requestLogListColumns excludes request_headers / request_body: the list API
// never returns them, and reading them here would pull every saved payload
// (potentially tens of KB per row) only to null it out before serialisation.
// The raw data is loaded on demand by GET /logs/:id/request-data.
const requestLogListColumns = `id, trace_id, api_key_name, gateway_api_key_id,
	model_name, channel_model_name, channel_name, phase, status, message,
	reasoning_effort, retry_index, response_time_ms, first_byte_ms,
	prompt_tokens, completion_tokens, total_tokens, route_source, protocol,
	created_at`

// dtFmt renders a stored timestamp with the exact pattern of the Java
// controller's DT_FMT ("yyyy-MM-dd HH:mm:ss", AdminLogController.java:49),
// which is what trace.startTime / trace.endTime carry in the Java response.
// The value in created_at is UTC (TimeZoneConfig forces the JVM zone to UTC),
// so no zone conversion is applied — only the rendering pattern differs.
func dtFmt(t *time.Time) string {
	if t == nil {
		return ""
	}
	return t.UTC().Format("2006-01-02 15:04:05")
}

// sargableStartBound returns a bare UTC date that is never greater than the
// given instant, so `created_at >= date` is a safe superset of the exact
// instant comparison. request_logs.created_at stores both "YYYY-MM-DDTHH:..."
// and "YYYY-MM-DD HH:..." text, and both sort at or after their date prefix.
//
// The offset is deliberately parsed first: slicing the input string would pick
// the caller's local date (e.g. 2026-10-01T00:00:00+08:00 is still
// 2026-09-30 in UTC) and could exclude rows that the exact predicate keeps.
func sargableStartBound(s string) (string, bool) {
	t, ok := jtime.Parse(s)
	if !ok {
		return "", false
	}
	return t.UTC().Format("2006-01-02"), true
}

// sargableEndBound returns the exclusive bare UTC date after the given
// instant, for `created_at < date`. It is a safe superset of the exact
// inclusive `<=` predicate.
func sargableEndBound(s string) (string, bool) {
	t, ok := jtime.Parse(s)
	if !ok {
		return "", false
	}
	return t.UTC().AddDate(0, 0, 1).Format("2006-01-02"), true
}

// countRangeIndexMaxSpan is the widest window for which the trace count is
// pinned to idx_request_logs_created_at_phase_trace. Measured on 4M rows: one
// month 3.9s -> 1.0s, one day 32ms -> 20ms, but one year 7.5s -> 13.9s. The
// planner's own choice (a full scan of the covering trace_id index) really is
// cheaper once the range stops being selective, so the pin is only applied
// while the window is short.
const countRangeIndexMaxSpan = 92 * 24 * time.Hour

// countUsesRangeIndex reports whether the filter bounds a window narrow enough
// that the count query should be forced onto the created_at range scan.
//
// A missing start bound leaves the window unbounded (the table's history is
// unknown), so it stays with the planner. A missing end bound means "up to
// now", which is still measurable.
func countUsesRangeIndex(startTime, endTime string) bool {
	if startTime == "" {
		return false
	}
	from, ok := jtime.Parse(startTime)
	if !ok {
		return false
	}
	to := time.Now()
	if endTime != "" {
		parsed, ok := jtime.Parse(endTime)
		if !ok {
			return false
		}
		to = parsed
	}
	span := to.Sub(from)
	return span > 0 && span <= countRangeIndexMaxSpan
}

// traceWalkMaxOffset bounds when the streaming walk is still worth it. The walk
// is linear in offset while GROUP BY is flat, so past a few hundred thousand
// traces the walk can only lose. The cap also bounds the dedup map so a
// pathological request cannot balloon a single page.
const traceWalkMaxOffset = 200000

// traceWalkMaxRows caps how many raw log rows one page may scan. The walk costs
// ~3.4µs per row here, so 1.5M rows is ~5s — still below the flat GROUP BY pager
// on this data (7-9s without a filter). Past that the walk can only lose.
const traceWalkMaxRows = 1500000

// traceWalkProbeRows is how many rows the walk reads before deciding whether it
// can reach the requested offset at all. Traces with retry chains occupy several
// rows each, so "offset traces" can mean millions of rows; predicting that from
// the head of the window costs ~10ms and saves scanning the whole budget only to
// fall back to the pager.
const traceWalkProbeRows = 4096

// traceWalkRowBudget is how many raw log rows one page may scan: ~12 rows per
// requested trace, capped.
func traceWalkRowBudget(limit, offset int) int {
	budget := 4096 + (offset+limit)*12
	if budget > traceWalkMaxRows {
		return traceWalkMaxRows
	}
	return budget
}

// listTracePageWalk collects one page of trace ids by streaming request_logs in
// (created_at DESC, id DESC) order and deduplicating trace ids in Go.
//
// The GROUP BY variant has to read, group and sort every row in the filter
// window before it can emit the first page (8.2s for one month at 4M rows, 9.4s
// without a filter), while this walk stops as soon as limit+1 traces past the
// offset are known: 18ms for the first page of a month. The rows arrive in the
// order of each trace's newest row, which is exactly the MAX(created_at) DESC
// ordering the Java mapper produced, so no sort is needed on the Go side either.
//
// This used to be a chunked keyset walk over `(created_at, id) < (?, ?)`. That
// row-value comparison only narrows the upper bound; it does not resume the
// index scan, so every chunk re-read the window from its start and the total
// cost grew with offset² (7.1s at offset 20000). One streaming statement is
// linear and ~7x faster at the same depth.
//
// The walk reports ok=false when the page is out of reach — either the probe
// below says the offset is too deep for this window's rows-per-trace ratio, or
// the row budget runs out. That is the caller's signal to use the pager instead.
func listTracePageWalk(ctx context.Context, st *store.Store, w string, args []any, limit, offset int) (ids []string, hasMore bool, ok bool) {
	if limit <= 0 || offset < 0 || offset > traceWalkMaxOffset {
		return nil, false, false
	}
	budget := traceWalkRowBudget(limit, offset)

	qArgs := make([]any, len(args)+1)
	copy(qArgs, args)
	qArgs[len(args)] = budget

	// created_at is only used for ordering, never read into Go, so the TIMESTAMP
	// column's driver conversion cannot corrupt anything here. Ordering by
	// (created_at DESC, id DESC) keeps page boundaries stable across requests:
	// traces sharing a timestamp are always resolved the same way.
	const query = `SELECT r.trace_id FROM request_logs r WHERE 1=1`

	seen := make(map[string]struct{}, offset+limit+1)
	out := make([]string, 0, limit+1)
	scanned := 0
	filled := false
	unreachable := false

	err := st.StreamReadOnly(ctx, func(r store.Row) bool {
		scanned++
		tid := r.Str("trace_id")
		if _, dup := seen[tid]; !dup {
			seen[tid] = struct{}{}
			if len(seen) > offset {
				out = append(out, tid)
				if len(out) > limit {
					// limit+1 traces found: the page is full and a next page exists.
					filled = true
					return false
				}
			}
		}

		if scanned == traceWalkProbeRows && !filled {
			// Extrapolate from the head of the window: at this rows-per-trace
			// ratio, how many rows does reaching the offset need? Declining here
			// costs ~10ms; discovering it at the budget's end costs seconds.
			if ratio := float64(scanned) / float64(len(seen)); ratio*float64(offset+limit+1) > float64(budget) {
				unreachable = true
				return false
			}
		}
		return true
	}, query+w+` ORDER BY r.created_at DESC, r.id DESC LIMIT ?`, qArgs...)

	if err != nil || unreachable {
		// The caller retries with the GROUP BY pager, which surfaces its own
		// error; swallowing it here keeps the fallback a single code path.
		return nil, false, false
	}
	if filled {
		return out[:limit], true, true
	}
	if scanned < budget {
		// The window ended before the budget did: everything reachable was seen.
		return out, false, true
	}
	// Budget exhausted before the page filled up: let the pager take over.
	return nil, false, false
}

// cachedTraceCount returns COUNT(DISTINCT trace_id) for the given filter,
// memoised in DashCache with stale-while-revalidate.
//
// The count is the single most expensive part of the list endpoint (it builds a
// temp B-tree over the whole window) and every infinite-scroll page re-requests
// it with identical parameters. Caching it removes that cost from all but the
// first request, and serving a just-expired entry while refreshing in the
// background means the expiry itself never lands on a user's request either.
//
// useRangeIndex pins the count to idx_request_logs_created_at_phase_trace. Left
// to itself the planner answers a bounded window by scanning the whole
// idx_request_logs_trace_id_created_at index (it is covering, and the estimated
// cost of a full index walk looks cheaper than a range scan plus a temp B-tree):
// 3.9s vs 1.0s for one month at 4M rows. The pin only pays off while the range
// is selective — over a year or with no time filter the full index scan really
// is cheaper (7.5s vs 13.9s) — so the caller sets it from the window length.
func (d Deps) cachedTraceCount(ctx context.Context, w string, args []any, useRangeIndex bool) (int64, error) {
	var sig strings.Builder
	sig.WriteString(w)
	for _, a := range args {
		sig.WriteByte('\x00')
		fmt.Fprintf(&sig, "%v", a)
	}
	to := sig.String()

	const cacheKey = "log-list-total"
	// Stale-while-revalidate, like the usage chart: an entry that just expired is
	// still good enough for a "loaded N of M" footer, and re-running the count
	// while the user waits is exactly the multi-second stall this cache exists to
	// remove. The footer may lag one TTL behind; that is invisible next to a page
	// that takes seconds to arrive.
	if cached, fresh, ok := d.DashCache.GetStale(cacheKey, "", to); ok {
		if n, isInt := cached.(int64); isInt {
			if !fresh {
				d.DashCache.RefreshAsync(d.DashCache.Key(cacheKey, "", to), cacheKey, "", to,
					traceCountTTL, func() (any, error) {
						// Detached: the response that triggered this refresh is already
						// on its way, so the request context is about to be cancelled.
						rctx, cancel := context.WithTimeout(context.Background(), usageChartQueryTimeout)
						defer cancel()
						return d.computeTraceCount(rctx, w, args, useRangeIndex)
					})
			}
			return n, nil
		}
	}

	total, err := d.computeTraceCount(ctx, w, args, useRangeIndex)
	if err != nil {
		return 0, err
	}
	// Shorter TTL than the chart: new logs arrive continuously, and the footer
	// ("loaded N of M") should not lag a whole minute behind them.
	d.DashCache.SetWithTTL(cacheKey, "", to, total, traceCountTTL)
	return total, nil
}

// traceCountTTL is how long the "loaded N of M" total stays fresh. It is short
// because new logs arrive continuously, and cheap because expiry is handled by a
// background refresh rather than a blocking recount.
const traceCountTTL = 30 * time.Second

// computeTraceCount runs the COUNT(DISTINCT trace_id) query.
func (d Deps) computeTraceCount(ctx context.Context, w string, args []any, useRangeIndex bool) (int64, error) {
	from := "request_logs r"
	if useRangeIndex {
		from = "request_logs r INDEXED BY idx_request_logs_created_at_phase_trace"
	}
	row, err := d.Store.QueryOneReadOnly(ctx,
		`SELECT COUNT(DISTINCT r.trace_id) as total FROM `+from+` WHERE 1=1`+w, args...)
	if err != nil {
		return 0, err
	}
	if row == nil {
		return 0, nil
	}
	return row.I64("total", 0), nil
}

func registerLogRoutes(g *gin.RouterGroup, d Deps) {
	// GET /admin/api/logs
	g.GET("/logs", func(c *gin.Context) {
		ctx := c.Request.Context()
		offset, _ := strconv.Atoi(c.DefaultQuery("offset", "0"))
		limit, _ := strconv.Atoi(c.DefaultQuery("limit", "50"))
		// Java AdminLogController.listLogs:106-110 binds the raw parameters and
		// only checks isEmpty(); trimming would change which rows match for
		// whitespace-only values, so the strings are used verbatim.
		modelName := c.Query("modelName")
		gwKeyIDStr := c.Query("gatewayApiKeyId")
		apiKeyName := c.Query("apiKeyName")
		startTime := c.Query("startTime")
		endTime := c.Query("endTime")

		var where []string
		var args []any
		if modelName != "" {
			where = append(where, "r.model_name = ?")
			args = append(args, modelName)
		}
		if gwKeyIDStr != "" {
			if id, err := strconv.ParseInt(strings.TrimSpace(gwKeyIDStr), 10, 64); err == nil {
				where = append(where, "r.gateway_api_key_id = ?")
				args = append(args, id)
			}
		}
		if apiKeyName != "" {
			// Java RequestLogMapper.java:100 — exact match on api_key_name
			// (WriteStart stores NULL, Java RequestLogService.java:162-163).
			where = append(where, "r.api_key_name = ?")
			args = append(args, apiKeyName)
		}
		if startTime != "" {
			// Java RequestLogMapper.java:101 — created_at >= #{startTime}.
			//
			// The exact comparison still goes through datetime() because the
			// column holds both "…T…" (app writes) and "… …" (SQL defaults).
			// Wrapping the column makes that predicate non-sargable, so a
			// coarse bare-date prefix is ANDed in to let SQLite open the
			// created_at index. The exact predicate stays and still decides
			// which rows inside the prefix range match.
			if bound, ok := sargableStartBound(startTime); ok {
				where = append(where, "r.created_at >= ?")
				args = append(args, bound)
			}
			where = append(where, "datetime(r.created_at) >= datetime(?)")
			args = append(args, startTime)
		}
		if endTime != "" {
			// Java RequestLogMapper.java:102 — created_at <= #{endTime}.
			// Same coarse-bound trick as startTime; the exclusive `< next day`
			// prefix is a superset of the inclusive instant comparison.
			if bound, ok := sargableEndBound(endTime); ok {
				where = append(where, "r.created_at < ?")
				args = append(args, bound)
			}
			where = append(where, "datetime(r.created_at) <= datetime(?)")
			args = append(args, endTime)
		}
		w := ""
		if len(where) > 0 {
			w = " AND " + strings.Join(where, " AND ")
		}

		started := time.Now()
		// COUNT(DISTINCT trace_id) has to build a temp B-tree over every row in the
		// window (hundreds of ms per month, seconds without a time filter), and the
		// front-end re-requests the list for every infinite-scroll page. Cache it per
		// filter set so only the first request of a window pays for it.
		total, err := d.cachedTraceCount(ctx, w, args, countUsesRangeIndex(startTime, endTime))
		if err != nil {
			slog.Error("统计请求日志失败", "offset", offset, "limit", limit, "error", err)
			httpx.JSON(c, http.StatusInternalServerError,
				httpx.NewOrderedMap().Set("error", "查询请求日志失败"))
			return
		}

		// NOTE: the where clause references columns via the "r." alias — the paging
		// queries must keep the same alias or the filter silently fails.
		// Java RequestLogMapper.java:94-105 pages traces by MAX(created_at) DESC.
		//
		// Two strategies, because they scale differently:
		//   - streaming the window in created_at order reads only as many rows as it
		//     needs to collect limit+1 distinct traces past the offset, which makes
		//     the first page ~400x cheaper than the GROUP BY (19ms vs 8.2s at 4M rows
		//     / 66k traces) and stays linear in offset;
		//   - GROUP BY trace_id is offset-independent (~7-10s) and caps the work, so
		//     absurdly deep pages and odd row shapes fall back to it.
		// The walk reports ok=false when the page is out of reach, which is the
		// signal to use the pager instead.
		traceIDs, hasMore, ok := listTracePageWalk(ctx, d.Store, w, args, limit, offset)
		if !ok {
			// A cancelled request must not pay for the fallback: the client is gone
			// (the list page aborts its in-flight request when the filter changes).
			if ctx.Err() != nil {
				return
			}
			traceArgs := make([]any, len(args))
			copy(traceArgs, args)
			traceRows, err := d.Store.QueryReadOnly(ctx,
				`SELECT trace_id FROM (SELECT trace_id, MAX(created_at) FROM request_logs r WHERE 1=1`+w+` GROUP BY trace_id ORDER BY MAX(created_at) DESC LIMIT ? OFFSET ?)`,
				append(traceArgs, limit, offset)...)
			if err != nil {
				slog.Error("分页查询请求日志失败", "offset", offset, "limit", limit, "error", err)
				httpx.JSON(c, http.StatusInternalServerError,
					httpx.NewOrderedMap().Set("error", "查询请求日志失败"))
				return
			}
			traceIDs = make([]string, 0, len(traceRows))
			for _, r := range traceRows {
				traceIDs = append(traceIDs, r.Str("trace_id"))
			}
			hasMore = int64(offset+len(traceIDs)) < total
		}

		trees := make([]logTree, 0, len(traceIDs))
		if len(traceIDs) > 0 {
			placeholders := strings.TrimSuffix(strings.Repeat("?,", len(traceIDs)), ",")
			traceArgs := make([]any, len(traceIDs))
			for i, id := range traceIDs {
				traceArgs[i] = id
			}

			// has_request_data is folded into the detail query instead of being a
			// separate round trip: it only tests the two payload columns for NULL
			// (no payload is read into the result set), so the extra expression is
			// free next to the row fetch and saves one query per page.
			logRows, err := d.Store.QueryReadOnly(ctx,
				"SELECT "+requestLogListColumns+", "+
					"(CASE WHEN phase='start' AND (request_headers IS NOT NULL OR request_body IS NOT NULL) THEN 1 ELSE 0 END) AS has_data"+
					" FROM request_logs WHERE trace_id IN ("+placeholders+") ORDER BY created_at ASC",
				traceArgs...)
			if err != nil {
				slog.Error("查询请求日志明细失败", "traceCount", len(traceIDs), "error", err)
				httpx.JSON(c, http.StatusInternalServerError,
					httpx.NewOrderedMap().Set("error", "查询请求日志失败"))
				return
			}

			traceMap := make(map[string][]store.Row)
			hasData := make(map[string]bool)
			for _, r := range logRows {
				tid := r.Str("trace_id")
				traceMap[tid] = append(traceMap[tid], r)
				if r.I64("has_data", 0) != 0 {
					hasData[tid] = true
				}
			}

			for _, tid := range traceIDs {
				rows := traceMap[tid]
				if len(rows) == 0 {
					continue
				}

				// Java AdminLogController.java:133-135 re-sorts each trace's logs by
				// createdAt before deriving the aggregates, so start/end times and the
				// first model name follow true chronological order, not the raw
				// lexicographic ORDER BY created_at of the fetch (mixed "T"/" " text).
				parsed := make([]models.RequestLog, 0, len(rows))
				for _, r := range rows {
					parsed = append(parsed, store.RowToRequestLog(r))
				}
				sort.SliceStable(parsed, func(i, j int) bool {
					a, b := parsed[i].CreatedAt.T, parsed[j].CreatedAt.T
					switch {
					case a == nil:
						return b != nil
					case b == nil:
						return false
					default:
						return a.Before(*b)
					}
				})

				logItems := make([]any, 0, len(parsed))
				// Java tracks "the first log with a non-null modelName", even if that
				// value is the empty string (AdminLogController.java:147-149).
				var modelName string
				haveModel := false
				var retryCount, successCount, failCount, interruptedCount int
				var totalTimeMs int64
				for _, lr := range parsed {
					// List API excludes the large request data columns (loaded on demand),
					// matching Java RequestLogQuerySupport.
					lr.RequestHeaders = nil
					lr.RequestBody = nil
					logItems = append(logItems, lr)

					// Java counts traces by phase == "retry", not retryIndex > 0
					switch lr.Phase {
					case "retry":
						retryCount++
					case "success":
						successCount++
					case "fail":
						failCount++
					case "interrupted":
						interruptedCount++
					}
					if !haveModel && lr.ModelName != nil {
						modelName = *lr.ModelName
						haveModel = true
					}
					// Java only accumulates response time for terminal phases
					if (lr.Phase == "success" || lr.Phase == "fail" || lr.Phase == "interrupted") && lr.ResponseTimeMs != nil {
						totalTimeMs += int64(*lr.ResponseTimeMs)
					}
				}

				// Java AdminLogController.java:161-164 formats both bounds with DT_FMT
				// from the first/last log of the chronologically sorted slice.
				trees = append(trees, logTree{
					TraceID:          tid,
					Logs:             logItems,
					RetryCount:       retryCount,
					SuccessCount:     successCount,
					FailCount:        failCount,
					InterruptedCount: interruptedCount,
					ModelName:        modelName,
					TotalTimeMs:      totalTimeMs,
					StartTime:        dtFmt(parsed[0].CreatedAt.T),
					EndTime:          dtFmt(parsed[len(parsed)-1].CreatedAt.T),
					HasRequestData:   hasData[tid],
				})
			}

			// Java sorts traces by endTime descending, nulls last
			// (AdminLogController.java:168-174).
			sort.SliceStable(trees, func(i, j int) bool {
				if trees[i].EndTime == "" {
					return false
				}
				if trees[j].EndTime == "" {
					return true
				}
				return trees[i].EndTime > trees[j].EndTime
			})
		}

		// Java AdminLogController.java:197-204 — hasMore is derived from the number
		// of traces actually returned, not from the requested limit. The walk path
		// already knows whether a further trace exists, so it supplies its own flag
		// (and avoids depending on a possibly stale cached total).
		slog.Debug("请求日志列表查询完成",
			"offset", offset, "limit", limit, "total", total,
			"returned", len(trees), "elapsedMs", time.Since(started).Milliseconds())
		httpx.OK(c, httpx.NewOrderedMap().
			Set("data", trees).
			Set("total", total).
			Set("offset", offset).
			Set("limit", limit).
			Set("hasMore", hasMore))
	})

	// POST /admin/api/logs/clean
	g.POST("/logs/clean", func(c *gin.Context) {
		ctx := c.Request.Context()
		// Java RequestLogCleanupSupport.cleanOldLogs:33-38 — delete rows older than
		// now-30d (JVM zone pinned to UTC). datetime() normalises the stored text so
		// mixed "T"/" " representations are compared as instants, not as strings;
		// the bare-date prefix keeps the scan sargable (see runLogCleanup).
		cutoff := time.Now().UTC().AddDate(0, 0, -30)
		if _, err := d.Store.Exec(ctx,
			"DELETE FROM request_logs WHERE created_at < ? AND datetime(created_at) < datetime(?)",
			cutoff.Format("2006-01-02"), jtime.FormatDefault(cutoff)); err != nil {
			// Java AdminLogController.cleanLogs:213-217 answers 200 with the error.
			httpx.OK(c, httpx.NewOrderedMap().Set("success", false).Set("error", err.Error()))
			return
		}
		// The cleanup changed the data behind every cached snapshot; invalidate
		// so the usage chart / dashboard stop serving pre-cleanup numbers for up
		// to a full TTL instead of only the 60s front-end poll interval.
		d.DashCache.Invalidate()
		// Release the freed pages immediately, matching the periodic cleanup task.
		if err := db.IncrementalVacuum(d.Store.DB); err != nil {
			slog.Warn("增量回收空闲页失败", "error", err)
		}
		httpx.OK(c, httpx.NewOrderedMap().Set("success", true).Set("message", "已清理 30 天前的日志"))
	})

	// GET /admin/api/logs/usage-chart
	g.GET("/logs/usage-chart", func(c *gin.Context) {
		// Keep the aggregation alive after a browser timeout so it can still
		// populate DashCache; otherwise every retry cancels the previous query
		// and starts the expensive scan again.
		ctx, cancel := context.WithTimeout(
			context.WithoutCancel(c.Request.Context()), usageChartQueryTimeout)
		defer cancel()

		// The chart is labelled with the browser's calendar month and the log
		// list filters on that same month, while the gateway's canonical display
		// timezone is Asia/Shanghai (the dashboard and key stats already use it).
		// Anchor the default period there so "current month" means the same
		// thing everywhere.
		nowLocal := time.Now().In(jtime.Shanghai)
		yearStr := c.DefaultQuery("year", strconv.Itoa(nowLocal.Year()))
		monthStr := c.DefaultQuery("month", strconv.Itoa(int(nowLocal.Month())))
		modelType := c.DefaultQuery("modelType", "entry")
		// StatsSupport.emptyToNull trims and treats blank as "no filter".
		modelName := strings.TrimSpace(c.Query("modelName"))
		gwKeyIDStr := strings.TrimSpace(c.Query("gatewayApiKeyId"))
		apiKeyName := strings.TrimSpace(c.Query("apiKeyName"))

		year, errY := strconv.Atoi(strings.TrimSpace(yearStr))
		if errY != nil {
			year = nowLocal.Year()
		}
		month, errM := strconv.Atoi(strings.TrimSpace(monthStr))
		if errM != nil {
			month = int(nowLocal.Month())
		}

		if month < 1 || month > 12 {
			httpx.JSON(c, 200, map[string]any{"error": "month 必须在 1-12 之间"})
			return
		}

		// Cache the whole response: the chart is polled silently every 60s and
		// re-fetched on every month/filter switch, so without this each poll
		// re-runs a multi-second aggregation. The key discriminates on every
		// parameter that changes the result.
		// NUL separates the filter values so that a value containing the separator
		// cannot make two different filter combinations hash to the same key.
		cacheKey := "usage-chart"
		cacheFrom := fmt.Sprintf("%04d-%02d", year, month)
		cacheTo := strings.Join([]string{modelType, modelName, gwKeyIDStr, apiKeyName}, "\x00")

		// compute runs the aggregation. It is a closure so the same code serves the
		// cold path (synchronously, first request of a window) and the background
		// revalidation of a stale snapshot.
		compute := func(qctx context.Context) (any, error) {
			// Half-open [since, until) covering the requested calendar month in the
			// display timezone. Using UTC here would put a request made at 00:30
			// Shanghai on the 1st into the previous month's bucket, so the chart
			// could claim "no data this month" while the list (which filters by the
			// same local month) clearly shows that request.
			since := time.Date(year, time.Month(month), 1, 0, 0, 0, 0, jtime.Shanghai)
			until := since.AddDate(0, 1, 0)
			daysInMonth := until.AddDate(0, 0, -1).Day()

			// Two layers of bounds, both load-bearing:
			//
			//  1. Coarse DATE-ONLY bounds compared against the bare column keep the
			//     range sargable, so SQLite can use idx_usage_entry instead of
			//     scanning the whole table (the old datetime(created_at) predicate
			//     took seconds at scale). They are widened by one UTC day on each
			//     side because the Shanghai month boundary is mid-day in UTC.
			//  2. Exact instant bounds still go through datetime() because
			//     created_at holds both "…T…" (app writes) and "… …" (SQL defaults).
			//     They trim the extra half-days the coarse bounds let through, so
			//     the month window is exact.
			sinceArg := since.UTC().Format("2006-01-02")
			untilArg := until.UTC().AddDate(0, 0, 1).Format("2006-01-02")
			sinceInstant := jtime.FormatDefault(since)
			untilInstant := jtime.FormatDefault(until)

			days := make([]string, daysInMonth)
			dateIndex := make(map[string]int, daysInMonth)
			for i := range days {
				days[i] = since.AddDate(0, 0, i).In(jtime.Shanghai).Format("2006-01-02")
				dateIndex[days[i]] = i
			}

			// Optional filters, in the mapper's <if> order: modelName, gatewayApiKeyId,
			// apiKeyName (RequestLogMapper.java:497-499 / 546-548).
			var filters []string
			var filterArgs []any
			if modelName != "" {
				filters = append(filters, "model_name = ?")
				filterArgs = append(filterArgs, modelName)
			}
			if gwKeyIDStr != "" {
				if id, err := strconv.ParseInt(gwKeyIDStr, 10, 64); err == nil {
					filters = append(filters, "gateway_api_key_id = ?")
					filterArgs = append(filterArgs, id)
				}
			}
			if apiKeyName != "" {
				filters = append(filters, "api_key_name = ?")
				filterArgs = append(filterArgs, apiKeyName)
			}
			w := ""
			if len(filters) > 0 {
				w = " AND " + strings.Join(filters, " AND ")
			}

			var rows []store.Row
			var err error
			queryStarted := time.Now()
			// Shanghai day bucket: created_at is UTC text, so the date must be
			// shifted by +8h before truncating; otherwise every request made
			// between 00:00 and 08:00 Shanghai lands on the previous day.
			const shanghaiDay = "DATE(created_at, '+8 hours')"
			if modelType == "channel" {
				// Java RequestLogMapper.java:527-559 — trace-level outcome aggregation.
				// The trace set is selected by rows inside [since, until) with a non-empty
				// channel_model_name, but each selected trace is then aggregated over its
				// whole history: successful traces are attributed to their channel model
				// name (NULL names are dropped by the collector), failed traces fold into
				// "请求失败" with zero tokens. The per-trace token value is MAX, not SUM.
				args := append([]any{sinceArg, untilArg, sinceInstant, untilInstant}, filterArgs...)
				rows, err = d.Store.QueryReadOnly(qctx,
					`SELECT date, model_name, COUNT(1) AS request_count,
					        COALESCE(SUM(total_tokens), 0) AS total_tokens
					   FROM (
					     SELECT r.trace_id,
					            DATE(MIN(r.created_at), '+8 hours') AS date,
					            CASE WHEN MAX(CASE WHEN r.phase = 'success' THEN 1 ELSE 0 END) = 1
					                 THEN MAX(CASE WHEN r.phase = 'success' THEN r.channel_model_name END)
					                 ELSE '请求失败'
					            END AS model_name,
					            CASE WHEN MAX(CASE WHEN r.phase = 'success' THEN 1 ELSE 0 END) = 1
					                 THEN MAX(CASE WHEN r.phase = 'success' THEN COALESCE(r.total_tokens, 0) ELSE 0 END)
					                 ELSE 0
					            END AS total_tokens
					       FROM request_logs r
					      WHERE r.trace_id IN (
					            SELECT DISTINCT trace_id FROM request_logs
					             WHERE created_at >= ? AND created_at < ?
					               AND datetime(created_at) >= datetime(?)
					               AND datetime(created_at) < datetime(?)
					               AND channel_model_name IS NOT NULL AND channel_model_name != ''`+w+`
					      )
					      GROUP BY r.trace_id
					   ) t
					   GROUP BY date, model_name
					   ORDER BY date ASC, request_count DESC`, args...)
			} else {
				// Java RequestLogMapper.java:489-507 — entry models by DATE(created_at).
				// Rows are not filtered by phase: models whose requests all failed still
				// contribute a (0 token, 0 request) row, exactly like Java.
				args := append([]any{sinceArg, untilArg, sinceInstant, untilInstant}, filterArgs...)
				rows, err = d.Store.QueryReadOnly(qctx,
					`SELECT `+shanghaiDay+` AS date, model_name,
					        COALESCE(SUM(CASE WHEN phase = 'success' THEN COALESCE(total_tokens, 0) ELSE 0 END), 0) AS total_tokens,
					        COUNT(DISTINCT CASE WHEN phase = 'success' THEN trace_id END) AS request_count
					   FROM request_logs
					  WHERE created_at >= ? AND created_at < ?
					    AND datetime(created_at) >= datetime(?)
					    AND datetime(created_at) < datetime(?)
					    AND model_name IS NOT NULL AND model_name != ''`+w+`
					  GROUP BY `+shanghaiDay+`, model_name
					  ORDER BY date ASC, total_tokens DESC`, args...)
			}
			if err != nil {
				slog.Warn("查询使用历史图表失败",
					"year", year, "month", month, "modelType", modelType,
					"elapsedMs", time.Since(queryStarted).Milliseconds(), "error", err)
				return nil, err
			}

			// Java TrendChartStatsCollector.collectLogUsageChart:340-362 — accumulate
			// per-day buckets, keeping first-seen model order for stable tie-breaking.
			// tokenValues is the single source of truth for bar height (both modelTypes
			// use the token measure); requestValues is a parallel dimension for the
			// tooltip. There is deliberately no separate `values` array: it was always
			// identical to tokenValues and only inflated the response payload.
			order := make([]string, 0, 8)
			tokenValues := make(map[string][]int64)
			requestValues := make(map[string][]int64)
			modelTotals := make(map[string]int64)
			for _, r := range rows {
				date := r.Str("date")
				model := r.Str("model_name")
				idx, ok := dateIndex[date]
				if !ok || model == "" {
					// Java: out-of-month date or empty/NULL model is skipped entirely.
					continue
				}
				tokens := r.I64("total_tokens", 0)
				requests := r.I64("request_count", 0)
				if _, seen := tokenValues[model]; !seen {
					order = append(order, model)
					tokenValues[model] = make([]int64, daysInMonth)
					requestValues[model] = make([]int64, daysInMonth)
				}
				tokenValues[model][idx] += tokens
				requestValues[model][idx] += requests
				modelTotals[model] += tokens
			}

			// Java sorts by monthly total tokens descending (stable), used to pin the
			// front-end colour palette (TrendChartStatsCollector.java:365-367).
			sort.SliceStable(order, func(i, j int) bool {
				return modelTotals[order[i]] > modelTotals[order[j]]
			})

			// Java: maxValue is the tallest *stacked* day (sum over models), totalValue is
			// the grand total (TrendChartStatsCollector.java:373-395).
			tokenValuesOM := httpx.NewOrderedMap()
			requestValuesOM := httpx.NewOrderedMap()
			dailyTotals := make([]int64, daysInMonth)
			var totalValue int64
			for _, model := range order {
				tokenValuesOM.Set(model, tokenValues[model])
				requestValuesOM.Set(model, requestValues[model])
				for i, v := range tokenValues[model] {
					dailyTotals[i] += v
					totalValue += v
				}
			}
			var maxValue int64
			for _, dt := range dailyTotals {
				if dt > maxValue {
					maxValue = dt
				}
			}

			return httpx.NewOrderedMap().
				Set("year", year).
				Set("month", month).
				Set("days", days).
				Set("models", order).
				Set("tokenValues", tokenValuesOM).
				Set("requestValues", requestValuesOM).
				Set("maxValue", maxValue).
				Set("totalValue", totalValue), nil
		}

		// Serve through the shared stale-while-revalidate helper: fresh hit answers
		// instantly, a stale hit answers from the last snapshot while the refresh
		// runs in the background, and a cold cache lets exactly one request run the
		// aggregation (concurrent polls wait for it instead of piling up identical
		// scans, each holding one of the four read-pool connections).
		d.serveCached(c, ctx, cacheKey, cacheFrom, cacheTo, dashCacheTTL,
			"查询使用历史数据失败", compute)
	})

	// GET /admin/api/logs/stream (SSE)
	g.GET("/logs/stream", func(c *gin.Context) {
		c.Header("Content-Type", "text/event-stream;charset=UTF-8")
		c.Header("Cache-Control", "no-cache, no-store, must-revalidate")
		c.Header("Pragma", "no-cache")
		c.Header("X-Accel-Buffering", "no")

		ch, cancel := d.LogSSE.Subscribe()
		defer cancel()

		// Send an initial keepalive with retry directive
		_, _ = c.Writer.Write([]byte("retry: 2000\n\n"))
		c.Writer.Flush()

		ctxDone := c.Request.Context().Done()
		for {
			select {
			case <-ctxDone:
				return
			case log, ok := <-ch:
				if !ok {
					return
				}
				data, err := json.Marshal(log)
				if err != nil {
					continue
				}
				_, _ = c.Writer.Write([]byte("event: log\ndata: "))
				_, _ = c.Writer.Write(data)
				_, _ = c.Writer.Write([]byte("\n\n"))
				c.Writer.Flush()
			}
		}
	})

	// GET /admin/api/sse-test — SSE connectivity test endpoint
	// Java AdminLogController.sseTest: sends two test events and completes.
	g.GET("/sse-test", func(c *gin.Context) {
		c.Header("Content-Type", "text/event-stream;charset=UTF-8")
		c.Header("Cache-Control", "no-cache, no-store, must-revalidate")
		c.Header("Pragma", "no-cache")
		c.Header("X-Accel-Buffering", "no")

		_, _ = c.Writer.Write([]byte("retry: 2000\n\n"))
		_, _ = c.Writer.Write([]byte("event: test\ndata: {\"message\":\"SSE connection works\"}\n\n"))
		c.Writer.Flush()

		// Simulate a short delay between events
		time.Sleep(200 * time.Millisecond)

		_, _ = c.Writer.Write([]byte("event: complete\ndata: {\"status\":\"done\"}\n\n"))
		c.Writer.Flush()
	})

	// GET /admin/api/logs/{logId}/request-data
	g.GET("/logs/:logId/request-data", func(c *gin.Context) {
		ctx := c.Request.Context()
		id, ok := pathID(c, "logId")
		if !ok {
			httpx.OK(c, httpx.NewOrderedMap().
				Set("requestHeaders", nil).
				Set("requestBody", nil))
			return
		}
		// Java RequestLogQuerySupport.getRequestDataByLogId:148-153 selects by id
		// only — no phase condition.
		row, _ := d.Store.QueryOne(ctx,
			"SELECT request_headers, request_body FROM request_logs WHERE id = ?", id)
		if row == nil {
			httpx.OK(c, httpx.NewOrderedMap().
				Set("requestHeaders", nil).
				Set("requestBody", nil))
			return
		}
		httpx.OK(c, httpx.NewOrderedMap().
			Set("requestHeaders", row.StrPtr("request_headers")).
			Set("requestBody", row.StrPtr("request_body")))
	})
}

// logTree mirrors the LinkedHashMap the Java controller builds per trace.
// Field order matches AdminLogController.java:131-164 (hasRequestData is added
// last, after the endTime sort).
type logTree struct {
	TraceID      string `json:"traceId"`
	Logs         []any  `json:"logs"`
	RetryCount   int    `json:"retryCount"`
	SuccessCount int    `json:"successCount"`
	FailCount    int    `json:"failCount"`
	// InterruptedCount keeps an aborted request (caller/proxy went away) out of
	// FailCount, so the UI does not present it as an upstream failure.
	InterruptedCount int    `json:"interruptedCount"`
	ModelName        string `json:"modelName"`
	TotalTimeMs      int64  `json:"totalTimeMs"`
	StartTime        string `json:"startTime"`
	EndTime          string `json:"endTime"`
	HasRequestData   bool   `json:"hasRequestData"`
}
