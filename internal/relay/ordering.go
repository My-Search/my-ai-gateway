package relay

import (
	"crypto/sha256"
	"encoding/binary"
	"hash/fnv"
	"math/rand"
	"sort"
	"strconv"
	"sync"

	"github.com/my-search/my-ai-gateway/internal/models"
)

// 小组内部的路由：把一个候选集合按小组自己的策略排成一条尝试顺序。
//
// 与入口模型的 Balancer 不同，小组不是「从剩余候选里挑一个」，而是「把整组候选
// 排成有序队列」，让上层入口模型的候选循环可以无缝地把组内成员当作自己的一级候选
// 逐个尝试（失败即换下一个，熔断/媒体/上下文跳过同样生效）。这样小组只需要决定
// 顺序，不需要介入重试与熔断逻辑。

// groupOrdering 是小组顺序器的输入：候选集合 + 策略 + 粘性开关 + 会话 key。
type groupOrdering struct {
	// candidates 是组内已过滤（启用/渠道启用/有可用 Key）的候选。
	candidates []RoutingCandidate
	// weights 与 candidates 一一对应：组内路由权重（加权随机 / 加权轮询 / 粘性哈希环），<=0 视为 1。
	weights  []int
	strategy string
	sticky   bool
	// stickyKey 是会话前缀的哈希输入；为空表示无法计算（退化为非粘性）。
	stickyKey string
	// groupID 用于轮询计数器分桶，避免不同小组互相干扰。
	groupID int64
}

// orderGroupCandidates 返回按小组策略排好序的候选（新切片，不改动入参）。
//
// 粘性生效时，哈希命中的成员被排到组内首位；该成员的所有候选会被标记
// RouteSourceSticky，供请求日志标注「本次由粘性命中」。调用方（转发循环）在
// 该成员失败后会把它后面的同组候选标为 RouteSourceStickyFallback。
func orderGroupCandidates(o groupOrdering) []RoutingCandidate {
	members := groupCandidateBlocks(o)
	if len(members) <= 1 {
		return flattenCandidateBlocks(members)
	}

	sticky := o.sticky && o.stickyKey != ""

	var ordered [][]RoutingCandidate
	switch o.strategy {
	case GroupStrategyRoundRobin:
		if sticky {
			// 粘性优先于轮询：哈希命中的成员先上，其余成员按轮询顺序回退，
			// 既保住同一会话的 prompt cache，又不让回退总落到同一个成员。
			ordered = stickyRotateBlocks(members, o.groupID, o.stickyKey)
		} else {
			ordered = rotateBlocks(members, "rr", o.groupID)
		}
	case GroupStrategyRandom:
		if sticky {
			ordered = stickyBlocks(members, o.groupID, "rand", o.stickyKey)
		} else {
			ordered = weightedShuffleBlocks(members)
		}
	default: // failover
		if sticky {
			ordered = stickyBlocks(members, o.groupID, "fail", o.stickyKey)
		} else {
			ordered = members
		}
	}

	// 三个 sticky 分支都把命中成员排在第 0 块，因此「粘性命中」等价于首块。
	// 标记写在这份副本上，调用方拿到的展平结果即携带标记。
	if sticky && len(ordered) > 0 {
		for i := range ordered[0] {
			ordered[0][i].RouteSource = models.RouteSourceSticky
		}
	}
	return flattenCandidateBlocks(ordered)
}

// groupCandidateBlocks collapses the API-key-expanded candidate list into one
// block per configured group member. Weighting and sticky hashing apply to these
// blocks, never to the number of API keys a member happens to have.
func groupCandidateBlocks(o groupOrdering) [][]RoutingCandidate {
	var blocks [][]RoutingCandidate
	var weights []int
	blockByMember := map[int64]int{}
	for i, candidate := range o.candidates {
		memberID := candidate.GroupMemberID
		if memberID == 0 {
			// Unit callers without persisted member IDs still get one block per model.
			memberID = candidate.ChannelModelID
		}
		idx, exists := blockByMember[memberID]
		if !exists {
			idx = len(blocks)
			blockByMember[memberID] = idx
			blocks = append(blocks, nil)
			weight := 1
			if i < len(o.weights) {
				weight = normalizeWeight(o.weights[i])
			}
			weights = append(weights, weight)
		}
		blocks[idx] = append(blocks[idx], candidate)
	}
	// Write normalized weights to each block's representative candidate so the
	// block operations below need no parallel index bookkeeping.
	for i := range blocks {
		if len(blocks[i]) > 0 {
			blocks[i][0].Weight = weights[i]
		}
	}
	return blocks
}

