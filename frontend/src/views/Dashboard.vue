<template>
  <div class="dashboard">
    <!-- Page Header -->
    <div class="page-header">
      <h2>{{ t('nav.dashboard') }}</h2>
      <p>{{ t('dashboard.resourceSummary') }}</p>
    </div>

    <!-- 今日请求趋势 -->
    <TodayTrendChart />

    <div class="stats-grid">
      <div class="stat-card">
        <div class="stat-icon" style="background:linear-gradient(135deg,rgba(88,166,255,0.15),rgba(88,166,255,0.05));color:var(--accent-blue);">
          <SvgIcon name="chart" :size="22" />
        </div>
        <div class="stat-body">
          <div class="stat-label">{{ t('dashboard.todayRequests') }}</div>
          <div class="stat-value">{{ stats.todayRequests ?? '-' }}</div>
          <div class="stat-hint" v-if="(stats.yesterdayRequests ?? 0) > 0">
            {{ t('dashboard.yesterday') }} {{ stats.yesterdayRequests }}
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
        <div class="stat-icon" style="background:linear-gradient(135deg,rgba(63,185,80,0.15),rgba(63,185,80,0.05));color:var(--accent-green);">
          <SvgIcon name="check" :size="22" />
        </div>
        <div class="stat-body">
          <div class="stat-label">{{ t('dashboard.successRate') }}</div>
          <div class="stat-value">{{ stats.successRate ?? '-' }}%</div>
          <div class="stat-hint">
            <span class="badge badge-success"><SvgIcon name="check-bold" :size="10" /> {{ stats.todaySuccess ?? 0 }}</span>
            <span class="badge badge-danger" style="margin-left:4px;"><SvgIcon name="x-bold" :size="10" /> {{ stats.todayFail ?? 0 }}</span>
          </div>
        </div>
      </div>
      <div class="stat-card">
        <div class="stat-icon" style="background:linear-gradient(135deg,rgba(188,140,255,0.15),rgba(188,140,255,0.05));color:var(--accent-purple);">
          <SvgIcon name="zap" :size="22" />
        </div>
        <div class="stat-body">
          <div class="stat-label">{{ t('dashboard.avgResponse') }}</div>
          <div class="stat-value">{{ stats.avgResponseTime ?? '-' }}<small>ms</small></div>
          <div class="stat-hint">{{ t('dashboard.basedOnToday') }}</div>
        </div>
      </div>
      <div class="stat-card">
        <div class="stat-icon" style="background:linear-gradient(135deg,rgba(210,153,34,0.15),rgba(210,153,34,0.05));color:var(--accent-yellow);">
          <SvgIcon name="monitor" :size="22" />
        </div>
        <div class="stat-body">
          <div class="stat-label">{{ t('dashboard.overview') }}</div>
          <div class="stat-value resource-value">
            {{ t('dashboard.channelCount').replace('{count}', stats.channelCount ?? 0) }} <span class="dot-sep">·</span>
            {{ t('dashboard.modelCount').replace('{count}', stats.customModelCount ?? 0) }} <span class="dot-sep">·</span>
            {{ t('dashboard.keyCount').replace('{count}', stats.apiKeyCount ?? 0) }}
          </div>
          <div class="stat-hint">{{ t('dashboard.resourceSummary') }}</div>
        </div>
      </div>
    </div>

    <!-- 本月统计 -->
    <div class="monthly-card">
      <div class="card-header">
        <div class="card-title"><SvgIcon name="chart" :size="18" /> {{ t('dashboard.monthlyStats') }}</div>
      </div>
      <div class="monthly-body" v-if="!loading">
        <div class="monthly-stat">
          <div class="monthly-label">{{ t('dashboard.monthlyRequests') }}</div>
          <div class="monthly-value">{{ stats.monthlyStats?.requests ?? 0 }}</div>
        </div>
        <div class="monthly-divider"></div>
        <div class="monthly-stat">
          <div class="monthly-label">{{ t('dashboard.monthlyTokens') }}</div>
          <div class="monthly-value">{{ formatTokens(stats.monthlyStats?.totalTokens) }}</div>
        </div>
        <div class="monthly-divider"></div>
        <div class="monthly-stat">
          <div class="monthly-label">{{ t('dashboard.monthlyInputTokens') }}</div>
          <div class="monthly-value">{{ formatTokens(stats.monthlyStats?.promptTokens) }}</div>
        </div>
        <div class="monthly-divider"></div>
        <div class="monthly-stat">
          <div class="monthly-label">{{ t('dashboard.monthlyOutputTokens') }}</div>
          <div class="monthly-value">{{ formatTokens(stats.monthlyStats?.completionTokens) }}</div>
        </div>
      </div>
      <div v-else style="text-align:center;padding:20px;color:var(--text-muted);font-size:13px;">
        <LoadingSpinner :text="t('common.loading')" />
      </div>
    </div>

    <div class="grid-2">
      <div class="card">
        <div class="card-header">
          <div class="card-title"><SvgIcon name="rank" :size="18" /> {{ t('dashboard.channelRank') }}</div>
          <TabSwitch v-model="channelRankPeriod" variant="period" :tabs="[
            { value: 'today', label: t('dashboard.periodToday') },
            { value: 'yesterday', label: t('dashboard.periodYesterday') },
            { value: 'week', label: t('dashboard.periodWeek') },
            { value: 'month', label: t('dashboard.periodMonth') },
          ]" />
        </div>
        <div v-if="loading" style="text-align:center;padding:30px;color:var(--text-muted);">
          <LoadingSpinner :text="t('common.loading')" />
        </div>
        <div v-else-if="!stats.channelRank?.length" style="text-align:center;padding:30px;color:var(--text-muted);">
          {{ t('dashboard.noRankData') }}
        </div>
        <div class="rank-list" v-else>
          <div class="rank-item" v-for="(ch, idx) in stats.channelRank" :key="ch.name">
            <div class="rank-pos" :class="idx === 0 ? 'gold' : idx === 1 ? 'silver' : idx === 2 ? 'bronze' : ''">
              {{ idx + 1 }}
            </div>
            <div class="rank-info">
              <div class="rank-name">{{ ch.name }}</div>
              <div class="rank-meta">
                <span>{{ ch.requests }} {{ t('dashboard.requests') }}</span>
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

      <div class="card">
        <div class="card-header">
          <div class="card-title"><SvgIcon name="model" :size="18" /> {{ t('dashboard.modelRank') }}</div>
          <div class="card-header-right">
            <TabSwitch v-model="modelRankTab" :tabs="[
              { value: 'entry', label: t('dashboard.entryModel') },
              { value: 'channel', label: t('dashboard.channelModel') },
            ]" />
            <TabSwitch v-model="modelRankPeriod" variant="period" :tabs="[
              { value: 'today', label: t('dashboard.periodToday') },
              { value: 'yesterday', label: t('dashboard.periodYesterday') },
              { value: 'week', label: t('dashboard.periodWeek') },
              { value: 'month', label: t('dashboard.periodMonth') },
            ]" />
          </div>
        </div>
        <div v-if="loading" style="text-align:center;padding:30px;color:var(--text-muted);">
          <LoadingSpinner :text="t('common.loading')" />
        </div>
        <div v-else-if="!currentModelRank?.length" style="text-align:center;padding:30px;color:var(--text-muted);">{{ t('dashboard.noData') }}</div>
        <div class="rank-list" v-else>
          <div class="rank-item" v-for="(m, idx) in currentModelRank" :key="m.name + (m.channelName || '')">
            <div class="rank-pos" :class="idx === 0 ? 'gold' : idx === 1 ? 'silver' : idx === 2 ? 'bronze' : ''">
              {{ idx + 1 }}
            </div>
            <div class="rank-info">
              <div class="rank-name">
                <span v-if="modelRankTab === 'channel' && m.channelName" class="rank-channel-tag">{{ m.channelName }}</span>
                <span class="rank-model-text">{{ m.name }}</span>
              </div>
              <div class="rank-meta">
                <span>{{ m.requests }} {{ t('dashboard.requests') }}</span>
                <span class="badge badge-success"><SvgIcon name="check-bold" :size="10" /> {{ m.success }}</span>
                <span v-if="m.totalTokens > 0" class="rank-meta-tokens">{{ formatTokens(m.totalTokens) }} tokens</span>
              </div>
            </div>
          </div>
        </div>
      </div>
    </div>

    <div class="card">
      <div class="card-header">
        <div class="card-title"><SvgIcon name="clock" :size="18" /> {{ t('dashboard.recentActivity') }}</div>
        <router-link to="/admin/log/list" class="btn btn-sm btn-secondary">{{ t('dashboard.viewAll') }}</router-link>
      </div>
      <div v-if="loading" style="text-align:center;padding:30px;color:var(--text-muted);">
        <LoadingSpinner :text="t('common.loading')" />
      </div>
      <div v-else-if="!stats.recentLogs?.length" style="text-align:center;padding:30px;color:var(--text-muted);">{{ t('dashboard.noActivity') }}</div>
      <div class="activity-list" v-else>
        <div class="activity-item" v-for="log in stats.recentLogs" :key="log.id">
          <PhaseBadge :phase="log.phase as any" />
          <code class="model-tag" style="font-size:11px;">{{ log.modelName }}</code>
          <span class="activity-channel">{{ log.channelName }}</span>
          <span class="activity-time">{{ formatTime(log.createdAt) }}</span>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted, onUnmounted, watch } from 'vue'
