package server

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"io"
	"log/slog"
	"net/http"
	"strings"

	"github.com/gin-gonic/gin"
	"github.com/my-search/my-ai-gateway/internal/httpx"
	"github.com/my-search/my-ai-gateway/internal/relay"
	"github.com/my-search/my-ai-gateway/internal/store"
)

func registerChatRoutes(g *gin.RouterGroup, d Deps) {
	// POST /admin/api/chat/stream
	g.POST("/chat/stream", func(c *gin.Context) {
		rawBody, _ := io.ReadAll(c.Request.Body)

		var body struct {
			Model       string          `json:"model"`
			APIKeyID    int64           `json:"api_key_id"`
			Messages    json.RawMessage `json:"messages"`
			Content     string          `json:"content"`
			Temperature *float64        `json:"temperature"`
			MaxTokens   *int            `json:"max_tokens"`
		}
		json.Unmarshal(rawBody, &body)
		modelName := body.Model

		c.Header("Content-Type", "text/event-stream;charset=UTF-8")
		c.Header("Cache-Control", "no-cache, no-store, must-revalidate")
		c.Header("Pragma", "no-cache")
		c.Header("X-Accel-Buffering", "no")

		if modelName == "" {
			c.Writer.Write(relay.FormatSSE("error", `{"error":"请选择要测试的模型"}`))
			c.Writer.Flush()
			return
		}

		authHeader, keyErr := resolveGatewayKey(c.Request.Context(), d.Store, body.APIKeyID)
		if keyErr != "" {
			c.Writer.Write(relay.FormatSSE("error", relay.BuildErrorJSON("openai", keyErr)))
			c.Writer.Flush()
			return
		}

		openAIReq := buildOpenAIRequest(modelName, body.Messages, body.Content, body.Temperature, body.MaxTokens, true)
		requestJSON, _ := json.Marshal(openAIReq)
		internalReq, _ := relay.ParseRequest(string(requestJSON), relay.ProtoOpenAI)

		ctx := c.Request.Context()
		d.Relay.RelayStream(ctx, internalReq, authHeader, "", string(requestJSON), true, relay.StreamSink{
			OnEvent: func(event, data string) {
				c.Writer.Write(relay.FormatSSE(event, data))
				c.Writer.Flush()
			},
			OnDone: func() {
				c.Writer.Write(relay.FormatSSE("", "[DONE]"))
				c.Writer.Flush()
			},
			OnError: func(err error) {
				slog.Error("流式请求失败", "error", err)
				c.Writer.Write(relay.FormatSSE("error", relay.BuildErrorJSON("openai", err.Error())))
				c.Writer.Flush()
			},
		})
	})

	// POST /admin/api/chat (non-stream)
	g.POST("/chat", func(c *gin.Context) {
		rawBody, _ := io.ReadAll(c.Request.Body)

		var body struct {
			Model       string          `json:"model"`
			APIKeyID    int64           `json:"api_key_id"`
			Messages    json.RawMessage `json:"messages"`
			Content     string          `json:"content"`
			Temperature *float64        `json:"temperature"`
			MaxTokens   *int            `json:"max_tokens"`
		}
		json.Unmarshal(rawBody, &body)
		modelName := body.Model

		if modelName == "" {
			httpx.OK(c, map[string]any{
				"error": map[string]any{"message": "请选择要测试的模型", "type": "invalid_request_error", "code": 400},
			})
			return
		}

		authHeader, keyErr := resolveGatewayKey(c.Request.Context(), d.Store, body.APIKeyID)
		if keyErr != "" {
			httpx.OK(c, map[string]any{
				"error": map[string]any{"message": keyErr, "type": "api_error", "code": 401},
			})
			return
		}

		openAIReq := buildOpenAIRequest(modelName, body.Messages, body.Content, body.Temperature, body.MaxTokens, false)
		requestJSON, _ := json.Marshal(openAIReq)
		internalReq, _ := relay.ParseRequest(string(requestJSON), relay.ProtoOpenAI)

		ctx := c.Request.Context()
		result := d.Relay.RelayNonStream(ctx, internalReq, authHeader, "", string(requestJSON))
		httpx.JSON(c, result.StatusCode, json.RawMessage(result.Body))
	})
}

