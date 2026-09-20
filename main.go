// Command my-ai-gateway is a drop-in replacement for the Java/Spring Boot
// backend of My AI Gateway. It serves the same REST API on the same port with
// the same SQLite database, so upgrades and rollbacks are transparent.
package main

import (
	"context"
	"errors"
	"fmt"
	"log/slog"
	"net/http"
	"os"
	"os/signal"
	"syscall"
	"time"

	"github.com/gin-gonic/gin"
	"github.com/my-search/my-ai-gateway/internal/auth"
	"github.com/my-search/my-ai-gateway/internal/config"
	"github.com/my-search/my-ai-gateway/internal/db"
	"github.com/my-search/my-ai-gateway/internal/httpx"
	"github.com/my-search/my-ai-gateway/internal/metrics"
	"github.com/my-search/my-ai-gateway/internal/relay"
	"github.com/my-search/my-ai-gateway/internal/relay/logsvc"
	"github.com/my-search/my-ai-gateway/internal/server"
	"github.com/my-search/my-ai-gateway/internal/service"
	"github.com/my-search/my-ai-gateway/internal/store"
)

func main() {
	slog.SetDefault(slog.New(slog.NewTextHandler(os.Stdout, &slog.HandlerOptions{Level: slog.LevelInfo})))

	cfg := config.Load()
	validateSecret(cfg.JWTSecret)

	conn, err := db.Open(cfg.DBPath)
	if err != nil {
		slog.Error("打开数据库失败", "error", err)
		os.Exit(1)
	}
	defer conn.Close()

	readConn := db.OpenReadOnly(cfg.DBPath)
	if readConn != nil {
		defer readConn.Close()
	}

	if err := db.Migrate(conn); err != nil {
		slog.Error("数据库迁移失败", "error", err)
		os.Exit(1)
	}

	st := store.New(conn, readConn)
	cfgSvc := service.NewConfigService(st)

	metricsRegistry := metrics.NewRegistry()
	sessions := auth.NewSessionStore()
	tokens := auth.NewTokenProvider(cfg.JWTSecret, cfg.JWTExpiration)

	relayCore := relay.NewRelayCore(st)

	logSSE := logsvc.NewLogSseService()
	logWriter := logsvc.NewLogWriter(st, logSSE, cfgSvc)
	relayCore.LogWriter = logWriter

	dashCache := server.NewDashCache()

	app := server.New(server.Deps{
		DashCache: dashCache,
		Cfg:       cfg,
		Store:     st,
		Config:    cfgSvc,
		Sessions:  sessions,
		Tokens:    tokens,
		Metrics:   metricsRegistry,
		Relay:     relayCore,
		LogSSE:    logSSE,
	})

	// --- Circuit breaker + background tasks (Java services + schedule package) ---
	circuitBreaker := server.WireRelayRuntime(relayCore, st, cfgSvc, metricsRegistry)
	stopTasks := server.StartBackgroundTasks(relayCore, st, cfgSvc, circuitBreaker, dashCache)

	// Background maintenance: expire idle sessions.
	go func() {
		t := time.NewTicker(10 * time.Minute)
		defer t.Stop()
		for range t.C {
			sessions.Cleanup()
		}
	}()

	gin.SetMode(gin.ReleaseMode)
	r := gin.New()
	r.Use(gin.Recovery())
	r.RedirectTrailingSlash = false
	r.RedirectFixedPath = false
	r.HandleMethodNotAllowed = true
	r.MaxMultipartMemory = 10 << 20

	app.Register(r)

	srv := &http.Server{
		Addr:              fmt.Sprintf(":%d", cfg.Port),
		Handler:           r,
		ReadHeaderTimeout: 30 * time.Second,
		// Streaming responses manage their own timeouts, mirroring
		// spring.mvc.async.request-timeout=-1.
		IdleTimeout: 0,
	}

	go func() {
		slog.Info("My AI Gateway 启动", "port", cfg.Port, "db", cfg.DBPath)
		if err := srv.ListenAndServe(); err != nil && !errors.Is(err, http.ErrServerClosed) {
			slog.Error("HTTP 服务异常", "error", err)
			os.Exit(1)
		}
	}()

	quit := make(chan os.Signal, 1)
	signal.Notify(quit, syscall.SIGINT, syscall.SIGTERM)
	<-quit
	slog.Info("正在关闭服务...")

	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()
	if err := srv.Shutdown(ctx); err != nil {
		slog.Warn("优雅关闭超时", "error", err)
	}
	stopTasks()
	app.Shutdown()
	slog.Info("服务已停止")
}

func validateSecret(secret string) {
	const defaultSecret = "my-ai-gateway-jwt-secret-key-2024-change-in-production"
	if secret == "" {
		slog.Warn("⚠️ app.jwt.secret 为空！JWT 签名将失败，请通过环境变量 APP_JWT_SECRET 配置密钥")
		return
	}
	if secret == defaultSecret {
		slog.Warn("⚠️ 检测到 JWT 密钥仍为内置默认值！生产环境存在被伪造管理后台 Token 的风险，" +
			"请通过环境变量 APP_JWT_SECRET 设置一个高熵随机密钥（建议 ≥ 64 字节）")
	}
	if len([]byte(secret)) < 32 {
		slog.Warn("⚠️ app.jwt.secret 长度不足 32 字节，无法满足 HS256 安全强度要求，请设置更长的密钥")
	}
}

var _ = httpx.OK
