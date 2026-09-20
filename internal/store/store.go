// Package store contains the data-access layer. It replaces MyBatis-Plus.
//
// Two behaviours of the original ORM are load-bearing and reproduced here:
//
//   - updateById skips null fields, so PUT handlers implement partial updates.
//     The Update* methods therefore take a column->value map containing only the
//     fields the caller actually supplied.
//   - Inserts return the new row id. MyBatis-Plus could not read SQLite
//     autoincrement ids for some tables and re-queried last_insert_rowid();
//     modernc/sqlite returns it directly, which is equivalent within the same
//     connection.
package store

import (
	"context"
	"database/sql"
	"errors"
	"fmt"
	"sort"
	"strings"
	"time"

	"github.com/my-search/my-ai-gateway/internal/jtime"
	"github.com/my-search/my-ai-gateway/internal/models"
)

// ErrNotFound is returned by single-row lookups that find nothing.
var ErrNotFound = errors.New("not found")

// Store wraps the database handle. ReadDB is an optional read-only connection
// pool; dashboard and other read-heavy queries should use QueryReadOnly to
// reduce lock contention with write transactions on the main pool.
type Store struct {
	DB     *sql.DB
	ReadDB *sql.DB
}

func New(db *sql.DB, readDB *sql.DB) *Store { return &Store{DB: db, ReadDB: readDB} }

// ---------------------------------------------------------------------------
// Generic helpers
// ---------------------------------------------------------------------------

// Row is a column-name keyed row, matching MyBatis-Plus result mapping.
type Row map[string]any

func (r Row) Str(col string) string {
	if v, ok := r[col]; ok {
		if s, ok := v.(string); ok {
			return s
		}
		if b, ok := v.([]byte); ok {
			return string(b)
		}
	}
	return ""
}

func (r Row) StrPtr(col string) *string {
	v, ok := r[col]
	if !ok || v == nil {
		return nil
	}
	var s string
	switch x := v.(type) {
	case string:
		s = x
	case []byte:
		s = string(x)
	default:
		return nil
	}
	return &s
}

func (r Row) IntPtr(col string) *int {
	v, ok := r[col]
	if !ok || v == nil {
		return nil
	}
	n := toInt(v)
	return &n
}

func (r Row) Int(col string, def int) int {
	if p := r.IntPtr(col); p != nil {
		return *p
	}
	return def
}

func (r Row) I64Ptr(col string) *int64 {
	v, ok := r[col]
	if !ok || v == nil {
		return nil
	}
	var n int64
	switch x := v.(type) {
	case int64:
		n = x
	case int:
		n = int64(x)
	case float64:
		n = int64(x)
	case []byte:
		fmt.Sscanf(string(x), "%d", &n)
	case string:
		fmt.Sscanf(x, "%d", &n)
	default:
		return nil
	}
	return &n
}

func (r Row) I64(col string, def int64) int64 {
	if p := r.I64Ptr(col); p != nil {
		return *p
	}
	return def
}

func (r Row) F64Ptr(col string) *float64 {
	v, ok := r[col]
	if !ok || v == nil {
		return nil
	}
	switch x := v.(type) {
	case float64:
		return &x
	case int64:
		f := float64(x)
		return &f
	case int:
		f := float64(x)
		return &f
	case []byte:
		var f float64
		if _, err := fmt.Sscanf(string(x), "%g", &f); err == nil {
			return &f
		}
	case string:
		var f float64
		if _, err := fmt.Sscanf(x, "%g", &f); err == nil {
			return &f
		}
	}
	return nil
}

func (r Row) TimePtr(col string) jtime.APITime {
	return jtime.APITime{T: jtime.ScanTime(r[col])}
}
func toInt(v any) int {
	switch x := v.(type) {
	case int64:
		return int(x)
	case int:
		return x
	case float64:
		return int(x)
	case bool:
		if x {
			return 1
		}
		return 0
	case []byte:
		var n int
		fmt.Sscanf(string(x), "%d", &n)
		return n
	case string:
		var n int
		fmt.Sscanf(x, "%d", &n)
		return n
	}
	return 0
}

