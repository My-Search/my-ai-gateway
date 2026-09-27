package server

import (
	"net/http"
	"strings"
	"time"

	"github.com/dlclark/regexp2"
	"github.com/gin-gonic/gin"
	"github.com/my-search/my-ai-gateway/internal/channelload"
	"github.com/my-search/my-ai-gateway/internal/httpx"
	"github.com/my-search/my-ai-gateway/internal/jtime"
	"github.com/my-search/my-ai-gateway/internal/models"
	"github.com/my-search/my-ai-gateway/internal/service"
	"github.com/my-search/my-ai-gateway/internal/store"
)

// registerMultiModalRoutes wires the model-config rules and prompt injections.
//
// A model config rule merges the former multimodal and context rules: one regex
// matched against channel model names that can append input modalities and/or
// set a context window. Its effect is layered on top of the models.dev baseline
// data, and always wins over it.
func registerMultiModalRoutes(g *gin.RouterGroup, d Deps) {
	// ---------------------------------------------------------------------------
	// Model config rules (/admin/api/model-config-rules)
	// ---------------------------------------------------------------------------

	// GET /admin/api/model-config-rules
	g.GET("/model-config-rules", func(c *gin.Context) {
		ctx := c.Request.Context()
		rows, err := d.Store.Query(ctx, "SELECT * FROM model_config_rules ORDER BY created_at ASC, id ASC")
		if err != nil {
			// 查询失败（如表缺失）必须暴露出来，返回空列表会掩盖故障
			httpx.JSON(c, http.StatusInternalServerError, failureEnvelope(err.Error()))
			return
		}
		out := make([]models.ModelConfigRule, 0, len(rows))
		for _, r := range rows {
			out = append(out, store.RowToModelConfigRule(r))
		}
		httpx.OK(c, out)
	})

	// POST /admin/api/model-config-rules
	g.POST("/model-config-rules", func(c *gin.Context) {
		ctx := c.Request.Context()
		var body struct {
			Pattern       string `json:"pattern"`
			AppendType    string `json:"appendType"`
			ContextLength int64  `json:"contextLength"`
		}
		if err := c.ShouldBindJSON(&body); err != nil {
			httpx.OK(c, failureEnvelope("请求参数错误"))
			return
		}
		body.Pattern = strings.TrimSpace(body.Pattern)
		if body.Pattern == "" {
			httpx.OK(c, failureEnvelope("pattern 不能为空"))
			return
		}
		if _, err := regexp2.Compile(body.Pattern, 0); err != nil {
			httpx.OK(c, failureEnvelope("正则表达式语法错误: "+err.Error()))
			return
		}
		if body.ContextLength < 0 {
			httpx.OK(c, failureEnvelope("上下文大小不能为负数"))
			return
		}
		if strings.TrimSpace(body.AppendType) == "" && body.ContextLength == 0 {
			httpx.OK(c, failureEnvelope("请输入输入模态或上下文大小，至少一项"))
			return
		}
		now := jtime.FormatApp(time.Now().UTC())
		id, err := d.Store.Insert(ctx,
			"INSERT INTO model_config_rules (pattern, append_type, context_length, created_at, updated_at) VALUES (?, ?, ?, ?, ?)",
			body.Pattern, strings.TrimSpace(body.AppendType), body.ContextLength, now, now)
		if err != nil {
			httpx.OK(c, failureEnvelope(err.Error()))
			return
		}
		channelload.InvalidateRuleCache()
		channelload.ReapplyAllRules(ctx, d.Store)
		row, _ := d.Store.QueryOne(ctx, "SELECT * FROM model_config_rules WHERE id=?", id)
		httpx.OK(c, httpx.NewOrderedMap().Set("success", true).Set("data", store.RowToModelConfigRule(row)))
	})

	// PUT /admin/api/model-config-rules/{id}
	g.PUT("/model-config-rules/:id", func(c *gin.Context) {
		ctx := c.Request.Context()
		id, ok := pathID(c, "id")
		if !ok {
			httpx.OK(c, failureEnvelope("规则不存在"))
			return
		}
		row, _ := d.Store.QueryOne(ctx, "SELECT * FROM model_config_rules WHERE id=?", id)
		if row == nil {
			httpx.OK(c, failureEnvelope("规则不存在"))
			return
		}
		var body struct {
			Pattern       string `json:"pattern"`
			AppendType    string `json:"appendType"`
			ContextLength int64  `json:"contextLength"`
		}
		if err := c.ShouldBindJSON(&body); err != nil {
			httpx.OK(c, failureEnvelope("请求参数错误"))
			return
		}
		body.Pattern = strings.TrimSpace(body.Pattern)
		if _, err := regexp2.Compile(body.Pattern, 0); err != nil {
			httpx.OK(c, failureEnvelope("正则表达式语法错误: "+err.Error()))
			return
		}
		if body.ContextLength < 0 {
			httpx.OK(c, failureEnvelope("上下文大小不能为负数"))
			return
		}
		if strings.TrimSpace(body.AppendType) == "" && body.ContextLength == 0 {
			httpx.OK(c, failureEnvelope("请输入输入模态或上下文大小，至少一项"))
			return
		}
		now := jtime.FormatApp(time.Now().UTC())
		d.Store.Exec(ctx, "UPDATE model_config_rules SET pattern=?, append_type=?, context_length=?, updated_at=? WHERE id=?",
			body.Pattern, strings.TrimSpace(body.AppendType), body.ContextLength, now, id)
		channelload.InvalidateRuleCache()
		channelload.ReapplyAllRules(ctx, d.Store)
		updated, _ := d.Store.QueryOne(ctx, "SELECT * FROM model_config_rules WHERE id=?", id)
		httpx.OK(c, httpx.NewOrderedMap().Set("success", true).Set("data", store.RowToModelConfigRule(updated)))
	})

	// DELETE /admin/api/model-config-rules/{id}
	g.DELETE("/model-config-rules/:id", func(c *gin.Context) {
		ctx := c.Request.Context()
		id, ok := pathID(c, "id")
		if !ok {
			httpx.OK(c, failureEnvelope("规则不存在"))
			return
		}
		d.Store.Exec(ctx, "DELETE FROM model_config_rules WHERE id=?", id)
		channelload.InvalidateRuleCache()
		channelload.ReapplyAllRules(ctx, d.Store)
		httpx.OK(c, httpx.NewOrderedMap().Set("success", true))
	})

	// POST /admin/api/model-config-rules/test
	g.POST("/model-config-rules/test", func(c *gin.Context) {
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
		for _, data := range body.TestData {
			m, _ := channelload.MatchPattern(body.Pattern, data)
			results = append(results, resultItem{Data: data, Matched: m})
		}
		// Also match against every real channel model: the pattern is applied to
		// channel_models.model_name, so testing against hand-typed IDs alone can
		// claim a match that never takes effect (e.g. "^hy" vs "workbuddy/hy4-preview").
		type matchedModel struct {
			ModelName     string `json:"modelName"`
			// ContextLength is the effective window after our rules and the
			// models.dev baseline are combined; nil = unknown, no filtering.
			ContextLength *int64 `json:"contextLength"`
			// ContextSource is "rule" / "catalog" / "none" for that window.
			ContextSource string `json:"contextSource"`
			// Input is the effective modality set after baseline + rule appends.
			Input string `json:"input"`
			// CatalogContextLength / CatalogInput are the raw baseline values, so
			// the operator can see what a rule overrides.
			CatalogContextLength int64  `json:"catalogContextLength"`
			CatalogInput         string `json:"catalogInput"`
		}
		matchedModels := make([]matchedModel, 0)
		modelRows, _ := d.Store.Query(ctx, "SELECT DISTINCT model_name FROM channel_models WHERE model_name != '' ORDER BY model_name")
		for _, row := range modelRows {
			name := row.Str("model_name")
			if m, _ := channelload.MatchPattern(body.Pattern, name); m {
				effective, _ := channelload.ResolveModelConfig(ctx, d.Store, name)
				catalogCtx, catalogInput := channelload.CatalogInfo(name)
				matchedModels = append(matchedModels, matchedModel{
					ModelName:            name,
					ContextLength:        channelload.ResolveContextLength(ctx, d.Store, name),
					ContextSource:        channelload.ContextLengthSource(ctx, d.Store, name),
					Input:                effective,
					CatalogContextLength: catalogCtx,
					CatalogInput:         catalogInput,
				})
			}
		}
		httpx.OK(c, httpx.NewOrderedMap().
			Set("success", true).
			Set("data", results).
			Set("matchedModels", matchedModels).
			Set("totalModels", len(modelRows)))
	})

	// ---------------------------------------------------------------------------
	// models.dev data file (/admin/api/models-dev/*)
	//
	// The catalog is read from a local file; the scheduled task refreshes that
	// file, and a successful update triggers a model-config reapply.
	// ---------------------------------------------------------------------------

	// GET /admin/api/models-dev/status
	g.GET("/models-dev/status", func(c *gin.Context) {
		ctx := c.Request.Context()
		st := channelload.CatalogStatus()
		httpx.OK(c, httpx.NewOrderedMap().
			Set("success", true).
			Set("enabled", d.Config.GetValue(ctx, service.KeyModelsDevEnabled, "1") == "1").
			Set("file", d.Config.GetValue(ctx, service.KeyModelsDevFile, "data/models.json")).
			Set("sourceUrl", d.Config.GetValue(ctx, service.KeyModelsDevSourceURL, "https://models.dev/models.json")).
			Set("count", st.Count).
			Set("path", st.Path).
			Set("updatedAt", catalogModTime(st)).
			Set("loadedAt", catalogLoadedAt(st)).
			Set("lastError", st.LastError))
	})

	// POST /admin/api/models-dev/reload — re-read the local file (no download)
	g.POST("/models-dev/reload", func(c *gin.Context) {
		ctx := c.Request.Context()
		path := d.Config.GetValue(ctx, service.KeyModelsDevFile, "data/models.json")
		channelload.SetCatalogPath(path)
		n := channelload.ReloadCatalog()
		channelload.InvalidateRuleCache()
		channelload.ReapplyAllRules(ctx, d.Store)
		httpx.OK(c, httpx.NewOrderedMap().Set("success", true).Set("count", n))
	})

	// POST /admin/api/models-dev/refresh — download into the local file, then reload
	g.POST("/models-dev/refresh", func(c *gin.Context) {
		ctx := c.Request.Context()
		path := d.Config.GetValue(ctx, service.KeyModelsDevFile, "data/models.json")
		url := strings.TrimSpace(d.Config.GetValue(ctx, service.KeyModelsDevSourceURL, "https://models.dev/models.json"))
		usedURL, n, err := channelload.DownloadCatalogWithFallback(url, path)
		if err != nil {
			channelload.SetCatalogError(err.Error())
			httpx.OK(c, failureEnvelope("models.dev 更新失败: "+err.Error()))
			return
		}
		channelload.SetCatalogPath(path)
		channelload.ReloadCatalog()
		channelload.InvalidateRuleCache()
		channelload.ReapplyAllRules(ctx, d.Store)
		channelload.SetCatalogError("")
		httpx.OK(c, httpx.NewOrderedMap().Set("success", true).Set("count", n).Set("sourceUrl", usedURL))
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

// catalogModTime / catalogLoadedAt format the data file's timestamps for the UI.
func catalogModTime(st channelload.CatalogFileState) string {
	if st.ModTime.IsZero() {
		return ""
	}
	return jtime.FormatApp(st.ModTime.UTC())
}

func catalogLoadedAt(st channelload.CatalogFileState) string {
	if st.LoadedAt.IsZero() {
		return ""
	}
	return jtime.FormatApp(st.LoadedAt.UTC())
}
