//go:build !unix

package db

// freeBytes reports the bytes available to unprivileged users on the filesystem
// holding dir. ok is false when the query fails or the platform lacks Statfs.
//
// Non-unix platforms (Windows in particular) have no syscall.Statfs, and the
// free-space figure is only an advisory guard on when to run an incremental
// vacuum — returning false simply skips that guard.
func freeBytes(string) (uint64, bool) {
	return 0, false
}
