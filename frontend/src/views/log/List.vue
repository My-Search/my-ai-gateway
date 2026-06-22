<template>
  <div class="card">
    <div class="card-header">
      <div class="card-title">
        {{ t('log.list.title') }}
        <span v-if="sseReconnecting" class="badge badge-sse-connecting">{{ t('log.list.connecting') }}</span>
        <span v-else-if="sseConnected" class="badge badge-sse">{{ t('log.list.realtime') }}</span>
      </div>
      <div style="display:flex;gap:8px;">
        <button class="btn btn-sm btn-secondary" @click="loadLogs" :disabled="loading">
          <SvgIcon name="refresh" :size="14" /> {{ t('common.refresh') }}
        </button>
        <button class="btn btn-sm btn-danger" @click="cleanLogs"><SvgIcon name="trash" :size="14" /> {{ t('log.list.cleanOld') }}</button>
      </div>
    </div>
    <div v-if="loading" style="text-align:center;padding:40px;color:var(--text-muted);">{{ t('common.loading') }}</div>

    <template v-for="trace in traces" :key="trace.traceId">
      <div class="log-trace" :class="{ 'trace-success': trace.successCount > 0 && trace.failCount === 0, 'trace-fail': trace.failCount > 0 }" @click="toggleTrace(trace.traceId)">
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
          <span class="trace-toggle">{{ expandedTraces.has(trace.traceId) ? '▲' : '▼' }}</span>
        </div>
      </div>
      <div v-if="expandedTraces.has(trace.traceId)" class="trace-detail">
        <div v-for="(group, gIdx) in groupedTraceLogs.get(trace.traceId) || []" :key="group.key + '-' + gIdx" class="log-entry" :class="{ 'log-entry-clickable': group.logs.some(l => l.message) }" :style="{ paddingLeft: `calc(var(--indent-base, 12px) + ${gIdx} * var(--indent-step, 16px))` }" @click.stop="openLogDetail(group)">
          <span class="phase" :class="'phase-' + group.logs[0].phase">
            {{ group.logs[0].phase }}<span v-if="group.logs.length > 1" class="retry-count">(x{{ group.logs.length }})</span>
          </span>
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
    v-model="dialogVisible"
    :title="dialogTitle"
    :type="dialogType"
    :confirm-class="dialogConfirmClass"
    :width="dialogWidth"
    @confirm="onDialogConfirm"
  >
    <pre class="dialog-pre">{{ dialogMessage }}</pre>
  </Dialog>
</template>

<script setup lang="ts">
import { ref, computed, onMounted, onUnmounted } from 'vue'
import { logApi, subscribeLogStream, type LogTrace, type RequestLog, type LogSseSubscription } from '@/api/log'
import Dialog from '@/components/common/Dialog.vue'
import { useI18n } from '@/composables/useI18n'
import { formatLocalDateTime, formatLocalFullTime } from '@/utils/date'

const { t } = useI18n()

const traces = ref<LogTrace[]>([])
const expandedTraces = ref(new Set<string>())
const loading = ref(false)
const loadingMore = ref(false)
const sseConnected = ref(false)
const sseReconnecting = ref(false)

// 分页状态
const offset = ref(0)
const limit = 50
const hasMore = ref(true)
const total = ref(0)
const loadMoreTrigger = ref<HTMLElement | null>(null)
let observer: IntersectionObserver | null = null

const dialogVisible = ref(false)
const dialogTitle = ref(t('dialog.title'))
const dialogMessage = ref('')
const dialogType = ref<'alert' | 'confirm'>('alert')
const dialogConfirmClass = ref('btn-primary')
const dialogWidth = ref('420px')
let dialogOnConfirm: (() => void) | null = null

