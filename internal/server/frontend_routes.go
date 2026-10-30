package server

import (
	"net/http"

	"github.com/gin-gonic/gin"
)

// 管理界面只有一种形态：frontend 的 Vue 构建产物（MAG_STATIC_DIR 指向其 dist）。
// 后端不再内嵌任何单文件控制台；未部署前端时给出明确的部署提示，
// 其他页面路由由 NoRoute 的 spaFallback 兜底交给前端 history 路由。

// frontendMissingHint 是未部署前端时的响应文案（纯文本，方便运维排查）。
const frontendMissingHint = "frontend not deployed: build the Vue app (cd frontend && npm run build) " +
	"and set MAG_STATIC_DIR to its dist directory, then restart."

// registerFrontendRoutes mounts the SPA entry points served from MAG_STATIC_DIR.
func (a *App) registerFrontendRoutes(r *gin.Engine) {
	for _, path := range []string{"/", "/index.html"} {
		r.GET(path, a.serveFrontendIndex)
		r.HEAD(path, a.serveFrontendIndex)
	}
}

// serveFrontendIndex serves the SPA entry point; without a configured bundle it
// answers 404 with the deployment hint instead of substituting any built-in UI.
func (a *App) serveFrontendIndex(c *gin.Context) {
	if a.serveStaticIndex(c) {
		return
	}
	c.Header("Cache-Control", "no-cache, no-store, must-revalidate")
	c.Header("X-Content-Type-Options", "nosniff")
	c.String(http.StatusNotFound, frontendMissingHint)
}