// Package channelload implements the provider model fetching and replacement
// logic of the Java ChannelModelLoader + MultiModalRuleService.
//
// It is a separate package (rather than living inside server) so the scheduled
// channel-refresh task and the admin handlers can share one implementation.
package channelload

import (
	"context"
	"database/sql"
	"encoding/json"
	"fmt"
	"log/slog"
	"net/http"
	"strings"
	"sync"
	"time"

	"github.com/dlclark/regexp2"
	"github.com/my-search/my-ai-gateway/internal/jtime"
	"github.com/my-search/my-ai-gateway/internal/store"
)

// ModelPair is one provider model (name + display name).
type ModelPair struct {
	Name    string
	Display string
}

// ---------------------------------------------------------------------------
// Multi-modal rules (Java MultiModalRuleService)
// ---------------------------------------------------------------------------

var (
	ruleCacheMu  sync.Mutex
	ruleCache    []ruleRow
	ruleCacheAt  time.Time
	ruleCacheTTL = 5 * time.Second
)

type ruleRow struct {
	Pattern    string
	AppendType string
}

func loadRules(ctx context.Context, st *store.Store) []ruleRow {
	ruleCacheMu.Lock()
	defer ruleCacheMu.Unlock()
	if time.Since(ruleCacheAt) < ruleCacheTTL && ruleCache != nil {
		return ruleCache
	}
	rows, _ := st.Query(ctx, "SELECT pattern, append_type FROM multimodal_rules ORDER BY created_at ASC")
	out := make([]ruleRow, 0, len(rows))
	for _, row := range rows {
		out = append(out, ruleRow{Pattern: row.Str("pattern"), AppendType: row.Str("append_type")})
	}
	ruleCache = out
	ruleCacheAt = time.Now()
	return out
}

// InvalidateRuleCache drops the short-lived rule cache (call after rule writes).
func InvalidateRuleCache() {
	ruleCacheMu.Lock()
	ruleCacheAt = time.Time{}
	ruleCacheMu.Unlock()
}

// MatchPattern evaluates pattern against s with the same engine and options
// (regexp2, flags 0) that the rule save/test endpoints use, so a rule that
// passes the test is applied exactly as tested.
func MatchPattern(pattern, s string) (bool, error) {
	re, err := regexp2.Compile(pattern, 0)
	if err != nil {
		return false, err
	}
	return re.MatchString(s)
}

// ComputeInput mirrors MultiModalRuleService.computeInput.
func ComputeInput(ctx context.Context, st *store.Store, modelName string) string {
	var types []string
	seen := map[string]bool{}
	for _, rule := range loadRules(ctx, st) {
		ok, err := MatchPattern(rule.Pattern, modelName)
		if err != nil {
			slog.Warn("多模态规则正则无效", "pattern", rule.Pattern, "error", err)
			continue
		}
		if !ok {
			continue
		}
		// append_type may hold several comma-separated types; dedupe per type
		// so overlapping rules cannot inject duplicates like "text,image,image".
		for _, t := range strings.Split(rule.AppendType, ",") {
			t = strings.TrimSpace(t)
			if t == "" || seen[t] {
				continue
			}
			seen[t] = true
			types = append(types, t)
		}
	}
	if len(types) == 0 {
		return "text"
	}
	return "text," + strings.Join(types, ",")
}

// ReapplyAllRules mirrors MultiModalRuleService.reapplyAllRules: recompute the
// input column for every channel model, updating only changed rows.
func ReapplyAllRules(ctx context.Context, st *store.Store) {
	rows, _ := st.Query(ctx, "SELECT id, model_name, input FROM channel_models")
	updated := 0
	for _, row := range rows {
		modelName := row.Str("model_name")
		if modelName == "" {
			continue
		}
		newInput := ComputeInput(ctx, st, modelName)
		if row.Str("input") != newInput {
			_, _ = st.Exec(ctx, "UPDATE channel_models SET input = ? WHERE id = ?", newInput, row.I64("id", 0))
			updated++
		}
	}
	slog.Info("多模态规则重新应用完成", "total", len(rows), "updated", updated)
}

// ---------------------------------------------------------------------------
// Provider model fetching (Java ChannelModelLoader.fetchNewModels)
// ---------------------------------------------------------------------------

// BuildModelsURL mirrors ChannelModelLoader.buildModelsUrl.
func BuildModelsURL(baseURL, chType string) string {
	b := strings.TrimSpace(baseURL)
	if b == "" {
		if chType == "anthropic" {
			b = "https://api.anthropic.com/v1"
		} else {
			b = "https://api.openai.com/v1"
		}
	}
	b = strings.TrimRight(b, "/")
	return b + "/models"
}

