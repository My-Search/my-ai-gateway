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

const testVueIndexHTML = "<html><title>Vue Admin</title></html>"

func newStaticTestRouter(t *testing.T) (*gin.Engine, string) {
	t.Helper()
	dir := t.TempDir()
	if err := os.MkdirAll(filepath.Join(dir, "assets"), 0o755); err != nil {
		t.Fatal(err)
	}
	files := map[string]string{
		"index.html":        testVueIndexHTML,
		"favicon.svg":       "<svg></svg>",
		"assets/app-123.js": "console.log('vue')",
	}
	for name, body := range files {
		if err := os.WriteFile(filepath.Join(dir, filepath.FromSlash(name)), []byte(body), 0o644); err != nil {
			t.Fatal(err)
		}
	}

	gin.SetMode(gin.TestMode)
	app := New(Deps{Cfg: &config.Config{StaticDir: dir}})
	r := gin.New()
	r.NoRoute(app.spaFallback)
	return r, dir
}

func staticRequest(r http.Handler, method, target string) *httptest.ResponseRecorder {
	w := httptest.NewRecorder()
	r.ServeHTTP(w, httptest.NewRequest(method, target, nil))
	return w
}

func TestSPAFallbackServesVuePageRoutes(t *testing.T) {
	r, _ := newStaticTestRouter(t)

	for _, target := range []string{"/admin/dashboard", "/login", "/share/example"} {
		t.Run(target, func(t *testing.T) {
			w := staticRequest(r, http.MethodGet, target)

			if w.Code != http.StatusOK {
				t.Fatalf("status = %d, want %d", w.Code, http.StatusOK)
			}
			if got := w.Body.String(); got != testVueIndexHTML {
				t.Errorf("body = %q, want the Vue index", got)
			}
			if got := w.Header().Get("Cache-Control"); got != "no-cache, no-store, must-revalidate" {
				t.Errorf("Cache-Control = %q", got)
			}
		})
	}
}

func TestSPAFallbackServesHashedAssets(t *testing.T) {
	r, _ := newStaticTestRouter(t)

	w := staticRequest(r, http.MethodGet, "/assets/app-123.js")
	if w.Code != http.StatusOK {
		t.Fatalf("status = %d, want %d", w.Code, http.StatusOK)
	}
	if got := w.Body.String(); got != "console.log('vue')" {
		t.Errorf("body = %q", got)
	}
	if got := w.Header().Get("Cache-Control"); got != "public, max-age=31536000, immutable" {
		t.Errorf("Cache-Control = %q", got)
	}
	if got := w.Header().Get("Content-Type"); !strings.Contains(got, "javascript") {
		t.Errorf("Content-Type = %q, want a JavaScript type", got)
	}
}

func TestSPAFallbackDoesNotServeHTMLForMissingAssets(t *testing.T) {
	r, _ := newStaticTestRouter(t)

	for _, target := range []string{"/assets/missing.js", "/favicon-old.svg"} {
		t.Run(target, func(t *testing.T) {
			w := staticRequest(r, http.MethodGet, target)
			if w.Code != http.StatusNotFound {
				t.Fatalf("status = %d, want %d", w.Code, http.StatusNotFound)
			}
		})
	}
}

func TestSPAFallbackLeavesBackendPathsAlone(t *testing.T) {
	r, _ := newStaticTestRouter(t)

	for _, target := range []string{
		"/admin/api/missing",
		"/api/share/missing",
		"/v1/missing",
		"/actuator/missing",
		"/uploads/missing",
	} {
		t.Run(target, func(t *testing.T) {
			w := staticRequest(r, http.MethodGet, target)
			if w.Code != http.StatusNotFound {
				t.Fatalf("status = %d, want %d", w.Code, http.StatusNotFound)
			}
			if strings.Contains(w.Body.String(), testVueIndexHTML) {
				t.Error("backend path must not fall back to the SPA index")
			}
		})
	}
}

func TestStaticFileRejectsPathTraversal(t *testing.T) {
	r, dir := newStaticTestRouter(t)
	parent := filepath.Dir(dir)
	t.Cleanup(func() { _ = os.Remove(filepath.Join(parent, "secret.txt")) })
	secret := filepath.Join(parent, "secret.txt")
	if err := os.WriteFile(secret, []byte("do-not-serve"), 0o644); err != nil {
		t.Fatal(err)
	}

	for _, target := range []string{
		"/../secret.txt",
		"/assets/../../secret.txt",
		"/assets/%2e%2e/%2e%2e/secret.txt",
		"/assets%5c..%5c..%5csecret.txt",
		"/assets/..%5c..%5csecret.txt",
	} {
		t.Run(target, func(t *testing.T) {
			w := staticRequest(r, http.MethodGet, target)
			if strings.Contains(w.Body.String(), "do-not-serve") {
				t.Fatal("path traversal escaped the static root")
			}
		})
	}
}

func TestSPAFallbackOnlyServesGetAndHead(t *testing.T) {
	r, _ := newStaticTestRouter(t)

	for _, method := range []string{http.MethodPost, http.MethodPut, http.MethodDelete} {
		t.Run(method, func(t *testing.T) {
			w := staticRequest(r, method, "/admin/dashboard")
			if w.Code != http.StatusNotFound {
				t.Fatalf("status = %d, want %d", w.Code, http.StatusNotFound)
			}
		})
	}
}

func TestSPAFallbackSupportsHead(t *testing.T) {
	r, _ := newStaticTestRouter(t)

	w := staticRequest(r, http.MethodHead, "/admin/dashboard")
	if w.Code != http.StatusOK {
		t.Fatalf("status = %d, want %d", w.Code, http.StatusOK)
	}
	if w.Body.Len() != 0 {
		t.Errorf("HEAD body length = %d, want 0", w.Body.Len())
	}
}
