// 模型小组（Model Group）管理接口。
//
// 小组是入口模型与渠道模型之间的一层子路由：能力相近、分布在不同渠道的模型放进
// 同一个小组，小组用自己的策略（随机加权 / 轮询 / 故障转移）在组内选路，从而把
// 同一能力的请求分散到多个渠道。入口模型通过 model_group_rels 关联小组，
// 与直连的 model_channel_rels 共用同一个 sort_order 序号空间。
package server

import (
	"context"
	"fmt"
	"strings"
	"time"

	"github.com/gin-gonic/gin"
	"github.com/my-search/my-ai-gateway/internal/groups"
	"github.com/my-search/my-ai-gateway/internal/httpx"
	"github.com/my-search/my-ai-gateway/internal/jtime"
	"github.com/my-search/my-ai-gateway/internal/models"
	"github.com/my-search/my-ai-gateway/internal/rulefilter"
	"github.com/my-search/my-ai-gateway/internal/store"
)

// 小组策略输入归一化见 normalizeGroupStrategyInput：白名单校验内联其中，
// 历史 random / round_robin 输入按 sticky 处理。

func registerModelGroupRoutes(g *gin.RouterGroup, d Deps) {
	// GET /admin/api/model-groups
	g.GET("/model-groups", func(c *gin.Context) {
		ctx := c.Request.Context()
		rows, err := d.Store.Query(ctx, "SELECT * FROM model_groups ORDER BY created_at ASC")
		if err != nil {
			httpx.OK(c, failureEnvelope(err.Error()))
			return
		}
		out := make([]models.ModelGroup, 0, len(rows))
		for _, r := range rows {
			grp := store.RowToModelGroup(r)
			// 摘要统一走 groupMemberSummaryOf，与入口模型关联页的小组行同口径；
			// 小组数量级小，逐组聚合可接受（详情页已按同模式取数）。
			summary := groupMemberSummaryOf(ctx, d.Store, grp.ID)
			grp.MemberCount = models.Int(summary.total)
			grp.AvailableCount = models.Int(summary.routable)
			grp.BrokenCount = models.Int(summary.brokenCount)
			grp.TTFTMs = summary.ttftMs
			grp.SampleCount = summary.sampleCount
			grp.OutputSpeed = summary.outputSpeed
			out = append(out, grp)
		}
		httpx.OK(c, out)
	})

	// POST /admin/api/model-groups
	g.POST("/model-groups", func(c *gin.Context) {
		ctx := c.Request.Context()
		var body struct {
			Name        string  `json:"name"`
			Description *string `json:"description"`
			Strategy    *string `json:"strategy"`
			Sticky      *int    `json:"sticky"`
			// Type/Pattern：动态小组（type='rule'）的成员由匹配表达式自动得出。
			Type    *string `json:"type"`
			Pattern *string `json:"pattern"`
		}
		if err := c.ShouldBindJSON(&body); err != nil {
			httpx.OK(c, failureEnvelope("请求参数错误"))
			return
		}
		name := strings.TrimSpace(body.Name)
		if name == "" {
			httpx.OK(c, failureEnvelope("小组名称不能为空"))
			return
		}
		strategy := normalizeGroupStrategyInput(body.Strategy)
		if strategy == "" {
			httpx.OK(c, failureEnvelope("路由方式无效，必须为 failover / sticky"))
			return
		}
		groupType := "manual"
		if body.Type != nil && strings.TrimSpace(*body.Type) == "rule" {
			groupType = "rule"
		}
		pattern := ""
		// 手动小组也接受 pattern（作为休眠值存储，改回动态小组时恢复生效）。
		if body.Pattern != nil {
			pattern = strings.TrimSpace(*body.Pattern)
			if pattern != "" {
				if err := rulefilter.Validate(pattern); err != nil {
					httpx.OK(c, failureEnvelope(err.Error()))
					return
				}
			}
		}
		if groupType == "rule" && pattern == "" {
			httpx.OK(c, failureEnvelope("动态小组必须提供匹配规则"))
			return
		}
		if dup, _ := d.Store.QueryOne(ctx, "SELECT id FROM model_groups WHERE name = ?", name); dup != nil {
			httpx.OK(c, failureEnvelope("小组名称「"+name+"」已存在"))
			return
		}
		now := jtime.FormatApp(time.Now().UTC())
		id, err := d.Store.Insert(ctx,
			`INSERT INTO model_groups (name, strategy, sticky, type, pattern, enabled, created_at, updated_at)
			 VALUES (?, ?, ?, ?, ?, 1, ?, ?)`,
			name, strategy, groupStickyDefault(body.Sticky), groupType, pattern, now, now)
		if err != nil {
			httpx.OK(c, failureEnvelope(err.Error()))
			return
		}
		// 动态小组创建后立即物化命中成员（新命中的按渠道模型 ID 顺序追加）。
		if groupType == "rule" {
			if _, err := groups.SyncOne(ctx, d.Store, id); err != nil {
				httpx.OK(c, failureEnvelope("同步动态小组成员失败: "+err.Error()))
				return
			}
		}
		httpx.OK(c, httpx.NewOrderedMap().Set("success", true).Set("id", id))
	})

	// GET /admin/api/model-groups/{id} — 小组详情 + 成员 + 可添加的渠道模型
	g.GET("/model-groups/:id", func(c *gin.Context) {
		ctx := c.Request.Context()
		id, ok := pathID(c, "id")
		if !ok {
			notFound(c, "小组不存在")
			return
		}
		row, err := d.Store.QueryOne(ctx, "SELECT * FROM model_groups WHERE id = ?", id)
		if err != nil {
			notFound(c, "小组不存在")
			return
		}
		members := loadGroupMembers(ctx, d.Store, id)

		// 可添加的渠道模型：排除已在组成员中的（小组内不允许重复渠道模型）。
		availRows, _ := d.Store.Query(ctx,
			`SELECT cm.*, c.name as channel_name, c.channel_type FROM channel_models cm
			  JOIN channels c ON c.id = cm.channel_id
			 WHERE cm.enabled=1 AND c.enabled=1 ORDER BY cm.model_name ASC`)
		existing := make(map[int64]bool, len(members))
		for _, m := range members {
			if m.ChannelModelID != nil {
				existing[*m.ChannelModelID] = true
			}
		}
		availModels := make([]models.ChannelModel, 0, len(availRows))
		for _, r := range availRows {
			if existing[r.I64("id", 0)] {
				continue
			}
			cm := store.RowToChannelModel(r)
			cm.ChannelName = r.StrPtr("channel_name")
			cm.ChannelType = r.StrPtr("channel_type")
			availModels = append(availModels, cm)
		}

		// 引用该小组的入口模型（用于展示影响面，删除前提示）
		usedBy := loadGroupUsage(ctx, d.Store, id)

		httpx.OK(c, httpx.NewOrderedMap().
			Set("group", store.RowToModelGroup(row)).
			Set("members", members).
			Set("availableModels", availModels).
			Set("usedByModels", usedBy))
	})

	// PUT /admin/api/model-groups/{id}
	g.PUT("/model-groups/:id", func(c *gin.Context) {
		ctx := c.Request.Context()
		id, ok := pathID(c, "id")
		if !ok {
			httpx.OK(c, failureEnvelope("小组不存在"))
			return
		}
		var body map[string]any
		if err := c.ShouldBindJSON(&body); err != nil {
			httpx.OK(c, failureEnvelope("请求参数错误"))
			return
		}
		cur, _ := d.Store.QueryOne(ctx, "SELECT * FROM model_groups WHERE id = ?", id)
		if cur == nil {
			httpx.OK(c, failureEnvelope("小组不存在"))
			return
		}
		sets := map[string]any{}
		if v, exists := body["name"]; exists {
			name, _ := v.(string)
			name = strings.TrimSpace(name)
			if name == "" {
				httpx.OK(c, failureEnvelope("小组名称不能为空"))
				return
			}
			if dup, _ := d.Store.QueryOne(ctx, "SELECT id FROM model_groups WHERE name = ? AND id != ?", name, id); dup != nil {
				httpx.OK(c, failureEnvelope("小组名称「"+name+"」已存在"))
				return
			}
			sets["name"] = name
		}
		if v, exists := body["strategy"]; exists {
			strategy := normalizeGroupStrategyInput(anyStrPtr(v))
			if strategy == "" {
				httpx.OK(c, failureEnvelope("路由方式无效，必须为 failover / sticky"))
				return
			}
			sets["strategy"] = strategy
		}
		if v, exists := body["sticky"]; exists {
			sets["sticky"] = toIntAny(v)
		}
		if v, exists := body["enabled"]; exists {
			sets["enabled"] = toIntAny(v)
		}
		// 类型与匹配规则：允许手动 ↔ 动态互转。转为动态：表达式取请求携带的值，
		// 否则恢复库中休眠保留的表达式，保存后立即物化成员（命中的渠道模型加入，
		// 未命中的现有成员移除）。转为手动：成员原地保留、转为可手动维护，
		// 表达式作为休眠值保留，改回动态小组时自动恢复生效。
		curType := strings.TrimSpace(cur.Str("type"))
		newType := curType
		if v, exists := body["type"]; exists {
			t, _ := v.(string)
			newType = strings.TrimSpace(t)
			if newType != "manual" && newType != "rule" {
				httpx.OK(c, failureEnvelope("小组类型无效，必须为 manual / rule"))
				return
			}
		}
		pattern := strings.TrimSpace(cur.Str("pattern"))
		if v, exists := body["pattern"]; exists {
			p, _ := v.(string)
			pattern = strings.TrimSpace(p)
			if pattern != "" {
				if err := rulefilter.Validate(pattern); err != nil {
					httpx.OK(c, failureEnvelope(err.Error()))
					return
				}
			}
		}
		if newType == "rule" {
			if pattern == "" {
				httpx.OK(c, failureEnvelope("动态小组需要匹配规则"))
				return
			}
			sets["pattern"] = pattern
		} else if _, exists := body["pattern"]; exists {
			// 手动小组的表达式是休眠值：仅存储，不参与路由与成员同步。
			sets["pattern"] = pattern
		}
		if newType != curType {
			sets["type"] = newType
		}
		if len(sets) == 0 {
			httpx.OK(c, successEnvelope())
			return
		}
		sets["updated_at"] = jtime.FormatApp(time.Now().UTC())
		q, args := store.BuildUpdate("model_groups", sets, "id = ?", id)
		if _, err := d.Store.Exec(ctx, q, args...); err != nil {
			httpx.OK(c, failureEnvelope(err.Error()))
			return
		}
		// 动态小组（含刚转为动态的）：表达式变化会触发成员重新同步（新命中追加到
		// 末尾、不再命中的移除，重新出现时按新成员处理）。
		if newType == "rule" {
			if _, ok := sets["pattern"]; ok {
				if _, err := groups.SyncOne(ctx, d.Store, id); err != nil {
					httpx.OK(c, failureEnvelope("同步动态小组成员失败: "+err.Error()))
					return
				}
			}
		}
		httpx.OK(c, successEnvelope())
	})

	// DELETE /admin/api/model-groups/{id}
	g.DELETE("/model-groups/:id", func(c *gin.Context) {
		ctx := c.Request.Context()
		id, ok := pathID(c, "id")
		if !ok {
			httpx.OK(c, failureEnvelope("小组不存在"))
			return
		}
		row, _ := d.Store.QueryOne(ctx, "SELECT name FROM model_groups WHERE id = ?", id)
		if row == nil {
			httpx.OK(c, failureEnvelope("小组不存在"))
			return
		}
		// 入口模型仍引用该小组时拒绝删除：静默解除关联会让入口模型悄悄少掉一条候选，
		// 与入口模型「被继承时不可删除」的处理保持一致，让用户先改关联。
		usedBy := loadGroupUsage(ctx, d.Store, id)
		if len(usedBy) > 0 {
			names := make([]string, 0, len(usedBy))
			for _, u := range usedBy {
				names = append(names, u.ModelName)
			}
			httpx.OK(c, failureEnvelope(fmt.Sprintf("小组「%s」正被以下入口模型关联，无法删除：%s",
				row.Str("name"), strings.Join(names, "、"))))
			return
		}
		if _, err := d.Store.Exec(ctx, "DELETE FROM model_group_members WHERE group_id = ?", id); err != nil {
			httpx.OK(c, failureEnvelope(err.Error()))
			return
		}
		if _, err := d.Store.Exec(ctx, "DELETE FROM model_groups WHERE id = ?", id); err != nil {
			httpx.OK(c, failureEnvelope(err.Error()))
			return
		}
		httpx.OK(c, successEnvelope())
	})

	// POST /admin/api/model-groups/{id}/members — 批量添加成员
	g.POST("/model-groups/:id/members", func(c *gin.Context) {
		ctx := c.Request.Context()
		groupID, ok := pathID(c, "id")
		if !ok {
			httpx.OK(c, failureEnvelope("小组不存在"))
			return
		}
		if d.Store.QueryOneOrZero(ctx, "SELECT id FROM model_groups WHERE id = ?", groupID) == nil {
			httpx.OK(c, failureEnvelope("小组不存在"))
			return
		}
		if denyDynamicGroupMutation(c, d, groupID) {
			return
		}
		var body struct {
			ChannelModelIDs []int64 `json:"channelModelIds"`
		}
		if err := c.ShouldBindJSON(&body); err != nil {
			httpx.OK(c, failureEnvelope("请求参数错误"))
			return
		}
		if len(body.ChannelModelIDs) == 0 {
			httpx.OK(c, failureEnvelope("channelModelIds 不能为空"))
			return
		}

		last, _ := d.Store.QueryOne(ctx, "SELECT sort_order FROM model_group_members WHERE group_id = ? ORDER BY sort_order DESC LIMIT 1", groupID)
		nextSort := int64(0)
		if last != nil {
			nextSort = last.I64("sort_order", 0) + 1
		}

		now := jtime.FormatApp(time.Now().UTC())
		added := 0
		for _, cmID := range body.ChannelModelIDs {
			if dup, _ := d.Store.QueryOne(ctx, "SELECT id FROM model_group_members WHERE group_id = ? AND channel_model_id = ?", groupID, cmID); dup != nil {
				continue
			}
			if _, err := d.Store.Insert(ctx,
				"INSERT INTO model_group_members (group_id, channel_model_id, weight, sort_order, enabled, created_at) VALUES (?, ?, 1, ?, 1, ?)",
				groupID, cmID, nextSort, now); err == nil {
				added++
				nextSort++
			}
		}
		httpx.OK(c, httpx.NewOrderedMap().Set("success", true).Set("count", added))
	})

	// DELETE /admin/api/model-groups/members/{memberId}
	g.DELETE("/model-groups/members/:memberId", func(c *gin.Context) {
		ctx := c.Request.Context()
		memberID, ok := pathID(c, "memberId")
		if !ok {
			httpx.OK(c, failureEnvelope("成员不存在"))
			return
		}
		if denyDynamicGroupMemberMutation(c, d, memberID) {
			return
		}
		if _, err := d.Store.Exec(ctx, "DELETE FROM model_group_members WHERE id = ?", memberID); err != nil {
			httpx.OK(c, failureEnvelope(err.Error()))
			return
		}
		httpx.OK(c, successEnvelope())
	})

	// POST /admin/api/model-groups/members/batch-delete
	g.POST("/model-groups/members/batch-delete", func(c *gin.Context) {
		ctx := c.Request.Context()
		var body struct {
			MemberIDs []any `json:"memberIds"`
		}
		if err := c.ShouldBindJSON(&body); err != nil || len(body.MemberIDs) == 0 {
			httpx.OK(c, failureEnvelope("memberIds 不能为空"))
			return
		}
		// 动态小组成员由规则匹配维护：只要本批里混有动态成员，整批拒绝。
		for _, mid := range body.MemberIDs {
			if v, ok := mid.(float64); ok && memberOfDynamicGroup(ctx, d.Store, int64(v)) {
				httpx.OK(c, failureEnvelope(dynamicMemberDenyMessage))
				return
			}
		}
		removed := 0
		for _, mid := range body.MemberIDs {
			switch v := mid.(type) {
			case float64:
				if _, err := d.Store.Exec(ctx, "DELETE FROM model_group_members WHERE id = ?", int64(v)); err == nil {
					removed++
				}
			}
		}
		httpx.OK(c, httpx.NewOrderedMap().Set("success", true).Set("count", removed))
	})

	// PUT /admin/api/model-groups/members/sort — 成员排序（前端拖拽后整表提交）
	g.PUT("/model-groups/members/sort", func(c *gin.Context) {
		ctx := c.Request.Context()
		var body struct {
			SortedMemberIDs []any `json:"sortedMemberIds"`
		}
		if err := c.ShouldBindJSON(&body); err != nil {
			httpx.OK(c, failureEnvelope("请求参数错误"))
			return
		}
		// 动态小组也允许手动排序：同步只把新命中的追加到末尾、移除不再命中的，
		// 已有成员的相对顺序（含手动拖拽结果）始终保持不变。
		for i, mid := range body.SortedMemberIDs {
			switch v := mid.(type) {
			case float64:
				d.Store.Exec(ctx, "UPDATE model_group_members SET sort_order = ? WHERE id = ?", i, int64(v))
			}
		}
		httpx.OK(c, successEnvelope())
	})

	// PUT /admin/api/model-groups/members/{memberId} — 成员权重 / 思考强度 / 启停
	// 动态小组的成员同样允许：同步不触碰已有成员行的属性，手动设置会一直保留。
	g.PUT("/model-groups/members/:memberId", func(c *gin.Context) {
		ctx := c.Request.Context()
		memberID, ok := pathID(c, "memberId")
		if !ok {
			httpx.OK(c, failureEnvelope("成员不存在"))
			return
		}
		var body struct {
			Weight          *int    `json:"weight"`
			ReasoningEffort *string `json:"reasoningEffort"`
			Enabled         *int    `json:"enabled"`
		}
		if err := c.ShouldBindJSON(&body); err != nil {
			httpx.OK(c, failureEnvelope("请求参数错误"))
			return
		}
		sets := map[string]any{}
		if body.Weight != nil {
			w := *body.Weight
			if w < 1 {
				w = 1
			}
			if w > 1000 {
				w = 1000
			}
			sets["weight"] = w
		}
		if body.ReasoningEffort != nil {
			effort := strings.TrimSpace(*body.ReasoningEffort)
			if effort == "" {
				sets["reasoning_effort"] = nil
			} else {
				sets["reasoning_effort"] = effort
			}
		}
		if body.Enabled != nil {
			sets["enabled"] = *body.Enabled
		}
		if len(sets) == 0 {
			httpx.OK(c, successEnvelope())
			return
		}
		q, args := store.BuildUpdate("model_group_members", sets, "id = ?", memberID)
		if _, err := d.Store.Exec(ctx, q, args...); err != nil {
			httpx.OK(c, failureEnvelope(err.Error()))
			return
		}
		httpx.OK(c, successEnvelope())
	})
}