function openDialog(opts: {
  title?: string
  message: string
  type?: 'alert' | 'confirm'
  confirmClass?: string
  onConfirm?: () => void
  width?: string
}) {
  dialogTitle.value = opts.title ?? t('dialog.title')
  dialogMessage.value = opts.message
  dialogType.value = opts.type ?? 'alert'
  dialogConfirmClass.value = opts.confirmClass ?? 'btn-primary'
  dialogWidth.value = opts.width ?? '420px'
  dialogOnConfirm = opts.onConfirm ?? null
  dialogVisible.value = true
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

  openDialog({
    title: `请求详情 [${first.phase}]`,
    message: parts.join('\n'),
    type: 'alert',
    confirmClass: 'btn-primary',
    width: '640px',
  })
}

function onDialogConfirm() {
  dialogOnConfirm?.()
  dialogOnConfirm = null
}
/* ------------------------------ */

let eventSource: LogSseSubscription | null = null

function startSse() {
  stopSse()
  eventSource = subscribeLogStream({
    onLog: (log) => {
      upsertTraceFromSse(log)
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

const groupedTraceLogs = computed(() => {
  const map = new Map<string, { key: string; logs: RequestLog[] }[]>()
  for (const trace of traces.value) {
    map.set(trace.traceId, groupLogs(trace.logs))
  }
  return map
})

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

async function loadLogs() {
  loading.value = true
  offset.value = 0
  hasMore.value = true
  try {
    const res = await logApi.list(0, limit)
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
  } catch (e: any) {
    openDialog({ title: t('error.loadFailed'), message: e.message })
  } finally {
    loading.value = false
  }
}

async function loadMoreLogs() {
  if (loadingMore.value || !hasMore.value) return
  loadingMore.value = true
  try {
    const res = await logApi.list(offset.value, limit)
    const newTraces = res.data.data
    // 避免重复添加（SSE 可能已提前插入相同 trace）
    const existingIds = new Set(traces.value.map(t => t.traceId))
    const uniqueNewTraces = newTraces.filter(t => !existingIds.has(t.traceId))
    traces.value.push(...uniqueNewTraces)
    hasMore.value = res.data.hasMore
    // offset 按 backend 返回总数递增（与后端分页语义对齐）
    offset.value += newTraces.length
  } catch (e: any) {
    console.error('Failed to load more logs:', e)
  } finally {
    loadingMore.value = false
  }
}

function cleanLogs() {
  openDialog({
    title: t('common.confirm'),
    message: t('log.list.cleanConfirm'),
    type: 'confirm',
    confirmClass: 'btn-danger',
    onConfirm: async () => {
      try {
        const res = await logApi.clean()
        openDialog({ message: res.data.message || t('log.list.cleanSuccess') })
        await loadLogs()
      } catch (e: any) {
        openDialog({ title: t('common.fail'), message: e.message })
      }
    }
  })
}

onMounted(() => {
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
  stopSse()
  observer?.disconnect()
})
</script>

<style scoped>
.log-trace {
  cursor: pointer; border-bottom: 1px solid var(--border-color);
  transition: background 0.15s;
  border-left: 3px solid transparent;
}
.log-trace:hover { background: var(--bg-hover); }
.log-trace.trace-success { border-left-color: var(--accent-green); }
.log-trace.trace-fail { border-left-color: var(--accent-red); }
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
  background: var(--bg-tertiary);
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

.phase { display: inline-block; padding: 1px 6px; border-radius: 3px; font-size: 11px; font-weight: 600; white-space: nowrap; }
.phase-start { background: color-mix(in srgb, var(--accent-blue) 20%, transparent); color: var(--accent-blue); }
.phase-retry { background: color-mix(in srgb, var(--accent-yellow) 20%, transparent); color: var(--accent-yellow); }
.phase-reroute { background: color-mix(in srgb, var(--accent-purple) 20%, transparent); color: var(--accent-purple); }
.phase-success { background: color-mix(in srgb, var(--accent-green) 20%, transparent); color: var(--accent-green); }
.phase-fail { background: color-mix(in srgb, var(--accent-red) 20%, transparent); color: var(--accent-red); }
.phase-skip { background: color-mix(in srgb, var(--text-muted) 20%, transparent); color: var(--text-muted); }
.retry-count { font-weight: 400; opacity: 0.85; margin-left: 2px; }

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
</style>
