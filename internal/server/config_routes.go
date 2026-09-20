package server

import (
	"context"
	"crypto/rand"
	"encoding/hex"
	"fmt"
	"io"
	"math"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"time"

	"github.com/gin-gonic/gin"
	"github.com/my-search/my-ai-gateway/internal/httpx"
	"github.com/my-search/my-ai-gateway/internal/jtime"
	"github.com/my-search/my-ai-gateway/internal/store"
)

var allowedImageExts = map[string]bool{
	".jpg": true, ".jpeg": true, ".png": true,
	".gif": true, ".webp": true, ".bmp": true, ".svg": true,
}

func registerConfigRoutes(g *gin.RouterGroup, d Deps) {
	// GET /admin/api/config/system
	g.GET("/config/system", func(c *gin.Context) {
		ctx := c.Request.Context()
		cfg := d.Config.GetSystemConfig(ctx)
		httpx.OK(c, httpx.NewOrderedMap().Set("success", true).Set("data", cfg))
	})

	// PUT /admin/api/config/system
	g.PUT("/config/system", func(c *gin.Context) {
		ctx := c.Request.Context()
		var body map[string]string
		if err := c.ShouldBindJSON(&body); err != nil {
			httpx.OK(c, failureEnvelope("请求参数错误"))
			return
		}

		// Validate keys
		for k, v := range body {
			switch k {
			case "log_retention_days":
				n, err := strconv.Atoi(v)
				if err != nil || n < 1 || n > 365 {
					httpx.OK(c, failureEnvelope("日志保留天数必须在 1-365 之间"))
					return
				}
			case "log_cleanup_enabled":
				if v != "0" && v != "1" {
					httpx.OK(c, failureEnvelope("清理开关值无效，必须为 0 或 1"))
					return
				}
			case "request_body_ttl_hours":
				n, err := strconv.Atoi(v)
				if err != nil || n < 0 || n > 8760 {
					httpx.OK(c, failureEnvelope("请求数据保留时长必须在 0-8760 小时之间"))
					return
				}
			case "retry_fail_ttl_hours":
				if v != "" {
					n, err := strconv.Atoi(v)
					if err != nil || n < 0 || n > 8760 {
						httpx.OK(c, failureEnvelope("重试/失败数据保留时长必须在 0-8760 小时之间（或留空）"))
						return
					}
				}
			case "request_data_save_level":
				if v != "info" && v != "warn" && v != "error" {
					httpx.OK(c, failureEnvelope("原始请求数据保存级别无效，必须为 info / warn / error"))
					return
				}
			case "timeout_min_seconds":
				n, err := strconv.Atoi(v)
				if err != nil || n < 1 || n > 600 {
					httpx.OK(c, failureEnvelope("最小超时时间必须在 1-600 秒之间"))
					return
				}
				if maxStr, hasMax := body["timeout_max_seconds"]; hasMax {
					max, err := strconv.Atoi(maxStr)
					if err == nil && n > max {
						httpx.OK(c, failureEnvelope("最小超时时间不能大于最大超时时间"))
						return
					}
				}
			case "timeout_max_seconds":
				n, err := strconv.Atoi(v)
				if err != nil || n < 1 || n > 600 {
					httpx.OK(c, failureEnvelope("最大超时时间必须在 1-600 秒之间"))
					return
				}
				if minStr, hasMin := body["timeout_min_seconds"]; hasMin {
					min, err := strconv.Atoi(minStr)
					if err == nil && min > n {
						httpx.OK(c, failureEnvelope("最小超时时间不能大于最大超时时间"))
						return
					}
				}
			case "channel_model_refresh_interval_minutes":
				n, err := strconv.Atoi(v)
				if err != nil || n < 1 || n > 1440 {
					httpx.OK(c, failureEnvelope("渠道模型刷新间隔必须在 1-1440 分钟之间"))
					return
				}
				body["channel_model_refresh_interval_minutes"] = strconv.Itoa(n)
			}
		}

		if err := d.Config.UpdateSystemConfig(ctx, body); err != nil {
			httpx.OK(c, failureEnvelope("更新失败："+err.Error()))
			return
		}
		httpx.OK(c, httpx.NewOrderedMap().Set("success", true).Set("data", d.Config.GetSystemConfig(ctx)))
	})

	// POST /admin/api/upload
	g.POST("/upload", func(c *gin.Context) {
		file, header, err := c.Request.FormFile("file")
		if err != nil {
			httpx.OK(c, failureEnvelope("文件为空"))
			return
		}
		defer file.Close()

		contentType := header.Header.Get("Content-Type")
		if !strings.HasPrefix(contentType, "image/") {
			httpx.OK(c, failureEnvelope("只允许上传图片文件"))
			return
		}

		ext := strings.ToLower(filepath.Ext(header.Filename))
		if !allowedImageExts[ext] {
			httpx.OK(c, failureEnvelope("不允许上传该文件类型（仅支持: jpg/png/gif/webp/bmp/svg）"))
			return
		}

		uploadDir := d.Cfg.UploadDir
		if uploadDir == "" {
			uploadDir = "data/uploads"
		}
		dateDir := time.Now().UTC().In(jtime.Shanghai).Format("2006/01/02")
		dir := filepath.Join(uploadDir, dateDir)
		if err := os.MkdirAll(dir, 0755); err != nil {
			httpx.OK(c, failureEnvelope("上传失败："+err.Error()))
			return
		}

		uuid16 := make([]byte, 8)
		rand.Read(uuid16)
		fileName := hex.EncodeToString(uuid16) + ext
		dst := filepath.Join(dir, fileName)

		out, err := os.Create(dst)
		if err != nil {
			httpx.OK(c, failureEnvelope("上传失败："+err.Error()))
			return
		}
		defer out.Close()
		if _, err := io.Copy(out, file); err != nil {
			httpx.OK(c, failureEnvelope("上传失败："+err.Error()))
			return
		}

		url := "/uploads/" + dateDir + "/" + fileName
		httpx.OK(c, httpx.NewOrderedMap().Set("success", true).
			Set("url", url).
			Set("originalName", header.Filename))
	})
}

