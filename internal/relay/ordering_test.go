package relay

import (
	"testing"
	"time"

	"github.com/my-search/my-ai-gateway/internal/models"
)

// groupTestCandidate builds a candidate with the identity fields the ordering and
// ring code key on.
func groupTestCandidate(id, keyID int64, name string) RoutingCandidate {
	return RoutingCandidate{ChannelModelID: id, APIKeyID: keyID, ModelName: name}
}

// groupTestBlocks builds one member block per name, so the *Blocks ordering
// helpers (the ones production uses) can be exercised directly.
func groupTestBlocks(names []string, weights []int) [][]RoutingCandidate {
	candidates := make([]RoutingCandidate, len(names))
	for i, n := range names {
		candidates[i] = groupTestCandidate(int64(i+1), 1, n)
	}
	return groupCandidateBlocks(groupOrdering{candidates: candidates, weights: weights})
}

// firstMember returns the model name leading the flattened ordering.
func firstMember(ordered []RoutingCandidate) string {
	if len(ordered) == 0 {
		return ""
	}
	return ordered[0].ModelName
}

// TestOrderEntriesKeepsGroupContiguous is the invariant that makes a group a real
// sub-route: under every entry-model strategy the group's members must stay
// adjacent and in the order the group decided.
func TestOrderEntriesKeepsGroupContiguous(t *testing.T) {
	entries := []routingEntry{
		{candidates: []RoutingCandidate{groupTestCandidate(1, 1, "direct-1")}},
		{isGroup: true, groupID: 7, candidates: []RoutingCandidate{
			groupTestCandidate(10, 1, "g1"), groupTestCandidate(11, 1, "g2"), groupTestCandidate(12, 1, "g3"),
		}},
		{candidates: []RoutingCandidate{groupTestCandidate(2, 1, "direct-2")}},
	}
	for _, strategy := range []string{"failover", "sticky", "random", "round_robin", ""} {
		for i := 0; i < 20; i++ {
			got := orderEntries(strategy, entries, 42, "session-"+string(rune('a'+i)))
			if len(got) != 3 {
				t.Fatalf("%s: got %d entries, want 3", strategy, len(got))
			}
			// Every entry keeps its own candidate slice intact (a group is never split).
			for _, e := range got {
				if !e.isGroup {
					continue
				}
				if len(e.candidates) != 3 || e.candidates[0].ModelName != "g1" || e.candidates[2].ModelName != "g3" {
					t.Fatalf("%s: group entry was split or reordered: %v", strategy, e.candidates)
				}
			}
			// The group entry must appear exactly once.
			groups := 0
			for _, e := range got {
				if e.isGroup {
					groups++
				}
			}
			if groups != 1 {
				t.Fatalf("%s: group entry count %d, want 1", strategy, groups)
			}
		}
	}
}

// TestOrderEntriesFailoverIsStable proves failover keeps the configured order,
// with or without a session key.
func TestOrderEntriesFailoverIsStable(t *testing.T) {
	entries := []routingEntry{
		{candidates: []RoutingCandidate{groupTestCandidate(1, 1, "first")}},
		{candidates: []RoutingCandidate{groupTestCandidate(2, 1, "second")}},
		{candidates: []RoutingCandidate{groupTestCandidate(3, 1, "third")}},
	}
	for _, key := range []string{"", "session-1"} {
		for i := 0; i < 5; i++ {
			got := orderEntries("failover", entries, 1, key)
			for j, want := range []string{"first", "second", "third"} {
				if got[j].candidates[0].ModelName != want {
					t.Fatalf("failover order changed at position %d: got %s want %s", j, got[j].candidates[0].ModelName, want)
				}
			}
		}
	}
}