func registerV1Routes(g *gin.RouterGroup, d Deps) {
	// POST /v1/chat/completions
	g.POST("/chat/completions", func(c *gin.Context) {
		rawBody, _ := io.ReadAll(c.Request.Body)
		authHeader := c.GetHeader("Authorization")

		if authHeader == "" {
			c.JSON(401, relay.ErrorMapOpenAI(
				"Authorization header is required. Expected: Authorization: Bearer sk-myai-xxx",
				"authentication_error", 401))
			return
		}

		internalReq, err := relay.ParseRequest(string(rawBody), relay.ProtoOpenAI)
		if err != nil {
			c.JSON(400, relay.ErrorMapOpenAI("Invalid request body", "invalid_request_error", 400))
			return
		}

		ctx := c.Request.Context()

		// Java reads the boolean with Jackson: json.get("stream").asBoolean()
		if internalReq.Stream {
			c.Header("Content-Type", "text/event-stream;charset=UTF-8")
			c.Header("Cache-Control", "no-cache")
			c.Header("X-Accel-Buffering", "no")

			internalClient := c.GetHeader("X-Internal-Client") == "playground"

			d.Relay.RelayStream(ctx, internalReq, authHeader, buildHeadersJSON(c), string(rawBody), internalClient,
				streamSinkFor(c, relay.ProtoOpenAI))
			return
		}

		result := d.Relay.RelayNonStream(ctx, internalReq, authHeader, buildHeadersJSON(c), string(rawBody))
		c.Header("Content-Type", "application/json;charset=UTF-8")
		c.Writer.WriteHeader(result.StatusCode)
		c.Writer.Write([]byte(result.Body))
	})

	// GET /v1/models — 获取模型列表，无需 API Key 认证
	g.GET("/models", func(c *gin.Context) {
		ctx := c.Request.Context()
		rows, _ := d.Store.Query(ctx,
			"SELECT m.id, m.model_name, m.description, m.created_at FROM models m WHERE m.enabled=1 AND m.hidden=0 ORDER BY m.created_at ASC")
		type v1Model struct {
			ID      string `json:"id"`
			Object  string `json:"object"`
			Created int64  `json:"created"`
			OwnedBy string `json:"owned_by"`
		}
		data := make([]v1Model, 0, len(rows))
		for _, r := range rows {
			created := int64(0)
			if t := r.TimePtr("created_at").T; t != nil {
				created = t.Unix()
			}
			data = append(data, v1Model{ID: r.Str("model_name"), Object: "model", Created: created, OwnedBy: "my-ai-gateway"})
		}
		c.JSON(200, map[string]any{"object": "list", "data": data})
	})

	// POST /v1/embeddings — passes through as chat (matches Java behaviour)
	g.POST("/embeddings", func(c *gin.Context) {
		rawBody, _ := io.ReadAll(c.Request.Body)
		authHeader := c.GetHeader("Authorization")

		ctx := c.Request.Context()
		internalReq, err := relay.ParseRequest(string(rawBody), relay.ProtoOpenAI)
		if err != nil {
			c.JSON(400, relay.ErrorMapOpenAI("Invalid request body", "invalid_request_error", 400))
			return
		}
		result := d.Relay.RelayNonStream(ctx, internalReq, authHeader, buildHeadersJSON(c), string(rawBody))
		c.Header("Content-Type", "application/json;charset=UTF-8")
		c.Writer.WriteHeader(result.StatusCode)
		c.Writer.Write([]byte(result.Body))
	})

	// POST /v1/messages (Anthropic)
	g.POST("/messages", func(c *gin.Context) {
		rawBody, _ := io.ReadAll(c.Request.Body)

		// Anthropic auth: x-api-key preferred, Authorization supported (OAuth/Claude Code)
		authHeader := c.GetHeader("x-api-key")
		if authHeader == "" {
			authHeader = c.GetHeader("Authorization")
			if authHeader == "" {
				httpx.JSON(c, 401, httpx.NewOrderedMap().
					Set("type", "error").
					Set("error", map[string]any{
						"type":    "authentication_error",
						"message": "Authentication required. Provide either 'x-api-key' header or 'Authorization: Bearer' header.",
					}))
				return
			}
		} else {
			authHeader = "Bearer " + authHeader
		}

		internalReq, err := relay.ParseRequest(string(rawBody), relay.ProtoAnthropic)
		if err != nil {
			httpx.JSON(c, 400, httpx.NewOrderedMap().
				Set("type", "error").
				Set("error", map[string]any{"type": "invalid_request_error", "message": "Invalid request body"}))
			return
		}

		ctx := c.Request.Context()

		if internalReq.Stream {
			c.Header("Content-Type", "text/event-stream;charset=UTF-8")
			internalClient := c.GetHeader("X-Internal-Client") == "playground"
			d.Relay.RelayStream(ctx, internalReq, authHeader, buildHeadersJSON(c), string(rawBody), internalClient,
				streamSinkFor(c, relay.ProtoAnthropic))
			return
		}

		result := d.Relay.RelayNonStream(ctx, internalReq, authHeader, buildHeadersJSON(c), string(rawBody))
		c.Header("Content-Type", "application/json;charset=UTF-8")
		c.Writer.WriteHeader(result.StatusCode)
		c.Writer.Write([]byte(result.Body))
	})
}