// FetchModels mirrors ChannelModelLoader.fetchNewModels.
func FetchModels(baseURL, apiKey, chType, customHeaders string) ([]ModelPair, error) {
	url := BuildModelsURL(baseURL, chType)
	req, err := http.NewRequest(http.MethodGet, url, nil)
	if err != nil {
		return nil, err
	}
	if chType == "anthropic" {
		req.Header.Set("x-api-key", apiKey)
		req.Header.Set("anthropic-version", "2023-06-01")
	} else {
		req.Header.Set("Authorization", "Bearer "+apiKey)
	}
	if customHeaders != "" {
		var ch map[string]string
		if json.Unmarshal([]byte(customHeaders), &ch) == nil {
			for k, v := range ch {
				req.Header.Set(k, v)
			}
		}
	}
	client := &http.Client{Timeout: 60 * time.Second}
	resp, err := client.Do(req)
	if err != nil {
		return nil, err
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		return nil, fmt.Errorf("status %d", resp.StatusCode)
	}
	var payload struct {
		Data []struct {
			ID string `json:"id"`
		} `json:"data"`
	}
	if err := json.NewDecoder(resp.Body).Decode(&payload); err != nil {
		return nil, err
	}
	out := make([]ModelPair, 0, len(payload.Data))
	for _, m := range payload.Data {
		if m.ID != "" {
			out = append(out, ModelPair{Name: m.ID, Display: m.ID})
		}
	}
	return out, nil
}

// DefaultModels mirrors ChannelModelLoader.getDefaultModels.
func DefaultModels(chType string) []ModelPair {
	if chType == "anthropic" {
		return []ModelPair{
			{"claude-3-opus-20240229", "Claude 3 Opus"},
			{"claude-3-sonnet-20240229", "Claude 3 Sonnet"},
			{"claude-3-haiku-20240307", "Claude 3 Haiku"},
			{"claude-3-5-sonnet-20241022", "Claude 3.5 Sonnet"},
			{"claude-3-5-haiku-20241022", "Claude 3.5 Haiku"},
		}
	}
	if chType == "openai" {
		return []ModelPair{
			{"gpt-4o", "GPT-4o"},
			{"gpt-4o-mini", "GPT-4o Mini"},
			{"gpt-4-turbo", "GPT-4 Turbo"},
			{"gpt-4", "GPT-4"},
			{"gpt-3.5-turbo", "GPT-3.5 Turbo"},
			{"o1-preview", "O1 Preview"},
			{"o1-mini", "O1 Mini"},
			{"dall-e-3", "DALL-E 3"},
			{"text-embedding-3-small", "Embedding 3 Small"},
			{"text-embedding-3-large", "Embedding 3 Large"},
		}
	}
	return []ModelPair{{"default-model", "Default Model"}}
}

// FirstEnabledAPIKey returns the channel's first enabled key (by sort_order).
func FirstEnabledAPIKey(ctx context.Context, st *store.Store, chID int64) string {
	rows, _ := st.Query(ctx, "SELECT api_key FROM channel_api_keys WHERE channel_id = ? AND enabled = 1 ORDER BY sort_order LIMIT 1", chID)
	if len(rows) > 0 {
		return rows[0].Str("api_key")
	}
	return ""
}

// LoadModels mirrors ChannelModelLoader.loadModels: fetch first, only replace on
// success; keep existing models when the fetch fails (unless the channel has no
// models at all, in which case the preset list is used).
func LoadModels(ctx context.Context, st *store.Store, chID int64, chType, baseURL, customHeaders, fallbackKey string) {
	apiKey := FirstEnabledAPIKey(ctx, st, chID)
	if apiKey == "" {
		apiKey = fallbackKey
	}
	var newModels []ModelPair
	if apiKey != "" {
		models, err := FetchModels(baseURL, apiKey, chType, customHeaders)
		if err == nil {
			newModels = models
		} else {
			slog.Warn("渠道模型拉取失败", "channelId", chID, "error", err)
		}
	}
	if len(newModels) == 0 {
		var existing int64
		if row, err := st.QueryOne(ctx, "SELECT COUNT(*) cnt FROM channel_models WHERE channel_id = ?", chID); err == nil {
			existing = row.I64("cnt", 0)
		}
		if existing == 0 {
			newModels = DefaultModels(chType)
		} else {
			slog.Warn("渠道刷新模型拉取失败或服务商未返回模型，保留现有模型", "channelId", chID)
			return
		}
	}
	ReplaceAPIModels(ctx, st, chID, newModels)
}

