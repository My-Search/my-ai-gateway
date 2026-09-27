// Package db opens the SQLite database and runs the versioned migrations that
// the Java build shipped in update.sql.
//
// The migration algorithm is a faithful port of DatabaseMigrationRunner:
// blocks are detected by "-- VERSION:" markers, applied in FILE ORDER (the file
// is not sorted by version - v1.5.0 really does precede v1.4.0), recorded in
// db_schema_version, and "already exists" / "duplicate column" errors are
// ignored idempotently while every other error aborts startup.
//
// update.sql is embedded byte-for-byte identically to the Java resource, so an
// existing installation sees zero new migrations and a fresh installation
// reproduces the historical schema exactly.
package db

import (
	"database/sql"
	_ "embed"
	"fmt"
	"log/slog"
	"os"
	"path/filepath"
	"strings"

	_ "modernc.org/sqlite"
)

//go:embed update.sql
var updateSQL string

const versionTable = "db_schema_version"

// OpenReadOnly opens a read-only SQLite connection for dashboard / read-only
// queries. WAL mode readers do not block each other or compete for the
// immediate transaction lock, so heavy aggregation queries on request_logs
// no longer contend with write transactions.
//
// Returns nil when the db path does not exist (e.g. first run before Open),
// so callers should treat nil as a transient fallback to the write pool.
func OpenReadOnly(path string) *sql.DB {
	if _, err := os.Stat(path); err != nil {
		return nil
	}
	dsn := path + "?" + strings.Join([]string{
		"_pragma=busy_timeout(30000)",
		"_pragma=journal_mode(WAL)",
		"_pragma=synchronous(NORMAL)",
		"_pragma=cache_size(-16000)",
		"_pragma=temp_store(MEMORY)",
		"_pragma=mmap_size(268435456)",
		"_pragma=query_only(true)",   // 强制只读，禁止写操作
		"_pragma=foreign_keys(ON)",
	}, "&")

	conn, err := sql.Open("sqlite", dsn)
	if err != nil {
		return nil
	}
	// 只读池使用较少的连接，因为它们是辅助的
	conn.SetMaxOpenConns(4)
	conn.SetMaxIdleConns(2)
	return conn
}

// Open opens (creating directories as needed) the SQLite database with the same
// PRAGMA settings and pool sizing the Java build used via HikariCP.
func Open(path string) (*sql.DB, error) {
	if dir := filepath.Dir(path); dir != "" && dir != "." {
		if err := os.MkdirAll(dir, 0o755); err != nil {
			return nil, fmt.Errorf("create data dir: %w", err)
		}
	}

	dsn := path + "?" + strings.Join([]string{
		"_pragma=busy_timeout(30000)",
		"_pragma=journal_mode(WAL)",
		"_pragma=synchronous(NORMAL)",
		"_pragma=cache_size(-16000)",
		"_pragma=temp_store(MEMORY)",
		"_pragma=mmap_size(268435456)",
		"_pragma=foreign_keys(ON)",
		// Mirrors the JDBC transaction_mode=IMMEDIATE flag: transactions take the
		// write lock up front to avoid SQLITE_BUSY_SNAPSHOT on read-then-write.
		"_txlock=immediate",
	}, "&")

	conn, err := sql.Open("sqlite", dsn)
	if err != nil {
		return nil, fmt.Errorf("open sqlite: %w", err)
	}
	// HikariCP: maximum-pool-size 10, minimum-idle 2.
	conn.SetMaxOpenConns(10)
	conn.SetMaxIdleConns(2)

	if err := conn.Ping(); err != nil {
		conn.Close()
		return nil, fmt.Errorf("ping sqlite: %w", err)
	}
	return conn, nil
}

type migrationBlock struct {
	version     string
	description string
	sql         string
}

// Migrate applies every unapplied block from the embedded update.sql.
func Migrate(conn *sql.DB) error {
	slog.Info("=== 开始数据库迁移 ===")

	if _, err := conn.Exec("CREATE TABLE IF NOT EXISTS " + versionTable + " (" +
		"version TEXT PRIMARY KEY," +
		"applied_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP," +
		"description TEXT DEFAULT '')"); err != nil {
		return fmt.Errorf("create version table: %w", err)
	}

	blocks := parseMigrationFile(updateSQL)
	slog.Info("解析到迁移版本", "count", len(blocks))

	applied := make(map[string]bool, len(blocks))
	rows, err := conn.Query("SELECT version FROM " + versionTable)
	if err != nil {
		return fmt.Errorf("load applied versions: %w", err)
	}
	for rows.Next() {
		var v string
		if err := rows.Scan(&v); err != nil {
			rows.Close()
			return err
		}
		applied[v] = true
	}
	rows.Close()

	for _, b := range blocks {
		if applied[b.version] {
			slog.Debug("迁移版本已执行，跳过", "version", b.version)
			continue
		}
		slog.Info("执行迁移版本", "version", b.version, "description", b.description)
		if err := executeMigration(conn, b); err != nil {
			return fmt.Errorf("迁移版本 %s 失败: %w", b.version, err)
		}
		if _, err := conn.Exec(
			"INSERT OR IGNORE INTO "+versionTable+" (version, description) VALUES (?, ?)",
			b.version, b.description); err != nil {
			return fmt.Errorf("record version %s: %w", b.version, err)
		}
		slog.Info("迁移版本完成", "version", b.version)
	}

	if err := repairModelConfigRules(conn); err != nil {
		return fmt.Errorf("修复模型配置规则状态: %w", err)
	}

	slog.Info("=== 数据库迁移完成 ===")
	return nil
}

