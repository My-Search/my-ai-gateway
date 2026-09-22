package server

import (
	"context"
	"log/slog"
	"net/http"
	"strconv"
	"strings"
	"time"

	"github.com/gin-gonic/gin"
	"github.com/my-search/my-ai-gateway/internal/httpx"
	"github.com/my-search/my-ai-gateway/internal/jtime"
	"github.com/my-search/my-ai-gateway/internal/store"
)

func registerAPIKeyRoutes(g *gin.RouterGroup, d Deps) {
	// GET /admin/api/api-keys
	g.GET("/api-keys", func(c *gin.Context) {
		ctx := c.Request.Context()
		rows, err := d.Store.Query(ctx, "SELECT * FROM api_keys ORDER BY created_at ASC")
		if err != nil {
			httpx.OK(c, failureEnvelope(err.Error()))
			return
		}

		// Key summary stats (today only), same shape as the channel list
		usageStats := getAPIKeySummaryStats(ctx, d.Store)

		type akItem struct {
			ID               int64         `json:"id"`
			KeyName          string        `json:"keyName"`
			KeyValue         string        `json:"keyValue"`
			Enabled          *int          `json:"enabled"`
			ShareCode        *string       `json:"shareCode"`
			Shared           *int          `json:"shared"`
			LastUsedAt       jtime.APITime `json:"lastUsedAt"`
			CreatedAt        jtime.APITime `json:"createdAt"`
			UpdatedAt        jtime.APITime `json:"updatedAt"`
			RequestCount     int64         `json:"requestCount"`
			PromptTokens     int64         `json:"promptTokens"`
			CompletionTokens int64         `json:"completionTokens"`
			TotalTokens      int64         `json:"totalTokens"`
		}

		out := make([]akItem, 0, len(rows))
		for _, r := range rows {
			k := store.RowToAPIKey(r)
			item := akItem{
				ID:         k.ID,
				KeyName:    k.KeyName,
				KeyValue:   k.KeyValue,
				Enabled:    k.Enabled,
				ShareCode:  k.ShareCode,
				Shared:     k.Shared,
				LastUsedAt: k.LastUsedAt,
				CreatedAt:  k.CreatedAt,
				UpdatedAt:  k.UpdatedAt,
			}
			if us, ok := usageStats[k.ID]; ok {
				// 汇总查询只统计 phase='success' 的行，成功数即请求次数
				item.RequestCount = us.SuccessCount
				item.PromptTokens = us.PromptTokens
				item.CompletionTokens = us.CompletionTokens
				item.TotalTokens = us.TotalTokens
			}
			out = append(out, item)
		}
		httpx.OK(c, out)
	})

	// GET /admin/api/api-keys/{id}
	g.GET("/api-keys/:id", func(c *gin.Context) {
		ctx := c.Request.Context()
		id, ok := pathID(c, "id")
		if !ok {
			notFound(c, "密钥不存在")
			return
		}
		row, err := d.Store.QueryOne(ctx, "SELECT * FROM api_keys WHERE id = ?", id)
		if err != nil {
			notFound(c, "密钥不存在")
			return
		}
		httpx.JSON(c, http.StatusOK, store.RowToAPIKey(row))
	})

	// POST /admin/api/api-keys
	g.POST("/api-keys", func(c *gin.Context) {
		ctx := c.Request.Context()
		var body struct {
			KeyName  string `json:"keyName"`
			KeyValue string `json:"keyValue"`
		}
		_ = c.ShouldBindJSON(&body)

		kv := strings.TrimSpace(body.KeyValue)
		shareCode := generateShareCode()

		if kv == "" {
			kv = generateAPIKeyValue()
		} else {
			// If a row with this key_value already exists, regenerate (Java behaviour).
			row, err := d.Store.QueryOne(ctx, "SELECT id FROM api_keys WHERE key_value = ?", kv)
			if err == nil && row != nil {
				kv = generateAPIKeyValue()
			}
		}

		now := jtime.FormatApp(time.Now().UTC())
		id, err := d.Store.Insert(ctx,
			"INSERT INTO api_keys (key_name, key_value, enabled, share_code, shared, created_at, updated_at) VALUES (?, ?, 1, ?, 1, ?, ?)",
			body.KeyName, kv, shareCode, now, now)
		if err != nil {
			httpx.OK(c, failureEnvelope(err.Error()))
			return
		}
		httpx.OK(c, httpx.NewOrderedMap().Set("success", true).Set("id", id))
	})

	// PUT /admin/api/api-keys/{id}
	g.PUT("/api-keys/:id", func(c *gin.Context) {
		ctx := c.Request.Context()
		id, ok := pathID(c, "id")
		if !ok {
			httpx.OK(c, failureEnvelope("密钥不存在"))
			return
		}
		var body map[string]any
		if err := c.ShouldBindJSON(&body); err != nil {
			httpx.OK(c, failureEnvelope("请求参数错误"))
			return
		}
		sets := make(map[string]any)
		if v, exists := body["keyName"]; exists {
			sets["key_name"] = v
		}
		if v, exists := body["keyValue"]; exists {
			sets["key_value"] = v
		}
		if v, exists := body["enabled"]; exists {
			sets["enabled"] = toIntAny(v)
		}
		if v, exists := body["shared"]; exists {
			sets["shared"] = toIntAny(v)
		}
		now := jtime.FormatApp(time.Now().UTC())
		sets["updated_at"] = now
		if err := d.Store.UpdateByID(ctx, "api_keys", id, sets); err != nil {
			httpx.OK(c, failureEnvelope(err.Error()))
			return
		}
		httpx.OK(c, httpx.NewOrderedMap().Set("success", true))
	})

	// DELETE /admin/api/api-keys/{id}
	g.DELETE("/api-keys/:id", func(c *gin.Context) {
		ctx := c.Request.Context()
		id, ok := pathID(c, "id")
		if !ok {
			httpx.OK(c, failureEnvelope("密钥不存在"))
			return
		}
		if _, err := d.Store.Exec(ctx, "DELETE FROM api_keys WHERE id = ?", id); err != nil {
			httpx.OK(c, failureEnvelope(err.Error()))
			return
		}
		httpx.OK(c, httpx.NewOrderedMap().Set("success", true))
	})

	// POST /admin/api/api-keys/{id}/toggle-share
	g.POST("/api-keys/:id/toggle-share", func(c *gin.Context) {
		ctx := c.Request.Context()
		id, ok := pathID(c, "id")
		if !ok {
			httpx.OK(c, failureEnvelope("密钥不存在"))
			return
		}
		var body struct {
			Shared bool `json:"shared"`
		}
		_ = c.ShouldBindJSON(&body)

		row, _ := d.Store.QueryOne(ctx, "SELECT * FROM api_keys WHERE id = ?", id)
		if row == nil {
			// Java: toggling a non-existent id still returns success:true (no-op).
			httpx.OK(c, httpx.NewOrderedMap().Set("success", true).Set("shared", false))
			return
		}

		shared := boolToInt(body.Shared)
		now := jtime.FormatApp(time.Now().UTC())

		shareCode := row.StrPtr("share_code")
		if body.Shared && (shareCode == nil || *shareCode == "") {
			code := generateShareCode()
			shareCode = &code
			d.Store.Exec(ctx, "UPDATE api_keys SET shared = ?, share_code = ?, updated_at = ? WHERE id = ?", shared, code, now, id)
		} else {
			d.Store.Exec(ctx, "UPDATE api_keys SET shared = ?, updated_at = ? WHERE id = ?", shared, now, id)
		}

		out := httpx.NewOrderedMap().Set("success", true).Set("shared", body.Shared)
		if body.Shared && shareCode != nil && *shareCode != "" {
			out.Set("shareCode", *shareCode)
		}
		httpx.OK(c, out)
	})

	// GET /admin/api/api-keys/:id/usage-stats
	// 密钥详情用量：按模型细分 + 今日/本周/本月 + 密钥级近 30 次平均（同渠道 usage-stats）
	g.GET("/api-keys/:id/usage-stats", func(c *gin.Context) {
		ctx := c.Request.Context()
		id, ok := pathID(c, "id")
		if !ok {
			notFound(c, "密钥不存在")
			return
		}
		row, err := d.Store.QueryOne(ctx, "SELECT id,key_name FROM api_keys WHERE id = ?", id)
		if err != nil {
			notFound(c, "密钥不存在")
			return
		}
		usage := getAPIKeyUsageStats(ctx, d.Store, id)
		httpx.OK(c, httpx.NewOrderedMap().
			Set("key", map[string]any{"id": id, "keyName": row.Str("key_name")}).
			Set("modelStats", usage.ModelStats).
			Set("keyAvgResponseTimeRecent30", usage.KeyAvgResponseTimeRecent30).
			Set("keyAvgOutputSpeedRecent30", usage.KeyAvgOutputSpeedRecent30))
	})

	// GET /admin/api/api-keys/usage-stats
	g.GET("/api-keys/usage-stats", func(c *gin.Context) {
		ctx := c.Request.Context()
		now := time.Now().UTC().In(jtime.Shanghai)
		todayStart := jtime.ShanghaiStart(now)
		weekStart := jtime.ShanghaiWeekStart(now)
		monthStart := jtime.ShanghaiMonthStart(now)

		out := make(map[string]any)
		periods := []struct {
			name  string
			since time.Time
		}{
			{"day", todayStart},
			{"week", weekStart},
			{"month", monthStart},
		}
		for _, p := range periods {
			rows, err := d.Store.Query(ctx,
				`SELECT gateway_api_key_id, COUNT(*) request_count,
					         COALESCE(SUM(prompt_tokens),0) prompt_tokens,
					         COALESCE(SUM(completion_tokens),0) completion_tokens,
					         COALESCE(SUM(total_tokens),0) total_tokens
					   FROM request_logs
					  WHERE phase='success' AND created_at >= ? AND gateway_api_key_id IS NOT NULL
					  GROUP BY gateway_api_key_id`, jtime.FormatDefault(p.since))
			if err != nil {
				continue
			}
			periodMap := make(map[string]any)
			for _, r := range rows {
				id := strconv.FormatInt(r.I64("gateway_api_key_id", 0), 10)
				periodMap[id] = map[string]any{
					"requestCount":     r.I64("request_count", 0),
					"promptTokens":     r.I64("prompt_tokens", 0),
					"completionTokens": r.I64("completion_tokens", 0),
					"totalTokens":      r.I64("total_tokens", 0),
				}
			}
			out[p.name] = periodMap
		}
		httpx.OK(c, out)
	})
}

