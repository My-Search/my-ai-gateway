package server

import (
	"context"
	"fmt"
	"math"
	"sort"
	"strconv"
	"strings"
	"time"

	"github.com/gin-gonic/gin"
	"github.com/my-search/my-ai-gateway/internal/httpx"
	"github.com/my-search/my-ai-gateway/internal/jtime"
	"github.com/my-search/my-ai-gateway/internal/models"
	"github.com/my-search/my-ai-gateway/internal/relay/circuit"
	"github.com/my-search/my-ai-gateway/internal/store"
)

func registerModelRoutes(g *gin.RouterGroup, d Deps) {
	// GET /admin/api/models
	g.GET("/models", func(c *gin.Context) {
		ctx := c.Request.Context()
		rows, err := d.Store.Query(ctx, "SELECT * FROM models ORDER BY created_at ASC")
		if err != nil {
			httpx.OK(c, failureEnvelope(err.Error()))
			return
		}
		out := make([]models.Model, 0, len(rows))
		for _, r := range rows {
			out = append(out, store.RowToModel(r))
		}
		httpx.OK(c, out)
	})

	// GET /admin/api/models/stats
	// This requires the stats collector which needs request_logs data.
	// For now return a stub matching the shape but with zeros.
	g.GET("/models/stats", func(c *gin.Context) {
		ctx := c.Request.Context()
		dateStr := c.Query("date")
		refDate := time.Now().UTC().In(jtime.Shanghai)
		if dateStr != "" {
			if t, err := time.ParseInLocation("2006-01-02", dateStr, jtime.Shanghai); err == nil {
				refDate = t
			}
		}
		since := jtime.ShanghaiStart(refDate)

		// 仅当显式 withTrends=true/1 时才计算趋势（144 桶 GROUP BY 聚合）；
		// 默认关闭，让入口模型页首屏只拉统计信息，避免拖慢打开速度。
		withTrends := c.Query("withTrends") == "true" || c.Query("withTrends") == "1"

		// Get all model names
		rows, _ := d.Store.Query(ctx, "SELECT model_name FROM models ORDER BY model_name ASC")
		modelNames := make([]string, 0, len(rows))
		for _, r := range rows {
			modelNames = append(modelNames, r.Str("model_name"))
		}

		// Per-model stats, computed with a handful of GROUP BY scans instead of
		// one correlated query fan-out per model (N models → N×4 queries before).
		// Keep the same start-anchored semantics: requests counts distinct start
		// traces, success counts those traces that also carry a success row.
		type modelStat struct {
			ModelName       string  `json:"modelName"`
			Requests        int64   `json:"requests"`
			SuccessRate     float64 `json:"successRate"`
			AvgResponseTime int64   `json:"avgResponseTime"`
			AvgOutputSpeed  float64 `json:"avgOutputSpeed"`
		}

		// 1) requests: distinct start traces per model
		// 边界口径：jtime.FormatDefault(since) 是 T 分隔的 UTC 瞬时下界（上海窗口
		// 起点转 UTC），对当前写入路径的 T 格式行精确。不能改成 DATE-ONLY：窗口锚定
		// 上海零点（16:00Z），截成日期会把边界日前一天 00:00Z~16:00Z（上海昨日时段）
		// 计入今天。详见 getAPIKeyPeriodStats 的边界口径注释。
		reqCount := make(map[string]int64)
		reqRows, _ := d.Store.Query(ctx,
			`SELECT model_name, COUNT(DISTINCT trace_id) as cnt
			 FROM request_logs WHERE phase='start' AND created_at>=? AND model_name IS NOT NULL AND model_name!=''
			 GROUP BY model_name`,
			jtime.FormatDefault(since))
		for _, r := range reqRows {
			reqCount[r.Str("model_name")] = r.I64("cnt", 0)
		}

		// 2) success: distinct start traces that also have a success row in window
		succCount := make(map[string]int64)
		succRows, _ := d.Store.Query(ctx,
			`SELECT s.model_name, COUNT(DISTINCT s.trace_id) as cnt
			 FROM (SELECT DISTINCT trace_id, model_name FROM request_logs
			         WHERE phase='start' AND created_at>=? AND model_name IS NOT NULL AND model_name!='') s
			 JOIN (SELECT DISTINCT trace_id FROM request_logs WHERE phase='success' AND created_at>=?) su
			   ON s.trace_id = su.trace_id
			 GROUP BY s.model_name`,
			jtime.FormatDefault(since), jtime.FormatDefault(since))
		for _, r := range succRows {
			succCount[r.Str("model_name")] = r.I64("cnt", 0)
		}

		// 3) avg response time + avg output speed per model in a single scan
		type modelAgg struct {
			avgRt  *float64
			avgSpd *float64
		}
		agg := make(map[string]modelAgg)
		aggRows, _ := d.Store.Query(ctx,
			`SELECT model_name,
			        AVG(CASE WHEN first_byte_ms>0 THEN first_byte_ms END) as avg_rt,
			        AVG(CASE WHEN phase='success' AND completion_tokens>0 AND response_time_ms>0
			                 THEN completion_tokens*1000.0/response_time_ms END) as avg_spd
			 FROM request_logs WHERE created_at>=? AND model_name IS NOT NULL AND model_name!=''
			 GROUP BY model_name`,
			jtime.FormatDefault(since))
		for _, r := range aggRows {
			agg[r.Str("model_name")] = modelAgg{avgRt: r.F64Ptr("avg_rt"), avgSpd: r.F64Ptr("avg_spd")}
		}

		stats := make([]modelStat, 0, len(modelNames))
		for _, mn := range modelNames {
			reqs := reqCount[mn]
			succ := succCount[mn]
			sr := 0.0
			if reqs > 0 {
				// Java clamps with Math.min(100.0, ...) as a defensive measure.
				rate := float64(succ) / float64(reqs) * 100
				if rate > 100 {
					rate = 100
				}
				sr = float64(int64(rate*10+0.5)) / 10.0
			}
			artVal := int64(0)
			spd := 0.0
			if a, ok := agg[mn]; ok {
				if v := a.avgRt; v != nil {
					artVal = int64(*v + 0.5)
				}
				if v := a.avgSpd; v != nil {
					spd = float64(int64(*v*10+0.5)) / 10.0
				}
			}
			stats = append(stats, modelStat{
				ModelName:       mn,
				Requests:        reqs,
				SuccessRate:     sr,
				AvgResponseTime: artVal,
				AvgOutputSpeed:  spd,
			})
		}

		// Trends (144 buckets) — opt-in only
		buckets := make([]string, 0)
		trends := make(map[string][]int64)
		if withTrends {
			buckets = make([]string, 144)
			for i := 0; i < 144; i++ {
				h := i / 6
				m := (i % 6) * 10
				buckets[i] = fmt.Sprintf("%02d:%02d", h, m)
			}
			for _, mn := range modelNames {
				trends[mn] = make([]int64, 144)
			}
			trendRaw, _ := d.Store.Query(ctx,
				`SELECT model_name,
				         printf('%02d:%02d',
				           CAST(STRFTIME('%H', DATETIME(created_at, '+8 hours')) AS INTEGER),
				           (CAST(STRFTIME('%M', DATETIME(created_at, '+8 hours')) AS INTEGER) / 10) * 10) bucket,
				         COUNT(DISTINCT CASE WHEN phase='start' THEN trace_id END) as cnt
				  FROM request_logs WHERE created_at>=? AND model_name IS NOT NULL AND model_name!=''
				  GROUP BY model_name, bucket`,
				jtime.FormatDefault(since))
			bucketIdx := make(map[string]int)
			for i, b := range buckets {
				bucketIdx[b] = i
			}
			for _, r := range trendRaw {
				mn := r.Str("model_name")
				bi, ok := bucketIdx[r.Str("bucket")]
				if !ok {
					continue
				}
				if _, exists := trends[mn]; exists {
					trends[mn][bi] = r.I64("cnt", 0)
				}
			}
		}

		httpx.OK(c, httpx.NewOrderedMap().
			Set("stats", stats).
			Set("trends", trends).
			Set("buckets", buckets))
	})

	// GET /admin/api/models/{id}
	g.GET("/models/:id", func(c *gin.Context) {
		ctx := c.Request.Context()
		id, ok := pathID(c, "id")
		if !ok {
			notFound(c, "模型不存在")
			return
		}
		row, err := d.Store.QueryOne(ctx, "SELECT * FROM models WHERE id = ?", id)
		if err != nil {
			notFound(c, "模型不存在")
			return
		}
		httpx.OK(c, store.RowToModel(row))
	})

	// POST /admin/api/models
	g.POST("/models", func(c *gin.Context) {
		ctx := c.Request.Context()
		var body struct {
			ModelName   string  `json:"modelName"`
			Description *string `json:"description"`
			Strategy    *string `json:"strategy"`
		}
		if err := c.ShouldBindJSON(&body); err != nil {
			httpx.OK(c, failureEnvelope("请求参数错误"))
			return
		}

		now := jtime.FormatApp(time.Now().UTC())
		id, err := d.Store.Insert(ctx,
			`INSERT INTO models (model_name, description, strategy, enabled, hidden, rel_mode, created_at, updated_at)
			 VALUES (?, ?, ?, 1, 0, 'self_add', ?, ?)`,
			body.ModelName, derefStr(body.Description, ""), derefStr(body.Strategy, "failover"), now, now)
		if err != nil {
			httpx.OK(c, failureEnvelope(err.Error()))
			return
		}
		// Insert default circuit breaker config
		d.Store.Insert(ctx,
			`INSERT OR IGNORE INTO circuit_breaker_configs (model_id, retry_count, circuit_break_duration, circuit_break_scope, enabled, created_at, updated_at)
			 VALUES (?, 3, 60, 'model', 1, ?, ?)`,
			id, now, now)
		httpx.OK(c, httpx.NewOrderedMap().Set("success", true).Set("id", id))
	})

	// PUT /admin/api/models/{id}
	g.PUT("/models/:id", func(c *gin.Context) {
		ctx := c.Request.Context()
		id, ok := pathID(c, "id")
		if !ok {
			httpx.OK(c, failureEnvelope("模型不存在"))
			return
		}
		var body map[string]any
		if err := c.ShouldBindJSON(&body); err != nil {
			httpx.OK(c, failureEnvelope("请求参数错误"))
			return
		}

		sets := make(map[string]any)
		for _, k := range []string{"modelName", "description", "strategy", "relMode"} {
			if v, exists := body[k]; exists {
				sets[modelsCamelToSnake(k)] = v
			}
		}
		for _, k := range []string{"enabled", "hidden", "imageInvalidateCount", "videoInvalidateCount", "audioInvalidateCount", "forceOverrideReasoningEffort"} {
			if v, exists := body[k]; exists {
				sets[modelsCamelToSnake(k)] = toIntAny(v)
			}
		}
		if _, exists := body["inheritFromModelId"]; exists {
			v := body["inheritFromModelId"]
			if v == nil {
				sets["inherit_from_model_id"] = nil
			} else {
				sets["inherit_from_model_id"] = toIntAny(v)
			}
		}
		if len(sets) == 0 {
			httpx.OK(c, httpx.NewOrderedMap().Set("success", true))
			return
		}
		now := jtime.FormatApp(time.Now().UTC())
		sets["updated_at"] = now
		q, args := store.BuildUpdate("models", sets, "id = ?", id)
		_, _ = d.Store.Exec(ctx, q, args...)
		httpx.OK(c, httpx.NewOrderedMap().Set("success", true))
	})

	// DELETE /admin/api/models/{id}
	g.DELETE("/models/:id", func(c *gin.Context) {
		ctx := c.Request.Context()
		id, ok := pathID(c, "id")
		if !ok {
			httpx.OK(c, failureEnvelope("模型不存在"))
			return
		}

		// Check inheritors
		inheritors, _ := d.Store.Query(ctx, "SELECT model_name FROM models WHERE rel_mode='inherit' AND inherit_from_model_id=?", id)
		if len(inheritors) > 0 {
			names := make([]string, 0, len(inheritors))
			row, _ := d.Store.QueryOne(ctx, "SELECT model_name FROM models WHERE id=?", id)
			selfName := row.Str("model_name")
			for _, r := range inheritors {
				names = append(names, r.Str("model_name"))
			}
			httpx.OK(c, failureEnvelope(fmt.Sprintf("模型「%s」正被以下模型继承，无法删除：%s", selfName, strings.Join(names, "、"))))
			return
		}

		d.Store.Exec(ctx, "DELETE FROM model_channel_rels WHERE model_id = ?", id)
		d.Store.Exec(ctx, "DELETE FROM model_group_rels WHERE model_id = ?", id)
		d.Store.Exec(ctx, "DELETE FROM circuit_breaker_configs WHERE model_id = ?", id)
		d.Store.Exec(ctx, "DELETE FROM prompt_injections WHERE model_id = ?", id)
		d.Store.Exec(ctx, "DELETE FROM models WHERE id = ?", id)
		httpx.OK(c, httpx.NewOrderedMap().Set("success", true))
	})

	// GET /admin/api/models/{id}/rels
	g.GET("/models/:id/rels", func(c *gin.Context) {
		ctx := c.Request.Context()
		id, ok := pathID(c, "id")
		if !ok {
			notFound(c, "模型不存在")
			return
		}

		modelRow, err := d.Store.QueryOne(ctx, "SELECT * FROM models WHERE id = ?", id)
		if err != nil {
			notFound(c, "模型不存在")
			return
		}
		model := store.RowToModel(modelRow)

		// Handle dangling inherit
		if derefStr(model.RelMode, "") == "inherit" {
			if model.InheritFromModelID == nil {
				d.Store.Exec(ctx, "UPDATE models SET rel_mode='self_add', inherit_from_model_id=NULL, updated_at=? WHERE id=?", jtime.FormatApp(time.Now().UTC()), id)
				model.RelMode = models.Str("self_add")
				model.InheritFromModelID = nil
			} else if src, _ := d.Store.QueryOne(ctx, "SELECT id FROM models WHERE id=?", *model.InheritFromModelID); src == nil {
				d.Store.Exec(ctx, "UPDATE models SET rel_mode='self_add', inherit_from_model_id=NULL, updated_at=? WHERE id=?", jtime.FormatApp(time.Now().UTC()), id)
				model.RelMode = models.Str("self_add")
				model.InheritFromModelID = nil
			}
		} else if model.InheritFromModelID != nil {
			// 自添加模式下保留的「上次继承源」：源已被删除时静默清除，避免切回继承时引用悬空
			if src, _ := d.Store.QueryOne(ctx, "SELECT id FROM models WHERE id=?", *model.InheritFromModelID); src == nil {
				d.Store.Exec(ctx, "UPDATE models SET inherit_from_model_id=NULL, updated_at=? WHERE id=?", jtime.FormatApp(time.Now().UTC()), id)
				model.InheritFromModelID = nil
			}
		}

		// Resolve rels (support inheritance)
		rels := resolveModelRels(ctx, d.Store, id, make(map[int64]bool))

		// Available channel models
		availRows, _ := d.Store.Query(ctx,
			`SELECT cm.*, c.name as channel_name, c.channel_type FROM channel_models cm
			  JOIN channels c ON c.id = cm.channel_id
			 WHERE cm.enabled=1 AND c.enabled=1 ORDER BY cm.model_name ASC`)
		availModels := make([]models.ChannelModel, 0, len(availRows))
		for _, r := range availRows {
			cm := store.RowToChannelModel(r)
			cm.ChannelName = r.StrPtr("channel_name")
			cm.ChannelType = r.StrPtr("channel_type")
			availModels = append(availModels, cm)
		}

		// InheritFromModelName
		var inheritFromName *string
		if derefStr(model.RelMode, "") == "inherit" && model.InheritFromModelID != nil {
			src, _ := d.Store.QueryOne(ctx, "SELECT model_name FROM models WHERE id=?", *model.InheritFromModelID)
			if src != nil {
				inheritFromName = src.StrPtr("model_name")
			}
		}

		// Compute TTFT and output speed stats (24h window) and breaker marks.
		relStats := computeRelStats(ctx, d.Store, rels)
		applyRelBrokenMarks(ctx, d.Store, relStats)

		groupRels := resolveModelGroupRels(ctx, d.Store, id, make(map[int64]bool))
		groups := resolveModelGroups(ctx, d.Store, id)

		httpx.OK(c, httpx.NewOrderedMap().
			Set("model", model).
			Set("rels", relStats).
			Set("groupRels", groupRels).
			Set("availableModels", availModels).
			Set("availableGroups", groups).
			Set("inheritFromModelName", inheritFromName))
	})

	// DELETE /admin/api/models/rels/{relId}/circuit-breaker
	g.DELETE("/models/rels/:relId/circuit-breaker", func(c *gin.Context) {
		ctx := c.Request.Context()
		relID, ok := pathID(c, "relId")
		if !ok {
			notFound(c, "关联不存在")
			return
		}
		relRow, err := d.Store.QueryOne(ctx, "SELECT * FROM model_channel_rels WHERE id = ?", relID)
		if err != nil {
			notFound(c, "关联不存在")
			return
		}
		recovered := clearChannelModelBreaker(ctx, d.Store, relRow.I64("channel_model_id", 0))
		httpx.OK(c, httpx.NewOrderedMap().Set("success", true).Set("recovered", recovered))
	})

	// DELETE /admin/api/channel-models/{cmId}/circuit-breaker — 按渠道模型解除熔断。
	// 小组页面的成员没有关联行，只能按渠道模型 ID 解除。
	g.DELETE("/channel-models/:cmId/circuit-breaker", func(c *gin.Context) {
		ctx := c.Request.Context()
		cmID, ok := pathID(c, "cmId")
		if !ok {
			notFound(c, "渠道模型不存在")
			return
		}
		recovered := clearChannelModelBreaker(ctx, d.Store, cmID)
		httpx.OK(c, httpx.NewOrderedMap().Set("success", true).Set("recovered", recovered))
	})

	// GET /admin/api/models/{id}/inheritable
	g.GET("/models/:id/inheritable", func(c *gin.Context) {
		ctx := c.Request.Context()
		id, ok := pathID(c, "id")
		if !ok {
			notFound(c, "模型不存在")
			return
		}
		rows, err := d.Store.Query(ctx, "SELECT * FROM models WHERE enabled=1 AND id!=? ORDER BY model_name ASC", id)
		if err != nil {
			notFound(c, "模型不存在")
			return
		}
		out := make([]models.Model, 0, len(rows))
		for _, r := range rows {
			out = append(out, store.RowToModel(r))
		}
		httpx.OK(c, out)
	})

	// PUT /admin/api/models/{id}/rel-mode
	g.PUT("/models/:id/rel-mode", func(c *gin.Context) {
		ctx := c.Request.Context()
		id, ok := pathID(c, "id")
		if !ok {
			httpx.OK(c, failureEnvelope("模型不存在"))
			return
		}
		var body struct {
			Mode          string `json:"mode"`
			SourceModelID *int64 `json:"sourceModelId"`
		}
		if err := c.ShouldBindJSON(&body); err != nil {
			httpx.OK(c, failureEnvelope("请求参数错误"))
			return
		}

		if body.Mode != "self_add" && body.Mode != "inherit" {
			httpx.OK(c, failureEnvelope("mode 必须为 self_add 或 inherit"))
			return
		}

		if _, err := d.Store.QueryOne(ctx, "SELECT id FROM models WHERE id = ?", id); err != nil {
			httpx.OK(c, failureEnvelope("模型不存在"))
			return
		}

		var cycleBrokenModel *models.Model

		if body.Mode == "inherit" {
			if body.SourceModelID == nil {
				httpx.OK(c, failureEnvelope("切换到继承模式时必须指定源模型"))
				return
			}
			if *body.SourceModelID == id {
				httpx.OK(c, failureEnvelope("不能将模型继承自自身"))
				return
			}
			src, err := d.Store.QueryOne(ctx, "SELECT * FROM models WHERE id = ?", *body.SourceModelID)
			if err != nil {
				httpx.OK(c, failureEnvelope("源模型不存在"))
				return
			}
			_ = src

			// Cycle detection
			visited := map[int64]bool{id: true}
			if closing := findCycleClosing(ctx, d.Store, *body.SourceModelID, visited); closing != nil {
				now := jtime.FormatApp(time.Now().UTC())
				d.Store.Exec(ctx, "UPDATE models SET rel_mode='self_add', inherit_from_model_id=NULL, updated_at=? WHERE id=?", now, closing.ID)
				cycleBrokenModel = closing
				cycleBrokenModel.RelMode = models.Str("self_add")
				cycleBrokenModel.InheritFromModelID = nil
			}
			now := jtime.FormatApp(time.Now().UTC())
			d.Store.Exec(ctx, "UPDATE models SET rel_mode='inherit', inherit_from_model_id=?, updated_at=? WHERE id=?", *body.SourceModelID, now, id)
		} else {
			// 保留 inherit_from_model_id 作为「上次继承源」，切回继承时自动沿用；
			// 仅在检测到循环继承被重置时（上方 closing 分支）才清空
			now := jtime.FormatApp(time.Now().UTC())
			d.Store.Exec(ctx, "UPDATE models SET rel_mode='self_add', updated_at=? WHERE id=?", now, id)
		}
		m2, _ := d.Store.QueryOne(ctx, "SELECT * FROM models WHERE id = ?", id)
		updated := store.RowToModel(m2)

		out := httpx.NewOrderedMap().Set("success", true).Set("model", updated)
		if cycleBrokenModel != nil {
			out.Set("cycleBrokenModel", map[string]any{
				"id":        cycleBrokenModel.ID,
				"modelName": cycleBrokenModel.ModelName,
			})
		}
		httpx.OK(c, out)
	})

	// POST /admin/api/models/{modelId}/rels
	g.POST("/models/:id/rels", func(c *gin.Context) {
		ctx := c.Request.Context()
		modelID, ok := pathID(c, "id")
		if !ok {
			httpx.OK(c, failureEnvelope("参数错误"))
			return
		}

		var body struct {
			ChannelModelIDs []int64 `json:"channelModelIds"`
			GroupIDs        []int64 `json:"groupIds"`
			SortedRelIDs    string  `json:"sortedRelIds"`
		}
		if err := c.ShouldBindJSON(&body); err != nil {
			httpx.OK(c, failureEnvelope("请求参数错误"))
			return
		}

		// Check model is not inherit mode
		mRow, _ := d.Store.QueryOne(ctx, "SELECT model_name, rel_mode FROM models WHERE id=?", modelID)
		if mRow == nil {
			httpx.OK(c, failureEnvelope("模型不存在"))
			return
		}
		if mRow.Str("rel_mode") == "inherit" {
			httpx.OK(c, failureEnvelope(fmt.Sprintf("模型「%s」当前为继承模式，无法修改关联", mRow.Str("model_name"))))
			return
		}

		// Get next sort order. 渠道模型关联与小组关联共用同一序号空间，
		// 因此下个序号要同时看两张表的最大值，避免新增项插到已有项之间。
		lastRel, _ := d.Store.QueryOne(ctx, "SELECT sort_order FROM model_channel_rels WHERE model_id=? ORDER BY sort_order DESC LIMIT 1", modelID)
		lastGroup, _ := d.Store.QueryOne(ctx, "SELECT sort_order FROM model_group_rels WHERE model_id=? ORDER BY sort_order DESC LIMIT 1", modelID)
		nextSort := int64(0)
		if lastRel != nil {
			nextSort = lastRel.I64("sort_order", 0) + 1
		}
		if lastGroup != nil {
			if g := lastGroup.I64("sort_order", 0) + 1; g > nextSort {
				nextSort = g
			}
		}

		now := jtime.FormatApp(time.Now().UTC())
		added := 0
		for _, cmID := range body.ChannelModelIDs {
			existing, _ := d.Store.QueryOne(ctx, "SELECT id FROM model_channel_rels WHERE model_id=? AND channel_model_id=?", modelID, cmID)
			if existing != nil {
				continue
			}
			_, err := d.Store.Insert(ctx,
				"INSERT INTO model_channel_rels (model_id, channel_model_id, weight, enabled, sort_order, created_at) VALUES (?, ?, 1, 1, ?, ?)",
				modelID, cmID, nextSort, now)
			if err == nil {
				added++
				nextSort++
			}
		}
		for _, gID := range body.GroupIDs {
			existing, _ := d.Store.QueryOne(ctx, "SELECT id FROM model_group_rels WHERE model_id=? AND group_id=?", modelID, gID)
			if existing != nil {
				continue
			}
			if d.Store.QueryOneOrZero(ctx, "SELECT id FROM model_groups WHERE id=?", gID) == nil {
				continue
			}
			_, err := d.Store.Insert(ctx,
				"INSERT INTO model_group_rels (model_id, group_id, enabled, sort_order, created_at) VALUES (?, ?, 1, ?, ?)",
				modelID, gID, nextSort, now)
			if err == nil {
				added++
				nextSort++
			}
		}

		// Apply sortedRelIds if present (reorder). 序号同时作用于两类关联，
		// 前端用 "cm:<relId>" / "g:<relId>" 前缀区分，避免两张表的 id 相互覆盖。
		if body.SortedRelIDs != "" {
			applyCombinedRelOrder(ctx, d.Store, strings.Split(body.SortedRelIDs, ","))
		}

		httpx.OK(c, httpx.NewOrderedMap().Set("success", true).Set("count", added))
	})

	// DELETE /admin/api/models/rels/{relId}
	g.DELETE("/models/rels/:relId", func(c *gin.Context) {
		ctx := c.Request.Context()
		relID, ok := pathID(c, "relId")
		if !ok {
			httpx.OK(c, failureEnvelope("关联不存在"))
			return
		}
		relRow, err := d.Store.QueryOne(ctx, "SELECT * FROM model_channel_rels WHERE id = ?", relID)
		if err != nil {
			httpx.OK(c, failureEnvelope("关联不存在"))
			return
		}
		// Check model mode
		modelRow, _ := d.Store.QueryOne(ctx, "SELECT model_name, rel_mode FROM models WHERE id=?", relRow.I64("model_id", 0))
		if modelRow != nil && modelRow.Str("rel_mode") == "inherit" {
			httpx.OK(c, failureEnvelope(fmt.Sprintf("模型「%s」当前为继承模式，无法修改关联", modelRow.Str("model_name"))))
			return
		}
		d.Store.Exec(ctx, "DELETE FROM model_channel_rels WHERE id = ?", relID)
		httpx.OK(c, httpx.NewOrderedMap().Set("success", true))
	})

	// POST /admin/api/models/rels/batch-delete
	// relIds 同时接受渠道模型关联与小组关联：前端用 "cm:<id>" / "g:<id>" 前缀区分，
	// 裸数字按渠道模型关联处理（兼容旧调用）。
	g.POST("/models/rels/batch-delete", func(c *gin.Context) {
		ctx := c.Request.Context()
		var body struct {
			RelIDs []any `json:"relIds"`
		}
		if err := c.ShouldBindJSON(&body); err != nil || len(body.RelIDs) == 0 {
			httpx.OK(c, failureEnvelope("relIds 不能为空"))
			return
		}

		deleted := 0
		for _, rid := range body.RelIDs {
			kind, relID := parseRelRef(rid)
			if relID == 0 {
				continue
			}
			var modelID int64
			var table string
			if kind == "g" {
				table = "model_group_rels"
			} else {
				table = "model_channel_rels"
			}
			relRow, err := d.Store.QueryOne(ctx, "SELECT model_id FROM "+table+" WHERE id = ?", relID)
			if err != nil {
				continue
			}
			modelID = relRow.I64("model_id", 0)
			modelRow, _ := d.Store.QueryOne(ctx, "SELECT rel_mode, model_name FROM models WHERE id=?", modelID)
			if modelRow != nil && modelRow.Str("rel_mode") == "inherit" {
				continue
			}
			if _, err := d.Store.Exec(ctx, "DELETE FROM "+table+" WHERE id = ?", relID); err == nil {
				deleted++
			}
		}
		httpx.OK(c, httpx.NewOrderedMap().Set("success", true).Set("count", deleted))
	})

	// PUT /admin/api/models/rels/{relId}/sort
	g.PUT("/models/rels/:relId/sort", func(c *gin.Context) {
		ctx := c.Request.Context()
		relID, ok := pathID(c, "relId")
		if !ok {
			httpx.OK(c, failureEnvelope("关联不存在"))
			return
		}
		var body struct {
			SortOrder int `json:"sortOrder"`
		}
		_ = c.ShouldBindJSON(&body)
		d.Store.Exec(ctx, "UPDATE model_channel_rels SET sort_order=? WHERE id=?", body.SortOrder, relID)
		httpx.OK(c, httpx.NewOrderedMap().Set("success", true))
	})

	// PUT /admin/api/models/rels/sort
	// sortedRelIds 是两类关联的混合顺序（"cm:<id>" / "g:<id>"），序号统一写入
	// 各自的 sort_order，路由时再按同一序号空间合并。
	g.PUT("/models/rels/sort", func(c *gin.Context) {
		ctx := c.Request.Context()
		var body struct {
			SortedRelIDs []any `json:"sortedRelIds"`
		}
		if err := c.ShouldBindJSON(&body); err != nil {
			httpx.OK(c, failureEnvelope("请求参数错误"))
			return
		}
		refs := make([]string, 0, len(body.SortedRelIDs))
		for _, rid := range body.SortedRelIDs {
			kind, relID := parseRelRef(rid)
			if relID == 0 {
				continue
			}
			refs = append(refs, kind+":"+strconv.FormatInt(relID, 10))
		}
		applyCombinedRelOrder(ctx, d.Store, refs)
		httpx.OK(c, httpx.NewOrderedMap().Set("success", true))
	})

	// PUT /admin/api/models/rels/{relId}/reasoning-effort
	g.PUT("/models/rels/:relId/reasoning-effort", func(c *gin.Context) {
		ctx := c.Request.Context()
		relID, ok := pathID(c, "relId")
		if !ok {
			httpx.OK(c, failureEnvelope("关联不存在"))
			return
		}
		var body struct {
			ReasoningEffort *string `json:"reasoningEffort"`
		}
		_ = c.ShouldBindJSON(&body)
		if body.ReasoningEffort != nil {
			d.Store.Exec(ctx, "UPDATE model_channel_rels SET reasoning_effort=? WHERE id=?", strings.TrimSpace(*body.ReasoningEffort), relID)
		} else {
			d.Store.Exec(ctx, "UPDATE model_channel_rels SET reasoning_effort=NULL WHERE id=?", relID)
		}
		httpx.OK(c, httpx.NewOrderedMap().Set("success", true))
	})

	// DELETE /admin/api/models/group-rels/{relId}
	g.DELETE("/models/group-rels/:relId", func(c *gin.Context) {
		ctx := c.Request.Context()
		relID, ok := pathID(c, "relId")
		if !ok {
			httpx.OK(c, failureEnvelope("关联不存在"))
			return
		}
		relRow, err := d.Store.QueryOne(ctx, "SELECT * FROM model_group_rels WHERE id = ?", relID)
		if err != nil {
			httpx.OK(c, failureEnvelope("关联不存在"))
			return
		}
		modelRow, _ := d.Store.QueryOne(ctx, "SELECT model_name, rel_mode FROM models WHERE id=?", relRow.I64("model_id", 0))
		if modelRow != nil && modelRow.Str("rel_mode") == "inherit" {
			httpx.OK(c, failureEnvelope(fmt.Sprintf("模型「%s」当前为继承模式，无法修改关联", modelRow.Str("model_name"))))
			return
		}
		d.Store.Exec(ctx, "DELETE FROM model_group_rels WHERE id = ?", relID)
		httpx.OK(c, httpx.NewOrderedMap().Set("success", true))
	})

	// PUT /admin/api/models/group-rels/{relId}/sort
	g.PUT("/models/group-rels/:relId/sort", func(c *gin.Context) {
		ctx := c.Request.Context()
		relID, ok := pathID(c, "relId")
		if !ok {
			httpx.OK(c, failureEnvelope("关联不存在"))
			return
		}
		var body struct {
			SortOrder int `json:"sortOrder"`
		}
		_ = c.ShouldBindJSON(&body)
		d.Store.Exec(ctx, "UPDATE model_group_rels SET sort_order=? WHERE id=?", body.SortOrder, relID)
		httpx.OK(c, httpx.NewOrderedMap().Set("success", true))
	})

	// PUT /admin/api/models/group-rels/{relId}/reasoning-effort
	// 入口模型 -> 小组 关联的默认思考强度：仅当组内成员未单独配置时对其生效。
	g.PUT("/models/group-rels/:relId/reasoning-effort", func(c *gin.Context) {
		ctx := c.Request.Context()
		relID, ok := pathID(c, "relId")
		if !ok {
			httpx.OK(c, failureEnvelope("关联不存在"))
			return
		}
		var body struct {
			ReasoningEffort *string `json:"reasoningEffort"`
		}
		_ = c.ShouldBindJSON(&body)
		if body.ReasoningEffort != nil {
			d.Store.Exec(ctx, "UPDATE model_group_rels SET reasoning_effort=? WHERE id=?", strings.TrimSpace(*body.ReasoningEffort), relID)
		} else {
			d.Store.Exec(ctx, "UPDATE model_group_rels SET reasoning_effort=NULL WHERE id=?", relID)
		}
		httpx.OK(c, httpx.NewOrderedMap().Set("success", true))
	})

	// GET /admin/api/models/{id}/circuit-breaker
	g.GET("/models/:id/circuit-breaker", func(c *gin.Context) {
		ctx := c.Request.Context()
		id, ok := pathID(c, "id")
		if !ok {
			notFound(c, "模型不存在")
			return
		}
		mdl, err := d.Store.QueryOne(ctx, "SELECT * FROM models WHERE id = ?", id)
		if err != nil {
			notFound(c, "模型不存在")
			return
		}
		cfg, _ := d.Store.QueryOne(ctx, "SELECT * FROM circuit_breaker_configs WHERE model_id = ?", id)
		if cfg == nil {
			now := jtime.FormatApp(time.Now().UTC())
			d.Store.Insert(ctx, "INSERT INTO circuit_breaker_configs (model_id, retry_count, circuit_break_duration, circuit_break_scope, enabled, created_at, updated_at) VALUES (?, 3, 60, 'model', 1, ?, ?)", id, now, now)
			cfg, _ = d.Store.QueryOne(ctx, "SELECT * FROM circuit_breaker_configs WHERE model_id = ?", id)
		}
		config := store.RowToCircuitConfig(cfg)
		config.ModelName = mdl.StrPtr("model_name")
		httpx.OK(c, httpx.NewOrderedMap().
			Set("model", store.RowToModel(mdl)).
			Set("config", config))
	})

	// PUT /admin/api/models/{id}/circuit-breaker
	g.PUT("/models/:id/circuit-breaker", func(c *gin.Context) {
		ctx := c.Request.Context()
		id, ok := pathID(c, "id")
		if !ok {
			httpx.OK(c, failureEnvelope("模型不存在"))
			return
		}
		var body struct {
			RetryCount           *int    `json:"retryCount"`
			CircuitBreakDuration *int    `json:"circuitBreakDuration"`
			CircuitBreakScope    *string `json:"circuitBreakScope"`
			Enabled              *int    `json:"enabled"`
		}
		if err := c.ShouldBindJSON(&body); err != nil {
			httpx.OK(c, failureEnvelope("请求参数错误"))
			return
		}
		now := jtime.FormatApp(time.Now().UTC())
		existing, err := d.Store.QueryOne(ctx, "SELECT id FROM circuit_breaker_configs WHERE model_id = ?", id)
		if err == nil && existing != nil {
			sets := make(map[string]any)
			if v := body.RetryCount; v != nil {
				sets["retry_count"] = *v
			}
			if v := body.CircuitBreakDuration; v != nil {
				sets["circuit_break_duration"] = *v
			}
			if v := body.CircuitBreakScope; v != nil {
				sets["circuit_break_scope"] = *v
			}
			if v := body.Enabled; v != nil {
				sets["enabled"] = *v
			}
			sets["updated_at"] = now
			q, args := store.BuildUpdate("circuit_breaker_configs", sets, "model_id = ?", id)
			_, _ = d.Store.Exec(ctx, q, args...)
		} else {
			d.Store.Insert(ctx,
				"INSERT INTO circuit_breaker_configs (model_id, retry_count, circuit_break_duration, circuit_break_scope, enabled, created_at, updated_at) VALUES (?, ?, ?, ?, 1, ?, ?)",
				id, body.RetryCount, body.CircuitBreakDuration, body.CircuitBreakScope, now, now)
		}
		httpx.OK(c, httpx.NewOrderedMap().Set("success", true))
	})
}

