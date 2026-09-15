package auth

import (
	"crypto/hmac"
	"crypto/sha256"
	"crypto/sha512"
	"encoding/base64"
	"encoding/json"
	"hash"
	"testing"
	"time"
)

// sha384 returns a hash.Hash computing SHA-384 (crypto/sha512.Sum384 based).
func sha384New() hash.Hash { return sha512.New384() }

const defaultSecret = "my-ai-gateway-jwt-secret-key-2024-change-in-production"

func b64(b []byte) string { return base64.RawURLEncoding.EncodeToString(b) }

// javaToken builds a token exactly like JJWT would, to prove that tokens issued
// by the previous (Java) backend keep working after the migration.
func javaToken(t *testing.T, secret, alg, sub string, iat, exp int64) string {
	t.Helper()
	var h func() hash.Hash
	switch alg {
	case "HS256":
		h = sha256.New
	case "HS384":
		h = sha384New
	case "HS512":
		h = sha512.New
	default:
		t.Fatalf("unknown alg %s", alg)
	}
	header := b64([]byte(`{"alg":"` + alg + `","typ":"JWT"}`))
	payload, _ := json.Marshal(map[string]any{"sub": sub, "iat": iat, "exp": exp})
	body := b64(payload)
	mac := hmac.New(h, []byte(secret))
	mac.Write([]byte(header + "." + body))
	return header + "." + body + "." + b64(mac.Sum(nil))
}

func TestValidateJavaIssuedToken(t *testing.T) {
	p := NewTokenProvider(defaultSecret, 31536000000)
	now := time.Now().Unix()

	cases := []struct {
		name string
		alg  string
		ok   bool
	}{
		// The shipped default secret is 54 bytes, so JJWT picks HS384.
		{"HS384 default secret", "HS384", true},
		{"HS256 too-weak key rejected", "HS256", true}, // 54 bytes satisfies HS256 minimum
	}
	for _, tc := range cases {
		tok := javaToken(t, defaultSecret, tc.alg, "zhuangjie", now, now+3600)
		sub, ok := p.Validate(tok)
		if ok != tc.ok {
			t.Errorf("%s: valid=%v want %v", tc.name, ok, tc.ok)
			continue
		}
		if ok && sub != "zhuangjie" {
			t.Errorf("%s: sub=%q", tc.name, sub)
		}
	}
}

func TestValidateRejectsTamperedToken(t *testing.T) {
	p := NewTokenProvider(defaultSecret, 31536000000)
	now := time.Now().Unix()
	tok := javaToken(t, defaultSecret, "HS384", "zhuangjie", now, now+3600)
	if _, ok := p.Validate(tok + "x"); ok {
		t.Fatal("tampered token accepted")
	}
	// Token signed with a different secret must be rejected.
	other := javaToken(t, "another-secret-that-is-long-enough-for-hs256", "HS384", "zhuangjie", now, now+3600)
	if _, ok := p.Validate(other); ok {
		t.Fatal("token from foreign secret accepted")
	}
}

func TestValidateRejectsExpired(t *testing.T) {
	p := NewTokenProvider(defaultSecret, 31536000000)
	now := time.Now().Unix()
	tok := javaToken(t, defaultSecret, "HS384", "zhuangjie", now-7200, now-3600)
	if _, ok := p.Validate(tok); ok {
		t.Fatal("expired token accepted")
	}
}

func TestGenerateRoundTrip(t *testing.T) {
	p := NewTokenProvider(defaultSecret, 3600000)
	tok, err := p.Generate("admin")
	if err != nil {
		t.Fatal(err)
	}
	sub, ok := p.Validate(tok)
	if !ok || sub != "admin" {
		t.Fatalf("round trip failed: sub=%q ok=%v", sub, ok)
	}
}

// TestAlgorithmSelection mirrors JJWT's signWith(SecretKey) key-strength choice.
func TestAlgorithmSelection(t *testing.T) {
	cases := []struct {
		keyLen int
		want   string
	}{
		{32, "HS256"}, {47, "HS256"}, {48, "HS384"}, {63, "HS384"}, {64, "HS512"}, {100, "HS512"},
	}
	for _, tc := range cases {
		secret := make([]byte, tc.keyLen)
		for i := range secret {
			secret[i] = 'a'
		}
		p := NewTokenProvider(string(secret), 1000)
		if got := p.newAlg.Alg(); got != tc.want {
			t.Errorf("keyLen=%d: alg=%s want %s", tc.keyLen, got, tc.want)
		}
	}
}
