package db

import (
	"path/filepath"
	"testing"
)

// TestMigrateRequestLogProtocol 覆盖 v1.46.0：request_logs 新增 protocol 列，
// 用于在「请求开始」行标注客户端使用的入站协议（openai-chat / anthropic-messages /
// openai-responses / embeddings）。
//
// 该列可空，历史行自动为 NULL，因此老库升级不需要回填；候选行也保持 NULL。
func TestMigrateRequestLogProtocol(t *testing.T) {
	path := filepath.Join(t.TempDir(), "protocol.db")
	conn, err := Open(path)
	if err != nil {
		t.Fatalf("open: %v", err)
	}
	defer conn.Close()

	if err := Migrate(conn); err != nil {
		t.Fatalf("migrate: %v", err)
	}
	// 第二次迁移必须是空操作：ALTER TABLE ADD COLUMN 的重复错误被幂等忽略。
	if err := Migrate(conn); err != nil {
		t.Fatalf("second migrate: %v", err)
	}

	var colType string
	var notNull int
	if err := conn.QueryRow(
		"SELECT type, \"notnull\" FROM pragma_table_info('request_logs') WHERE name='protocol'").
		Scan(&colType, &notNull); err != nil {
		t.Fatalf("protocol column missing from request_logs: %v", err)
	}
	if notNull != 0 {
		t.Errorf("protocol is NOT NULL; historical rows would fail to upgrade")
	}

	// 历史行（该列不存在时的写入形态）与带协议的行都要能共存。
	if _, err := conn.Exec(
		`INSERT INTO request_logs (trace_id, phase, message, protocol)
		 VALUES ('t-chat', 'start', '请求开始', 'openai-chat')`); err != nil {
		t.Fatalf("insert protocol row: %v", err)
	}
	if _, err := conn.Exec(
		"INSERT INTO request_logs (trace_id, phase, message) VALUES ('t-plain', 'start', '请求开始')"); err != nil {
		t.Fatalf("insert plain row: %v", err)
	}

	var got string
	if err := conn.QueryRow(
		"SELECT protocol FROM request_logs WHERE trace_id = 't-chat'").Scan(&got); err != nil {
		t.Fatalf("read back protocol: %v", err)
	}
	if got != "openai-chat" {
		t.Errorf("protocol = %q, want openai-chat", got)
	}

	var nullCount int
	if err := conn.QueryRow(
		"SELECT COUNT(*) FROM request_logs WHERE trace_id = 't-plain' AND protocol IS NULL").Scan(&nullCount); err != nil {
		t.Fatalf("count null protocol: %v", err)
	}
	if nullCount != 1 {
		t.Errorf("rows with NULL protocol = %d, want 1 (candidate/legacy rows store NULL)", nullCount)
	}
}
