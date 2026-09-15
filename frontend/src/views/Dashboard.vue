<template>
  <div class="dashboard">
    <!-- Page Header -->
    <div class="dashboard-header">
      <div class="header-left">
        <div class="title-row">
          <h2>{{ t('nav.dashboard') }}</h2>
          <div class="header-controls">
            <!-- 时间段下拉选择器 -->
            <div class="period-dropdown" ref="periodDropdownRef">
              <button class="period-trigger" @click="openPeriod">
                <SvgIcon name="calendar" :size="14" />
                <span>{{ periodOptions.find(o => o.value === rangeKey)?.label }}</span>
                <SvgIcon name="chevron-down" :size="12" :class="{ rotated: periodOpen }" />
              </button>
              <Transition name="fade">
                <div v-if="periodOpen" class="period-menu">
                  <button
                    v-for="opt in periodOptions"
                    :key="opt.value"
                    :class="['period-option', { active: rangeKey === opt.value }]"
                    @click="selectPeriod(opt.value)"
                  >
                    {{ opt.label }}
                  </button>
                </div>
              </Transition>
            </div>
          </div>
        </div>
        <p>{{ t('dashboard.subtitle') }}</p>
      </div>
    </div>

    <!-- 自定义时间段选择弹框 -->
    <Dialog
      v-model="customDialogOpen"
      :title="t('dashboard.rangeCustom')"
      type="confirm"
      :confirm-text="t('dialog.confirm')"
      :cancel-text="t('dialog.cancel')"
      width="420px"
      @confirm="confirmCustomRange"
    >
      <div class="custom-range-form">
        <div class="form-group">
          <label>{{ t('dashboard.rangeStart') }}</label>
          <input v-model="customFrom" type="date" class="form-control" />
        </div>
        <div class="form-group" style="margin-bottom:0;">
          <label>{{ t('dashboard.rangeEnd') }}</label>
          <input v-model="customTo" type="date" class="form-control" />
        </div>
        <p v-if="customInvalid" class="custom-range-error">{{ t('dashboard.rangeError') }}</p>
      </div>
    </Dialog>

    <!-- 请求趋势（跟随所选时间段） -->
    <TodayTrendChart :range-key="rangeKey" :from="fromDate" :to="toDate" />

    <!-- Stats Grid -->
    <div class="stats-grid" v-if="!loading">
      <div class="stat-card stat-card--blue">
        <div class="stat-top">
          <div class="stat-icon"><SvgIcon name="chart" :size="20" /></div>
          <div class="stat-head">
            <div class="stat-label">{{ t('dashboard.todayRequests') }}</div>
            <div class="stat-value">{{ hasData ? formatNumber(totals.requests) : '-' }}</div>
          </div>
          <svg class="stat-spark" viewBox="0 0 100 30" preserveAspectRatio="none" aria-hidden="true">
            <path :d="sparklinePaths(spark.requests).area" class="spark-area" />
            <path :d="sparklinePaths(spark.requests).line" class="spark-line" />
          </svg>
        </div>
        <div class="stat-bottom">
          <span class="stat-meta"><SvgIcon name="token" :size="11" /> {{ formatTokens(totals.totalTokens) }} tokens</span>
          <span v-if="vsRequests.show" class="stat-change" :class="vsRequests.css">
            <span class="change-arrow">{{ vsRequests.arrow }}</span> {{ rangeLabels.prev }} {{ vsRequests.percent }}%
          </span>
        </div>
      </div>

      <div class="stat-card stat-card--green">
        <div class="stat-top">
          <div class="stat-icon"><SvgIcon name="check" :size="20" /></div>
          <div class="stat-head">
            <div class="stat-label">{{ t('dashboard.successRate') }}</div>
            <div class="stat-value">{{ hasData ? (totals.successRate ?? 0) + '%' : '-' }}</div>
          </div>
          <svg class="stat-spark" viewBox="0 0 100 30" preserveAspectRatio="none" aria-hidden="true">
            <path :d="sparklinePaths(spark.successRate).area" class="spark-area" />
            <path :d="sparklinePaths(spark.successRate).line" class="spark-line" />
          </svg>
        </div>
        <div class="stat-bottom">
          <span class="stat-badges">
            <span class="badge badge-success"><SvgIcon name="check-bold" :size="10" /> {{ totals.success ?? 0 }}</span>
            <span class="badge badge-danger"><SvgIcon name="x-bold" :size="10" /> {{ totals.fail ?? 0 }}</span>
          </span>
          <span v-if="vsSuccessRate.show" class="stat-change" :class="vsSuccessRate.css">
            <span class="change-arrow">{{ vsSuccessRate.arrow }}</span> {{ rangeLabels.prev }} {{ vsSuccessRate.percent }}%
          </span>
        </div>
      </div>

      <div class="stat-card stat-card--purple">
        <div class="stat-top">
          <div class="stat-icon"><SvgIcon name="clock" :size="20" /></div>
          <div class="stat-head">
            <div class="stat-label">{{ t('dashboard.avgResponse') }}</div>
            <div class="stat-value">{{ totals.avgResponseTime ? formatSeconds(totals.avgResponseTime) : '-' }}</div>
          </div>
          <svg class="stat-spark" viewBox="0 0 100 30" preserveAspectRatio="none" aria-hidden="true">
            <path :d="sparklinePaths(spark.avgResponseTime).area" class="spark-area" />
            <path :d="sparklinePaths(spark.avgResponseTime).line" class="spark-line" />
          </svg>
        </div>
        <div class="stat-bottom">
          <span class="stat-meta">{{ t('dashboard.basedOnRange') }}</span>
          <span v-if="vsAvgResponse.show" class="stat-change" :class="vsAvgResponse.css">
            <span class="change-arrow">{{ vsAvgResponse.arrow }}</span> {{ rangeLabels.prev }} {{ vsAvgResponse.percent }}%
          </span>
        </div>
      </div>

      <div class="stat-card stat-card--yellow">
        <div class="stat-top">
          <div class="stat-icon"><SvgIcon name="zap" :size="20" /></div>
          <div class="stat-head">
            <div class="stat-label">{{ t('dashboard.avgOutputSpeed') }}</div>
            <div class="stat-value">
              <template v-if="totals.avgOutputSpeed">{{ totals.avgOutputSpeed.toFixed(1) }}<small> t/s</small></template>
              <template v-else>-</template>
            </div>
          </div>
          <svg class="stat-spark" viewBox="0 0 100 30" preserveAspectRatio="none" aria-hidden="true">
            <path :d="sparklinePaths(spark.avgOutputSpeed).area" class="spark-area" />
            <path :d="sparklinePaths(spark.avgOutputSpeed).line" class="spark-line" />
          </svg>
        </div>
        <div class="stat-bottom">
          <span class="stat-meta">{{ t('dashboard.basedOnRangeSuccess') }}</span>
          <span v-if="vsOutputSpeed.show" class="stat-change" :class="vsOutputSpeed.css">
            <span class="change-arrow">{{ vsOutputSpeed.arrow }}</span> {{ rangeLabels.prev }} {{ vsOutputSpeed.percent }}%
          </span>
        </div>
      </div>
    </div>
    <div class="stats-grid stats-grid-loading" v-else>
      <div class="stat-card stat-card-loading" v-for="i in 4" :key="i">
        <LoadingSpinner :text="t('common.loading')" />
      </div>
    </div>

    <!-- Rankings -->
    <div class="grid-2 rank-grid">
      <!-- 渠道排行 -->
      <div class="card rank-card">
        <div class="card-header">
          <div class="card-title"><SvgIcon name="rank" :size="18" /> {{ t('dashboard.channelRank') }}</div>
          <router-link to="/admin/log/list" class="view-all-link">{{ t('dashboard.viewAll') }}</router-link>
        </div>
        <div v-if="loading" class="rank-state"><LoadingSpinner :text="t('common.loading')" /></div>
        <div v-else-if="!stats.channelRank?.length" class="rank-state">{{ t('dashboard.noRankData') }}</div>
        <div v-else class="table-scroll">
          <table class="rank-table">
          <thead>
            <tr>
              <th class="col-idx">#</th>
              <th>{{ t('dashboard.rankChannelName') }}</th>
              <th class="col-num">{{ t('dashboard.rankRequests') }}</th>
              <th class="col-rate">{{ t('dashboard.rankSuccessRate') }}</th>
              <th class="col-num">{{ t('dashboard.rankAvgTime') }}</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="(ch, idx) in stats.channelRank" :key="ch.name">
              <td class="col-idx">{{ idx + 1 }}</td>
              <td>
                <div class="rank-name-cell">
                  <span class="rank-avatar" :style="{ background: iconGradient(ch.name) }">{{ (ch.name || '?').charAt(0).toUpperCase() }}</span>
                  <span class="rank-name-text">{{ ch.name }}</span>
                </div>
              </td>
              <td class="col-num">{{ formatNumber(ch.requests) }}</td>
              <td class="col-rate">
                <div class="rate-cell">
                  <span class="rate-text">{{ successRateOf(ch) }}%</span>
                  <span class="rate-bar"><span class="rate-bar-fill" :style="{ width: successRateOf(ch) + '%' }"></span></span>
                </div>
              </td>
              <td class="col-num">{{ ch.avgTime > 0 ? formatSeconds(ch.avgTime) : '-' }}</td>
            </tr>
          </tbody>
        </table>
        </div>
      </div>

      <!-- 模型排行 -->
      <div class="card rank-card">
        <div class="card-header">
          <div class="card-title"><SvgIcon name="model" :size="18" /> {{ t('dashboard.modelRank') }}</div>
          <TabSwitch v-model="modelRankTab" variant="primary" :tabs="[
            { value: 'entry', label: t('dashboard.entryModel') },
            { value: 'channel', label: t('dashboard.channelModel') },
          ]" />
        </div>
        <div v-if="loading" class="rank-state"><LoadingSpinner :text="t('common.loading')" /></div>
        <div v-else-if="!currentModelRank?.length" class="rank-state">{{ t('dashboard.noRankData') }}</div>
        <div v-else class="table-scroll">
          <table class="rank-table">
          <thead>
            <tr>
              <th class="col-idx">#</th>
              <th>{{ t('dashboard.rankModelName') }}</th>
              <th class="col-num">{{ t('dashboard.rankRequests') }}</th>
              <th class="col-rate">{{ t('dashboard.rankSuccessRate') }}</th>
              <th class="col-num">{{ t('dashboard.rankAvgTime') }}</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="(m, idx) in currentModelRank" :key="m.name + (m.channelName || '')">
              <td class="col-idx">{{ idx + 1 }}</td>
              <td>
                <div class="rank-name-cell">
                  <span class="rank-avatar" :style="{ background: iconGradient(m.name) }">{{ (m.name || '?').charAt(0).toUpperCase() }}</span>
                  <span class="rank-name-text">
                    <span v-if="modelRankTab === 'channel' && m.channelName" class="rank-channel-tag">{{ m.channelName }}/</span>{{ m.name }}
                  </span>
                </div>
              </td>
              <td class="col-num">{{ formatNumber(m.requests) }}</td>
              <td class="col-rate">
                <div class="rate-cell">
                  <span class="rate-text">{{ successRateOf(m) }}%</span>
                  <span class="rate-bar"><span class="rate-bar-fill" :style="{ width: successRateOf(m) + '%' }"></span></span>
                </div>
              </td>
              <td class="col-num">{{ m.avgTime > 0 ? formatSeconds(m.avgTime) : '-' }}</td>
            </tr>
          </tbody>
        </table>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted, onUnmounted, watch, onActivated } from 'vue'
