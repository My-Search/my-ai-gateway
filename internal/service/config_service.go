// Package service holds business logic that mirrors the Java @Service classes.
package service

import (
	"context"
	"database/sql"
	"fmt"
	"strings"

	"github.com/my-search/my-ai-gateway/internal/store"
)

// Config keys and their Java defaults (AdminConfigService).
const (
	KeyUsername                        = "username"
	KeyPassword                        = "password"
	KeyLogRetentionDays                = "log_retention_days"
	KeyLogCleanupEnabled               = "log_cleanup_enabled"
	KeyRequestBodyTTLHours             = "request_body_ttl_hours"
	KeyRetryFailTTLHours               = "retry_fail_ttl_hours"
	KeyTimeoutMinSeconds               = "timeout_min_seconds"
	KeyTimeoutMaxSeconds               = "timeout_max_seconds"
	KeyRequestDataSaveLevel            = "request_data_save_level"
	KeyCircuitProbeIntervalMinutes     = "circuit_breaker_probe_interval_minutes"
	KeyCircuitProbeThrottleSeconds     = "circuit_breaker_probe_throttle_seconds"
	KeyChannelModelRefreshIntervalMins = "channel_model_refresh_interval_minutes"
)

// SystemConfigOrder is the exact insertion order of GET /admin/api/config/system.
var SystemConfigOrder = []string{
	KeyLogRetentionDays,
	KeyLogCleanupEnabled,
	KeyRequestBodyTTLHours,
	KeyRetryFailTTLHours,
	KeyTimeoutMinSeconds,
	KeyTimeoutMaxSeconds,
	KeyRequestDataSaveLevel,
	KeyCircuitProbeIntervalMinutes,
	KeyCircuitProbeThrottleSeconds,
	KeyChannelModelRefreshIntervalMins,
}

// systemConfigDefaults mirrors the fallbacks in AdminConfigService.
var systemConfigDefaults = map[string]string{
	KeyLogRetentionDays:                "7",
	KeyLogCleanupEnabled:               "1",
	KeyRequestBodyTTLHours:             "4",
	KeyRetryFailTTLHours:               "",
	KeyTimeoutMinSeconds:               "20",
	KeyTimeoutMaxSeconds:               "60",
	KeyRequestDataSaveLevel:            "info",
	KeyCircuitProbeIntervalMinutes:     "30",
	KeyCircuitProbeThrottleSeconds:     "6",
	KeyChannelModelRefreshIntervalMins: "30",
}

// ConfigService reads and writes admin_config.
type ConfigService struct {
	S *store.Store
}

func NewConfigService(s *store.Store) *ConfigService { return &ConfigService{S: s} }

// GetValue returns the stored value, or the supplied default when absent.
func (c *ConfigService) GetValue(ctx context.Context, key, def string) string {
	row, err := c.S.QueryOne(ctx, "SELECT config_value FROM admin_config WHERE config_key = ?", key)
	if err != nil {
		return def
	}
	v := row.Str("config_value")
	if v == "" && def != "" {
		// Matches the Java behaviour of substituting defaults when the stored
		// value is missing; an empty string is a legitimate stored value only
		// for retry_fail_ttl_hours, which has an empty default anyway.
		if key == KeyRetryFailTTLHours {
			return ""
		}
		if _, ok := systemConfigDefaults[key]; ok {
			return def
		}
	}
	if v == "" {
		return def
	}
	return v
}

// GetUsername / GetPassword read the admin credentials (stored Base64 for the
// password, exactly like the Java version).
func (c *ConfigService) GetUsername(ctx context.Context) string {
	return c.GetValue(ctx, KeyUsername, "")
}

func (c *ConfigService) GetPassword(ctx context.Context) string {
	return c.GetValue(ctx, KeyPassword, "")
}

// HasAdminAccount mirrors AdminConfigService.hasAdminAccount.
func (c *ConfigService) HasAdminAccount(ctx context.Context) bool {
	u := c.GetUsername(ctx)
	p := c.GetPassword(ctx)
	return strings.TrimSpace(u) != "" && strings.TrimSpace(p) != ""
}

// SetAdminAccount writes username/password (password expected already Base64).
func (c *ConfigService) SetAdminAccount(ctx context.Context, username, passwordB64 string) error {
	now := store.NowApp()
	for k, v := range map[string]string{KeyUsername: username, KeyPassword: passwordB64} {
		if err := c.upsert(ctx, k, v, now); err != nil {
			return err
		}
	}
	return nil
}

// UpdateSystemConfig writes only the supplied keys.
func (c *ConfigService) UpdateSystemConfig(ctx context.Context, values map[string]string) error {
	now := store.NowApp()
	for k, v := range values {
		if err := c.upsert(ctx, k, v, now); err != nil {
			return err
		}
	}
	return nil
}

func (c *ConfigService) upsert(ctx context.Context, key, value string, now any) error {
	n, err := c.S.Exec(ctx,
		"UPDATE admin_config SET config_value = ?, updated_at = ? WHERE config_key = ?",
		value, now, key)
	if err != nil {
		return err
	}
	if n > 0 {
		return nil
	}
	_, err = c.S.Exec(ctx,
		"INSERT INTO admin_config (config_key, config_value, description, created_at, updated_at) VALUES (?, ?, '', ?, ?)",
		key, value, now, now)
	return err
}

// GetSystemConfig returns the ordered key/value map exposed by the API.
func (c *ConfigService) GetSystemConfig(ctx context.Context) map[string]string {
	out := make(map[string]string, len(SystemConfigOrder))
	for _, k := range SystemConfigOrder {
		out[k] = c.GetValue(ctx, k, systemConfigDefaults[k])
	}
	return out
}

// IntValue parses a config value into an int with a fallback, mirroring the
// clamping the Java readers applied.
func (c *ConfigService) IntValue(ctx context.Context, key string, def int) int {
	v := strings.TrimSpace(c.GetValue(ctx, key, systemConfigDefaults[key]))
	if v == "" {
		return def
	}
	n := 0
	if _, err := fmt.Sscan(v, &n); err != nil {
		return def
	}
	return n
}

// FloatValue parses a config value into a float with a fallback.
func (c *ConfigService) FloatValue(ctx context.Context, key string, def float64) float64 {
	v := strings.TrimSpace(c.GetValue(ctx, key, systemConfigDefaults[key]))
	if v == "" {
		return def
	}
	var f float64
	if _, err := fmt.Sscan(v, &f); err != nil {
		return def
	}
	return f
}

// SQLDB exposes the raw handle for callers that need it.
func (c *ConfigService) SQLDB() *sql.DB { return c.S.DB }
