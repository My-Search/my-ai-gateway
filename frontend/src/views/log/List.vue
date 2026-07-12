<template>
  <!-- 使用历史（堆叠柱状图） -->
  <div class="card usage-history-card">
    <div class="card-header">
      <div>
        <div class="card-title">{{ t('log.chart.title') }}</div>
        <div class="card-subtitle">{{ t('log.chart.subtitle') }}</div>
      </div>
      <div class="usage-history-controls">
        <TabSwitch v-model="chartModelType" :tabs="[
          { value: 'entry', label: t('dashboard.entryModel') },
          { value: 'channel', label: t('dashboard.channelModel') },
        ]" class="chart-model-type-switch" />
        <div class="month-nav">
          <button class="month-nav-btn" :disabled="chartLoading" @click="prevChartMonth" :title="t('log.chart.prevMonth')">
            <SvgIcon name="arrow-left" :size="14" />
          </button>
          <span class="month-nav-label">{{ chartMonthLabel }}</span>
          <button class="month-nav-btn" :disabled="chartLoading" @click="nextChartMonth" :title="t('log.chart.nextMonth')">
            <SvgIcon name="arrow-left" :size="14" style="transform: rotate(180deg);" />
          </button>
        </div>
        <select class="form-input form-input-sm chart-filter" v-model="chartModelName" @change="loadUsageChart" :disabled="chartLoading">
          <option value="">{{ t('log.chart.allModels') }}</option>
          <option v-for="m in entryModels" :key="m.id" :value="m.modelName">{{ m.modelName }}</option>
        </select>
        <select class="form-input form-input-sm chart-filter" v-model="chartApiKeyId" @change="loadUsageChart" :disabled="chartLoading">
          <option :value="null">{{ t('log.chart.allKeys') }}</option>
          <option v-for="k in chartApiKeyOptions" :key="k.id" :value="k.id">{{ k.keyName }}</option>
        </select>
      </div>
    </div>

    <div class="chart-wrapper" @mouseleave="hideUsageTooltip">
      <!-- 无数据时叠加空状态提示，图表骨架依然可见 -->
      <div v-if="!usageChartData || usageChartData.models.length === 0" class="chart-empty-overlay">
        {{ t('log.chart.empty') }}
      </div>
      <svg :viewBox="`0 0 ${CHART_SVG_WIDTH} ${CHART_SVG_HEIGHT}`" class="chart-svg" preserveAspectRatio="none">
        <!-- Y 网格线 + 刻度 -->
        <g class="chart-y-axis">
          <line v-for="tick in chartYAxis" :key="`g-${tick.value}`"
                :x1="CHART_PLOT_LEFT" :x2="CHART_PLOT_RIGHT" :y1="tick.y" :y2="tick.y"
                class="chart-grid-line" />
          <text v-for="tick in chartYAxis" :key="`l-${tick.value}`"
                :x="CHART_PLOT_LEFT - 8" :y="tick.y + 4"
                class="chart-y-label" text-anchor="end">
            {{ formatCompactNumber(tick.value) }}
          </text>
        </g>
        <!-- X 标签 -->
        <g class="chart-x-axis">
          <text v-for="lab in chartXLabels" :key="lab.date"
                :x="lab.cx" :y="CHART_PLOT_BOTTOM + 18"
                class="chart-x-label" text-anchor="middle">
            {{ lab.short }}
          </text>
        </g>
        <!-- 柱体（按模型堆叠） -->
        <g v-for="bar in chartBars" :key="`bar-${bar.date}`">
          <rect v-for="(seg, i) in bar.segments" :key="`seg-${bar.date}-${i}`"
                :x="bar.x" :y="seg.y" :width="CHART_BAR_WIDTH" :height="Math.max(seg.h, 0.5)"
                :fill="seg.color" class="chart-bar-seg">
            <title>{{ bar.date }} {{ seg.model }}: {{ seg.value }} tokens</title>
          </rect>
        </g>
        <!-- 透明 hover 探测区（每列一个，用于精确定位 tooltip） -->
        <g>
          <rect v-for="bar in chartBars" :key="`hover-${bar.date}`"
                :x="bar.slotX" :y="CHART_PLOT_TOP"
                :width="bar.slotWidth" :height="CHART_PLOT_HEIGHT"
                fill="transparent" style="cursor: crosshair;"
                @mouseenter="onBarHover(bar, $event)"
                @mousemove="onBarHover(bar, $event)" />
        </g>
      </svg>

      <!-- 图例（无数据时隐藏） -->
      <div v-if="usageChartData?.models?.length" class="chart-legend">
        <div v-for="m in usageChartData.models" :key="`leg-${m}`" class="chart-legend-item">
          <span class="chart-legend-swatch" :style="{ background: modelColor(m) }"></span>
          <span class="chart-legend-name">{{ m }}</span>
        </div>
      </div>

      <!-- Tooltip -->
      <div v-if="usageTooltip.visible"
           class="chart-tooltip"
           :style="{ left: usageTooltip.x + 'px', top: usageTooltip.y + 'px' }">
        <div class="chart-tooltip-date">{{ usageTooltip.date }}</div>
        <div v-if="usageTooltip.rows.length === 0" class="chart-tooltip-empty">—</div>
        <div v-else class="chart-tooltip-rows">
          <div v-for="row in usageTooltip.rows" :key="`tt-${row.model}`" class="chart-tooltip-row">
            <span class="chart-legend-swatch" :style="{ background: row.color }"></span>
            <span class="chart-tooltip-model" :title="row.model">{{ row.model }}</span>
            <span class="chart-tooltip-value">{{ formatCompactNumber(row.value) }}</span>
          </div>
          <div class="chart-tooltip-total">
            <span>{{ chartModelType === 'channel' ? t('log.chart.totalRequests') : t('log.chart.totalTokens') }}</span>
            <span>{{ formatCompactNumber(usageTooltip.total) }}</span>
          </div>
        </div>
      </div>
    </div>
  </div>

  <div class="card">
    <div class="card-header">
      <div class="card-title">
        {{ t('log.list.title') }}
        <span v-if="sseReconnecting" class="badge badge-sse-connecting">{{ t('log.list.connecting') }}</span>
        <span v-else-if="sseConnected" class="badge badge-sse">{{ t('log.list.realtime') }}</span>
        <span class="log-filter-summary" :title="t('log.list.filterSummaryTitle')">{{ logFilterSummary }}</span>
      </div>
      <div style="display:flex;gap:8px;">
        <button class="btn btn-sm btn-ghost" @click="loadLogs" :disabled="loading" :title="t('common.refresh')">
          <SvgIcon name="refresh" :size="14" />
        </button>
      </div>
    </div>
    <div v-if="loading" style="text-align:center;padding:40px;color:var(--text-muted);">{{ t('common.loading') }}</div>

    <template v-for="trace in traces" :key="trace.traceId">
      <div class="log-trace" @click="toggleTrace(trace.traceId)">
        <div class="trace-header">
          <span class="trace-id" :title="trace.traceId">{{ shortenId(trace.traceId) }}</span>
          <span class="trace-model">
            <code class="model-tag">{{ trace.modelName || '-' }}</code>
          </span>
          <span class="trace-stats">
            <span v-if="trace.failCount > 0" class="badge badge-danger">{{ t('log.list.fail') }}</span>
            <span v-else-if="trace.successCount > 0" class="badge badge-success">{{ t('log.list.success') }}</span>
            <span v-if="trace.retryCount > 0" class="badge badge-warning">{{ t('log.list.retry', { count: trace.retryCount }) }}</span>
          </span>
          <span class="trace-time">{{ formatDateTime(trace.startTime) }}</span>
          <span class="trace-duration" v-if="trace.totalTimeMs > 0">{{ trace.totalTimeMs }}ms</span>
          <button v-if="hasRequestData(trace.traceId)" class="btn btn-sm btn-ghost trace-view-btn" @click.stop="openRequestView(trace.traceId)">
            <SvgIcon name="code" :size="12" /> {{ t('log.list.viewRequest') }}
          </button>
          <span class="trace-toggle">{{ expandedTraces.has(trace.traceId) ? '▲' : '▼' }}</span>
        </div>
      </div>
      <div v-if="expandedTraces.has(trace.traceId)" class="trace-detail">
        <div v-for="(group, gIdx) in groupedTraceLogs.get(trace.traceId) || []" :key="group.key + '-' + gIdx" class="log-entry" :class="{ 'log-entry-clickable': group.logs.some(l => l.message) }" :style="{ paddingLeft: `calc(var(--indent-base, 12px) + ${gIdx} * var(--indent-step, 16px))` }" @click.stop="openLogDetail(group)">
          <PhaseBadge :phase="group.logs[0].phase" :count="group.logs.length > 1 ? group.logs.length : undefined" />
          <span class="log-info">
            <template v-if="group.logs[0].channelName">{{ group.logs[0].channelName }}/</template>
            <template v-if="group.logs[0].apiKeyName">{{ group.logs[0].apiKeyName }}/</template>
            <template v-if="group.logs[0].channelModelName">{{ group.logs[0].channelModelName }}</template>
            <template v-else-if="group.logs[0].modelName">{{ group.logs[0].modelName }}</template>
            {{ groupDurationText(group) }}
            <span v-if="group.logs[0].message" class="log-message" :class="{ 'log-message-error': group.logs[0].phase === 'fail' }"> — {{ group.logs[0].message }}</span>
          </span>
          <span class="log-time">{{ formatTime(group.logs[group.logs.length - 1].createdAt) }}</span>
        </div>
      </div>
    </template>

    <div v-if="!loading && !traces.length" class="empty-state">{{ t('log.list.empty') }}</div>

    <div v-if="hasMore" ref="loadMoreTrigger" class="load-more">
      <span v-if="loadingMore">{{ t('common.loading') }}</span>
      <span v-else>{{ t('log.list.loadMore') }}</span>
    </div>
    <div v-if="!hasMore && traces.length > 0" class="load-more">
      {{ t('log.list.loadedAll').replace('{total}', total) }}
    </div>
  </div>

  <Dialog
    v-model="visible"
    :title="title"
    :type="type"
    :confirm-class="confirmClass"
    @confirm="onConfirm"
  >
    <pre class="dialog-pre">{{ message }}</pre>
  </Dialog>

  <Dialog
    v-model="requestDialogVisible"
    :title="requestDialogTitle"
    type="alert"
    confirm-class="btn-primary"
    :width="requestDialogWidth"
  >
    <div class="request-viewer">
      <div v-if="requestDataLoading" class="request-loading">
        <LoadingSpinner :text="t('common.loading')" />
      </div>
      <template v-else>
        <div v-if="requestHeadersText" class="request-section">
          <div class="request-section-title">{{ t('log.list.requestHeaders') }}</div>
          <div class="request-pre">
            <JsonTreeViewer :json="requestHeadersText" />
          </div>
        </div>
        <div v-if="requestBodyText" class="request-section">
          <div class="request-section-title">
            <span>{{ t('log.list.requestBody') }}</span>
            <button class="btn-download-json" @click="downloadRequestBody" :title="t('log.list.downloadJson')">
              <SvgIcon name="download" :size="12" />
            </button>
          </div>
          <div class="request-pre">
            <JsonTreeViewer :json="requestBodyText" />
          </div>
        </div>
        <div v-if="!requestHeadersText && !requestBodyText && !requestDataLoading" class="empty-state">
          {{ requestDataExpired ? t('log.list.requestDataExpired') : t('log.list.noRequestData') }}
        </div>
      </template>
    </div>
  </Dialog>