// registerDashboardRoutes wires the dashboard endpoints from AdminAuthController.
func registerDashboardRoutes(g *gin.RouterGroup, d Deps) {
	// GET /admin/api/dashboard/stats
	//
	// Every query is bounded by the selected time window ([since, until)) and
	// forced through idx_request_logs_created_at_phase_trace, so the planner can
	// never fall back to a full scan of the trace_id / channel_name indexes as
	// the request_logs table grows.
	g.GET("/dashboard/stats", func(c *gin.Context) {
		ctx := c.Request.Context()

		// 尝试命中缓存
		cacheKey := c.Query("range")
		cacheFrom := c.Query("from")
		cacheTo := c.Query("to")
		if cached, ok := d.DashCache.Get(cacheKey, cacheFrom, cacheTo); ok {
			httpx.OK(c, cached)
			return
		}

		dr := parseDashRange(c)

		// Totals for the window and for its comparison window (昨日 / 上周同期 /
		// 上月同期 / 上一个等长区间). Each call is a single range scan; success
		// is start-anchored so fail = requests - success and successRate ≤ 100%.
		totals := dashWindowTotals(ctx, d, dr.since, dr.until)
		prevTotals := dashWindowTotals(ctx, d, dr.prevSince, dr.prevUntil)

		// Top-10 rankings for the same window (channel / entry model / channel model).
		chRows, _ := d.Store.QueryReadOnly(ctx,
			`SELECT channel_name AS name,
			        COUNT(DISTINCT CASE WHEN phase='start' THEN trace_id END) AS requests,
			        COUNT(DISTINCT CASE WHEN phase='success' THEN trace_id END) AS success,
			        AVG(CASE WHEN first_byte_ms>0 THEN first_byte_ms END) AS avg_time,
			        COALESCE(SUM(CASE WHEN phase='success' THEN COALESCE(total_tokens,0) ELSE 0 END),0) AS total_tokens
			   FROM request_logs INDEXED BY idx_request_logs_created_at_phase_trace
			  WHERE created_at >= ? AND created_at < ? AND phase IN ('start','success')
			    AND channel_name IS NOT NULL AND channel_name != ''
			  GROUP BY channel_name ORDER BY requests DESC LIMIT 10`,
			jtime.FormatDefault(dr.since), jtime.FormatDefault(dr.until))
		mdlRows, _ := d.Store.QueryReadOnly(ctx,
			`SELECT model_name AS name,
			        COUNT(DISTINCT CASE WHEN phase='start' THEN trace_id END) AS requests,
			        COUNT(DISTINCT CASE WHEN phase='success' THEN trace_id END) AS success,
			        AVG(CASE WHEN first_byte_ms>0 THEN first_byte_ms END) AS avg_time,
			        COALESCE(SUM(CASE WHEN phase='success' THEN COALESCE(total_tokens,0) ELSE 0 END),0) AS total_tokens
			   FROM request_logs INDEXED BY idx_request_logs_created_at_phase_trace
			  WHERE created_at >= ? AND created_at < ? AND phase IN ('start','success')
			    AND model_name IS NOT NULL AND model_name != ''
			  GROUP BY model_name ORDER BY requests DESC LIMIT 10`,
			jtime.FormatDefault(dr.since), jtime.FormatDefault(dr.until))
		cmRows, _ := d.Store.QueryReadOnly(ctx,
			`SELECT channel_name, channel_model_name AS name,
			        COUNT(DISTINCT CASE WHEN phase='start' THEN trace_id END) AS requests,
			        COUNT(DISTINCT CASE WHEN phase='success' THEN trace_id END) AS success,
			        AVG(CASE WHEN first_byte_ms>0 THEN first_byte_ms END) AS avg_time,
			        COALESCE(SUM(CASE WHEN phase='success' THEN COALESCE(total_tokens,0) ELSE 0 END),0) AS total_tokens
			   FROM request_logs INDEXED BY idx_request_logs_created_at_phase_trace
			  WHERE created_at >= ? AND created_at < ? AND phase IN ('start','success')
			    AND channel_model_name IS NOT NULL AND channel_model_name != ''
			  GROUP BY channel_name, channel_model_name ORDER BY requests DESC LIMIT 10`,
			jtime.FormatDefault(dr.since), jtime.FormatDefault(dr.until))

		out := httpx.NewOrderedMap().
			Set("range", dr.meta()).
			Set("totals", totals).
			Set("prevTotals", prevTotals).
			Set("sparklines", dashSparklines(ctx, d, dr)).
			Set("channelRank", channelRankList(chRows)).
			Set("modelRank", modelRankList(mdlRows)).
			Set("channelModelRank", channelModelRankList(cmRows))

		// 写入缓存
		d.DashCache.Set(cacheKey, cacheFrom, cacheTo, out)

		httpx.OK(c, out)
	})

	// GET /admin/api/dashboard/today-trend
	//
	// Follows the selected window. Buckets sit on a grid anchored at the window
	// start: windows up to 24h are split every 10 minutes, longer windows by
	// day (a multi-day window starting mid-day keeps its offsets). Failures are
	// derived as requests - success (start-anchored), never by summing
	// phase='start' rows as if they were failures.
	g.GET("/dashboard/today-trend", func(c *gin.Context) {
		ctx := c.Request.Context()
		mode := strings.ToLower(strings.TrimSpace(c.DefaultQuery("mode", "all")))
		if mode != "entry" && mode != "channel" {
			mode = "all"
		}
		dr := parseDashRange(c)

		// Buckets are laid out on a grid anchored at since (not at Shanghai
		// midnight): a custom window may start at any second, and anchoring by
		// wall clock would merge, say, day-1 10:00 with day-2 10:00 into one
		// 10-minute label. Integer epoch seconds keep bucket edges exact.
		bucketUnit := "10m"
		bucketSecs := int64(600)
		if dr.until.Sub(dr.since) > 24*time.Hour {
			bucketUnit = "1d"
			bucketSecs = 24 * 60 * 60
		}
		bucketExpr := fmt.Sprintf(
			`CAST((CAST(STRFTIME('%%s', created_at) AS INTEGER) - %d) / %d AS INTEGER)`,
			dr.since.Unix(), bucketSecs)

		// Pre-fill the bucket labels: HH:MM within the first 24h, calendar dates
		// beyond that (with the clock time appended when since is not midnight).
		var buckets []string
		for i := 0; ; i++ {
			start := dr.since.Add(time.Duration(int64(i)*bucketSecs) * time.Second)
			if !start.Before(dr.until) {
				break
			}
			if bucketUnit == "10m" {
				buckets = append(buckets, start.In(jtime.Shanghai).Format("15:04"))
			} else {
				buckets = append(buckets, dashBucketLabel(start))
			}
		}
		if len(buckets) == 0 {
			buckets = []string{dashBucketLabel(dr.since)}
		}

		rows, _ := d.Store.QueryReadOnly(ctx,
			`SELECT `+bucketExpr+` AS bucket, phase, model_name, channel_name, channel_model_name,
			        COUNT(DISTINCT trace_id) AS cnt
			   FROM request_logs INDEXED BY idx_request_logs_created_at_phase_trace
			  WHERE created_at >= ? AND created_at < ? AND phase IN ('start','success')
			  GROUP BY bucket, phase, model_name, channel_name, channel_model_name
			  ORDER BY bucket`,
			jtime.FormatDefault(dr.since), jtime.FormatDefault(dr.until))

		// Rows carry the integer bucket index; anything outside the grid is
		// dropped (it can only come from rounded edges of the scan window).
		bucketOf := func(r store.Row) (int, bool) {
			i := int(r.I64("bucket", -1))
			return i, i >= 0 && i < len(buckets)
		}

		if mode == "all" {
			starts := make([]int64, len(buckets))
			success := make([]int64, len(buckets))
			for _, r := range rows {
				idx, ok := bucketOf(r)
				if !ok {
					continue
				}
				switch r.Str("phase") {
				case "start":
					starts[idx] += r.I64("cnt", 0)
				case "success":
					success[idx] += r.I64("cnt", 0)
				}
			}
			fail := make([]int64, len(buckets))
			for i := range fail {
				f := starts[i] - success[i]
				if f < 0 {
					f = 0
				}
				fail[i] = f
			}
			httpx.OK(c, httpx.NewOrderedMap().
				Set("range", dr.meta()).
				Set("buckets", buckets).
				Set("bucketUnit", bucketUnit).
				Set("mode", "all").
				Set("series", map[string]any{"success": success, "fail": fail}))
			return
		}

		// entry / channel modes: start-anchored request counts per model.
		series := make(map[string][]int64)
		for _, r := range rows {
			if r.Str("phase") != "start" {
				continue
			}
			idx, ok := bucketOf(r)
			if !ok {
				continue
			}
			var key string
			if mode == "entry" {
				key = r.Str("model_name")
			} else {
				cn := r.Str("channel_name")
				mn := r.Str("channel_model_name")
				if mn == "" {
					continue
				}
				if cn != "" {
					key = cn + "/" + mn
				} else {
					key = mn
				}
			}
			if key == "" {
				continue
			}
			if series[key] == nil {
				series[key] = make([]int64, len(buckets))
			}
			series[key][idx] += r.I64("cnt", 0)
		}
		httpx.OK(c, httpx.NewOrderedMap().
			Set("range", dr.meta()).
			Set("buckets", buckets).
			Set("bucketUnit", bucketUnit).
			Set("mode", mode).
			Set("series", series))
	})
}

