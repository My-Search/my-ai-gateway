// Package groups 维护「动态小组」的成员物化。
//
// 动态小组（model_groups.type='rule'）的成员不是手动添加的，而是按匹配表达式
// （pattern）从「启用的渠道模型 + 启用渠道」中自动得出。成员物化到
// model_group_members，语义与用户约定一致：
//   - 新命中的渠道模型 → 追加到成员表末尾（sort_order 递增），不打乱已有顺序；
//   - 因渠道刷新/禁用/删除而移出的成员 → 成员行删除；
//   - 之后重新命中时按新加入处理（再次追加到末尾）。
//
// 路由层不感知动态与手动的区别：动态小组就是成员由本包维护的普通小组。
// 同步在渠道模型发生任何变更（刷新/手动增删改/启用切换）、动态小组保存、
// 以及服务启动时触发。
package groups

import (
	"context"
	"log/slog"
	"strings"
	"sync"

	"github.com/my-search/my-ai-gateway/internal/rulefilter"
	"github.com/my-search/my-ai-gateway/internal/store"
)

var (
	mu      sync.Mutex
	verbose bool
)

// SetVerbose 打开每次同步的摘要日志（默认关闭，仅启动与测试时使用）。
func SetVerbose(v bool) {
	verbose = v
}

// SyncAll 把所有启用的动态小组的成员表与当前渠道模型对齐，返回变更的成员行数。
// 全量同步成本低（一次候选查询 + 每组一次表达式求值），可在任意渠道模型变更后调用。
func SyncAll(ctx context.Context, st *store.Store) (int, error) {
	mu.Lock()
	defer mu.Unlock()
	return syncAllLocked(ctx, st)
}

// SyncOne 只同步一个动态小组（保存小组 / 修改表达式后调用），返回变更的成员行数。
// 小组不存在、非动态类型或表达式为空时返回 0（不报错）。
func SyncOne(ctx context.Context, st *store.Store, groupID int64) (int, error) {
	mu.Lock()
	defer mu.Unlock()
	row, _ := st.QueryOne(ctx,
		"SELECT id, pattern FROM model_groups WHERE id = ? AND type = 'rule' AND enabled = 1", groupID)
	if row == nil {
		return 0, nil
	}
	return syncGroup(ctx, st, row.I64("id", 0), row.Str("pattern"))
}

func syncAllLocked(ctx context.Context, st *store.Store) (int, error) {
	groups, _ := st.Query(ctx,
		"SELECT id, pattern FROM model_groups WHERE type = 'rule' AND enabled = 1 AND TRIM(pattern) != ''")
	changed := 0
	for _, g := range groups {
		n, err := syncGroup(ctx, st, g.I64("id", 0), g.Str("pattern"))
		if err != nil {
			return changed, err
		}
		changed += n
	}
	if verbose && changed > 0 {
		slog.Info("动态小组成员同步完成", "changedMembers", changed)
	}
	return changed, nil
}

// syncGroup 对齐一个动态小组的成员表：删除不再命中的成员行，把新命中的渠道模型
// 按 channel_models.id 稳定顺序追加到现有成员之后。
func syncGroup(ctx context.Context, st *store.Store, groupID int64, pattern string) (int, error) {
	if strings.TrimSpace(pattern) == "" {
		return 0, nil
	}
	expr, err := rulefilter.Compile(pattern)
	if err != nil {
		// 表达式无效的小组不参与路由（成员表冻结），保存时已校验，这里只记录。
		slog.Warn("动态小组匹配表达式无效，跳过同步", "group_id", groupID, "error", err)
		return 0, nil
	}

	// 候选：启用的渠道模型 + 启用渠道，匹配文本与前端/路由一致（模型名 (渠道名)）。
	cands, _ := st.Query(ctx,
		`SELECT cm.id, cm.model_name, c.name AS channel_name
		   FROM channel_models cm
		   JOIN channels c ON c.id = cm.channel_id
		  WHERE cm.enabled = 1 AND c.enabled = 1
		  ORDER BY cm.id ASC`)
	matched := make(map[int64]bool, len(cands))
	var matchedIDs []int64
	for _, r := range cands {
		if expr.Match(rulefilter.MatchText(r.Str("model_name"), r.Str("channel_name"))) {
			id := r.I64("id", 0)
			if !matched[id] {
				matched[id] = true
				matchedIDs = append(matchedIDs, id)
			}
		}
	}

	members, _ := st.Query(ctx,
		"SELECT id, channel_model_id FROM model_group_members WHERE group_id = ?", groupID)
	existing := make(map[int64]bool, len(members))
	changed := 0
	for _, m := range members {
		cmID := m.I64("channel_model_id", 0)
		if matched[cmID] {
			existing[cmID] = true
			continue
		}
		// 不再命中（渠道模型删除/禁用、改名移出、渠道禁用）→ 移除成员行；
		// 重新命中时按新加入处理（追加到末尾）。
		if _, err := st.Exec(ctx, "DELETE FROM model_group_members WHERE id = ?", m.I64("id", 0)); err != nil {
			return changed, err
		}
		changed++
	}

	// 新命中的按候选顺序追加到末尾（sort_order 递增）。
	row, _ := st.QueryOne(ctx,
		"SELECT COALESCE(MAX(sort_order), -1) + 1 AS next FROM model_group_members WHERE group_id = ?", groupID)
	var nextSort int64
	if row != nil {
		nextSort = row.I64("next", 0)
	}
	for _, cmID := range matchedIDs {
		if existing[cmID] {
			continue
		}
		if _, err := st.Exec(ctx,
			`INSERT INTO model_group_members
			 (group_id, channel_model_id, weight, reasoning_effort, sort_order, enabled, created_at)
			 VALUES (?, ?, 1, NULL, ?, 1, ?)`,
			groupID, cmID, nextSort, store.NowApp()); err != nil {
			return changed, err
		}
		nextSort++
		changed++
	}
	return changed, nil
}