import { dashboardApi, type DashboardRangeKey, type DashboardRangeParams, type DashboardStats, type ModelRankItem, type DashboardSparklines } from '@/api/dashboard'
import { useI18n } from '@/composables/useI18n'
import { formatNumber, formatSeconds, formatTokens } from '@/utils/format'
import { sparklinePaths } from '@/utils/sparkline'
import TodayTrendChart from '@/components/dashboard/TodayTrendChart.vue'
import Dialog from '@/components/common/Dialog.vue'

const { t } = useI18n()

const stats = ref<DashboardStats>({} as DashboardStats)
const loading = ref(true)
let dashboardRefreshTimer: ReturnType<typeof setInterval> | null = null
const modelRankTab = ref<'entry' | 'channel'>('entry')

// ===== 下拉时间段选择器 =====
const periodOpen = ref(false)
const periodDropdownRef = ref<HTMLDivElement | null>(null)
const periodOptions = [
  { value: 'today' as DashboardRangeKey, label: t('dashboard.periodToday') },
  { value: 'week' as DashboardRangeKey, label: t('dashboard.periodWeek') },
  { value: 'month' as DashboardRangeKey, label: t('dashboard.periodMonth') },
  { value: 'custom' as DashboardRangeKey, label: t('dashboard.rangeCustom') },
]