// dashMaxRangeDays caps custom windows (and therefore every dashboard scan) at
// roughly a year.
const dashMaxRangeDays = 366

// dashRange is a resolved dashboard window. since/until are UTC instants
// (until exclusive); prevSince/prevUntil describe the comparison window of the
// same shape one period earlier (yesterday / same point last week / same point
// last month / previous equal-length window).
//
// The window is not necessarily day-aligned: a custom range may start and end
// at any second (e.g. 09:30:00 → 12:00:00 of the same day).
type dashRange struct {
	key       string
	since     time.Time
	until     time.Time
	prevSince time.Time
	prevUntil time.Time
}

// meta describes the effective window to the client. start/end stay
// Shanghai calendar dates for backwards compatibility; startAt/endAt carry the
// full Shanghai wall-clock bounds so sub-day windows are not misreported as
// whole days. end/endAt are inclusive (the last second inside the window).
func (r dashRange) meta() map[string]any {
	end := r.until.Add(-time.Second)
	prevEnd := r.prevUntil.Add(-time.Second)
	return map[string]any{
		"key":     r.key,
		"start":   dashDate(r.since),
		"end":     dashDate(end),
		"startAt": dashDateTime(r.since),
		"endAt":   dashDateTime(end),
		"prev": map[string]any{
			"start":   dashDate(r.prevSince),
			"end":     dashDate(prevEnd),
			"startAt": dashDateTime(r.prevSince),
			"endAt":   dashDateTime(prevEnd),
		},
	}
}