</template>

<script setup lang="ts">
import { ref, computed, onMounted, onUnmounted, reactive, watch } from 'vue'
import { logApi, subscribeLogStream, type LogTrace, type RequestLog, type LogSseSubscription, type LogUsageChart } from '@/api/log'
import { modelApi, type CustomModel } from '@/api/model'
import { apikeyApi, type ApiKey } from '@/api/apikey'
import Dialog from '@/components/common/Dialog.vue'
import JsonTreeViewer from '@/components/common/JsonTreeViewer.vue'
import TabSwitch from '@/components/common/TabSwitch.vue'
import LoadingSpinner from '@/components/common/LoadingSpinner.vue'
import { useI18n } from '@/composables/useI18n'
import { useDialog } from '@/composables/useDialog'
import { formatLocalDateTime, formatLocalFullTime } from '@/utils/date'

const { t } = useI18n()

const traces = ref<LogTrace[]>([])
const expandedTraces = ref(new Set<string>())
const loading = ref(false)
const loadingMore = ref(false)
const sseConnected = ref(false)
const sseReconnecting = ref(false)

// 图表 15s 自动刷新定时器
let chartRefreshTimer: ReturnType<typeof setInterval> | null = null

// SSE 缓冲队列：合并短时间内的多条事件，减少重复计算和渲染次数
const sseBuffer: RequestLog[] = []
let sseFlushTimer: ReturnType<typeof setTimeout> | null = null