// loadGroupMembers loads a group's members with the same display/breaker fields
// the entry-model relation list shows, so the group editor looks consistent.
func loadGroupMembers(ctx context.Context, st *store.Store, groupID int64) []models.ModelGroupMember {
	rows, _ := st.Query(ctx, "SELECT * FROM model_group_members WHERE group_id = ? ORDER BY sort_order ASC, id ASC", groupID)
	out := make([]models.ModelGroupMember, 0, len(rows))
	for _, r := range rows {
		member := store.RowToModelGroupMember(r)
		out = append(out, member)
	}
	fillGroupMemberDisplay(ctx, st, out)
	return out
}

// fillGroupMemberDisplay attaches channel/channel-model fields and breaker marks.
func fillGroupMemberDisplay(ctx context.Context, st *store.Store, members []models.ModelGroupMember) {
	if len(members) == 0 {
		return
	}
	cmIDs := make([]string, 0, len(members))
	for _, m := range members {
		if m.ChannelModelID != nil {
			cmIDs = append(cmIDs, int64Str(*m.ChannelModelID))
		}
	}
	if len(cmIDs) == 0 {
		return
	}
	inList := strings.Join(cmIDs, ",")
	cmRows, _ := st.Query(ctx, "SELECT * FROM channel_models WHERE id IN ("+inList+")")
	cmByID := map[int64]store.Row{}
	for _, r := range cmRows {
		cmByID[r.I64("id", 0)] = r
	}

	chIDs := make([]string, 0, len(cmRows))
	seenCh := map[int64]bool{}
	for _, r := range cmRows {
		chID := r.I64("channel_id", 0)
		if !seenCh[chID] {
			seenCh[chID] = true
			chIDs = append(chIDs, int64Str(chID))
		}
	}
	chByID := map[int64]store.Row{}
	if len(chIDs) > 0 {
		chRows, _ := st.Query(ctx, "SELECT * FROM channels WHERE id IN ("+strings.Join(chIDs, ",")+")")
		for _, r := range chRows {
			chByID[r.I64("id", 0)] = r
		}
	}

	for i := range members {
		if members[i].ChannelModelID == nil {
			continue
		}
		cm, ok := cmByID[*members[i].ChannelModelID]
		if !ok {
			continue
		}
		members[i].ChannelModelName = cm.StrPtr("model_name")
		members[i].Input = cm.StrPtr("input")
		members[i].ContextLength = cm.I64Ptr("context_length")
		ch, ok := chByID[cm.I64("channel_id", 0)]
		if !ok {
			continue
		}
		members[i].ChannelName = ch.StrPtr("name")
		members[i].ChannelType = ch.StrPtr("channel_type")
		members[i].ChannelID = int64Ptr(ch.I64("id", 0))
		members[i].ChannelEnabled = ch.IntPtr("enabled")

		// API Key 可用性：绑定 Key 需存在且启用，否则渠道下需至少有一个启用的 Key。
		available := false
		if kID := cm.I64Ptr("channel_api_key_id"); kID != nil {
			row, _ := st.QueryOne(ctx, "SELECT id FROM channel_api_keys WHERE id=? AND enabled=1", *kID)
			available = row != nil
		} else {
			row, _ := st.QueryOne(ctx, "SELECT id FROM channel_api_keys WHERE channel_id=? AND enabled=1 LIMIT 1", cm.I64("channel_id", 0))
			available = row != nil
		}
		members[i].APIKeyAvailable = models.Int(boolToInt(available))
	}
	applyGroupMemberPerfStats(ctx, st, members)
	applyGroupMemberBrokenMarks(ctx, st, members)
}