function openPeriod() {
  periodOpen.value = !periodOpen.value
}

// 自定义时间段的弹框
const customDialogOpen = ref(false)
const customFrom = ref(todayStr())
const customTo = ref(todayStr())
const customInvalid = computed(() =>
  !!customFrom.value && !!customTo.value && customFrom.value > customTo.value)

function selectPeriod(val: DashboardRangeKey) {
  periodOpen.value = false
  if (val === 'custom') {
    // 打开弹框让用户选择起止日期
    customFrom.value = fromDate.value
    customTo.value = toDate.value
    customDialogOpen.value = true
    return
  }
  rangeKey.value = val
}

function confirmCustomRange() {
  if (customInvalid.value) {
    // 非法区间：保持弹框打开并提示
    customDialogOpen.value = true
    return
  }
  fromDate.value = customFrom.value
  toDate.value = customTo.value
  rangeKey.value = 'custom'
  customDialogOpen.value = false
}

function onPeriodClickOutside(e: MouseEvent) {
  const el = periodDropdownRef.value
  if (el && !el.contains(e.target as Node)) {
    periodOpen.value = false
  }
}

onMounted(() => {
  document.addEventListener('click', onPeriodClickOutside)
})
onUnmounted(() => {
  document.removeEventListener('click', onPeriodClickOutside)
})

