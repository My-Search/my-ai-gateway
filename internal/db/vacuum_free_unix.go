//go:build unix

package db

import "syscall"

// freeBytes reports the bytes available to unprivileged users on the filesystem
// holding dir. ok is false when the query fails or the platform lacks Statfs.
func freeBytes(dir string) (uint64, bool) {
	if dir == "" {
		dir = "."
	}
	var st syscall.Statfs_t
	if err := syscall.Statfs(dir, &st); err != nil {
		return 0, false
	}
	return uint64(st.Bavail) * uint64(st.Bsize), true
}
