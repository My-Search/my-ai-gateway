package server

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"regexp"
	"sort"
	"strconv"
	"strings"
	"time"

	"github.com/gin-gonic/gin"
	"github.com/my-search/my-ai-gateway/internal/channelload"
	"github.com/my-search/my-ai-gateway/internal/httpx"
	"github.com/my-search/my-ai-gateway/internal/jtime"
	"github.com/my-search/my-ai-gateway/internal/models"
	"github.com/my-search/my-ai-gateway/internal/store"
)

func registerChannelRoutes(g *gin.RouterGroup, d Deps) {
	// GET /admin/api/channels
	g.GET("/channels", func(c *gin.Context) {
		ctx := c.Request.Context()
		rows, err := d.Store.Query(ctx, "SELECT * FROM channels ORDER BY created_at ASC")
		if err != nil {
			httpx.OK(c, failureEnvelope(err.Error()))
			return
		}

		// Gather model counts
		chIDs := make([]string, 0, len(rows))
		for _, r := range rows {
			chIDs = append(chIDs, strconv.FormatInt(r.I64("id", 0), 10))
		}
		modelCounts := make(map[int64]int)
		if len(chIDs) > 0 {
			cmRows, _ := d.Store.Query(ctx,
				"SELECT channel_id, COUNT(*) cnt FROM channel_models WHERE channel_id IN ("+joinInts(chIDs)+") AND enabled=1 GROUP BY channel_id")
			for _, r := range cmRows {
				modelCounts[r.I64("channel_id", 0)] = r.Int("cnt", 0)
			}
		}

		type chItem struct {
			ID                  int64         `json:"id"`
			Name                string        `json:"name"`
			ChannelType         string        `json:"channelType"`
			BaseURL             string        `json:"baseUrl"`
			Enabled             *int          `json:"enabled"`
			SortOrder           *int          `json:"sortOrder"`
			CreatedAt           jtime.APITime `json:"createdAt"`
			UpdatedAt           jtime.APITime `json:"updatedAt"`
			ModelRefreshEnabled *int          `json:"modelRefreshEnabled"`
			CustomHeaders       *string       `json:"customHeaders"`
			ModelCount          int           `json:"modelCount"`
		}

		out := make([]chItem, 0, len(rows))
		for _, r := range rows {
			ch := store.RowToChannel(r)
			id := ch.ID
			item := chItem{
				ID:                  id,
				Name:                ch.Name,
				ChannelType:         ch.ChannelType,
				BaseURL:             ch.BaseURL,
				Enabled:             ch.Enabled,
				SortOrder:           ch.SortOrder,
				CreatedAt:           ch.CreatedAt,
				UpdatedAt:           ch.UpdatedAt,
				ModelRefreshEnabled: ch.ModelRefreshEnabled,
				CustomHeaders:       ch.CustomHeaders,
				ModelCount:          modelCounts[id],
			}
			out = append(out, item)
		}
		if out == nil {
			out = make([]chItem, 0)
		}
		httpx.JSON(c, http.StatusOK, out)
	})

	// GET /admin/api/channels/{id}
	g.GET("/channels/:id", func(c *gin.Context) {
		ctx := c.Request.Context()
		id, ok := pathID(c, "id")
		if !ok {
			notFound(c, "渠道不存在")
			return
		}
		chRow, err := d.Store.QueryOne(ctx, "SELECT * FROM channels WHERE id = ?", id)
		if err != nil {
			notFound(c, "渠道不存在")
			return
		}
		channel := store.RowToChannel(chRow)

		cmRows, _ := d.Store.Query(ctx, "SELECT * FROM channel_models WHERE channel_id = ?", id)
		models := make([]models.ChannelModel, 0, len(cmRows))
		for _, r := range cmRows {
			models = append(models, store.RowToChannelModel(r))
		}

		akRows, _ := d.Store.Query(ctx, "SELECT * FROM channel_api_keys WHERE channel_id = ? ORDER BY sort_order", id)
		type akItem struct {
			ID        int64         `json:"id"`
			ChannelID int64         `json:"channelId"`
			KeyName   string        `json:"keyName"`
			APIKey    string        `json:"apiKey"`
			Enabled   *int          `json:"enabled"`
			SortOrder *int          `json:"sortOrder"`
			CreatedAt jtime.APITime `json:"createdAt"`
			UpdatedAt jtime.APITime `json:"updatedAt"`
		}
		apiKeyItems := make([]akItem, 0, len(akRows))
		for _, r := range akRows {
			apiKeyItems = append(apiKeyItems, akItem{
				ID:        r.I64("id", 0),
				ChannelID: r.I64("channel_id", 0),
				KeyName:   r.Str("key_name"),
				APIKey:    r.Str("api_key"),
				Enabled:   r.IntPtr("enabled"),
				SortOrder: r.IntPtr("sort_order"),
				CreatedAt: r.TimePtr("created_at"),
				UpdatedAt: r.TimePtr("updated_at"),
			})
		}

		httpx.OK(c, httpx.NewOrderedMap().
			Set("channel", channel).
			Set("channelModels", models).
			Set("apiKeys", apiKeyItems))
	})

	// POST /admin/api/channels
	g.POST("/channels", func(c *gin.Context) {
		ctx := c.Request.Context()
		var body map[string]any
		if err := c.ShouldBindJSON(&body); err != nil {
			httpx.OK(c, failureEnvelope("请求参数错误"))
			return
		}

		name, _ := body["name"].(string)
		chType, _ := body["channelType"].(string)
		baseURL, _ := body["baseUrl"].(string)
		customHeaders, _ := body["customHeaders"].(string)

		enabled := 1
		if v, ok := body["enabled"]; ok {
			enabled = toIntAny(v)
		}
		mre := 1
		if v, ok := body["modelRefreshEnabled"]; ok {
			mre = toIntAny(v)
		} else if v, ok := body["model_refresh_enabled"]; ok {
			mre = toIntAny(v)
		}

		now := jtime.FormatApp(time.Now().UTC())
		baseURL = strings.TrimSpace(baseURL)

		chID, err := d.Store.Insert(ctx,
			"INSERT INTO channels (name, channel_type, api_key, base_url, enabled, sort_order, model_refresh_enabled, custom_headers, created_at, updated_at) VALUES (?, ?, '', ?, ?, 0, ?, ?, ?, ?)",
			name, chType, baseURL, enabled, mre, customHeaders, now, now)
		if err != nil {
			httpx.OK(c, failureEnvelope(err.Error()))
			return
		}

		// manualModels
		if mm, ok := body["manualModels"].(string); ok && mm != "[]" && mm != "" {
			addManualModels(ctx, d.Store, chID, mm)
		} else if mre == 1 {
			_ = loadModelsForChannel(ctx, d.Store, chID, chType, baseURL, customHeaders)
		}

		// apiKeysJson
		if akj, ok := body["apiKeysJson"].(string); ok && akj != "[]" && akj != "" {
			syncChannelAPIKeys(ctx, d.Store, chID, akj)
		}

		httpx.OK(c, httpx.NewOrderedMap().Set("success", true).Set("id", chID))
	})

	// PUT /admin/api/channels/{id}
	g.PUT("/channels/:id", func(c *gin.Context) {
		ctx := c.Request.Context()
		id, ok := pathID(c, "id")
		if !ok {
			httpx.OK(c, failureEnvelope("渠道不存在"))
			return
		}

		existing, err := d.Store.QueryOne(ctx, "SELECT * FROM channels WHERE id = ?", id)
		if err != nil {
			httpx.OK(c, failureEnvelope("渠道不存在"))
			return
		}

		var body map[string]any
		if err := c.ShouldBindJSON(&body); err != nil {
			httpx.OK(c, failureEnvelope("请求参数错误"))
			return
		}

		sets := make(map[string]any)
		applyChanBody(body, sets)
		now := jtime.FormatApp(time.Now().UTC())
		sets["updated_at"] = now

		if len(sets) > 0 {
			delete(sets, "id")
			q, args := store.BuildUpdate("channels", sets, "id = ?", id)
			d.Store.Exec(ctx, q, args...)
		}

		oldEnabled := existing.Int("enabled", 0)
		newEnabled := oldEnabled
		if v, ok := body["enabled"]; ok {
			newEnabled = toIntAny(v)
		}
		becomingEnabled := oldEnabled == 0 && newEnabled == 1

		var refreshCount int
		if mm, ok := body["manualModels"]; ok {
			mmStr, _ := mm.(string)
			updateWithModels(ctx, d.Store, id, mmStr, body)
		} else if becomingEnabled {
			beforeNames := getChannelModelNameSet(ctx, d.Store, id)
			_ = loadModelsForChannelID(ctx, d.Store, id)
			afterNames := getChannelModelNameSet(ctx, d.Store, id)
			refreshCount = len(symDiff(beforeNames, afterNames))
		}

		if akj, ok := body["apiKeysJson"].(string); ok {
			syncChannelAPIKeys(ctx, d.Store, id, akj)
		}

		out := httpx.NewOrderedMap().Set("success", true)
		if refreshCount > 0 {
			out.Set("refreshCount", refreshCount)
		}
		httpx.OK(c, out)
	})

	// DELETE /admin/api/channels/{id}
	g.DELETE("/channels/:id", func(c *gin.Context) {
		ctx := c.Request.Context()
		id, ok := pathID(c, "id")
		if !ok {
			httpx.OK(c, failureEnvelope("渠道不存在"))
			return
		}
		deleteChannel(ctx, d.Store, id)
		httpx.OK(c, httpx.NewOrderedMap().Set("success", true))
	})

	// GET /admin/api/channels/{id}/models
	g.GET("/channels/:id/models", func(c *gin.Context) {
		ctx := c.Request.Context()
		id, ok := pathID(c, "id")
		if !ok {
			notFound(c, "渠道不存在")
			return
		}
		chRow, err := d.Store.QueryOne(ctx, "SELECT id,name,channel_type FROM channels WHERE id = ?", id)
		if err != nil {
			notFound(c, "渠道不存在")
			return
		}
		cmRows, _ := d.Store.Query(ctx, "SELECT * FROM channel_models WHERE channel_id = ? AND enabled = 1", id)
		// 关联状态：被 model_channel_rels 中至少一条记录引用即为已关联（与入口模型建立过关联关系）
		linkedSet := make(map[int64]bool, len(cmRows))
		if len(cmRows) > 0 {
			cmIDs := make([]string, 0, len(cmRows))
			for _, r := range cmRows {
				cmIDs = append(cmIDs, strconv.FormatInt(r.I64("id", 0), 10))
			}
			relRows, _ := d.Store.Query(ctx,
				"SELECT DISTINCT channel_model_id FROM model_channel_rels WHERE channel_model_id IN ("+joinInts(cmIDs)+")")
			for _, rr := range relRows {
				linkedSet[rr.I64("channel_model_id", 0)] = true
			}
		}
		outModels := make([]models.ChannelModel, 0, len(cmRows))
		for _, r := range cmRows {
			m := store.RowToChannelModel(r)
			linked := linkedSet[m.ID]
			m.Linked = &linked
			outModels = append(outModels, m)
		}
		httpx.OK(c, httpx.NewOrderedMap().
			Set("channel", map[string]any{"id": chRow.I64("id", 0), "name": chRow.Str("name"), "channelType": chRow.Str("channel_type")}).
			Set("models", outModels))
	})

	// GET /admin/api/channels/{id}/usage-stats
	g.GET("/channels/:id/usage-stats", func(c *gin.Context) {
		ctx := c.Request.Context()
		id, ok := pathID(c, "id")
		if !ok {
			notFound(c, "渠道不存在")
			return
		}
		chRow, err := d.Store.QueryOne(ctx, "SELECT id,name,channel_type FROM channels WHERE id = ?", id)
		if err != nil {
			notFound(c, "渠道不存在")
			return
		}
		chName := chRow.Str("name")
		usage := getChannelModelUsageStats(ctx, d.Store, chName)
		httpx.OK(c, httpx.NewOrderedMap().
			Set("channel", map[string]any{"id": chRow.I64("id", 0), "name": chName, "channelType": chRow.Str("channel_type")}).
			Set("modelStats", usage.ModelStats).
			Set("channelAvgResponseTimeRecent30", usage.ChannelAvgResponseTimeRecent30).
			Set("channelAvgOutputSpeedRecent30", usage.ChannelAvgOutputSpeedRecent30))
	})

	// POST /admin/api/channels/{id}/reload-models
	g.POST("/channels/:id/reload-models", func(c *gin.Context) {
		ctx := c.Request.Context()
		id, ok := pathID(c, "id")
		if !ok {
			httpx.OK(c, failureEnvelope("渠道不存在"))
			return
		}
		before := getChannelModelNameSet(ctx, d.Store, id)
		models, err := reloadChannelModels(ctx, d.Store, id)
		if err != nil {
			httpx.OK(c, failureEnvelope(err.Error()))
			return
		}
		after := make(map[string]bool, len(models))
		for _, m := range models {
			after[m.ModelName] = true
		}
		added, removed := diffModelNames(before, after)
		httpx.OK(c, httpx.NewOrderedMap().
			Set("success", true).
			Set("data", models).
			Set("count", len(models)).
			Set("changed", len(added) > 0 || len(removed) > 0).
			Set("addedCount", len(added)).
			Set("removedCount", len(removed)).
			Set("added", added).
			Set("removed", removed))
	})

	// GET /admin/api/channels/fetch-models?baseUrl=...&apiKey=...&channelType=...
	g.GET("/channels/fetch-models", func(c *gin.Context) {
		baseURL := strings.TrimSpace(c.Query("baseUrl"))
		apiKey := strings.TrimSpace(c.Query("apiKey"))
		chType := strings.TrimSpace(c.Query("channelType"))
		if baseURL == "" && apiKey == "" && chType == "" {
			httpx.OK(c, failureEnvelope("缺少参数"))
			return
		}
		models, err := fetchModelsPreview(baseURL, apiKey, chType)
		if err != nil {
			httpx.OK(c, failureEnvelope(err.Error()))
			return
		}
		httpx.OK(c, httpx.NewOrderedMap().Set("success", true).Set("data", models).Set("count", len(models)))
	})

	// POST /admin/api/channels/:id/models
	g.POST("/channels/:id/models", func(c *gin.Context) {
		ctx := c.Request.Context()
		chID, ok := pathID(c, "id")
		if !ok {
			httpx.OK(c, failureEnvelope("渠道不存在"))
			return
		}
		var body struct {
			ModelName   string `json:"modelName"`
			DisplayName string `json:"displayName"`
		}
		_ = c.ShouldBindJSON(&body)
		if body.DisplayName == "" {
			body.DisplayName = body.ModelName
		}
		now := jtime.FormatApp(time.Now().UTC())
		id, err := d.Store.Insert(ctx,
			"INSERT INTO channel_models (channel_id, model_name, display_name, enabled, source, input, created_at) VALUES (?, ?, ?, 1, 'manual', 'text', ?)",
			chID, body.ModelName, body.DisplayName, now)
		if err != nil {
			httpx.OK(c, failureEnvelope(err.Error()))
			return
		}
		row, _ := d.Store.QueryOne(ctx, "SELECT * FROM channel_models WHERE id = ?", id)
		httpx.OK(c, httpx.NewOrderedMap().Set("success", true).Set("data", store.RowToChannelModel(row)))
	})

	// POST /admin/api/channels/{id}/quick-test
	g.POST("/channels/:id/quick-test", func(c *gin.Context) {
		ctx := c.Request.Context()
		chID, ok := pathID(c, "id")
		if !ok {
			httpx.OK(c, failureEnvelope("渠道不存在"))
			return
		}
		var body struct {
			Message   string `json:"message"`
			APIKeyID  *int64 `json:"apiKeyId"`
			ModelName string `json:"modelName"`
		}
		_ = c.ShouldBindJSON(&body)
		if body.Message == "" {
			body.Message = "Hello"
		}

		chRow, err := d.Store.QueryOne(ctx, "SELECT * FROM channels WHERE id = ?", chID)
		if err != nil {
			httpx.OK(c, failureEnvelope("渠道不存在"))
			return
		}
		channel := store.RowToChannel(chRow)

		// Resolve API key
		var apiKeyRow store.Row
		if body.APIKeyID != nil {
			akRow, err2 := d.Store.QueryOne(ctx, "SELECT * FROM channel_api_keys WHERE id = ? AND channel_id = ?", *body.APIKeyID, chID)
			if err2 != nil {
				httpx.OK(c, failureEnvelope("指定的 API Key 不存在或不属于该渠道"))
				return
			}
			apiKeyRow = akRow
		} else {
			keys, _ := d.Store.Query(ctx, "SELECT * FROM channel_api_keys WHERE channel_id = ? ORDER BY sort_order", chID)
			for _, k := range keys {
				if k.Int("enabled", 0) == 1 {
					apiKeyRow = k
					break
				}
			}
			if apiKeyRow == nil && len(keys) > 0 {
				// 测试不受禁用状态影响，全部禁用时退化用第一个 Key（Java quickTest）
				apiKeyRow = keys[0]
			}
			if apiKeyRow == nil {
				httpx.OK(c, failureEnvelope("渠道没有可用的 API Key，请先添加 API Key"))
				return
			}
		}

		// Get enabled models
		models, _ := d.Store.Query(ctx, "SELECT * FROM channel_models WHERE channel_id = ? AND enabled = 1", chID)
		if len(models) == 0 {
			httpx.OK(c, failureEnvelope("渠道没有可用模型，请先加载模型"))
			return
		}
		var testCM store.Row
		if body.ModelName != "" {
			for _, m := range models {
				if m.Str("model_name") == body.ModelName {
					testCM = m
					break
				}
			}
		}
		if testCM == nil {
			// Java 回退到第一个模型，不报"指定的模型不存在"
			testCM = models[0]
		}

		channelModel := store.RowToChannelModel(testCM)
		apiKey := apiKeyRow.Str("api_key")
		provider := channel.ChannelType
		if provider == "" {
			provider = "openai"
		}

		testStart := time.Now()
		content, ttfb, usageOut, err := quickTestProvider(channel, channelModel, apiKey, provider, body.Message)
		if err != nil {
			httpx.OK(c, failureEnvelope(err.Error()))
			return
		}
		totalTime := time.Since(testStart).Milliseconds()

		outputTokens := usageOut
		if outputTokens == 0 {
			cjk, other := countTokens(content)
			outputTokens = int64(cjk + other/4)
		}

		outputSpeed := 0.0
		if totalTime > 0 {
			outputSpeed = float64(outputTokens) * 1000.0 / float64(totalTime)
		}

		httpx.OK(c, httpx.NewOrderedMap().
			Set("success", true).
			Set("response", content).
			Set("ttfb", ttfb).
			Set("model", channelModel.ModelName).
			Set("outputSpeed", outputSpeed))
	})

	// DELETE /admin/api/channels/{id}/models/{modelId}
	g.DELETE("/channels/:id/models/:modelId", func(c *gin.Context) {
		ctx := c.Request.Context()
		modelID, ok := pathID(c, "modelId")
		if !ok {
			httpx.OK(c, failureEnvelope("参数错误"))
			return
		}
		d.Store.Exec(ctx, "DELETE FROM model_channel_rels WHERE channel_model_id = ?", modelID)
		d.Store.Exec(ctx, "DELETE FROM channel_models WHERE id = ?", modelID)
		httpx.OK(c, httpx.NewOrderedMap().Set("success", true))
	})

	// DELETE /admin/api/channels/{channelId}/models
	g.DELETE("/channels/:id/models", func(c *gin.Context) {
		ctx := c.Request.Context()
		chID, ok := pathID(c, "id")
		if !ok {
			httpx.OK(c, failureEnvelope("参数错误"))
			return
		}
		ids, _ := d.Store.Query(ctx, "SELECT id FROM channel_models WHERE channel_id = ?", chID)
		var idList []string
		for _, r := range ids {
			idList = append(idList, strconv.FormatInt(r.I64("id", 0), 10))
		}
		if len(idList) > 0 {
			ins := joinInts(idList)
			d.Store.Exec(ctx, "DELETE FROM model_channel_rels WHERE channel_model_id IN ("+ins+")")
			d.Store.Exec(ctx, "DELETE FROM channel_models WHERE channel_id = ?", chID)
		}
		httpx.OK(c, httpx.NewOrderedMap().Set("success", true).Set("count", len(ids)))
	})
}

