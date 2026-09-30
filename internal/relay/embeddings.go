// OpenAI Embeddings API (POST /v1/embeddings) inbound support.
//
// Embeddings is not a chat protocol: the request carries a top-level `input`
// (a string, an array of strings, or token arrays) instead of `messages`, and
// the response is a list of vectors rather than a chat completion. The gateway
// therefore treats it as a *passthrough* request: it reuses the shared routing
// loop (candidates, retries, circuit breaking, logging, usage stats) but sends
// the original body verbatim to the upstream `{base_url}/embeddings` endpoint,
// rewriting only the `model` field to the channel model name. The upstream
// response is returned as-is, with `model` rewritten back to the entry model.
package relay

import (
	"encoding/json"
	"errors"
	"strings"
)

// embeddingsEndpoint is the upstream path suffix for the Embeddings API.
const embeddingsEndpoint = "/embeddings"

// ParseEmbeddingsRequest converts a raw /v1/embeddings body into the internal
// representation. The body is kept verbatim in Passthrough so it can be
// forwarded unchanged (BuildProviderRequest only rewrites the model field).
func ParseEmbeddingsRequest(requestBody string) (*InternalRequest, error) {
	var raw map[string]json.RawMessage
	if err := json.Unmarshal([]byte(requestBody), &raw); err != nil {
		return nil, err
	}
	if raw == nil {
		return nil, errors.New("request body must be a JSON object")
	}
	model := strings.TrimSpace(rawString(raw, "model"))
	if model == "" {
		return nil, errors.New("missing required field: model")
	}
	return &InternalRequest{
		ClientAPIFormat: ProtoOpenAI,
		Model:           model,
		Passthrough:     json.RawMessage(requestBody),
		EndpointPath:    embeddingsEndpoint,
		EstimatedTokens: estimateEmbeddingsTokens(raw),
	}, nil
}

// estimateEmbeddingsTokens approximates the input size (in tokens) so the
// context-limit guard can skip candidates whose window is clearly too small.
// The `input` field may be a string, an array of strings, or token arrays.
func estimateEmbeddingsTokens(raw map[string]json.RawMessage) int64 {
	v, ok := raw["input"]
	if !ok {
		return 0
	}
	return estimateEmbeddingsInput(v)
}

func estimateEmbeddingsInput(v json.RawMessage) int64 {
	// Single string.
	var s string
	if json.Unmarshal(v, &s) == nil {
		return estimateTextTokens(s)
	}
	// Array: of strings, of token ids, or of token-id arrays.
	var arr []json.RawMessage
	if json.Unmarshal(v, &arr) == nil {
		var total int64
		for _, item := range arr {
			total += estimateEmbeddingsInput(item)
		}
		return total
	}
	// A flat token id counts as exactly one token.
	var n int64
	if json.Unmarshal(v, &n) == nil {
		return 1
	}
	return 0
}