// queryOn runs a query on the given DB and returns rows. It is the shared
// implementation behind Query and QueryReadOnly.
func (s *Store) queryOn(db *sql.DB, ctx context.Context, query string, args ...any) ([]Row, error) {
	rows, err := db.QueryContext(ctx, query, args...)
	if err != nil {
		return nil, err
	}
	defer rows.Close()

	cols, err := rows.Columns()
	if err != nil {
		return nil, err
	}
	out := make([]Row, 0, 16)
	for rows.Next() {
		vals := make([]any, len(cols))
		ptrs := make([]any, len(cols))
		for i := range vals {
			ptrs[i] = &vals[i]
		}
		if err := rows.Scan(ptrs...); err != nil {
			return nil, err
		}
		row := make(Row, len(cols))
		for i, c := range cols {
			row[c] = vals[i]
		}
		out = append(out, row)
	}
	return out, rows.Err()
}

// Query runs a SELECT and returns every row as a Row.
func (s *Store) Query(ctx context.Context, query string, args ...any) ([]Row, error) {
	return s.queryOn(s.DB, ctx, query, args...)
}

// QueryReadOnly is like Query but routes through the read-only connection pool
// when available. Falls back to the main connection when ReadDB is nil.
func (s *Store) QueryReadOnly(ctx context.Context, query string, args ...any) ([]Row, error) {
	db := s.DB
	if s.ReadDB != nil {
		db = s.ReadDB
	}
	return s.queryOn(db, ctx, query, args...)
}

// QueryOne returns the first row or ErrNotFound.
func (s *Store) QueryOne(ctx context.Context, query string, args ...any) (Row, error) {
	rows, err := s.Query(ctx, query, args...)
	if err != nil {
		return nil, err
	}
	if len(rows) == 0 {
		return nil, ErrNotFound
	}
	return rows[0], nil
}

// QueryOneReadOnly is the single-row variant of QueryReadOnly.
func (s *Store) QueryOneReadOnly(ctx context.Context, query string, args ...any) (Row, error) {
	rows, err := s.QueryReadOnly(ctx, query, args...)
	if err != nil {
		return nil, err
	}
	if len(rows) == 0 {
		return nil, ErrNotFound
	}
	return rows[0], nil
}

// Exec runs a statement, returning rows affected.
func (s *Store) Exec(ctx context.Context, query string, args ...any) (int64, error) {
	res, err := s.DB.ExecContext(ctx, query, args...)
	if err != nil {
		return 0, err
	}
	n, err := res.RowsAffected()
	if err != nil {
		return 0, nil // some drivers do not report it
	}
	return n, nil
}

// Insert runs an INSERT and returns the generated row id.
func (s *Store) Insert(ctx context.Context, query string, args ...any) (int64, error) {
	res, err := s.DB.ExecContext(ctx, query, args...)
	if err != nil {
		return 0, err
	}
	id, err := res.LastInsertId()
	if err != nil {
		return 0, nil
	}
	return id, nil
}

// Tx runs fn inside a transaction.
func (s *Store) Tx(ctx context.Context, fn func(tx *sql.Tx) error) error {
	tx, err := s.DB.BeginTx(ctx, nil)
	if err != nil {
		return err
	}
	if err := fn(tx); err != nil {
		_ = tx.Rollback()
		return err
	}
	return tx.Commit()
}

// BuildUpdate renders "UPDATE <table> SET a=?, b=? WHERE id=?" from an ordered
// column map. Iteration order is deterministic so generated SQL is stable.
func BuildUpdate(table string, sets map[string]any, where string, whereArgs ...any) (string, []any) {
	cols := make([]string, 0, len(sets))
	for c := range sets {
		cols = append(cols, c)
	}
	sort.Strings(cols)

	var sb strings.Builder
	sb.WriteString("UPDATE ")
	sb.WriteString(table)
	sb.WriteString(" SET ")
	args := make([]any, 0, len(cols)+len(whereArgs))
	for i, c := range cols {
		if i > 0 {
			sb.WriteString(", ")
		}
		sb.WriteString(c)
		sb.WriteString(" = ?")
		args = append(args, sets[c])
	}
	sb.WriteString(" WHERE ")
	sb.WriteString(where)
	args = append(args, whereArgs...)
	return sb.String(), args
}

// UpdateByID applies the partial-update semantics of MyBatis-Plus updateById:
// only the supplied columns are written.
func (s *Store) UpdateByID(ctx context.Context, table string, id int64, sets map[string]any) error {
	if len(sets) == 0 {
		return nil
	}
	q, args := BuildUpdate(table, sets, "id = ?", id)
	_, err := s.Exec(ctx, q, args...)
	return err
}

