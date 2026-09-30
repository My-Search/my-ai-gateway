package relay

import (
	"encoding/json"
	"strings"
	"testing"
)

// TestParseEmbeddingsRequest covers the passthrough fields the routing loop
// relies on: model extraction, endpoint override and verbatim body retention.
func TestParseEmbeddingsRequest(t *testing.T) {
	body := `{"model":"text-embedding-3-small","input":"hello world","encoding_format":"float"}`
	req, err := ParseEmbeddingsRequest(body)
	if err != nil {
		t.Fatalf("ParseEmbeddingsRequest error: %v", err)
	}
	if req.Model != "text-embedding-3-small" {
		t.Errorf("Model = %q, want text-embedding-3-small", req.Model)
	}
	if req.ClientAPIFormat != ProtoOpenAI {
		t.Errorf("ClientAPIFormat = %q, want openai", req.ClientAPIFormat)
	}
	if req.EndpointPath != "/embeddings" {
		t.Errorf("EndpointPath = %q, want /embeddings", req.EndpointPath)
	}
	if string(req.Passthrough) != body {
		t.Errorf("Passthrough = %q, want original body", string(req.Passthrough))
	}
	if req.EstimatedTokens <= 0 {
		t.Errorf("EstimatedTokens = %d, want > 0", req.EstimatedTokens)
	}
}

func TestParseEmbeddingsRequestInvalid(t *testing.T) {
	cases := map[string]string{
		"not-json":   `{not-json`,
		"not-object": `"a string"`,
		"no-model":   `{"input":"hi"}`,
		"empty":      ``,
	}
	for name, body := range cases {
		if _, err := ParseEmbeddingsRequest(body); err == nil {
			t.Errorf("%s: expected error, got nil", name)
		}
	}
}

// TestEstimateEmbeddingsTokens checks each accepted `input` shape.
func TestEstimateEmbeddingsTokens(t *testing.T) {
	cases := []struct {
		name string
		body string
	}{
		{"string", `{"model":"m","input":"hello world"}`},
		{"string-array", `{"model":"m","input":["hello world","foo bar baz","lorem ipsum"]}`},
		{"token-ids", `{"model":"m","input":[1,2,3,4]}`},
		{"token-id-arrays", `{"model":"m","input":[[1,2],[3,4,5]]}`},
		{"no-input", `{"model":"m"}`},
	}
	for _, tc := range cases {
		req, err := ParseEmbeddingsRequest(tc.body)
		if err != nil {
			t.Fatalf("%s: unexpected error: %v", tc.name, err)
		}
		if tc.name == "no-input" {
			if req.EstimatedTokens != 0 {
				t.Errorf("%s: EstimatedTokens = %d, want 0", tc.name, req.EstimatedTokens)
			}
			continue
		}
		if req.EstimatedTokens <= 0 {
			t.Errorf("%s: EstimatedTokens = %d, want > 0", tc.name, req.EstimatedTokens)
		}
	}
}

// TestBuildProviderRequestPassthrough verifies the body is forwarded verbatim
// with only the model field rewritten to the candidate's model name.
func TestBuildProviderRequestPassthrough(t *testing.T) {
	req, err := ParseEmbeddingsRequest(`{"model":"entry-model","input":["a","b"],"encoding_format":"base64"}`)
	if err != nil {
		t.Fatalf("parse: %v", err)
	}
	// ReqForCandidate sets Model to the channel model name.
	upstream := BuildProviderRequest(ReqForCandidate(req, RoutingCandidate{ModelName: "text-embedding-3-large"}), ProtoOpenAI)

	var got map[string]any
	if err := json.Unmarshal([]byte(upstream), &got); err != nil {
		t.Fatalf("upstream not JSON: %v (%s)", err, upstream)
	}
	if got["model"] != "text-embedding-3-large" {
		t.Errorf("model = %v, want text-embedding-3-large", got["model"])
	}
	if _, ok := got["input"]; !ok {
		t.Errorf("input dropped from upstream body: %s", upstream)
	}
	if got["encoding_format"] != "base64" {
		t.Errorf("encoding_format = %v, want base64 (verbatim)", got["encoding_format"])
	}
	if _, ok := got["messages"]; ok {
		t.Errorf("embeddings body must not gain a messages field: %s", upstream)
	}
}

