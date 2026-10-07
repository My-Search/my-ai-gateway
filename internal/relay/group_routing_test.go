package relay

import (
	"context"
	"encoding/json"
	"io"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
)

// newGroupTestStore builds the schema the group routing path touches, on top of
// the minimal relay schema. The group tables mirror the v1.43.0 migration.
func newGroupTestStore(t *testing.T) DataStore {
	t.Helper()
	st := newRelayTestStore(t)
	for _, stmt := range []string{
		`CREATE TABLE model_groups (
			id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT UNIQUE NOT NULL,
			description TEXT DEFAULT '', strategy TEXT DEFAULT 'random',
			sticky INTEGER DEFAULT 0, enabled INTEGER DEFAULT 1,
			created_at TEXT, updated_at TEXT)`,
		`CREATE TABLE model_group_members (
			id INTEGER PRIMARY KEY AUTOINCREMENT, group_id INTEGER NOT NULL,
			channel_model_id INTEGER NOT NULL, weight INTEGER DEFAULT 1,
			reasoning_effort TEXT, sort_order INTEGER DEFAULT 0,
			enabled INTEGER DEFAULT 1, created_at TEXT)`,
		`CREATE TABLE model_group_rels (
			id INTEGER PRIMARY KEY AUTOINCREMENT, model_id INTEGER NOT NULL,
			group_id INTEGER NOT NULL, sort_order INTEGER DEFAULT 0,
			enabled INTEGER DEFAULT 1, created_at TEXT)`,
	} {
		if _, err := st.Exec(context.Background(), stmt); err != nil {
			t.Fatalf("group schema: %v", err)
		}
	}
	return st
}

// groupFixture wires an entry model whose candidates all point at one upstream
// that echoes the upstream model name it received, so a test can prove which
// member served the request.
type groupFixture struct {
	core     *RelayCore
	store    DataStore
	upstream *httptest.Server
	modelIDs map[string]int64 // upstream model name -> channel_models.id
	groupID  int64
	entryID  int64
}

func setupGroupFixture(t *testing.T, strategy string, sticky bool, weights map[string]int) *groupFixture {
	t.Helper()
	st := newGroupTestStore(t)
	core := NewRelayCore(nil)
	core.Store = st
	core.RouteResolver = NewRouteResolver(st)

	upstream := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		raw, _ := io.ReadAll(r.Body)
		var parsed struct {
			Model string `json:"model"`
		}
		_ = json.Unmarshal(raw, &parsed)
		w.Header().Set("Content-Type", "application/json")
		_, _ = w.Write([]byte(`{"id":"1","choices":[{"message":{"role":"assistant","content":"served-by:` +
			parsed.Model + `"}}],"usage":{"prompt_tokens":1,"completion_tokens":1,"total_tokens":2}}`))
	}))
	t.Cleanup(upstream.Close)

	ctx := context.Background()
	entryID, err := st.Insert(ctx, "INSERT INTO models (model_name, strategy, enabled) VALUES ('entry','failover',1)")
	if err != nil {
		t.Fatal(err)
	}
	groupID, err := st.Insert(ctx,
		"INSERT INTO model_groups (name, strategy, sticky, enabled) VALUES ('g1', ?, ?, 1)",
		strategy, boolToIntTest(sticky))
	if err != nil {
		t.Fatal(err)
	}
	if _, err := st.Exec(ctx,
		"INSERT INTO model_group_rels (model_id, group_id, sort_order, enabled) VALUES (?, ?, 0, 1)",
		entryID, groupID); err != nil {
		t.Fatal(err)
	}

	modelIDs := map[string]int64{}
	sort := 0
	// Deterministic insertion order for stable expectations.
	for _, name := range []string{"m-a", "m-b", "m-c"} {
		chID, err := st.Insert(ctx,
			"INSERT INTO channels (name, channel_type, base_url, enabled) VALUES (?, 'openai', ?, 1)",
			"ch-"+name, upstream.URL)
		if err != nil {
			t.Fatal(err)
		}
		keyID, err := st.Insert(ctx,
			"INSERT INTO channel_api_keys (channel_id, key_name, api_key, enabled, sort_order) VALUES (?, 'k', 'sk', 1, 0)", chID)
		if err != nil {
			t.Fatal(err)
		}
		cmID, err := st.Insert(ctx,
			"INSERT INTO channel_models (channel_id, model_name, enabled, input, context_length, channel_api_key_id) VALUES (?, ?, 1, 'text', 200000, ?)",
			chID, name, keyID)
		if err != nil {
			t.Fatal(err)
		}
		w := 1
		if weights != nil {
			if v, ok := weights[name]; ok {
				w = v
			}
		}
		if _, err := st.Exec(ctx,
			"INSERT INTO model_group_members (group_id, channel_model_id, weight, sort_order, enabled) VALUES (?, ?, ?, ?, 1)",
			groupID, cmID, w, sort); err != nil {
			t.Fatal(err)
		}
		modelIDs[name] = cmID
		sort++
	}
	return &groupFixture{core: core, store: st, upstream: upstream, modelIDs: modelIDs, groupID: groupID, entryID: entryID}
}