// getAPIKeySummaryStats returns today's usage stats per gateway API key,
// grouped from request_logs (same shape as getChannelSummaryStats).
func getAPIKeySummaryStats(ctx context.Context, st *store.Store) map[int64]channelUsage {
	// Only count today's stats for the API key list.
	now := time.Now().UTC().In(jtime.Shanghai)
	todayStart := jtime.ShanghaiStart(now)

	rows, err := st.Query(ctx,
		`SELECT gateway_api_key_id,
		         COUNT(*) request_count,
		         COALESCE(SUM(prompt_tokens),0) prompt_tokens,
		         COALESCE(SUM(completion_tokens),0) completion_tokens,
		         COALESCE(SUM(total_tokens),0) total_tokens
		  FROM request_logs
		 WHERE phase='success' AND gateway_api_key_id IS NOT NULL AND created_at >= ?
		 GROUP BY gateway_api_key_id`, jtime.FormatDefault(todayStart))
	if err != nil {
		// 统计查询失败时列表仍返回密钥本身（用量列显示 0），仅记录日志便于区分
		// "没有用量" 与 "查询失败"，不改变接口行为。
		slog.Warn("查询 API 密钥用量汇总失败", "error", err)
		return nil
	}
	out := make(map[int64]channelUsage, len(rows))
	for _, r := range rows {
		out[r.I64("gateway_api_key_id", 0)] = channelUsage{
			// 查询已限定 phase='success'，尝试数与成功数相同
			TotalAttempts:    r.I64("request_count", 0),
			SuccessCount:     r.I64("request_count", 0),
			PromptTokens:     r.I64("prompt_tokens", 0),
			CompletionTokens: r.I64("completion_tokens", 0),
			TotalTokens:      r.I64("total_tokens", 0),
		}
	}
	return out
}