// TestOrderEntriesStickyPinsSession proves the sticky strategy maps one session
// key to a deterministic entry, and legacy random / round_robin values behave
// exactly like sticky (the only non-failover strategy).
func TestOrderEntriesStickyPinsSession(t *testing.T) {
	newEntries := func() []routingEntry {
		return []routingEntry{
			{candidates: []RoutingCandidate{groupTestCandidate(1, 1, "a")}},
			{candidates: []RoutingCandidate{groupTestCandidate(2, 1, "b")}},
			{candidates: []RoutingCandidate{groupTestCandidate(3, 1, "c")}},
		}
	}
	leader := func(strategy, key string) string {
		return orderEntries(strategy, newEntries(), 99, key)[0].candidates[0].ModelName
	}

	pinned := leader("sticky", "same-session")
	for i := 0; i < 10; i++ {
		if got := leader("sticky", "same-session"); got != pinned {
			t.Fatalf("sticky session moved from %s to %s", pinned, got)
		}
	}
	// 历史策略值统一按粘性处理：同一 key 必须命中同一 leader。
	if got := leader("random", "same-session"); got != pinned {
		t.Errorf("legacy random leader = %s, want %s (normalized to sticky)", got, pinned)
	}
	if got := leader("round_robin", "same-session"); got != pinned {
		t.Errorf("legacy round_robin leader = %s, want %s (normalized to sticky)", got, pinned)
	}
	// 重排始终是完整排列：不丢条目、不重复。
	for _, key := range []string{"k1", "k2", "k3", "k4", "k5"} {
		got := orderEntries("sticky", newEntries(), 99, key)
		if len(got) != 3 {
			t.Fatalf("sticky order returned %d entries, want 3", len(got))
		}
		seen := map[string]bool{}
		for _, e := range got {
			if seen[e.candidates[0].ModelName] {
				t.Fatalf("entry %s appears twice in %v", e.candidates[0].ModelName, got)
			}
			seen[e.candidates[0].ModelName] = true
		}
	}
}

// TestOrderEntriesStickyWithoutKeyFallsBack proves a session without hashable
// content degrades to the configured order instead of pinning everything.
func TestOrderEntriesStickyWithoutKeyFallsBack(t *testing.T) {
	entries := []routingEntry{
		{candidates: []RoutingCandidate{groupTestCandidate(1, 1, "first")}},
		{candidates: []RoutingCandidate{groupTestCandidate(2, 1, "second")}},
	}
	got := orderEntries("sticky", entries, 1, "")
	if got[0].candidates[0].ModelName != "first" || got[1].candidates[0].ModelName != "second" {
		t.Errorf("sticky without a session key changed the order: %v", got)
	}
}

// TestOrderEntriesWeightsSteerSticky proves entry weights steer the hash ring:
// a heavy entry owns proportionally more sessions.
func TestOrderEntriesWeightsSteerSticky(t *testing.T) {
	newEntries := func() []routingEntry {
		a := groupTestCandidate(1, 1, "heavy")
		a.Weight = 9
		b := groupTestCandidate(2, 1, "light")
		b.Weight = 1
		return []routingEntry{{candidates: []RoutingCandidate{a}}, {candidates: []RoutingCandidate{b}}}
	}
	heavy := 0
	const sessions = 400
	for i := 0; i < sessions; i++ {
		key := "conversation-" + string(rune('a'+i%26)) + "-" + string(rune('0'+i/26))
		if orderEntries("sticky", newEntries(), 555, key)[0].candidates[0].ModelName == "heavy" {
			heavy++
		}
	}
	if heavy < sessions*70/100 {
		t.Errorf("weight 9 entry pinned only %d/%d sessions; expected roughly 90%%", heavy, sessions)
	}
}

