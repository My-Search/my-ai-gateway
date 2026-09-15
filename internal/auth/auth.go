// Package auth reproduces the JWT and session behaviour of the Java build.
//
// Generation note: JJWT's signWith(SecretKey) selects the strongest HMAC
// algorithm the key supports (>=64 bytes HS512, >=48 HS384, >=32 HS256). The
// shipped default secret is 54 bytes, so existing installations hold HS384
// tokens. Verification therefore honours whatever algorithm the token header
// declares (as JJWT does), while generation mirrors JJWT's strength selection.
package auth

import (
	"crypto/hmac"
	"crypto/rand"
	"encoding/hex"
	"errors"
	"fmt"
	"strings"
	"sync"
	"time"

	"github.com/golang-jwt/jwt/v5"
)

// TokenProvider issues and validates admin JWTs.
type TokenProvider struct {
	secret     []byte
	expiration time.Duration
	newAlg     jwt.SigningMethod
}

// NewTokenProvider builds a provider. expirationMS matches app.jwt.expiration.
func NewTokenProvider(secret string, expirationMS int64) *TokenProvider {
	if expirationMS <= 0 {
		expirationMS = 8 * 60 * 60 * 1000
	}
	key := []byte(secret)
	return &TokenProvider{
		secret:     key,
		expiration: time.Duration(expirationMS) * time.Millisecond,
		newAlg:     strongestHMAC(len(key)),
	}
}

func strongestHMAC(keyLen int) jwt.SigningMethod {
	switch {
	case keyLen >= 64:
		return jwt.SigningMethodHS512
	case keyLen >= 48:
		return jwt.SigningMethodHS384
	default:
		return jwt.SigningMethodHS256
	}
}

// Generate creates a token for the username. Claims match the Java version:
// sub / iat / exp only, no iss or jti.
func (p *TokenProvider) Generate(username string) (string, error) {
	now := time.Now()
	claims := jwt.MapClaims{
		"sub": username,
		"iat": now.Unix(),
		"exp": now.Add(p.expiration).Unix(),
	}
	tok := jwt.NewWithClaims(p.newAlg, claims)
	return tok.SignedString(p.secret)
}

// Validate verifies the signature, algorithm and expiry, returning the subject.
func (p *TokenProvider) Validate(token string) (string, bool) {
	parsed, err := jwt.Parse(token, func(t *jwt.Token) (any, error) {
		m, ok := t.Method.(*jwt.SigningMethodHMAC)
		if !ok {
			return nil, fmt.Errorf("unexpected signing method: %v", t.Header["alg"])
		}
		// JJWT refuses keys shorter than the algorithm's minimum (= key size).
		if len(p.secret) < hmacMinKeyLen(m) {
			return nil, errors.New("key too short for algorithm")
		}
		return p.secret, nil
	}, jwt.WithExpirationRequired())
	if err != nil || !parsed.Valid {
		return "", false
	}
	claims, ok := parsed.Claims.(jwt.MapClaims)
	if !ok {
		return "", false
	}
	sub, _ := claims["sub"].(string)
	if sub == "" {
		return "", false
	}
	return sub, true
}

// hmacMinKeyLen returns the minimum key length (in bytes) for an HMAC method.
func hmacMinKeyLen(m *jwt.SigningMethodHMAC) int {
	switch m.Alg() {
	case "HS384":
		return 48
	case "HS512":
		return 64
	default:
		return 32
	}
}

// ---------------------------------------------------------------------------
// Sessions (HttpSession compatibility)
// ---------------------------------------------------------------------------

// SessionStore is an in-process session table backing the JSESSIONID cookie.
// The original relied on Tomcat's memory sessions with an 8 hour inactivity
// timeout and lost them on restart, which this reproduces.
type SessionStore struct {
	mu       sync.Mutex
	sessions map[string]*sessionEntry
	ttl      time.Duration
}

type sessionEntry struct {
	username string
	expires  time.Time
}

// NewSessionStore creates a store with the Java 8 hour inactivity timeout.
func NewSessionStore() *SessionStore {
	return &SessionStore{sessions: make(map[string]*sessionEntry), ttl: 8 * time.Hour}
}

// Create registers a new session and returns its id.
func (s *SessionStore) Create(username string) string {
	buf := make([]byte, 16)
	_, _ = rand.Read(buf)
	id := strings.ToUpper(hex.EncodeToString(buf))
	s.mu.Lock()
	defer s.mu.Unlock()
	s.sessions[id] = &sessionEntry{username: username, expires: time.Now().Add(s.ttl)}
	return id
}

// Lookup returns the username for a live session, refreshing the inactivity
// timer like Tomcat does on access.
func (s *SessionStore) Lookup(id string) (string, bool) {
	if id == "" {
		return "", false
	}
	s.mu.Lock()
	defer s.mu.Unlock()
	e, ok := s.sessions[id]
	if !ok {
		return "", false
	}
	if time.Now().After(e.expires) {
		delete(s.sessions, id)
		return "", false
	}
	e.expires = time.Now().Add(s.ttl)
	return e.username, true
}

// Delete invalidates a session (logout).
func (s *SessionStore) Delete(id string) {
	if id == "" {
		return
	}
	s.mu.Lock()
	defer s.mu.Unlock()
	delete(s.sessions, id)
}

// Cleanup removes expired sessions; call periodically.
func (s *SessionStore) Cleanup() {
	s.mu.Lock()
	defer s.mu.Unlock()
	now := time.Now()
	for id, e := range s.sessions {
		if now.After(e.expires) {
			delete(s.sessions, id)
		}
	}
}

// ConstantTimeEqual mirrors MessageDigest.isEqual for token comparisons.
func ConstantTimeEqual(a, b string) bool {
	return hmac.Equal([]byte(a), []byte(b))
}
