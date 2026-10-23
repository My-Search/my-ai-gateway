package httpx

import (
	"compress/gzip"
	"io"
	"net/http"
	"strings"
	"sync"

	"github.com/gin-gonic/gin"
)

// gzipMinSize is the payload size below which compression costs more than it
// saves (headers, gzip trailer, and a CPU burst for a few hundred bytes).
const gzipMinSize = 1024

// gzipWriterPool reuses compressors: allocating one per response was measurable
// on the admin log list, which is fetched repeatedly while scrolling.
var gzipWriterPool = sync.Pool{
	New: func() any {
		w, _ := gzip.NewWriterLevel(io.Discard, gzip.BestSpeed)
		return w
	},
}

// Gzip compresses responses when the client advertises gzip support.
//
// The admin API is almost entirely JSON, and the log list / dashboard payloads
// are large and highly repetitive (long model names, repeated keys, similar
// timestamps): gzip typically cuts them by 70-85%, which matters far more than
// the server-side CPU. Server-sent events must never be compressed — the
// compressor would hold each event in its buffer — so those paths bypass this
// middleware entirely.
func Gzip() gin.HandlerFunc {
	return func(c *gin.Context) {
		if c.Request.Method == http.MethodHead ||
			!strings.Contains(c.GetHeader("Accept-Encoding"), "gzip") {
			c.Next()
			return
		}

		// SSE endpoints write tiny fragments and call Flush after each one;
		// compressing them would delay delivery until the buffer fills.
		if strings.Contains(c.GetHeader("Accept"), "text/event-stream") ||
			strings.HasPrefix(c.Request.URL.Path, "/admin/api/logs/stream") ||
			strings.HasPrefix(c.Request.URL.Path, "/admin/api/sse-test") {
			c.Next()
			return
		}

		// Content-Length, if a handler set one, would describe the uncompressed
		// body. Drop it before anything is written.
		c.Writer.Header().Del("Content-Length")
		// Vary belongs on the response regardless of whether we end up
		// compressing, so shared caches keep the two variants apart.
		c.Writer.Header().Add("Vary", "Accept-Encoding")

		gz := gzipWriterPool.Get().(*gzip.Writer)
		gz.Reset(c.Writer)
		c.Writer = &gzipResponseWriter{ResponseWriter: c.Writer, gz: gz}

		defer func() {
			w := c.Writer.(*gzipResponseWriter)
			if w.compressing {
				_ = gz.Close()
			}
			gzipWriterPool.Put(gz)
		}()

		c.Next()
	}
}

// gzipResponseWriter defers the decision until the first body byte, so empty
// responses (204, 304, a handler that only set a status) stay valid and tiny
// bodies are not grown by gzip overhead.
type gzipResponseWriter struct {
	gin.ResponseWriter
	gz          *gzip.Writer
	decided     bool
	compressing bool
}

func (w *gzipResponseWriter) Write(p []byte) (int, error) {
	if !w.decided {
		w.decided = true
		if len(p) >= gzipMinSize {
			w.compressing = true
			// The header is set here rather than in the middleware so a response
			// that never gets a body is not labelled gzip.
			w.ResponseWriter.Header().Set("Content-Encoding", "gzip")
		}
	}
	if !w.compressing {
		return w.ResponseWriter.Write(p)
	}
	return w.gz.Write(p)
}

func (w *gzipResponseWriter) WriteString(s string) (int, error) {
	return w.Write([]byte(s))
}

// Flush pushes both the compressor and the connection so a streaming response
// still reaches the client promptly.
func (w *gzipResponseWriter) Flush() {
	if w.compressing {
		_ = w.gz.Flush()
	}
	w.ResponseWriter.Flush()
}

// Size delegates to the wrapped writer, whose counter already reflects the
// bytes that actually went on the wire (i.e. the compressed size).
func (w *gzipResponseWriter) Size() int {
	return w.ResponseWriter.Size()
}
