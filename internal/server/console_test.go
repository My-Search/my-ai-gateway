package server

import (
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"testing"

	"github.com/gin-gonic/gin"

	"github.com/my-search/my-ai-gateway/internal/config"
)

func newConsoleTestRouter(cfg *config.Config) *gin.Engine {
	gin.SetMode(gin.TestMode)
	r := gin.New()
	New(Deps{Cfg: cfg}).registerConsoleRoutes(r)
	return r
}

func consoleRequest(t *testing.T, r *gin.Engine, method, path string) *httptest.ResponseRecorder {
	t.Helper()
	w := httptest.NewRecorder()
	r.ServeHTTP(w, httptest.NewRequest(method, path, nil))
	return w
}

func TestEmbeddedConsoleServedAtRootAndConsole(t *testing.T) {
	r := newConsoleTestRouter(&config.Config{})

	for _, path := range []string{"/", "/index.html", "/console", "/console.html"} {
		t.Run(path, func(t *testing.T) {
			w := consoleRequest(t, r, http.MethodGet, path)

			if w.Code != http.StatusOK {
				t.Fatalf("status = %d, want %d", w.Code, http.StatusOK)
			}
			if got := w.Header().Get("Content-Type"); !strings.HasPrefix(got, "text/html") {
				t.Errorf("Content-Type = %q, want text/html", got)
			}
			if !strings.Contains(w.Body.String(), "<title>My AI Gateway") {
				t.Error("response does not contain the console document")
			}
		})
	}
}

func TestEmbeddedConsoleNoCacheHeaders(t *testing.T) {
	r := newConsoleTestRouter(&config.Config{})
	w := consoleRequest(t, r, http.MethodGet, "/")

	if got := w.Header().Get("Cache-Control"); got != "no-cache, no-store, must-revalidate" {
		t.Errorf("Cache-Control = %q, want no-cache, no-store, must-revalidate", got)
	}
	if got := w.Header().Get("X-Content-Type-Options"); got != "nosniff" {
		t.Errorf("X-Content-Type-Options = %q, want nosniff", got)
	}
}

// A configured Vue bundle must keep owning "/", otherwise upgrading the
// binary would silently swap the admin UI under existing deployments.
func TestStaticDirTakesPrecedenceAtRoot(t *testing.T) {
	dir := t.TempDir()
	const vueIndex = "<html><title>Vue Admin</title></html>"
	if err := os.WriteFile(filepath.Join(dir, "index.html"), []byte(vueIndex), 0o644); err != nil {
		t.Fatal(err)
	}
	r := newConsoleTestRouter(&config.Config{StaticDir: dir})

	w := consoleRequest(t, r, http.MethodGet, "/")
	if w.Code != http.StatusOK {
		t.Fatalf("status = %d, want %d", w.Code, http.StatusOK)
	}
	if got := w.Body.String(); got != vueIndex {
		t.Errorf("body = %q, want the configured static index", got)
	}

	// The embedded console stays reachable on its dedicated path.
	w = consoleRequest(t, r, http.MethodGet, "/console")
	if !strings.Contains(w.Body.String(), "<title>My AI Gateway") {
		t.Error("/console should still serve the embedded console")
	}
}

// A stale MAG_STATIC_DIR without index.html must not break startup or "/".
func TestStaticDirWithoutIndexFallsBackToEmbeddedConsole(t *testing.T) {
	r := newConsoleTestRouter(&config.Config{StaticDir: t.TempDir()})

	w := consoleRequest(t, r, http.MethodGet, "/")
	if w.Code != http.StatusOK {
		t.Fatalf("status = %d, want %d", w.Code, http.StatusOK)
	}
	if !strings.Contains(w.Body.String(), "<title>My AI Gateway") {
		t.Error("expected embedded console fallback")
	}
}

func TestEmbeddedConsoleSupportsHead(t *testing.T) {
	r := newConsoleTestRouter(&config.Config{})
	w := consoleRequest(t, r, http.MethodHead, "/")

	if w.Code != http.StatusOK {
		t.Fatalf("status = %d, want %d", w.Code, http.StatusOK)
	}
	if got := w.Header().Get("Content-Type"); !strings.HasPrefix(got, "text/html") {
		t.Errorf("Content-Type = %q, want text/html", got)
	}
}