// streamSinkFor builds a sink that writes SSE frames and terminates the stream
// like SseHandler.sendSseEvent / sendSseError did (error terminates the emitter).
func streamSinkFor(c *gin.Context, clientFormat string) relay.StreamSink {
	return relay.StreamSink{
		OnEvent: func(event, data string) {
			c.Writer.Write(relay.FormatSSE(event, data))
			c.Writer.Flush()
		},
		OnDone: func() {
			c.Writer.Write(relay.FormatSSE("", "[DONE]"))
			c.Writer.Flush()
		},
		OnError: func(err error) {
			slog.Error("流式请求失败", "error", err)
			c.Writer.Write(relay.FormatSSE("error", relay.BuildErrorJSON(clientFormat, err.Error())))
			c.Writer.Flush()
		},
	}
}

func registerShareRoutes(g *gin.RouterGroup, d Deps) {
	g.GET("/:code", func(c *gin.Context) {
		code := strings.TrimSpace(c.Param("code"))
		if code == "" {
			c.JSON(404, map[string]string{"error": "分享链接无效或已失效"})
			return
		}
		ctx := c.Request.Context()
		row, err := d.Store.QueryOne(ctx, "SELECT * FROM api_keys WHERE share_code=? AND enabled=1 AND shared=1", code)
		if err != nil {
			c.JSON(404, map[string]string{"error": "分享链接无效或已失效"})
			return
		}
		httpx.OK(c, buildShareResponse(ctx, d.Store, row))
	})

	g.GET("/by-key/:keyValue", func(c *gin.Context) {
		keyVal := strings.TrimSpace(c.Param("keyValue"))
		ctx := c.Request.Context()
		row, err := d.Store.QueryOne(ctx, "SELECT * FROM api_keys WHERE key_value=? AND enabled=1 AND shared=1", keyVal)
		if err != nil {
			c.JSON(404, map[string]string{"error": "分享链接无效或已失效"})
			return
		}
		httpx.OK(c, buildShareResponse(ctx, d.Store, row))
	})

	g.POST("/chat/stream", func(c *gin.Context) {
		shareCode := strings.TrimSpace(c.Query("shareCode"))
		ctx := c.Request.Context()
		row, err := d.Store.QueryOne(ctx, "SELECT key_value FROM api_keys WHERE share_code=? AND enabled=1 AND shared=1", shareCode)
		if err != nil {
			c.Header("Content-Type", "text/event-stream;charset=UTF-8")
			c.Writer.Write(relay.FormatSSE("error", `{"error":"API 密钥无效或已禁用"}`))
			c.Writer.Flush()
			return
		}
		rawBody, _ := io.ReadAll(c.Request.Body)
		authHeader := "Bearer " + row.Str("key_value")
		internalReq, err := relay.ParseRequest(string(rawBody), relay.ProtoOpenAI)
		if err != nil {
			internalReq = &relay.InternalRequest{ClientAPIFormat: relay.ProtoOpenAI}
		}

		c.Header("Content-Type", "text/event-stream;charset=UTF-8")
		d.Relay.RelayStream(ctx, internalReq, authHeader, "", string(rawBody), true, relay.StreamSink{
			OnEvent: func(event, data string) {
				c.Writer.Write(relay.FormatSSE(event, data))
				c.Writer.Flush()
			},
			OnDone: func() {
				c.Writer.Write(relay.FormatSSE("", "[DONE]"))
				c.Writer.Flush()
			},
			OnError: func(err error) {
				c.Writer.Write(relay.FormatSSE("error", relay.BuildErrorJSON("openai", err.Error())))
				c.Writer.Flush()
			},
		})
	})

	g.POST("/chat", func(c *gin.Context) {
		shareCode := strings.TrimSpace(c.Query("shareCode"))
		ctx := c.Request.Context()
		row, err := d.Store.QueryOne(ctx, "SELECT key_value FROM api_keys WHERE share_code=? AND enabled=1 AND shared=1", shareCode)
		if err != nil {
			c.JSON(200, map[string]any{
				"error": map[string]any{"message": "API 密钥无效或已禁用", "type": "api_error", "code": 403},
			})
			return
		}
		rawBody, _ := io.ReadAll(c.Request.Body)
		authHeader := "Bearer " + row.Str("key_value")
		internalReq, err := relay.ParseRequest(string(rawBody), relay.ProtoOpenAI)
		if err != nil {
			internalReq = &relay.InternalRequest{ClientAPIFormat: relay.ProtoOpenAI}
		}
		result := d.Relay.RelayNonStream(ctx, internalReq, authHeader, "", string(rawBody))
		c.Header("Content-Type", "application/json;charset=UTF-8")
		c.Writer.WriteHeader(result.StatusCode)
		c.Writer.Write([]byte(result.Body))
	})
}