// NowApp is the timestamp the application writes for created_at/updated_at.
func NowApp() any { return jtime.FormatApp(time.Now().UTC()) }

// TimeCol formats an optional time for binding.
func TimeCol(t *time.Time) any {
	if t == nil {
		return nil
	}
	return jtime.FormatApp(*t)
}

// TimeColStr formats an optional time as a string pointer.
func TimeColStr(t *time.Time) any {
	if t == nil {
		return nil
	}
	return jtime.FormatApp(*t)
}

// ---------------------------------------------------------------------------
// Row -> entity converters
// ---------------------------------------------------------------------------

func RowToChannel(r Row) models.Channel {
	return models.Channel{
		ID:                  r.I64("id", 0),
		Name:                r.Str("name"),
		ChannelType:         r.Str("channel_type"),
		APIKey:              r.Str("api_key"),
		BaseURL:             r.Str("base_url"),
		Enabled:             r.IntPtr("enabled"),
		SortOrder:           r.IntPtr("sort_order"),
		ModelRefreshEnabled: r.IntPtr("model_refresh_enabled"),
		CustomHeaders:       r.StrPtr("custom_headers"),
		CreatedAt:           r.TimePtr("created_at"),
		UpdatedAt:           r.TimePtr("updated_at"),
		APIKeys:             nil,
		Models:              nil,
	}
}

func RowToChannelModel(r Row) models.ChannelModel {
	chID := r.I64Ptr("channel_id")
	return models.ChannelModel{
		ID:              r.I64("id", 0),
		ChannelID:       chID,
		ChannelAPIKeyID: r.I64Ptr("channel_api_key_id"),
		ModelName:       r.Str("model_name"),
		DisplayName:     r.StrPtr("display_name"),
		Enabled:         r.IntPtr("enabled"),
		LastUsedAt:      r.TimePtr("last_used_at"),
		Source:          r.StrPtr("source"),
		Input:           r.StrPtr("input"),
		CreatedAt:       r.TimePtr("created_at"),
	}
}

func RowToChannelAPIKey(r Row) models.ChannelAPIKey {
	return models.ChannelAPIKey{
		ID:        r.I64("id", 0),
		ChannelID: r.I64("channel_id", 0),
		KeyName:   r.Str("key_name"),
		APIKey:    r.Str("api_key"),
		Enabled:   r.IntPtr("enabled"),
		SortOrder: r.IntPtr("sort_order"),
		CreatedAt: r.TimePtr("created_at"),
		UpdatedAt: r.TimePtr("updated_at"),
	}
}

func RowToModel(r Row) models.Model {
	return models.Model{
		ID:                           r.I64("id", 0),
		ModelName:                    r.Str("model_name"),
		Description:                  r.StrPtr("description"),
		Strategy:                     r.StrPtr("strategy"),
		Enabled:                      r.IntPtr("enabled"),
		Hidden:                       r.IntPtr("hidden"),
		RelMode:                      r.StrPtr("rel_mode"),
		InheritFromModelID:           r.I64Ptr("inherit_from_model_id"),
		ImageInvalidateCount:         r.IntPtr("image_invalidate_count"),
		VideoInvalidateCount:         r.IntPtr("video_invalidate_count"),
		AudioInvalidateCount:         r.IntPtr("audio_invalidate_count"),
		ForceOverrideReasoningEffort: r.IntPtr("force_override_reasoning_effort"),
		CreatedAt:                    r.TimePtr("created_at"),
		UpdatedAt:                    r.TimePtr("updated_at"),
	}
}

func RowToRel(r Row) models.ModelChannelRel {
	return models.ModelChannelRel{
		ID:              r.I64("id", 0),
		ModelID:         r.I64Ptr("model_id"),
		ChannelModelID:  r.I64Ptr("channel_model_id"),
		Weight:          r.IntPtr("weight"),
		ReasoningEffort: r.StrPtr("reasoning_effort"),
		SortOrder:       r.IntPtr("sort_order"),
		Enabled:         r.IntPtr("enabled"),
		CreatedAt:       r.TimePtr("created_at"),
	}
}