// applyGroupMemberPerfStats attaches each member's 24h TTFT/speed samples, using
// the same aggregation the entry-model relation rows use so both pages show
// identical numbers for the same channel model.
func applyGroupMemberPerfStats(ctx context.Context, st *store.Store, members []models.ModelGroupMember) {
	stats := loadRelPerfStats(ctx, st)
	if len(stats) == 0 {
		return
	}
	for i := range members {
		if members[i].ChannelName == nil || members[i].ChannelModelName == nil {
			continue
		}
		stat, ok := stats[*members[i].ChannelName+"||"+*members[i].ChannelModelName]
		if !ok {
			continue
		}
		ttft := stat.ttftMs
		members[i].TTFTMs = &ttft
		members[i].SampleCount = models.Int(stat.sampleCount)
		members[i].OutputSpeed = stat.outputSpeed
	}
}

// loadGroupUsage lists the entry models that reference a group.
func loadGroupUsage(ctx context.Context, st *store.Store, groupID int64) []models.Model {
	rows, _ := st.Query(ctx,
		`SELECT m.* FROM model_group_rels rel JOIN models m ON m.id = rel.model_id
		  WHERE rel.group_id = ? ORDER BY m.model_name ASC`, groupID)
	out := make([]models.Model, 0, len(rows))
	for _, r := range rows {
		out = append(out, store.RowToModel(r))
	}
	return out
}

