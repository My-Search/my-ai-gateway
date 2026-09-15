package relay

import (
	"encoding/json"
	"strings"
)

// extractReasoningEffortFromBody pulls the client-supplied reasoning_effort
// from a raw JSON request body, mirroring RelayLogger.extractReasoningEffortFromBody.
func extractReasoningEffortFromBody(requestBody string) string {
	if strings.TrimSpace(requestBody) == "" {
		return ""
	}
	var m map[string]json.RawMessage
	if err := json.Unmarshal([]byte(requestBody), &m); err != nil {
		return ""
	}
	raw, ok := m["reasoning_effort"]
	if !ok {
		return ""
	}
	var s string
	if err := json.Unmarshal(raw, &s); err != nil {
		return ""
	}
	return s
}
