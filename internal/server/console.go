package server

import (
	_ "embed"
	"net/http"

	"github.com/gin-gonic/gin"
)

// consoleHTML is the single-file management console. Embedding it keeps the
// backend runnable on its own (`go run .` / mag-gateway.exe) without a separate
// front-end build, while the Vue bundle keeps working through MAG_STATIC_DIR.
//
//go:embed web/console.html
var consoleHTML []byte

// registerConsoleRoutes mounts the console entry points. The console talks to
// the admin API with relative URLs, so serving it from the backend keeps it
// same-origin and avoids CORS entirely.
func (a *App) registerConsoleRoutes(r *gin.Engine) {
	for _, path := range []string{"/", "/index.html", "/console", "/console.html"} {
		r.GET(path, a.serveConsole)
		r.HEAD(path, a.serveConsole)
	}
}

func (a *App) serveConsole(c *gin.Context) {
	// MAG_STATIC_DIR wins for the SPA entry points so an existing Vue deployment
	// is not replaced by the embedded console; it stays reachable at /console.
	if c.Request.URL.Path == "/" || c.Request.URL.Path == "/index.html" {
		if a.serveStaticIndex(c) {
			return
		}
	}

	c.Header("Cache-Control", "no-cache, no-store, must-revalidate")
	c.Header("X-Content-Type-Options", "nosniff")
	c.Data(http.StatusOK, "text/html; charset=utf-8", consoleHTML)
}