import { dashboardApi, type DashboardStats } from '@/api/dashboard'
import { useI18n } from '@/composables/useI18n'
import { formatLocalTime } from '@/utils/date'
import TodayTrendChart from '@/components/dashboard/TodayTrendChart.vue'
import PhaseBadge from '@/components/common/PhaseBadge.vue'

const { t } = useI18n()

const stats = ref<DashboardStats>({} as DashboardStats)
const loading = ref(true)
let dashboardRefreshTimer: ReturnType<typeof setInterval> | null = null
const modelRankTab = ref<'entry' | 'channel'>('entry')
const channelRankPeriod = ref('today')
const modelRankPeriod = ref('today')

const currentModelRank = computed(() => {
  return modelRankTab.value === 'entry' ? stats.value.modelRank : stats.value.channelModelRank
})



function formatTime(dateStr: string) {
  return formatLocalTime(dateStr)
}

/** 格式化 token 数量，大数字用 K/M 缩写 */
function formatTokens(n: number | undefined | null): string {
  if (n == null || n === 0) return '0'
  if (n >= 1_000_000) return (n / 1_000_000).toFixed(1) + 'M'
  if (n >= 1_000) return (n / 1_000).toFixed(1) + 'K'
  return n.toLocaleString()
}

async function fetchStats() {
  try {
    const res = await dashboardApi.getStats({
      channelRankPeriod: channelRankPeriod.value,
      modelRankPeriod: modelRankPeriod.value
    })
    stats.value = res.data
  } catch {
    // stats will show empty values
  }
}