// Helpers

// buildHeadersJSON snapshots the client request headers the way
// RelayLogger.buildFullHeadersJson did (sensitive values masked).
func buildHeadersJSON(c *gin.Context) string {
	m := map[string]any{
		"method": c.Request.Method,
		"path":   c.Request.URL.Path,
	}
	headers := map[string]string{}
	for k, v := range c.Request.Header {
		lower := strings.ToLower(k)
		val := ""
		if len(v) > 0 {
			val = v[0]
		}
		if isSensitiveHeader(lower) {
			val = maskHeaderValue(val)
		}
		headers[lower] = val
	}
	m["headers"] = headers
	if ip := c.ClientIP(); ip != "" {
		m["clientIp"] = ip
	}
	b, err := json.Marshal(m)
	if err != nil {
		return ""
	}
	return string(b)
}

func isSensitiveHeader(name string) bool {
	switch name {
	case "authorization", "x-api-key", "api-key", "cookie", "proxy-authorization":
		return true
	}
	return false
}

func maskHeaderValue(v string) string {
	if v == "" {
		return ""
	}
	if strings.HasPrefix(v, "Bearer ") {
		token := strings.TrimSpace(v[7:])
		return "Bearer " + maskToken(token)
	}
	return maskToken(v)
}

func maskToken(t string) string {
	if len(t) <= 8 {
		return "****"
	}
	return t[:4] + "****" + t[len(t)-4:]
}

