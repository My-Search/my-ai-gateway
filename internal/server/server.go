// Package server wires the HTTP surface. Route paths, response shapes and
// status codes reproduce the Java controllers exactly.
package server

import (
	"net/http"
	"os"
	"path/filepath"
	"strconv"
	"strings"

	"github.com/gin-gonic/gin"
	"github.com/prometheus/client_golang/prometheus/promhttp"

	"github.com/my-search/my-ai-gateway/internal/auth"
	"github.com/my-search/my-ai-gateway/internal/config"
	"github.com/my-search/my-ai-gateway/internal/httpx"
	"github.com/my-search/my-ai-gateway/internal/metrics"
	"github.com/my-search/my-ai-gateway/internal/relay"
	"github.com/my-search/my-ai-gateway/internal/relay/logsvc"
	"github.com/my-search/my-ai-gateway/internal/service"
	"github.com/my-search/my-ai-gateway/internal/store"
)

// Deps are the shared dependencies handed to every handler group.
type Deps struct {
	Cfg      *config.Config
	Store    *store.Store
	Config   *service.ConfigService
	Sessions *auth.SessionStore
	Tokens   *auth.TokenProvider
	Metrics  *metrics.Registry
	Relay    *relay.RelayCore
	LogSSE   *logsvc.LogSseService
}

// App holds registered handlers and background services.
type App struct {
	deps Deps
}

// New creates the application.
func New(d Deps) *App { return &App{deps: d} }

// Shutdown stops background services (async log writer, schedulers, workers).
func (a *App) Shutdown() {
	if a.deps.LogSSE != nil {
		a.deps.LogSSE.Shutdown()
	}
}

// Register mounts every route.
func (a *App) Register(r *gin.Engine) {
	d := a.deps

	r.Use(httpx.ActuatorToken(d.Cfg.ActuatorToken))

	// --- Actuator ---------------------------------------------------------
	r.GET("/actuator/health", func(c *gin.Context) {
		httpx.JSON(c, http.StatusOK, gin.H{"status": "UP"})
	})
	r.GET("/actuator/prometheus", gin.WrapH(promhttp.HandlerFor(d.Metrics.Reg, promhttp.HandlerOpts{})))

	// --- Static: uploaded files -------------------------------------------
	uploads := r.Group("/uploads")
	uploads.Use(noCacheProxied())
	uploads.GET("/*filepath", a.serveUpload)

	// --- API groups --------------------------------------------------------
	admin := r.Group("/admin/api")
	admin.Use(httpx.AdminAuth(d.Tokens, d.Sessions))
	registerAuthRoutes(admin, d)
	registerAPIKeyRoutes(admin, d)
	registerChannelRoutes(admin, d)
	registerModelRoutes(admin, d)
	registerLogRoutes(admin, d)
	registerConfigRoutes(admin, d)
	registerMultiModalRoutes(admin, d)
	registerChatRoutes(admin, d)

	v1 := r.Group("/v1")
	registerV1Routes(v1, d)

	share := r.Group("/api/share")
	registerShareRoutes(share, d)

	// --- SPA fallback ------------------------------------------------------
	r.NoRoute(a.spaFallback)
	r.NoMethod(func(c *gin.Context) {
		c.String(http.StatusMethodNotAllowed, "Method Not Allowed")
	})
}

func noCacheProxied() gin.HandlerFunc {
	return func(c *gin.Context) { c.Next() }
}

// serveUpload serves data/uploads/** like the Java resource handler.
func (a *App) serveUpload(c *gin.Context) {
	rel := strings.TrimPrefix(c.Param("filepath"), "/")
	if rel == "" || strings.Contains(rel, "..") {
		c.Status(http.StatusNotFound)
		return
	}
	full := filepath.Join(a.deps.Cfg.UploadDir, filepath.FromSlash(rel))
	if st, err := os.Stat(full); err != nil || st.IsDir() {
		c.Status(http.StatusNotFound)
		return
	}
	c.File(full)
}

// spaFallback reproduces the WebMvcConfig resource resolver: unknown paths that
// are not API calls return index.html when the front-end bundle is present.
func (a *App) spaFallback(c *gin.Context) {
	uri := c.Request.URL.Path

	if strings.HasPrefix(uri, "/admin/api/") {
		c.String(http.StatusNotFound, "Not Found")
		return
	}
	if strings.HasPrefix(uri, "/uploads/") {
		c.String(http.StatusNotFound, "Not Found")
		return
	}
	if a.deps.Cfg.StaticDir != "" {
		idx := filepath.Join(a.deps.Cfg.StaticDir, "index.html")
		if _, err := os.Stat(idx); err == nil {
			c.File(idx)
			return
		}
	}
	c.String(http.StatusNotFound, "Not Found")
}

// ---------------------------------------------------------------------------
// Small helpers shared by handlers
// ---------------------------------------------------------------------------

// successEnvelope is the `{success:true,...}` shape used by mutations.
func successEnvelope() *httpx.OrderedMap { return httpx.NewOrderedMap().Set("success", true) }

// failureEnvelope is the `{success:false,error:...}` shape.
func failureEnvelope(msg string) *httpx.OrderedMap {
	return httpx.NewOrderedMap().Set("success", false).Set("error", msg)
}

// pathID parses a numeric path parameter.
func pathID(c *gin.Context, name string) (int64, bool) {
	v := c.Param(name)
	n, err := strconv.ParseInt(v, 10, 64)
	if err != nil {
		return 0, false
	}
	return n, true
}

// notFound writes the 404 shape used by admin detail endpoints.
func notFound(c *gin.Context, msg string) {
	httpx.JSON(c, http.StatusNotFound, httpx.NewOrderedMap().Set("error", msg))
}