// 周期切换时重新加载排行数据
watch([channelRankPeriod, modelRankPeriod], fetchStats)

onMounted(async () => {
  loading.value = true
  await fetchStats()
  loading.value = false
  dashboardRefreshTimer = setInterval(fetchStats, 15000)
})

onUnmounted(() => {
  if (dashboardRefreshTimer) clearInterval(dashboardRefreshTimer)
})
</script>

<style scoped>
.dashboard {
  display: flex;
  flex-direction: column;
  gap: 0;
}

.stats-grid {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 16px;
  margin-bottom: 20px;
}

.stat-card {
  background: var(--bg-secondary);
  border: 1px solid var(--border-color);
  border-radius: var(--radius-md, 12px);
  padding: 20px;
  display: flex;
  gap: 16px;
  align-items: center;
  box-shadow: var(--shadow-sm);
  transition: box-shadow 0.2s ease, transform 0.2s ease;
}

.stat-card:hover {
  box-shadow: var(--shadow-md);
  transform: translateY(-2px);
}

.stat-icon {
  width: 48px;
  height: 48px;
  border-radius: var(--radius-md, 12px);
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
}
.stat-body { flex: 1; min-width: 0; }
.stat-label { font-size: 12px; color: var(--text-muted); margin-bottom: 4px; font-weight: 500; }
.stat-value { font-size: 28px; font-weight: 700; line-height: 1.2; color: var(--text-primary); }
.stat-value small { font-size: 14px; font-weight: 400; color: var(--text-muted); margin-left: 2px; }
.stat-value.resource-value { font-size: 15px; line-height: 1.6; }
.dot-sep { color: var(--text-muted); margin: 0 6px; }
.stat-hint { font-size: 12px; color: var(--text-muted); margin-top: 6px; display: flex; align-items: center; flex-wrap: wrap; gap: 2px; }
.hint-sep { color: var(--border-color); margin: 0 4px; }

