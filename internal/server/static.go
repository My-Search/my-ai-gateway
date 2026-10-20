package server

import (
	"net/http"
	"os"
	"path"
	"path/filepath"
	"runtime"
	"strings"

	"github.com/gin-gonic/gin"
)

// serveStaticIndex serves the SPA entry point when a built front-end bundle is
// configured. The HTML shell is never cached so deployments pick up new asset
// hashes immediately.
func (a *App) serveStaticIndex(c *gin.Context) bool {
	root := a.staticRoot()
	if root == "" {
		return false
	}
	index := filepath.Join(root, "index.html")
	st, err := os.Stat(index)
	if err != nil || !st.Mode().IsRegular() {
		return false
	}

	setHTMLNoCache(c)
	content, err := os.ReadFile(index)
	if err != nil {
		return false
	}
	c.Header("Content-Type", "text/html; charset=utf-8")
	c.Status(http.StatusOK)
	if c.Request.Method == http.MethodHead {
		return true
	}
	_, _ = c.Writer.Write(content)
	return true
}

// serveStaticFile serves one file below MAG_STATIC_DIR. Paths are resolved and
// checked against the root to prevent traversal outside the bundle.
func (a *App) serveStaticFile(c *gin.Context, uri string) bool {
	root := a.staticRoot()
	if root == "" {
		return false
	}

	rel := strings.TrimPrefix(uri, "/")
	if rel == "" {
		return false
	}
	// Go HTTP servers normalize backslashes on Windows, so reject encoded or
	// literal separators before path.Clean can collapse a traversal segment.
	if runtime.GOOS == "windows" && strings.Contains(rel, `\`) {
		return false
	}
	clean := path.Clean(rel)
	if clean == "." || clean == ".." || strings.HasPrefix(clean, "../") || strings.HasPrefix(clean, "/") {
		return false
	}
	// The entry point is rendered by serveStaticIndex so requests to
	// /index.html never get the redirect behavior of http.ServeFile.
	if clean == "index.html" {
		return false
	}

	absoluteRoot, err := filepath.Abs(root)
	if err != nil {
		return false
	}
	full := filepath.Join(absoluteRoot, filepath.FromSlash(clean))
	relative, err := filepath.Rel(absoluteRoot, full)
	if err != nil || relative == ".." || strings.HasPrefix(relative, ".."+string(filepath.Separator)) || filepath.IsAbs(relative) {
		return false
	}

	st, err := os.Stat(full)
	if err != nil || !st.Mode().IsRegular() {
		return false
	}

	switch {
	case strings.HasPrefix(uri, "/assets/") || uri == "/favicon.svg":
		c.Header("Cache-Control", "public, max-age=31536000, immutable")
	default:
		c.Header("Cache-Control", "no-cache")
	}
	file, err := os.Open(full)
	if err != nil {
		return false
	}
	defer file.Close()
	http.ServeContent(c.Writer, c.Request, filepath.Base(full), st.ModTime(), file)
	return true
}

func (a *App) staticRoot() string {
	if a.deps.Cfg == nil {
		return ""
	}
	return strings.TrimSpace(a.deps.Cfg.StaticDir)
}

func setHTMLNoCache(c *gin.Context) {
	c.Header("Cache-Control", "no-cache, no-store, must-revalidate")
	c.Header("Pragma", "no-cache")
	c.Header("Expires", "0")
	c.Header("X-Content-Type-Options", "nosniff")
}

func isBackendOnlyPath(uri string) bool {
	for _, prefix := range []string{"/admin/api", "/api/share", "/v1", "/actuator", "/uploads"} {
		if uri == prefix || strings.HasPrefix(uri, prefix+"/") {
			return true
		}
	}
	return false
}

// isStaticAssetLike catches requests for non-page files. Returning index.html
// for a missing script or stylesheet hides deployment errors and breaks caches.
func isStaticAssetLike(uri string) bool {
	lastSegment := uri
	if i := strings.LastIndexByte(uri, '/'); i >= 0 {
		lastSegment = uri[i+1:]
	}
	ext := strings.ToLower(path.Ext(lastSegment))
	switch ext {
	case ".js", ".mjs", ".css", ".map", ".json", ".svg", ".png", ".jpg", ".jpeg", ".gif", ".webp", ".ico", ".woff", ".woff2", ".ttf":
		return true
	default:
		return false
	}
}