function flushSseBuffer() {
  sseFlushTimer = null
  const batch = sseBuffer.splice(0)
  if (batch.length === 0) return
  for (const log of batch) {
    upsertTraceFromSse(log)
  }
}

function scheduleSseFlush() {
  if (sseFlushTimer) return
  sseFlushTimer = setTimeout(flushSseBuffer, 80)
}

// 入口模型列表（供下拉选择）
const entryModels = ref<CustomModel[]>([])
async function fetchEntryModels() {
  try {
    const res = await modelApi.list()
    entryModels.value = res.data
  } catch { /* 静默失败，下拉为空不影响使用 */ }
}

/* ========== 使用历史图表 ========== */

// 月份导航
const chartYear = ref(new Date().getFullYear())
const chartMonth = ref(new Date().getMonth() + 1) // 1-12
const chartModelName = ref('')
const chartModelType = ref<'entry' | 'channel'>('entry')
const chartApiKeyId = ref<number | null>(null)
const usageChartData = ref<LogUsageChart | null>(null)
const chartLoading = ref(false)
const chartApiKeyOptions = ref<ApiKey[]>([])

/** API Key 列表（供图表 Key 下拉） */
async function fetchChartApiKeys() {
  try {
    const res = await apikeyApi.list()
    chartApiKeyOptions.value = res.data
  } catch { /* 静默失败，下拉为空不影响图表 */ }
}

// 月份标签：跟随 i18n
const chartMonthLabel = computed(() =>
  t('log.chart.monthLabel', { year: chartYear.value, month: chartMonth.value })
)

/** 月份翻页：跨年时自动调整年份 */
function prevChartMonth() {
  if (chartMonth.value === 1) {
    chartYear.value -= 1
    chartMonth.value = 12
  } else {
    chartMonth.value -= 1
  }
}
function nextChartMonth() {
  if (chartMonth.value === 12) {
    chartYear.value += 1
    chartMonth.value = 1
  } else {
    chartMonth.value += 1
  }
}

async function loadUsageChart() {
  try {
    const res = await logApi.usageChart({
      year: chartYear.value,
      month: chartMonth.value,
      modelType: chartModelType.value,
      modelName: chartModelName.value || undefined,
      gatewayApiKeyId: chartApiKeyId.value ?? undefined,
    })
    usageChartData.value = res.data
  } catch (e) {
    console.warn('Failed to load usage chart:', e)
    // 失败时保留旧数据，图表不闪烁
  }
}

/* --- SVG 几何常量 --- */
const CHART_SVG_WIDTH = 800
const CHART_SVG_HEIGHT = 280
const CHART_PLOT_LEFT = 60
const CHART_PLOT_RIGHT = CHART_SVG_WIDTH - 16
const CHART_PLOT_TOP = 20
const CHART_PLOT_BOTTOM = CHART_SVG_HEIGHT - 40
const CHART_PLOT_WIDTH = CHART_PLOT_RIGHT - CHART_PLOT_LEFT
const CHART_PLOT_HEIGHT = CHART_PLOT_BOTTOM - CHART_PLOT_TOP
const CHART_BAR_WIDTH = 14

/**
 * 固定 9 色调色板（与后端按月总用量降序对应：TopN 模型固定拿主色，保证视觉稳定）。
 * <p>
 * 色系选择要点：
 * <ul>
 *   <li>深色主题下需保证足够亮度，避开低饱和/低明度的"浊色"，否则柱体在背景上几乎不可见。</li>
 *   <li>色相分布均匀，相邻槽位颜色对比足够，避免堆叠时产生混淆。</li>
 *   <li>保持中明度（55-70%），兼顾浅色主题下的可读性。</li>
 * </ul>
 */
const CHART_COLOR_PALETTE = [
  '#5b9bd5', // sky blue
  '#c98a5a', // sienna / amber
  '#7ab55c', // sage green
  '#d87093', // rose pink
  '#4ec5b9', // cyan-teal
  '#9b86cc', // light purple
  '#e57373', // coral red
  '#c5b358', // mustard / gold
  '#7986cb', // indigo
]

function modelColor(model: string): string {
  if (model === '请求失败') return '#e57373'
  if (!usageChartData.value) return CHART_COLOR_PALETTE[0]
  const idx = usageChartData.value.models.indexOf(model)
  return CHART_COLOR_PALETTE[(idx >= 0 ? idx : 0) % CHART_COLOR_PALETTE.length]
}

/** 取"漂亮"的刻度步长（1/2/5 * 10^n），用于 Y 轴自适应。 */
function niceStep(raw: number): number {
  if (raw <= 0) return 1
  const exp = Math.floor(Math.log10(raw))
  const base = Math.pow(10, exp)
  const ratio = raw / base
  let nice: number
  if (ratio < 1.5) nice = 1
  else if (ratio < 3) nice = 2
  else if (ratio < 7) nice = 5
  else nice = 10
  return nice * base
}

/** Y 轴 5 个等距刻度（0/25/50/75/100%），无数据时仍显示 5 条网格线让图表骨架可见。 */
const chartYAxis = computed<{ value: number; y: number }[]>(() => {
  if (!usageChartData.value || usageChartData.value.maxValue === 0) {
    // 无数据时显示 0-4 共 5 条网格线，让图表框架依然可见
    return [0, 1, 2, 3, 4].map(i => ({
      value: i,
      y: CHART_PLOT_BOTTOM - (i / 4) * CHART_PLOT_HEIGHT,
    }))
  }
  const max = usageChartData.value.maxValue
  const step = niceStep(max / 4)
  const top = step * 4
  return [0, 1, 2, 3, 4].map(i => ({
    value: i * step,
    y: CHART_PLOT_BOTTOM - (i * step / top) * CHART_PLOT_HEIGHT,
  }))
})