// createModelConfigRulesSQL 与 update.sql 的 v1.41.0 块保持一致。
const createModelConfigRulesSQL = `CREATE TABLE IF NOT EXISTS model_config_rules (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    pattern TEXT NOT NULL,
    append_type TEXT NOT NULL DEFAULT '',
    context_length INTEGER NOT NULL DEFAULT 0,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)`

// repairModelConfigRules 按“状态”而非版本号修复 v1.41.0 的历史问题：
// 该版本号曾被第一版草稿占用（建的是 context_rules / model_context_catalog），
// 记录进 db_schema_version 后，合并版的同号 SQL 块就被永久跳过——表没建、
// 旧表没搬、种子配置没写。因为迁移按版本号幂等，重写已记录的版本块无法
// 自愈，这里在每次迁移结束后检查实际状态并补齐，健康库一次探测即返回。
func repairModelConfigRules(conn *sql.DB) error {
	// 1. 表状态探测
	rows, err := conn.Query(`SELECT name FROM sqlite_master WHERE type='table'
		AND name IN ('model_config_rules','multimodal_rules','context_rules','model_context_catalog')`)
	if err != nil {
		return fmt.Errorf("探测表状态: %w", err)
	}
	exist := map[string]bool{}
	for rows.Next() {
		var name string
		if err := rows.Scan(&name); err != nil {
			rows.Close()
			return err
		}
		exist[name] = true
	}
	rows.Close()
	if err := rows.Err(); err != nil {
		return err
	}

	// 2. 渠道模型上下文列
	var hasCtxCol int
	if err := conn.QueryRow(
		`SELECT COUNT(*) FROM pragma_table_info('channel_models') WHERE name='context_length'`).Scan(&hasCtxCol); err != nil {
		return fmt.Errorf("探测 channel_models.context_length: %w", err)
	}

	// 3. models.dev 种子配置（含草稿版遗留的 models_dev_url）
	var seedCount, staleURL int
	if err := conn.QueryRow(`SELECT COUNT(*) FROM admin_config WHERE config_key IN
		('models_dev_enabled','models_dev_file','models_dev_source_url','models_dev_refresh_interval_minutes')`).
		Scan(&seedCount); err != nil {
		return fmt.Errorf("探测 models.dev 配置: %w", err)
	}
	if err := conn.QueryRow(
		`SELECT COUNT(*) FROM admin_config WHERE config_key='models_dev_url'`).Scan(&staleURL); err != nil {
		return fmt.Errorf("探测 models_dev_url: %w", err)
	}

	needRepair := !exist["model_config_rules"] || exist["multimodal_rules"] ||
		exist["context_rules"] || exist["model_context_catalog"] ||
		hasCtxCol == 0 || seedCount < 4 || staleURL > 0
	if !needRepair {
		return nil
	}
	slog.Info("检测到模型配置规则状态不完整，开始修复",
		"model_config_rules", exist["model_config_rules"],
		"multimodal_rules", exist["multimodal_rules"],
		"context_rules", exist["context_rules"],
		"context_length_column", hasCtxCol > 0,
		"seed_keys", seedCount, "stale_models_dev_url", staleURL)

	tx, err := conn.Begin()
	if err != nil {
		return err
	}
	defer tx.Rollback()

	if !exist["model_config_rules"] {
		if _, err := tx.Exec(createModelConfigRulesSQL); err != nil {
			return fmt.Errorf("创建 model_config_rules: %w", err)
		}
	}
	if exist["multimodal_rules"] {
		// 多模态规则原样并入（上下文留 0=不覆盖），行必须先搬再删表。
		if _, err := tx.Exec(`INSERT INTO model_config_rules
			(pattern, append_type, context_length, created_at, updated_at)
			SELECT pattern, append_type, 0, created_at, updated_at FROM multimodal_rules`); err != nil {
			return fmt.Errorf("迁移 multimodal_rules 行: %w", err)
		}
		if _, err := tx.Exec(`DROP TABLE multimodal_rules`); err != nil {
			return fmt.Errorf("删除 multimodal_rules: %w", err)
		}
	}
	if exist["context_rules"] {
		if _, err := tx.Exec(`INSERT INTO model_config_rules
			(pattern, append_type, context_length, created_at, updated_at)
			SELECT pattern, '', context_length, created_at, updated_at FROM context_rules`); err != nil {
			return fmt.Errorf("迁移 context_rules 行: %w", err)
		}
		if _, err := tx.Exec(`DROP TABLE context_rules`); err != nil {
			return fmt.Errorf("删除 context_rules: %w", err)
		}
	}
	if exist["model_context_catalog"] {
		// 目录数据已改为本地文件缓存，DB 目录表整体废弃。
		if _, err := tx.Exec(`DROP TABLE model_context_catalog`); err != nil {
			return fmt.Errorf("删除 model_context_catalog: %w", err)
		}
	}
	if hasCtxCol == 0 {
		if _, err := tx.Exec(`ALTER TABLE channel_models ADD COLUMN context_length INTEGER`); err != nil {
			return fmt.Errorf("添加 channel_models.context_length: %w", err)
		}
	}
	// 草稿版把下载地址存在 models_dev_url，先带上原值迁到新键，再补默认值。
	if _, err := tx.Exec(`INSERT INTO admin_config (config_key, config_value, description)
		SELECT 'models_dev_source_url', config_value,
			'用于更新本地缓存文件的下载地址（定时拉取写入 models_dev_file）'
		FROM admin_config WHERE config_key='models_dev_url'
		  AND NOT EXISTS (SELECT 1 FROM admin_config WHERE config_key='models_dev_source_url')`); err != nil {
		return fmt.Errorf("迁移 models_dev_url 值: %w", err)
	}
	if _, err := tx.Exec(`INSERT OR IGNORE INTO admin_config (config_key, config_value, description) VALUES
		('models_dev_enabled', '1', '是否启用 models.dev 数据文件缓存（1=启用，0=关闭）'),
		('models_dev_file', 'data/models.json', 'models.dev 数据文件的本地缓存路径（models.json / api.json），仅读取本地文件'),
		('models_dev_source_url', 'https://models.dev/models.json', '用于更新本地缓存文件的下载地址（定时拉取写入 models_dev_file）'),
		('models_dev_refresh_interval_minutes', '30', 'models.dev 数据文件更新间隔（分钟），默认 30 分钟；更新成功后自动重新应用模型配置')`); err != nil {
		return fmt.Errorf("写入 models.dev 配置种子: %w", err)
	}
	if staleURL > 0 {
		if _, err := tx.Exec(`DELETE FROM admin_config WHERE config_key='models_dev_url'`); err != nil {
			return fmt.Errorf("清理 models_dev_url: %w", err)
		}
	}

	if err := tx.Commit(); err != nil {
		return err
	}
	slog.Info("模型配置规则状态修复完成")
	return nil
}