func flattenCandidateBlocks(blocks [][]RoutingCandidate) []RoutingCandidate {
	var out []RoutingCandidate
	for _, block := range blocks {
		out = append(out, block...)
	}
	return out
}

func rotateBlocks(blocks [][]RoutingCandidate, bucket string, groupID int64) [][]RoutingCandidate {
	if len(blocks) <= 1 {
		return blocks
	}
	// 轮询同样按权重：起始位置在加权循环上推进，权重大的成员在同等请求数里排在
	// 队首的比例更高；等权重时循环退化为逐个成员，与旧的均匀轮转完全一致。
	weights := make([]int, len(blocks))
	for i, b := range blocks {
		weights[i] = normalizeWeight(b[0].Weight)
	}
	cycle := groupWeightedCycle(weights)
	idx := int(groupCounters.add(bucket+":"+strconv.FormatInt(groupID, 10)) % int64(len(cycle)))
	leader := cycle[idx]
	out := make([][]RoutingCandidate, 0, len(blocks))
	out = append(out, blocks[leader:]...)
	out = append(out, blocks[:leader]...)
	return out
}

// ---------------------------------------------------------------------------
// Weighted round-robin cycle
// ---------------------------------------------------------------------------

const (
	// cycleMaxLen 限制单个加权循环的长度。权重最高可配到 1000，成员多时
	// sum(weight) 会线性膨胀，而循环是在请求路径上按小组缓存的。超限时按比例
	// 缩小权重：比例略有偏差，但「重的更常排到队首」这一关系保留。
	cycleMaxLen = 20000
	// cycleCacheMaxEntries 限制缓存条数。循环只依赖权重序列，权重不变即长期可复用；
	// 上限兜底成员权重被频繁修改时的内存增长。
	cycleCacheMaxEntries = 256
)

var (
	cycleCacheMu sync.Mutex
	cycleCache   = map[string][]int{}
)

// groupWeightedCycle 返回权重序列对应的成员索引循环（按权重指纹缓存）。
func groupWeightedCycle(weights []int) []int {
	version := weightsVersion(weights)

	cycleCacheMu.Lock()
	if c, ok := cycleCache[version]; ok {
		cycleCacheMu.Unlock()
		return c
	}
	cycleCacheMu.Unlock()

	cycle := buildWeightedCycle(weights)

	cycleCacheMu.Lock()
	if len(cycleCache) >= cycleCacheMaxEntries {
		// 简单整体清空：循环只在权重变更时才需要重建。
		cycleCache = map[string][]int{}
	}
	cycleCache[version] = cycle
	cycleCacheMu.Unlock()
	return cycle
}

// buildWeightedCycle 用平滑加权轮询（SWRR，与 nginx 同款）把权重展开成成员索引
// 循环：成员 i 出现 w_i/gcd(w) 次，且位置尽量均匀。等权重时结果是 [0,1,...,n-1]，
// 即逐成员均匀轮转。
func buildWeightedCycle(weights []int) []int {
	if len(weights) == 0 {
		return nil
	}
	reduced := make([]int, len(weights))
	total := 0
	for i, w := range weights {
		reduced[i] = normalizeWeight(w)
		total += reduced[i]
	}

	if total > cycleMaxLen {
		scale := (total + cycleMaxLen - 1) / cycleMaxLen
		total = 0
		for i := range reduced {
			n := (reduced[i] + scale - 1) / scale
			if n < 1 {
				n = 1
			}
			reduced[i] = n
			total += n
		}
	}

	// 约去公因数：(2,2) 与 (1,1) 是同一个循环，不必把序列拉长一倍。
	g := 0
	for _, w := range reduced {
		g = gcdWeight(g, w)
	}
	if g > 1 {
		total = 0
		for i := range reduced {
			reduced[i] /= g
			total += reduced[i]
		}
	}

	cur := make([]int, len(reduced))
	cycle := make([]int, 0, total)
	for len(cycle) < total {
		best := 0
		for i, w := range reduced {
			cur[i] += w
			if cur[i] > cur[best] {
				best = i
			}
		}
		cur[best] -= total
		cycle = append(cycle, best)
	}
	return cycle
}

