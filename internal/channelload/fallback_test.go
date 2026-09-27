package channelload

import (
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

func TestDownloadCatalogWithFallback(t *testing.T) {
	valid := `{"zhipuai/glm-5.3": {"limit":{"context":262144},"modalities":{"input":["text","image"],"output":["text"]}}}`
	good := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		_, _ = w.Write([]byte(valid))
	}))
	defer good.Close()
	bad := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		http.Error(w, "unavailable", http.StatusServiceUnavailable)
	}))
	defer bad.Close()
	bad2 := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		http.Error(w, "gone", http.StatusBadGateway)
	}))
	defer bad2.Close()
	dir := t.TempDir()

	t.Run("主地址成功时不用备用", func(t *testing.T) {
		dest := filepath.Join(dir, "primary-ok.json")
		used, n, err := downloadCatalogWithFallback(good.URL, bad.URL, dest)
		if err != nil {
			t.Fatalf("unexpected error: %v", err)
		}
		if used != good.URL {
			t.Errorf("used = %q, want primary %q", used, good.URL)
		}
		if n != 1 {
			t.Errorf("n = %d, want 1", n)
		}
		if _, err := os.Stat(dest); err != nil {
			t.Errorf("dest file missing: %v", err)
		}
	})

	t.Run("主地址失败时改用备用", func(t *testing.T) {
		dest := filepath.Join(dir, "fallback-ok.json")
		used, n, err := downloadCatalogWithFallback(bad.URL, good.URL, dest)
		if err != nil {
			t.Fatalf("unexpected error: %v", err)
		}
		if used != good.URL {
			t.Errorf("used = %q, want fallback %q", used, good.URL)
		}
		if n != 1 {
			t.Errorf("n = %d, want 1", n)
		}
		raw, err := os.ReadFile(dest)
		if err != nil {
			t.Fatalf("dest file missing: %v", err)
		}
		if !strings.Contains(string(raw), "glm-5.3") {
			t.Errorf("dest file does not contain fallback payload: %s", raw)
		}
	})

	t.Run("两个地址都失败", func(t *testing.T) {
		dest := filepath.Join(dir, "all-fail.json")
		used, n, err := downloadCatalogWithFallback(bad.URL, bad2.URL, dest)
		if err == nil {
			t.Fatal("expected error")
		}
		if used != "" || n != 0 {
			t.Errorf("used = %q, n = %d; want empty", used, n)
		}
		if !strings.Contains(err.Error(), "备用地址") {
			t.Errorf("error should mention fallback failure: %v", err)
		}
	})

	t.Run("主地址即备用地址时只试一次", func(t *testing.T) {
		dest := filepath.Join(dir, "same-url.json")
		used, _, err := downloadCatalogWithFallback(bad.URL, bad.URL, dest)
		if err == nil {
			t.Fatal("expected error")
		}
		if used != "" {
			t.Errorf("used = %q, want empty", used)
		}
		// 未包装“备用地址”字样说明没有重复请求同一个地址。
		if strings.Contains(err.Error(), "备用地址") {
			t.Errorf("should not retry when primary equals fallback: %v", err)
		}
	})

	t.Run("空主地址是配置错误不走备用", func(t *testing.T) {
		dest := filepath.Join(dir, "empty-primary.json")
		used, _, err := downloadCatalogWithFallback("", good.URL, dest)
		if err == nil {
			t.Fatal("expected error")
		}
		if !strings.Contains(err.Error(), "地址为空") {
			t.Errorf("expected config error, got: %v", err)
		}
		if used != "" {
			t.Errorf("used = %q, want empty", used)
		}
		if _, statErr := os.Stat(dest); statErr == nil {
			t.Error("dest file should not be written")
		}
	})
}