func boolToIntTest(b bool) int {
	if b {
		return 1
	}
	return 0
}

// serveOnce runs one non-stream request through the routing loop and returns the
// upstream model name that actually served it.
func (f *groupFixture) serveOnce(t *testing.T, content string) string {
	t.Helper()
	body := `{"model":"entry","messages":[{"role":"user","content":` + jsonString(content) + `}]}`
	req, err := ParseRequest(body, ProtoOpenAI)
	if err != nil {
		t.Fatalf("parse: %v", err)
	}
	res := f.core.RelayNonStream(context.Background(), req, "Bearer gw-key", "", body)
	if res.StatusCode != 200 {
		t.Fatalf("status %d body %s", res.StatusCode, res.Body)
	}
	const marker = "served-by:"
	i := strings.Index(res.Body, marker)
	if i < 0 {
		t.Fatalf("no marker in body: %s", res.Body)
	}
	rest := res.Body[i+len(marker):]
	if j := strings.IndexAny(rest, `"\`); j >= 0 {
		rest = rest[:j]
	}
	return rest
}

func jsonString(s string) string {
	b, _ := json.Marshal(s)
	return string(b)
}

// TestGroupFailoverKeepsMemberOrder proves the group's failover strategy exposes
// its members in sort order and that the entry model's own strategy does not
// scramble them.
func TestGroupFailoverKeepsMemberOrder(t *testing.T) {
	f := setupGroupFixture(t, GroupStrategyFailover, false, nil)
	if got := f.serveOnce(t, "hello"); got != "m-a" {
		t.Errorf("served by %q, want m-a (first member in sort order)", got)
	}
}

// TestGroupRoundRobinRotates proves the group round-robin rotates the starting
// member across requests while still queueing the rest behind it.
func TestGroupRoundRobinRotates(t *testing.T) {
	f := setupGroupFixture(t, GroupStrategyRoundRobin, false, nil)
	seen := map[string]int{}
	for i := 0; i < 6; i++ {
		seen[f.serveOnce(t, "same-prompt")]++
	}
	if len(seen) != 3 {
		t.Fatalf("round robin used %d distinct members over 6 requests, want 3 (got %v)", len(seen), seen)
	}
	for name, n := range seen {
		if n != 2 {
			t.Errorf("member %s served %d times over 6 requests, want 2 (even rotation, got %v)", name, n, seen)
		}
	}
}

// TestGroupRoundRobinHonorsWeights proves round_robin is weighted end to end: a
// heavy member leads proportionally more requests while every member still serves.
func TestGroupRoundRobinHonorsWeights(t *testing.T) {
	f := setupGroupFixture(t, GroupStrategyRoundRobin, false, map[string]int{"m-a": 3, "m-b": 1, "m-c": 1})
	seen := map[string]int{}
	const n = 300
	for i := 0; i < n; i++ {
		seen[f.serveOnce(t, "same-prompt")]++
	}
	if len(seen) != 3 {
		t.Fatalf("weighted round robin used %d distinct members over %d requests, want 3 (got %v)", len(seen), n, seen)
	}
	// 3:1:1 schedule — m-a leads 3/5 of the requests, the others 1/5 each.
	if seen["m-a"] != n*3/5 {
		t.Errorf("weight-3 member led %d/%d requests, want %d (got %v)", seen["m-a"], n, n*3/5, seen)
	}
	for _, name := range []string{"m-b", "m-c"} {
		if seen[name] != n/5 {
			t.Errorf("member %s led %d/%d requests, want %d (got %v)", name, seen[name], n, n/5, seen)
		}
	}
}

// TestGroupStickyPinsConversationToSameMember is the core cache-affinity promise:
// the same conversation prefix always lands on the same member, while different
// conversations spread across members.
func TestGroupStickyPinsConversationToSameMember(t *testing.T) {
	f := setupGroupFixture(t, GroupStrategyRandom, true, nil)

	first := f.serveOnce(t, "conversation-one")
	for i := 0; i < 8; i++ {
		if got := f.serveOnce(t, "conversation-one"); got != first {
			t.Fatalf("sticky group moved conversation from %s to %s on repeat %d", first, got, i)
		}
	}

	// Different conversations should not all collapse onto one member.
	spread := map[string]bool{}
	for _, prompt := range []string{"alpha", "beta", "gamma", "delta", "epsilon", "zeta", "eta", "theta"} {
		spread[f.serveOnce(t, prompt)] = true
	}
	if len(spread) < 2 {
		t.Errorf("sticky hashing mapped 8 distinct conversations onto %d member(s); expected spread", len(spread))
	}
}

// TestStickyKeyIgnoresAppendedTurns pins the property the feature depends on: a
// growing conversation keeps the same key, because only the leading messages
// participate in the hash.
func TestStickyKeyIgnoresAppendedTurns(t *testing.T) {
	base := `{"model":"m","messages":[
		{"role":"system","content":"you are helpful"},
		{"role":"user","content":"first question"},
		{"role":"assistant","content":"first answer"}
	]}`
	grown := `{"model":"m","messages":[
		{"role":"system","content":"you are helpful"},
		{"role":"user","content":"first question"},
		{"role":"assistant","content":"first answer"},
		{"role":"user","content":"second question"},
		{"role":"assistant","content":"second answer"},
		{"role":"user","content":"third question"}
	]}`
	reqBase, err := ParseRequest(base, ProtoOpenAI)
	if err != nil {
		t.Fatal(err)
	}
	reqGrown, err := ParseRequest(grown, ProtoOpenAI)
	if err != nil {
		t.Fatal(err)
	}
	if a, b := StickyKey(reqBase), StickyKey(reqGrown); a != b {
		t.Errorf("appending turns changed the sticky key (%s vs %s); cache affinity would break each turn", a, b)
	}

	other := `{"model":"m","messages":[{"role":"user","content":"a completely different question"}]}`
	reqOther, _ := ParseRequest(other, ProtoOpenAI)
	if StickyKey(reqBase) == StickyKey(reqOther) {
		t.Error("different conversations produced the same sticky key")
	}
}

// TestStickySurvivesMemberChange partially is the point of the consistent hash
// ring: adding a member must not re-map every conversation.
func TestStickySurvivesMemberChange(t *testing.T) {
	f := setupGroupFixture(t, GroupStrategyRandom, true, nil)

	prompts := make([]string, 30)
	before := make(map[string]string, len(prompts))
	for i := range prompts {
		prompts[i] = "conv-" + string(rune('A'+i%26)) + "-" + string(rune('0'+i/26))
		before[prompts[i]] = f.serveOnce(t, prompts[i])
	}

	// Add a fourth member to the group.
	ctx := context.Background()
	chID, err := f.store.Insert(ctx,
		"INSERT INTO channels (name, channel_type, base_url, enabled) VALUES ('ch-extra','openai',?,1)", f.upstream.URL)
	if err != nil {
		t.Fatal(err)
	}
	keyID, _ := f.store.Insert(ctx,
		"INSERT INTO channel_api_keys (channel_id, key_name, api_key, enabled, sort_order) VALUES (?, 'k', 'sk', 1, 0)", chID)
	cmID, err := f.store.Insert(ctx,
		"INSERT INTO channel_models (channel_id, model_name, enabled, input, context_length, channel_api_key_id) VALUES (?, 'm-extra', 1, 'text', 200000, ?)",
		chID, keyID)
	if err != nil {
		t.Fatal(err)
	}
	if _, err := f.store.Exec(ctx,
		"INSERT INTO model_group_members (group_id, channel_model_id, weight, sort_order, enabled) VALUES (?, ?, 1, 99, 1)",
		f.groupID, cmID); err != nil {
		t.Fatal(err)
	}

	kept := 0
	for _, p := range prompts {
		if f.serveOnce(t, p) == before[p] {
			kept++
		}
	}
	// Consistent hashing re-maps roughly 1/N of the keys on an N->N+1 change.
	// Require the overwhelming majority to stay put; a modulo hash would lose ~75%.
	if kept*10 < len(prompts)*6 {
		t.Errorf("only %d/%d conversations kept their member after adding one member; consistent hashing should preserve most", kept, len(prompts))
	}
}

// TestGroupWeightBiasesWeightedRandom proves weights actually steer distribution:
// a member with much higher weight must serve most requests.
func TestGroupWeightBiasesWeightedRandom(t *testing.T) {
	f := setupGroupFixture(t, GroupStrategyRandom, false, map[string]int{"m-a": 20, "m-b": 1, "m-c": 1})
	counts := map[string]int{}
	const n = 200
	for i := 0; i < n; i++ {
		counts[f.serveOnce(t, "prompt-"+string(rune('a'+i%26)))]++
	}
	if counts["m-a"] <= n/2 {
		t.Errorf("weight 20 member served only %d/%d requests; weights are not steering selection (got %v)", counts["m-a"], n, counts)
	}
}

// TestGroupMemberSkipOnCircuitBreak proves a broken group member is skipped inside
// the group and the next member serves — i.e. the group is a real sub-route, not
// just a label.
func TestGroupMemberSkipOnCircuitBreak(t *testing.T) {
	f := setupGroupFixture(t, GroupStrategyFailover, false, nil)
	ctx := context.Background()

	// Trip the first member's model-level breaker.
	cmID := f.modelIDs["m-a"]
	cmRow, _ := f.store.QueryOne(ctx, "SELECT channel_id, channel_api_key_id FROM channel_models WHERE id = ?", cmID)
	// The minimal relay fixture keeps the legacy breaker columns
	// (api_key_id / broken_at / expires_at); the routing loop only reads
	// circuit state through CircuitCheckFn, which the stub below supplies.
	if _, err := f.store.Exec(ctx,
		`INSERT INTO circuit_breaker_states (channel_id, channel_model_id, api_key_id, broken_at, expires_at)
		 VALUES (?, ?, ?, datetime('now'), datetime('now','+1 hour'))`,
		cmRow.I64("channel_id", 0), cmID, cmRow.I64("channel_api_key_id", 0)); err != nil {
		t.Fatal(err)
	}
	// Wire the breaker check the way WireRelayRuntime does.
	f.core.CircuitCheckFn = func(ctx context.Context, c RoutingCandidate) (bool, string) {
		row := f.store.QueryOneOrZero(ctx,
			"SELECT id FROM circuit_breaker_states WHERE channel_model_id = ?", c.ChannelModelID)
		if row != nil {
			return true, "模型级熔断"
		}
		return false, ""
	}

	if got := f.serveOnce(t, "hello"); got != "m-b" {
		t.Errorf("served by %q, want m-b (m-a is circuit-broken and must be skipped inside the group)", got)
	}
}

// TestBuildCandidatesMergesGroupAndDirectRels proves both relation kinds land in
// one candidate queue ordered by their shared sort_order space.
func TestBuildCandidatesMergesGroupAndDirectRels(t *testing.T) {
	f := setupGroupFixture(t, GroupStrategyFailover, false, nil)
	ctx := context.Background()

	// A direct relation placed before the group (sort_order -1).
	chID, _ := f.store.Insert(ctx,
		"INSERT INTO channels (name, channel_type, base_url, enabled) VALUES ('ch-direct','openai',?,1)", f.upstream.URL)
	keyID, _ := f.store.Insert(ctx,
		"INSERT INTO channel_api_keys (channel_id, key_name, api_key, enabled, sort_order) VALUES (?, 'k', 'sk', 1, 0)", chID)
	directCM, err := f.store.Insert(ctx,
		"INSERT INTO channel_models (channel_id, model_name, enabled, input, context_length, channel_api_key_id) VALUES (?, 'm-direct', 1, 'text', 200000, ?)",
		chID, keyID)
	if err != nil {
		t.Fatal(err)
	}
	if _, err := f.store.Exec(ctx,
		"INSERT INTO model_channel_rels (model_id, channel_model_id, sort_order, enabled) VALUES (?, ?, -1, 1)",
		f.entryID, directCM); err != nil {
		t.Fatal(err)
	}

	req, _ := ParseRequest(`{"model":"entry","messages":[{"role":"user","content":"hi"}]}`, ProtoOpenAI)
	candidates := f.core.RouteResolver.BuildCandidates(ctx, req)
	if len(candidates) != 4 {
		t.Fatalf("got %d candidates, want 4 (1 direct + 3 group members)", len(candidates))
	}
	if candidates[0].ModelName != "m-direct" {
		t.Errorf("first candidate is %q, want m-direct (sort_order -1 precedes the group)", candidates[0].ModelName)
	}
	wantAfter := []string{"m-a", "m-b", "m-c"}
	for i, want := range wantAfter {
		if got := candidates[i+1].ModelName; got != want {
			t.Errorf("candidate %d is %q, want %q (group members follow in sort order)", i+1, got, want)
		}
	}
}

// TestGroupInheritModeMirrorsGroups proves a model inheriting from another picks
// up the source model's group relations too.
func TestGroupInheritModeMirrorsGroups(t *testing.T) {
	f := setupGroupFixture(t, GroupStrategyFailover, false, nil)
	ctx := context.Background()

	childID, err := f.store.Insert(ctx,
		"INSERT INTO models (model_name, strategy, enabled, rel_mode, inherit_from_model_id) VALUES ('child','failover',1,'inherit',?)",
		f.entryID)
	if err != nil {
		t.Fatal(err)
	}
	req, _ := ParseRequest(`{"model":"child","messages":[{"role":"user","content":"hi"}]}`, ProtoOpenAI)
	candidates := f.core.RouteResolver.BuildCandidates(ctx, req)
	if len(candidates) != 3 {
		t.Fatalf("inheriting model got %d candidates, want 3 (mirrored from the source model's group); childID=%d", len(candidates), childID)
	}
	if candidates[0].ModelName != "m-a" {
		t.Errorf("first inherited candidate is %q, want m-a", candidates[0].ModelName)
	}
}

// TestDisabledGroupContributesNoCandidates proves a disabled group drops out of
// routing instead of silently serving.
func TestDisabledGroupContributesNoCandidates(t *testing.T) {
	f := setupGroupFixture(t, GroupStrategyFailover, false, nil)
	ctx := context.Background()
	if _, err := f.store.Exec(ctx, "UPDATE model_groups SET enabled = 0 WHERE id = ?", f.groupID); err != nil {
		t.Fatal(err)
	}
	req, _ := ParseRequest(`{"model":"entry","messages":[{"role":"user","content":"hi"}]}`, ProtoOpenAI)
	if got := f.core.RouteResolver.BuildCandidates(ctx, req); len(got) != 0 {
		t.Fatalf("disabled group still contributed %d candidates", len(got))
	}
}

// TestDisabledGroupMemberIsExcluded proves a disabled member is not routable.
func TestDisabledGroupMemberIsExcluded(t *testing.T) {
	f := setupGroupFixture(t, GroupStrategyFailover, false, nil)
	ctx := context.Background()
	if _, err := f.store.Exec(ctx,
		"UPDATE model_group_members SET enabled = 0 WHERE group_id = ? AND channel_model_id = ?",
		f.groupID, f.modelIDs["m-a"]); err != nil {
		t.Fatal(err)
	}
	req, _ := ParseRequest(`{"model":"entry","messages":[{"role":"user","content":"hi"}]}`, ProtoOpenAI)
	candidates := f.core.RouteResolver.BuildCandidates(ctx, req)
	if len(candidates) != 2 {
		t.Fatalf("got %d candidates, want 2 (disabled member excluded)", len(candidates))
	}
	if candidates[0].ModelName != "m-b" {
		t.Errorf("first candidate is %q, want m-b", candidates[0].ModelName)
	}
}

// TestGroupWeightIsPerMemberWithMultipleAPIKeys proves a channel model with more
// API keys does not receive extra probability. Member selection is weighted once;
// all keys remain grouped behind that member for failover.
func TestGroupWeightIsPerMemberWithMultipleAPIKeys(t *testing.T) {
	f := setupGroupFixture(t, GroupStrategyRandom, false, nil)
	ctx := context.Background()
	row := f.store.QueryOneOrZero(ctx, "SELECT channel_id FROM channel_models WHERE id = ?", f.modelIDs["m-a"])
	channelID := row.I64("channel_id", 0)
	if _, err := f.store.Exec(ctx, "UPDATE channel_models SET channel_api_key_id = NULL WHERE id = ?", f.modelIDs["m-a"]); err != nil {
		t.Fatal(err)
	}
	for i := 0; i < 3; i++ {
		if _, err := f.store.Insert(ctx,
			"INSERT INTO channel_api_keys (channel_id, key_name, api_key, enabled, sort_order) VALUES (?, ?, ?, 1, ?)",
			channelID, "extra-"+string(rune('1'+i)), "sk-extra-"+string(rune('1'+i)), i+1); err != nil {
			t.Fatal(err)
		}
	}

	counts := map[string]int{}
	for i := 0; i < 500; i++ {
		req, _ := ParseRequest(`{"model":"entry","messages":[{"role":"user","content":"weighted"}]}`, ProtoOpenAI)
		candidates := f.core.RouteResolver.BuildCandidates(ctx, req)
		if len(candidates) != 6 { // member A has 4 keys, B and C have one each
			t.Fatalf("got %d candidates, want 6 after adding three keys to member A", len(candidates))
		}
		for _, c := range candidates {
			if c.GroupMemberID == 0 {
				t.Fatalf("group candidate %q has no group-member identity", c.ModelName)
			}
		}
		counts[candidates[0].ModelName]++
	}
	if counts["m-a"] < 140 || counts["m-a"] > 200 {
		t.Errorf("member with 4 keys led %d/500 weighted draws; equal-weight members should each lead about 1/3: %v", counts["m-a"], counts)
	}
}

// TestStickyMemberIdentityIgnoresAPIKeyCount proves the consistent-hash ring is
// built from member identity and weight, not API-key identity/count.
func TestStickyMemberIdentityIgnoresAPIKeyCount(t *testing.T) {
	f := setupGroupFixture(t, GroupStrategyRandom, true, nil)
	ctx := context.Background()
	requestBody := `{"model":"entry","messages":[{"role":"system","content":"stable"},{"role":"user","content":"same conversation"}]}`
	buildOrder := func() string {
		req, err := ParseRequest(requestBody, ProtoOpenAI)
		if err != nil {
			t.Fatal(err)
		}
		candidates := f.core.RouteResolver.BuildCandidates(ctx, req)
		if len(candidates) == 0 {
			t.Fatal("no candidates")
		}
		return candidates[0].ModelName
	}
	before := buildOrder()
	row := f.store.QueryOneOrZero(ctx, "SELECT channel_id FROM channel_models WHERE id = ?", f.modelIDs["m-a"])
	if _, err := f.store.Exec(ctx, "UPDATE channel_models SET channel_api_key_id = NULL WHERE id = ?", f.modelIDs["m-a"]); err != nil {
		t.Fatal(err)
	}
	for i := 0; i < 5; i++ {
		if _, err := f.store.Insert(ctx,
			"INSERT INTO channel_api_keys (channel_id, key_name, api_key, enabled, sort_order) VALUES (?, ?, ?, 1, ?)",
			row.I64("channel_id", 0), "sticky-extra-"+string(rune('1'+i)), "sk-sticky-extra-"+string(rune('1'+i)), i+1); err != nil {
			t.Fatal(err)
		}
	}
	for i := 0; i < 10; i++ {
		if got := buildOrder(); got != before {
			t.Fatalf("API key count changed sticky member from %q to %q", before, got)
		}
	}
}