// normalizeGroupStrategyInput validates an optional strategy string, returning ""
// for invalid input (so callers can report a precise error) and the default for
// an omitted value.
// groupStickyDefault enables session affinity unless the caller explicitly sends 0.
func groupStickyDefault(v *int) int {
	if v == nil {
		return 1
	}
	if *v == 0 {
		return 0
	}
	return 1
}

// dynamicMemberDenyMessage 是动态小组成员变更被拒绝时的统一提示。
const dynamicMemberDenyMessage = "动态小组成员由匹配规则自动维护，不可手动添加或移除；如需调整成员集合，请编辑小组的匹配规则"

// denyDynamicGroupMutation 按 groupID 拒绝对动态小组的成员变更（已是动态小组时返回 true）。
func denyDynamicGroupMutation(c *gin.Context, d Deps, groupID int64) bool {
	if isDynamicGroup(c.Request.Context(), d.Store, groupID) {
		httpx.OK(c, failureEnvelope(dynamicMemberDenyMessage))
		return true
	}
	return false
}

// denyDynamicGroupMemberMutation 按成员 ID 拒绝对动态小组成员的变更。
func denyDynamicGroupMemberMutation(c *gin.Context, d Deps, memberID int64) bool {
	if memberOfDynamicGroup(c.Request.Context(), d.Store, memberID) {
		httpx.OK(c, failureEnvelope(dynamicMemberDenyMessage))
		return true
	}
	return false
}

