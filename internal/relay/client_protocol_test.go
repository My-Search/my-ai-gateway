package relay

import "testing"

// TestClientProtocolMapping checks the inbound-protocol slug derivation for
// every supported entry endpoint, including the embeddings special case
// (which reuses the OpenAI wire format).
func TestClientProtocolMapping(t *testing.T) {
	cases := []struct {
		name string
		req  *InternalRequest
		want string
	}{
		{"openai chat", &InternalRequest{ClientAPIFormat: ProtoOpenAI}, ClientProtocolOpenAIChat},
		{"anthropic messages", &InternalRequest{ClientAPIFormat: ProtoAnthropic}, ClientProtocolAnthropicMessages},
		{"openai responses", &InternalRequest{ClientAPIFormat: ProtoResponses}, ClientProtocolOpenAIResponses},
		{"embeddings", &InternalRequest{ClientAPIFormat: ProtoOpenAI, EndpointPath: embeddingsEndpoint}, ClientProtocolEmbeddings},
		{"nil", nil, ClientProtocolOpenAIChat},
	}
	for _, tc := range cases {
		if got := ClientProtocol(tc.req); got != tc.want {
			t.Errorf("%s: ClientProtocol = %q, want %q", tc.name, got, tc.want)
		}
	}
}

// TestParseEmbeddingsRequestProtocol confirms the parsed embeddings request
// derives the embeddings protocol slug.
func TestParseEmbeddingsRequestProtocol(t *testing.T) {
	req, err := ParseEmbeddingsRequest(`{"model":"m","input":"hi"}`)
	if err != nil {
		t.Fatalf("parse: %v", err)
	}
	if got := ClientProtocol(req); got != ClientProtocolEmbeddings {
		t.Errorf("ClientProtocol = %q, want %q", got, ClientProtocolEmbeddings)
	}
}