/* Rank section */
.rank-list {
  flex: 1;
  overflow-y: auto;
  min-height: 200px;
  display: flex;
  flex-direction: column;
  gap: 8px;
  padding-right: 4px;
}
.rank-list::-webkit-scrollbar {
  width: 5px;
}
.rank-list::-webkit-scrollbar-track {
  background: transparent;
}
.rank-list::-webkit-scrollbar-thumb {
  background: color-mix(in srgb, var(--text-muted) 40%, transparent);
  border-radius: 3px;
}
.rank-list::-webkit-scrollbar-thumb:hover {
  background: var(--text-muted);
}
.rank-item { display: flex; align-items: center; gap: 12px; padding: 12px 14px; border-radius: var(--radius); border: 1px solid var(--border-color); transition: background 0.15s, transform 0.15s; }
.rank-item:hover { background: var(--bg-hover); transform: translateX(4px); }
.rank-pos { width: 28px; height: 28px; border-radius: 50%; display: flex; align-items: center; justify-content: center; font-weight: 700; font-size: 12px; flex-shrink: 0; }
.rank-pos.gold { background: linear-gradient(135deg, #ffd700, #ffb700); color: #1a1a1a; }
.rank-pos.silver { background: linear-gradient(135deg, #c0c0c0, #a8a8a8); color: #1a1a1a; }
.rank-pos.bronze { background: linear-gradient(135deg, #cd7f32, #b87333); color: #fff; }
.rank-info { flex: 1; min-width: 0; }
.rank-name { display: flex; align-items: center; font-size: 14px; font-weight: 600; min-width: 0; color: var(--text-primary); }
.rank-model-text { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; min-width: 0; }
.rank-meta { font-size: 12px; color: var(--text-muted); display: flex; gap: 8px; margin-top: 3px; align-items: center; }
.rank-meta-time { color: var(--text-muted); font-size: 11px; }
.rank-meta-tokens { color: #ec4899; font-size: 11px; font-weight: 600; }
.rank-channel-tag {
  display: inline-block;
  padding: 1px 6px;
  border-radius: var(--radius-sm, 4px);
  font-size: 11px;
  font-weight: 600;
  background: rgba(88,166,255,0.15);
  color: var(--accent-blue);
  margin-right: 6px;
  line-height: 1.4;
}
.rank-bar-bg { width: 80px; height: 6px; background: var(--bg-primary); border-radius: var(--radius-full, 9999px); overflow: hidden; flex-shrink: 0; min-width: 20px; }
.rank-bar { height: 100%; background: linear-gradient(90deg, var(--accent-blue), color-mix(in srgb, var(--accent-blue) 70%, white)); border-radius: var(--radius-full, 9999px); transition: width 0.3s; min-width: 4px; }

/* Tab switch */
.card-header-right { display: flex; align-items: center; gap: 8px; margin-left: auto; }

/* Activity */
.activity-list { display: flex; flex-direction: column; gap: 6px; }
.activity-item { display: flex; align-items: center; gap: 8px; padding: 10px 12px; border-radius: var(--radius); font-size: 13px; border: 1px solid var(--border-color); transition: background 0.12s; }
.activity-item:hover { background: var(--bg-hover); }
.activity-channel { font-size: 12px; color: var(--text-secondary); flex: 1; text-align: right; }
.activity-time { font-size: 11px; color: var(--text-muted); white-space: nowrap; font-family: var(--font-mono, monospace); }

/* Monthly stats */
.monthly-card {
  background: var(--bg-secondary);
  border: 1px solid var(--border-color);
  border-radius: var(--radius-md, 12px);
  padding: 20px;
  overflow: hidden;
  margin-bottom: 20px;
  box-shadow: var(--shadow-sm);
}
.monthly-body {
  display: flex;
  align-items: center;
  padding: 16px 0;
  gap: 0;
}
.monthly-stat {
  flex: 1;
  text-align: center;
}
.monthly-label {
  font-size: 12px;
  color: var(--text-muted);
  margin-bottom: 6px;
  font-weight: 500;
}
.monthly-value {
  font-size: 22px;
  font-weight: 700;
  line-height: 1.2;
  color: var(--text-primary);
}
.monthly-divider {
  width: 1px;
  height: 40px;
  background: var(--border-color);
  flex-shrink: 0;
  margin: 0 16px;
}

@media (max-width: 768px) {
  .monthly-body {
    flex-wrap: wrap;
    gap: 12px 0;
    padding: 16px;
  }
  .monthly-stat {
    flex: 0 0 50%;
  }
  .monthly-divider {
    display: none;
  }
}

@media (max-width: 1400px) { .stats-grid { grid-template-columns: repeat(3, 1fr); } }
@media (max-width: 1000px) { .stats-grid { grid-template-columns: repeat(2, 1fr); } }
@media (max-width: 768px) {
  .stats-grid { grid-template-columns: 1fr; }
  .rank-meta-time { display: none; }
  .rank-item { gap: 8px; }
  .rank-bar-bg { width: 60px; margin-left: auto; }
}

</style>
