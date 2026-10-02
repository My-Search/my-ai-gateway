package circuit

import (
	"context"
	"encoding/json"
	"strings"
	"testing"
	"time"

	"github.com/my-search/my-ai-gateway/internal/relay"
)

// TestTriggerBreakStoresProtocol verifies the inbound protocol is persisted and
// later overwritten by the most recent failure (DELETE + INSERT semantics).
func TestTriggerBreakStoresProtocol(t *testing.T) {
	st := newTestStore(t)
	ctx := context.Background()
	key := int64(7)

	trigger := &Trigger{
		Store:     st,
		ConfigMgr: &ConfigManager{Store: st},
	}
	// Default config scope is "model".
	trigger.TriggerBreak(ctx, 42, 1, &key, 100, relay.ClientProtocolEmbeddings)

	row, err := st.QueryOne(ctx, "SELECT protocol, channel_model_id FROM circuit_breaker_states WHERE channel_model_id = 100")
	if err != nil || row == nil {
		t.Fatalf("expected a breaker row, err=%v", err)
	}
	if got := row.Str("protocol"); got != relay.ClientProtocolEmbeddings {
		t.Errorf("protocol = %q, want %q", got, relay.ClientProtocolEmbeddings)
	}

	// A later chat failure on the same breaker replaces the protocol.
	trigger.TriggerBreak(ctx, 42, 1, &key, 100, relay.ClientProtocolAnthropicMessages)
	row, _ = st.QueryOne(ctx, "SELECT protocol FROM circuit_breaker_states WHERE channel_model_id = 100")
	if got := row.Str("protocol"); got != relay.ClientProtocolAnthropicMessages {
		t.Errorf("protocol after overwrite = %q, want %q", got, relay.ClientProtocolAnthropicMessages)
	}
	if c, _ := st.QueryOne(ctx, "SELECT COUNT(*) cnt FROM circuit_breaker_states WHERE channel_model_id = 100"); c.Int("cnt", 0) != 1 {
		t.Errorf("expected exactly one row after overwrite, got %d", c.Int("cnt", 0))
	}
}

// TestProbeUsesProtocol verifies the probe endpoint/body follow the recorded
// protocol: embeddings hits /embeddings with an input body, chat keeps
// /chat/completions with a messages body.
func TestProbeUsesProtocol(t *testing.T) {
	var gotEndpoint, gotBody string
	probe := &ProbeService{
		HTTPDo: func(_ context.Context, _ ProbeTarget, endpoint string, _ map[string]string, body string) ProbeResult {
			gotEndpoint, gotBody = endpoint, body
			return ProbeResult{Alive: true, StatusCode: 200}
		},
	}

	probe.Probe(context.Background(), ProbeTarget{
		ChannelType: "openai", BaseURL: "https://api.openai.com/v1",
		ModelName: "text-embedding-3-small", Protocol: relay.ClientProtocolEmbeddings,
	})
	if gotEndpoint != "https://api.openai.com/v1/embeddings" {
		t.Errorf("embeddings endpoint = %q, want .../embeddings", gotEndpoint)
	}
	var embBody map[string]any
	if err := json.Unmarshal([]byte(gotBody), &embBody); err != nil {
		t.Fatalf("embeddings probe body not JSON: %v (%s)", err, gotBody)
	}
	if embBody["input"] != "ping" {
		t.Errorf("embeddings probe body input = %v, want ping", embBody["input"])
	}
	if _, ok := embBody["messages"]; ok {
		t.Errorf("embeddings probe body must not contain messages: %s", gotBody)
	}

	// Chat protocol keeps the legacy chat body / endpoint.
	probe.Probe(context.Background(), ProbeTarget{
		ChannelType: "openai", BaseURL: "https://api.openai.com/v1",
		ModelName: "gpt-4o", Protocol: relay.ClientProtocolOpenAIChat,
	})
	if gotEndpoint != "https://api.openai.com/v1/chat/completions" {
		t.Errorf("chat endpoint = %q, want .../chat/completions", gotEndpoint)
	}
	if !strings.Contains(gotBody, `"messages"`) {
		t.Errorf("chat probe body should contain messages: %s", gotBody)
	}
}

// TestProbeResponsesChannel verifies a "responses" channel is probed at
// {base}/responses with an input-shaped body.
func TestProbeResponsesChannel(t *testing.T) {
	var gotEndpoint, gotBody string
	probe := &ProbeService{
		HTTPDo: func(_ context.Context, _ ProbeTarget, endpoint string, _ map[string]string, body string) ProbeResult {
			gotEndpoint, gotBody = endpoint, body
			return ProbeResult{Alive: true, StatusCode: 200}
		},
	}
	probe.Probe(context.Background(), ProbeTarget{
		ChannelType: "responses", BaseURL: "https://api.openai.com/v1",
		ModelName: "gpt-5", Protocol: relay.ClientProtocolOpenAIChat,
	})
	if gotEndpoint != "https://api.openai.com/v1/responses" {
		t.Errorf("responses endpoint = %q, want .../responses", gotEndpoint)
	}
	var body map[string]any
	if err := json.Unmarshal([]byte(gotBody), &body); err != nil {
		t.Fatalf("responses probe body not JSON: %v (%s)", err, gotBody)
	}
	if body["input"] != "ping" {
		t.Errorf("responses probe body input = %v, want ping", body["input"])
	}
	if _, ok := body["messages"]; ok {
		t.Errorf("responses probe body must not contain messages: %s", gotBody)
	}
}

