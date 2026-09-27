// models.dev data-file cache.
//
// The models.dev catalog is consumed as a local file (default data/models.json,
// configurable via admin_config.models_dev_file). A background task periodically
// downloads the upstream document into that file; whenever the file changes the
// catalog is reloaded and every channel model is recomputed.
//
// This module provides the "uploaded"/upstream side of a channel model's
// configuration (context window + input modalities). Our own rules
// (model_config_rules) always take precedence — see ResolveModelConfig.
package channelload

import (
	"encoding/json"
	"fmt"
	"io"
	"log/slog"
	"net/http"
	"os"
	"path/filepath"
	"regexp"
	"strings"
	"sync"
	"time"
)

// ContextToleranceTokens is the slack added to a model's context window before a
// request is considered too large: a request is skipped only when the estimated
// size exceeds window + tolerance.
const ContextToleranceTokens = 5000

// CatalogEntry is one normalized models.dev record.
type CatalogEntry struct {
	ModelID       string
	ShortID       string
	ContextLength int64
	InputTypes    string // normalized, comma separated, always starts with "text"
}

type catalogIndex struct {
	byModelID map[string]*CatalogEntry
	byShortID map[string]*CatalogEntry
}

var (
	catalogCacheMu  sync.RWMutex
	catalogCache    *catalogIndex
	catalogCacheKey string // file path + mtime + size of the loaded file
)

// CatalogFileState describes the currently loaded data file.
type CatalogFileState struct {
	Path      string
	ModTime   time.Time
	Size      int64
	Count     int
	LoadedAt  time.Time
	LastError string
}

var (
	catalogStateMu sync.RWMutex
	catalogState   CatalogFileState
)

// CatalogStatus reports the loaded cache file's state for the admin UI.
func CatalogStatus() CatalogFileState {
	catalogStateMu.RLock()
	defer catalogStateMu.RUnlock()
	return catalogState
}

// SetCatalogError records the most recent load/download failure for display.
func SetCatalogError(msg string) {
	catalogStateMu.Lock()
	catalogState.LastError = msg
	catalogStateMu.Unlock()
}

// catalogBaseInputTypes returns the models.dev baseline modalities for a model.
func catalogBaseInputTypes(modelName string) []string {
	e := catalogLookup(modelName)
	if e == nil || e.InputTypes == "" {
		return nil
	}
	return strings.Split(e.InputTypes, ",")
}

// catalogContextLength returns the models.dev context window for a model, or 0.
func catalogContextLength(modelName string) int64 {
	if e := catalogLookup(modelName); e != nil {
		return e.ContextLength
	}
	return 0
}

// CatalogInfo returns the baseline context window and modalities for a model,
// used by the admin test view to show what the uploaded data provides.
func CatalogInfo(modelName string) (contextLength int64, inputTypes string) {
	e := catalogLookup(modelName)
	if e == nil {
		return 0, ""
	}
	return e.ContextLength, e.InputTypes
}

// loadCatalogIndex returns the parsed catalog, re-reading the file when its
// mtime/size changed since the last load.
func loadCatalogIndex(path string) *catalogIndex {
	st, err := os.Stat(path)
	if err != nil {
		// Missing file: an empty catalog is a valid state (no baseline data).
		catalogCacheMu.Lock()
		catalogCache = &catalogIndex{byModelID: map[string]*CatalogEntry{}, byShortID: map[string]*CatalogEntry{}}
		catalogCacheKey = ""
		catalogCacheMu.Unlock()
		return nil
	}
	key := fmt.Sprintf("%s|%d|%d", path, st.ModTime().UnixNano(), st.Size())

	catalogCacheMu.RLock()
	if catalogCache != nil && catalogCacheKey == key {
		idx := catalogCache
		catalogCacheMu.RUnlock()
		return idx
	}
	catalogCacheMu.RUnlock()

	raw, err := os.ReadFile(path)
	if err != nil {
		slog.Warn("读取 models.dev 数据文件失败", "path", path, "error", err)
		return catalogCache // keep the previous index
	}
	entries, err := ParseCatalog(raw)
	if err != nil {
		slog.Warn("解析 models.dev 数据文件失败", "path", path, "error", err)
		return catalogCache
	}
	idx := buildCatalogIndex(entries)

	catalogCacheMu.Lock()
	catalogCache = idx
	catalogCacheKey = key
	catalogCacheMu.Unlock()

	catalogStateMu.Lock()
	catalogState = CatalogFileState{
		Path: path, ModTime: st.ModTime(), Size: st.Size(),
		Count: len(entries), LoadedAt: time.Now(),
	}
	catalogStateMu.Unlock()

	slog.Info("models.dev 数据文件已加载", "path", path, "models", len(entries))
	return idx
}