/** 每天的堆叠柱信息（含每个模型段的几何信息）。 */
const chartBars = computed(() => {
  if (!usageChartData.value) return []
  const { days, models, values } = usageChartData.value
  if (days.length === 0) return []
  const slotWidth = CHART_PLOT_WIDTH / days.length
  const top = chartYAxis.value[4]?.value ?? 0
  return days.map((date, dayIdx) => {
    const slotX = CHART_PLOT_LEFT + dayIdx * slotWidth
    const barX = slotX + (slotWidth - CHART_BAR_WIDTH) / 2
    const segments: { model: string; y: number; h: number; color: string; value: number }[] = []
    let stackY = CHART_PLOT_BOTTOM
    let totalValue = 0
    for (const model of models) {
      const value = values[model]?.[dayIdx] ?? 0
      if (value === 0) continue
      const h = top > 0 ? (value / top) * CHART_PLOT_HEIGHT : 0
      stackY -= h
      segments.push({ model, y: stackY, h, color: modelColor(model), value })
      totalValue += value
    }
    return { date, dayIdx, x: barX, slotX, slotWidth, segments, totalValue }
  })
})

/** X 轴标签：每两天一个 + 末日，确保不重叠。 */
const chartXLabels = computed<{ date: string; cx: number; short: string }[]>(() => {
  if (!usageChartData.value || usageChartData.value.days.length === 0) return []
  const days = usageChartData.value.days
  const slotWidth = CHART_PLOT_WIDTH / days.length
  const labels: { date: string; cx: number; short: string }[] = []
  for (let i = 0; i < days.length; i++) {
    if (i % 2 !== 0 && i !== days.length - 1) continue
    const slotX = CHART_PLOT_LEFT + i * slotWidth
    const cx = slotX + slotWidth / 2
    const parts = days[i].split('-')
    const short = parts.length === 3 ? `${parseInt(parts[1])}/${parseInt(parts[2])}` : days[i]
    labels.push({ date: days[i], cx, short })
  }
  return labels
})

/** 压缩数字显示（K / M）。 */
function formatCompactNumber(n: number): string {
  if (!isFinite(n) || n === 0) return '0'
  if (Math.abs(n) >= 1_000_000) return (n / 1_000_000).toFixed(1) + 'M'
  if (Math.abs(n) >= 1_000) return (n / 1_000).toFixed(1) + 'K'
  return Math.round(n).toString()
}

/* --- Tooltip --- */
const usageTooltip = reactive({
  visible: false,
  x: 0,
  y: 0,
  date: '',
  rows: [] as { model: string; color: string; value: number }[],
  total: 0,
})

function onBarHover(bar: { date: string; segments: { model: string; color: string; value: number }[]; totalValue: number }, evt: MouseEvent) {
  const target = evt.currentTarget as Element
  const container = target.closest('.chart-wrapper') as HTMLElement | null
  if (!container) return
  const rect = container.getBoundingClientRect()
  // 限定 tooltip 在容器内（避免超出右侧）
  const x = Math.min(evt.clientX - rect.left + 12, rect.width - 220)
  const y = Math.min(evt.clientY - rect.top + 12, rect.height - 140)
  usageTooltip.x = Math.max(0, x)
  usageTooltip.y = Math.max(0, y)
  usageTooltip.date = bar.date
  usageTooltip.rows = bar.segments.map(s => ({ model: s.model, color: s.color, value: s.value }))
  usageTooltip.total = bar.totalValue
  usageTooltip.visible = bar.segments.length > 0
}

function hideUsageTooltip() {
  usageTooltip.visible = false
}

// 分页状态
const offset = ref(0)
const limit = 50
const hasMore = ref(true)
const total = ref(0)
const loadMoreTrigger = ref<HTMLElement | null>(null)
let observer: IntersectionObserver | null = null

const { visible, title, message, type, confirmClass, open, onConfirm } = useDialog()

/* ========== 原始请求查看对话框（按需加载） ========== */
const requestDialogVisible = ref(false)
const requestDialogTitle = ref('')
const requestDialogWidth = ref('720px')
const requestHeadersText = ref('')
const requestBodyText = ref('')
const requestDataExpired = ref(false)
const requestDataLoading = ref(false)

/** 判断 trace 是否包含原始请求数据（由后端根据 save level 决定，request data 通过 API 按需加载） */
function hasRequestData(traceId: string): boolean {
  const trace = traces.value.find(t => t.traceId === traceId)
  return trace?.hasRequestData ?? false
}

/** 打开原始请求查看对话框（通过 API 按需加载原始请求数据） */
async function openRequestView(traceId: string) {
  const trace = traces.value.find(t => t.traceId === traceId)
  if (!trace) return
  const startLog = trace.logs.find(l => l.phase === 'start')
  if (!startLog) {
    requestDataExpired.value = true
    requestHeadersText.value = ''
    requestBodyText.value = ''
    requestDialogTitle.value = `原始请求 [${shortenId(traceId)}]`
    requestDialogVisible.value = true
    return
  }

  requestDataExpired.value = false
  requestDataLoading.value = true
  requestDialogTitle.value = `原始请求 [${shortenId(traceId)}]`
  requestHeadersText.value = ''
  requestBodyText.value = ''
  requestDialogVisible.value = true

  try {
    const res = await logApi.getRequestData(startLog.id)
    const data = res.data
    if (data.requestHeaders || data.requestBody) {
      requestHeadersText.value = data.requestHeaders ?? ''
      requestBodyText.value = data.requestBody ?? ''
    } else {
      // requestHeaders 和 requestBody 均为 null → 数据已被 TTL 清理
      requestDataExpired.value = true
    }
  } catch (e) {
    console.warn('Failed to load request data:', e)
    requestDataExpired.value = true
  } finally {
    requestDataLoading.value = false
  }
}