func extractAuthHeader(c *gin.Context) string {
	if h := c.GetHeader("Authorization"); h != "" {
		return h
	}
	return ""
}

type strStore = store.Store

// resolveGatewayKey resolves the gateway API key used for /admin/api/chat.
func resolveGatewayKey(ctx context.Context, st *strStore, apiKeyID int64) (authHeader string, errMsg string) {
	if apiKeyID > 0 {
		row, err := st.QueryOne(ctx, "SELECT key_value FROM api_keys WHERE id = ?", apiKeyID)
		if err == nil {
			return "Bearer " + row.Str("key_value"), ""
		}
	}
	row, err := st.QueryOne(ctx, "SELECT key_value FROM api_keys WHERE enabled = 1 ORDER BY created_at ASC LIMIT 1")
	if err == nil {
		return "Bearer " + row.Str("key_value"), ""
	}
	return "", "没有可用的 API Key，请先创建一个"
}

// buildOpenAIRequest constructs the upstream request JSON for the chat playground.
func buildOpenAIRequest(model string, messages json.RawMessage, content string, temperature *float64, maxTokens *int, stream bool) map[string]any {
	req := map[string]any{
		"model":  model,
		"stream": stream,
	}
	if len(messages) > 0 && string(messages) != "null" {
		req["messages"] = json.RawMessage(messages)
	} else {
		req["messages"] = []any{map[string]string{"role": "user", "content": content}}
	}
	if temperature != nil {
		req["temperature"] = *temperature
	}
	if maxTokens != nil {
		req["max_tokens"] = *maxTokens
	}
	return req
}

func buildShareResponse(ctx context.Context, st *strStore, akRow store.Row) map[string]any {
	ak := store.RowToAPIKey(akRow)
	maskKey := ak.KeyValue
	if len(maskKey) > 12 {
		maskKey = maskKey[:8] + "****" + maskKey[len(maskKey)-4:]
	}

	modelRows, _ := st.Query(ctx, "SELECT * FROM models WHERE hidden=0 AND enabled=1 ORDER BY created_at ASC")
	type shareModel struct {
		ID           int64    `json:"id"`
		ModelName    string   `json:"modelName"`
		Description  *string  `json:"description"`
		ChannelTypes []string `json:"channelTypes"`
	}
	allCT := make(map[string]bool)
	var models []shareModel
	for _, r := range modelRows {
		m := store.RowToModel(r)
		var cts []string
		rels, _ := st.Query(ctx, "SELECT DISTINCT c.channel_type FROM model_channel_rels mcr JOIN channel_models cm ON cm.id=mcr.channel_model_id JOIN channels c ON c.id=cm.channel_id WHERE mcr.model_id=? AND mcr.enabled=1", m.ID)
		for _, rel := range rels {
			ct := rel.Str("channel_type")
			if ct != "" {
				cts = append(cts, ct)
				allCT[ct] = true
			}
		}
		models = append(models, shareModel{ID: m.ID, ModelName: m.ModelName, Description: m.Description, ChannelTypes: cts})
	}
	var ctList []string
	for ct := range allCT {
		ctList = append(ctList, ct)
	}

	return map[string]any{
		"success": true, "id": ak.ID, "shareCode": derefStr(ak.ShareCode, ""),
		"keyName": ak.KeyName, "keyValue": ak.KeyValue, "keyValueMasked": maskKey,
		"baseUrl": "", "models": models, "channelTypes": ctList,
	}
}

var (
	_ = fmt.Sprintf
	_ = bytes.Buffer{}
	_ = http.MethodPost
	_ = extractAuthHeader
)