// parseDashRange resolves ?range=today|week|month|custom plus optional
// ?from=/?to=. Both bounds accept either a Shanghai date (yyyy-MM-dd, meaning
// 00:00:00 / the whole day) or a Shanghai wall-clock timestamp
// (yyyy-MM-ddTHH:mm[:ss], an offset-aware RFC3339 value, or the same two with a
// space separator). Invalid or inverted custom input falls back to today;
// over-long windows are clamped to dashMaxRangeDays.
func parseDashRange(c *gin.Context) dashRange {
	now := time.Now().UTC().In(jtime.Shanghai)
	todayStart := jtime.ShanghaiStart(now)
	tomorrow := todayStart.AddDate(0, 0, 1)

	todayRange := func() dashRange {
		return dashRange{
			key:       "today",
			since:     todayStart,
			until:     tomorrow,
			prevSince: todayStart.AddDate(0, 0, -1),
			prevUntil: todayStart,
		}
	}

	switch strings.ToLower(strings.TrimSpace(c.DefaultQuery("range", "today"))) {
	case "week":
		// 本周至今 vs 上周同期（上周一 00:00 起、同一跨度）
		since := jtime.ShanghaiWeekStart(now)
		prevSince := since.AddDate(0, 0, -7)
		return dashRange{key: "week", since: since, until: tomorrow,
			prevSince: prevSince, prevUntil: prevSince.Add(tomorrow.Sub(since))}
	case "month":
		// 本月至今 vs 上月同期（上月 1 日起、同一跨度，不越过本月起点）
		since := jtime.ShanghaiMonthStart(now)
		prevSince := since.AddDate(0, -1, 0)
		prevUntil := prevSince.Add(tomorrow.Sub(since))
		if prevUntil.After(since) {
			prevUntil = since
		}
		return dashRange{key: "month", since: since, until: tomorrow,
			prevSince: prevSince, prevUntil: prevUntil}
	case "custom":
		from, fromHasTime, okFrom := dashParseInstant(c.Query("from"))
		if !okFrom {
			return todayRange()
		}
		to, toHasTime, okTo := dashParseInstant(c.Query("to"))
		if !okTo {
			to, toHasTime = from, fromHasTime
		}
		if to.Before(from) {
			return todayRange()
		}
		since := from
		// until 始终为开区间上界：带时间时把结束秒本身含入（+1s），
		// 仅有日期时含入整天（次日 00:00 前），与旧版语义一致。
		until := to.Add(time.Second)
		if !toHasTime {
			until = to.AddDate(0, 0, 1)
		}
		if maxSpan := time.Duration(dashMaxRangeDays) * 24 * time.Hour; until.Sub(since) > maxSpan {
			since = until.Add(-maxSpan)
		}
		// 自定义区间：上一期 = 紧邻其前的等长区间
		prevSince := since.Add(-until.Sub(since))
		return dashRange{key: "custom", since: since, until: until,
			prevSince: prevSince, prevUntil: since}
	default:
		return todayRange()
	}
}

