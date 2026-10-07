package db

import (
	"path/filepath"
	"testing"
)

// TestMigrateRequestLogRouteSource 覆盖 v1.44.0：request_logs 新增 route_source 列，
// 用于标注请求日志里的「小组粘性命中 / 粘性回退」。
//
// 该列可空，历史行自动为 NULL（= 非粘性），因此老库升级不需要回填。
func TestMigrateRequestLogRouteSource(t *testing.T) {
	path := filepath.Join(t.TempDir(), "route-source.db")
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
		"SELECT type, \"notnull\" FROM pragma_table_info('request_logs') WHERE name='route_source'").
		Scan(&colType, &notNull); err != nil {
		t.Fatalf("route_source column missing from request_logs: %v", err)
	}
	if notNull != 0 {
		t.Errorf("route_source is NOT NULL; historical rows would fail to upgrade")
	}

	// 历史行（该列不存在时的写入形态）与带标记的行都要能共存。
	if _, err := conn.Exec(
		`INSERT INTO request_logs (trace_id, phase, message, route_source)
		 VALUES ('t-sticky', 'start', '路由到 c/k/m', 'sticky')`); err != nil {
		t.Fatalf("insert sticky row: %v", err)
	}
	if _, err := conn.Exec(
		"INSERT INTO request_logs (trace_id, phase, message) VALUES ('t-plain', 'start', '路由到 c/k/m')"); err != nil {
		t.Fatalf("insert plain row: %v", err)
	}

	var got string
	if err := conn.QueryRow(
		"SELECT route_source FROM request_logs WHERE trace_id = 't-sticky'").Scan(&got); err != nil {
		t.Fatalf("read back route_source: %v", err)
	}
	if got != "sticky" {
		t.Errorf("route_source = %q, want sticky", got)
	}

	var nullCount int
	if err := conn.QueryRow(
		"SELECT COUNT(*) FROM request_logs WHERE trace_id = 't-plain' AND route_source IS NULL").Scan(&nullCount); err != nil {
		t.Fatalf("count null route_source: %v", err)
	}
	if nullCount != 1 {
		t.Errorf("rows with NULL route_source = %d, want 1 (non-sticky routing stores NULL)", nullCount)
	}
}