func RowToCircuitConfig(r Row) models.CircuitBreakerConfig {
	return models.CircuitBreakerConfig{
		ID:                   r.I64("id", 0),
		ModelID:              r.I64Ptr("model_id"),
		RetryCount:           r.IntPtr("retry_count"),
		CircuitBreakDuration: r.IntPtr("circuit_break_duration"),
		CircuitBreakScope:    models.NormalizeScope(r.StrPtr("circuit_break_scope")),
		Enabled:              r.IntPtr("enabled"),
		CreatedAt:            r.TimePtr("created_at"),
		UpdatedAt:            r.TimePtr("updated_at"),
	}
}

func RowToCircuitState(r Row) models.CircuitBreakerState {
	return models.CircuitBreakerState{
		ID:              r.I64("id", 0),
		ChannelID:       r.I64Ptr("channel_id"),
		ChannelAPIKeyID: r.I64Ptr("channel_api_key_id"),
		ChannelModelID:  r.I64Ptr("channel_model_id"),
		IsOpen:          r.IntPtr("is_open"),
		FailCount:       r.IntPtr("fail_count"),
		OpenedAt:        r.TimePtr("opened_at"),
		ExpireAt:        r.TimePtr("expire_at"),
		CreatedAt:       r.TimePtr("created_at"),
		UpdatedAt:       r.TimePtr("updated_at"),
	}
}

func RowToAPIKey(r Row) models.APIKey {
	return models.APIKey{
		ID:         r.I64("id", 0),
		KeyName:    r.Str("key_name"),
		KeyValue:   r.Str("key_value"),
		Enabled:    r.IntPtr("enabled"),
		ShareCode:  r.StrPtr("share_code"),
		Shared:     r.IntPtr("shared"),
		LastUsedAt: r.TimePtr("last_used_at"),
		CreatedAt:  r.TimePtr("created_at"),
		UpdatedAt:  r.TimePtr("updated_at"),
	}
}

func RowToRequestLog(r Row) models.RequestLog {
	return models.RequestLog{
		ID:               r.I64("id", 0),
		TraceID:          r.Str("trace_id"),
		APIKeyName:       r.StrPtr("api_key_name"),
		GatewayAPIKeyID:  r.I64Ptr("gateway_api_key_id"),
		ModelName:        r.StrPtr("model_name"),
		ChannelModelName: r.StrPtr("channel_model_name"),
		ChannelName:      r.StrPtr("channel_name"),
		Phase:            r.Str("phase"),
		Status:           r.StrPtr("status"),
		Message:          r.StrPtr("message"),
		ReasoningEffort:  r.StrPtr("reasoning_effort"),
		RetryIndex:       r.IntPtr("retry_index"),
		ResponseTimeMs:   r.IntPtr("response_time_ms"),
		FirstByteMs:      r.IntPtr("first_byte_ms"),
		PromptTokens:     r.IntPtr("prompt_tokens"),
		CompletionTokens: r.IntPtr("completion_tokens"),
		TotalTokens:      r.IntPtr("total_tokens"),
		RequestHeaders:   r.StrPtr("request_headers"),
		RequestBody:      r.StrPtr("request_body"),
		CreatedAt:        r.TimePtr("created_at"),
	}
}

func RowToMultiModalRule(r Row) models.MultiModalRule {
	return models.MultiModalRule{
		ID:         r.I64("id", 0),
		Pattern:    r.Str("pattern"),
		AppendType: r.Str("append_type"),
		CreatedAt:  r.TimePtr("created_at"),
		UpdatedAt:  r.TimePtr("updated_at"),
	}
}

func RowToPromptInjection(r Row) models.PromptInjection {
	return models.PromptInjection{
		ID:             r.I64("id", 0),
		ModelID:        r.I64Ptr("model_id"),
		Name:           r.StrPtr("name"),
		InjectRole:     r.StrPtr("inject_role"),
		InjectPosition: r.StrPtr("inject_position"),
		Content:        r.StrPtr("content"),
		Enabled:        r.IntPtr("enabled"),
		Priority:       r.IntPtr("priority"),
		CreatedAt:      r.TimePtr("created_at"),
		UpdatedAt:      r.TimePtr("updated_at"),
	}
}

func RowToAdminConfig(r Row) models.AdminConfig {
	return models.AdminConfig{
		ID:          r.I64("id", 0),
		ConfigKey:   r.Str("config_key"),
		ConfigValue: r.Str("config_value"),
		Description: r.StrPtr("description"),
		CreatedAt:   r.TimePtr("created_at"),
		UpdatedAt:   r.TimePtr("updated_at"),
	}
}
