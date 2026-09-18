package relay

import (
	"context"
	"errors"
	"strings"
	"time"
)

// TimeoutError is a structured wrapper for timeout-related errors that
// carry semantic metadata, avoiding fragile string-pattern matching.
// This mirrors the approach of new-api's NewAPIError errorCode/errorType
// but scoped solely to timeout detection — the only dimension the current
// routing loop needs to distinguish.
type TimeoutError struct {
	// Upstream indicates the timeout originates from the upstream provider
	// (first-byte timeout, stream idle timeout) rather than a gateway-level
	// deadline or client cancellation.
	Upstream bool
	// Stage identifies what phase timed out: "first_byte", "stream_idle",
	// "upstream" (e.g. context deadline triggered by non-stream attemptCtx),
	// or "deadline" (global 600s candidate-loop ceiling).
	Stage    string
	Wrapped  error
}

func (e *TimeoutError) Error() string {
	if e.Wrapped != nil {
		return e.Wrapped.Error()
	}
	return "timeout"
}

func (e *TimeoutError) Unwrap() error { return e.Wrapped }

// isTimeoutError returns true when err is (or wraps) a TimeoutError, or is
// a context deadline exceeded / cancelled from a timeout-derived context.
// It is the single decision point so call sites never need strings.Contains.
func isTimeoutError(err error) bool {
	if err == nil {
		return false
	}
	var te *TimeoutError
	if errors.As(err, &te) {
		return true
	}
	// context.DeadlineExceeded — triggered by attemptCtx (NonStream),
	// the global 600s deadline, or the transport-layer ResponseHeaderTimeout.
	if errors.Is(err, context.DeadlineExceeded) {
		return true
	}
	// Go's http.Client / net/http may produce opaque timeout strings when
	// the underlying dial or TLS handshake times out.  We check the
	// standard net.Error timeout interface for good measure.
	var netErr interface{ Timeout() bool }
	if errors.As(err, &netErr) && netErr.Timeout() {
		return true
	}
// Fallback for known timeout message patterns from wrap errors that
		// were created with errors.New(...) and lack structured wrapping.
		msg := strings.ToLower(err.Error())
		return strings.Contains(msg, "timeout") ||
			strings.Contains(msg, "did not observe") ||
			strings.Contains(msg, "stream idle timeout") ||
			strings.Contains(msg, "empty response") ||
			strings.Contains(msg, "treated as timeout")
}

// newFirstByteTimeout returns a TimeoutError for the "no event before timeout"
// scenario (Java's firstTimeout via Mono.delay).
func newFirstByteTimeout(timeoutMs int64) *TimeoutError {
	return &TimeoutError{
		Upstream: true,
		Stage:    "first_byte",
		Wrapped:  errors.New("did not observe any item within timeout"),
	}
}

// newStreamIdleTimeout returns a TimeoutError for the per-chunk idle timeout
// that fires after the first event when data stops arriving.
func newStreamIdleTimeout(d time.Duration) *TimeoutError {
	return &TimeoutError{
		Upstream: true,
		Stage:    "stream_idle",
		Wrapped:  errors.New("stream idle timeout (" + itoa(int(d/time.Millisecond)) + "ms)"),
	}
}

// newEmptyResponseTimeout returns a TimeoutError for upstream returning an
// empty body with a 2xx status (treated as timeout for retry purposes).
func newEmptyResponseTimeout() *TimeoutError {
	return &TimeoutError{
		Upstream: true,
		Stage:    "empty_response",
		Wrapped:  errors.New("Provider returned empty response, treated as timeout"),
	}
}