<template>
  <div>
    <!-- 统计卡片 -->
    <div class="stats-grid">
      <div class="stat-card">
        <div class="stat-icon" style="background:rgba(88,166,255,0.1);color:var(--accent-blue);">
          <SvgIcon name="chart" :size="24" />
        </div>
        <div class="stat-body">
          <div class="stat-label">今日请求</div>
          <div class="stat-value">{{ stats.todayRequests ?? '-' }}</div>
          <div class="stat-hint" v-if="(stats.yesterdayRequests ?? 0) > 0">
            昨日 {{ stats.yesterdayRequests }}
            <span class="hint-sep">|</span>
            <SvgIcon name="token" :size="11" />
            {{ formatTokens(stats.todayTokenStats?.totalTokens) }} tokens
          </div>
          <div class="stat-hint" v-else>
            <SvgIcon name="token" :size="11" />
            {{ formatTokens(stats.todayTokenStats?.totalTokens) }} tokens
          </div>
        </div>
      </div>
      <div class="stat-card">
        <div class="stat-icon" style="background:rgba(63,185,80,0.1);color:var(--accent-green);">
          <SvgIcon name="check" :size="24" />
        </div>
        <div class="stat-body">
          <div class="stat-label">成功率</div>
          <div class="stat-value">{{ stats.successRate ?? '-' }}%</div>
          <div class="stat-hint">
            <span class="badge badge-success"><SvgIcon name="check-bold" :size="10" /> {{ stats.todaySuccess ?? 0 }}</span>
            <span class="badge badge-danger" style="margin-left:4px;"><SvgIcon name="x-bold" :size="10" /> {{ stats.todayFail ?? 0 }}</span>
          </div>
        </div>
      </div>
      <div class="stat-card">
        <div class="stat-icon" style="background:rgba(188,140,255,0.1);color:var(--accent-purple);">
          <SvgIcon name="zap" :size="24" />
        </div>
        <div class="stat-body">
          <div class="stat-label">平均响应</div>
          <div class="stat-value">{{ stats.avgResponseTime ?? '-' }}<small>ms</small></div>
          <div class="stat-hint">基于今日所有完成请求</div>
        </div>
      </div>
      <div class="stat-card">
        <div class="stat-icon" style="background:rgba(210,153,34,0.1);color:var(--accent-yellow);">
          <SvgIcon name="monitor" :size="24" />
        </div>
        <div class="stat-body">
          <div class="stat-label">资源概览</div>
          <div class="stat-value resource-value">
            {{ stats.channelCount ?? 0 }} 渠道 <span class="dot-sep">·</span>
            {{ stats.customModelCount ?? 0 }} 模型 <span class="dot-sep">·</span>
            {{ stats.apiKeyCount ?? 0 }} 密钥
          </div>
          <div class="stat-hint">系统配置资源汇总</div>
        </div>
      </div>
    </div>

    <!-- 7日趋势 + 渠道排行 -->
    <div class="grid-2" style="margin-top:20px;">
      <div class="card">
        <div class="card-header">
          <div class="card-title"><SvgIcon name="chart" :size="18" /> 7日请求趋势</div>
        </div>
        <div class="trend-chart" v-if="stats.dailyTrend?.length">
          <div class="trend-bars">
            <div class="trend-bar-col" v-for="day in stats.dailyTrend" :key="day.label">
              <div class="trend-bar-wrap">
                <div class="trend-bar" :style="{ height: maxReq > 0 ? (day.requests / maxReq * 100) + '%' : '2px' }"
                     :title="day.label + ': ' + day.requests + ' 请求'">
                </div>
              </div>
              <div class="trend-label">{{ day.label }}</div>
              <div class="trend-count">{{ day.requests }}</div>
            </div>
          </div>
        </div>
        <div v-else style="text-align:center;padding:30px;color:var(--text-muted);">暂无数据</div>
      </div>

      <div class="card">
        <div class="card-header">
          <div class="card-title"><SvgIcon name="rank" :size="18" /> 今日渠道排行</div>
        </div>
        <div v-if="!stats.channelRank?.length" style="text-align:center;padding:30px;color:var(--text-muted);">
          暂无数据，发起请求后将显示在此处
        </div>
        <div class="rank-list" v-else>
          <div class="rank-item" v-for="(ch, idx) in stats.channelRank" :key="ch.name">
            <div class="rank-pos" :class="idx === 0 ? 'gold' : idx === 1 ? 'silver' : idx === 2 ? 'bronze' : ''">
              {{ idx + 1 }}
            </div>
            <div class="rank-info">
              <div class="rank-name">{{ ch.name }}</div>
              <div class="rank-meta">
                <span>{{ ch.requests }} 请求</span>
                <span class="badge badge-success"><SvgIcon name="check-bold" :size="10" /> {{ ch.success }}</span>
                <span v-if="ch.avgTime > 0" class="rank-meta-time">{{ ch.avgTime }}ms</span>
                <span v-if="ch.totalTokens > 0" class="rank-meta-tokens">{{ formatTokens(ch.totalTokens) }} tokens</span>
              </div>
            </div>
            <div class="rank-bar-bg">
              <div class="rank-bar" :style="{ width: (ch.requests / stats.channelRank[0].requests * 100) + '%' }"></div>
            </div>
          </div>
        </div>
      </div>
    </div>

    <!-- 模型排行 + 最近日志 -->
    <div class="grid-2" style="margin-top:16px;">
      <div class="card">
        <div class="card-header">
          <div class="card-title"><SvgIcon name="model" :size="18" /> 今日模型排行</div>
          <div class="tab-switch">
            <button :class="['tab-btn', modelRankTab === 'entry' ? 'active' : '']" @click="modelRankTab = 'entry'">入口模型</button>
            <button :class="['tab-btn', modelRankTab === 'channel' ? 'active' : '']" @click="modelRankTab = 'channel'">渠道模型</button>
          </div>
        </div>
        <div v-if="!currentModelRank?.length" style="text-align:center;padding:30px;color:var(--text-muted);">暂无数据</div>
        <div class="rank-list" v-else>
          <div class="rank-item" v-for="(m, idx) in currentModelRank" :key="m.name">
            <div class="rank-pos" :class="idx === 0 ? 'gold' : idx === 1 ? 'silver' : idx === 2 ? 'bronze' : ''">
              {{ idx + 1 }}
            </div>
            <div class="rank-info">
              <div class="rank-name">{{ m.name }}</div>
              <div class="rank-meta">
                <span>{{ m.requests }} 请求</span>
                <span class="badge badge-success"><SvgIcon name="check-bold" :size="10" /> {{ m.success }}</span>
                <span v-if="m.totalTokens > 0" class="rank-meta-tokens">{{ formatTokens(m.totalTokens) }} tokens</span>
              </div>
            </div>
          </div>
        </div>
      </div>

      <div class="card">
        <div class="card-header">
          <div class="card-title"><SvgIcon name="clock" :size="18" /> 最近活动</div>
          <router-link to="/admin/log/list" class="btn btn-sm btn-secondary">查看全部 →</router-link>
        </div>
        <div v-if="!stats.recentLogs?.length" style="text-align:center;padding:30px;color:var(--text-muted);">暂无活动记录</div>
        <div class="activity-list" v-else>
          <div class="activity-item" v-for="log in stats.recentLogs" :key="log.id">
            <span :class="'phase phase-' + log.phase">{{ phaseLabel(log.phase) }}</span>
            <code class="model-tag" style="font-size:11px;">{{ log.modelName }}</code>
            <span class="activity-channel">{{ log.channelName }}</span>
            <span class="activity-time">{{ formatTime(log.createdAt) }}</span>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import { dashboardApi, type DashboardStats } from '@/api/dashboard'

