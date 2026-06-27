<template>
  <div class="card">
    <div class="card-header">
      <div class="card-title">{{ t('channel.models.title').replace('{name}', channel?.name || '') }}</div>
      <router-link to="/admin/channel/list" class="btn btn-secondary">{{ t('common.back') }}</router-link>
    </div>

    <!-- Summary stats -->
    <div v-if="modelStats.length" class="usage-summary">
      <div class="stat-item">
        <div class="stat-label">{{ t('channel.models.totalRequests') }}</div>
        <div class="stat-value">{{ formatNumber(totalRequestCount) }}</div>
      </div>
      <div class="stat-item">
        <div class="stat-label">{{ t('channel.models.totalTokens') }}</div>
        <div class="stat-value">{{ formatTokens(totalTokens) }}</div>
      </div>
      <div class="stat-item">
        <div class="stat-label">{{ t('channel.models.inputTokens') }}</div>
        <div class="stat-value">{{ formatTokens(totalPromptTokens) }}</div>
      </div>
      <div class="stat-item">
        <div class="stat-label">{{ t('channel.models.outputTokens') }}</div>
        <div class="stat-value">{{ formatTokens(totalCompletionTokens) }}</div>
      </div>
      <div class="stat-item">
        <div class="stat-label">{{ t('channel.models.avgResponse') }}</div>
        <div class="stat-value">{{ formatResponseTime(channelAvgResponseTimeRecent30) }}</div>
      </div>
    </div>

    <div v-if="!models.length" class="empty-state">{{ t('channel.models.noData') }}</div>
    <div class="table-container" v-else>
      <!-- Desktop table view -->
      <table class="desktop-table">
        <thead>
          <tr>
            <th>{{ t('channel.models.modelName') }}</th>
            <th>{{ t('channel.models.displayName') }}</th>
            <th>{{ t('channel.models.inputTypes') }}</th>
            <th>{{ t('channel.models.status') }}</th>
            <th>{{ t('channel.models.requestCount') }}</th>
            <th>{{ t('channel.models.tokenUsage') }}</th>
            <th>{{ t('channel.models.avgResponseShort') }}</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="m in models" :key="m.id">
            <td><code class="model-tag">{{ m.modelName }}</code></td>
            <td>{{ m.displayName || m.modelName }}</td>
            <td>
              <span v-if="m.input" class="input-tags">
                <span v-for="type in (m.input || '').split(',')" :key="type" class="input-tag" :class="'input-tag--' + type">{{ type }}</span>
              </span>
              <span v-else class="text-muted">text</span>
            </td>
            <td style="white-space:nowrap;"><span class="badge badge-success">{{ t('channel.models.linked') }}</span></td>
            <td style="text-align:right;font-variant-numeric:tabular-nums;">
              <span style="font-weight:600;">{{ formatNumber(getModelStat(m.modelName)?.requestCount) }}</span>
            </td>
            <td style="font-size:12px;font-variant-numeric:tabular-nums;">
              <template v-if="getModelStat(m.modelName)?.totalTokens">
                <div style="display:flex;flex-direction:column;gap:2px;">
                  <span :title="t('channel.models.inputTokens') + ': ' + formatNumber(getModelStat(m.modelName)?.promptTokens) + ' | ' + t('channel.models.outputTokens') + ': ' + formatNumber(getModelStat(m.modelName)?.completionTokens)">
                    {{ formatTokens(getModelStat(m.modelName)?.totalTokens) }}
                  </span>
                  <span style="color:var(--text-muted);font-size:11px;">
                    {{ t('channel.models.inputTokens') }} {{ formatTokens(getModelStat(m.modelName)?.promptTokens) }} / {{ t('channel.models.outputTokens') }} {{ formatTokens(getModelStat(m.modelName)?.completionTokens) }}
                  </span>
                </div>
              </template>
              <span v-else style="color:var(--text-muted);">-</span>
            </td>
            <td style="text-align:right;font-variant-numeric:tabular-nums;">
              <span v-if="getModelStat(m.modelName)?.avgResponseTimeRecent30" style="font-weight:600;">
                {{ formatResponseTime(getModelStat(m.modelName)?.avgResponseTimeRecent30) }}
              </span>
              <span v-else style="color:var(--text-muted);">-</span>
            </td>
          </tr>
        </tbody>
      </table>

      <!-- Mobile card list view -->
      <div class="mobile-card-list">
        <div v-for="m in models" :key="m.id" class="mobile-model-card">
          <div class="mobile-card-header">
            <span class="mobile-card-title">{{ m.displayName || m.modelName }}</span>
            <span class="badge badge-success">{{ t('channel.models.linked') }}</span>
          </div>
          <div class="mobile-card-model-name">
            {{ t('channel.models.modelName') }}: <code class="model-tag">{{ m.modelName }}</code>
          </div>
          <div class="mobile-card-row" style="margin-bottom:8px;font-size:12px;">
            <span class="mobile-card-label">{{ t('channel.models.inputTypes') }}:</span>
            <span v-if="m.input" class="input-tags" style="display:inline-flex;">
              <span v-for="type in (m.input || '').split(',')" :key="type" class="input-tag" :class="'input-tag--' + type">{{ type }}</span>
            </span>
            <span v-else class="text-muted">text</span>
          </div>
          <div class="mobile-card-divider"></div>
          <div class="mobile-card-stats">
            <div class="mobile-stat">
              <span class="mobile-stat-label">{{ t('channel.models.requestCount') }}</span>
              <span class="mobile-stat-value">{{ formatNumber(getModelStat(m.modelName)?.requestCount) }}</span>
            </div>
            <div class="mobile-stat">
              <span class="mobile-stat-label">{{ t('channel.models.tokenUsage') }}</span>
              <span class="mobile-stat-value">{{ formatTokens(getModelStat(m.modelName)?.totalTokens) }}</span>
            </div>
            <div class="mobile-stat">
              <span class="mobile-stat-label">{{ t('channel.models.avgResponseShort') }}</span>
              <span class="mobile-stat-value">{{ formatResponseTime(getModelStat(m.modelName)?.avgResponseTimeRecent30) }}</span>
            </div>
          </div>
        </div>
      </div>
    </div>
  </div>

  <!-- Common Dialog -->
  <Dialog
    v-model="dialogVisible"
    :title="dialogTitle"
    :type="dialogType"
    @confirm="onDialogConfirm"
  >
    {{ dialogMessage }}
  </Dialog>