// parseMigrationFile splits the SQL file on "-- VERSION:" markers, mirroring
// DatabaseMigrationRunner.parseMigrationFile. Comments other than the version
// marker are dropped from the SQL text; the first non-empty comment after a
// marker becomes the description.
func parseMigrationFile(content string) []migrationBlock {
	var blocks []migrationBlock
	var sb strings.Builder
	version := ""
	description := ""

	flush := func() {
		if version != "" && sb.Len() > 0 {
			blocks = append(blocks, migrationBlock{version, description, sb.String()})
		}
	}

	for _, line := range strings.Split(content, "\n") {
		trimmed := strings.TrimSpace(line)
		switch {
		case strings.HasPrefix(trimmed, "-- VERSION:"):
			flush()
			version = strings.TrimSpace(strings.TrimPrefix(trimmed, "-- VERSION:"))
			description = ""
			sb.Reset()
		case strings.HasPrefix(trimmed, "--") && version != "":
			desc := strings.TrimSpace(strings.TrimPrefix(trimmed, "--"))
			if desc != "" && description == "" {
				description = desc
			}
		case trimmed != "" && !strings.HasPrefix(trimmed, "--"):
			sb.WriteString(line)
			sb.WriteString("\n")
		}
	}
	flush()
	return blocks
}

// executeMigration splits the block on ';' because SQLite cannot execute
// multiple statements at once, and tolerates the idempotency errors the Java
// runner ignored.
func executeMigration(conn *sql.DB, b migrationBlock) error {
	for _, stmt := range strings.Split(b.sql, ";") {
		trimmed := strings.TrimSpace(stmt)
		if trimmed == "" {
			continue
		}
		if _, err := conn.Exec(trimmed); err != nil {
			msg := strings.ToLower(err.Error())
			if strings.Contains(msg, "already exists") || strings.Contains(msg, "duplicate column") {
				slog.Debug("幂等忽略", "error", err.Error())
				continue
			}
			return err
		}
	}
	return nil
}
