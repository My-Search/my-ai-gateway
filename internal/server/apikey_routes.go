package server

import (
	"net/http"
	"strconv"
	"strings"
	"time"

	"github.com/gin-gonic/gin"
	"github.com/my-search/my-ai-gateway/internal/jtime"
	"github.com/my-search/my-ai-gateway/internal/httpx"
	"github.com/my-search/my-ai-gateway/internal/models"
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
		out := make([]models.APIKey, 0, len(rows))
		for _, r := range rows {
			out = append(out, store.RowToAPIKey(r))
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
				`SELECT gateway_api_key_id, COUNT(*) request_count, COALESCE(SUM(total_tokens),0) total_tokens
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
					"requestCount": r.I64("request_count", 0),
					"totalTokens":  r.I64("total_tokens", 0),
				}
			}
			out[p.name] = periodMap
		}
		httpx.OK(c, out)
	})
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
	case float64: return int(x)
	case int: return x
	case bool: return boolToInt(x)
	default: return 0
	}
}