// ===== 时间段选择 =====
// today/week/month 由后端按上海时区计算（周=周一起、月=1日起，均为"至今"）；
// custom 为用户自定义起止日期（含边界），非法输入时不发请求。
const rangeKey = ref<DashboardRangeKey>('today')

function todayStr(): string {
  const d = new Date()
  const y = d.getFullYear()
  const m = String(d.getMonth() + 1).padStart(2, '0')
  const day = String(d.getDate()).padStart(2, '0')
  return `${y}-${m}-${day}`
}

const fromDate = ref(todayStr())
const toDate = ref(todayStr())

const rangeInvalid = computed(() =>
  rangeKey.value === 'custom' && !!fromDate.value && !!toDate.value && fromDate.value > toDate.value)

const totals = computed(() => stats.value.totals ?? {
  requests: 0, success: 0, fail: 0, successRate: 0, avgResponseTime: 0, avgOutputSpeed: 0, totalTokens: 0
})

const prevTotals = computed(() => stats.value.prevTotals ?? {
  requests: 0, success: 0, fail: 0, successRate: 0, avgResponseTime: 0, avgOutputSpeed: 0, totalTokens: 0
})

const emptySpark = (): DashboardSparklines => ({
  requests: [], successRate: [], avgResponseTime: [], avgOutputSpeed: []
})
const spark = computed<DashboardSparklines>(() => stats.value.sparklines ?? emptySpark())

const hasData = computed(() => (totals.value.requests ?? 0) > 0)

// 环比标签随所选时间段变化：较昨日 / 较上周同期 / 较上月同期 / 较上期
const rangeLabels = computed(() => {
  const prevText = {
    today: t('dashboard.vsYesterday'),
    week: t('dashboard.vsLastWeek'),
    month: t('dashboard.vsLastMonth'),
    custom: t('dashboard.vsPrevPeriod'),
  }[rangeKey.value] ?? t('dashboard.vsPrevPeriod')
  return { prev: prevText }
})

// ===== 卡片环比（与上一同期窗口对比）=====
// 双零 → 隐藏；仅上一期无数据 → 视为 0，显示 +100.0%
interface VsChange { show: boolean; arrow: string; percent: string; css: string }

