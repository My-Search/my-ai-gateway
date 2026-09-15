// Package jtime reproduces the exact timestamp semantics of the Java/Spring
// original. Two distinct representations exist in the live database and both
// must be readable and writable byte-for-byte identically:
//
//   - Rows created by SQL migration defaults use "2006-01-02 15:04:05" (space).
//   - Rows written by the application use java.time.LocalDateTime.toString(),
//     which yields "2006-01-02T15:04:05" with a variable-length fractional part
//     (0, 3, 6 or 9 digits) and no timezone suffix.
//
// All instants are UTC because the Java build forced the JVM default timezone
// to UTC (TimeZoneConfig). Wall-clock display/statistics convert to
// Asia/Shanghai explicitly.
package jtime

import (
	"strings"
	"time"
)

// Shanghai is a fixed +8 offset, matching Asia/Shanghai (China has no DST).
var Shanghai = time.FixedZone("Asia/Shanghai", 8*3600)

// DefaultLayout is the format used by the JSON API (a literal 'Z' is appended
// by the serialiser; the stored value is already UTC).
const DefaultLayout = "2006-01-02T15:04:05"

// Now returns the current UTC time truncated to a comparable precision.
func Now() time.Time { return time.Now().UTC() }

// FormatApp renders a timestamp the way java.time.LocalDateTime.toString() does:
// seconds precision when nanoseconds are zero, otherwise 3/6/9 fractional digits.
func FormatApp(t time.Time) string {
	t = t.UTC()
	ns := t.Nanosecond()
	if ns == 0 {
		return t.Format("2006-01-02T15:04:05")
	}
	// LocalDateTime.toString drops trailing zeros but keeps at least 3 digits.
	switch {
	case ns%1_000_000 == 0:
		return t.Format("2006-01-02T15:04:05.000")
	case ns%1_000 == 0:
		return t.Format("2006-01-02T15:04:05.000000")
	default:
		return t.Format("2006-01-02T15:04:05.000000000")
	}
}

// FormatDefault renders a UTC timestamp in T-separated format so that
// string comparison with stored data (written by FormatApp, T-separated)
// produces correct ordering in SQLite. The space-separated DEFAULT
// CURRENT_TIMESTAMP shape would break range queries because ASCII 'T' (0x54)
// > ' ' (0x20), causing all T-format records on the same calendar day to
// be excluded from the upper-bound check.
func FormatDefault(t time.Time) string { return t.UTC().Format("2006-01-02T15:04:05") }

// Parse accepts every representation found in the database plus ISO-8601
// values that carry an explicit offset.
func Parse(s string) (time.Time, bool) {
	s = strings.TrimSpace(s)
	if s == "" {
		return time.Time{}, false
	}
	layouts := []string{
		"2006-01-02 15:04:05.999999999",
		"2006-01-02 15:04:05",
		"2006-01-02T15:04:05.999999999",
		"2006-01-02T15:04:05",
		"2006-01-02T15:04:05Z07:00",
		"2006-01-02T15:04:05.999999999Z07:00",
		"2006-01-02",
	}
	for _, l := range layouts {
		if t, err := time.ParseInLocation(l, s, time.UTC); err == nil {
			return t.UTC(), true
		}
	}
	return time.Time{}, false
}

// FormatAPI renders the exact string the Java Jackson serialiser produced for
// LocalDateTime with pattern yyyy-MM-dd'T'HH:mm:ss'Z'.
func FormatAPI(t time.Time) string { return t.UTC().Format(DefaultLayout) + "Z" }

// APITime is an optional timestamp that serialises exactly like the Java
// Jackson mapper did: "yyyy-MM-dd'T'HH:mm:ss'Z'" for a value, null otherwise.
type APITime struct{ T *time.Time }

// NewAPITime wraps a time value.
func NewAPITime(t time.Time) APITime { return APITime{T: &t} }

// Ptr wraps a time value in a pointer.
func Ptr(t time.Time) *time.Time { return &t }

func (a APITime) MarshalJSON() ([]byte, error) {
	if a.T == nil {
		return []byte("null"), nil
	}
	return []byte(`"` + FormatAPI(*a.T) + `"`), nil
}

func (a *APITime) UnmarshalJSON(b []byte) error {
	s := strings.TrimSpace(string(b))
	if s == "null" || s == `""` {
		a.T = nil
		return nil
	}
	s = strings.Trim(s, `"`)
	if t, ok := Parse(s); ok {
		a.T = &t
	}
	return nil
}

// IsZero reports whether no time is present.
func (a APITime) IsZero() bool { return a.T == nil }

// ShanghaiStart returns midnight (00:00) today in Shanghai timezone as UTC.
func ShanghaiStart(t time.Time) time.Time {
	y, m, d := t.In(Shanghai).Date()
	return time.Date(y, m, d, 0, 0, 0, 0, Shanghai).UTC()
}

// ShanghaiWeekStart returns Monday 00:00 of the current week in Shanghai time.
func ShanghaiWeekStart(t time.Time) time.Time {
	now := t.In(Shanghai)
	wd := now.Weekday()
	if wd == time.Sunday {
		wd = 7
	}
	mon := now.AddDate(0, 0, -int(wd)+1)
	return time.Date(mon.Year(), mon.Month(), mon.Day(), 0, 0, 0, 0, Shanghai).UTC()
}

// ShanghaiMonthStart returns the 1st day of the current month in Shanghai time.
func ShanghaiMonthStart(t time.Time) time.Time {
	now := t.In(Shanghai)
	return time.Date(now.Year(), now.Month(), 1, 0, 0, 0, 0, Shanghai).UTC()
}

// ScanTime converts a database column value into an optional *time.Time.
// NULL / empty values produce nil so JSON emits null.
func ScanTime(v any) *time.Time {
	switch x := v.(type) {
	case nil:
		return nil
	case time.Time:
		t := x.UTC()
		return &t
	case string:
		if t, ok := Parse(x); ok {
			return &t
		}
		return nil
	case []byte:
		if t, ok := Parse(string(x)); ok {
			return &t
		}
		return nil
	}
	return nil
}