// TestEmbeddingsResponseModelRewrite confirms the openai→openai response path
// rewrites the model back to the entry model while leaving vectors untouched.
func TestEmbeddingsResponseModelRewrite(t *testing.T) {
	providerBody := `{"object":"list","data":[{"object":"embedding","index":0,"embedding":[0.1,0.2]}],"model":"text-embedding-3-large","usage":{"prompt_tokens":4,"total_tokens":4}}`
	out := TransformResponse(providerBody, ProtoOpenAI, ProtoOpenAI, "entry-model")

	var got map[string]any
	if err := json.Unmarshal([]byte(out), &got); err != nil {
		t.Fatalf("transformed not JSON: %v (%s)", err, out)
	}
	if got["model"] != "entry-model" {
		t.Errorf("model = %v, want entry-model", got["model"])
	}
	if !strings.Contains(out, `[0.1,0.2]`) {
		t.Errorf("embedding vector missing from response: %s", out)
	}
	pt, ct, tt := extractUsageFromResponse(providerBody)
	if pt != 4 || ct != 0 || tt != 4 {
		t.Errorf("usage = (%d,%d,%d), want (4,0,4)", pt, ct, tt)
	}
}

// TestBuildProviderURLEndpointPath checks the upstream path override.
func TestBuildProviderURLEndpointPath(t *testing.T) {
	cand := RoutingCandidate{BaseURL: "https://api.openai.com/v1", ChannelType: ProtoOpenAI}
	if got := buildProviderURL(cand, ProtoOpenAI, "/embeddings"); got != "https://api.openai.com/v1/embeddings" {
		t.Errorf("openai embeddings URL = %q", got)
	}
	// A non-openai channel still attempts the call at the same suffix.
	anth := RoutingCandidate{BaseURL: "https://api.anthropic.com/v1", ChannelType: ProtoAnthropic}
	if got := buildProviderURL(anth, ProtoAnthropic, "/embeddings"); got != "https://api.anthropic.com/v1/embeddings" {
		t.Errorf("anthropic embeddings URL = %q", got)
	}
	// Azure keeps returning the base URL (deployment path lives in base_url).
	az := RoutingCandidate{BaseURL: "https://x.openai.azure.com/openai/deployments/d", ChannelType: ProtoAzure}
	if got := buildProviderURL(az, ProtoAzure, "/embeddings"); got != az.BaseURL {
		t.Errorf("azure URL = %q, want base unchanged", got)
	}
	// Empty endpointPath keeps the protocol default.
	if got := buildProviderURL(cand, ProtoOpenAI, ""); got != "https://api.openai.com/v1/chat/completions" {
		t.Errorf("default URL = %q", got)
	}
}

// TestEstimateRequestTokensOverride ensures a pre-computed estimate bypasses
// the message heuristic (and therefore feeds the context-limit guard).
func TestEstimateRequestTokensOverride(t *testing.T) {
	req := &InternalRequest{EstimatedTokens: 12345}
	if got := EstimateRequestTokens(req); got != 12345 {
		t.Errorf("EstimateRequestTokens = %d, want 12345", got)
	}
	tooLarge, estimated := ExceedsContextLimit(req, 1000)
	if !tooLarge || estimated != 12345 {
		t.Errorf("ExceedsContextLimit = (%v,%d), want (true,12345)", tooLarge, estimated)
	}
	// A chat request (no override) keeps the old behaviour: empty messages → 0.
	if got := EstimateRequestTokens(&InternalRequest{}); got != 0 {
		t.Errorf("empty chat estimate = %d, want 0", got)
	}
}