/** 下载请求体为 JSON 文件 */
function downloadRequestBody() {
  if (!requestBodyText.value) return
  try {
    // 尝试格式化为美观 JSON
    const formatted = JSON.stringify(JSON.parse(requestBodyText.value), null, 2)
    const blob = new Blob([formatted], { type: 'application/json' })
    const url = URL.createObjectURL(blob)
    const a = document.createElement('a')
    a.href = url
    a.download = `request-body-${Date.now()}.json`
    a.click()
    URL.revokeObjectURL(url)
  } catch {
    // 若非合法 JSON 则直接下载原始文本
    const blob = new Blob([requestBodyText.value], { type: 'text/plain' })
    const url = URL.createObjectURL(blob)
    const a = document.createElement('a')
    a.href = url
    a.download = `request-body-${Date.now()}.txt`
    a.click()
    URL.revokeObjectURL(url)
  }
}

/** 打开日志详情弹框，完整展示日志消息内容 */
function openLogDetail(group: { key: string; logs: RequestLog[] }) {
  if (!group.logs.some(l => l.message)) return
  const first = group.logs[0]
  const parts: string[] = []
  parts.push(`阶段: ${first.phase}`)
  const modelParts: string[] = []
  if (first.channelName) modelParts.push(first.channelName)
  if (first.apiKeyName) modelParts.push(first.apiKeyName)
  if (first.channelModelName) modelParts.push(first.channelModelName)
  else if (first.modelName) modelParts.push(first.modelName)
  if (modelParts.length) parts.push(`路由: ${modelParts.join(' / ')}`)

  // 构建完整消息内容
  let detailText = ''
  for (const log of group.logs) {
    if (log.message) {
      if (detailText) detailText += '\n---\n'
      detailText += log.message
    }
  }
  if (detailText) {
    parts.push('')
    parts.push(detailText)
  }

  // 显示耗时
  const durations = group.logs
    .filter(l => l.responseTimeMs != null && l.responseTimeMs > 0)
    .map(l => l.responseTimeMs)
  if (durations.length) {
    parts.push(`\n耗时: ${durations.map(d => d + 'ms').join(' / ')}`)
  }

  open({
    title: `请求详情 [${first.phase}]`,
    message: parts.join('\n'),
    type: 'alert',
    confirmClass: 'btn-primary',
  })
}

let eventSource: LogSseSubscription | null = null

function startSse() {
  stopSse()
  eventSource = subscribeLogStream({
    onLog: (log) => {
      sseBuffer.push(log)
      scheduleSseFlush()
    },
    onError: () => {
      sseConnected.value = false
    },
    onReconnecting: () => {
      sseConnected.value = false
      sseReconnecting.value = true
    },
    onReconnected: () => {
      sseConnected.value = true
      sseReconnecting.value = false
    },
  })
}

function stopSse() {
  sseConnected.value = false
  sseReconnecting.value = false
  eventSource?.close()
  eventSource = null
}

/** 判断 trace 是否还在进行中（尚未成功/失败终结） */
function isTraceInProgress(trace: LogTrace): boolean {
  return !trace.logs.some(l => l.phase === 'success' || l.phase === 'fail')
}

/** 将 SSE 推送的单条日志合并到 traces 列表中 */
function upsertTraceFromSse(log: RequestLog) {
  let trace = traces.value.find(t => t.traceId === log.traceId)
  if (trace) {
    // 防止已存在（SSE 重连后可能重复收到）
    if (trace.logs.some(l => l.id === log.id)) return
    const wasInProgress = isTraceInProgress(trace)
    trace.logs.push(log)
    trace.logs.sort((a, b) => (a.createdAt || '').localeCompare(b.createdAt || ''))
    recalcTrace(trace)
    if (wasInProgress && !isTraceInProgress(trace)) {
      // 由进行中刚完成 → 自动折叠
      expandedTraces.value.delete(trace.traceId)
    } else if (isTraceInProgress(trace)) {
      // 仍进行中 → 展开
      expandedTraces.value.add(trace.traceId)
    }
  } else {
    trace = {
      traceId: log.traceId,
      logs: [log],
      retryCount: 0,
      successCount: 0,
      failCount: 0,
      modelName: log.modelName || '',
      totalTimeMs: 0,
      hasRequestData: !!(log.requestHeaders || log.requestBody),
    }
    recalcTrace(trace)
    traces.value.unshift(trace)
    // 新推送的 trace 必然是进行中的，默认展开
    expandedTraces.value.add(trace.traceId)
  }
  // 收到数据说明连接活跃，清除重连状态
  sseConnected.value = true
  sseReconnecting.value = false
}

/** 重新计算 trace 的统计摘要 */
function recalcTrace(trace: LogTrace) {
  trace.retryCount = trace.logs.filter(l => l.phase === 'retry').length
  trace.successCount = trace.logs.filter(l => l.phase === 'success').length
  trace.failCount = trace.logs.filter(l => l.phase === 'fail').length
  trace.totalTimeMs = trace.logs
    .filter(l => (l.phase === 'success' || l.phase === 'fail') && l.responseTimeMs != null)
    .reduce((sum, l) => sum + (l.responseTimeMs || 0), 0)
  const first = trace.logs[0]
  const last = trace.logs[trace.logs.length - 1]
  trace.startTime = first?.createdAt
  trace.endTime = last?.createdAt
  trace.modelName = trace.logs.find(l => l.modelName)?.modelName || ''

  // 如果 trace 已完成（有终态），且成功无重试，检查是否需要清除 hasRequestData
  // 后端 save level=warn/error 会清理成功无重试的请求数据
  if (!isTraceInProgress(trace) && trace.successCount > 0 && trace.retryCount === 0 && trace.failCount === 0) {
    trace.hasRequestData = false
  }

  // 仅更新当前 trace 的分组缓存，避免全量重计算
  updateTraceGroups(trace)

  // 按最新时间排序
  traces.value.sort((a, b) => {
    const ta = a.endTime || a.startTime || ''
    const tb = b.endTime || b.startTime || ''
    return tb.localeCompare(ta)
  })
}

/* ========== 工具函数 ========== */

/** 获取日志条目的模型标识（channelName/apiKeyName/channelModelName 或 modelName） */
function getModelKey(log: RequestLog): string {
  if (log.channelName && log.channelModelName) {
    if (log.apiKeyName) return `${log.channelName}/${log.apiKeyName}/${log.channelModelName}`
    return `${log.channelName}/${log.channelModelName}`
  }
  return log.modelName || '-'
}

