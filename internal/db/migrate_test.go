package db

import (
	"path/filepath"
	"strings"
	"testing"
)

// TestMigrateContextFeatureSchema verifies the v1.41.0 migration lands the merged
// model-config rule table, the channel context column and the config seeds, that
// the legacy tables are gone, and that re-running Migrate is a no-op.
func TestMigrateContextFeatureSchema(t *testing.T) {
	path := filepath.Join(t.TempDir(), "test.db")
	conn, err := Open(path)
	if err != nil {
		t.Fatalf("open: %v", err)
	}
	defer conn.Close()

	if err := Migrate(conn); err != nil {
		t.Fatalf("migrate: %v", err)
	}
	// Second run must not error (idempotency).
	if err := Migrate(conn); err != nil {
		t.Fatalf("second migrate: %v", err)
	}

	var name string
	if err := conn.QueryRow(
		"SELECT name FROM sqlite_master WHERE type='table' AND name='model_config_rules'").Scan(&name); err != nil {
		t.Errorf("model_config_rules table missing: %v", err)
	}
	// The rules were merged into model_config_rules; the split tables must be gone.
	for _, table := range []string{"multimodal_rules", "context_rules", "model_context_catalog"} {
		var n int
		if err := conn.QueryRow(
			"SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name=?", table).Scan(&n); err != nil {
			t.Fatalf("table lookup: %v", err)
		}
		if n != 0 {
			t.Errorf("legacy table %s should have been dropped", table)
		}
	}

	var hasCol int
	if err := conn.QueryRow(
		"SELECT COUNT(*) FROM pragma_table_info('channel_models') WHERE name='context_length'").Scan(&hasCol); err != nil {
		t.Fatalf("pragma: %v", err)
	}
	if hasCol != 1 {
		t.Errorf("channel_models.context_length missing")
	}

	for _, key := range []string{
		"models_dev_enabled", "models_dev_file", "models_dev_source_url", "models_dev_refresh_interval_minutes",
	} {
		var n int
		if err := conn.QueryRow(
			"SELECT COUNT(*) FROM admin_config WHERE config_key=?", key).Scan(&n); err != nil {
			t.Fatalf("config query: %v", err)
		}
		if n != 1 {
			t.Errorf("config seed %s missing", key)
		}
	}
}