const stats = ref<DashboardStats>({} as DashboardStats)
const modelRankTab = ref<'entry' | 'channel'>('entry')

const maxReq = computed(() => {
  if (!stats.value.dailyTrend?.length) return 1
  return Math.max(...stats.value.dailyTrend.map(d => d.requests), 1)
})

const currentModelRank = computed(() => {
  return modelRankTab.value === 'entry' ? stats.value.modelRank : stats.value.channelModelRank
})

function phaseLabel(phase: string) {
  const map: Record<string, string> = { start: '开始', retry: '重试', reroute: '重路由', success: '成功', fail: '失败' }
  return map[phase] || phase
}

function formatTime(dateStr: string) {
  if (!dateStr) return ''
  return dateStr.substring(11, 16)
}

/** 格式化 token 数量，大数字用 K/M 缩写 */
function formatTokens(n: number | undefined | null): string {
  if (n == null || n === 0) return '0'
  if (n >= 1_000_000) return (n / 1_000_000).toFixed(1) + 'M'
  if (n >= 1_000) return (n / 1_000).toFixed(1) + 'K'
  return n.toLocaleString()
}

onMounted(async () => {
  try {
    const res = await dashboardApi.getStats()
    stats.value = res.data
  } catch {
    // stats will show empty values
  }
})
</script>

<style scoped>
.stats-grid {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 16px;
}
.stat-card {
  background: var(--bg-secondary);
  border: 1px solid var(--border-color);
  border-radius: 12px;
  padding: 20px;
  display: flex;
  gap: 16px;
  align-items: center;
}
.stat-icon {
  width: 48px;
  height: 48px;
  border-radius: 12px;
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
}
.stat-body { flex: 1; min-width: 0; }
.stat-label { font-size: 12px; color: var(--text-muted); margin-bottom: 4px; }
.stat-value { font-size: 28px; font-weight: 700; line-height: 1.2; }
.stat-value small { font-size: 14px; font-weight: 400; color: var(--text-muted); margin-left: 2px; }
.stat-value.resource-value { font-size: 16px; line-height: 1.6; }
.dot-sep { color: var(--text-muted); margin: 0 6px; }
.stat-hint { font-size: 12px; color: var(--text-muted); margin-top: 6px; display: flex; align-items: center; flex-wrap: wrap; gap: 2px; }
.hint-sep { color: var(--border-color); margin: 0 4px; }

