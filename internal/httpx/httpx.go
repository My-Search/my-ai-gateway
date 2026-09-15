// Package httpx contains HTTP middleware and shared response helpers.
package httpx

import (
	"encoding/json"
	"log/slog"
	"net/http"

	"github.com/gin-gonic/gin"
	"github.com/my-search/my-ai-gateway/internal/auth"
)

// Context keys for values the Java version stored as request attributes.
const (
	CtxAdminUser = "adminUser"
	CtxSessionID = "sessionID"
)

// SessionCookieName matches the servlet container's cookie so an existing
// browser session keeps working (best effort; the JWT is the durable path).
const SessionCookieName = "JSESSIONID"

// AdminAuth mirrors AdminAuthInterceptor.
//
// Order of checks is significant and preserved:
//  1. static resources and the SPA root are never intercepted
//  2. /admin/api/auth/* is public
//  3. for /admin/api/* -> ?token= param, then Authorization: Bearer, then session
//  4. everything else (SPA pages) passes through
func AdminAuth(tp *auth.TokenProvider, sessions *auth.SessionStore) gin.HandlerFunc {
	return func(c *gin.Context) {
		uri := c.Request.URL.Path

		if isStaticOrRoot(uri) || isAuthEndpoint(uri) {
			c.Next()
			return
		}

		if len(uri) >= len("/admin/api/") && uri[:len("/admin/api/")] == "/admin/api/" {
			// SSE clients cannot set headers, so the token may arrive as a query
			// parameter. This is checked first, exactly like the Java version.
			if tokenParam := c.Query("token"); tokenParam != "" {
				if user, ok := tp.Validate(tokenParam); ok {
					c.Set(CtxAdminUser, user)
					c.Next()
					return
				}
			}

			authz := c.GetHeader("Authorization")
			if len(authz) > 7 && authz[:7] == "Bearer " {
				if user, ok := tp.Validate(authz[7:]); ok {
					c.Set(CtxAdminUser, user)
					c.Next()
					return
				}
				slog.Debug("JWT Token 无效", "uri", uri)
			}

			if cookie, err := c.Cookie(SessionCookieName); err == nil {
				if user, ok := sessions.Lookup(cookie); ok {
					c.Set(CtxAdminUser, user)
					c.Set(CtxSessionID, cookie)
					c.Next()
					return
				}
			}

			slog.Debug("API 请求未登录", "uri", uri)
			c.Header("Content-Type", "application/json;charset=UTF-8")
			c.String(http.StatusUnauthorized,
				`{"success":false,"error":"未登录","authenticated":false}`)
			c.Abort()
			return
		}

		c.Next()
	}
}

func isStaticOrRoot(uri string) bool {
	return len(uri) >= 8 && uri[:8] == "/static/" ||
		len(uri) >= 8 && uri[:8] == "/assets/" ||
		uri == "/" || uri == "/index.html"
}

func isAuthEndpoint(uri string) bool {
	const p = "/admin/api/auth/"
	return len(uri) >= len(p) && uri[:len(p)] == p
}

// ActuatorToken guards /actuator/prometheus exactly like ActuatorSecurityFilter.
func ActuatorToken(token string) gin.HandlerFunc {
	return func(c *gin.Context) {
		if c.Request.URL.Path != "/actuator/prometheus" || token == "" {
			c.Next()
			return
		}
		provided := ""
		if authz := c.GetHeader("Authorization"); len(authz) > 7 && authz[:7] == "Bearer " {
			provided = trimSpace(authz[7:])
		}
		if provided == "" {
			provided = trimSpace(c.GetHeader("X-Actuator-Token"))
		}
		if !auth.ConstantTimeEqual(provided, token) {
			c.Header("Content-Type", "application/json")
			c.AbortWithStatusJSON(http.StatusForbidden, gin.H{
				"error":   "Forbidden",
				"message": "Invalid or missing access token for /actuator/prometheus",
			})
			return
		}
		c.Next()
	}
}

func trimSpace(s string) string {
	start, end := 0, len(s)
	for start < end && (s[start] == ' ' || s[start] == '\t') {
		start++
	}
	for end > start && (s[end-1] == ' ' || s[end-1] == '\t') {
		end--
	}
	return s[start:end]
}

// JSON writes a JSON response with the charset the Java build used.
func JSON(c *gin.Context, status int, body any) {
	c.Header("Content-Type", "application/json;charset=UTF-8")
	c.Status(status)
	enc := json.NewEncoder(c.Writer)
	enc.SetEscapeHTML(false)
	_ = enc.Encode(body)
}

// OK writes a 200 JSON response (the admin API always answers 200).
func OK(c *gin.Context, body any) { JSON(c, http.StatusOK, body) }

// OrderedMap preserves JSON key insertion order, which the front-end and the
// Java LinkedHashMap responses rely on.
type OrderedMap struct {
	keys   []string
	values map[string]any
}

func NewOrderedMap() *OrderedMap {
	return &OrderedMap{values: make(map[string]any)}
}

func (o *OrderedMap) Set(k string, v any) *OrderedMap {
	if _, ok := o.values[k]; !ok {
		o.keys = append(o.keys, k)
	}
	o.values[k] = v
	return o
}

func (o *OrderedMap) MarshalJSON() ([]byte, error) {
	buf := []byte{'{'}
	for i, k := range o.keys {
		if i > 0 {
			buf = append(buf, ',')
		}
		kb, err := json.Marshal(k)
		if err != nil {
			return nil, err
		}
		buf = append(buf, kb...)
		buf = append(buf, ':')
		vb, err := marshalNoEscape(o.values[k])
		if err != nil {
			return nil, err
		}
		buf = append(buf, vb...)
	}
	return append(buf, '}'), nil
}

func marshalNoEscape(v any) ([]byte, error) {
	b, err := json.Marshal(v)
	if err != nil {
		return nil, err
	}
	return b, nil
}
