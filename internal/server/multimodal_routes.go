package server

import (
	"strings"
	"time"

	"github.com/dlclark/regexp2"
	"github.com/gin-gonic/gin"
	"github.com/my-search/my-ai-gateway/internal/channelload"
	"github.com/my-search/my-ai-gateway/internal/httpx"
	"github.com/my-search/my-ai-gateway/internal/jtime"
	"github.com/my-search/my-ai-gateway/internal/models"
	"github.com/my-search/my-ai-gateway/internal/store"
)

func registerMultiModalRoutes(g *gin.RouterGroup, d Deps) {
	// GET /admin/api/multimodal-rules
	g.GET("/multimodal-rules", func(c *gin.Context) {
		ctx := c.Request.Context()
		rows, _ := d.Store.Query(ctx, "SELECT * FROM multimodal_rules ORDER BY created_at ASC")
		out := make([]models.MultiModalRule, 0, len(rows))
		for _, r := range rows {
			out = append(out, store.RowToMultiModalRule(r))
		}
		httpx.OK(c, out)
	})

	// POST /admin/api/multimodal-rules
	g.POST("/multimodal-rules", func(c *gin.Context) {
		ctx := c.Request.Context()
		var rule models.MultiModalRule
		if err := c.ShouldBindJSON(&rule); err != nil {
			httpx.OK(c, failureEnvelope("请求参数错误"))
			return
		}
		if rule.Pattern == "" {
			httpx.OK(c, failureEnvelope("pattern 不能为空"))
			return
		}
		if _, err := regexp2.Compile(rule.Pattern, 0); err != nil {
			httpx.OK(c, failureEnvelope("正则表达式语法错误: "+err.Error()))
			return
		}
		if rule.AppendType == "" {
			rule.AppendType = "image"
		}
		now := jtime.FormatApp(time.Now().UTC())
		id, err := d.Store.Insert(ctx,
			"INSERT INTO multimodal_rules (pattern, append_type, created_at, updated_at) VALUES (?, ?, ?, ?)",
			rule.Pattern, rule.AppendType, now, now)
		if err != nil {
			httpx.OK(c, failureEnvelope(err.Error()))
			return
		}
		channelload.InvalidateRuleCache()
		channelload.ReapplyAllRules(ctx, d.Store)
		row, _ := d.Store.QueryOne(ctx, "SELECT * FROM multimodal_rules WHERE id=?", id)
		httpx.OK(c, httpx.NewOrderedMap().Set("success", true).Set("data", store.RowToMultiModalRule(row)))
	})

	// PUT /admin/api/multimodal-rules/{id}
	g.PUT("/multimodal-rules/:id", func(c *gin.Context) {
		ctx := c.Request.Context()
		id, ok := pathID(c, "id")
		if !ok {
			httpx.OK(c, failureEnvelope("规则不存在"))
			return
		}
		row, _ := d.Store.QueryOne(ctx, "SELECT * FROM multimodal_rules WHERE id=?", id)
		if row == nil {
			httpx.OK(c, failureEnvelope("规则不存在"))
			return
		}
		var body struct {
			Pattern    string `json:"pattern"`
			AppendType string `json:"appendType"`
		}
		if err := c.ShouldBindJSON(&body); err != nil {
			httpx.OK(c, failureEnvelope("请求参数错误"))
			return
		}
		if _, err := regexp2.Compile(body.Pattern, 0); err != nil {
			httpx.OK(c, failureEnvelope("正则表达式语法错误: "+err.Error()))
			return
		}
		now := jtime.FormatApp(time.Now().UTC())
		d.Store.Exec(ctx, "UPDATE multimodal_rules SET pattern=?, append_type=?, updated_at=? WHERE id=?", body.Pattern, body.AppendType, now, id)
		channelload.InvalidateRuleCache()
		channelload.ReapplyAllRules(ctx, d.Store)
		updated, _ := d.Store.QueryOne(ctx, "SELECT * FROM multimodal_rules WHERE id=?", id)
		httpx.OK(c, httpx.NewOrderedMap().Set("success", true).Set("data", store.RowToMultiModalRule(updated)))
	})

	// DELETE /admin/api/multimodal-rules/{id}
	g.DELETE("/multimodal-rules/:id", func(c *gin.Context) {
		ctx := c.Request.Context()
		id, ok := pathID(c, "id")
		if !ok {
			httpx.OK(c, failureEnvelope("规则不存在"))
			return
		}
		d.Store.Exec(ctx, "DELETE FROM multimodal_rules WHERE id=?", id)
		channelload.InvalidateRuleCache()
		channelload.ReapplyAllRules(ctx, d.Store)
		httpx.OK(c, httpx.NewOrderedMap().Set("success", true))
	})

	// POST /admin/api/multimodal-rules/test
	g.POST("/multimodal-rules/test", func(c *gin.Context) {
		ctx := c.Request.Context()
		var body struct {
			Pattern  string   `json:"pattern"`
			TestData []string `json:"testData"`
		}
		if err := c.ShouldBindJSON(&body); err != nil {
			httpx.OK(c, failureEnvelope("请求参数错误"))
			return
		}
		if body.Pattern == "" {
			httpx.OK(c, failureEnvelope("请输入正则表达式"))
			return
		}
		if len(body.TestData) == 0 {
			httpx.OK(c, failureEnvelope("请添加测试数据"))
			return
		}
		// Syntax check mirrors rule save validation (regexp2, flags 0).
		if _, err := regexp2.Compile(body.Pattern, 0); err != nil {
			httpx.OK(c, failureEnvelope("正则表达式语法错误: "+err.Error()))
			return
		}
		type resultItem struct {
			Data    string `json:"data"`
			Matched bool   `json:"matched"`
		}
		results := make([]resultItem, 0, len(body.TestData))
		for _, d := range body.TestData {
			// Match via channelload.MatchPattern (same engine/options as
			// ComputeInput) so the test result equals the applied result.
			m, _ := channelload.MatchPattern(body.Pattern, d)
			results = append(results, resultItem{Data: d, Matched: m})
		}
		// Also match against every real channel model: the pattern is applied
		// to channel_models.model_name, so testing against hand-typed IDs alone
		// can claim a match that never takes effect (e.g. "^hy" vs "workbuddy/hy4-preview").
		matchedModels := make([]string, 0)
		modelRows, _ := d.Store.Query(ctx, "SELECT DISTINCT model_name FROM channel_models WHERE model_name != '' ORDER BY model_name")
		for _, row := range modelRows {
			name := row.Str("model_name")
			if m, _ := channelload.MatchPattern(body.Pattern, name); m {
				matchedModels = append(matchedModels, name)
			}
		}
		httpx.OK(c, httpx.NewOrderedMap().
			Set("success", true).
			Set("data", results).
			Set("matchedModels", matchedModels).
			Set("totalModels", len(modelRows)))
	})

	// GET /admin/api/models/{modelId}/prompt-injections
	g.GET("/models/:id/prompt-injections", func(c *gin.Context) {
		ctx := c.Request.Context()
		modelID, ok := pathID(c, "id")
		if !ok {
			httpx.OK(c, failureEnvelope("模型不存在"))
			return
		}
		rows, _ := d.Store.Query(ctx, "SELECT * FROM prompt_injections WHERE model_id=? ORDER BY priority ASC, id ASC", modelID)
		out := make([]models.PromptInjection, 0, len(rows))
		for _, r := range rows {
			out = append(out, store.RowToPromptInjection(r))
		}
		httpx.OK(c, out)
	})

	// GET /admin/api/prompt-injections/{id}
	g.GET("/prompt-injections/:id", func(c *gin.Context) {
		ctx := c.Request.Context()
		id, ok := pathID(c, "id")
		if !ok {
			notFound(c, "规则不存在")
			return
		}
		row, err := d.Store.QueryOne(ctx, "SELECT * FROM prompt_injections WHERE id=?", id)
		if err != nil {
			notFound(c, "规则不存在")
			return
		}
		httpx.OK(c, store.RowToPromptInjection(row))
	})

	// POST /admin/api/models/{modelId}/prompt-injections
	g.POST("/models/:id/prompt-injections", func(c *gin.Context) {
		ctx := c.Request.Context()
		modelID, ok := pathID(c, "id")
		if !ok {
			httpx.OK(c, failureEnvelope("模型不存在"))
			return
		}
		var body struct {
			Name           string `json:"name"`
			InjectRole     string `json:"injectRole"`
			InjectPosition string `json:"injectPosition"`
			Content        string `json:"content"`
			Enabled        *int   `json:"enabled"`
			Priority       *int   `json:"priority"`
		}
		if err := c.ShouldBindJSON(&body); err != nil {
			httpx.OK(c, failureEnvelope("请求参数错误"))
			return
		}
		body.InjectRole = strings.TrimSpace(body.InjectRole)
		body.InjectPosition = strings.TrimSpace(body.InjectPosition)
		body.Content = strings.TrimSpace(body.Content)
		if body.Content == "" {
			httpx.OK(c, failureEnvelope("content 不能为空"))
			return
		}
		if len([]rune(body.Content)) > 10000 {
			httpx.OK(c, failureEnvelope("content 超出最大长度限制（10000 字符）"))
			return
		}
		if body.InjectRole != "system" && body.InjectRole != "user" && body.InjectRole != "assistant" {
			httpx.OK(c, failureEnvelope("injectRole 必须为 system / user / assistant"))
			return
		}
		if body.InjectPosition != "prepend" && body.InjectPosition != "append" && body.InjectPosition != "replace_system" {
			httpx.OK(c, failureEnvelope("injectPosition 必须为 prepend / append / replace_system"))
			return
		}
		if body.InjectPosition == "replace_system" {
			existing, _ := d.Store.Query(ctx, "SELECT id FROM prompt_injections WHERE model_id=? AND inject_position='replace_system' AND id!=? AND id!=0", modelID, 0)
			if len(existing) > 0 {
				httpx.OK(c, failureEnvelope("每个模型只能有一条 replace_system 规则"))
				return
			}
		}
		en := 1
		if body.Enabled != nil {
			en = *body.Enabled
		}
		pr := 0
		if body.Priority != nil {
			pr = *body.Priority
		}
		now := jtime.FormatApp(time.Now().UTC())
		id, err := d.Store.Insert(ctx,
			"INSERT INTO prompt_injections (model_id, name, inject_role, inject_position, content, enabled, priority, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
			modelID, body.Name, body.InjectRole, body.InjectPosition, body.Content, en, pr, now, now)
		if err != nil {
			httpx.OK(c, failureEnvelope(err.Error()))
			return
		}
		row, _ := d.Store.QueryOne(ctx, "SELECT * FROM prompt_injections WHERE id=?", id)
		httpx.OK(c, httpx.NewOrderedMap().Set("success", true).Set("data", store.RowToPromptInjection(row)))
	})

	// PUT /admin/api/prompt-injections/{id}
	g.PUT("/prompt-injections/:id", func(c *gin.Context) {
		ctx := c.Request.Context()
		id, ok := pathID(c, "id")
		if !ok {
			httpx.OK(c, failureEnvelope("规则不存在"))
			return
		}
		row, _ := d.Store.QueryOne(ctx, "SELECT * FROM prompt_injections WHERE id=?", id)
		if row == nil {
			httpx.OK(c, failureEnvelope("Prompt 注入规则不存在"))
			return
		}
		var body struct {
			Name           string `json:"name"`
			InjectRole     string `json:"injectRole"`
			InjectPosition string `json:"injectPosition"`
			Content        string `json:"content"`
			Enabled        *int   `json:"enabled"`
			Priority       *int   `json:"priority"`
		}
		if err := c.ShouldBindJSON(&body); err != nil {
			httpx.OK(c, failureEnvelope("请求参数错误"))
			return
		}
		now := jtime.FormatApp(time.Now().UTC())
		sets := make(map[string]any)
		sets["updated_at"] = now
		if body.InjectRole != "" {
			sets["inject_role"] = body.InjectRole
		}
		if body.InjectPosition != "" {
			sets["inject_position"] = body.InjectPosition
		}
		if body.Content != "" {
			sets["content"] = body.Content
		}
		if body.Name != "" {
			sets["name"] = body.Name
		}
		if body.Enabled != nil {
			sets["enabled"] = *body.Enabled
		}
		if body.Priority != nil {
			sets["priority"] = *body.Priority
		}
		if len(sets) > 1 {
			q, args := store.BuildUpdate("prompt_injections", sets, "id = ?", id)
			_, _ = d.Store.Exec(ctx, q, args...)
		}
		updated, _ := d.Store.QueryOne(ctx, "SELECT * FROM prompt_injections WHERE id=?", id)
		httpx.OK(c, httpx.NewOrderedMap().Set("success", true).Set("data", store.RowToPromptInjection(updated)))
	})

	// DELETE /admin/api/prompt-injections/{id}
	g.DELETE("/prompt-injections/:id", func(c *gin.Context) {
		ctx := c.Request.Context()
		id, ok := pathID(c, "id")
		if !ok {
			httpx.OK(c, failureEnvelope("规则不存在"))
			return
		}
		d.Store.Exec(ctx, "DELETE FROM prompt_injections WHERE id=?", id)
		httpx.OK(c, httpx.NewOrderedMap().Set("success", true))
	})
}
