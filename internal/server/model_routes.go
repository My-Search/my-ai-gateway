package server

import (
	"context"
	"fmt"
	"math"
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

		httpx.OK(c, httpx.NewOrderedMap().
			Set("model", model).
			Set("rels", relStats).
			Set("availableModels", availModels).
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
		cmRow, _ := d.Store.QueryOne(ctx, "SELECT channel_id, channel_api_key_id FROM channel_models WHERE id = ?", relRow.I64("channel_model_id", 0))
		var recovered int64
		if cmRow != nil {
			chID := cmRow.I64("channel_id", 0)
			akID := cmRow.IntPtr("channel_api_key_id")
			if akID != nil {
				r, _ := d.Store.Exec(ctx, "DELETE FROM circuit_breaker_states WHERE channel_model_id=? AND channel_id=? AND channel_api_key_id=?", relRow.I64("channel_model_id", 0), chID, *akID)
				recovered = r
			} else {
				r, _ := d.Store.Exec(ctx, "DELETE FROM circuit_breaker_states WHERE channel_model_id=? AND channel_id=?", relRow.I64("channel_model_id", 0), chID)
				recovered = r
			}
		}
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

		row, err := d.Store.QueryOne(ctx, "SELECT * FROM models WHERE id = ?", id)
		if err != nil {
			httpx.OK(c, failureEnvelope("模型不存在"))
			return
		}
		m := store.RowToModel(row)

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
				srcRow, _ := d.Store.QueryOne(ctx, "SELECT model_name FROM models WHERE id=?", closing.ID)
				cycleBrokenModel = &m
				if srcRow != nil {
					cycleBrokenModel.ModelName = srcRow.Str("model_name")
				}
				cycleBrokenModel.RelMode = models.Str("self_add")
				cycleBrokenModel.InheritFromModelID = nil
			}
			now := jtime.FormatApp(time.Now().UTC())
			d.Store.Exec(ctx, "UPDATE models SET rel_mode='inherit', inherit_from_model_id=?, updated_at=? WHERE id=?", *body.SourceModelID, now, id)
		} else {
			now := jtime.FormatApp(time.Now().UTC())
			d.Store.Exec(ctx, "UPDATE models SET rel_mode='self_add', inherit_from_model_id=NULL, updated_at=? WHERE id=?", now, id)
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

		// Get next sort order
		last, _ := d.Store.QueryOne(ctx, "SELECT sort_order FROM model_channel_rels WHERE model_id=? ORDER BY sort_order DESC LIMIT 1", modelID)
		nextSort := int64(0)
		if last != nil {
			nextSort = last.I64("sort_order", 0) + 1
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

		// Apply sortedRelIds if present (reorder)
		if body.SortedRelIDs != "" {
			ids := strings.Split(body.SortedRelIDs, ",")
			for i, sid := range ids {
				sid = strings.TrimSpace(sid)
				if sid == "" {
					continue
				}
				relID, _ := strconv.ParseInt(sid, 10, 64)
				if relID > 0 {
					d.Store.Exec(ctx, "UPDATE model_channel_rels SET sort_order=? WHERE id=?", int64(i), relID)
				}
			}
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
	g.POST("/models/rels/batch-delete", func(c *gin.Context) {
		ctx := c.Request.Context()
		var body struct {
			RelIDs []any `json:"relIds"`
		}
		if err := c.ShouldBindJSON(&body); err != nil || len(body.RelIDs) == 0 {
			httpx.OK(c, failureEnvelope("relIds 不能为空"))
			return
		}

		for _, rid := range body.RelIDs {
			var relID int64
			switch v := rid.(type) {
			case float64:
				relID = int64(v)
			default:
				continue
			}
			relRow, err := d.Store.QueryOne(ctx, "SELECT model_id FROM model_channel_rels WHERE id = ?", relID)
			if err != nil {
				continue
			}
			modelRow, _ := d.Store.QueryOne(ctx, "SELECT rel_mode, model_name FROM models WHERE id=?", relRow.I64("model_id", 0))
			if modelRow != nil && modelRow.Str("rel_mode") == "inherit" {
				continue
			}
			d.Store.Exec(ctx, "DELETE FROM model_channel_rels WHERE id = ?", relID)
		}
		httpx.OK(c, httpx.NewOrderedMap().Set("success", true).Set("count", len(body.RelIDs)))
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
	g.PUT("/models/rels/sort", func(c *gin.Context) {
		ctx := c.Request.Context()
		var body struct {
			SortedRelIDs []any `json:"sortedRelIds"`
		}
		if err := c.ShouldBindJSON(&body); err != nil {
			httpx.OK(c, failureEnvelope("请求参数错误"))
			return
		}
		for i, rid := range body.SortedRelIDs {
			var relID int64
			switch v := rid.(type) {
			case float64:
				relID = int64(v)
			default:
				continue
			}
			d.Store.Exec(ctx, "UPDATE model_channel_rels SET sort_order=? WHERE id=?", i, relID)
		}
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

func computeRelStats(ctx context.Context, st *store.Store, rels []models.ModelChannelRel) []models.ModelChannelRel {
	if len(rels) == 0 {
		return rels
	}

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

	result := make([]models.ModelChannelRel, len(rels))
	for i, rel := range rels {
		key := derefStr(rel.ChannelName, "") + "||" + derefStr(rel.ChannelModelName, "")
		if a, ok := accums[key]; ok && a.fbmCount > 0 {
			avg := float64(a.fbmSum) / float64(a.fbmCount)
			rel.TTFTMs = int64Ptr(int64(math.Round(avg)))
			rel.SampleCount = models.Int(a.fbmCount)
			if a.spdCount > 0 {
				spd := float64(int64(a.spdSum/float64(a.spdCount)*10+0.5)) / 10.0
				rel.OutputSpeed = &spd
			}
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
	channelIDs := make([]string, 0, len(rels))
	seenCh := map[int64]bool{}
	cmIDs := make([]string, 0, len(rels))
	seenCM := map[int64]bool{}
	for _, rel := range rels {
		if rel.ChannelID != nil && !seenCh[*rel.ChannelID] {
			seenCh[*rel.ChannelID] = true
			channelIDs = append(channelIDs, int64Str(*rel.ChannelID))
		}
		if rel.ChannelModelID != nil && !seenCM[*rel.ChannelModelID] {
			seenCM[*rel.ChannelModelID] = true
			cmIDs = append(cmIDs, int64Str(*rel.ChannelModelID))
		}
	}

	// Load every open breaker state touching these channels (is_open=1; expiry
	// is irrelevant — only a successful probe opens the gate).
	var states []circuit.CircuitBreakerState
	if len(channelIDs) > 0 {
		rows, _ := st.Query(ctx,
			"SELECT * FROM circuit_breaker_states WHERE channel_id IN ("+strings.Join(channelIDs, ",")+") AND is_open = 1")
		for _, row := range rows {
			states = append(states, circuitStateFromRow(row))
		}
	}
	_ = cmIDs

	// Channel models (bound key) and enabled keys per channel.
	cmByID := map[int64]store.Row{}
	if len(cmIDs) > 0 {
		rows, _ := st.Query(ctx, "SELECT id, channel_api_key_id FROM channel_models WHERE id IN ("+strings.Join(cmIDs, ",")+")")
		for _, row := range rows {
			cmByID[row.I64("id", 0)] = row
		}
	}
	enabledKeysByChannel := map[int64][]circuit.KeyRef{}
	if len(channelIDs) > 0 {
		rows, _ := st.Query(ctx, "SELECT id, channel_id FROM channel_api_keys WHERE channel_id IN ("+strings.Join(channelIDs, ",")+") AND enabled = 1")
		for _, row := range rows {
			chID := row.I64("channel_id", 0)
			enabledKeysByChannel[chID] = append(enabledKeysByChannel[chID], circuit.KeyRef{ID: row.I64("id", 0), Enabled: true})
		}
	}
	boundKeyIDs := map[int64]circuit.KeyRef{}
	for _, rel := range rels {
		if rel.ChannelModelID == nil {
			continue
		}
		cm, ok := cmByID[*rel.ChannelModelID]
		if !ok {
			continue
		}
		if kID := cm.I64Ptr("channel_api_key_id"); kID != nil {
			enabled := false
			if kRow, err := st.QueryOne(ctx, "SELECT enabled FROM channel_api_keys WHERE id = ?", *kID); err == nil {
				enabled = kRow.Int("enabled", 0) == 1
			}
			boundKeyIDs[*kID] = circuit.KeyRef{ID: *kID, Enabled: enabled}
		}
	}

	for i := range rels {
		rel := &rels[i]
		if rel.ChannelModelID == nil || rel.ChannelID == nil {
			continue
		}
		var relKeyID *int64
		if cm, ok := cmByID[*rel.ChannelModelID]; ok {
			relKeyID = cm.I64Ptr("channel_api_key_id")
		}
		mark := circuit.EvaluateRelBroken(*rel.ChannelModelID, *rel.ChannelID, relKeyID,
			states, enabledKeysByChannel[*rel.ChannelID], boundKeyIDs)
		if mark != nil {
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
		}
	}
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