// TestNormalizeModelStrategy documents the entry-model strategy normalization:
// only failover / sticky survive, legacy random / round_robin become sticky and
// unknown values fall back to failover.
func TestNormalizeModelStrategy(t *testing.T) {
	cases := map[string]string{
		"":            ModelStrategyFailover,
		"  ":          ModelStrategyFailover,
		"bogus":       ModelStrategyFailover,
		"failover":    ModelStrategyFailover,
		"sticky":      ModelStrategySticky,
		"random":      ModelStrategySticky,
		"round_robin": ModelStrategySticky,
		" sticky":     ModelStrategySticky,
	}
	for in, want := range cases {
		if got := normalizeModelStrategy(in); got != want {
			t.Errorf("normalizeModelStrategy(%q) = %q, want %q", in, got, want)
		}
	}
}

// TestGroupWeightsApplyPerMemberNotPerAPIKey proves one member with many API
// keys still receives exactly its configured member weight, rather than being
// selected multiple times because its channel has more keys.
func TestGroupWeightsApplyPerMemberNotPerAPIKey(t *testing.T) {
	candidates := []RoutingCandidate{
		{ChannelModelID: 1, GroupMemberID: 101, APIKeyID: 1, ModelName: "member-a"},
		{ChannelModelID: 1, GroupMemberID: 101, APIKeyID: 2, ModelName: "member-a"},
		{ChannelModelID: 1, GroupMemberID: 101, APIKeyID: 3, ModelName: "member-a"},
		{ChannelModelID: 1, GroupMemberID: 101, APIKeyID: 4, ModelName: "member-a"},
		{ChannelModelID: 2, GroupMemberID: 102, APIKeyID: 5, ModelName: "member-b"},
	}
	weights := []int{1, 1, 1, 1, 1}
	blocks := groupCandidateBlocks(groupOrdering{candidates: candidates, weights: weights})
	if len(blocks) != 2 {
		t.Fatalf("got %d member blocks, want 2", len(blocks))
	}
	if len(blocks[0]) != 4 || len(blocks[1]) != 1 {
		t.Fatalf("API key candidates not grouped by member: block sizes %d, %d", len(blocks[0]), len(blocks[1]))
	}

	// If weights are equal, a member with four keys should not win ~80% of the
	// sessions; each member owns half of the hash ring.
	aWins := 0
	const sessions = 1000
	for i := 0; i < sessions; i++ {
		key := "session-" + string(rune('a'+i%26)) + "-" + string(rune('0'+i/26))
		if stickyBlocks(blocks, 7, "member-keys", key)[0][0].GroupMemberID == 101 {
			aWins++
		}
	}
	if aWins < sessions*40/100 || aWins > sessions*60/100 {
		t.Errorf("member with 4 API keys won %d/%d sessions with equal member weights; API key count must not multiply weight", aWins, sessions)
	}

	// All keys of the chosen member remain contiguous for failover within member.
	ordered := orderGroupCandidates(groupOrdering{candidates: candidates, weights: weights, strategy: GroupStrategyFailover})
	if ordered[0].GroupMemberID != 101 || ordered[3].GroupMemberID != 101 || ordered[4].GroupMemberID != 102 {
		t.Errorf("member API keys were not kept as a contiguous failover block: %+v", ordered)
	}
}

// TestStickyKeepsEveryCandidate proves reordering never drops or duplicates a
// candidate (the routing loop relies on the pool being exact), including
// degenerate zero/negative weights.
func TestStickyKeepsEveryCandidate(t *testing.T) {
	blocks := groupTestBlocks([]string{"a", "b", "c"}, []int{3, 1, 2})
	for i := 0; i < 50; i++ {
		key := "session-" + string(rune('a'+i%26)) + "-" + string(rune('0'+i/26))
		got := flattenCandidateBlocks(stickyBlocks(blocks, 21, "exact", key))
		if len(got) != 3 {
			t.Fatalf("got %d candidates, want 3", len(got))
		}
		seen := map[string]bool{}
		for _, c := range got {
			if seen[c.ModelName] {
				t.Fatalf("candidate %s appears twice in %v", c.ModelName, got)
			}
			seen[c.ModelName] = true
		}
	}
	// All-zero weights must still produce a full, non-crashing ordering.
	zeroBlocks := groupTestBlocks([]string{"a", "b", "c"}, []int{0, -5, 0})
	if got := flattenCandidateBlocks(stickyBlocks(zeroBlocks, 22, "zero", "session")); len(got) != 3 {
		t.Fatalf("all-zero weights: got %d candidates, want 3", len(got))
	}
}