func buildCatalogIndex(entries []CatalogEntry) *catalogIndex {
	idx := &catalogIndex{byModelID: map[string]*CatalogEntry{}, byShortID: map[string]*CatalogEntry{}}
	for i := range entries {
		e := &entries[i]
		register := func(m map[string]*CatalogEntry, key string) {
			key = normalizeModelKey(key)
			if key == "" {
				return
			}
			// The same name is served by many providers. Keep the largest window:
			// a too-small value would skip working candidates (hard failure),
			// while a too-large one merely falls back to the existing
			// upstream-error failover.
			if prev, ok := m[key]; !ok || e.ContextLength > prev.ContextLength {
				m[key] = e
			}
		}
		register(idx.byModelID, e.ModelID)
		register(idx.byShortID, e.ShortID)
	}
	return idx
}

// normalizeModelKey makes catalog lookups tolerant of the naming differences
// between models.dev's canonical slugs and the IDs providers actually return.
// models.dev writes some lab models with dots (anthropic/claude-sonnet-4.5)
// while the Anthropic API reports them with hyphens (claude-sonnet-4-5), so a
// literal match would silently miss the baseline for those models.
func normalizeModelKey(s string) string {
	s = strings.ToLower(strings.TrimSpace(s))
	if s == "" {
		return ""
	}
	return strings.ReplaceAll(s, ".", "-")
}

// CatalogPath is the configured data-file path, resolved by the caller.
var (
	catalogPathMu sync.RWMutex
	catalogPath   string
)

// SetCatalogPath points the catalog at a data file (from admin_config).
func SetCatalogPath(path string) {
	catalogPathMu.Lock()
	catalogPath = path
	catalogPathMu.Unlock()
}

// CatalogPath returns the currently configured data-file path.
func CatalogPath() string {
	catalogPathMu.RLock()
	defer catalogPathMu.RUnlock()
	return catalogPath
}

// ReloadCatalog forces a re-read of the data file (mtime cache is bypassed by
// clearing the cached key first).
func ReloadCatalog() int {
	path := CatalogPath()
	catalogCacheMu.Lock()
	catalogCacheKey = ""
	catalogCacheMu.Unlock()
	loadCatalogIndex(path)
	return CatalogStatus().Count
}

// catalogLookup finds the catalog entry best matching a local channel model name.
func catalogLookup(modelName string) *CatalogEntry {
	path := CatalogPath()
	if path == "" {
		return nil
	}
	idx := loadCatalogIndex(path)
	if idx == nil || len(idx.byModelID) == 0 {
		return nil
	}
	name := normalizeModelKey(modelName)
	if name == "" {
		return nil
	}
	if e, ok := idx.byModelID[name]; ok {
		return e
	}
	if e, ok := idx.byShortID[name]; ok {
		return e
	}
	short := name
	if i := strings.LastIndexByte(short, '/'); i >= 0 {
		short = short[i+1:]
	}
	if e, ok := idx.byShortID[short]; ok {
		return e
	}
	// Dated variants ("-20250929" / "-2025-09-29") often have no catalog entry of
	// their own; retry without the trailing date.
	if base := stripDateSuffix(short); base != short {
		if e, ok := idx.byShortID[base]; ok {
			return e
		}
	}
	// 渠道常给模型名附加 -preview / -free 这类特殊后缀（不算模型本身的一部
	// 分）：全名与日期变体都匹配不到时，逐层剥掉特殊后缀与日期再查，让
	// xxx/hy4-preview 回退到目录里的 yyy/hy4。每剥一层都先查一次，保证目录
	// 里存在更具体的名称（hy4-preview 本身）时优先于基础名。
	cur := short
	for i := 0; i < 4; i++ {
		next := stripDateSuffix(stripSpecialSuffix(cur))
		if next == cur || next == "" {
			break
		}
		if e, ok := idx.byShortID[next]; ok {
			return e
		}
		cur = next
	}
	return nil
}

