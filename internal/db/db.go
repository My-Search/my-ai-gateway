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

	slog.Info("=== 数据库迁移完成 ===")
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