// TestRingCacheRebuildsOnWeightChange proves a configuration change invalidates
// the cached ring (otherwise weight edits would never take effect).
func TestRingCacheRebuildsOnWeightChange(t *testing.T) {
	candidates := []RoutingCandidate{groupTestCandidate(1, 1, "a"), groupTestCandidate(2, 1, "b")}
	r1 := groupRing(12345, "test", candidates, []int{1, 1})
	r2 := groupRing(12345, "test", candidates, []int{1, 1})
	if r1 != r2 {
		t.Error("unchanged configuration rebuilt the ring; the cache is not working")
	}
	r3 := groupRing(12345, "test", candidates, []int{5, 1})
	if r3 == r1 {
		t.Error("changed weights reused the cached ring; a weight edit would not take effect")
	}
	// The total vnode count is capped, so a heavier config shows up as a larger
	// share for the heavy member rather than a bigger ring.
	if shareOf(r3, 0) <= shareOf(r1, 0) {
		t.Errorf("heavier config gave owner 0 a share of %.3f, want more than %.3f",
			shareOf(r3, 0), shareOf(r1, 0))
	}
}

// shareOf returns the fraction of the ring owned by one candidate index.
func shareOf(r *hashRing, owner int) float64 {
	if len(r.owners) == 0 {
		return 0
	}
	n := 0
	for _, o := range r.owners {
		if o == owner {
			n++
		}
	}
	return float64(n) / float64(len(r.owners))
}

// TestRingBuildCostIsBounded proves the max-weight configuration no longer blows
// up the ring: vnode count stays capped and the build stays well under a
// millisecond-scale budget instead of the multi-second linear expansion.
func TestRingBuildCostIsBounded(t *testing.T) {
	// Worst case the admin UI allows: many members, each at weight 1000.
	candidates := make([]RoutingCandidate, 0, 16)
	weights := make([]int, 0, 16)
	for i := 0; i < 16; i++ {
		candidates = append(candidates, groupTestCandidate(int64(i+1), 1, "m"))
		weights = append(weights, 1000)
	}
	start := time.Now()
	ring := buildRing(candidates, weights)
	elapsed := time.Since(start)

	if len(ring.points) > ringMaxVNods {
		t.Errorf("ring has %d virtual nodes, want at most %d", len(ring.points), ringMaxVNods)
	}
	if elapsed > 500*time.Millisecond {
		t.Errorf("ring build took %v for the max-weight config; must stay bounded", elapsed)
	}
	// The cap must not starve members: every one still owns ring territory.
	owners := map[int]bool{}
	for _, o := range ring.owners {
		owners[o] = true
	}
	if len(owners) != len(candidates) {
		t.Errorf("ring covers %d of %d members; a member would never receive traffic", len(owners), len(candidates))
	}
}