// dateSuffixRe matches a trailing "-YYYYMMDD" or "-YYYY-MM-DD" segment, which
// dated model variants append and which models.dev entries usually omit.
var dateSuffixRe = regexp.MustCompile(`-(?:\d{8}|\d{4}-\d{2}-\d{2})$`)

// stripDateSuffix removes a trailing date segment.
func stripDateSuffix(s string) string {
	return dateSuffixRe.ReplaceAllString(s, "")
}

// specialSuffixRe 匹配渠道附加在模型名后的特殊后缀：这是渠道自己的标记，
// 不算模型名的一部分，models.dev 目录里通常没有带这些后缀的条目。
var specialSuffixRe = regexp.MustCompile(`-(?:experimental|exp|preview|free|beta|test|demo)$`)

// stripSpecialSuffix 去掉模型名末尾的一层特殊后缀。
func stripSpecialSuffix(s string) string {
	return specialSuffixRe.ReplaceAllString(s, "")
}

// normalizeInputTypes keeps only the media types the relay can detect
// (image/video/audio) and always includes text.
func normalizeInputTypes(mods []string) string {
	seen := map[string]bool{"text": true}
	var others []string
	for _, m := range mods {
		switch t := strings.ToLower(strings.TrimSpace(m)); t {
		case "text":
			// already the baseline
		case "image", "video", "audio":
			if !seen[t] {
				seen[t] = true
				others = append(others, t)
			}
		}
	}
	if len(others) == 0 {
		return "text"
	}
	return "text," + strings.Join(others, ",")
}

// catalogRecord tolerates both upstream shapes:
//   - flat catalog (models.json): {id, context_length, architecture.input_modalities}
//   - provider map (api.json):    {limit.context, modalities.input}
type catalogRecord struct {
	ID            string `json:"id"`
	ContextLength int64  `json:"context_length"`
	Architecture  struct {
		InputModalities []string `json:"input_modalities"`
	} `json:"architecture"`
	Limit struct {
		Context int64 `json:"context"`
	} `json:"limit"`
	Modalities struct {
		Input []string `json:"input"`
	} `json:"modalities"`
}

func (r catalogRecord) context() int64 {
	if r.ContextLength > 0 {
		return r.ContextLength
	}
	return r.Limit.Context
}

func (r catalogRecord) inputs() []string {
	if len(r.Architecture.InputModalities) > 0 {
		return r.Architecture.InputModalities
	}
	return r.Modalities.Input
}

// ParseCatalog normalizes either supported models.dev payload shape.
func ParseCatalog(raw []byte) ([]CatalogEntry, error) {
	// Flat shape: {"data": [ {...}, ... ]}
	var flat struct {
		Data []catalogRecord `json:"data"`
	}
	if err := json.Unmarshal(raw, &flat); err == nil && len(flat.Data) > 0 {
		seen := map[string]bool{}
		out := make([]CatalogEntry, 0, len(flat.Data))
		for _, rec := range flat.Data {
			if rec.ID == "" || seen[rec.ID] {
				continue
			}
			seen[rec.ID] = true
			out = append(out, buildCatalogEntry("", rec.ID, rec))
		}
		return out, nil
	}

	// Provider map shape: {"<provider>": {"models": {"<id>": {...}}}}
	var providerMap map[string]struct {
		Models map[string]catalogRecord `json:"models"`
	}
	if err := json.Unmarshal(raw, &providerMap); err == nil {
		seen := map[string]bool{}
		var out []CatalogEntry
		for providerID, p := range providerMap {
			for key, rec := range p.Models {
				id := rec.ID
				if id == "" {
					id = key
				}
				if id == "" || seen[id] {
					continue
				}
				seen[id] = true
				out = append(out, buildCatalogEntry(providerID, id, rec))
			}
		}
		if len(out) > 0 {
			return out, nil
		}
	}

	// Live models.dev /models.json shape: a record keyed by the full model id,
	// {"zhipuai/glm-5.3": {"limit": {...}, "modalities": {...}}}. Reached last
	// because it would otherwise swallow every other shape (a JSON object always
	// unmarshals into map[string]struct{Models ...} with empty Models).
	var record map[string]catalogRecord
	if err := json.Unmarshal(raw, &record); err == nil && len(record) > 0 {
		seen := map[string]bool{}
		out := make([]CatalogEntry, 0, len(record))
		for key, rec := range record {
			id := rec.ID
			if id == "" {
				id = key
			}
			if id == "" || seen[id] {
				continue
			}
			seen[id] = true
			// The key already carries the provider prefix when one exists.
			out = append(out, buildCatalogEntry("", id, rec))
		}
		if len(out) > 0 {
			return out, nil
		}
	}
	return nil, fmt.Errorf("无法识别的 models.dev 数据格式")
}