// weightsVersion fingerprints a weight sequence for the cycle cache.
func weightsVersion(weights []int) string {
	buf := make([]byte, 0, len(weights)*4)
	for _, w := range weights {
		buf = strconv.AppendInt(buf, int64(normalizeWeight(w)), 10)
		buf = append(buf, ',')
	}
	return string(buf)
}

func gcdWeight(a, b int) int {
	for b != 0 {
		a, b = b, a%b
	}
	return a
}

func weightedShuffleBlocks(blocks [][]RoutingCandidate) [][]RoutingCandidate {
	pool := make([]int, len(blocks))
	for i := range pool {
		pool[i] = i
	}
	out := make([][]RoutingCandidate, 0, len(blocks))
	for len(pool) > 0 {
		total := 0
		for _, idx := range pool {
			total += normalizeWeight(blocks[idx][0].Weight)
		}
		pick := rand.Intn(total)
		chosen := 0
		for i, idx := range pool {
			pick -= normalizeWeight(blocks[idx][0].Weight)
			if pick < 0 {
				chosen = i
				break
			}
		}
		memberIdx := pool[chosen]
		out = append(out, blocks[memberIdx])
		pool = append(pool[:chosen], pool[chosen+1:]...)
	}
	return out
}

func stickyBlocks(blocks [][]RoutingCandidate, groupID int64, bucket, stickyKey string) [][]RoutingCandidate {
	representatives := make([]RoutingCandidate, len(blocks))
	weights := make([]int, len(blocks))
	for i, block := range blocks {
		representatives[i] = block[0]
		weights[i] = normalizeWeight(block[0].Weight)
	}
	ring := groupRing(groupID, bucket, representatives, weights)
	pos := ring.locate(hashKey(stickyKey))
	out := make([][]RoutingCandidate, 0, len(blocks))
	out = append(out, blocks[pos])
	for i, block := range blocks {
		if i != pos {
			out = append(out, block)
		}
	}
	return out
}

// stickyRotateBlocks combines sticky affinity with round-robin fallback: the
// member owning the session hash leads, and the remaining members follow in the
// rotating order (so a broken pinned member fails over across the group instead
// of always landing on the same neighbour).
func stickyRotateBlocks(blocks [][]RoutingCandidate, groupID int64, stickyKey string) [][]RoutingCandidate {
	rotated := rotateBlocks(blocks, "rr", groupID)
	representatives := make([]RoutingCandidate, len(rotated))
	weights := make([]int, len(rotated))
	for i, block := range rotated {
		representatives[i] = block[0]
		weights[i] = normalizeWeight(block[0].Weight)
	}
	pos := groupRing(groupID, "rr", representatives, weights).locate(hashKey(stickyKey))
	out := make([][]RoutingCandidate, 0, len(rotated))
	out = append(out, rotated[pos])
	out = append(out, rotated[:pos]...)
	out = append(out, rotated[pos+1:]...)
	return out
}

// hashKey hashes one string to a ring position.
func hashKey(key string) uint64 {
	h := sha256.Sum256([]byte(key))
	return binary.BigEndian.Uint64(h[:8])
}

// ---------------------------------------------------------------------------
// Consistent hash ring cache
// ---------------------------------------------------------------------------

// ringVNodeScale 是权重到虚拟节点数的放大系数：权重 1 的成员占 1000 个虚拟节点，
// 占比 1/total。这个量级足以把哈希分配的偏差压到很小，而构建仍在毫秒级。
const ringVNodeScale = 1000