/** 将 trace 的 logs 按连续相同 phase+model 分组，用于合并显示重试 */
function groupLogs(logs: RequestLog[]) {
  const groups: { key: string; logs: RequestLog[] }[] = []
  for (const log of logs) {
    const key = `${log.phase}::${getModelKey(log)}`
    const last = groups[groups.length - 1]
    if (last && last.key === key) {
      last.logs.push(log)
    } else {
      groups.push({ key, logs: [log] })
    }
  }
  return groups
}

/**
 * 获取 group 的耗时展示文本。
 * 后端会在 retry 失败时记录本次尝试的耗时、在 success/fail 时记录最后一次的耗时，
 * 因此一个 group 内可能有 1..N 个耗时值：单一 phase 行展示 1 个，合并的 (xN) retry 行展示 N 个，
 * 用 " / " 拼接以体现"每次真实模型请求的用时"。
 * 无耗时数据时返回空字符串（模板中可直接 {{ }} 插值，无需额外 v-if 判断）。
 */
function groupDurationText(group: { logs: RequestLog[] }): string {
  const durations = group.logs
    .filter(l => l.responseTimeMs != null && l.responseTimeMs > 0)
    .map(l => l.responseTimeMs as number)
  if (durations.length === 0) return ''
  return ' · ' + durations.map(d => d + 'ms').join(' / ')
}

/** 每个 trace 的分组结果缓存，仅更新变更的 trace，避免全量重计算 */
const groupedTraceLogs = reactive(new Map<string, { key: string; logs: RequestLog[] }[]>())

function updateTraceGroups(trace: LogTrace) {
  groupedTraceLogs.set(trace.traceId, groupLogs(trace.logs))
}

function rebuildAllTraceGroups() {
  groupedTraceLogs.clear()
  for (const trace of traces.value) {
    groupedTraceLogs.set(trace.traceId, groupLogs(trace.logs))
  }
}

function shortenId(id: string) {
  if (!id) return '-'
  return id.length > 16 ? id.substring(0, 8) + '...' : id
}

function formatDateTime(dateStr?: string) {
  return formatLocalDateTime(dateStr)
}

function formatTime(dateStr: string) {
  return formatLocalFullTime(dateStr)
}

function toggleTrace(id: string) {
  if (expandedTraces.value.has(id)) {
    expandedTraces.value.delete(id)
  } else {
    expandedTraces.value.add(id)
  }
}

/**
 * 从图表状态派生日志列表的过滤条件。
 * <p>
 * 设计要点：日志列表与图表共用同一组筛选器（chartYear / chartMonth / chartModelName / chartApiKeyId），
 * 保证"看哪个时间段的图表 / 哪些模型的用量"与"看哪些日志"始终一致。月份翻页/模型/Key 一变，
 * 日志列表立即按新条件重拉，无需用户额外点击。
 * </p>
 */
function getCurrentFilters(): import('@/api/log').LogFilters {
  const filters: import('@/api/log').LogFilters = {}
  if (chartModelName.value.trim()) filters.modelName = chartModelName.value.trim()
  if (chartApiKeyId.value != null) filters.gatewayApiKeyId = chartApiKeyId.value
  // 月份 → 半开区间 [since, until)：本月 1 日 00:00 → 次月 1 日 00:00
  const since = new Date(chartYear.value, chartMonth.value - 1, 1)
  const until = new Date(chartYear.value, chartMonth.value, 1)
  filters.startTime = since.toISOString()
  filters.endTime = until.toISOString()
  return filters
}

async function loadLogs() {
  loading.value = true
  offset.value = 0
  hasMore.value = true
  try {
    const filters = getCurrentFilters()
    const res = await logApi.list(0, limit, filters)
    traces.value = res.data.data
    total.value = res.data.total
    hasMore.value = res.data.hasMore
    offset.value = res.data.data.length
    // 初始加载时，进行中的 trace 默认展开
    for (const t of traces.value) {
      if (isTraceInProgress(t)) {
        expandedTraces.value.add(t.traceId)
      }
    }
    // 重建全部分组缓存
    rebuildAllTraceGroups()
  } catch (e: any) {
    open({ title: t('error.loadFailed'), message: e.message })
  } finally {
    loading.value = false
  }
}

async function loadMoreLogs() {
  if (loadingMore.value || !hasMore.value) return
  loadingMore.value = true
  try {
    const filters = getCurrentFilters()
    const res = await logApi.list(offset.value, limit, filters)
    const newTraces = res.data.data
    // 避免重复添加（SSE 可能已提前插入相同 trace）
    const existingIds = new Set(traces.value.map(t => t.traceId))
    const uniqueNewTraces = newTraces.filter(t => !existingIds.has(t.traceId))
    traces.value.push(...uniqueNewTraces)
    // 为新加载的 trace 建立分组缓存
    for (const t of uniqueNewTraces) {
      updateTraceGroups(t)
    }
    hasMore.value = res.data.hasMore
    // offset 按 backend 返回总数递增（与后端分页语义对齐）
    offset.value += newTraces.length
  } catch (e) {
    console.warn('Failed to load more logs:', e)
  } finally {
    loadingMore.value = false
  }
}

function cleanLogs() {
  open({
    title: t('common.confirm'),
    message: t('log.list.cleanConfirm'),
    type: 'confirm',
    confirmClass: 'btn-danger',
    onConfirm: async () => {
      try {
        const res = await logApi.clean()
        open({ message: res.data.message || t('log.list.cleanSuccess') })
        await loadLogs()
      } catch (e: any) {
        open({ title: t('common.fail'), message: e.message })
      }
    }
  })
}

/**
 * 模型类型切换时仅重载图表（不影响日志列表过滤条件）。
 */
watch(chartModelType, loadUsageChart)

/**
 * 图表筛选状态变化时，日志列表按新条件重拉。
 * 监听来源：月份翻页、All Models / All Keys 下拉。
 */
watch(
  [chartYear, chartMonth, chartModelName, chartApiKeyId],
  () => {
    // 翻月/换过滤时丢弃已展开状态，避免残留 trace id 跨过滤误命中
    expandedTraces.value.clear()
    loadLogs()
  }
)