// dashDate renders an instant as its Shanghai calendar date (yyyy-MM-dd).
func dashDate(t time.Time) string { return t.In(jtime.Shanghai).Format("2006-01-02") }

// dashDateTime renders an instant as its Shanghai wall-clock timestamp
// (yyyy-MM-ddTHH:mm:ss) — the same shape ?from=/?to= accept.
func dashDateTime(t time.Time) string {
	return t.In(jtime.Shanghai).Format("2006-01-02T15:04:05")
}

// dashBucketLabel renders the label of a >24h trend bucket: its Shanghai
// calendar date, plus the clock time when the bucket does not start at
// midnight (which happens when a custom window starts mid-day).
func dashBucketLabel(start time.Time) string {
	sh := start.In(jtime.Shanghai)
	if sh.Hour() == 0 && sh.Minute() == 0 && sh.Second() == 0 {
		return sh.Format("2006-01-02")
	}
	return sh.Format("2006-01-02 15:04")
}

// dashWindowTotals aggregates the headline metrics for one [since, until)
// window. success is start-anchored (mirroring the Java collector), so
// fail = requests - success and successRate cannot exceed 100%.
func dashWindowTotals(ctx context.Context, d Deps, since, until time.Time) map[string]any {
	out := map[string]any{
		"requests":        int64(0),
		"success":         int64(0),
		"fail":            int64(0),
		"successRate":     0.0,
		"avgResponseTime": int64(0),
		"avgOutputSpeed":  0.0,
		"totalTokens":     int64(0),
	}
	row, err := d.Store.QueryOneReadOnly(ctx,
		`SELECT COUNT(DISTINCT CASE WHEN phase='start' THEN trace_id END) AS requests,
		        COUNT(DISTINCT CASE WHEN phase='success' THEN trace_id END) AS success,
		        AVG(CASE WHEN first_byte_ms>0 THEN first_byte_ms END) AS avg_ttfb,
		        AVG(CASE WHEN phase='success' AND completion_tokens>0 AND response_time_ms>0
		                 THEN completion_tokens*1000.0/response_time_ms END) AS avg_speed,
		        COALESCE(SUM(CASE WHEN phase='success' THEN COALESCE(total_tokens,0) ELSE 0 END),0) AS total_tokens
		   FROM request_logs INDEXED BY idx_request_logs_created_at_phase_trace
		  WHERE created_at >= ? AND created_at < ? AND phase IN ('start','success')`,
		jtime.FormatDefault(since), jtime.FormatDefault(until))
	if err != nil || row == nil {
		return out
	}
	reqs := row.I64("requests", 0)
	succ := row.I64("success", 0)
	if succ > reqs {
		succ = reqs
	}
	out["requests"] = reqs
	out["success"] = succ
	out["fail"] = reqs - succ
	if reqs > 0 {
		out["successRate"] = math.Round(float64(succ)/float64(reqs)*1000) / 10
	}
	if v := row.F64Ptr("avg_ttfb"); v != nil {
		out["avgResponseTime"] = int64(math.Round(*v))
	}
	if v := row.F64Ptr("avg_speed"); v != nil {
		out["avgOutputSpeed"] = math.Round(*v*10) / 10
	}
	out["totalTokens"] = row.I64("total_tokens", 0)
	return out
}