function buildVsChange(current: number | undefined, prev: number | undefined, invert = false): VsChange {
  const c = current ?? 0
  const p = prev ?? 0
  if (p === 0 && c === 0) return { show: false, arrow: '→', percent: '0.0', css: '' }
  if (p === 0) {
    // 上一期无数据视为 0，本期有数据 → 上涨 100%
    // invert=true 时"上涨"是坏事（首字节变慢），颜色翻转为 down
    return { show: true, arrow: '↑', percent: '+100.0', css: invert ? 'down' : 'up' }
  }
  const change = ((c - p) / p) * 100
  const isUp = c > p
  return {
    show: true,
    arrow: isUp ? '↑' : '↓',
    percent: (change > 0 ? '+' : '') + change.toFixed(1),
    css: invert ? (isUp ? 'down' : 'up') : (isUp ? 'up' : 'down')
  }
}

const vsRequests = computed(() =>
  buildVsChange(totals.value.requests, prevTotals.value.requests))
const vsSuccessRate = computed(() =>
  buildVsChange(totals.value.successRate, prevTotals.value.successRate))
// 首字节时间：上升（变慢）为坏方向，颜色翻转
const vsAvgResponse = computed(() =>
  buildVsChange(totals.value.avgResponseTime, prevTotals.value.avgResponseTime, true))
const vsOutputSpeed = computed(() =>
  buildVsChange(totals.value.avgOutputSpeed, prevTotals.value.avgOutputSpeed))

const currentModelRank = computed<(ModelRankItem & { channelName?: string })[]>(() => {
  return modelRankTab.value === 'entry' ? (stats.value.modelRank ?? []) : (stats.value.channelModelRank ?? [])
})

// 排行表格：成功率由 成功数/请求数 推导（口径与卡片一致，恒 ≤100%）
function successRateOf(row: { requests: number; success: number }): string {
  if (!row.requests) return '0.0'
  const rate = Math.min(100, (row.success / row.requests) * 100)
  return rate.toFixed(1)
}

// 排行首字母色块（与模型列表页同一套配色算法）
const iconPalette = [
  'linear-gradient(135deg, #61afef, #2b6cb0)',
  'linear-gradient(135deg, #98c379, #3f7d3a)',
  'linear-gradient(135deg, #e5c07b, #b8860b)',
  'linear-gradient(135deg, #c678dd, #7c3a9e)',
  'linear-gradient(135deg, #56b6c2, #1a7a8a)',
  'linear-gradient(135deg, #e06c75, #b33b3b)',
  'linear-gradient(135deg, #d4a0f0, #8b5cf6)',
  'linear-gradient(135deg, #7ee787, #2d7d46)',
]
function iconGradient(name: string): string {
  let hash = 0
  for (let i = 0; i < name.length; i++) {
    hash = ((hash << 5) - hash) + name.charCodeAt(i)
    hash |= 0
  }
  return iconPalette[Math.abs(hash) % iconPalette.length]
}

function currentRangeParams(): DashboardRangeParams {
  if (rangeKey.value === 'custom') {
    return { range: 'custom', from: fromDate.value, to: toDate.value }
  }
  return { range: rangeKey.value }
}

async function fetchStats() {
  if (rangeInvalid.value) return
  try {
    const res = await dashboardApi.getStats(currentRangeParams())
    stats.value = res.data
  } catch {
    // stats will show empty values
  }
}

// 时间段切换时的加载：先置 loading 再拉取，避免旧数据残留（脏读）；
// 60s 轮询与 keep-alive 恢复仍走原始 fetchStats，保持静默不闪烁
async function refreshStatsOnSwitch() {
  loading.value = true
  try {
    await fetchStats()
  } finally {
    loading.value = false
  }
}

// 快捷时间段切换时同步起止日期，便于切到「自定义」时以此为初始区间
function syncRangeDates() {
  if (rangeKey.value === 'custom') return
  const today = todayStr()
  const d = new Date()
  if (rangeKey.value === 'today') {
    fromDate.value = today
    toDate.value = today
  } else if (rangeKey.value === 'week') {
    const wd = d.getDay() === 0 ? 7 : d.getDay()
    const monday = new Date(d.getFullYear(), d.getMonth(), d.getDate() - wd + 1)
    fromDate.value = toISO(monday)
    toDate.value = today
  } else if (rangeKey.value === 'month') {
    fromDate.value = toISO(new Date(d.getFullYear(), d.getMonth(), 1))
    toDate.value = today
  }
}

