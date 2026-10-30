package relay

import (
	"sort"
	"strings"
)

// 入口模型策略作用于「路由条目」而不是「候选」。
//
// 一个条目 = 一条直连关联（对应若干 API Key 候选）或一个模型小组。小组必须在队列里
// 保持连续，组内顺序由小组自己的策略决定，否则入口模型的排序会把同一小组的成员
// 打散，小组就退化成了普通的候选池，子路由语义丢失。
//
// 策略只有两种：
//   - failover（顺序）：按配置顺序逐一尝试；
//   - sticky（粘性）：按条目权重做一致性哈希，把同一会话固定到同一条目（提升上游
//     prompt cache 命中），命中条目失败后仍按配置顺序回退。
//
// 历史版本的 random / round_robin 都曾依赖权重分布，统一按 sticky 处理
// （见 normalizeModelStrategy 与 v1.48.0 数据库迁移）。

// normalizeModelStrategy 把库中的策略值归一为两种：failover / sticky。
// 空值与未知值回退为顺序；random / round_robin 按粘性处理。
func normalizeModelStrategy(s string) string {
	switch strings.TrimSpace(s) {
	case ModelStrategySticky, "random", "round_robin":
		return ModelStrategySticky
	default:
		return ModelStrategyFailover
	}
}

// orderEntries 按入口模型策略排出条目的尝试顺序（返回新切片）。
// sticky 且能算出会话指纹时，哈希命中的条目排到首位，其余条目保持配置
// 顺序作为回退；其余情况（failover / 无指纹）保持原序。
func orderEntries(strategy string, entries []routingEntry, modelID int64, stickyKey string) []routingEntry {
	out := append([]routingEntry(nil), entries...)
	if len(out) <= 1 || normalizeModelStrategy(strategy) != ModelStrategySticky || stickyKey == "" {
		return out
	}
	pos := entryRing(modelID, out).locate(hashKey(stickyKey))
	ordered := make([]routingEntry, 0, len(out))
	ordered = append(ordered, out[pos])
	ordered = append(ordered, out[:pos]...)
	ordered = append(ordered, out[pos+1:]...)
	return ordered
}

// entryRing builds the (cached) consistent-hash ring over routing entries.
// 直连关联用渠道模型 ID 作为环身份、关联权重作为份额；小组整体作为一个
// 单位参与哈希（权重固定为 1，组内权重由小组策略自己处理）。小组身份取
// groupID 的负数，避免与渠道模型 ID 在同一环上碰撞。
func entryRing(modelID int64, entries []routingEntry) *hashRing {
	reps := make([]RoutingCandidate, len(entries))
	weights := make([]int, len(entries))
	for i, e := range entries {
		if e.isGroup {
			reps[i] = RoutingCandidate{GroupMemberID: -e.groupID}
			weights[i] = 1
			continue
		}
		reps[i] = e.candidates[0]
		weights[i] = normalizeWeight(e.candidates[0].Weight)
	}
	return groupRing(modelID, "entry", reps, weights)
}

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