// TestMigrateMergesLegacyMultiModalRules verifies existing multimodal rules are
// carried over into the merged table with their context window left unset, so an
// upgrade keeps working configuration.
func TestMigrateMergesLegacyMultiModalRules(t *testing.T) {
	path := filepath.Join(t.TempDir(), "legacy.db")
	conn, err := Open(path)
	if err != nil {
		t.Fatalf("open: %v", err)
	}
	defer conn.Close()

	// Create the pre-merge schema as an upgraded deployment would have it.
	if _, err := conn.Exec(`CREATE TABLE multimodal_rules (
		id INTEGER PRIMARY KEY AUTOINCREMENT, pattern TEXT NOT NULL,
		append_type TEXT NOT NULL DEFAULT 'image', created_at TEXT, updated_at TEXT)`); err != nil {
		t.Fatal(err)
	}
	if _, err := conn.Exec(
		`INSERT INTO multimodal_rules (pattern, append_type, created_at, updated_at) VALUES ('^hy', 'image,video', '2026-01-01', '2026-01-01')`); err != nil {
		t.Fatal(err)
	}

	if err := Migrate(conn); err != nil {
		t.Fatalf("migrate: %v", err)
	}

	var pattern, appendType string
	var contextLength int64
	if err := conn.QueryRow(
		"SELECT pattern, append_type, context_length FROM model_config_rules WHERE pattern='^hy'").
		Scan(&pattern, &appendType, &contextLength); err != nil {
		t.Fatalf("legacy rule not carried over: %v", err)
	}
	if appendType != "image,video" {
		t.Errorf("append_type = %q, want image,video", appendType)
	}
	if contextLength != 0 {
		t.Errorf("context_length = %d, want 0 (unset)", contextLength)
	}
}
// TestMigrateRepairsStaleV1410 复现真实故障：v1.41.0 版本号曾被第一版草稿
// （context_rules + model_context_catalog）占用并记录，合并版重写同号块后被
// 永久跳过——model_config_rules 没建、旧规则没搬、种子配置缺失、下载地址
// 还留在 models_dev_url。修复必须按状态补（而非按版本号），且幂等。
func TestMigrateRepairsStaleV1410(t *testing.T) {
	path := filepath.Join(t.TempDir(), "stale.db")
	conn, err := Open(path)
	if err != nil {
		t.Fatalf("open: %v", err)
	}
	defer conn.Close()

	// 基线：全新库正常迁移，v1.41.0 记录在案。
	if err := Migrate(conn); err != nil {
		t.Fatalf("baseline migrate: %v", err)
	}

	// 把库改造成“版本已记录、表却没建”的用户现场。
	stmts := []string{
		`DROP TABLE model_config_rules`,
		`CREATE TABLE multimodal_rules (id INTEGER PRIMARY KEY AUTOINCREMENT, pattern TEXT NOT NULL,
			append_type TEXT NOT NULL DEFAULT 'image', created_at TEXT, updated_at TEXT)`,
		`INSERT INTO multimodal_rules (pattern, append_type, created_at, updated_at) VALUES
			('kimi', 'image', '2026-06-27', '2026-06-27'),
			('gpt', 'image,video,audio', '2026-06-27', '2026-06-27')`,
		`CREATE TABLE context_rules (id INTEGER PRIMARY KEY AUTOINCREMENT, pattern TEXT NOT NULL,
			context_length INTEGER NOT NULL DEFAULT 0, created_at TEXT, updated_at TEXT)`,
		`INSERT INTO context_rules (pattern, context_length, created_at, updated_at) VALUES
			('deepseek', 131072, '2026-09-27', '2026-09-27')`,
		`CREATE TABLE model_context_catalog (id INTEGER PRIMARY KEY, model_id TEXT, context_length INTEGER)`,
		`ALTER TABLE channel_models DROP COLUMN context_length`,
		`DELETE FROM admin_config WHERE config_key IN ('models_dev_file','models_dev_source_url')`,
		`INSERT INTO admin_config (config_key, config_value, description)
			VALUES ('models_dev_url', 'https://example.test/api.json', '草稿版下载地址')`,
	}
	for _, s := range stmts {
		if _, err := conn.Exec(s); err != nil {
			t.Fatalf("setup %s: %v", s, err)
		}
	}

	// 第二次迁移：版本块全部跳过，只有状态修复起作用。
	if err := Migrate(conn); err != nil {
		t.Fatalf("repair migrate: %v", err)
	}

	// 规则合并结果：2 条多模态 + 1 条上下文。
	var total int
	if err := conn.QueryRow(`SELECT COUNT(*) FROM model_config_rules`).Scan(&total); err != nil {
		t.Fatalf("count: %v", err)
	}
	if total != 3 {
		t.Errorf("model_config_rules rows = %d, want 3", total)
	}
	var appendType, pattern string
	var ctxLen int64
	if err := conn.QueryRow(
		`SELECT pattern, append_type, context_length FROM model_config_rules WHERE context_length > 0`).
		Scan(&pattern, &appendType, &ctxLen); err != nil {
		t.Fatalf("context rule row: %v", err)
	}
	if pattern != "deepseek" || appendType != "" || ctxLen != 131072 {
		t.Errorf("context rule = (%s, %q, %d), want (deepseek, '', 131072)", pattern, appendType, ctxLen)
	}

	// 旧表清除、上下文列补回。
	for _, table := range []string{"multimodal_rules", "context_rules", "model_context_catalog"} {
		var n int
		if err := conn.QueryRow(
			`SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name=?`, table).Scan(&n); err != nil {
			t.Fatal(err)
		}
		if n != 0 {
			t.Errorf("legacy table %s should be dropped", table)
		}
	}
	var hasCol int
	if err := conn.QueryRow(
		`SELECT COUNT(*) FROM pragma_table_info('channel_models') WHERE name='context_length'`).Scan(&hasCol); err != nil {
		t.Fatal(err)
	}
	if hasCol != 1 {
		t.Error("channel_models.context_length should be re-added")
	}

	// 下载地址：原值迁到新键，旧键清理，缺失种子补齐。
	var srcURL string
	if err := conn.QueryRow(
		`SELECT config_value FROM admin_config WHERE config_key='models_dev_source_url'`).Scan(&srcURL); err != nil {
		t.Fatalf("source url: %v", err)
	}
	if srcURL != "https://example.test/api.json" {
		t.Errorf("source url = %q, want carried-over value", srcURL)
	}
	for _, key := range []string{
		"models_dev_enabled", "models_dev_file", "models_dev_source_url", "models_dev_refresh_interval_minutes",
	} {
		var n int
		if err := conn.QueryRow(`SELECT COUNT(*) FROM admin_config WHERE config_key=?`, key).Scan(&n); err != nil {
			t.Fatal(err)
		}
		if n != 1 {
			t.Errorf("config seed %s missing", key)
		}
	}
	var stale int
	if err := conn.QueryRow(`SELECT COUNT(*) FROM admin_config WHERE config_key='models_dev_url'`).Scan(&stale); err != nil {
		t.Fatal(err)
	}
	if stale != 0 {
		t.Error("stale models_dev_url should be removed")
	}

	// 幂等：再跑两次不报错、不重复搬行。
	if err := Migrate(conn); err != nil {
		t.Fatalf("third migrate: %v", err)
	}
	if err := Migrate(conn); err != nil {
		t.Fatalf("fourth migrate: %v", err)
	}
	if err := conn.QueryRow(`SELECT COUNT(*) FROM model_config_rules`).Scan(&total); err != nil {
		t.Fatal(err)
	}
	if total != 3 {
		t.Errorf("after re-runs rows = %d, want 3 (no duplicates)", total)
	}
}