// apiKeyUsageResult bundles per-model stats with key-level averages.
type apiKeyUsageResult struct {
	ModelStats                 []modelUsageStat
	KeyAvgResponseTimeRecent30 int64
	KeyAvgOutputSpeedRecent30  float64
}

// getAPIKeyUsageStats mirrors getChannelModelUsageStats but scoped to a
// gateway API key: per-model all-time stats, recent-30 averages and
// today/week/month periods from request_logs.gateway_api_key_id.
func getAPIKeyUsageStats(ctx context.Context, st *store.Store, keyID int64) apiKeyUsageResult {
	now := time.Now().UTC().In(jtime.Shanghai)
	todayStart := jtime.ShanghaiStart(now)
	weekStart := jtime.ShanghaiWeekStart(now)
	monthStart := jtime.ShanghaiMonthStart(now)

	const where = `phase='success' AND gateway_api_key_id = ? AND channel_model_name IS NOT NULL AND channel_model_name != ''`

	// All-time stats per model
	allRows, err := st.Query(ctx,
		`SELECT channel_model_name,
		         COUNT(*) request_count,
		         COALESCE(SUM(prompt_tokens),0) pt,
		         COALESCE(SUM(completion_tokens),0) ct,
		         COALESCE(SUM(total_tokens),0) tt
		  FROM request_logs
		 WHERE `+where+`
		 GROUP BY channel_model_name ORDER BY request_count DESC`, keyID)
	if err != nil {
		slog.Warn("查询密钥按模型用量失败", "keyId", keyID, "error", err)
	}

	// Recent 30 response times and speeds (per model)
	recent30, err := st.Query(ctx,
		`SELECT channel_model_name, first_byte_ms, completion_tokens, response_time_ms
		  FROM (SELECT channel_model_name, first_byte_ms, completion_tokens, response_time_ms,
		               ROW_NUMBER() OVER (PARTITION BY channel_model_name ORDER BY created_at DESC, id DESC) rn
		          FROM request_logs
		         WHERE phase IN ('success','fail') AND gateway_api_key_id = ?
		           AND channel_model_name IS NOT NULL AND channel_model_name != '') WHERE rn <= 30`, keyID)
	if err != nil {
		slog.Warn("查询密钥近 30 次明细失败", "keyId", keyID, "error", err)
	}

	// Key-level: all models combined, recent 30
	keyRT, err := st.QueryOne(ctx,
		`SELECT AVG(first_byte_ms) avg_first_byte FROM (
		  SELECT first_byte_ms, ROW_NUMBER() OVER (ORDER BY created_at DESC, id DESC) rn
		    FROM request_logs
		   WHERE phase IN ('success','fail') AND gateway_api_key_id = ?
		     AND channel_model_name IS NOT NULL AND channel_model_name != ''
		     AND first_byte_ms IS NOT NULL AND first_byte_ms > 0) WHERE rn <= 30`, keyID)
	if err != nil && err != store.ErrNotFound {
		slog.Warn("查询密钥级近 30 次平均首字节失败", "keyId", keyID, "error", err)
	}
	var keyAvgRT int64
	if v := keyRT.F64Ptr("avg_first_byte"); v != nil {
		keyAvgRT = int64(*v + 0.5)
	}
	keySpd, err := st.QueryOne(ctx,
		`SELECT AVG(completion_tokens * 1000.0 / response_time_ms) avg_speed FROM (
		  SELECT completion_tokens, response_time_ms, ROW_NUMBER() OVER (ORDER BY created_at DESC, id DESC) rn
		    FROM request_logs
		   WHERE phase = 'success' AND gateway_api_key_id = ?
		     AND channel_model_name IS NOT NULL AND channel_model_name != ''
		     AND completion_tokens > 0 AND response_time_ms > 0) WHERE rn <= 30`, keyID)
	if err != nil && err != store.ErrNotFound {
		slog.Warn("查询密钥级近 30 次平均生成速度失败", "keyId", keyID, "error", err)
	}
	var keyAvgSpd float64
	if v := keySpd.F64Ptr("avg_speed"); v != nil {
		keyAvgSpd = float64(int64(*v*10+0.5)) / 10.0
	}

	rtAvg := make(map[string]int64)
	spdAvg := make(map[string]float64)
	rtSums := make(map[string]struct{ cnt, sum int64 })
	spdSums := make(map[string]struct {
		cnt int
		sum float64
	})
	for _, r := range recent30 {
		name := r.Str("channel_model_name")
		if fbm := r.I64Ptr("first_byte_ms"); fbm != nil && *fbm > 0 {
			s := rtSums[name]
			s.cnt++
			s.sum += *fbm
			rtSums[name] = s
		}
		if ct := r.I64Ptr("completion_tokens"); ct != nil && *ct > 0 {
			if rtm := r.I64Ptr("response_time_ms"); rtm != nil && *rtm > 0 {
				s := spdSums[name]
				s.cnt++
				s.sum += float64(*ct) * 1000.0 / float64(*rtm)
				spdSums[name] = s
			}
		}
	}
	for name, s := range rtSums {
		if s.cnt > 0 {
			rtAvg[name] = int64(float64(s.sum)/float64(s.cnt) + 0.5)
		}
	}
	for name, s := range spdSums {
		if s.cnt > 0 {
			spdAvg[name] = float64(int64(s.sum/float64(s.cnt)*10+0.5)) / 10.0
		}
	}

	// Period queries
	periodStats := getAPIKeyPeriodStats(ctx, st, keyID, todayStart, weekStart, monthStart)

	out := make([]modelUsageStat, 0, len(allRows))
	for _, r := range allRows {
		name := r.Str("channel_model_name")
		s := modelUsageStat{
			ModelName:               name,
			RequestCount:            r.I64("request_count", 0),
			PromptTokens:            r.I64("pt", 0),
			CompletionTokens:        r.I64("ct", 0),
			TotalTokens:             r.I64("tt", 0),
			AvgResponseTimeRecent30: rtAvg[name],
			AvgOutputSpeedRecent30:  spdAvg[name],
		}
		if p, ok := periodStats[name]; ok {
			s.Today = p.Today
			s.Week = p.Week
			s.Month = p.Month
		}
		out = append(out, s)
	}
	return apiKeyUsageResult{
		ModelStats:                 out,
		KeyAvgResponseTimeRecent30: keyAvgRT,
		KeyAvgOutputSpeedRecent30:  keyAvgSpd,
	}
}