/* Trend chart */
.trend-chart { padding: 8px 0; }
.trend-bars { display: flex; align-items: flex-end; gap: 8px; height: 120px; }
.trend-bar-col { flex: 1; display: flex; flex-direction: column; align-items: center; height: 100%; }
.trend-bar-wrap { flex: 1; width: 100%; display: flex; align-items: flex-end; justify-content: center; }
.trend-bar {
  width: 70%;
  background: linear-gradient(180deg, var(--accent-blue), rgba(88,166,255,0.3));
  border-radius: 4px 4px 0 0;
  min-height: 2px;
  transition: height 0.3s;
}
.trend-label { font-size: 12px; color: var(--text-secondary); margin-top: 6px; font-weight: 500; }
.trend-count { font-size: 11px; color: var(--text-primary); font-weight: 600; }

/* Rank */
.rank-list { display: flex; flex-direction: column; gap: 8px; }
.rank-item { display: flex; align-items: center; gap: 12px; padding: 10px 12px; border-radius: 8px; background: var(--bg-tertiary); transition: background 0.15s; }
.rank-item:hover { background: var(--bg-hover); }
.rank-pos { width: 28px; height: 28px; border-radius: 50%; display: flex; align-items: center; justify-content: center; font-weight: 700; font-size: 12px; }
.rank-pos.gold { background: linear-gradient(135deg, #ffd700, #ffb700); color: #1a1a1a; }
.rank-pos.silver { background: linear-gradient(135deg, #c0c0c0, #a8a8a8); color: #1a1a1a; }
.rank-pos.bronze { background: linear-gradient(135deg, #cd7f32, #b87333); color: #fff; }
.rank-info { flex: 1; min-width: 0; }
.rank-name { font-size: 14px; font-weight: 600; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.rank-meta { font-size: 12px; color: var(--text-muted); display: flex; gap: 8px; margin-top: 3px; align-items: center; }
.rank-meta-time { color: var(--text-muted); font-size: 11px; }
.rank-meta-tokens { color: #ec4899; font-size: 11px; font-weight: 600; }
.rank-bar-bg { width: 80px; height: 6px; background: var(--bg-primary); border-radius: 3px; overflow: hidden; flex-shrink: 0; min-width: 20px; }
.rank-bar { height: 100%; background: var(--accent-blue); border-radius: 3px; transition: width 0.3s; min-width: 4px; }

/* Tab switch */
.tab-switch { display: flex; gap: 4px; background: var(--bg-primary); border-radius: 6px; padding: 2px; }
.tab-btn { padding: 4px 10px; border: none; background: transparent; color: var(--text-muted); font-size: 12px; font-weight: 500; border-radius: 4px; cursor: pointer; transition: all 0.15s; }
.tab-btn:hover { color: var(--text-primary); }
.tab-btn.active { background: var(--bg-secondary); color: var(--text-primary); box-shadow: 0 1px 2px rgba(0,0,0,0.1); }

/* Activity */
.activity-list { display: flex; flex-direction: column; gap: 6px; }
.activity-item { display: flex; align-items: center; gap: 8px; padding: 8px 10px; border-radius: 6px; font-size: 13px; background: var(--bg-tertiary); }
.activity-channel { font-size: 12px; color: var(--text-secondary); flex: 1; text-align: right; }
.activity-time { font-size: 11px; color: var(--text-muted); white-space: nowrap; font-family: 'SF Mono', 'Fira Code', monospace; }

/* Phase colors */
.phase { display: inline-block; padding: 1px 6px; border-radius: 3px; font-size: 11px; font-weight: 600; }
.phase-start { background: rgba(88,166,255,0.2); color: var(--accent-blue); }
.phase-retry { background: rgba(210,153,34,0.2); color: var(--accent-yellow); }
.phase-reroute { background: rgba(188,140,255,0.2); color: var(--accent-purple); }
.phase-success { background: rgba(63,185,80,0.2); color: var(--accent-green); }
.phase-fail { background: rgba(248,81,73,0.2); color: var(--accent-red); }

@media (max-width: 1400px) { .stats-grid { grid-template-columns: repeat(3, 1fr); } }
@media (max-width: 1000px) { .stats-grid { grid-template-columns: repeat(2, 1fr); } }
@media (max-width: 768px) { .stats-grid { grid-template-columns: 1fr; } }
</style>