// TestMigrateModelGroupSchema verifies the v1.43.0 migration creates the model
// group tables and constraints, and stays idempotent on a re-run.
func TestMigrateModelGroupSchema(t *testing.T) {
	path := filepath.Join(t.TempDir(), "groups.db")
	conn, err := Open(path)
	if err != nil {
		t.Fatalf("open: %v", err)
	}
	defer conn.Close()

	if err := Migrate(conn); err != nil {
		t.Fatalf("migrate: %v", err)
	}
	if err := Migrate(conn); err != nil {
		t.Fatalf("second migrate: %v", err)
	}

	for _, table := range []string{"model_groups", "model_group_members", "model_group_rels"} {
		var name string
		if err := conn.QueryRow(
			"SELECT name FROM sqlite_master WHERE type='table' AND name=?", table).Scan(&name); err != nil {
			t.Errorf("table %s missing: %v", table, err)
		}
	}

	// The strategy default must be random (the group-level balancing default).
	var def string
	if err := conn.QueryRow(
		"SELECT dflt_value FROM pragma_table_info('model_groups') WHERE name='strategy'").Scan(&def); err != nil {
		t.Fatalf("pragma: %v", err)
	}
	if !strings.Contains(def, "random") {
		t.Errorf("model_groups.strategy default = %s, want random", def)
	}

	// A duplicate group name would list the group twice; a duplicate member would
	// double its weight in routing. Both must be rejected by UNIQUE constraints.
	if _, err := conn.Exec(
		"INSERT INTO model_groups (name, strategy, sticky, enabled) VALUES ('g', 'random', 0, 1)"); err != nil {
		t.Fatalf("insert group: %v", err)
	}
	if _, err := conn.Exec(
		"INSERT INTO model_groups (name, strategy, sticky, enabled) VALUES ('g', 'random', 0, 1)"); err == nil {
		t.Error("duplicate group name was accepted; the UNIQUE(name) constraint is missing")
	}

	// Members reference channel models, and foreign keys are enforced.
	if _, err := conn.Exec(
		"INSERT INTO channels (name, channel_type, base_url) VALUES ('c', 'openai', 'http://x')"); err != nil {
		t.Fatalf("insert channel: %v", err)
	}
	if _, err := conn.Exec(
		"INSERT INTO channel_models (channel_id, model_name) VALUES (1, 'm')"); err != nil {
		t.Fatalf("insert channel model: %v", err)
	}
	if _, err := conn.Exec(
		"INSERT INTO model_group_members (group_id, channel_model_id, weight) VALUES (1, 1, 2)"); err != nil {
		t.Fatalf("insert member: %v", err)
	}
	if _, err := conn.Exec(
		"INSERT INTO model_group_members (group_id, channel_model_id, weight) VALUES (1, 1, 5)"); err == nil {
		t.Error("duplicate group member was accepted; the UNIQUE(group_id, channel_model_id) constraint is missing")
	}
}