// getAPIKeyPeriodStats returns today/week/month usage per model for one key.
//
// 边界口径：since 是上海时区窗口起点（如今日 00:00 +08:00 = 前一日 16:00Z），
// FormatDefault 生成 T 分隔的 UTC 瞬时下界，对当前唯一写入路径（logsvc，恒为
// T 格式）是精确比较。这里不能像 usage-chart 那样改用 DATE-ONLY 裸日期下界：
// 图表窗口锚定 UTC 自然日零点（日期前缀恰等于瞬时值），而本窗口锚定上海零点
// （16:00Z），截断成日期会把边界日 00:00Z~16:00Z（即上海昨日 08:00~24:00）的
// 行错误计入。已知限制：历史 space 格式行（SQL DEFAULT 写入，当前写入路径不再
// 产生）落在边界日当天时会被 T 下界排除（' ' < 'T'）。
func getAPIKeyPeriodStats(ctx context.Context, st *store.Store, keyID int64, todayStart, weekStart, monthStart time.Time) map[string]perModelPeriods {
	result := make(map[string]perModelPeriods)
	for label, since := range map[string]time.Time{"today": todayStart, "week": weekStart, "month": monthStart} {
		rows, err := st.Query(ctx,
			`SELECT channel_model_name,
			         COUNT(*) rc,
			         COALESCE(SUM(prompt_tokens),0) pt,
			         COALESCE(SUM(completion_tokens),0) ct,
			         COALESCE(SUM(total_tokens),0) tt
			  FROM request_logs
			 WHERE phase='success' AND gateway_api_key_id = ? AND channel_model_name IS NOT NULL
			   AND created_at >= ?
			 GROUP BY channel_model_name`, keyID, jtime.FormatDefault(since))
		if err != nil {
			// 单周期查询失败只影响该周期列（保持为空），其余照常返回。
			slog.Warn("查询密钥周期用量失败", "keyId", keyID, "period", label, "error", err)
			continue
		}
		for _, r := range rows {
			name := r.Str("channel_model_name")
			p := result[name]
			period := usagePeriod{
				RequestCount:     r.I64("rc", 0),
				PromptTokens:     r.I64("pt", 0),
				CompletionTokens: r.I64("ct", 0),
				TotalTokens:      r.I64("tt", 0),
			}
			switch label {
			case "today":
				p.Today = period
			case "week":
				p.Week = period
			case "month":
				p.Month = period
			}
			result[name] = p
		}
	}
	return result
}

func generateAPIKeyValue() string {
	b := make([]byte, 32)
	randRead(b)
	return "sk-myai-" + encodeBase64URL(b)
}

func generateShareCode() string {
	b := make([]byte, 12)
	randRead(b)
	return encodeBase64URL(b)
}

func boolToInt(v bool) int {
	if v {
		return 1
	}
	return 0
}

func toIntAny(v any) int {
	switch x := v.(type) {
	case float64:
		return int(x)
	case int:
		return x
	case bool:
		return boolToInt(x)
	default:
		return 0
	}
}