</template>

<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useI18n } from '@/composables/useI18n'
import { channelApi, type Channel, type ChannelModel, type ModelUsageStat } from '@/api/channel'
import Dialog from '@/components/common/Dialog.vue'

const { t } = useI18n()
const route = useRoute()
const router = useRouter()
const channel = ref<Channel | null>(null)
const models = ref<ChannelModel[]>([])
const modelStats = ref<ModelUsageStat[]>([])
const channelAvgResponseTimeRecent30 = ref<number>(0)

/* ---------- Dialog state ---------- */
const dialogVisible = ref(false)
const dialogTitle = ref(t('common.prompt'))
const dialogMessage = ref('')
const dialogType = ref<'alert' | 'confirm'>('alert')
let dialogOnConfirm: (() => void) | null = null

function openDialog(opts: {
  title?: string
  message: string
  type?: 'alert' | 'confirm'
  onConfirm?: () => void
}) {
  dialogTitle.value = opts.title ?? t('common.prompt')
  dialogMessage.value = opts.message
  dialogType.value = opts.type ?? 'alert'
  dialogOnConfirm = opts.onConfirm ?? null
  dialogVisible.value = true
}

function onDialogConfirm() {
  dialogOnConfirm?.()
  dialogOnConfirm = null
}
/* ------------------------------ */

/** Find usage stats by model name */
function getModelStat(modelName: string): ModelUsageStat | undefined {
  return modelStats.value.find(s => s.modelName === modelName)
}

/** Total request count */
const totalRequestCount = computed(() =>
  modelStats.value.reduce((sum, s) => sum + s.requestCount, 0)
)
/** Total tokens */
const totalTokens = computed(() =>
  modelStats.value.reduce((sum, s) => sum + s.totalTokens, 0)
)
/** Total input tokens */
const totalPromptTokens = computed(() =>
  modelStats.value.reduce((sum, s) => sum + s.promptTokens, 0)
)
/** Total output tokens */
const totalCompletionTokens = computed(() =>
  modelStats.value.reduce((sum, s) => sum + s.completionTokens, 0)
)