// TestRingVNodeCountsFollowWeightRatio proves the capped allocation still keeps
// weight semantics: a heavier member owns proportionally more of the ring.
func TestRingVNodeCountsFollowWeightRatio(t *testing.T) {
	counts := ringVNodeCounts([]int{3, 1})
	if counts[0] <= counts[1] {
		t.Fatalf("counts = %v, want the weight-3 member to own more nodes", counts)
	}
	// Roughly 3:1 — allow generous slack for integer rounding.
	ratio := float64(counts[0]) / float64(counts[1])
	if ratio < 2.0 || ratio > 4.0 {
		t.Errorf("vnode ratio = %.2f, want about 3", ratio)
	}
	// A tiny weight still gets at least one node (otherwise it is unreachable).
	tiny := ringVNodeCounts([]int{1000, 1})
	if tiny[1] < 1 {
		t.Errorf("tiny member got %d nodes, want at least 1", tiny[1])
	}
	total := 0
	for _, n := range tiny {
		total += n
	}
	if total > ringMaxVNods {
		t.Errorf("total vnodes = %d, want at most %d", total, ringMaxVNods)
	}
	// Degenerate input must not divide by zero.
	if got := ringVNodeCounts([]int{0, -1}); len(got) != 2 || got[0] < 1 || got[1] < 1 {
		t.Errorf("ringVNodeCounts(all-zero) = %v, want at least one node each", got)
	}
	if got := ringVNodeCounts(nil); len(got) != 0 {
		t.Errorf("ringVNodeCounts(nil) = %v, want empty", got)
	}
}

// TestRingVNodeCountsUnchangedForTypicalWeights proves the cap does not alter
// ordinary configurations: below the budget the allocation stays exactly
// weight*ringVNodeScale, so existing deployments see no ring reshuffle.
func TestRingVNodeCountsUnchangedForTypicalWeights(t *testing.T) {
	for _, weights := range [][]int{{1, 1, 1}, {5, 1}, {3, 1, 2}, {1}, {1, 1, 1, 1, 1, 1, 1, 1, 1, 1}} {
		counts := ringVNodeCounts(weights)
		for i, w := range weights {
			if counts[i] != normalizeWeight(w)*ringVNodeScale {
				t.Fatalf("weights %v: counts[%d] = %d, want %d (unchanged below the budget)",
					weights, i, counts[i], normalizeWeight(w)*ringVNodeScale)
			}
		}
	}
	// Exactly at the budget boundary the linear expansion is still preserved.
	if counts := ringVNodeCounts([]int{10, 10}); counts[0] != 10*ringVNodeScale {
		t.Errorf("counts = %v, want %d at the budget boundary", counts, 10*ringVNodeScale)
	}
}

// TestStickyWeightDistribution proves ring weights steer conversation affinity:
// a heavier member must own a proportionally larger share of the ring.
func TestStickyWeightDistribution(t *testing.T) {
	blocks := groupTestBlocks([]string{"heavy", "light"}, []int{9, 1})
	heavy := 0
	const sessions = 400
	for i := 0; i < sessions; i++ {
		key := "conversation-" + string(rune('a'+i%26)) + "-" + string(rune('0'+i/26))
		if stickyBlocks(blocks, 777, "dist", key)[0][0].ModelName == "heavy" {
			heavy++
		}
	}
	if heavy < sessions*70/100 {
		t.Errorf("weight 9 member pinned only %d/%d conversations; expected roughly 90%%", heavy, sessions)
	}
}

// TestStickyKeyEmptyWithoutContent proves a request with nothing to hash degrades
// gracefully instead of producing a constant key that would pin every request.
func TestStickyKeyEmptyWithoutContent(t *testing.T) {
	if k := StickyKey(nil); k != "" {
		t.Errorf("StickyKey(nil) = %q, want empty", k)
	}
	if k := StickyKey(&InternalRequest{}); k != "" {
		t.Errorf("StickyKey(empty request) = %q, want empty", k)
	}
}

// TestStickyKeyUsesContentPartsText proves multimodal messages hash on their text
// parts (images excluded, since their URLs vary per request).
func TestStickyKeyUsesContentPartsText(t *testing.T) {
	req := &InternalRequest{Messages: []InternalMessage{{
		Role: "user",
		ContentParts: []map[string]any{
			{"type": "text", "text": "describe this"},
			{"type": "image_url", "image_url": map[string]any{"url": "https://example.com/a.png"}},
		},
	}}}
	k1 := StickyKey(req)
	if k1 == "" {
		t.Fatal("multimodal message produced an empty sticky key")
	}
	// Same text, different image URL → same key.
	req2 := &InternalRequest{Messages: []InternalMessage{{
		Role: "user",
		ContentParts: []map[string]any{
			{"type": "text", "text": "describe this"},
			{"type": "image_url", "image_url": map[string]any{"url": "https://example.com/DIFFERENT.png"}},
		},
	}}}
	if StickyKey(req2) != k1 {
		t.Error("changing only the image URL changed the sticky key; varying URLs would break affinity")
	}
}