// dashParseInstant parses a dashboard range bound. Accepted shapes:
//
//	2006-01-02                  → Shanghai midnight, hasTime=false
//	2006-01-02T15:04            → Shanghai wall clock
//	2006-01-02T15:04:05         → Shanghai wall clock
//	2006-01-02 15:04[:05]       → space-separated variants of the above
//	RFC3339 (with Z or ±hh:mm)  → explicit instant, offset honoured
//
// Date-only input deliberately means *Shanghai* midnight (not UTC like
// jtime.Parse), because the dashboard window is defined in Shanghai time.
// hasTime reports whether an explicit time-of-day was supplied, which decides
// how the range end is interpreted (that second vs the whole day).
func dashParseInstant(s string) (t time.Time, hasTime, ok bool) {
	s = strings.TrimSpace(s)
	if s == "" {
		return time.Time{}, false, false
	}
	// Explicit offset / Z: honour the instant as given.
	if withOffset, err := time.Parse(time.RFC3339, s); err == nil {
		return withOffset.UTC(), true, true
	}
	// Date-only: Shanghai midnight, whole-day semantics.
	if d, err := time.ParseInLocation("2006-01-02", s, jtime.Shanghai); err == nil {
		return d.UTC(), false, true
	}
	// Wall-clock timestamp: both 'T' and ' ' separators, seconds optional.
	normalized := strings.Replace(s, " ", "T", 1)
	for _, layout := range []string{"2006-01-02T15:04:05", "2006-01-02T15:04"} {
		if d, err := time.ParseInLocation(layout, normalized, jtime.Shanghai); err == nil {
			return d.UTC(), true, true
		}
	}
	return time.Time{}, false, false
}

