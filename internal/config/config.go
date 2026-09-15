// Package config holds all runtime configuration for the gateway.
//
// Values mirror the original Spring Boot application.yml so that an existing
// deployment can be replaced without touching docker-compose.yml or .env files.
package config

import (
	"os"
	"strconv"
	"strings"
)

const (
	// DefaultPort matches server.port in the original application.yml.
	DefaultPort = 1399

	// DefaultDBPath is where SQLite lives inside the container.
	// The compose file bind-mounts ./data -> /app/data, so this survives rebuilds.
	DefaultDBPath = "data/gateway.db"

	// DefaultJWTSecret matches app.jwt.secret fallback. It is intentionally the
	// same string as the Java version so existing tokens keep verifying.
	DefaultJWTSecret = "my-ai-gateway-jwt-secret-key-2024-change-in-production"

	// DefaultJWTExpirationMs matches app.jwt.expiration (365 days).
	DefaultJWTExpirationMs = int64(31536000000)

	// ApplicationName is used as the Prometheus `application` tag.
	ApplicationName = "my-ai-gateway"
)

// Config is the fully resolved runtime configuration.
type Config struct {
	Port          int
	DBPath        string
	JWTSecret     string
	JWTExpiration int64 // milliseconds
	ActuatorToken string
	UploadDir     string
	StaticDir     string
}

// Load reads configuration from the environment, falling back to the same
// defaults the Java build used. Environment variable names are preserved
// (APP_JWT_SECRET, ACTUATOR_ACCESS_TOKEN) plus a few Go-specific additions.
func Load() *Config {
	c := &Config{
		Port:          DefaultPort,
		DBPath:        DefaultDBPath,
		JWTSecret:     DefaultJWTSecret,
		JWTExpiration: DefaultJWTExpirationMs,
		UploadDir:     "data/uploads",
	}

	// Port: MAG_PORT is preferred; SPRING-style SERVER_PORT also honoured so an
	// operator can keep the old env file working unchanged.
	if v := firstNonEmpty(os.Getenv("MAG_PORT"), os.Getenv("SERVER_PORT")); v != "" {
		if n, err := strconv.Atoi(v); err == nil && n > 0 {
			c.Port = n
		}
	}

	// Database path. MAG_DB_PATH wins; otherwise derive from SPRING_DATASOURCE_URL
	// (the compose file still sets that variable for the Java build).
	if v := os.Getenv("MAG_DB_PATH"); v != "" {
		c.DBPath = v
	} else if v := os.Getenv("SPRING_DATASOURCE_URL"); v != "" {
		if p := parseSQLiteURL(v); p != "" {
			c.DBPath = p
		}
	}

	if v := os.Getenv("APP_JWT_SECRET"); v != "" {
		c.JWTSecret = v
	}
	if v := os.Getenv("MAG_JWT_EXPIRATION_MS"); v != "" {
		if n, err := strconv.ParseInt(v, 10, 64); err == nil && n > 0 {
			c.JWTExpiration = n
		}
	}
	c.ActuatorToken = strings.TrimSpace(os.Getenv("ACTUATOR_ACCESS_TOKEN"))

	if v := os.Getenv("MAG_UPLOAD_DIR"); v != "" {
		c.UploadDir = v
	}
	c.StaticDir = os.Getenv("MAG_STATIC_DIR")

	return c
}

// parseSQLiteURL extracts the file path from a JDBC-style SQLite URL such as
// jdbc:sqlite:/app/data/gateway.db?transaction_mode=IMMEDIATE
func parseSQLiteURL(raw string) string {
	s := strings.TrimSpace(raw)
	s = strings.TrimPrefix(s, "jdbc:sqlite:")
	if i := strings.IndexByte(s, '?'); i >= 0 {
		s = s[:i]
	}
	return strings.TrimSpace(s)
}

func firstNonEmpty(vals ...string) string {
	for _, v := range vals {
		if strings.TrimSpace(v) != "" {
			return strings.TrimSpace(v)
		}
	}
	return ""
}