function toISO(d: Date): string {
  const y = d.getFullYear()
  const m = String(d.getMonth() + 1).padStart(2, '0')
  const day = String(d.getDate()).padStart(2, '0')
  return `${y}-${m}-${day}`
}

// 切换时间段时先置 loading 再拉取，避免旧数据残留造成脏读
async function fetchStatsWithLoading() {
  loading.value = true
  try {
    await fetchStats()
  } finally {
    loading.value = false
  }
}

watch(rangeKey, () => {
  syncRangeDates()
  fetchStatsWithLoading()
})
watch([fromDate, toDate], () => {
  if (rangeKey.value === 'custom') fetchStatsWithLoading()
})

// 供 keep-alive 按组件名缓存（Layout.vue cachedViews）
defineOptions({ name: 'Dashboard' })

// onMounted 负责首次加载（保证页面一定有数据，不依赖 keep-alive 是否命中）；
// onActivated 仅在 keep-alive 缓存恢复（菜单切回）时刷新数据，首次跳过避免重复加载
let activatedCount = 0
onMounted(async () => {
  loading.value = true
  syncRangeDates()
  await fetchStats()
  loading.value = false
  // 60 秒轮询；页面隐藏（切到其它标签页）时跳过，避免无谓的数据库聚合压力
  dashboardRefreshTimer = setInterval(() => {
    if (!document.hidden) fetchStats()
  }, 60000)
})
onActivated(async () => {
  if (activatedCount++ > 0) {
    loading.value = true
    await fetchStats()
    loading.value = false
  }
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

/* ── Header ── */
.dashboard-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 20px;
  gap: 16px;
  flex-wrap: nowrap;
}

.dashboard-header .header-left h2 {
  font-size: 24px;
  font-weight: 700;
  color: var(--text-primary);
  letter-spacing: -0.02em;
}

.dashboard-header .header-left p {
  font-size: 13px;
  color: var(--text-muted);
  margin-top: 4px;
}

.header-left {
  width: 100%;
  min-width: 0;
}
.header-left .title-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  flex-wrap: nowrap;
}

.header-controls {
  display: flex;
  align-items: center;
  gap: 6px;
  flex-wrap: wrap;
}

/* ── 时间段下拉选择器 ── */
.period-dropdown {
  position: relative;
  display: inline-block;
}
.period-trigger {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 5px 10px;
  border: 1px solid var(--border-color);
  border-radius: var(--radius, 6px);
  background: var(--bg-secondary);
  color: var(--text-secondary);
  font-size: 13px;
  font-weight: 500;
  cursor: pointer;
  transition: all 0.15s;
  font-family: inherit;
  line-height: 1.5;
}
.period-trigger:hover {
  border-color: color-mix(in srgb, var(--text-muted) 40%, var(--border-color));
  color: var(--text-primary);
}
.period-trigger .rotated {
  transform: rotate(180deg);
  transition: transform 0.2s ease;
}

.period-menu {
  position: absolute;
  top: calc(100% + 4px);
  right: 0;
  min-width: 120px;
  background: var(--bg-secondary);
  border: 1px solid var(--border-color);
  border-radius: var(--radius, 6px);
  box-shadow: var(--shadow-md);
  padding: 4px;
  z-index: 100;
  display: flex;
  flex-direction: column;
  gap: 2px;
}
.period-option {
  display: flex;
  align-items: center;
  padding: 6px 10px;
  border: none;
  border-radius: var(--radius-sm, 4px);
  background: transparent;
  color: var(--text-secondary);
  font-size: 13px;
  cursor: pointer;
  transition: all 0.12s;
  font-family: inherit;
  text-align: left;
  white-space: nowrap;
}
.period-option:hover {
  background: var(--bg-hover);
  color: var(--text-primary);
}
.period-option.active {
  background: var(--bg-hover);
  color: var(--accent-blue);
  font-weight: 600;
}

