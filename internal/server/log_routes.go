package server

import (
	"encoding/json"
	"sort"
	"strconv"
	"strings"
	"time"

	"github.com/gin-gonic/gin"
	"github.com/my-search/my-ai-gateway/internal/httpx"
	"github.com/my-search/my-ai-gateway/internal/jtime"
	"github.com/my-search/my-ai-gateway/internal/models"
	"github.com/my-search/my-ai-gateway/internal/store"
)

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
			// Java RequestLogMapper.java:101 — created_at >= #{startTime}. The
			// column holds both "…T…" (app writes) and "… …" (SQL defaults)
			// representations, so both sides go through SQLite datetime() to
			// compare the same instant instead of the raw text.
			where = append(where, "datetime(r.created_at) >= datetime(?)")
			args = append(args, startTime)
		}
		if endTime != "" {
			// Java RequestLogMapper.java:102 — created_at <= #{endTime}.
			where = append(where, "datetime(r.created_at) <= datetime(?)")
			args = append(args, endTime)
		}
		w := ""
		if len(where) > 0 {
			w = " AND " + strings.Join(where, " AND ")
		}

		countRow, _ := d.Store.QueryOne(ctx,
			`SELECT COUNT(DISTINCT r.trace_id) as total FROM request_logs r WHERE 1=1`+w, args...)
		total := int64(0)
		if countRow != nil {
			total = countRow.I64("total", 0)
		}

		traceArgs := make([]any, len(args))
		copy(traceArgs, args)
		// NOTE: the where clause references columns via the "r." alias — this query
		// must keep the same alias as the count query or the filter silently fails.
		// Java RequestLogMapper.java:94-105 pages traces by MAX(created_at) DESC.
		traceRows, _ := d.Store.Query(ctx,
			`SELECT trace_id FROM (SELECT trace_id, MAX(created_at) FROM request_logs r WHERE 1=1`+w+` GROUP BY trace_id ORDER BY MAX(created_at) DESC LIMIT ? OFFSET ?)`,
			append(traceArgs, limit, offset)...)

		traceIDs := make([]string, 0, len(traceRows))
		for _, r := range traceRows {
			traceIDs = append(traceIDs, r.Str("trace_id"))
		}

		trees := make([]logTree, 0, len(traceIDs))
		if len(traceIDs) > 0 {
			quotedIDs := make([]string, 0, len(traceIDs))
			for _, id := range traceIDs {
				quotedIDs = append(quotedIDs, "'"+strings.ReplaceAll(id, "'", "''")+"'")
			}
			traceList := strings.Join(quotedIDs, ",")

			logRows, _ := d.Store.Query(ctx,
				"SELECT * FROM request_logs WHERE trace_id IN ("+traceList+") ORDER BY created_at ASC")

			traceMap := make(map[string][]store.Row)
			for _, r := range logRows {
				tid := r.Str("trace_id")
				traceMap[tid] = append(traceMap[tid], r)
			}

			// Check which traces have request data
			hasDataRows, _ := d.Store.Query(ctx,
				"SELECT DISTINCT trace_id FROM request_logs WHERE trace_id IN ("+traceList+") AND phase='start' AND (request_headers IS NOT NULL OR request_body IS NOT NULL)")
			hasData := make(map[string]bool)
			for _, r := range hasDataRows {
				hasData[r.Str("trace_id")] = true
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
				var retryCount, successCount, failCount int
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
					}
					if !haveModel && lr.ModelName != nil {
						modelName = *lr.ModelName
						haveModel = true
					}
					// Java only accumulates response time for terminal phases
					if (lr.Phase == "success" || lr.Phase == "fail") && lr.ResponseTimeMs != nil {
						totalTimeMs += int64(*lr.ResponseTimeMs)
					}
				}

				// Java AdminLogController.java:161-164 formats both bounds with DT_FMT
				// from the first/last log of the chronologically sorted slice.
				trees = append(trees, logTree{
					TraceID:        tid,
					Logs:           logItems,
					RetryCount:     retryCount,
					SuccessCount:   successCount,
					FailCount:      failCount,
					ModelName:      modelName,
					TotalTimeMs:    totalTimeMs,
					StartTime:      dtFmt(parsed[0].CreatedAt.T),
					EndTime:        dtFmt(parsed[len(parsed)-1].CreatedAt.T),
					HasRequestData: hasData[tid],
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
		// of traces actually returned, not from the requested limit.
		httpx.OK(c, httpx.NewOrderedMap().
			Set("data", trees).
			Set("total", total).
			Set("offset", offset).
			Set("limit", limit).
			Set("hasMore", int64(offset+len(trees)) < total))
	})

	// POST /admin/api/logs/clean
	g.POST("/logs/clean", func(c *gin.Context) {
		ctx := c.Request.Context()
		// Java RequestLogCleanupSupport.cleanOldLogs:33-38 — delete rows older than
		// now-30d (JVM zone pinned to UTC). datetime() normalises the stored text so
		// mixed "T"/" " representations are compared as instants, not as strings.
		since := jtime.FormatDefault(time.Now().UTC().AddDate(0, 0, -30))
		if _, err := d.Store.Exec(ctx, "DELETE FROM request_logs WHERE datetime(created_at) < datetime(?)", since); err != nil {
			// Java AdminLogController.cleanLogs:213-217 answers 200 with the error.
			httpx.OK(c, httpx.NewOrderedMap().Set("success", false).Set("error", err.Error()))
			return
		}
		httpx.OK(c, httpx.NewOrderedMap().Set("success", true).Set("message", "已清理 30 天前的日志"))
	})

	// GET /admin/api/logs/usage-chart
	g.GET("/logs/usage-chart", func(c *gin.Context) {
		ctx := c.Request.Context()
		// Java AdminLogController.logUsageChart:245 defaults to LocalDate.now();
		// TimeZoneConfig pins the JVM zone to UTC, so the default period is the
		// current UTC month.
		nowUTC := time.Now().UTC()
		yearStr := c.DefaultQuery("year", strconv.Itoa(nowUTC.Year()))
		monthStr := c.DefaultQuery("month", strconv.Itoa(int(nowUTC.Month())))
		modelType := c.DefaultQuery("modelType", "entry")
		// StatsSupport.emptyToNull trims and treats blank as "no filter".
		modelName := strings.TrimSpace(c.Query("modelName"))
		gwKeyIDStr := strings.TrimSpace(c.Query("gatewayApiKeyId"))
		apiKeyName := strings.TrimSpace(c.Query("apiKeyName"))

		year, errY := strconv.Atoi(strings.TrimSpace(yearStr))
		if errY != nil {
			year = nowUTC.Year()
		}
		month, errM := strconv.Atoi(strings.TrimSpace(monthStr))
		if errM != nil {
			month = int(nowUTC.Month())
		}

		if month < 1 || month > 12 {
			httpx.JSON(c, 200, map[string]any{"error": "month 必须在 1-12 之间"})
			return
		}

		// Java TrendChartStatsCollector.collectLogUsageChart:314-320 — half-open
		// [since, until) covering the requested calendar month.
		since := time.Date(year, time.Month(month), 1, 0, 0, 0, 0, time.UTC)
		until := since.AddDate(0, 1, 0)
		daysInMonth := until.AddDate(0, 0, -1).Day()

		days := make([]string, daysInMonth)
		dateIndex := make(map[string]int, daysInMonth)
		for i := range days {
			days[i] = since.AddDate(0, 0, i).Format("2006-01-02")
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
		if modelType == "channel" {
			// Java RequestLogMapper.java:527-559 — trace-level outcome aggregation.
			// The trace set is selected by rows inside [since, until) with a non-empty
			// channel_model_name, but each selected trace is then aggregated over its
			// whole history: successful traces are attributed to their channel model
			// name (NULL names are dropped by the collector), failed traces fold into
			// "请求失败" with zero tokens. The per-trace token value is MAX, not SUM.
			args := append([]any{jtime.FormatDefault(since), jtime.FormatDefault(until)}, filterArgs...)
			rows, _ = d.Store.Query(ctx,
				`SELECT date, model_name, COUNT(1) AS request_count,
				        COALESCE(SUM(total_tokens), 0) AS total_tokens
				   FROM (
				     SELECT r.trace_id,
				            DATE(MIN(r.created_at)) AS date,
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
				             WHERE datetime(created_at) >= datetime(?) AND datetime(created_at) < datetime(?)
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
			args := append([]any{jtime.FormatDefault(since), jtime.FormatDefault(until)}, filterArgs...)
			rows, _ = d.Store.Query(ctx,
				`SELECT DATE(created_at) AS date, model_name,
				        COALESCE(SUM(CASE WHEN phase = 'success' THEN COALESCE(total_tokens, 0) ELSE 0 END), 0) AS total_tokens,
				        COUNT(DISTINCT CASE WHEN phase = 'success' THEN trace_id END) AS request_count
				   FROM request_logs
				  WHERE datetime(created_at) >= datetime(?) AND datetime(created_at) < datetime(?)
				    AND model_name IS NOT NULL AND model_name != ''`+w+`
				  GROUP BY DATE(created_at), model_name
				  ORDER BY date ASC, total_tokens DESC`, args...)
		}

		// Java TrendChartStatsCollector.collectLogUsageChart:340-362 — accumulate
		// per-day buckets, keeping first-seen model order for stable tie-breaking.
		order := make([]string, 0, 8)
		values := make(map[string][]int64)
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
			bucket, seen := values[model]
			if !seen {
				order = append(order, model)
				bucket = make([]int64, daysInMonth)
				values[model] = bucket
				tokenValues[model] = make([]int64, daysInMonth)
				requestValues[model] = make([]int64, daysInMonth)
			}
			bucket[idx] += tokens
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
		valuesOM := httpx.NewOrderedMap()
		tokenValuesOM := httpx.NewOrderedMap()
		requestValuesOM := httpx.NewOrderedMap()
		dailyTotals := make([]int64, daysInMonth)
		var totalValue int64
		for _, model := range order {
			valuesOM.Set(model, values[model])
			tokenValuesOM.Set(model, tokenValues[model])
			requestValuesOM.Set(model, requestValues[model])
			for i, v := range values[model] {
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

		httpx.OK(c, httpx.NewOrderedMap().
			Set("year", year).
			Set("month", month).
			Set("days", days).
			Set("models", order).
			Set("values", valuesOM).
			Set("tokenValues", tokenValuesOM).
			Set("requestValues", requestValuesOM).
			Set("maxValue", maxValue).
			Set("totalValue", totalValue))
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
	TraceID        string `json:"traceId"`
	Logs           []any  `json:"logs"`
	RetryCount     int    `json:"retryCount"`
	SuccessCount   int    `json:"successCount"`
	FailCount      int    `json:"failCount"`
	ModelName      string `json:"modelName"`
	TotalTimeMs    int64  `json:"totalTimeMs"`
	StartTime      string `json:"startTime"`
	EndTime        string `json:"endTime"`
	HasRequestData bool   `json:"hasRequestData"`
}
