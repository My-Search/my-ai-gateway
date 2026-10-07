package relay

import (
	"math/rand"
	"sort"
	"strconv"
	"sync"
	"sync/atomic"
)

// 入口模型策略作用于「路由条目」而不是「候选」。
//
// 一个条目 = 一条直连关联（对应若干 API Key 候选）或一个模型小组。小组必须在队列里
// 保持连续，组内顺序由小组自己的策略决定，否则入口模型的随机/轮询会把同一小组的成员
// 打散，小组就退化成了普通的候选池，子路由语义丢失。
//
// Java 的 LoadBalancerFactory 语义在这里保留（failover=按序、random=打乱、
// round_robin=轮换），只是把「每次挑一个候选」改成「一次排出整个条目顺序」，
// 由路由循环按序尝试，失败即换下一条，与原先的逐个挑选等价。

// orderEntries 按入口模型策略排出条目的尝试顺序（返回新切片）。
func orderEntries(strategy string, entries []routingEntry, modelID int64) []routingEntry {
	if len(entries) <= 1 {
		return append([]routingEntry(nil), entries...)
	}
	switch strategy {
	case "random":
		out := append([]routingEntry(nil), entries...)
		rand.Shuffle(len(out), func(i, j int) { out[i], out[j] = out[j], out[i] })
		return out
	case "round_robin":
		return rotateEntries(entries, modelID)
	default: // failover（含未配置）
		return append([]routingEntry(nil), entries...)
	}
}

// rotateEntries rotates the entry queue by a per-entry-model counter, so
// consecutive requests start from different entries while the queue order is
// still walked front to back on retries.
func rotateEntries(entries []routingEntry, modelID int64) []routingEntry {
	n := len(entries)
	idx := int(entryCounters.add("entry:"+strconv.FormatInt(modelID, 10)) % int64(n))
	out := make([]routingEntry, 0, n)
	out = append(out, entries[idx:]...)
	out = append(out, entries[:idx]...)
	return out
}

// counterStore keeps process-wide rotation counters alive across requests,
// exactly like the Java Spring singletons (LoadBalancerFactory / RoundRobinBalancer).
type counterStore struct {
	mu       sync.Mutex
	counters map[string]*atomic.Int64
}

// countersMaxEntries bounds the counter map so a long-lived process with many
// entry models / groups cannot grow it without limit. Clearing only resets the
// rotation phase, which is harmless.
const countersMaxEntries = 512

var globalCounters = counterStore{counters: map[string]*atomic.Int64{}}

func (c *counterStore) add(key string) int64 {
	c.mu.Lock()
	ctr, ok := c.counters[key]
	if !ok {
		if len(c.counters) >= countersMaxEntries {
			c.counters = map[string]*atomic.Int64{}
		}
		ctr = &atomic.Int64{}
		c.counters[key] = ctr
	}
	c.mu.Unlock()
	return ctr.Add(1)
}

// entryCounters and groupCounters share one store so the bound covers both.
var entryCounters = &globalCounters

// groupCounters holds the per-group round-robin counters, keyed by
// "bucket:groupID" so a strategy change does not inherit the old rotation phase.
var groupCounters = &globalCounters

// sortEntriesByOrder 是 failover 顺序的基准比较：先 sort_order，同序号时直连
// 关联排在小组之前（保持“先直连、后分组”的稳定直觉顺序）。
func sortEntriesByOrder(entries []routingEntry) {
	sort.SliceStable(entries, func(i, j int) bool {
		if entries[i].sortOrder != entries[j].sortOrder {
			return entries[i].sortOrder < entries[j].sortOrder
		}
		return !entries[i].isGroup && entries[j].isGroup
	})
}