/* 下拉菜单过渡动画 */
.fade-enter-active,
.fade-leave-active {
  transition: opacity 0.15s ease, transform 0.15s ease;
}
.fade-enter-from,
.fade-leave-to {
  opacity: 0;
  transform: translateY(-4px);
}

/* 自定义时间段弹框 */
.custom-range-form {
  display: flex;
  flex-direction: column;
  gap: 14px;
}
.custom-range-error {
  margin: 0;
  font-size: 12px;
  color: var(--accent-red);
}

/* ── Stats Grid：桌面宽度下四张卡片保持同一行 ── */
.stats-grid {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 16px;
  margin-bottom: 20px;
}

.stat-card {
  position: relative;
  background: var(--bg-secondary);
  border: 1px solid var(--border-color);
  border-radius: var(--radius-md, 12px);
  padding: 16px 18px;
  box-shadow: var(--shadow-sm);
  transition: box-shadow 0.2s ease, transform 0.2s ease, border-color 0.2s ease;
  display: flex;
  flex-direction: column;
  gap: 12px;
  min-width: 0;
  overflow: hidden;
}

.stat-card:hover {
  box-shadow: var(--shadow-md);
  transform: translateY(-2px);
}

.stat-top {
  display: flex;
  align-items: center;
  gap: 12px;
  min-width: 0;
}