/** 日志列表当前应用的过滤条件简述（仅展示用，真实值在 getCurrentFilters 中读取） */
const logFilterSummary = computed(() => {
  const parts: string[] = []
  parts.push(`${chartYear.value}-${String(chartMonth.value).padStart(2, '0')}`)
  if (chartModelName.value) parts.push(chartModelName.value)
  if (chartApiKeyId.value != null) {
    const key = chartApiKeyOptions.value.find(k => k.id === chartApiKeyId.value)
    if (key) parts.push(key.keyName)
  }
  return `· ${parts.join(' · ')}`
})

onMounted(() => {
  fetchEntryModels()
  fetchChartApiKeys()
  loadUsageChart()
  chartRefreshTimer = setInterval(loadUsageChart, 15000)
  loadLogs()
  startSse()

  // 设置无限滚动观察器
  observer = new IntersectionObserver((entries) => {
    if (entries[0].isIntersecting && hasMore.value && !loadingMore.value) {
      loadMoreLogs()
    }
  }, { threshold: 0.1 })

  // 等待 DOM 更新后绑定观察器
  setTimeout(() => {
    if (loadMoreTrigger.value) {
      observer?.observe(loadMoreTrigger.value)
    }
  }, 100)
})

onUnmounted(() => {
  if (chartRefreshTimer) clearInterval(chartRefreshTimer)
  if (sseFlushTimer) clearTimeout(sseFlushTimer)
  stopSse()
  observer?.disconnect()
})
</script>

<style scoped>
.log-trace {
  cursor: pointer; border-bottom: 1px solid var(--border-color);
  transition: background 0.15s;
}
.log-trace:hover { background: var(--bg-hover); }
.trace-header {
  display: flex; align-items: center; gap: 12px; padding: 10px 12px; font-size: 13px;
}
.trace-id { font-family: 'SF Mono', 'Fira Code', monospace; font-size: 12px; color: var(--text-muted); min-width: 100px; }
.trace-model { flex: 0 0 auto; }
.model-tag {
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  max-width: 180px;
  display: inline-block;
}
.trace-stats { display: flex; gap: 4px; flex: 1; }
.trace-time { font-size: 11px; color: var(--text-muted); white-space: nowrap; }
.trace-duration { font-size: 11px; color: var(--text-muted); font-family: monospace; }
.trace-toggle { color: var(--text-muted); font-size: 10px; }

.trace-detail {
  background: var(--bg-primary);
  border-bottom: 1px solid var(--border-color);
  border-left: 2px solid var(--border-color);
  margin-left: 20px;
  --indent-base: 12px;
  --indent-step: 16px;
}
.log-entry {
  padding: 8px 12px;
  border-bottom: 1px solid var(--border-color);
  font-family: 'SF Mono', 'Fira Code', 'Consolas', monospace;
  font-size: 12px;
  line-height: 1.6;
  display: flex;
  align-items: center;
  gap: 8px;
}
.log-entry:last-child { border-bottom: none; }
.log-entry-clickable { cursor: pointer; }
.log-entry-clickable:hover { background: var(--bg-hover); }
.log-info { color: var(--text-secondary); flex: 1; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.log-time { color: var(--text-muted); font-size: 11px; white-space: nowrap; }
.log-message { color: var(--text-muted); font-size: 11px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; max-width: 320px; flex-shrink: 1; }
.log-message-error { color: var(--accent-red); }

.dialog-pre {
  margin: 0;
  padding: 12px;
  border: 1px solid var(--border-color);
  border-radius: 6px;
  font-size: 12px;
  line-height: 1.6;
  white-space: pre-wrap;
  word-break: break-all;
  max-height: 60vh;
  overflow-y: auto;
  color: var(--text-primary);
  font-family: 'SF Mono', 'Fira Code', 'Consolas', monospace;
}

/* ========== 原始请求查看器 ========== */
.request-viewer {
  display: flex;
  flex-direction: column;
  gap: 12px;
  max-height: 70vh;
  overflow-y: auto;
}
.request-section {
  display: flex;
  flex-direction: column;
  gap: 6px;
}
.request-section-title {
  font-size: 12px;
  font-weight: 600;
  color: var(--text-secondary);
  padding: 4px 8px;
  border: 1px solid var(--border-color);
  border-radius: 4px;
  display: flex;
  align-items: center;
  justify-content: space-between;
}
.btn-download-json {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 20px;
  height: 20px;
  border: none;
  background: transparent;
  color: var(--text-muted);
  cursor: pointer;
  border-radius: 3px;
  transition: background 0.15s, color 0.15s;
}
.btn-download-json:hover {
  background: var(--bg-hover);
  color: var(--text-primary);
}
.request-pre {
  margin: 0;
  overflow: auto;
  padding: 8px;
  border: 1px solid var(--border-color);
  border-radius: 6px;
}
.trace-view-btn {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  padding: 2px 8px;
  font-size: 11px;
  white-space: nowrap;
  flex-shrink: 0;
}

.request-loading {
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 40px 20px;
  color: var(--text-muted);
  font-size: 13px;
}

.badge-sse {
  background: color-mix(in srgb, var(--accent-green) 20%, transparent);
  color: var(--accent-green);
  font-size: 11px;
  font-weight: 600;
  padding: 2px 8px;
  border-radius: 3px;
  margin-left: 8px;
  vertical-align: middle;
  animation: pulse-badge 2s ease-in-out infinite;
}
.badge-sse-connecting {
  background: color-mix(in srgb, var(--accent-yellow) 20%, transparent);
  color: var(--accent-yellow);
  font-size: 11px;
  font-weight: 600;
  padding: 2px 8px;
  border-radius: 3px;
  margin-left: 8px;
  vertical-align: middle;
  animation: pulse-badge 1s ease-in-out infinite;
}
@keyframes pulse-badge {
  0%, 100% { opacity: 1; }
  50% { opacity: 0.6; }
}

/* ========== 移动端适配 ========== */
/* 旧的 .filter-bar / .filter-item / .form-input-sm 等样式已删除：日志列表改用图表筛选 */
.log-filter-summary {
  margin-left: 8px;
  font-size: 11px;
  font-weight: 500;
  color: var(--text-muted);
  font-family: 'SF Mono', 'Fira Code', 'Consolas', monospace;
  padding: 2px 8px;
  border: 1px solid var(--border-color);
  border-radius: 3px;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  max-width: 360px;
  display: inline-block;
  vertical-align: middle;
}
.btn-ghost {
  background: transparent;
  border: 1px solid transparent;
  color: var(--text-muted);
  cursor: pointer;
  padding: 4px 10px;
  font-size: 12px;
  border-radius: 4px;
}
.btn-ghost:hover {
  background: var(--bg-hover);
  color: var(--text-primary);
}


.log-list-card .log-filter-summary {
  flex-shrink: 0;
}

@media (max-width: 768px) {
  .trace-header {
    gap: 8px;
    flex-wrap: wrap;
  }
  .model-tag {
    max-width: 120px;
  }
  .trace-detail {
    margin-left: 8px;
    --indent-base: 8px;
    --indent-step: 12px;
  }
  .log-entry {
    padding: 6px 8px;
    gap: 6px;
    font-size: 11px;
  }
  .log-filter-summary {
    max-width: 100%;
    margin-top: 4px;
  }
}

@media (max-width: 480px) {
  .trace-header {
    gap: 4px;
  }
  .model-tag {
    max-width: 80px;
  }
  .log-entry {
    padding: 4px 6px;
    gap: 4px;
    font-size: 10px;
  }
}

.load-more {
  text-align: center;
  padding: 20px;
  color: var(--text-muted);
  font-size: 13px;
  border-top: 1px solid var(--border-color);
}

/* ========== 使用历史图表 ========== */
.usage-history-card {
  position: relative;
}
.usage-history-card .card-header {
  border-bottom: none;
  padding-bottom: 8px;
  margin-bottom: 4px;
  align-items: flex-start;
}
.card-subtitle {
  font-size: 12px;
  color: var(--text-muted);
  margin-top: 4px;
  font-weight: 400;
}
/* 图表右上角模型类型切换 */
.chart-model-type-switch {
  flex-shrink: 0;
}
.usage-history-controls {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}
.month-nav {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  border: 1px solid var(--border-color);
  border-radius: 6px;
  padding: 2px 4px;
}
.month-nav-btn {
  background: transparent;
  border: none;
  padding: 2px 6px;
  cursor: pointer;
  color: var(--text-secondary);
  border-radius: 4px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  transition: background 0.15s;
}
.month-nav-btn:hover:not(:disabled) {
  background: var(--bg-hover);
  color: var(--text-primary);
}
.month-nav-btn:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}
.month-nav-label {
  font-size: 13px;
  font-weight: 600;
  color: var(--text-primary);
  padding: 0 6px;
  min-width: 110px;
  text-align: center;
  font-variant-numeric: tabular-nums;
}
.chart-filter {
  min-width: 140px;
  max-width: 200px;
}

