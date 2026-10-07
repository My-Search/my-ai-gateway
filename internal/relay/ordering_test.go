package relay

import (
	"testing"
	"time"
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
	for _, strategy := range []string{"failover", "random", "round_robin", ""} {
		for i := 0; i < 20; i++ {
			got := orderEntries(strategy, entries, 42)
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

// TestOrderEntriesFailoverIsStable proves failover keeps the configured order.
func TestOrderEntriesFailoverIsStable(t *testing.T) {
	entries := []routingEntry{
		{candidates: []RoutingCandidate{groupTestCandidate(1, 1, "first")}},
		{candidates: []RoutingCandidate{groupTestCandidate(2, 1, "second")}},
		{candidates: []RoutingCandidate{groupTestCandidate(3, 1, "third")}},
	}
	for i := 0; i < 5; i++ {
		got := orderEntries("failover", entries, 1)
		for j, want := range []string{"first", "second", "third"} {
			if got[j].candidates[0].ModelName != want {
				t.Fatalf("failover order changed at position %d: got %s want %s", j, got[j].candidates[0].ModelName, want)
			}
		}
	}
}

// TestOrderEntriesRoundRobinRotates proves the entry-level rotation cycles the
// starting entry, so consecutive requests do not always begin at the same place.
func TestOrderEntriesRoundRobinRotates(t *testing.T) {
	newEntries := func() []routingEntry {
		return []routingEntry{
			{candidates: []RoutingCandidate{groupTestCandidate(1, 1, "a")}},
			{candidates: []RoutingCandidate{groupTestCandidate(2, 1, "b")}},
			{candidates: []RoutingCandidate{groupTestCandidate(3, 1, "c")}},
		}
	}
	firsts := map[string]bool{}
	for i := 0; i < 6; i++ {
		firsts[orderEntries("round_robin", newEntries(), 99)[0].candidates[0].ModelName] = true
	}
	if len(firsts) != 3 {
		t.Errorf("round robin started at %d distinct entries over 6 requests, want 3 (got %v)", len(firsts), firsts)
	}
}

// TestWeightedShufflePrefersHeavyMember proves weight steers ordering: over many
// draws the heavy member must be first far more often than a light one.
func TestWeightedShufflePrefersHeavyMember(t *testing.T) {
	blocks := groupTestBlocks([]string{"heavy", "light-a", "light-b"}, []int{50, 1, 1})
	heavyFirst := 0
	const rounds = 300
	for i := 0; i < rounds; i++ {
		if weightedShuffleBlocks(blocks)[0][0].ModelName == "heavy" {
			heavyFirst++
		}
	}
	// Probability of "heavy" first is 50/52 ≈ 96%.
	if heavyFirst < rounds*80/100 {
		t.Errorf("heavy member led only %d/%d draws; weights are not being applied", heavyFirst, rounds)
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
	// requests; each member owns half of the weighted draw.
	aWins := 0
	const rounds = 1000
	for i := 0; i < rounds; i++ {
		ordered := weightedShuffleBlocks(blocks)
		if ordered[0][0].GroupMemberID == 101 {
			aWins++
		}
	}
	if aWins < rounds*40/100 || aWins > rounds*60/100 {
		t.Errorf("member with 4 API keys won %d/%d draws with equal member weights; API key count must not multiply weight", aWins, rounds)
	}

	// All keys of the chosen member remain contiguous for failover within member.
	ordered := orderGroupCandidates(groupOrdering{candidates: candidates, weights: weights, strategy: GroupStrategyFailover})
	if ordered[0].GroupMemberID != 101 || ordered[3].GroupMemberID != 101 || ordered[4].GroupMemberID != 102 {
		t.Errorf("member API keys were not kept as a contiguous failover block: %+v", ordered)
	}
}

// TestWeightedShuffleKeepsEveryCandidate proves reordering never drops or
// duplicates a candidate (the routing loop relies on the pool being exact).
func TestWeightedShuffleKeepsEveryCandidate(t *testing.T) {
	blocks := groupTestBlocks([]string{"a", "b", "c"}, []int{3, 1, 2})
	for i := 0; i < 50; i++ {
		got := flattenCandidateBlocks(weightedShuffleBlocks(blocks))
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
}

// TestZeroAndNegativeWeightsTreatedAsOne proves a missing/zero weight does not
// crash the weighted picker or starve a member (divide-by-zero / zero-total).
func TestZeroAndNegativeWeightsTreatedAsOne(t *testing.T) {
	blocks := groupTestBlocks([]string{"zero", "negative", "normal"}, []int{0, -5, 1})
	for i := 0; i < 30; i++ {
		got := flattenCandidateBlocks(weightedShuffleBlocks(blocks))
		if len(got) != 3 {
			t.Fatalf("got %d candidates, want 3 for weights %v", len(got), []int{0, -5, 1})
		}
	}
	// All-zero weights must still produce a full, non-crashing ordering.
	zeroBlocks := groupTestBlocks([]string{"a", "b", "c"}, []int{0, 0, 0})
	if got := flattenCandidateBlocks(weightedShuffleBlocks(zeroBlocks)); len(got) != 3 {
		t.Fatalf("all-zero weights: got %d candidates, want 3", len(got))
	}
}

// TestRoundRobinRotationKeepsQueueIntact proves the rotation used for
// round_robin preserves the cyclic order (no drops, no duplicates).
func TestRoundRobinRotationKeepsQueueIntact(t *testing.T) {
	blocks := groupTestBlocks([]string{"a", "b", "c"}, []int{1, 1, 1})
	for i := 0; i < 7; i++ {
		got := flattenCandidateBlocks(rotateBlocks(blocks, "test", 5))
		if len(got) != 3 {
			t.Fatalf("got %d candidates, want 3", len(got))
		}
		// Consecutive pairs must be cyclically adjacent in the source slice.
		for j := range got {
			next := got[(j+1)%len(got)]
			if got[j].ModelName == next.ModelName {
				t.Fatalf("duplicate %s in rotation %v", got[j].ModelName, got)
			}
		}
	}
}

// TestRotateBlocksIsWeighted proves round_robin honors weights: the starting
// member cycles over a weighted schedule, so a heavy member leads proportionally
// more requests while every member still gets a turn.
func TestRotateBlocksIsWeighted(t *testing.T) {
	blocks := groupTestBlocks([]string{"heavy", "light"}, []int{3, 1})
	firsts := map[string]int{}
	const rounds = 400
	for i := 0; i < rounds; i++ {
		firsts[flattenCandidateBlocks(rotateBlocks(blocks, "weighted-rr", 77))[0].ModelName]++
	}
	// 3:1 schedule — heavy must lead about 3/4 of the requests.
	if firsts["heavy"] != rounds*3/4 || firsts["light"] != rounds/4 {
		t.Errorf("weighted round robin led %v over %d requests, want heavy=300 light=100", firsts, rounds)
	}
}

// TestRotateBlocksEqualWeightsStaysUniform proves the weighting does not distort
// the classic case: equal weights must keep the plain per-member rotation, with
// each member leading equally.
func TestRotateBlocksEqualWeightsStaysUniform(t *testing.T) {
	blocks := groupTestBlocks([]string{"a", "b", "c"}, []int{1, 1, 1})
	firsts := map[string]int{}
	const rounds = 300
	for i := 0; i < rounds; i++ {
		firsts[flattenCandidateBlocks(rotateBlocks(blocks, "even-rr", 78))[0].ModelName]++
	}
	for _, name := range []string{"a", "b", "c"} {
		if firsts[name] != rounds/3 {
			t.Errorf("member %s led %d/%d requests, want %d (equal weights must stay uniform)", name, firsts[name], rounds, rounds/3)
		}
	}
}

// TestRotateBlocksKeepsQueueIntact proves weighted rotation still returns every
// member exactly once, so the failover tail behind the leader stays complete.
func TestRotateBlocksKeepsQueueIntact(t *testing.T) {
	blocks := groupTestBlocks([]string{"a", "b", "c"}, []int{5, 2, 1})
	for i := 0; i < 20; i++ {
		got := flattenCandidateBlocks(rotateBlocks(blocks, "intact-rr", 79))
		if len(got) != 3 {
			t.Fatalf("got %d candidates, want 3", len(got))
		}
		seen := map[string]bool{}
		for _, c := range got {
			if seen[c.ModelName] {
				t.Fatalf("duplicate %s in rotation %v", c.ModelName, got)
			}
			seen[c.ModelName] = true
		}
	}
}

// TestBuildWeightedCycleShape pins the schedule itself: member i appears
// weight_i/gcd times, and equal weights reproduce the identity rotation.
func TestBuildWeightedCycleShape(t *testing.T) {
	cases := []struct {
		weights []int
		want    []int
	}{
		{[]int{1, 1, 1}, []int{0, 1, 2}},
		{[]int{5, 5}, []int{0, 1}},        // common factor reduced
		{[]int{2, 1}, []int{0, 1, 0}},     // heavy leads 2 of 3
		{[]int{0, -1, 1}, []int{0, 1, 2}}, // <=0 counts as 1
	}
	for _, tc := range cases {
		got := buildWeightedCycle(tc.weights)
		if len(got) != len(tc.want) {
			t.Fatalf("buildWeightedCycle(%v) = %v, want %v", tc.weights, got, tc.want)
		}
		counts := map[int]int{}
		for _, v := range got {
			counts[v]++
		}
		wantCounts := map[int]int{}
		for _, v := range tc.want {
			wantCounts[v]++
		}
		for k, v := range wantCounts {
			if counts[k] != v {
				t.Errorf("buildWeightedCycle(%v) = %v, member %d appears %d times, want %d",
					tc.weights, got, k, counts[k], v)
			}
		}
	}
}

// TestBuildWeightedCycleBounded proves the schedule cannot blow up with the
// maximum configurable weights: the cycle is capped while still letting every
// member appear.
func TestBuildWeightedCycleBounded(t *testing.T) {
	weights := make([]int, 40)
	for i := range weights {
		weights[i] = 1000 // 40 * 1000 = 40000, twice the cap
	}
	cycle := buildWeightedCycle(weights)
	if len(cycle) > cycleMaxLen {
		t.Errorf("cycle length = %d, want at most %d", len(cycle), cycleMaxLen)
	}
	seen := map[int]bool{}
	for _, v := range cycle {
		seen[v] = true
	}
	if len(seen) != len(weights) {
		t.Errorf("cycle covers %d of %d members; a member would never lead", len(seen), len(weights))
	}
	// Equal weights must still split the schedule evenly after scaling.
	counts := map[int]int{}
	for _, v := range cycle {
		counts[v]++
	}
	first := counts[0]
	for i, n := range counts {
		if n != first {
			t.Errorf("member %d appears %d times, want %d (equal weights)", i, n, first)
		}
	}
}

// TestStickyBlocksPinKey proves the ring maps one key to a stable member, and
// that the pinned member always leads the queue.
func TestStickyBlocksPinKey(t *testing.T) {
	blocks := groupTestBlocks([]string{"a", "b", "c"}, []int{1, 1, 1})
	first := stickyBlocks(blocks, 11, "t", "session-xyz")[0][0].ModelName
	for i := 0; i < 20; i++ {
		got := flattenCandidateBlocks(stickyBlocks(blocks, 11, "t", "session-xyz"))
		if got[0].ModelName != first {
			t.Fatalf("sticky key moved between members: %s -> %s", first, got[0].ModelName)
		}
		if len(got) != 3 {
			t.Fatalf("sticky order returned %d candidates, want 3 (failover tail must remain)", len(got))
		}
	}
}

// TestStickyAppliesUnderEveryGroupStrategy proves sticky=1 is honored no matter
// which strategy the group uses — round_robin must not silently drop affinity.
func TestStickyAppliesUnderEveryGroupStrategy(t *testing.T) {
	candidates := []RoutingCandidate{
		groupTestCandidate(1, 1, "a"), groupTestCandidate(2, 1, "b"), groupTestCandidate(3, 1, "c"),
	}
	for _, strategy := range []string{GroupStrategyFailover, GroupStrategyRandom, GroupStrategyRoundRobin} {
		firsts := map[string]int{}
		for i := 0; i < 12; i++ {
			got := orderGroupCandidates(groupOrdering{
				candidates: candidates, weights: []int{1, 1, 1},
				strategy: strategy, sticky: true, stickyKey: "same-session", groupID: 42,
			})
			firsts[firstMember(got)]++
		}
		if len(firsts) != 1 {
			t.Errorf("%s: sticky session spread across %d members (%v); sticky must pin one member",
				strategy, len(firsts), firsts)
		}
	}
}

// TestStickyOffStillRotatesUnderRoundRobin proves disabling sticky keeps the
// round-robin behaviour (the fix must not make round_robin always pick one member).
func TestStickyOffStillRotatesUnderRoundRobin(t *testing.T) {
	candidates := []RoutingCandidate{
		groupTestCandidate(1, 1, "a"), groupTestCandidate(2, 1, "b"), groupTestCandidate(3, 1, "c"),
	}
	firsts := map[string]bool{}
	for i := 0; i < 6; i++ {
		got := orderGroupCandidates(groupOrdering{
			candidates: candidates, weights: []int{1, 1, 1},
			strategy: GroupStrategyRoundRobin, sticky: false, groupID: 43,
		})
		firsts[firstMember(got)] = true
	}
	if len(firsts) != 3 {
		t.Errorf("round_robin without sticky started at %d members over 6 requests, want 3 (%v)", len(firsts), firsts)
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

// TestNormalizeGroupStrategyFallsBackToRandom documents the default for unknown
// or blank strategies (a corrupt value must not disable routing).
func TestNormalizeGroupStrategyFallsBackToRandom(t *testing.T) {
	cases := map[string]string{
		"":             GroupStrategyRandom,
		"  ":           GroupStrategyRandom,
		"bogus":        GroupStrategyRandom,
		"failover":     GroupStrategyFailover,
		"random":       GroupStrategyRandom,
		"round_robin":  GroupStrategyRoundRobin,
		" round_robin": GroupStrategyRoundRobin,
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