// TestNormalizeGroupStrategyUnifiesLegacy documents the normalization: only
// failover / sticky survive; blank and legacy random / round_robin values become
// sticky (a corrupt value must not disable routing).
func TestNormalizeGroupStrategyUnifiesLegacy(t *testing.T) {
	cases := map[string]string{
		"":             GroupStrategySticky,
		"  ":           GroupStrategySticky,
		"bogus":        GroupStrategySticky,
		"failover":     GroupStrategyFailover,
		"sticky":       GroupStrategySticky,
		"random":       GroupStrategySticky,
		"round_robin":  GroupStrategySticky,
		" round_robin": GroupStrategySticky,
	}
	for in, want := range cases {
		if got := normalizeGroupStrategy(in); got != want {
			t.Errorf("normalizeGroupStrategy(%q) = %q, want %q", in, got, want)
		}
	}
}

// TestNormalizeWeightTreatsNonPositiveAsOne proves a missing weight is neutral.
func TestNormalizeWeightTreatsNonPositiveAsOne(t *testing.T) {
	for _, in := range []int{0, -1, -100} {
		if got := normalizeWeight(in); got != 1 {
			t.Errorf("normalizeWeight(%d) = %d, want 1", in, got)
		}
	}
	if got := normalizeWeight(7); got != 7 {
		t.Errorf("normalizeWeight(7) = %d, want 7", got)
	}
}

// TestOrderGroupMarksStickyWinner 覆盖请求日志来源标记：粘性策略生效时，哈希命中的
// 成员（排在组内首位）的每个候选都带 sticky 标记，其余成员保持未标记。
func TestOrderGroupMarksStickyWinner(t *testing.T) {
	candidates := []RoutingCandidate{
		groupTestCandidate(1, 1, "a"), groupTestCandidate(2, 1, "b"), groupTestCandidate(3, 1, "c"),
	}
	for _, strategy := range []string{GroupStrategySticky, "random", "round_robin"} {
		got := orderGroupCandidates(groupOrdering{
			candidates: candidates, weights: []int{1, 1, 1},
			strategy: strategy, stickyKey: "session-1", groupID: 7,
		})
		if len(got) != 3 {
			t.Fatalf("%s: ordered %d candidates, want 3", strategy, len(got))
		}
		if got[0].RouteSource != models.RouteSourceSticky {
			t.Errorf("%s: leading member RouteSource = %q, want %q",
				strategy, got[0].RouteSource, models.RouteSourceSticky)
		}
		for _, c := range got[1:] {
			if c.RouteSource != "" {
				t.Errorf("%s: non-pinned member %s carries RouteSource %q; only the pinned member is marked",
					strategy, c.ModelName, c.RouteSource)
			}
		}
	}
}

// TestOrderGroupNoStickyMarkWhenInactive 覆盖不标记的情形：顺序策略，或会话前缀
// 无法计算（stickyKey 为空），都不应把成员说成「粘性命中」。
func TestOrderGroupNoStickyMarkWhenInactive(t *testing.T) {
	candidates := []RoutingCandidate{
		groupTestCandidate(1, 1, "a"), groupTestCandidate(2, 1, "b"), groupTestCandidate(3, 1, "c"),
	}
	cases := []struct {
		name     string
		strategy string
		key      string
	}{
		{"failover strategy", GroupStrategyFailover, "session-1"},
		{"sticky without key", GroupStrategySticky, ""},
	}
	for _, tc := range cases {
		got := orderGroupCandidates(groupOrdering{
			candidates: candidates, weights: []int{1, 1, 1},
			strategy: tc.strategy, stickyKey: tc.key, groupID: 7,
		})
		for _, c := range got {
			if c.RouteSource != "" {
				t.Errorf("%s: member %s carries RouteSource %q, want empty",
					tc.name, c.ModelName, c.RouteSource)
			}
		}
	}
}

