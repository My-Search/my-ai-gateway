package server

import (
	"context"
	"encoding/base64"
	"strings"

	"github.com/gin-gonic/gin"
	"github.com/my-search/my-ai-gateway/internal/httpx"
)

// registerAuthRoutes wires /admin/api/auth/* plus the dashboard endpoints that
// live on AdminAuthController.
func registerAuthRoutes(g *gin.RouterGroup, d Deps) {
	// GET /admin/api/auth/check
	g.GET("/auth/check", func(c *gin.Context) {
		ctx := c.Request.Context()
		authenticated := false

		// Method 1: JWT bearer header (this endpoint is excluded from the auth
		// interceptor, so it validates itself).
		if authz := c.GetHeader("Authorization"); strings.HasPrefix(authz, "Bearer ") {
			if _, ok := d.Tokens.Validate(strings.TrimPrefix(authz, "Bearer ")); ok {
				authenticated = true
			}
		}
		// Method 2: session cookie (backwards compatibility).
		if !authenticated {
			if cookie, err := c.Cookie(httpx.SessionCookieName); err == nil {
				if _, ok := d.Sessions.Lookup(cookie); ok {
					authenticated = true
				}
			}
		}

		httpx.OK(c, httpx.NewOrderedMap().
			Set("authenticated", authenticated).
			Set("hasAdminAccount", d.Config.HasAdminAccount(ctx)))
	})

	// POST /admin/api/auth/login
	g.POST("/auth/login", func(c *gin.Context) {
		ctx := c.Request.Context()
		var body struct {
			Username *string `json:"username"`
			Password *string `json:"password"`
		}
		_ = c.ShouldBindJSON(&body)

		username, password := "", ""
		if body.Username != nil {
			username = *body.Username
		}
		if body.Password != nil {
			password = *body.Password
		}

		if !d.Config.HasAdminAccount(ctx) {
			httpx.OK(c, failureEnvelope("请先设置管理员账号"))
			return
		}

		if !verifyCredentials(ctx, d, username, password) {
			httpx.OK(c, failureEnvelope("用户名或密码错误"))
			return
		}

		token, err := d.Tokens.Generate(username)
		if err != nil {
			httpx.OK(c, failureEnvelope(err.Error()))
			return
		}
		setSessionCookie(c, d.Sessions.Create(username))

		httpx.OK(c, httpx.NewOrderedMap().
			Set("success", true).
			Set("username", username).
			Set("token", token))
	})

	// POST /admin/api/auth/setup
	g.POST("/auth/setup", func(c *gin.Context) {
		ctx := c.Request.Context()
		var body struct {
			Username        *string `json:"username"`
			Password        *string `json:"password"`
			ConfirmPassword *string `json:"confirmPassword"`
		}
		if err := c.ShouldBindJSON(&body); err != nil {
			httpx.OK(c, failureEnvelope("请求参数错误"))
			return
		}
		username := ""
		if body.Username != nil {
			username = *body.Username
		}
		password, confirm := "", ""
		if body.Password != nil {
			password = *body.Password
		}
		if body.ConfirmPassword != nil {
			confirm = *body.ConfirmPassword
		}

		if password != confirm {
			httpx.OK(c, failureEnvelope("两次输入的密码不一致"))
			return
		}
		if len([]rune(password)) < 6 {
			httpx.OK(c, failureEnvelope("密码长度至少6位"))
			return
		}
		if d.Config.HasAdminAccount(ctx) {
			httpx.OK(c, failureEnvelope("管理员账号已存在"))
			return
		}

		// Password storage matches the Java version: Base64 of the raw UTF-8
		// bytes (not a hash).
		enc := base64.StdEncoding.EncodeToString([]byte(password))
		if err := d.Config.SetAdminAccount(ctx, username, enc); err != nil {
			httpx.OK(c, failureEnvelope(err.Error()))
			return
		}

		token, err := d.Tokens.Generate(username)
		if err != nil {
			httpx.OK(c, failureEnvelope(err.Error()))
			return
		}
		setSessionCookie(c, d.Sessions.Create(username))

		// Note: setup does not return `username`, matching the Java response.
		httpx.OK(c, httpx.NewOrderedMap().Set("success", true).Set("token", token))
	})

	// POST /admin/api/auth/logout
	g.POST("/auth/logout", func(c *gin.Context) {
		if cookie, err := c.Cookie(httpx.SessionCookieName); err == nil {
			d.Sessions.Delete(cookie)
		}
		c.SetCookie(httpx.SessionCookieName, "", -1, "/", "", false, true)
		httpx.OK(c, httpx.NewOrderedMap().Set("success", true))
	})

	registerDashboardRoutes(g, d)
}

func setSessionCookie(c *gin.Context, sid string) {
	// 8 hour session, matching session.setMaxInactiveInterval(8*60*60).
	c.SetCookie(httpx.SessionCookieName, sid, 8*60*60, "/", "", false, true)
}

// verifyCredentials compares the submitted password against the Base64-stored
// value, exactly like AdminConfigService.verify + verifyPassword.
func verifyCredentials(ctx context.Context, d Deps, username, password string) bool {
	storedUser := d.Config.GetUsername(ctx)
	storedPass := d.Config.GetPassword(ctx)
	if storedUser == "" || storedPass == "" {
		return false
	}
	if storedUser != username {
		return false
	}
	decoded, err := base64.StdEncoding.DecodeString(storedPass)
	if err != nil {
		return false
	}
	return string(decoded) == password
}