func buildCatalogEntry(providerID, id string, rec catalogRecord) CatalogEntry {
	fullID := id
	if providerID != "" && !strings.Contains(id, "/") {
		fullID = providerID + "/" + id
	}
	short := fullID
	if i := strings.LastIndexByte(short, '/'); i >= 0 {
		short = short[i+1:]
	}
	return CatalogEntry{
		ModelID:       fullID,
		ShortID:       short,
		ContextLength: rec.context(),
		InputTypes:    normalizeInputTypes(rec.inputs()),
	}
}

// DownloadCatalog fetches the upstream document and writes it to destPath
// atomically (temp file + rename). Returns the number of entries written.
func DownloadCatalog(url, destPath string) (int, error) {
	u := strings.TrimSpace(url)
	if u == "" {
		return 0, fmt.Errorf("models.dev 下载地址为空")
	}
	if strings.TrimSpace(destPath) == "" {
		return 0, fmt.Errorf("models.dev 数据文件路径为空")
	}
	client := &http.Client{Timeout: 60 * time.Second}
	req, err := http.NewRequest(http.MethodGet, u, nil)
	if err != nil {
		return 0, err
	}
	req.Header.Set("Accept", "application/json")
	resp, err := client.Do(req)
	if err != nil {
		return 0, err
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		return 0, fmt.Errorf("status %d", resp.StatusCode)
	}
	raw, err := io.ReadAll(io.LimitReader(resp.Body, 64<<20))
	if err != nil {
		return 0, err
	}
	// Validate before replacing the file so a bad upstream response cannot wipe
	// the working cache.
	entries, err := ParseCatalog(raw)
	if err != nil {
		return 0, err
	}
	if len(entries) == 0 {
		return 0, fmt.Errorf("models.dev 返回空目录")
	}
	if err := os.MkdirAll(filepath.Dir(destPath), 0o755); err != nil {
		return 0, err
	}
	tmp := destPath + ".tmp"
	if err := os.WriteFile(tmp, raw, 0o644); err != nil {
		return 0, err
	}
	if err := os.Rename(tmp, destPath); err != nil {
		_ = os.Remove(tmp)
		return 0, err
	}
	return len(entries), nil
}

// FallbackSourceURL is tried when the configured source URL cannot be fetched
// (e.g. models.dev itself is unreachable from this network).
const FallbackSourceURL = "https://raw.githubusercontent.com/anomalyco/models.dev/refs/heads/dev/models.json"

// DownloadCatalogWithFallback downloads from primary and, when that fails,
// retries with the fallback mirror. Returns the URL that succeeded.
func DownloadCatalogWithFallback(primary, destPath string) (string, int, error) {
	return downloadCatalogWithFallback(primary, FallbackSourceURL, destPath)
}

func downloadCatalogWithFallback(primary, fallback, destPath string) (string, int, error) {
	primary = strings.TrimSpace(primary)
	fallback = strings.TrimSpace(fallback)
	n, err := DownloadCatalog(primary, destPath)
	if err == nil {
		return primary, n, nil
	}
	// 空地址是配置错误；主地址即备用地址时重试没有意义，都直接返回原错误。
	if primary == "" || primary == fallback {
		return "", 0, err
	}
	fbN, fbErr := DownloadCatalog(fallback, destPath)
	if fbErr != nil {
		return "", 0, fmt.Errorf("主地址下载失败: %s；备用地址下载失败: %w", err, fbErr)
	}
	return fallback, fbN, nil
}