.usage-history-loading {
  text-align: center;
  padding: 60px 20px;
  color: var(--text-muted);
  font-size: 13px;
}
/* 图表空状态覆盖层：叠加在图表 SVG 之上，不隐藏图表骨架 */
.chart-empty-overlay {
  position: absolute;
  inset: 0;
  display: flex;
  align-items: center;
  justify-content: center;
  color: var(--text-muted);
  font-size: 13px;
  pointer-events: none;
  z-index: 5;
}
.chart-wrapper {
  position: relative;
  margin-top: 8px;
}
.chart-svg {
  display: block;
  width: 100%;
  height: 280px;
  user-select: none;
}
.chart-grid-line {
  stroke: var(--border-color);
  stroke-width: 1;
  stroke-dasharray: 3 3;
  opacity: 0.6;
}
.chart-y-label,
.chart-x-label {
  fill: var(--text-muted);
  font-size: 11px;
  font-family: 'SF Mono', 'Fira Code', 'Consolas', monospace;
}
.chart-bar-seg {
  opacity: 0.5;
  transition: opacity 0.15s;
}
.chart-bar-seg:hover {
  opacity: 1;
}

.chart-legend {
  display: flex;
  flex-wrap: wrap;
  gap: 8px 16px;
  padding: 12px 8px 4px;
  justify-content: center;
}
.chart-legend-item {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  font-size: 12px;
  color: var(--text-secondary);
  max-width: 220px;
}
.chart-legend-swatch {
  width: 12px;
  height: 12px;
  border-radius: 2px;
  flex-shrink: 0;
  display: inline-block;
}
.chart-legend-name {
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  font-family: 'SF Mono', 'Fira Code', monospace;
}

.chart-tooltip {
  position: absolute;
  pointer-events: none;
  background: var(--bg-primary);
  border: 1px solid var(--border-color);
  border-radius: 6px;
  padding: 8px 10px;
  min-width: 180px;
  max-width: 280px;
  box-shadow: 0 4px 12px rgba(0, 0, 0, 0.25);
  font-size: 12px;
  z-index: 10;
}
.chart-tooltip-date {
  font-weight: 600;
  color: var(--text-primary);
  margin-bottom: 6px;
  font-family: 'SF Mono', 'Fira Code', monospace;
  font-size: 11px;
}
.chart-tooltip-empty {
  color: var(--text-muted);
  font-style: italic;
}
.chart-tooltip-rows {
  display: flex;
  flex-direction: column;
  gap: 3px;
}
.chart-tooltip-row {
  display: grid;
  grid-template-columns: 12px 1fr auto;
  align-items: center;
  gap: 6px;
}
.chart-tooltip-row .chart-legend-swatch {
  width: 8px;
  height: 8px;
}
.chart-tooltip-model {
  color: var(--text-secondary);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.chart-tooltip-value {
  color: var(--text-primary);
  font-family: 'SF Mono', 'Fira Code', monospace;
  font-weight: 500;
}
.chart-tooltip-total {
  display: flex;
  justify-content: space-between;
  margin-top: 6px;
  padding-top: 6px;
  border-top: 1px solid var(--border-color);
  color: var(--text-primary);
  font-weight: 600;
}

@media (max-width: 768px) {
  .usage-history-card .card-header {
    flex-direction: column;
    align-items: flex-start;
  }
  .usage-history-controls {
    width: 100%;
  }
  .chart-filter {
    flex: 1;
    min-width: 0;
    max-width: none;
  }
  .month-nav-label {
    min-width: 90px;
    font-size: 12px;
  }
}
</style>
