package db

import (
	"path/filepath"
	"testing"
)

// TestEnsureIncrementalVacuum verifies the one-time conversion both switches
// the persistent auto_vacuum flag and reclaims the existing freelist, and that
// a second call is a no-op (mode already incremental).
func TestEnsureIncrementalVacuum(t *testing.T) {
	path := filepath.Join(t.TempDir(), "vacuum.db")
	conn, err := Open(path)
	if err != nil {
		t.Fatalf("open: %v", err)
	}
	defer conn.Close()

	// Build a bloaty database: enough rows to span many pages, then delete most
	// of them so the file keeps a large freelist until it is rebuilt.
	if _, err := conn.Exec(`CREATE TABLE logs(id INTEGER PRIMARY KEY, payload TEXT, trace TEXT)`); err != nil {
		t.Fatalf("create: %v", err)
	}
	if _, err := conn.Exec(`INSERT INTO logs(payload, trace)
		WITH RECURSIVE seq(n) AS (SELECT 1 UNION ALL SELECT n+1 FROM seq WHERE n < 20000)
		SELECT printf('%.*c', 400, 'x'), 'trace-' || (n % 1000) FROM seq`); err != nil {
		t.Fatalf("seed: %v", err)
	}
	if _, err := conn.Exec(`DELETE FROM logs WHERE id <= 15000`); err != nil {
		t.Fatalf("delete: %v", err)
	}

	before, freelistBefore := pageStats(conn)
	if freelistBefore <= 0 {
		t.Fatalf("expected a non-empty freelist before conversion, got %d", freelistBefore)
	}

	if err := EnsureIncrementalVacuum(conn, path); err != nil {
		t.Fatalf("EnsureIncrementalVacuum: %v", err)
	}

	mode, err := AutoVacuumMode(conn)
	if err != nil {
		t.Fatalf("AutoVacuumMode: %v", err)
	}
	if mode != autoVacuumIncremental {
		t.Fatalf("auto_vacuum = %d, want %d (incremental)", mode, autoVacuumIncremental)
	}
	after, freelistAfter := pageStats(conn)
	if freelistAfter != 0 {
		t.Errorf("freelist after conversion = %d, want 0", freelistAfter)
	}
	if after >= before {
		t.Errorf("page_count did not shrink: before=%d after=%d", before, after)
	}

	// Second call must be a no-op (no error, mode unchanged).
	if err := EnsureIncrementalVacuum(conn, path); err != nil {
		t.Fatalf("second EnsureIncrementalVacuum: %v", err)
	}
}

// TestIncrementalVacuumReleasesPages verifies that after the switch, a DELETE
// followed by IncrementalVacuum shrinks the file — the behaviour the periodic
// log cleanup relies on.
func TestIncrementalVacuumReleasesPages(t *testing.T) {
	path := filepath.Join(t.TempDir(), "incr.db")
	conn, err := Open(path)
	if err != nil {
		t.Fatalf("open: %v", err)
	}
	defer conn.Close()

	if _, err := conn.Exec(`CREATE TABLE logs(id INTEGER PRIMARY KEY, payload TEXT)`); err != nil {
		t.Fatalf("create: %v", err)
	}
	if _, err := conn.Exec(`INSERT INTO logs(payload)
		WITH RECURSIVE seq(n) AS (SELECT 1 UNION ALL SELECT n+1 FROM seq WHERE n < 20000)
		SELECT printf('%.*c', 400, 'x') FROM seq`); err != nil {
		t.Fatalf("seed: %v", err)
	}
	if err := EnsureIncrementalVacuum(conn, path); err != nil {
		t.Fatalf("EnsureIncrementalVacuum: %v", err)
	}
	before, _ := pageStats(conn)

	if _, err := conn.Exec(`DELETE FROM logs WHERE id <= 15000`); err != nil {
		t.Fatalf("delete: %v", err)
	}
	// incremental_vacuum is only reachable because the DB is in the right mode.
	if err := IncrementalVacuum(conn); err != nil {
		t.Fatalf("IncrementalVacuum: %v", err)
	}
	after, freelist := pageStats(conn)
	if freelist != 0 {
		t.Errorf("freelist after incremental_vacuum = %d, want 0", freelist)
	}
	if after >= before {
		t.Errorf("page_count did not shrink after incremental_vacuum: before=%d after=%d", before, after)
	}
}
