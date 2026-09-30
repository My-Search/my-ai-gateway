package server

import (
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"

	"github.com/gin-gonic/gin"
)

// TestV1EmbeddingsRequiresAuth verifies /v1/embeddings is mounted and rejects a
// request without an Authorization header using the OpenAI error envelope.
func TestV1EmbeddingsRequiresAuth(t *testing.T) {
	gin.SetMode(gin.TestMode)
	r := gin.New()
	registerV1Routes(r.Group("/v1"), Deps{})

	req := httptest.NewRequest(http.MethodPost, "/v1/embeddings", strings.NewReader(`{"model":"m","input":"hi"}`))
	req.Header.Set("Content-Type", "application/json")
	w := httptest.NewRecorder()
	r.ServeHTTP(w, req)

	if w.Code != http.StatusUnauthorized {
		t.Fatalf("status = %d, want 401 (body: %s)", w.Code, w.Body.String())
	}
	var env struct {
		Error struct {
			Type string `json:"type"`
		} `json:"error"`
	}
	if err := json.Unmarshal(w.Body.Bytes(), &env); err != nil {
		t.Fatalf("invalid JSON error envelope: %v (%s)", err, w.Body.String())
	}
	if env.Error.Type != "authentication_error" {
		t.Errorf("error.type = %q, want authentication_error", env.Error.Type)
	}
}

// TestV1EmbeddingsInvalidBody checks the 400 invalid_request_error path.
func TestV1EmbeddingsInvalidBody(t *testing.T) {
	gin.SetMode(gin.TestMode)
	r := gin.New()
	registerV1Routes(r.Group("/v1"), Deps{})

	req := httptest.NewRequest(http.MethodPost, "/v1/embeddings", strings.NewReader(`{not-json`))
	req.Header.Set("Authorization", "Bearer sk-myai-test")
	w := httptest.NewRecorder()
	r.ServeHTTP(w, req)

	if w.Code != http.StatusBadRequest {
		t.Fatalf("status = %d, want 400 (body: %s)", w.Code, w.Body.String())
	}
}
