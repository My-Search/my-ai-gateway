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
	"github.com/my-search/my-ai-gateway/internal/httpx"
	"github.com/my-search/my-ai-gateway/internal/jtime"
	"github.com/my-search/my-ai-gateway/internal/models"
	"github.com/my-search/my-ai-gateway/internal/store"
)

// validGroupStrategies 是小组策略白名单。random 会按成员 weight 加权，
// sticky=1 时策略只决定「哈希命中成员失败后」的回退顺序。
var validGroupStrategies = map[string]bool{
	models.GroupStrategyFailover:   true,
	models.GroupStrategyRandom:     true,
	models.GroupStrategyRoundRobin: true,
}

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
			httpx.OK(c, failureEnvelope("路由方式无效，必须为 failover / random / round_robin"))
			return
		}
		if dup, _ := d.Store.QueryOne(ctx, "SELECT id FROM model_groups WHERE name = ?", name); dup != nil {
			httpx.OK(c, failureEnvelope("小组名称「"+name+"」已存在"))
			return
		}
		now := jtime.FormatApp(time.Now().UTC())
		id, err := d.Store.Insert(ctx,
			`INSERT INTO model_groups (name, strategy, sticky, enabled, created_at, updated_at)
			 VALUES (?, ?, ?, 1, ?, ?)`,
			name, strategy, groupStickyDefault(body.Sticky), now, now)
		if err != nil {
			httpx.OK(c, failureEnvelope(err.Error()))
			return
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
				httpx.OK(c, failureEnvelope("路由方式无效，必须为 failover / random / round_robin"))
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
		for i, mid := range body.SortedMemberIDs {
			switch v := mid.(type) {
			case float64:
				d.Store.Exec(ctx, "UPDATE model_group_members SET sort_order = ? WHERE id = ?", i, int64(v))
			}
		}
		httpx.OK(c, successEnvelope())
	})

	// PUT /admin/api/model-groups/members/{memberId} — 成员权重 / 思考强度
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

func normalizeGroupStrategyInput(s *string) string {
	if s == nil {
		return models.GroupStrategyRandom
	}
	v := strings.TrimSpace(*s)
	if v == "" {
		return models.GroupStrategyRandom
	}
	if !validGroupStrategies[v] {
		return ""
	}
	return v
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