// TestStickyStrategyPinsAndFailoverIgnores proves strategy is authoritative:
// sticky pins the session even when no group bool is involved, failover keeps
// the configured order regardless of the key.
func TestStickyStrategyPinsAndFailoverIgnores(t *testing.T) {
	candidates := []RoutingCandidate{
		groupTestCandidate(1, 1, "a"), groupTestCandidate(2, 1, "b"), groupTestCandidate(3, 1, "c"),
	}
	pinned := firstMember(orderGroupCandidates(groupOrdering{
		candidates: candidates, weights: []int{1, 1, 1},
		strategy: GroupStrategySticky, stickyKey: "same-session", groupID: 9,
	}))
	for i := 0; i < 6; i++ {
		if got := firstMember(orderGroupCandidates(groupOrdering{
			candidates: candidates, weights: []int{1, 1, 1},
			strategy: GroupStrategySticky, stickyKey: "same-session", groupID: 9,
		})); got != pinned {
			t.Fatalf("sticky session moved from %s to %s", pinned, got)
		}
	}
	got := orderGroupCandidates(groupOrdering{
		candidates: candidates, weights: []int{1, 1, 1},
		strategy: GroupStrategyFailover, stickyKey: "same-session", groupID: 9,
	})
	for j, want := range []string{"a", "b", "c"} {
		if got[j].ModelName != want {
			t.Fatalf("failover with a session key reordered members at %d: got %s want %s", j, got[j].ModelName, want)
		}
	}
}

// TestMarkStickyFallback 覆盖粘性回退标记：命中成员让位后，同组的下一个成员被标为
// 回退；跨组（直连关联或另一个小组）不标记，回退链也继续向后传递。
func TestMarkStickyFallback(t *testing.T) {
	pinned := RoutingCandidate{GroupID: 3, ModelName: "pinned", RouteSource: models.RouteSourceSticky}
	sameGroupOther := RoutingCandidate{GroupID: 3, ModelName: "other"}

	if got := markStickyFallback(pinned, sameGroupOther); got.RouteSource != models.RouteSourceStickyFallback {
		t.Errorf("same-group takeover RouteSource = %q, want %q", got.RouteSource, models.RouteSourceStickyFallback)
	}

	// 回退成员再次让位时，标记继续传给组内下一个成员。
	fallback := markStickyFallback(pinned, sameGroupOther)
	if got := markStickyFallback(fallback, RoutingCandidate{GroupID: 3, ModelName: "third"}); got.RouteSource != models.RouteSourceStickyFallback {
		t.Errorf("chained takeover RouteSource = %q, want %q", got.RouteSource, models.RouteSourceStickyFallback)
	}

	// 跨组 / 直连不标记。
	if got := markStickyFallback(pinned, RoutingCandidate{GroupID: 9, ModelName: "other-group"}); got.RouteSource != "" {
		t.Errorf("cross-group RouteSource = %q, want empty", got.RouteSource)
	}
	if got := markStickyFallback(pinned, RoutingCandidate{ModelName: "direct"}); got.RouteSource != "" {
		t.Errorf("direct-rel RouteSource = %q, want empty", got.RouteSource)
	}

	// 非粘性成员让位不产生回退标记。
	if got := markStickyFallback(RoutingCandidate{GroupID: 3, ModelName: "plain"}, sameGroupOther); got.RouteSource != "" {
		t.Errorf("non-sticky failed member produced RouteSource %q, want empty", got.RouteSource)
	}
}