.stat-icon {
  width: 42px;
  height: 42px;
  border-radius: 10px;
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
}
.stat-card--blue .stat-icon { background: color-mix(in srgb, var(--accent-blue) 16%, transparent); color: var(--accent-blue); }
.stat-card--green .stat-icon { background: color-mix(in srgb, var(--accent-green) 16%, transparent); color: var(--accent-green); }
.stat-card--purple .stat-icon { background: rgba(188,140,255,0.16); color: #bc8cff; }
.stat-card--yellow .stat-icon { background: color-mix(in srgb, var(--accent-yellow, #d29922) 16%, transparent); color: var(--accent-yellow, #d29922); }

.stat-head { min-width: 0; flex: 1; }
.stat-label { font-size: 12px; color: var(--text-muted); margin-bottom: 2px; font-weight: 500; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.stat-value { font-size: 26px; font-weight: 700; line-height: 1.15; color: var(--text-primary); white-space: nowrap; }
.stat-value small { font-size: 13px; font-weight: 400; color: var(--text-muted); margin-left: 2px; }

/* 卡片右侧迷你趋势线（随时间段变化的稀疏折线） */
.stat-spark {
  width: 92px;
  height: 34px;
  flex-shrink: 0;
  overflow: visible;
}
.stat-spark .spark-line { fill: none; stroke-width: 1.6; vector-effect: non-scaling-stroke; }
.stat-spark .spark-area { stroke: none; }
.stat-card--blue .spark-line { stroke: var(--accent-blue); }
.stat-card--blue .spark-area { fill: color-mix(in srgb, var(--accent-blue) 14%, transparent); }
.stat-card--green .spark-line { stroke: var(--accent-green); }
.stat-card--green .spark-area { fill: color-mix(in srgb, var(--accent-green) 14%, transparent); }
.stat-card--purple .spark-line { stroke: #bc8cff; }
.stat-card--purple .spark-area { fill: rgba(188,140,255,0.14); }
.stat-card--yellow .spark-line { stroke: var(--accent-yellow, #d29922); }
.stat-card--yellow .spark-area { fill: color-mix(in srgb, var(--accent-yellow, #d29922) 14%, transparent); }

.stat-bottom {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  flex-wrap: wrap;
  min-height: 20px;
}
.stat-meta {
  font-size: 12px;
  color: var(--text-muted);
  display: inline-flex;
  align-items: center;
  gap: 4px;
}
.stat-badges { display: inline-flex; gap: 4px; }

/* 环比（较昨日 / 较上周同期 / 较上月同期 / 较上期） */
.stat-change {
  font-size: 12px; font-weight: 600;
  display: inline-flex; align-items: center; gap: 3px;
  white-space: nowrap;
}
.stat-change.up { color: var(--accent-green); }
.stat-change.down { color: var(--accent-red); }
.stat-change .change-arrow { font-size: 10px; }

.stat-card-loading {
  min-height: 120px;
  display: flex;
  align-items: center;
  justify-content: center;
}

/* ── Rankings ── */
.rank-grid { display: grid; grid-template-columns: repeat(2, 1fr); gap: 16px; }

.rank-card { display: flex; flex-direction: column; }

.view-all-link {
  margin-left: auto;
  font-size: 13px;
  color: var(--accent-blue);
  text-decoration: none;
}
.view-all-link:hover { text-decoration: underline; }

.rank-state {
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 40px 0;
  color: var(--text-muted);
  font-size: 13px;
}

.table-scroll {
  overflow-y: auto;
  max-height: 320px;
}
.table-scroll::-webkit-scrollbar {
  width: 6px;
}
.table-scroll::-webkit-scrollbar-thumb {
  background: var(--bg-hover);
  border-radius: 3px;
}

.rank-table {
  width: 100%;
  border-collapse: collapse;
  font-size: 13px;
}
.rank-table thead th {
  text-align: left;
  font-size: 12px;
  font-weight: 500;
  color: var(--text-muted);
  padding: 10px 12px;
  border-bottom: 1px solid var(--border-color);
  white-space: nowrap;
}
.rank-table thead th.col-num,
.rank-table thead th.col-rate { text-align: left; }
.rank-table tbody td {
  padding: 10px 12px;
  border-bottom: 1px solid color-mix(in srgb, var(--border-color) 60%, transparent);
  color: var(--text-primary);
  vertical-align: middle;
}
.rank-table tbody tr:last-child td { border-bottom: none; }
.rank-table tbody tr:hover { background: var(--bg-hover); }

.col-idx { width: 34px; color: var(--text-muted); font-variant-numeric: tabular-nums; }
.col-num { width: 84px; font-variant-numeric: tabular-nums; white-space: nowrap; }
.col-rate { width: 150px; }

.btn-icon {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  height: 24px;
  width: 24px;
  border-radius: var(--radius-sm);
  color: var(--text-muted);
  background: transparent;
  border: none;
  cursor: pointer;
  transition: all 0.15s;
  padding: 0;
}
.btn-icon:hover:not(:disabled) {
  color: var(--text-primary);
  background: var(--bg-hover);
}
.btn-icon:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}
.btn-icon .spinning {
  animation: spin 1s linear infinite;
}
@keyframes spin {
  from { transform: rotate(0deg); }
  to { transform: rotate(360deg); }
}

.rank-name-cell { display: flex; align-items: center; gap: 8px; min-width: 0; }
.rank-avatar {
  width: 22px; height: 22px;
  border-radius: 6px;
  display: inline-flex; align-items: center; justify-content: center;
  font-size: 11px; font-weight: 700; color: #fff;
  flex-shrink: 0;
}
.rank-name-text { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.rank-channel-tag { color: var(--text-muted); font-size: 12px; }

.rate-cell { display: flex; align-items: center; gap: 8px; }
.rate-text { width: 46px; flex-shrink: 0; font-variant-numeric: tabular-nums; }
.rate-bar {
  flex: 1;
  height: 6px;
  border-radius: 9999px;
  background: var(--bg-primary);
  overflow: hidden;
  min-width: 40px;
}
.rate-bar-fill {
  display: block;
  height: 100%;
  border-radius: 9999px;
  background: linear-gradient(90deg, #3fb950, #56d364);
  transition: width 0.3s;
}

/* ── Responsive ── */
@media (max-width: 1200px) {
  .stats-grid { grid-template-columns: repeat(2, 1fr); }
  .rank-grid { grid-template-columns: 1fr; }
}
@media (max-width: 768px) {
  .stats-grid { grid-template-columns: 1fr; }
  .dashboard-header {
    align-items: center;
    gap: 8px;
  }
  .dashboard-header .header-left {
    min-width: 0;
  }
  .dashboard-header .header-left h2 {
    font-size: 20px;
  }
  .period-trigger {
    padding: 4px 8px;
    font-size: 12px;
    gap: 4px;
  }
  .stat-spark { width: 70px; }
  .col-rate { width: 110px; }
}
</style>
