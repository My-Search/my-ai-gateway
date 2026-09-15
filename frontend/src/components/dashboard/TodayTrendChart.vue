<template>
  <div class="trend-card card">
    <div class="card-header trend-header">
      <div class="trend-title-wrap">
        <div class="title-row">
          <div class="card-title"><SvgIcon name="chart" :size="18" /> {{ t('dashboard.trendTitle') }}</div>
          <div class="tab-switch">
            <button :class="['tab-btn', mode === 'all' ? 'active' : '']" @click="switchMode('all')">{{ t('dashboard.trendSuccessFail') }}</button>
            <button :class="['tab-btn', mode === 'entry' ? 'active' : '']" @click="switchMode('entry')">{{ t('dashboard.trendEntry') }}</button>
            <button :class="['tab-btn', mode === 'channel' ? 'active' : '']" @click="switchMode('channel')">{{ t('dashboard.trendChannel') }}</button>
          </div>
        </div>
        <p class="card-subtitle">{{ t('dashboard.trendSubtitle') }}</p>
      </div>
    </div>
    <div class="trend-body" ref="chartRef" style="width:100%;height:300px;"></div>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted, onUnmounted, watch, nextTick } from 'vue'
import { dashboardApi, type TodayTrendData, type DashboardRangeKey, type DashboardRangeParams } from '@/api/dashboard'
import { useI18n } from '@/composables/useI18n'
import * as echarts from 'echarts'

const { t } = useI18n()

const props = withDefaults(defineProps<{
  rangeKey?: DashboardRangeKey
  from?: string
  to?: string
}>(), {
  rangeKey: 'today',
  from: '',
  to: ''
})

const mode = ref<'all' | 'entry' | 'channel'>('entry')
const trendData = ref<TodayTrendData | null>(null)
const chartRef = ref<HTMLDivElement | null>(null)
let chart: echarts.ECharts | null = null

function switchMode(newMode: 'all' | 'entry' | 'channel'): void {
  if (mode.value === newMode) return
  mode.value = newMode
  fetchData()
}

function rangeParams(): DashboardRangeParams {
  if (props.rangeKey === 'custom') {
    if (!props.from || !props.to || props.from > props.to) return { range: 'today' }
    return { range: 'custom', from: props.from, to: props.to }
  }
  return { range: props.rangeKey }
}

async function fetchData() {
  try {
    const res = await dashboardApi.getTodayTrend(mode.value, rangeParams())
    trendData.value = res.data
    nextTick(() => renderChart())
  } catch {
    // ignore
  }
}