// ringMaxVNods 是单个环的虚拟节点总数上限。权重可以配置到 1000，若始终按
// weight*ringVNodeScale 线性展开，4 个满权重成员就要构建 400 万节点（实测约 3.4s、
// 100MB），而环是在请求路径上构建的。只有超过这个上限时才等比缩减，
// 因此常规配置（每成员约 1000 节点）的环与原先完全一致。
const ringMaxVNods = 20000

// ringCacheMaxEntries 限制缓存的小组环数量。环只依赖小组配置（成员+权重+策略），
// 因此配置不变时环可长期复用；上限用于兜底小组被频繁改动/删除时的内存增长。
const ringCacheMaxEntries = 256

// ringVNodeCounts 把虚拟节点按权重比例分配给各成员：常规权重下等于
// weight*ringVNodeScale（与旧的线性展开一致），总预算超过 ringMaxVNods 时等比缩减。
// 每个成员至少 1 个节点，保证权重极小的成员仍可被哈希命中（否则永远拿不到会话）。
func ringVNodeCounts(weights []int) []int {
	counts := make([]int, len(weights))
	if len(weights) == 0 {
		return counts
	}
	// 成员数已达上限时退化为每成员一个节点，否则「至少 1 个」与总量上限无法同时满足。
	if len(weights) >= ringMaxVNods {
		for i := range counts {
			counts[i] = 1
		}
		return counts
	}

	total := 0
	for _, w := range weights {
		total += normalizeWeight(w)
	}
	// 预算 = min(按比例线性展开的总量, 上限)。
	budget := int64(total) * int64(ringVNodeScale)
	if budget > ringMaxVNods {
		budget = ringMaxVNods
	}

	// 按权重占比分配，向下取整并记录余数；随后把剩余名额补给余数最大的成员。
	type share struct {
		idx int
		rem int64
	}
	shares := make([]share, len(weights))
	assigned := 0
	for i, w := range weights {
		nw := int64(normalizeWeight(w))
		n := int(budget * nw / int64(total))
		if n < 1 {
			n = 1
		}
		counts[i] = n
		assigned += n
		shares[i] = share{idx: i, rem: budget * nw % int64(total)}
	}
	for assigned < int(budget) {
		best, bestRem := -1, int64(-1)
		for _, s := range shares {
			if s.rem > bestRem {
				best, bestRem = s.idx, s.rem
			}
		}
		if best < 0 {
			break
		}
		counts[best]++
		assigned++
		shares[best].rem = -1
	}
	return counts
}

type hashRing struct {
	// points 是升序排列的虚拟节点哈希值，owners 与之平行：points[i] 归属于 owners[i]。
	points []uint64
	owners []int
}

// locate returns the index (into the candidate slice) owning the key.
func (r *hashRing) locate(h uint64) int {
	i := sort.Search(len(r.points), func(i int) bool { return r.points[i] >= h })
	if i == len(r.points) {
		i = 0 // wrap around the ring
	}
	return r.owners[i]
}

type ringCacheEntry struct {
	ring    *hashRing
	version string
}

var (
	ringCacheMu sync.Mutex
	ringCache   = map[string]ringCacheEntry{}
)

// groupRing returns the (cached) hash ring for one group. version fingerprints
// the member set + weights so a config change rebuilds the ring, while an
// unchanged config reuses it across requests.
func groupRing(groupID int64, bucket string, candidates []RoutingCandidate, weights []int) *hashRing {
	version := ringVersion(candidates, weights)
	key := bucket + ":" + strconv.FormatInt(groupID, 10)

	ringCacheMu.Lock()
	if e, ok := ringCache[key]; ok && e.version == version {
		ring := e.ring
		ringCacheMu.Unlock()
		return ring
	}
	ringCacheMu.Unlock()

	ring := buildRing(candidates, weights)

	ringCacheMu.Lock()
	if len(ringCache) >= ringCacheMaxEntries {
		// 简单整体清空：小组配置不常变，避免引入 LRU 复杂度。
		ringCache = map[string]ringCacheEntry{}
	}
	ringCache[key] = ringCacheEntry{ring: ring, version: version}
	ringCacheMu.Unlock()
	return ring
}