// ---------------------------------------------------------------------------
// Helper types and functions
// ---------------------------------------------------------------------------

type modelUsageStat struct {
	ModelName               string      `json:"modelName"`
	RequestCount            int64       `json:"requestCount"`
	PromptTokens            int64       `json:"promptTokens"`
	CompletionTokens        int64       `json:"completionTokens"`
	TotalTokens             int64       `json:"totalTokens"`
	AvgResponseTimeRecent30 int64       `json:"avgResponseTimeRecent30"`
	AvgOutputSpeedRecent30  float64     `json:"avgOutputSpeedRecent30"`
	Today                   usagePeriod `json:"today"`
	Week                    usagePeriod `json:"week"`
	Month                   usagePeriod `json:"month"`
}

type usagePeriod struct {
	RequestCount     int64 `json:"requestCount"`
	PromptTokens     int64 `json:"promptTokens"`
	CompletionTokens int64 `json:"completionTokens"`
	TotalTokens      int64 `json:"totalTokens"`
}

// channelUsageResult bundles the model rows with the channel-level averages.
type channelUsageResult struct {
	ModelStats                     []modelUsageStat
	ChannelAvgResponseTimeRecent30 int64
	ChannelAvgOutputSpeedRecent30  float64
}

func getChannelModelUsageStats(ctx context.Context, st *store.Store, chName string) channelUsageResult {
	now := time.Now().UTC().In(jtime.Shanghai)
	todayStart := jtime.ShanghaiStart(now)
	weekStart := jtime.ShanghaiWeekStart(now)
	monthStart := jtime.ShanghaiMonthStart(now)

	// All-time stats
	allRows, _ := st.Query(ctx,
		`SELECT channel_model_name,
		         COUNT(*) request_count,
		         COALESCE(SUM(prompt_tokens),0) pt,
		         COALESCE(SUM(completion_tokens),0) ct,
		         COALESCE(SUM(total_tokens),0) tt
		  FROM request_logs
		 WHERE phase='success' AND channel_name = ? AND channel_model_name IS NOT NULL AND channel_model_name != ''
		 GROUP BY channel_model_name ORDER BY request_count DESC`, chName)

	// Recent 30 response times and speeds (per model and channel-wide), matching
	// RequestLogMapper.selectChannelModelRecent30FirstByte / selectChannelRecent30FirstByte
	// and selectChannelModelRecent30Speed / selectChannelRecent30Speed.
	recent30, _ := st.Query(ctx,
		`SELECT channel_model_name, first_byte_ms, completion_tokens, response_time_ms
		  FROM (SELECT channel_model_name, first_byte_ms, completion_tokens, response_time_ms,
		               ROW_NUMBER() OVER (PARTITION BY channel_model_name ORDER BY created_at DESC, id DESC) rn
		          FROM request_logs
		         WHERE channel_name = ? AND phase IN ('success','fail')
		           AND channel_model_name IS NOT NULL AND channel_model_name != '') WHERE rn <= 30`, chName)

	// 渠道级：所有模型合并取最近 30 条
	channelRT, _ := st.QueryOne(ctx,
		`SELECT AVG(first_byte_ms) avg_first_byte FROM (
		   SELECT first_byte_ms, ROW_NUMBER() OVER (ORDER BY created_at DESC, id DESC) rn
		     FROM request_logs
		    WHERE phase IN ('success','fail') AND channel_name = ?
		      AND channel_model_name IS NOT NULL AND channel_model_name != ''
		      AND first_byte_ms IS NOT NULL AND first_byte_ms > 0) WHERE rn <= 30`, chName)
	var channelAvgRT int64
	if v := channelRT.F64Ptr("avg_first_byte"); v != nil {
		channelAvgRT = int64(*v + 0.5)
	}
	channelSpd, _ := st.QueryOne(ctx,
		`SELECT AVG(completion_tokens * 1000.0 / response_time_ms) avg_speed FROM (
		   SELECT completion_tokens, response_time_ms, ROW_NUMBER() OVER (ORDER BY created_at DESC, id DESC) rn
		     FROM request_logs
		    WHERE phase = 'success' AND channel_name = ?
		      AND channel_model_name IS NOT NULL AND channel_model_name != ''
		      AND completion_tokens > 0 AND response_time_ms > 0) WHERE rn <= 30`, chName)
	var channelAvgSpd float64
	if v := channelSpd.F64Ptr("avg_speed"); v != nil {
		channelAvgSpd = float64(int64(*v*10+0.5)) / 10.0
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
	periodStats := getPeriodStats(ctx, st, chName, todayStart, weekStart, monthStart)

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
	return channelUsageResult{
		ModelStats:                     out,
		ChannelAvgResponseTimeRecent30: channelAvgRT,
		ChannelAvgOutputSpeedRecent30:  channelAvgSpd,
	}
}

type perModelPeriods struct {
	Today, Week, Month usagePeriod
}

func getPeriodStats(ctx context.Context, st *store.Store, chName string, todayStart, weekStart, monthStart time.Time) map[string]perModelPeriods {
	result := make(map[string]perModelPeriods)
	for label, since := range map[string]time.Time{"today": todayStart, "week": weekStart, "month": monthStart} {
		rows, _ := st.Query(ctx,
			`SELECT channel_model_name,
			         COUNT(*) rc,
			         COALESCE(SUM(prompt_tokens),0) pt,
			         COALESCE(SUM(completion_tokens),0) ct,
			         COALESCE(SUM(total_tokens),0) tt
			  FROM request_logs
			 WHERE phase='success' AND channel_name = ? AND channel_model_name IS NOT NULL
			   AND created_at >= ?
			 GROUP BY channel_model_name`, chName, jtime.FormatDefault(since))
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

func deleteChannel(ctx context.Context, st *store.Store, id int64) {
	// Cascade delete - each step is swallowed on error, matching Java behaviour
	cmRows, _ := st.Query(ctx, "SELECT id FROM channel_models WHERE channel_id = ?", id)
	for _, r := range cmRows {
		mid := r.I64("id", 0)
		st.Exec(ctx, "DELETE FROM model_channel_rels WHERE channel_model_id = ?", mid)
	}
	st.Exec(ctx, "DELETE FROM channel_models WHERE channel_id = ?", id)
	st.Exec(ctx, "DELETE FROM channel_api_keys WHERE channel_id = ?", id)
	st.Exec(ctx, "DELETE FROM circuit_breaker_states WHERE channel_id = ?", id)
	st.Exec(ctx, "DELETE FROM channels WHERE id = ?", id)
}

func applyChanBody(body map[string]any, sets map[string]any) {
	for _, k := range []string{"name", "channelType", "baseUrl"} {
		if v, exists := body[k]; exists {
			sets[camelToSnake(k)] = v
		}
	}
	if v, exists := body["enabled"]; exists {
		sets["enabled"] = toIntAny(v)
	}
	if v, exists := body["modelRefreshEnabled"]; exists {
		sets["model_refresh_enabled"] = toIntAny(v)
	} else if v, exists := body["model_refresh_enabled"]; exists {
		sets["model_refresh_enabled"] = toIntAny(v)
	}
	if _, exists := body["customHeaders"]; exists {
		sets["custom_headers"] = body["customHeaders"]
	}
}

func camelToSnake(s string) string {
	var b strings.Builder
	for i, ch := range s {
		if ch >= 'A' && ch <= 'Z' {
			if i > 0 {
				b.WriteByte('_')
			}
			b.WriteByte(byte(ch + 32))
		} else {
			b.WriteRune(ch)
		}
	}
	return b.String()
}

func addManualModels(ctx context.Context, st *store.Store, chID int64, jsonStr string) {
	var models []struct {
		ModelName   string `json:"modelName"`
		DisplayName string `json:"displayName"`
	}
	if err := json.Unmarshal([]byte(jsonStr), &models); err != nil {
		return
	}
	now := jtime.FormatApp(time.Now().UTC())
	for _, m := range models {
		dn := m.DisplayName
		if dn == "" {
			dn = m.ModelName
		}
		input := channelload.ComputeInput(ctx, st, m.ModelName)
		st.Insert(ctx, "INSERT INTO channel_models (channel_id, model_name, display_name, enabled, source, input, created_at) VALUES (?, ?, ?, 1, 'manual', ?, ?)",
			chID, m.ModelName, dn, input, now)
	}
}

// loadModelsForChannel delegates to channelload.LoadModels so that a failed
// provider fetch preserves the channel's existing models (Java
// ChannelModelLoader.loadModels), falling back to the preset list only for a
// channel that has no models yet.
func loadModelsForChannel(ctx context.Context, st *store.Store, chID int64, chType, baseURL, customHeaders string) error {
	channelload.LoadModels(ctx, st, chID, chType, baseURL, customHeaders, "")
	return nil
}

func loadModelsForChannelID(ctx context.Context, st *store.Store, chID int64) error {
	ch, err := st.QueryOne(ctx, "SELECT * FROM channels WHERE id = ?", chID)
	if err != nil {
		return err
	}
	return loadModelsForChannel(ctx, st, chID, ch.Str("channel_type"), ch.Str("base_url"), ch.Str("custom_headers"))
}

func reloadChannelModels(ctx context.Context, st *store.Store, chID int64) ([]models.ChannelModel, error) {
	ch, err := st.QueryOne(ctx, "SELECT * FROM channels WHERE id = ?", chID)
	if err != nil {
		return nil, fmt.Errorf("渠道不存在")
	}
	channel := store.RowToChannel(ch)
	if err := channelload.LoadModelsByID(ctx, st, chID); err != nil {
		return nil, err
	}
	_ = channel
	return listChannelModels(ctx, st, chID), nil
}

func listChannelModels(ctx context.Context, st *store.Store, chID int64) []models.ChannelModel {
	rows, _ := st.Query(ctx, "SELECT * FROM channel_models WHERE channel_id = ?", chID)
	out := make([]models.ChannelModel, 0, len(rows))
	for _, r := range rows {
		out = append(out, store.RowToChannelModel(r))
	}
	return out
}

// fetchModelsPreview lists provider models for the admin fetch-models endpoint.
func fetchModelsPreview(baseURL, apiKey, chType string) ([]models.ChannelModel, error) {
	pairs, err := channelload.FetchModels(baseURL, apiKey, chType, "")
	if err != nil {
		return nil, err
	}
	out := make([]models.ChannelModel, 0, len(pairs))
	for _, p := range pairs {
		out = append(out, models.ChannelModel{
			ModelName:   p.Name,
			DisplayName: models.Str(p.Display),
			Enabled:     models.Int(1),
			Input:       models.Str("text"),
		})
	}
	return out, nil
}

func syncChannelAPIKeys(ctx context.Context, st *store.Store, chID int64, jsonStr string) {
	var submitted []struct {
		KeyName   string `json:"keyName"`
		APIKey    string `json:"apiKey"`
		Enabled   *int   `json:"enabled"`
		SortOrder *int   `json:"sortOrder"`
	}
	if err := json.Unmarshal([]byte(jsonStr), &submitted); err != nil {
		return
	}

	existing, _ := st.Query(ctx, "SELECT * FROM channel_api_keys WHERE channel_id = ? ORDER BY sort_order", chID)

	existingByName := make(map[string]store.Row)
	for _, r := range existing {
		existingByName[r.Str("key_name")] = r
	}

	submittedNames := make(map[string]bool)
	firstEmpty := true
	for _, s := range submitted {
		kn := strings.TrimSpace(s.KeyName)
		ak := strings.TrimSpace(s.APIKey)
		if ak == "" {
			if firstEmpty {
				firstEmpty = false
			} else {
				continue
			}
		}
		submittedNames[kn] = true

		if existing, exists := existingByName[kn]; exists {
			sets := make(map[string]any)
			sets["api_key"] = ak
			if s.Enabled != nil {
				sets["enabled"] = *s.Enabled
			}
			if s.SortOrder != nil {
				sets["sort_order"] = *s.SortOrder
			}
			sets["updated_at"] = jtime.FormatApp(time.Now().UTC())
			q, args := store.BuildUpdate("channel_api_keys", sets, "id = ?", existing.I64("id", 0))
			st.Exec(ctx, q, args...)
		} else {
			en := 1
			if s.Enabled != nil {
				en = *s.Enabled
			}
			so := 0
			if s.SortOrder != nil {
				so = *s.SortOrder
			}
			now := jtime.FormatApp(time.Now().UTC())
			st.Insert(ctx, "INSERT INTO channel_api_keys (channel_id, key_name, api_key, enabled, sort_order, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?)",
				chID, kn, ak, en, so, now, now)
		}
	}

	// Delete keys not in submitted list
	for _, r := range existing {
		if !submittedNames[r.Str("key_name")] {
			st.Exec(ctx, "DELETE FROM channel_api_keys WHERE id = ?", r.I64("id", 0))
		}
	}
}

func updateWithModels(ctx context.Context, st *store.Store, chID int64, modelsJSON string, body map[string]any) {
	if modelsJSON == "" || modelsJSON == "[]" {
		// Delete all
		cmRows, _ := st.Query(ctx, "SELECT id FROM channel_models WHERE channel_id = ?", chID)
		for _, r := range cmRows {
			st.Exec(ctx, "DELETE FROM model_channel_rels WHERE channel_model_id = ?", r.I64("id", 0))
		}
		st.Exec(ctx, "DELETE FROM channel_models WHERE channel_id = ?", chID)
		return
	}

	var items []struct {
		ModelName   string `json:"modelName"`
		DisplayName string `json:"displayName"`
		Deleted     bool   `json:"_deleted"`
	}
	if err := json.Unmarshal([]byte(modelsJSON), &items); err != nil {
		return
	}

	existing, _ := st.Query(ctx, "SELECT * FROM channel_models WHERE channel_id = ?", chID)
	existingMap := make(map[string]store.Row)
	for _, r := range existing {
		existingMap[r.Str("model_name")] = r
	}

	submittedNames := make(map[string]bool)
	for _, item := range items {
		if item.Deleted {
			continue
		}
		submittedNames[item.ModelName] = true
	}

	// Delete removed
	for _, r := range existing {
		if !submittedNames[r.Str("model_name")] {
			st.Exec(ctx, "DELETE FROM model_channel_rels WHERE channel_model_id = ?", r.I64("id", 0))
			st.Exec(ctx, "DELETE FROM channel_models WHERE id = ?", r.I64("id", 0))
		}
	}

	now := jtime.FormatApp(time.Now().UTC())
	for _, item := range items {
		if item.Deleted {
			continue
		}
		dn := item.DisplayName
		if dn == "" {
			dn = item.ModelName
		}
		if _, exists := existingMap[item.ModelName]; !exists {
			st.Insert(ctx, "INSERT INTO channel_models (channel_id, model_name, display_name, enabled, source, input, created_at) VALUES (?, ?, ?, 1, 'manual', 'text', ?)",
				chID, item.ModelName, dn, now)
		}
	}
}

func getChannelModelNameSet(ctx context.Context, st *store.Store, chID int64) map[string]bool {
	rows, _ := st.Query(ctx, "SELECT model_name FROM channel_models WHERE channel_id = ?", chID)
	out := make(map[string]bool)
	for _, r := range rows {
		out[r.Str("model_name")] = true
	}
	return out
}

func symDiff(a, b map[string]bool) []string {
	var diff []string
	for k := range a {
		if !b[k] {
			diff = append(diff, k)
		}
	}
	for k := range b {
		if !a[k] {
			diff = append(diff, k)
		}
	}
	return diff
}

// diffModelNames returns names present only in after (added) and only in
// before (removed); both slices are non-nil and sorted for stable output.
func diffModelNames(before, after map[string]bool) (added, removed []string) {
	added = []string{}
	removed = []string{}
	for k := range after {
		if !before[k] {
			added = append(added, k)
		}
	}
	for k := range before {
		if !after[k] {
			removed = append(removed, k)
		}
	}
	sort.Strings(added)
	sort.Strings(removed)
	return added, removed
}

func quickTestProvider(channel models.Channel, channelModel models.ChannelModel, apiKey, provider, message string) (content string, ttfb int64, usageOutputTokens int64, err error) {
	baseURL := strings.TrimSpace(channel.BaseURL)
	if baseURL == "" {
		if provider == "anthropic" {
			baseURL = "https://api.anthropic.com/v1"
		} else {
			baseURL = "https://api.openai.com/v1"
		}
	}
	baseURL = strings.TrimRight(baseURL, "/")

	var endpoint string
	if provider == "azure" {
		endpoint = baseURL
	} else if provider == "anthropic" {
		endpoint = baseURL + "/messages"
	} else {
		endpoint = baseURL + "/chat/completions"
	}

	reqBody := map[string]any{
		"model":      channelModel.ModelName,
		"max_tokens": 100,
		"stream":     true,
		"messages":   []any{map[string]string{"role": "user", "content": message}},
	}
	if provider != "anthropic" && provider != "azure" {
		reqBody["stream_options"] = map[string]bool{"include_usage": true}
	}

	bodyBytes, _ := json.Marshal(reqBody)
	req, _ := http.NewRequest("POST", endpoint, bytes.NewReader(bodyBytes))
	req.Header.Set("Content-Type", "application/json")
	if provider == "azure" {
		req.Header.Set("api-key", apiKey)
	} else if provider == "anthropic" {
		req.Header.Set("x-api-key", apiKey)
		req.Header.Set("anthropic-version", "2023-06-01")
	} else {
		req.Header.Set("Authorization", "Bearer "+apiKey)
	}
	if channel.CustomHeaders != nil {
		var ch map[string]string
		if json.Unmarshal([]byte(*channel.CustomHeaders), &ch) == nil {
			for k, v := range ch {
				req.Header.Set(k, v)
			}
		}
	}

	client := &http.Client{Timeout: 120 * time.Second}
	start := time.Now()
	resp, err := client.Do(req)
	if err != nil {
		return "", 0, 0, err
	}
	defer resp.Body.Close()

	firstByte := time.Now()
	ttfb = firstByte.Sub(start).Milliseconds()

	if resp.StatusCode != http.StatusOK {
		body, _ := io.ReadAll(resp.Body)
		return "", 0, 0, fmt.Errorf("API Error: %s", string(body))
	}

	// Read SSE stream
	buf := make([]byte, 0, 4096)
	readBuf := make([]byte, 1024)
	var text strings.Builder
	usage := int64(0)
	hasData := false

	for {
		n, readErr := resp.Body.Read(readBuf)
		if n > 0 {
			buf = append(buf, readBuf[:n]...)
			for {
				idx := bytes.Index(buf, []byte("\n\n"))
				if idx < 0 {
					break
				}
				frame := buf[:idx]
				buf = buf[idx+2:]
				hasData = true

				for _, line := range bytes.Split(frame, []byte("\n")) {
					line = bytes.TrimSpace(line)
					if bytes.HasPrefix(line, []byte("data: ")) {
						data := bytes.TrimSpace(line[6:])
						if string(data) == "[DONE]" {
							continue
						}
						var evt map[string]any
						json.Unmarshal(data, &evt)

						// Extract text
						if choices, ok := evt["choices"].([]any); ok && len(choices) > 0 {
							if choice, ok := choices[0].(map[string]any); ok {
								if delta, ok := choice["delta"].(map[string]any); ok {
									if c, ok := delta["content"].(string); ok {
										text.WriteString(c)
									}
								}
							}
						}
						if typ, ok := evt["type"].(string); ok && typ == "content_block_delta" {
							if delta, ok := evt["delta"].(map[string]any); ok {
								if t, ok := delta["text"].(string); ok {
									text.WriteString(t)
								}
							}
						}

						// Extract usage
						if u, ok := evt["usage"].(map[string]any); ok {
							if ct, ok := u["completion_tokens"].(float64); ok {
								usage = int64(ct)
							} else if ot, ok := u["output_tokens"].(float64); ok {
								usage = int64(ot)
							}
						}
					}
				}
			}
		}
		if readErr != nil {
			break
		}
	}

	// Fallback: if no data: lines, try JSON parse
	if !hasData || (text.Len() == 0 && resp.ContentLength > 0) {
		allBody, _ := io.ReadAll(io.NopCloser(bytes.NewReader(buf)))
		var fallback map[string]any
		if json.Unmarshal(allBody, &fallback) == nil {
			if choices, ok := fallback["choices"].([]any); ok && len(choices) > 0 {
				if choice, ok := choices[0].(map[string]any); ok {
					if msg, ok := choice["message"].(map[string]any); ok {
						if c, ok := msg["content"].(string); ok {
							text.Reset()
							text.WriteString(c)
						}
					}
				}
			}
			if u, ok := fallback["usage"].(map[string]any); ok {
				if ct, ok := u["completion_tokens"].(float64); ok {
					usage = int64(ct)
				}
			}
		}
	}

	return text.String(), ttfb, usage, nil
}

var cjkRegex = regexp.MustCompile(`[\p{Han}]`)

func countTokens(s string) (cjk, other int) {
	for _, r := range s {
		if cjkRegex.MatchString(string(r)) {
			cjk++
		} else {
			other++
		}
	}
	return
}

func joinInts(ids []string) string {
	return strings.Join(ids, ",")
}

func joinIntsAny(ids []any) string {
	var b strings.Builder
	for i, id := range ids {
		if i > 0 {
			b.WriteByte(',')
		}
		fmt.Fprint(&b, id)
	}
	return b.String()
}
