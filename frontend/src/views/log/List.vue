<template>
  <div class="card">
    <div class="card-header">
      <div class="card-title">
        请求日志
        <span v-if="sseConnected" class="badge badge-sse">实时</span>
      </div>
      <div style="display:flex;gap:8px;">
        <button class="btn btn-sm btn-secondary" @click="loadLogs" :disabled="loading">
          <SvgIcon name="refresh" :size="14" /> 刷新
        </button>
        <button class="btn btn-sm btn-danger" @click="cleanLogs">清理旧日志</button>
      </div>
    </div>
    <div v-if="loading" style="text-align:center;padding:40px;color:var(--text-muted);">加载中...</div>

    <template v-for="trace in traces" :key="trace.traceId">
      <div class="log-trace" :class="{ 'trace-success': trace.successCount > 0 && trace.failCount === 0, 'trace-fail': trace.failCount > 0 }" @click="toggleTrace(trace.traceId)">
        <div class="trace-header">
          <span class="trace-id" :title="trace.traceId">{{ shortenId(trace.traceId) }}</span>
          <span class="trace-model">
            <code class="model-tag">{{ trace.modelName || '-' }}</code>
          </span>
          <span class="trace-stats">
            <span v-if="trace.failCount > 0" class="badge badge-danger">失败</span>
            <span v-else-if="trace.successCount > 0" class="badge badge-success">成功</span>
            <span v-if="trace.retryCount > 0" class="badge badge-warning">重试 {{ trace.retryCount }}</span>
          </span>
          <span class="trace-time">{{ formatDateTime(trace.startTime) }}</span>
          <span class="trace-duration" v-if="trace.totalTimeMs > 0">{{ trace.totalTimeMs }}ms</span>
          <span class="trace-toggle">{{ expandedTraces.has(trace.traceId) ? '▲' : '▼' }}</span>
        </div>
      </div>
      <div v-if="expandedTraces.has(trace.traceId)" class="trace-detail">
        <div v-for="(group, gIdx) in groupedTraceLogs.get(trace.traceId) || []" :key="group.key + '-' + gIdx" class="log-entry" :style="{ paddingLeft: `${12 + gIdx * 16}px` }">
          <span class="phase" :class="'phase-' + group.logs[0].phase">
            {{ group.logs[0].phase }}<span v-if="group.logs.length > 1" class="retry-count">（x{{ group.logs.length }}）</span>
          </span>
          <span class="log-info">
            <template v-if="group.logs[0].channelName">{{ group.logs[0].channelName }}/</template>
            <template v-if="group.logs[0].channelModelName">{{ group.logs[0].channelModelName }}</template>
            <template v-else-if="group.logs[0].modelName">{{ group.logs[0].modelName }}</template>
            <template v-if="group.logs[group.logs.length - 1].responseTimeMs != null"> · {{ group.logs[group.logs.length - 1].responseTimeMs }}ms</template>
          </span>
          <span class="log-time">{{ formatTime(group.logs[group.logs.length - 1].createdAt) }}</span>
          <div v-if="group.logs.some(l => l.errorMessage)" class="log-error">{{ group.logs.filter(l => l.errorMessage).map(l => l.errorMessage).join('\n') }}</div>
        </div>
      </div>
    </template>

    <div v-if="!loading && !traces.length" class="empty-state">暂无日志数据</div>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted, onUnmounted } from 'vue'
import { logApi, subscribeLogStream, type LogTrace, type RequestLog } from '@/api/log'

const traces = ref<LogTrace[]>([])
const expandedTraces = ref(new Set<string>())
const loading = ref(false)
const sseConnected = ref(false)

/* ========== EventSource / SSE 管理 ========== */

let eventSource: EventSource | null = null

function startSse() {
  stopSse()
  eventSource = subscribeLogStream({
    onLog: (log) => {
      upsertTraceFromSse(log)
    },
    onError: () => {
      sseConnected.value = false
    },
  })
  // 连接建立时标记
  eventSource.addEventListener('open', () => {
    sseConnected.value = true
  })
}

function stopSse() {
  sseConnected.value = false
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
  // 新日志插入后总是把连接标记为活跃（EventSource 自动重连场景）
  sseConnected.value = true
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

/** 获取日志条目的模型标识（channelName/modelName 或 modelName） */
function getModelKey(log: RequestLog): string {
  if (log.channelName && log.channelModelName) return `${log.channelName}/${log.channelModelName}`
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
  if (!dateStr || typeof dateStr !== 'string') return ''
  return dateStr.substring(5, 16)
}

function formatTime(dateStr: string) {
  if (!dateStr || typeof dateStr !== 'string') return ''
  return dateStr.substring(11, 19)
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
  try {
    const res = await logApi.list()
    traces.value = res.data
    // 初始加载时，进行中的 trace 默认展开
    for (const t of traces.value) {
      if (isTraceInProgress(t)) {
        expandedTraces.value.add(t.traceId)
      }
    }
  } catch (e: any) {
    alert('加载失败: ' + e.message)
  } finally {
    loading.value = false
  }
}

async function cleanLogs() {
  if (!confirm('确认清理 30 天前的日志？')) return
  try {
    const res = await logApi.clean()
    alert(res.data.message || '清理成功')
    await loadLogs()
  } catch (e: any) {
    alert('清理失败: ' + e.message)
  }
}

onMounted(() => {
  loadLogs()
  startSse()
})

onUnmounted(() => {
  stopSse()
})
</script>

<style scoped>
.log-trace {
  cursor: pointer; border-bottom: 1px solid var(--border-color);
  transition: background 0.15s;
  border-left: 3px solid transparent;
}
.log-trace:hover { background: var(--bg-hover); }
.log-trace.trace-success { border-left-color: var(--accent-green, #3fb950); }
.log-trace.trace-fail { border-left-color: var(--accent-red, #f85149); }
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
.log-info { color: var(--text-secondary); flex: 1; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.log-time { color: var(--text-muted); font-size: 11px; white-space: nowrap; }
.log-error { color: var(--accent-red); font-size: 11px; margin-top: 4px; white-space: pre-wrap; }

.phase { display: inline-block; padding: 1px 6px; border-radius: 3px; font-size: 11px; font-weight: 600; white-space: nowrap; }
.phase-start { background: rgba(88,166,255,0.2); color: var(--accent-blue); }
.phase-retry { background: rgba(210,153,34,0.2); color: var(--accent-yellow); }
.phase-reroute { background: rgba(188,140,255,0.2); color: var(--accent-purple); }
.phase-success { background: rgba(63,185,80,0.2); color: var(--accent-green); }
.phase-fail { background: rgba(248,81,73,0.2); color: var(--accent-red); }
.phase-skip { background: rgba(139,139,139,0.2); color: var(--text-muted); }
.retry-count { font-weight: 400; opacity: 0.85; margin-left: 2px; }

.badge-sse {
  background: rgba(63,185,80,0.2);
  color: var(--accent-green, #3fb950);
  font-size: 11px;
  font-weight: 600;
  padding: 2px 8px;
  border-radius: 3px;
  margin-left: 8px;
  vertical-align: middle;
  animation: pulse-badge 2s ease-in-out infinite;
}
@keyframes pulse-badge {
  0%, 100% { opacity: 1; }
  50% { opacity: 0.6; }
}
</style>