// TestEvaluateRelBrokenProtocolsByKey checks that each broken API key reports
// its own protocol, and that model-level records take precedence.
func TestEvaluateRelBrokenProtocolsByKey(t *testing.T) {
	now := time.Now().UTC()
	expire := now.Add(time.Minute)
	older := now.Add(-time.Minute)
	k7, k8 := int64(7), int64(8)

	states := []CircuitBreakerState{
		{ChannelID: 1, ChannelAPIKeyID: &k7, ChannelModelID: i64ptr(100), IsOpen: 1,
			ExpireAt: &expire, OpenedAt: &older, Protocol: relay.ClientProtocolEmbeddings},
		{ChannelID: 1, ChannelAPIKeyID: &k8, ChannelModelID: i64ptr(100), IsOpen: 1,
			ExpireAt: &expire, OpenedAt: &now, Protocol: relay.ClientProtocolOpenAIResponses},
	}
	mark := EvaluateRelBroken(100, 1, nil, states,
		[]KeyRef{{ID: 7, Enabled: true, Name: "key-a"}, {ID: 8, Enabled: true, Name: "key-b"}},
		map[int64]KeyRef{})
	if mark == nil {
		t.Fatal("expected a mark when all keys are broken")
	}
	got := map[int64]string{}
	names := map[int64]string{}
	for _, kp := range mark.ProtocolsByKey {
		got[kp.KeyID] = kp.Protocol
		names[kp.KeyID] = kp.KeyName
	}
	if got[7] != relay.ClientProtocolEmbeddings {
		t.Errorf("key 7 protocol = %q, want embeddings", got[7])
	}
	if got[8] != relay.ClientProtocolOpenAIResponses {
		t.Errorf("key 8 protocol = %q, want openai-responses", got[8])
	}
	if names[7] != "key-a" || names[8] != "key-b" {
		t.Errorf("key names not carried: %v", names)
	}

	// A legacy whole-channel row (no key) covers every key and, being model-level
	// here, provides the fallback protocol for keys without their own record.
	legacyStates := []CircuitBreakerState{
		{ChannelID: 1, ChannelAPIKeyID: &k7, ChannelModelID: i64ptr(100), IsOpen: 1,
			ExpireAt: &expire, OpenedAt: &older, Protocol: relay.ClientProtocolOpenAIChat},
		{ChannelID: 1, ChannelAPIKeyID: nil, ChannelModelID: nil, IsOpen: 1,
			ExpireAt: &expire, OpenedAt: &now, Protocol: relay.ClientProtocolAnthropicMessages},
	}
	mark = EvaluateRelBroken(100, 1, nil, legacyStates,
		[]KeyRef{{ID: 7, Enabled: true, Name: "key-a"}, {ID: 8, Enabled: true, Name: "key-b"}},
		map[int64]KeyRef{})
	if mark == nil {
		t.Fatal("expected a mark for the legacy whole-channel breaker")
	}
	got = map[int64]string{}
	for _, kp := range mark.ProtocolsByKey {
		got[kp.KeyID] = kp.Protocol
	}
	if got[7] != relay.ClientProtocolOpenAIChat {
		t.Errorf("key 7 = %q, want its own model-level openai-chat", got[7])
	}
	if got[8] != relay.ClientProtocolAnthropicMessages {
		t.Errorf("key 8 = %q, want channel-level anthropic-messages fallback", got[8])
	}
}

// TestProbeEmbeddingsAzureKeepsBase documents the azure exception: the
// deployment path lives in base_url, so no /embeddings suffix is appended.
func TestProbeEmbeddingsAzureKeepsBase(t *testing.T) {
	var gotEndpoint string
	probe := &ProbeService{
		HTTPDo: func(_ context.Context, _ ProbeTarget, endpoint string, _ map[string]string, _ string) ProbeResult {
			gotEndpoint = endpoint
			return ProbeResult{Alive: true, StatusCode: 200}
		},
	}
	base := "https://x.openai.azure.com/openai/deployments/d"
	probe.Probe(context.Background(), ProbeTarget{
		ChannelType: "azure", BaseURL: base, ModelName: "m", Protocol: relay.ClientProtocolEmbeddings,
	})
	if gotEndpoint != base {
		t.Errorf("azure embeddings endpoint = %q, want base unchanged %q", gotEndpoint, base)
	}
}