// ---------------------------------------------------------------------------
// Model helpers
// ---------------------------------------------------------------------------

func modelsCamelToSnake(s string) string {
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

func resolveModelRels(ctx context.Context, st *store.Store, modelID int64, visited map[int64]bool) []models.ModelChannelRel {
	if visited[modelID] {
		return nil
	}
	visited[modelID] = true

	mdl, err := st.QueryOne(ctx, "SELECT * FROM models WHERE id = ?", modelID)
	if err != nil {
		return nil
	}
	m := store.RowToModel(mdl)

	if derefStr(m.RelMode, "") == "inherit" && m.InheritFromModelID != nil {
		return resolveModelRels(ctx, st, *m.InheritFromModelID, visited)
	}

	rels, _ := st.Query(ctx, "SELECT * FROM model_channel_rels WHERE model_id = ? ORDER BY sort_order ASC, created_at ASC", modelID)
	out := make([]models.ModelChannelRel, 0, len(rels))
	for _, r := range rels {
		rel := store.RowToRel(r)
		cm, _ := st.QueryOne(ctx, "SELECT * FROM channel_models WHERE id = ?", *rel.ChannelModelID)
		if cm != nil {
			rel.ChannelModelName = cm.StrPtr("model_name")
			rel.Input = cm.StrPtr("input")
			rel.ContextLength = cm.I64Ptr("context_length")
			ch, _ := st.QueryOne(ctx, "SELECT * FROM channels WHERE id = ?", cm.I64("channel_id", 0))
			if ch != nil {
				rel.ChannelName = ch.StrPtr("name")
				rel.ChannelType = ch.StrPtr("channel_type")
				rel.ChannelID = int64Ptr(ch.I64("id", 0))
				rel.ChannelEnabled = ch.IntPtr("enabled")
			}
			// API key availability
			akID := cm.IntPtr("channel_api_key_id")
			if akID != nil {
				ak, _ := st.QueryOne(ctx, "SELECT id FROM channel_api_keys WHERE id=? AND enabled=1", *akID)
				if ak != nil {
					rel.APIKeyAvailable = models.Int(1)
				} else {
					rel.APIKeyAvailable = models.Int(0)
				}
			} else {
				aks, _ := st.QueryOne(ctx, "SELECT id FROM channel_api_keys WHERE channel_id=? AND enabled=1 LIMIT 1", cm.I64("channel_id", 0))
				if aks != nil {
					rel.APIKeyAvailable = models.Int(1)
				} else {
					rel.APIKeyAvailable = models.Int(0)
				}
			}
		}
		out = append(out, rel)
	}
	return out
}

// resolveModelGroupRels lists the entry model's group relations (inheritance
// aware), enriched with the group's name/strategy and member counts. It mirrors
// resolveModelRels so both relation kinds follow the same inherit semantics.
func resolveModelGroupRels(ctx context.Context, st *store.Store, modelID int64, visited map[int64]bool) []models.ModelGroupRel {
	if visited[modelID] {
		return nil
	}
	visited[modelID] = true

	mdl, err := st.QueryOne(ctx, "SELECT * FROM models WHERE id = ?", modelID)
	if err != nil {
		return nil
	}
	m := store.RowToModel(mdl)
	if derefStr(m.RelMode, "") == "inherit" && m.InheritFromModelID != nil {
		return resolveModelGroupRels(ctx, st, *m.InheritFromModelID, visited)
	}

	rows, _ := st.Query(ctx,
		`SELECT rel.*, g.name AS group_name, g.strategy AS group_strategy, g.sticky AS group_sticky,
		        g.enabled AS group_enabled, g.description AS group_description
		   FROM model_group_rels rel
		   JOIN model_groups g ON g.id = rel.group_id
		  WHERE rel.model_id = ?
		  ORDER BY rel.sort_order ASC, rel.created_at ASC`, modelID)
	out := make([]models.ModelGroupRel, 0, len(rows))
	for _, r := range rows {
		rel := store.RowToModelGroupRel(r)
		if rel.GroupID == nil {
			continue
		}
		summary := groupMemberSummaryOf(ctx, st, *rel.GroupID)
		rel.MemberCount = models.Int(summary.total)
		rel.AvailableCount = models.Int(summary.routable)
		rel.BrokenCount = models.Int(summary.brokenCount)
		rel.Input = summary.input
		rel.MaxContextLength = summary.maxContext
		rel.TTFTMs = summary.ttftMs
		rel.SampleCount = summary.sampleCount
		rel.OutputSpeed = summary.outputSpeed
		if summary.breakerAggregate == "all" {
			rel.CircuitBroken = models.Int(1)
			if summary.breakerScope != "" {
				rel.CircuitBrokenScope = models.Str(summary.breakerScope)
			}
		}
		rel.MemberModelNames = summary.modelNames
		out = append(out, rel)
	}
	return out
}

// groupMemberSummary 汇总一个小组的成员能力摘要，供入口模型关联列表展示。
//
// total 是启用成员数；routable 是静态可路由成员数，口径与 relay 的
// expandChannelModel 一致：成员启用 + 渠道模型启用 + 渠道启用 + 有可用 API Key
// （绑定 Key 需该 Key 启用；未绑定则渠道下至少一枚启用 Key）。不含请求级的
// 熔断/媒体/上下文跳过——那些依赖请求内容，不能作为无请求上下文的固定统计。
// input 是可路由成员输入模态并集（Go 内去重、text 优先）；maxContext 是其中的
// 最大正值上下文，全部未知时为 null。
type groupMemberSummary struct {
	total      int
	routable   int
	input      *string
	maxContext *int64
	// modelNames 是可路由成员的上游模型名（按成员 sort_order 去重），供入口模型
	// 关联列表的小组行在「模型」列逐行展示。
	modelNames []string
	// 成员性能聚合（24h 日志口径，与渠道模型行列一致）：
	// ttftMs/outputSpeed 是各成员最近样本的总体平均；无样本时为 nil。
	ttftMs      *int64
	sampleCount *int
	outputSpeed *float64
	// breakerAggregate：routable 全部熔断时为 "all"，部分熔断为 "partial"，
	// 无可路由成员或全部正常为 ""。
	breakerAggregate string
	// brokenCount 是可路由成员中处于熔断状态的个数，随 AvailableCount 一起返回，
	// 供小组行展示「熔断成员数/成员总数」。
	brokenCount int
	// breakerScope：全部熔断时成员熔断级别的聚合结果，"model" | "channel" | "both"；
	// 多级别混合时取最广的一档（both > channel > model）。未全熔断时为空。
	breakerScope string
}

func groupMemberSummaryOf(ctx context.Context, st *store.Store, groupID int64) groupMemberSummary {
	var out groupMemberSummary
	rows, _ := st.Query(ctx,
		`SELECT cm.id, cm.channel_id, cm.model_name, cm.input, cm.context_length, c.name AS channel_name,
		        cm.enabled = 1 AND c.enabled = 1
		          AND ((cm.channel_api_key_id IS NOT NULL AND EXISTS (
		                  SELECT 1 FROM channel_api_keys k
		                   WHERE k.id = cm.channel_api_key_id AND k.enabled = 1))
		            OR (cm.channel_api_key_id IS NULL AND EXISTS (
		                  SELECT 1 FROM channel_api_keys k
		                   WHERE k.channel_id = cm.channel_id AND k.enabled = 1))) AS routable
		   FROM model_group_members m
		   LEFT JOIN channel_models cm ON cm.id = m.channel_model_id
		   LEFT JOIN channels c ON c.id = cm.channel_id
		  WHERE m.group_id = ? AND m.enabled = 1`, groupID)
	out.total = len(rows)

	inputSeen := map[string]bool{}
	seenModelName := map[string]bool{}
	var maxContext int64
	var routableMembers []groupMemberRef
	for _, r := range rows {
		if r.Int("routable", 0) != 1 {
			continue
		}
		out.routable++
		routableMembers = append(routableMembers, groupMemberRef{
			cmID:        r.I64("id", 0),
			chID:        r.I64("channel_id", 0),
			channelName: r.Str("channel_name"),
			modelName:   r.Str("model_name"),
		})
		if name := r.Str("model_name"); name != "" && !seenModelName[name] {
			seenModelName[name] = true
			out.modelNames = append(out.modelNames, name)
		}
		if input := r.Str("input"); input != "" {
			for _, t := range strings.Split(input, ",") {
				t = strings.TrimSpace(t)
				if t != "" && !inputSeen[t] {
					inputSeen[t] = true
				}
			}
		}
		if ctxLen := r.I64("context_length", 0); ctxLen > maxContext {
			maxContext = ctxLen
		}
	}
	if len(inputSeen) > 0 {
		ordered := orderedInputTypes(inputSeen)
		out.input = models.Str(strings.Join(ordered, ","))
	}
	if maxContext > 0 {
		out.maxContext = &maxContext
	}

	// 成员性能聚合：与渠道模型行同口径（computeRelStats 的 24h 日志、每成员最多
	// 30 个样本），把成员均值再总体平均一次，作为小组整体的参考值。
	if len(routableMembers) > 0 {
		out.ttftMs, out.sampleCount, out.outputSpeed = computeMemberPerfStats(ctx, st, routableMembers)

		// 熔断聚合：只有全部可路由成员都熔断时，小组才算「熔断中」；部分熔断时
		// 组内仍有可用候选，不告警。
		cmIDs := make([]int64, 0, len(routableMembers))
		for _, m := range routableMembers {
			cmIDs = append(cmIDs, m.cmID)
		}
		lookup := loadBreakerLookup(ctx, st, cmIDs)
		brokenCount := 0
		scopeSet := map[string]bool{}
		for _, m := range routableMembers {
			mark := lookup.mark(m.cmID, m.chID)
			if mark == nil {
				continue
			}
			brokenCount++
			if mark.Scope != "" {
				scopeSet[mark.Scope] = true
			}
		}
		out.brokenCount = brokenCount
		if brokenCount == len(routableMembers) {
			out.breakerAggregate = "all"
			out.breakerScope = mergeBreakerScopes(scopeSet)
		} else if brokenCount > 0 {
			out.breakerAggregate = "partial"
		}
	}
	return out
}

// mergeBreakerScopes 把组内各成员的熔断级别合并成一个展示级别。
//
// 成员的熔断状态按 (渠道, 渠道模型, Key) 独立判定，组内可能混着「模型级」与
// 「渠道级」。对使用者而言最有用的信息是影响面：只要有一个成员是因为整条渠道
// 被熔断，小组就不该号称只是模型级。因此取包含关系最广的一档。
func mergeBreakerScopes(scopes map[string]bool) string {
	switch {
	case scopes["both"]:
		return "both"
	case scopes["channel"]:
		return "channel"
	case scopes["model"]:
		return "model"
	default:
		return ""
	}
}

// computeMemberPerfStats 汇总一组渠道模型的 24h 性能样本，口径与 computeRelStats
// 一致（每成员 TTFT/速度各取最近 30 个样本）。
//
// request_logs 只记录「渠道名 + 渠道模型名」，没有 channel_model_id 列，因此这里
// 与 computeRelStats 一样按名称二元组聚合，而不是按渠道模型 ID。两个成员若指向
// 同一个「渠道名||模型名」，它们的样本会被合并统计——与渠道模型行的口径保持一致。
type groupMemberRef struct {
	cmID, chID int64
	// channelName / modelName 是 request_logs 的归属键；任一为空表示无法统计。
	channelName string
	modelName   string
}

func computeMemberPerfStats(ctx context.Context, st *store.Store, members []groupMemberRef) (ttftMs *int64, sampleCount *int, outputSpeed *float64) {
	if len(members) == 0 {
		return nil, nil, nil
	}
	// 先按渠道名收窄扫描范围（channel_name 上有索引），再在 Go 内按名称二元组精确归组。
	seenKey := map[string]bool{}
	args := make([]any, 0, len(members))
	for _, m := range members {
		if m.channelName == "" || m.modelName == "" {
			continue
		}
		key := m.channelName + "||" + m.modelName
		if seenKey[key] {
			continue
		}
		seenKey[key] = true
		args = append(args, m.channelName)
	}
	if len(seenKey) == 0 {
		return nil, nil, nil
	}
	placeholders := strings.TrimSuffix(strings.Repeat("?,", len(args)), ",")
	since := jtime.FormatApp(time.Now().UTC().Add(-24 * time.Hour))
	queryArgs := append(append([]any{}, args...), since)
	rows, _ := st.Query(ctx,
		`SELECT channel_name, channel_model_name, first_byte_ms, completion_tokens, response_time_ms
		   FROM request_logs
		  WHERE channel_name IN (`+placeholders+`)
		    AND phase IN ('success','fail')
		    AND channel_model_name IS NOT NULL AND channel_model_name != ''
		    AND first_byte_ms IS NOT NULL AND first_byte_ms > 0
		    AND created_at >= ?
		  ORDER BY created_at DESC`, queryArgs...)

	// 每成员独立限 30 个样本，与渠道模型行的统计口径一致。
	fbmCount := make(map[string]int)
	spdCount := make(map[string]int)
	var fbmSum int64
	var fbmTotal int
	var spdSum float64
	var spdTotal int
	for _, r := range rows {
		key := r.Str("channel_name") + "||" + r.Str("channel_model_name")
		if !seenKey[key] {
			continue
		}
		if fbmCount[key] < 30 {
			fbmSum += r.I64("first_byte_ms", 0)
			fbmCount[key]++
			fbmTotal++
		}
		if spdCount[key] < 30 && r.Int("completion_tokens", 0) > 0 && r.Int("response_time_ms", 0) > 0 {
			spdSum += float64(r.I64("completion_tokens", 0)) * 1000.0 / float64(r.I64("response_time_ms", 0))
			spdCount[key]++
			spdTotal++
		}
	}
	if fbmTotal > 0 {
		avg := float64(fbmSum) / float64(fbmTotal)
		ttftMs = int64Ptr(int64(math.Round(avg)))
		sampleCount = models.Int(fbmTotal)
	}
	if spdTotal > 0 {
		spd := float64(int64(spdSum/float64(spdTotal)*10+0.5)) / 10.0
		outputSpeed = &spd
	}
	return ttftMs, sampleCount, outputSpeed
}

// orderedInputTypes 把模态集合规范成 text 优先的稳定顺序，其余按固定次序追加，
// 未知模态按字母序殿后，保证同一集合总是渲染出同一串标签。
func orderedInputTypes(seen map[string]bool) []string {
	canonical := []string{"text", "image", "video", "audio"}
	var rest []string
	var out []string
	for _, t := range canonical {
		if seen[t] {
			out = append(out, t)
			delete(seen, t)
		}
	}
	for t := range seen {
		rest = append(rest, t)
	}
	sort.Strings(rest)
	return append(out, rest...)
}

// resolveModelGroups lists the groups that can still be added to an entry model,
// i.e. every enabled group not already related to it.
func resolveModelGroups(ctx context.Context, st *store.Store, modelID int64) []models.ModelGroup {
	rows, _ := st.Query(ctx,
		`SELECT * FROM model_groups g
		  WHERE g.enabled = 1
		    AND NOT EXISTS (
		        SELECT 1 FROM model_group_rels rel
		         WHERE rel.model_id = ? AND rel.group_id = g.id)
		  ORDER BY g.name ASC`, modelID)
	out := make([]models.ModelGroup, 0, len(rows))
	for _, r := range rows {
		grp := store.RowToModelGroup(r)
		summary := groupMemberSummaryOf(ctx, st, grp.ID)
		grp.MemberCount = models.Int(summary.total)
		out = append(out, grp)
	}
	return out
}

// relPerfStat 是一个「渠道名||渠道模型名」的 24h 性能均值。
type relPerfStat struct {
	ttftMs      int64
	sampleCount int
	outputSpeed *float64
}

// loadRelPerfStats 聚合 24h 性能样本，键为「渠道名||渠道模型名」。
//
// request_logs 没有 channel_model_id 列，样本只能按名称二元组归属，所以入口模型
// 关联行与小组成员行都从这里取数——两处展示的是同一个渠道模型的同一份统计。
// 每个键最多取最近 30 条 TTFT 样本（按时间倒序扫描），速度样本单独计数。
func loadRelPerfStats(ctx context.Context, st *store.Store) map[string]relPerfStat {
	since := jtime.FormatApp(time.Now().UTC().Add(-24 * time.Hour))
	logRows, _ := st.Query(ctx,
		`SELECT channel_name, channel_model_name, first_byte_ms, completion_tokens, response_time_ms
		  FROM request_logs
		 WHERE phase IN ('success','fail')
		   AND channel_name IS NOT NULL AND channel_model_name IS NOT NULL
		   AND channel_name != '' AND channel_model_name != ''
		   AND first_byte_ms IS NOT NULL AND first_byte_ms > 0
		   AND created_at >= ?
		 ORDER BY created_at DESC`, since)

	type statsAccum struct {
		fbmSum   int64
		fbmCount int
		spdSum   float64
		spdCount int
	}
	accums := make(map[string]*statsAccum)
	for _, r := range logRows {
		key := r.Str("channel_name") + "||" + r.Str("channel_model_name")
		a := accums[key]
		if a == nil {
			a = &statsAccum{}
			accums[key] = a
		}
		if a.fbmCount < 30 {
			a.fbmSum += r.I64("first_byte_ms", 0)
			a.fbmCount++
		}
		if a.spdCount < 30 && r.Int("completion_tokens", 0) > 0 && r.Int("response_time_ms", 0) > 0 {
			a.spdSum += float64(r.I64("completion_tokens", 0)) * 1000.0 / float64(r.I64("response_time_ms", 0))
			a.spdCount++
		}
	}

	out := make(map[string]relPerfStat, len(accums))
	for key, a := range accums {
		if a.fbmCount == 0 {
			continue
		}
		stat := relPerfStat{
			ttftMs:      int64(math.Round(float64(a.fbmSum) / float64(a.fbmCount))),
			sampleCount: a.fbmCount,
		}
		if a.spdCount > 0 {
			spd := float64(int64(a.spdSum/float64(a.spdCount)*10+0.5)) / 10.0
			stat.outputSpeed = &spd
		}
		out[key] = stat
	}
	return out
}

func computeRelStats(ctx context.Context, st *store.Store, rels []models.ModelChannelRel) []models.ModelChannelRel {
	if len(rels) == 0 {
		return rels
	}
	stats := loadRelPerfStats(ctx, st)
	result := make([]models.ModelChannelRel, len(rels))
	for i, rel := range rels {
		key := derefStr(rel.ChannelName, "") + "||" + derefStr(rel.ChannelModelName, "")
		if stat, ok := stats[key]; ok {
			ttft := stat.ttftMs
			rel.TTFTMs = &ttft
			rel.SampleCount = models.Int(stat.sampleCount)
			rel.OutputSpeed = stat.outputSpeed
		}
		result[i] = rel
	}
	return result
}

// applyRelBrokenMarks mirrors AdminModelController.computeRelBrokenMarks: fill
// circuitBroken / circuitBrokenScope / circuitBrokenExpireAt for each rel.
func applyRelBrokenMarks(ctx context.Context, st *store.Store, rels []models.ModelChannelRel) {
	if len(rels) == 0 {
		return
	}
	cmIDs := make([]int64, 0, len(rels))
	seenCM := map[int64]bool{}
	for _, rel := range rels {
		if rel.ChannelModelID != nil && !seenCM[*rel.ChannelModelID] {
			seenCM[*rel.ChannelModelID] = true
			cmIDs = append(cmIDs, *rel.ChannelModelID)
		}
	}
	lookup := loadBreakerLookup(ctx, st, cmIDs)

	for i := range rels {
		rel := &rels[i]
		if rel.ChannelModelID == nil || rel.ChannelID == nil {
			continue
		}
		mark := lookup.mark(*rel.ChannelModelID, *rel.ChannelID)
		if mark == nil {
			continue
		}
		rel.CircuitBroken = models.Int(1)
		rel.CircuitBrokenScope = models.Str(mark.Scope)
		if mark.ExpireAt != nil {
			rel.CircuitBrokenExpireAt = jtime.NewAPITime(*mark.ExpireAt)
		}
		if mark.LastProbeAt != nil {
			rel.LastProbeAt = jtime.NewAPITime(*mark.LastProbeAt)
		}
		rel.LastProbeStatus = mark.LastProbeStatus
		rel.LastProbeDetail = mark.LastProbeDetail
		rel.CircuitBrokenProtocols = apiKeyProtocols(mark)
	}
}

// applyGroupMemberBrokenMarks fills the same breaker display fields on group
// members. The evaluation is identical to the entry-model relation list: breaker
// state is keyed by (channel, channel model, API key) and carries no entry-model
// identity, so a member shows the same status wherever it is referenced.
func applyGroupMemberBrokenMarks(ctx context.Context, st *store.Store, members []models.ModelGroupMember) {
	if len(members) == 0 {
		return
	}
	cmIDs := make([]int64, 0, len(members))
	seenCM := map[int64]bool{}
	for _, m := range members {
		if m.ChannelModelID != nil && !seenCM[*m.ChannelModelID] {
			seenCM[*m.ChannelModelID] = true
			cmIDs = append(cmIDs, *m.ChannelModelID)
		}
	}
	lookup := loadBreakerLookup(ctx, st, cmIDs)

	for i := range members {
		m := &members[i]
		if m.ChannelModelID == nil || m.ChannelID == nil {
			continue
		}
		mark := lookup.mark(*m.ChannelModelID, *m.ChannelID)
		if mark == nil {
			continue
		}
		m.CircuitBroken = models.Int(1)
		m.CircuitBrokenScope = models.Str(mark.Scope)
		if mark.ExpireAt != nil {
			m.CircuitBrokenExpireAt = jtime.NewAPITime(*mark.ExpireAt)
		}
		if mark.LastProbeAt != nil {
			m.LastProbeAt = jtime.NewAPITime(*mark.LastProbeAt)
		}
		m.LastProbeStatus = mark.LastProbeStatus
		m.LastProbeDetail = mark.LastProbeDetail
		m.CircuitBrokenProtocols = apiKeyProtocols(mark)
	}
}

// apiKeyProtocols converts the breaker mark's per-key protocols for the API shape.
func apiKeyProtocols(mark *circuit.RelBrokenMark) []models.APIKeyProtocol {
	if len(mark.ProtocolsByKey) == 0 {
		return nil
	}
	out := make([]models.APIKeyProtocol, 0, len(mark.ProtocolsByKey))
	for _, kp := range mark.ProtocolsByKey {
		out = append(out, models.APIKeyProtocol{KeyID: kp.KeyID, KeyName: kp.KeyName, Protocol: kp.Protocol})
	}
	return out
}

// breakerLookup preloads everything needed to evaluate breaker display marks for
// a set of channel models: open states on their channels, each channel model's
// bound key, and each channel's enabled keys. Sharing it across the relation list
// and the group member list keeps both views consistent and avoids the N+1
// lookups a per-row evaluation would need.
type breakerLookup struct {
	states               []circuit.CircuitBreakerState
	cmByID               map[int64]store.Row
	enabledKeysByChannel map[int64][]circuit.KeyRef
	boundKeyIDs          map[int64]circuit.KeyRef
}

func loadBreakerLookup(ctx context.Context, st *store.Store, cmIDs []int64) *breakerLookup {
	l := &breakerLookup{
		cmByID:               map[int64]store.Row{},
		enabledKeysByChannel: map[int64][]circuit.KeyRef{},
		boundKeyIDs:          map[int64]circuit.KeyRef{},
	}
	if len(cmIDs) == 0 {
		return l
	}
	cmIDStrs := make([]string, 0, len(cmIDs))
	for _, id := range cmIDs {
		cmIDStrs = append(cmIDStrs, int64Str(id))
	}
	cmInList := strings.Join(cmIDStrs, ",")
	rows, _ := st.Query(ctx, "SELECT id, channel_id, channel_api_key_id FROM channel_models WHERE id IN ("+cmInList+")")
	channelIDs := make([]string, 0, len(rows))
	seenCh := map[int64]bool{}
	for _, row := range rows {
		l.cmByID[row.I64("id", 0)] = row
		if chID := row.I64("channel_id", 0); !seenCh[chID] {
			seenCh[chID] = true
			channelIDs = append(channelIDs, int64Str(chID))
		}
	}
	if len(channelIDs) == 0 {
		return l
	}
	chInList := strings.Join(channelIDs, ",")

	// Load every open breaker state touching these channels (is_open=1; expiry is
	// irrelevant — only a successful probe opens the gate).
	stateRows, _ := st.Query(ctx, "SELECT * FROM circuit_breaker_states WHERE channel_id IN ("+chInList+") AND is_open = 1")
	for _, row := range stateRows {
		l.states = append(l.states, circuitStateFromRow(row))
	}
	keyRows, _ := st.Query(ctx, "SELECT id, channel_id, key_name FROM channel_api_keys WHERE channel_id IN ("+chInList+") AND enabled = 1")
	for _, row := range keyRows {
		chID := row.I64("channel_id", 0)
		l.enabledKeysByChannel[chID] = append(l.enabledKeysByChannel[chID],
			circuit.KeyRef{ID: row.I64("id", 0), Enabled: true, Name: row.Str("key_name")})
	}
	for _, row := range rows {
		if kID := row.I64Ptr("channel_api_key_id"); kID != nil {
			enabled := false
			name := ""
			if kRow, err := st.QueryOne(ctx, "SELECT enabled, key_name FROM channel_api_keys WHERE id = ?", *kID); err == nil {
				enabled = kRow.Int("enabled", 0) == 1
				name = kRow.Str("key_name")
			}
			l.boundKeyIDs[*kID] = circuit.KeyRef{ID: *kID, Enabled: enabled, Name: name}
		}
	}
	return l
}

// mark evaluates the breaker display mark for one (channel, channel model) pair.
func (l *breakerLookup) mark(channelModelID, channelID int64) *circuit.RelBrokenMark {
	var relKeyID *int64
	if cm, ok := l.cmByID[channelModelID]; ok {
		relKeyID = cm.I64Ptr("channel_api_key_id")
	}
	return circuit.EvaluateRelBroken(channelModelID, channelID, relKeyID,
		l.states, l.enabledKeysByChannel[channelID], l.boundKeyIDs)
}

// clearChannelModelBreaker reopens a channel model's breaker paths by deleting its
// circuit_breaker_states rows. A channel model bound to a key is judged per key,
// otherwise every open state on the channel model is cleared. Returns rows removed.
func clearChannelModelBreaker(ctx context.Context, st *store.Store, cmID int64) int64 {
	cmRow, _ := st.QueryOne(ctx, "SELECT channel_id, channel_api_key_id FROM channel_models WHERE id = ?", cmID)
	if cmRow == nil {
		return 0
	}
	chID := cmRow.I64("channel_id", 0)
	if akID := cmRow.IntPtr("channel_api_key_id"); akID != nil {
		r, _ := st.Exec(ctx, "DELETE FROM circuit_breaker_states WHERE channel_model_id=? AND channel_id=? AND channel_api_key_id=?", cmID, chID, *akID)
		return r
	}
	r, _ := st.Exec(ctx, "DELETE FROM circuit_breaker_states WHERE channel_model_id=? AND channel_id=?", cmID, chID)
	return r
}

func int64Str(n int64) string { return strconv.FormatInt(n, 10) }

// circuitStateFromRow converts a circuit_breaker_states row for the breaker
// evaluation helpers.
func circuitStateFromRow(r store.Row) circuit.CircuitBreakerState {
	return circuit.CircuitBreakerState{
		ID:              r.I64("id", 0),
		ChannelID:       r.I64("channel_id", 0),
		ChannelAPIKeyID: r.I64Ptr("channel_api_key_id"),
		ChannelModelID:  r.I64Ptr("channel_model_id"),
		IsOpen:          r.Int("is_open", 0),
		FailCount:       r.Int("fail_count", 0),
		OpenedAt:        jtime.ScanTime(r["opened_at"]),
		ExpireAt:        jtime.ScanTime(r["expire_at"]),
		Protocol:        r.Str("protocol"),
		LastProbeAt:     jtime.ScanTime(r["last_probe_at"]),
		LastProbeStatus: r.IntPtr("last_probe_status"),
		LastProbeDetail: r.StrPtr("last_probe_detail"),
	}
}

func findCycleClosing(ctx context.Context, st *store.Store, fromModelID int64, visited map[int64]bool) *models.Model {
	if fromModelID == 0 {
		return nil
	}
	row, err := st.QueryOne(ctx, "SELECT * FROM models WHERE id = ?", fromModelID)
	if err != nil {
		return nil
	}
	m := store.RowToModel(row)
	if derefStr(m.RelMode, "") != "inherit" || m.InheritFromModelID == nil {
		return nil
	}
	next := *m.InheritFromModelID
	if visited[next] {
		return &m
	}
	visited[next] = true
	return findCycleClosing(ctx, st, next, visited)
}

// parseRelRef 解析前端提交的关联引用。两类关联共用一套排序接口，
// 用前缀区分来源："cm:<id>" = model_channel_rels，"g:<id>" = model_group_rels；
// 裸数字视为渠道模型关联（兼容旧版前端）。
func parseRelRef(v any) (kind string, id int64) {
	switch x := v.(type) {
	case float64:
		return "cm", int64(x)
	case string:
		raw := strings.TrimSpace(x)
		if raw == "" {
			return "", 0
		}
		if i := strings.Index(raw, ":"); i > 0 {
			kind = strings.ToLower(strings.TrimSpace(raw[:i]))
			n, err := strconv.ParseInt(strings.TrimSpace(raw[i+1:]), 10, 64)
			if err != nil || n <= 0 {
				return "", 0
			}
			if kind != "g" {
				kind = "cm"
			}
			return kind, n
		}
		n, err := strconv.ParseInt(raw, 10, 64)
		if err != nil || n <= 0 {
			return "", 0
		}
		return "cm", n
	}
	return "", 0
}

// applyCombinedRelOrder 按提交顺序给两类关联统一编号：序号写入各自的 sort_order，
// 路由时再按同一序号空间合并成候选队列，因此入口模型列表里直连关联与小组的
// 前后顺序就是实际尝试顺序。
func applyCombinedRelOrder(ctx context.Context, st *store.Store, refs []string) {
	order := 0
	for _, ref := range refs {
		kind, id := parseRelRef(strings.TrimSpace(ref))
		if id == 0 {
			continue
		}
		if kind == "g" {
			st.Exec(ctx, "UPDATE model_group_rels SET sort_order=? WHERE id=?", order, id)
		} else {
			st.Exec(ctx, "UPDATE model_channel_rels SET sort_order=? WHERE id=?", order, id)
		}
		order++
	}
}
