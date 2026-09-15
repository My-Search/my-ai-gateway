package server

import (
	"crypto/rand"
	"encoding/base64"
)

func randRead(b []byte) { _, _ = rand.Read(b) }

func encodeBase64URL(data []byte) string { return base64.RawURLEncoding.EncodeToString(data) }

func int64Ptr(v int64) *int64 { return &v }

func derefStr(s *string, def string) string {
	if s == nil { return def }
	return *s
}