/** Format number with thousands separator */
function formatNumber(n: number | undefined): string {
  if (n == null) return '0'
  return n.toLocaleString()
}

/** Format token count with K/M abbreviation */
function formatTokens(n: number | undefined): string {
  if (n == null || n === 0) return '0'
  if (n >= 1_000_000) return (n / 1_000_000).toFixed(1) + 'M'
  if (n >= 1_000) return (n / 1_000).toFixed(1) + 'K'
  return n.toString()
}

/** Format response time: >=1000ms shows seconds, otherwise ms */
function formatResponseTime(ms: number | undefined): string {
  if (ms == null || ms === 0) return '-'
  if (ms >= 1000) return (ms / 1000).toFixed(2) + 's'
  return Math.round(ms) + 'ms'
}

onMounted(async () => {
  const id = Number(route.params.id)
  try {
    const [modelsRes, statsRes] = await Promise.all([
      channelApi.getModels(id),
      channelApi.getUsageStats(id)
    ])
    channel.value = modelsRes.data.channel
    models.value = modelsRes.data.models
    modelStats.value = statsRes.data.modelStats
    channelAvgResponseTimeRecent30.value = statsRes.data.channelAvgResponseTimeRecent30 ?? 0
  } catch (e: any) {
    openDialog({ title: t('error.loadFailed'), message: e.message })
    router.push('/admin/channel/list')
  }
})
</script>

<style scoped>
/* Ensure all table cells are vertically centered */
.desktop-table td {
  vertical-align: middle;
}

.usage-summary {
  display: grid;
  grid-template-columns: repeat(5, 1fr);
  gap: 16px;
  padding: 16px 0;
  margin-bottom: 16px;
  border-bottom: 1px solid var(--border-color);
}
.stat-item {
  text-align: center;
}
.stat-label {
  font-size: 12px;
  color: var(--text-muted);
  margin-bottom: 4px;
}
.stat-value {
  font-size: 20px;
  font-weight: 700;
  font-variant-numeric: tabular-nums;
}

/* Table / Card toggle */
.mobile-card-list {
  display: none;
}

/* Mobile card styling */
.mobile-model-card {
  border: 1px solid var(--border-color);
  border-radius: 8px;
  padding: 12px 16px;
  background: var(--bg-secondary);
}
.mobile-card-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 8px;
}
.mobile-card-title {
  font-weight: 600;
  font-size: 14px;
}
.mobile-card-model-name {
  font-size: 13px;
  color: var(--text-muted);
  margin-bottom: 8px;
}
.mobile-card-model-name .model-tag {
  font-size: 12px;
}
.mobile-card-divider {
  height: 1px;
  background: var(--border-color);
  margin: 8px 0;
}
.mobile-card-stats {
  display: flex;
  justify-content: space-between;
  gap: 8px;
}
.mobile-stat {
  display: flex;
  flex-direction: column;
  align-items: center;
  flex: 1;
  gap: 2px;
}
.mobile-stat-label {
  font-size: 11px;
  color: var(--text-muted);
}
.mobile-stat-value {
  font-size: 14px;
  font-weight: 600;
  font-variant-numeric: tabular-nums;
}

/* Responsive breakpoints */
@media (max-width: 768px) {
  .usage-summary {
    grid-template-columns: repeat(3, 1fr);
  }
  .desktop-table {
    display: none;
  }
  .mobile-card-list {
    display: flex;
    flex-direction: column;
    gap: 12px;
  }
}

@media (max-width: 480px) {
  .usage-summary {
    grid-template-columns: repeat(2, 1fr);
  }
}

/* Input type tags */
.input-tags {
  display: inline-flex;
  gap: 3px;
  flex-wrap: nowrap;
  white-space: nowrap;
}
.input-tag {
  display: inline-flex;
  align-items: center;
  font-size: 10px;
  padding: 1px 6px;
  border-radius: 4px;
  font-weight: 500;
  line-height: 1.5;
  text-transform: lowercase;
}
.input-tag--text {
  background: rgba(88, 166, 255, 0.12);
  color: var(--accent-blue, #58a6ff);
}
.input-tag--image {
  background: rgba(46, 160, 67, 0.12);
  color: #2ea043;
}
.text-muted {
  color: var(--text-muted);
  font-size: 12px;
}
</style>