// ringVersion fingerprints the member identity + weight so a config change
// invalidates the cached ring.
func ringVersion(candidates []RoutingCandidate, weights []int) string {
	buf := make([]byte, 0, len(candidates)*12)
	for i, c := range candidates {
		buf = strconv.AppendInt(buf, ringOwnerID(c), 10)
		buf = append(buf, ':')
		buf = strconv.AppendInt(buf, int64(normalizeWeight(weights[i])), 10)
		buf = append(buf, ',')
	}
	return string(buf)
}

func ringOwnerID(c RoutingCandidate) int64 {
	if c.GroupMemberID != 0 {
		return c.GroupMemberID
	}
	return c.ChannelModelID
}

// buildRing places virtual nodes per candidate (proportional to weight, capped at
// ringMaxVNods in total), then sorts them so a lookup is a binary search.
// Candidate identity includes the API key so a member whose candidate got a
// different key does not silently share the slot.
func buildRing(candidates []RoutingCandidate, weights []int) *hashRing {
	vnodes := ringVNodeCounts(weights)
	total := 0
	for _, n := range vnodes {
		total += n
	}
	ring := &hashRing{points: make([]uint64, 0, total), owners: make([]int, 0, total)}
	for i, c := range candidates {
		for v := 0; v < vnodes[i]; v++ {
			seed := strconv.FormatInt(ringOwnerID(c), 10) + "#" + strconv.Itoa(v)
			ring.points = append(ring.points, hashKey(seed))
			ring.owners = append(ring.owners, i)
		}
	}
	sort.Sort(ring)
	return ring
}

func (r *hashRing) Len() int           { return len(r.points) }
func (r *hashRing) Less(i, j int) bool { return r.points[i] < r.points[j] }
func (r *hashRing) Swap(i, j int) {
	r.points[i], r.points[j] = r.points[j], r.points[i]
	r.owners[i], r.owners[j] = r.owners[j], r.owners[i]
}

func normalizeWeight(w int) int {
	if w <= 0 {
		return 1
	}
	return w
}

// StickyKey builds the conversation fingerprint used by sticky groups: the
// system prompt plus the first N messages, concatenated in order. Only the
// leading messages participate, so a long conversation keeps the same key as it
// grows (each new turn appends at the end, leaving the prefix untouched) while
// different conversations still hash apart.
//
// stickyPrefixMessages 是参与哈希的消息条数上限：太少区分度不足，太多会把
// 「同一会话的后续追加」也算进去导致漂移；前若干条足以稳定标识一段会话。
const stickyPrefixMessages = 3

func StickyKey(req *InternalRequest) string {
	if req == nil {
		return ""
	}
	// 快速路径：没有任何可哈希内容时不建环（退化为非粘性顺序）。
	if req.SystemPrompt == "" && len(req.Messages) == 0 {
		return ""
	}
	h := fnv.New64a()
	writeField := func(tag, s string) {
		_, _ = h.Write([]byte(tag))
		_, _ = h.Write([]byte(s))
		_, _ = h.Write([]byte{0})
	}
	writeField("system", req.SystemPrompt)
	limit := len(req.Messages)
	if limit > stickyPrefixMessages {
		limit = stickyPrefixMessages
	}
	for i := 0; i < limit; i++ {
		m := req.Messages[i]
		writeField("role", m.Role)
		switch {
		case m.Content != "":
			_, _ = h.Write([]byte(m.Content))
		case len(m.ContentParts) > 0:
			// 多模态：只取文本分片，图片等二进制内容不参与哈希（同一会话的
			// 图片 URL 可能每次生成不同，纳入会打散粘性）。
			for _, part := range m.ContentParts {
				if t, ok := part["text"].(string); ok {
					_, _ = h.Write([]byte(t))
				}
			}
		}
		_, _ = h.Write([]byte{0})
	}
	return strconv.FormatUint(h.Sum64(), 16)
}
