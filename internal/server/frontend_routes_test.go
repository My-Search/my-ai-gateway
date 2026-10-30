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

// 前端服务测试：管理界面只有 Vue 构建产物一种形态（MAG_STATIC_DIR），
// 未部署时给出部署提示；SPA 页面路由由 spaFallback 兜底到 index.html。
// 旧的 /console 单文件控制台已移除，相关路径要么走 SPA 兜底、要么 404。

const vueIndexFixture = "<html><title>Vue Admin</title></html>"

func newFrontendTestRouter(cfg *config.Config) *gin.Engine {
	gin.SetMode(gin.TestMode)
	r := gin.New()
	app := New(Deps{Cfg: cfg})
	app.registerFrontendRoutes(r)
	r.NoRoute(app.spaFallback)
	return r
}

func serveVueDir(t *testing.T) string {
	t.Helper()
	dir := t.TempDir()
	if err := os.WriteFile(filepath.Join(dir, "index.html"), []byte(vueIndexFixture), 0o644); err != nil {
		t.Fatal(err)
	}
	return dir
}

func frontendRequest(t *testing.T, r *gin.Engine, method, path string) *httptest.ResponseRecorder {
	t.Helper()
	w := httptest.NewRecorder()
	r.ServeHTTP(w, httptest.NewRequest(method, path, nil))
	return w
}

func TestFrontendIndexServedAtRootAndIndexHTML(t *testing.T) {
	r := newFrontendTestRouter(&config.Config{StaticDir: serveVueDir(t)})

	for _, path := range []string{"/", "/index.html"} {
		t.Run(path, func(t *testing.T) {
			w := frontendRequest(t, r, http.MethodGet, path)

			if w.Code != http.StatusOK {
				t.Fatalf("status = %d, want %d", w.Code, http.StatusOK)
			}
			if got := w.Header().Get("Content-Type"); !strings.HasPrefix(got, "text/html") {
				t.Errorf("Content-Type = %q, want text/html", got)
			}
			if w.Body.String() != vueIndexFixture {
				t.Errorf("body = %q, want the Vue index", w.Body.String())
			}
		})
	}
}

func TestFrontendIndexNoCacheHeaders(t *testing.T) {
	r := newFrontendTestRouter(&config.Config{StaticDir: serveVueDir(t)})
	w := frontendRequest(t, r, http.MethodGet, "/")

	if got := w.Header().Get("Cache-Control"); got != "no-cache, no-store, must-revalidate" {
		t.Errorf("Cache-Control = %q, want no-cache, no-store, must-revalidate", got)
	}
	if got := w.Header().Get("X-Content-Type-Options"); got != "nosniff" {
		t.Errorf("X-Content-Type-Options = %q, want nosniff", got)
	}
}

func TestFrontendIndexSupportsHead(t *testing.T) {
	r := newFrontendTestRouter(&config.Config{StaticDir: serveVueDir(t)})
	w := frontendRequest(t, r, http.MethodHead, "/")

	if w.Code != http.StatusOK {
		t.Fatalf("status = %d, want %d", w.Code, http.StatusOK)
	}
	if got := w.Header().Get("Content-Type"); !strings.HasPrefix(got, "text/html") {
		t.Errorf("Content-Type = %q, want text/html", got)
	}
	if w.Body.Len() != 0 {
		t.Errorf("HEAD body should be empty, got %d bytes", w.Body.Len())
	}
}

// 未配置静态目录（裸二进制运行）时必须给出明确的部署提示，而不是任何内嵌界面。
func TestRootWithoutStaticDirHintsDeployment(t *testing.T) {
	r := newFrontendTestRouter(&config.Config{})
	w := frontendRequest(t, r, http.MethodGet, "/")

	if w.Code != http.StatusNotFound {
		t.Fatalf("status = %d, want %d", w.Code, http.StatusNotFound)
	}
	if !strings.Contains(w.Body.String(), "MAG_STATIC_DIR") {
		t.Errorf("body %q should point operators at MAG_STATIC_DIR", w.Body.String())
	}
	if strings.Contains(w.Body.String(), "<title>My AI Gateway") {
		t.Error("embedded console must not exist anymore")
	}
}

// 静态目录存在但没有 index.html（构建产物不完整）同样提示部署问题。
func TestStaticDirWithoutIndexHintsDeployment(t *testing.T) {
	r := newFrontendTestRouter(&config.Config{StaticDir: t.TempDir()})
	w := frontendRequest(t, r, http.MethodGet, "/")

	if w.Code != http.StatusNotFound {
		t.Fatalf("status = %d, want %d", w.Code, http.StatusNotFound)
	}
	if !strings.Contains(w.Body.String(), "MAG_STATIC_DIR") {
		t.Errorf("body %q should point operators at MAG_STATIC_DIR", w.Body.String())
	}
}

// 旧控制台路径 /console、/console.html 不再有专门入口：
// 配置了 Vue 包时按 SPA 页面路由兜底到 index.html；未配置时 404。
func TestLegacyConsolePathsFallBackToSpaOr404(t *testing.T) {
	r := newFrontendTestRouter(&config.Config{StaticDir: serveVueDir(t)})
	for _, path := range []string{"/console", "/console.html"} {
		w := frontendRequest(t, r, http.MethodGet, path)
		if w.Code != http.StatusOK {
			t.Fatalf("%s: status = %d, want %d (SPA fallback)", path, w.Code, http.StatusOK)
		}
		if w.Body.String() != vueIndexFixture {
			t.Errorf("%s: body = %q, want the Vue index (SPA fallback, not a built-in console)", path, w.Body.String())
		}
	}

	bare := newFrontendTestRouter(&config.Config{})
	w := frontendRequest(t, bare, http.MethodGet, "/console")
	if w.Code != http.StatusNotFound {
		t.Fatalf("bare /console status = %d, want %d", w.Code, http.StatusNotFound)
	}
}