// LoadModelsByID resolves the channel row and delegates to LoadModels.
func LoadModelsByID(ctx context.Context, st *store.Store, chID int64) error {
	row, err := st.QueryOne(ctx, "SELECT * FROM channels WHERE id = ?", chID)
	if err != nil {
		return fmt.Errorf("渠道不存在")
	}
	ch := store.RowToChannel(row)
	LoadModels(ctx, st, chID, ch.ChannelType, ch.BaseURL, deref(ch.CustomHeaders), ch.APIKey)
	return nil
}

func deref(s *string) string {
	if s == nil {
		return ""
	}
	return *s
}

// ReplaceAPIModels mirrors ChannelModelLoader's transactional replace: delete
// source='api' rows (after moving their rels aside), re-insert the fetched set
// skipping names that collide with manual models, then restore the entry-model
// relations by model name. Fetching happens before the transaction is opened.
func ReplaceAPIModels(ctx context.Context, st *store.Store, chID int64, newModels []ModelPair) {
	oldAPIRows, _ := st.Query(ctx, "SELECT id, model_name FROM channel_models WHERE channel_id = ? AND source = 'api'", chID)
	nameByOldID := map[int64]string{}
	ids := make([]string, 0, len(oldAPIRows))
	for _, r := range oldAPIRows {
		nameByOldID[r.I64("id", 0)] = r.Str("model_name")
		ids = append(ids, itoa(r.I64("id", 0)))
	}

	var relRows []store.Row
	if len(ids) > 0 {
		inList := strings.Join(ids, ",")
		relRows, _ = st.Query(ctx, "SELECT id, channel_model_id, model_id, weight, reasoning_effort, sort_order, enabled FROM model_channel_rels WHERE channel_model_id IN ("+inList+")")
	}

	// Recompute inputs outside the transaction (each call may hit the DB).
	type prepared struct {
		pair    ModelPair
		input   string
		display string
	}
	manualRows, _ := st.Query(ctx, "SELECT model_name FROM channel_models WHERE channel_id = ? AND (source IS NULL OR source != 'api')", chID)
	manualNames := map[string]bool{}
	for _, r := range manualRows {
		manualNames[r.Str("model_name")] = true
	}
	preparedModels := make([]prepared, 0, len(newModels))
	for _, mp := range newModels {
		if manualNames[mp.Name] {
			continue
		}
		display := mp.Display
		if display == "" {
			display = mp.Name
		}
		preparedModels = append(preparedModels, prepared{
			pair: mp, input: ComputeInput(ctx, st, mp.Name), display: display,
		})
	}

	now := jtime.FormatApp(time.Now().UTC())
	_ = st.Tx(ctx, func(tx *sql.Tx) error {
		if len(ids) > 0 {
			inList := strings.Join(ids, ",")
			_, _ = tx.ExecContext(ctx, "DELETE FROM model_channel_rels WHERE channel_model_id IN ("+inList+")")
		}
		_, _ = tx.ExecContext(ctx, "DELETE FROM channel_models WHERE channel_id = ? AND source = 'api'", chID)

		newIDs := map[string]int64{}
		for _, p := range preparedModels {
			res, err := tx.ExecContext(ctx,
				"INSERT INTO channel_models (channel_id, model_name, display_name, enabled, source, input, created_at) VALUES (?, ?, ?, 1, 'api', ?, ?)",
				chID, p.pair.Name, p.display, p.input, now)
			if err != nil {
				continue
			}
			if id, err := res.LastInsertId(); err == nil {
				newIDs[p.pair.Name] = id
			}
		}
		for _, r := range relRows {
			oldName := nameByOldID[r.I64("channel_model_id", 0)]
			newID, ok := newIDs[oldName]
			if oldName == "" || !ok {
				continue
			}
			_, _ = tx.ExecContext(ctx,
				"INSERT OR IGNORE INTO model_channel_rels (model_id, channel_model_id, weight, reasoning_effort, sort_order, enabled, created_at) VALUES (?, ?, ?, ?, ?, ?, ?)",
				r.I64("model_id", 0), newID, r.Int("weight", 1), r.StrPtr("reasoning_effort"), r.Int("sort_order", 0), r.Int("enabled", 1), now)
		}
		return nil
	})
}

func itoa(n int64) string { return fmt.Sprintf("%d", n) }