function renderChart() {
  if (!chartRef.value) return
  if (!chart) {
    chart = echarts.init(chartRef.value)
  }
  const data = trendData.value
  if (!data) {
    chart.clear()
    return
  }

  const isAll = mode.value === 'all'
  const modelNames = Object.keys(data.series)
  const isMulti = modelNames.length > 1 || !isAll

  let seriesList: echarts.SeriesOption[]

  if (isAll) {
    // all 模式：成功（绿色）+ 失败（红色）两条线
    const successData = data.series['success'] ?? []
    const failData = data.series['fail'] ?? []
    seriesList = [
      {
        name: t('dashboard.success'),
        type: 'line',
        smooth: true,
        symbol: 'none',
        lineStyle: { width: 1.5, color: '#3fb950' },
        itemStyle: { color: '#3fb950' },
        areaStyle: { color: new echarts.graphic.LinearGradient(0, 0, 0, 1, [{ offset: 0, color: 'rgba(63,185,80,0.2)' }, { offset: 1, color: 'rgba(63,185,80,0)' }]) },
        emphasis: { focus: 'series' },
        data: successData
      },
      {
        name: t('common.fail'),
        type: 'line',
        smooth: true,
        symbol: 'none',
        lineStyle: { width: 1.5, color: '#f85173' },
        itemStyle: { color: '#f85173' },
        areaStyle: { color: new echarts.graphic.LinearGradient(0, 0, 0, 1, [{ offset: 0, color: 'rgba(248,81,115,0.15)' }, { offset: 1, color: 'rgba(248,81,115,0)' }]) },
        emphasis: { focus: 'series' },
        data: failData
      }
    ]
  } else {
    // entry/channel 模式：每条线带面积
    seriesList = modelNames.map((name) => ({
      name,
      type: 'line',
      smooth: true,
      symbol: 'none',
      lineStyle: { width: 1.5 },
      areaStyle: { opacity: 0.12 },
      emphasis: { focus: 'series' },
      data: data.series[name]
    }))
  }

  const option: echarts.EChartsOption = {
    tooltip: {
      trigger: 'axis',
      backgroundColor: 'var(--bg-secondary)',
      borderColor: 'var(--border-color)',
      textStyle: { color: 'var(--text-primary)', fontSize: 12 },
      formatter: (params: echarts.DefaultAxisPointerParams['value'] | echarts.DefaultAxisPointerParams['value'][]) => {
        const items = Array.isArray(params) ? params : [params]
        if (!items.length) return ''
        const time = (items[0] as Record<string, unknown>).axisValue as string
        let html = `<div style="font-weight:600;margin-bottom:4px;">${time}</div>`
        for (const p of items) {
          const pp = p as Record<string, unknown>
          const val = (pp.value as number) ?? 0
          if (val === 0) continue
          html += `<div style="display:flex;align-items:center;gap:6px;padding:1px 0;">
            <span style="display:inline-block;width:8px;height:8px;border-radius:50%;background:${pp.color as string};"></span>
            ${pp.seriesName as string}: <strong style="margin-left:4px;">${val}</strong> ${t('dashboard.requests')}
          </div>`
        }
        return html
      }
    },
    legend: isMulti ? {
      type: 'scroll',
      bottom: 0,
      // 图例用线条而非色块，与折线本身保持一致
      icon: 'line',
      itemStyle: { borderColor: 'auto', borderWidth: 2 },
      textStyle: { color: 'var(--text-secondary)', fontSize: 11 },
      pageTextStyle: { color: 'var(--text-secondary)' }
    } : undefined,
    grid: {
      left: 44,
      right: 20,
      top: 16,
      bottom: isMulti ? 44 : 24
    },
    xAxis: {
      type: 'category',
      data: data.buckets,
      boundaryGap: false,
      axisLine: { lineStyle: { color: 'var(--border-color)' } },
      axisTick: { show: false },
      // 10 分钟桶（单日 144 个）每 5 个显示一个标签；按天分桶时自动抽稀
      axisLabel: { color: 'var(--text-muted)', fontSize: 10, interval: data.bucketUnit === '1d' ? 'auto' : 5, showMaxLabel: true },
      splitLine: { show: false }
    },
    yAxis: {
      type: 'value',
      minInterval: 1,
      axisLine: { show: false },
      axisTick: { show: false },
      splitLine: { lineStyle: { color: 'var(--border-color)', type: 'dashed', opacity: 0.6 } },
      axisLabel: { color: 'var(--text-muted)', fontSize: 10 }
    },
    series: seriesList
  }

  chart.setOption(option, true)
  chart.resize()
}

function handleResize() {
  chart?.resize()
}

onMounted(() => {
  fetchData()
  window.addEventListener('resize', handleResize)
})

onUnmounted(() => {
  window.removeEventListener('resize', handleResize)
  chart?.dispose()
  chart = null
})

watch(() => t('dashboard.trendRequests'), () => {
  // 语言切换时重新渲染
  nextTick(() => renderChart())
}, { flush: 'post' })

watch(() => props.rangeKey, () => {
  fetchData()
})

watch([() => props.from, () => props.to], () => {
  if (props.rangeKey === 'custom') fetchData()
})
</script>

<style scoped>
.trend-card {
  margin-bottom: 20px;
}
.trend-body {
  min-height: 240px;
}

.trend-header {
  flex-direction: column;
  align-items: stretch;
  gap: 0;
  padding-bottom: 10px;
  margin-bottom: 12px;
}
.trend-title-wrap {
  display: flex;
  flex-direction: column;
  gap: 2px;
  min-width: 0;
  width: 100%;
}
.title-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  min-width: 0;
}
.card-subtitle {
  font-size: 12px;
  color: var(--text-muted);
  margin: 0;
  font-weight: 400;
}

/* Segmented control style tabs */
.tab-switch {
  display: inline-flex;
  align-items: center;
  gap: 2px;
  background: var(--bg-primary);
  border: 1px solid var(--border-color);
  border-radius: var(--radius, 6px);
  padding: 3px;
}
.tab-btn {
  padding: 5px 14px;
  border: none;
  background: transparent;
  color: var(--text-muted);
  font-size: 13px;
  font-weight: 500;
  border-radius: var(--radius-sm, 4px);
  cursor: pointer;
  transition: all 0.2s ease;
  white-space: nowrap;
  font-family: inherit;
  line-height: 1.5;
}
.tab-btn:hover {
  color: var(--text-secondary);
}
.tab-btn.active {
  background: var(--bg-secondary);
  color: var(--accent-blue);
  box-shadow: 0 1px 2px rgba(0,0,0,0.08);
}

@media (max-width: 768px) {
  .tab-switch { flex-wrap: wrap; }
  .tab-btn { padding: 5px 10px; font-size: 12px; }
}
</style>
