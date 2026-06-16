<template>
  <div class="card">
    <div class="card-header">
      <div class="card-title">请求日志</div>
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
import { ref, computed, onMounted } from 'vue'
import { logApi, type LogTrace, type RequestLog } from '@/api/log'

const traces = ref<LogTrace[]>([])
const expandedTraces = ref(new Set<string>())
const loading = ref(false)

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

onMounted(loadLogs)
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
</style>
