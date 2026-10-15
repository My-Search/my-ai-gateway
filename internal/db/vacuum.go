package db

// Space reclamation.
//
// SQLite never returns freed pages to the operating system unless the database
// is built in an auto-vacuum mode. The historical schema (and the Java build)
// used the default, auto_vacuum=NONE: the 30-minute log cleanup only issued
// DELETE, so every expired request_logs row left its pages stranded inside the
// file. The result was the classic "database file is 4.6 GB but only holds
// 0.7 GB of data" bloat, reclaimable only by a manual, offline VACUUM.
//
// enableIncremental mode fixes that for every deployment, past and future:
//
//   - auto_vacuum=INCREMENTAL is a *persistent* database-header flag. Once set
//     it stays set across connections and even across plain VACUUMs.
//   - Switching an existing database into that mode is the one operation
//     SQLite refuses to do lazily: PRAGMA auto_vacuum=INCREMENTAL is recorded
//     but only takes effect after a VACUUM rebuilds the file. Doing both in the
//     same session is therefore the migration itself.
//   - From then on, pages freed by DELETE merely land on the freelist, and
//     PRAGMA incremental_vacuum hands them back to the OS without the full,
//     2x-space, exclusive-lock cost of a VACUUM.

import (
	"context"
	"database/sql"
	"fmt"
	"log/slog"
	"os"
	"path/filepath"
)

// autoVacuumNone / autoVacuumFull / autoVacuumIncremental are the values the
// auto_vacuum pragma reports (0/1/2).
const (
	autoVacuumNone        = 0
	autoVacuumIncremental = 2
)

// AutoVacuumMode reports the database's persistent auto_vacuum setting.
func AutoVacuumMode(conn *sql.DB) (int, error) {
	var mode int
	if err := conn.QueryRow("PRAGMA auto_vacuum").Scan(&mode); err != nil {
		return 0, fmt.Errorf("read auto_vacuum: %w", err)
	}
	return mode, nil
}

// EnsureIncrementalVacuum switches the database into auto_vacuum=INCREMENTAL,
// running the one-time VACUUM that makes the switch take effect and reclaiming
// the entire existing freelist in the process. It is a no-op once the database
// is already in incremental mode, so it costs nothing on ordinary restarts.
//
// path is the database file, used only to size the disk-space guard: VACUUM
// needs temporary space roughly equal to the compacted database. When that
// space is not available the conversion is skipped with a warning rather than
// risking a mid-VACUUM failure; the service keeps running and the conversion is
// retried on the next start.
func EnsureIncrementalVacuum(conn *sql.DB, path string) error {
	mode, err := AutoVacuumMode(conn)
	if err != nil {
		return err
	}
	if mode == autoVacuumIncremental {
		return nil
	}

	fileSize := int64(0)
	if fi, err := os.Stat(path); err == nil {
		fileSize = fi.Size()
	}
	if avail, ok := freeBytes(filepath.Dir(path)); ok {
		if need, needOK := vacuumTempEstimate(conn, fileSize); needOK && avail < need {
			slog.Warn("磁盘可用空间不足以启用增量回收，已跳过一次性 VACUUM；"+
				"释放空间后重启即可自动完成，或手动执行 VACUUM",
				"needBytes", need, "availBytes", avail, "fileBytes", fileSize)
			return nil
		}
	}

	slog.Info("检测到数据库未启用增量回收，正在执行一次性 VACUUM（将历史空闲页归还磁盘）…",
		"auto_vacuum", mode, "fileBytes", fileSize)

	// PRAGMA auto_vacuum is connection-local until a VACUUM rebuilds the file, so
	// both statements must run on the SAME connection. The pool would otherwise
	// be free to hand each Exec a different connection and the setting would be
	// silently lost.
	ctx := context.Background()
	c, err := conn.Conn(ctx)
	if err != nil {
		return fmt.Errorf("acquire connection for vacuum: %w", err)
	}
	defer c.Close()

	if _, err := c.ExecContext(ctx, "PRAGMA auto_vacuum=INCREMENTAL"); err != nil {
		return fmt.Errorf("set auto_vacuum=INCREMENTAL: %w", err)
	}
	if _, err := c.ExecContext(ctx, "VACUUM"); err != nil {
		return fmt.Errorf("vacuum for auto_vacuum switch: %w", err)
	}
	if _, err := c.ExecContext(ctx, "PRAGMA wal_checkpoint(TRUNCATE)"); err != nil {
		// Non-fatal: the checkpoint merely shrinks the WAL immediately.
		slog.Debug("VACUUM 后 WAL checkpoint 失败", "error", err)
	}
	slog.Info("增量自动回收已启用，历史空闲页已归还磁盘")

	pageCount, freelist := pageStats(conn)
	slog.Info("数据库空间统计", "pageCount", pageCount, "freelist", freelist,
		"fileMB", fileSize/1024/1024)
	return nil
}

// vacuumTempEstimate estimates the peak *additional* disk space a VACUUM needs.
// VACUUM builds the compacted database in a temporary file while the original
// still occupies its full size, so the extra space is roughly the size of the
// compacted result: (page_count - freelist_count) * page_size. A 50% safety
// margin is added because VACUUM may also need journal/WAL headroom. Falls back
// to the current file size when the page statistics are unavailable.
func vacuumTempEstimate(conn *sql.DB, fileSize int64) (uint64, bool) {
	var pageSize, pageCount, freelist int64
	if err := conn.QueryRow("PRAGMA page_size").Scan(&pageSize); err != nil || pageSize <= 0 {
		if fileSize <= 0 {
			return 0, false
		}
		return uint64(fileSize), true
	}
	if err := conn.QueryRow("PRAGMA page_count").Scan(&pageCount); err != nil || pageCount <= 0 {
		if fileSize <= 0 {
			return 0, false
		}
		return uint64(fileSize), true
	}
	if err := conn.QueryRow("PRAGMA freelist_count").Scan(&freelist); err != nil || freelist < 0 {
		freelist = 0
	}
	live := pageCount - freelist
	if live < 0 {
		live = pageCount
	}
	need := uint64(live) * uint64(pageSize)
	return need + need/2 + 32*1024*1024, true
}

// IncrementalVacuum returns pages on the freelist to the operating system. On a
// database that is not in incremental auto-vacuum mode it is a harmless no-op.
// It is called after the periodic log cleanup so the space freed by that
// DELETE is released in the same cycle.
func IncrementalVacuum(conn *sql.DB) error {
	// PRAGMA incremental_vacuum is a row-returning statement: it emits one row
	// per page it releases, and the driver only advances one step per Exec. The
	// rows must therefore be drained (not merely Exec'd) or only a single page
	// would be reclaimed per call. Omitting the page count releases every free
	// page; on a non-incremental database the result set is empty.
	rows, err := conn.Query("PRAGMA incremental_vacuum")
	if err != nil {
		return fmt.Errorf("incremental_vacuum: %w", err)
	}
	defer rows.Close()
	for rows.Next() {
		// Draining the result set is what drives the reclamation.
	}
	return rows.Err()
}

// pageStats returns (page_count, freelist_count); either is -1 on error.
func pageStats(conn *sql.DB) (int64, int64) {
	var pageCount, freelist int64
	if err := conn.QueryRow("PRAGMA page_count").Scan(&pageCount); err != nil {
		pageCount = -1
	}
	if err := conn.QueryRow("PRAGMA freelist_count").Scan(&freelist); err != nil {
		freelist = -1
	}
	return pageCount, freelist
}