// channelRankList maps channel rank rows for the dashboard payload.
func channelRankList(rows []store.Row) []map[string]any {
	out := make([]map[string]any, 0, len(rows))
	for _, r := range rows {
		item := map[string]any{
			"name":        r.Str("name"),
			"requests":    r.I64("requests", 0),
			"success":     r.I64("success", 0),
			"totalTokens": r.I64("total_tokens", 0),
			"avgTime":     int64(0),
		}
		if v := r.F64Ptr("avg_time"); v != nil {
			item["avgTime"] = int64(math.Round(*v))
		}
		out = append(out, item)
	}
	return out
}

// dashSparklinePoints is how many samples each card sparkline carries.
const dashSparklinePoints = 32

// dashSparklines returns one downsampled series per stat card (requests /
// successRate / avgResponseTime / avgOutputSpeed), so the four cards can draw
// a mini trend line without extra queries from the client. A single bucketed
// range scan feeds all four; buckets are derived from the window length so the
// scan stays bounded by the selected range.
func dashSparklines(ctx context.Context, d Deps, dr dashRange) map[string][]float64 {
	zero := make([]float64, dashSparklinePoints)
	out := map[string][]float64{
		"requests":        append([]float64(nil), zero...),
		"successRate":     append([]float64(nil), zero...),
		"avgResponseTime": append([]float64(nil), zero...),
		"avgOutputSpeed":  append([]float64(nil), zero...),
	}

	span := dr.until.Sub(dr.since)
	if span <= 0 {
		return out
	}
	bucketSecs := int64(span.Seconds()) / int64(dashSparklinePoints)
	if bucketSecs < 1 {
		bucketSecs = 1
	}

	// Bucket index is derived from the offset from `since` in whole epoch
	// seconds, so no timezone conversions or floating-point day arithmetic are
	// needed and the scan uses the created_at index range.
rows, _ := d.Store.QueryReadOnly(ctx, `
			SELECT CAST((CAST(STRFTIME('%s', created_at) AS INTEGER) - ?) / ? AS INTEGER) AS bucket,
			        COUNT(DISTINCT CASE WHEN phase='start' THEN trace_id END) AS requests,
			        COUNT(DISTINCT CASE WHEN phase='success' THEN trace_id END) AS success,
			        AVG(CASE WHEN first_byte_ms>0 THEN first_byte_ms END) AS avg_ttfb,
			        AVG(CASE WHEN phase='success' AND completion_tokens>0 AND response_time_ms>0
			                 THEN completion_tokens*1000.0/response_time_ms END) AS avg_speed
			   FROM request_logs INDEXED BY idx_request_logs_created_at_phase_trace
			  WHERE created_at >= ? AND created_at < ? AND phase IN ('start','success')
			  GROUP BY bucket`,
		dr.since.Unix(), bucketSecs,
		jtime.FormatDefault(dr.since), jtime.FormatDefault(dr.until))

	requests := make([]float64, dashSparklinePoints)
	successRate := make([]float64, dashSparklinePoints)
	avgResp := make([]float64, dashSparklinePoints)
	avgSpeed := make([]float64, dashSparklinePoints)

	// A single row may span several buckets when the range is dense; the row's
	// bucket marks where its data starts. Later buckets without rows keep the
	// previous value so the card lines read as a trend rather than a comb.
	lastRate, lastResp, lastSpeed := 0.0, 0.0, 0.0
	fillFrom := 0
	for _, r := range rows {
		b := int(r.I64("bucket", 0))
		if b < 0 {
			b = 0
		}
		if b >= dashSparklinePoints {
			b = dashSparklinePoints - 1
		}
		reqs := float64(r.I64("requests", 0))
		succ := float64(r.I64("success", 0))
		if succ > reqs {
			succ = reqs
		}
		rate := 0.0
		if reqs > 0 {
			rate = math.Round(succ/reqs*1000) / 10
		}
		resp := 0.0
		if v := r.F64Ptr("avg_ttfb"); v != nil {
			resp = math.Round(*v)
		}
		speed := 0.0
		if v := r.F64Ptr("avg_speed"); v != nil {
			speed = math.Round(*v*10) / 10
		}
		for i := fillFrom; i <= b; i++ {
			requests[i] = reqs
			successRate[i] = rate
			avgResp[i] = resp
			avgSpeed[i] = speed
		}
		lastRate, lastResp, lastSpeed = rate, resp, speed
		fillFrom = b + 1
	}
	// Tail: carry the last known value to the end of the window.
	for i := fillFrom; i < dashSparklinePoints; i++ {
		successRate[i] = lastRate
		avgResp[i] = lastResp
		avgSpeed[i] = lastSpeed
	}

	return map[string][]float64{
		"requests":        requests,
		"successRate":     successRate,
		"avgResponseTime": avgResp,
		"avgOutputSpeed":  avgSpeed,
	}
}

// modelRankList maps entry-model rank rows.
func modelRankList(rows []store.Row) []map[string]any {
	return rankRows(rows, false)
}

// channelModelRankList maps channel-model rank rows.
func channelModelRankList(rows []store.Row) []map[string]any {
	return rankRows(rows, true)
}

// rankRows shapes channel-model / entry-model rank rows. withChannel adds the
// channelName field used by the channel-model ranking table.
func rankRows(rows []store.Row, withChannel bool) []map[string]any {
	out := make([]map[string]any, 0, len(rows))
	for _, r := range rows {
		item := map[string]any{
			"name":        r.Str("name"),
			"requests":    r.I64("requests", 0),
			"success":     r.I64("success", 0),
			"totalTokens": r.I64("total_tokens", 0),
			"avgTime":     int64(0),
		}
		if withChannel {
			item["channelName"] = r.Str("channel_name")
		}
		if v := r.F64Ptr("avg_time"); v != nil {
			item["avgTime"] = int64(math.Round(*v))
		}
		out = append(out, item)
	}
	return out
}