// memberOfDynamicGroup 报告该成员行是否属于动态小组。
func memberOfDynamicGroup(ctx context.Context, st *store.Store, memberID int64) bool {
	row, _ := st.QueryOne(ctx,
		`SELECT g.type FROM model_group_members m JOIN model_groups g ON g.id = m.group_id WHERE m.id = ?`, memberID)
	return row != nil && strings.TrimSpace(row.Str("type")) == "rule"
}

// isDynamicGroup 报告该小组是否为动态小组（type='rule'）。
func isDynamicGroup(ctx context.Context, st *store.Store, groupID int64) bool {
	row, _ := st.QueryOne(ctx, "SELECT type FROM model_groups WHERE id = ?", groupID)
	return row != nil && strings.TrimSpace(row.Str("type")) == "rule"
}

func normalizeGroupStrategyInput(s *string) string {
	if s == nil {
		return models.GroupStrategySticky
	}
	switch strings.TrimSpace(*s) {
	case "", models.GroupStrategySticky, "random", "round_robin":
		// 空值默认粘性（沿用旧默认 random 的加权分布意图）；历史值归一为粘性。
		return models.GroupStrategySticky
	case models.GroupStrategyFailover:
		return models.GroupStrategyFailover
	default:
		return ""
	}
}

func anyStrPtr(v any) *string {
	if v == nil {
		return nil
	}
	if s, ok := v.(string); ok {
		return &s
	}
	return nil
}
