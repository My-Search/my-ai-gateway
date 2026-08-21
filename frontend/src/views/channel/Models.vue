<template>
  <div class="card">
    <div class="card-header">
      <div class="card-title">{{ t('channel.models.title').replace('{name}', channel?.name || '') }}</div>
      <div style="display:flex;gap:12px;align-items:center;">
        <TabSwitch
          v-if="models.length"
          v-model="period"
          variant="period"
          :tabs="[
            { value: 'all', label: t('dashboard.trendAll') },
            { value: 'today', label: t('dashboard.periodToday') },
            { value: 'week', label: t('dashboard.periodWeek') },
            { value: 'month', label: t('dashboard.periodMonth') },
          ]"
        />
        <router-link to="/admin/channel/list" class="btn btn-secondary"><SvgIcon name="arrow-left" :size="14" /> {{ t('common.back') }}</router-link>
      </div>
    </div>

    <div v-if="loading" class="page-loading">
      <LoadingSpinner :size="18" :text="t('common.loading')" />
    </div>

    <template v-else>
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
      <div class="stat-item">
        <div class="stat-label">{{ t('channel.models.avgOutputSpeed') }}</div>
        <div class="stat-value">{{ formatOutputSpeed(channelAvgOutputSpeedRecent30) }}</div>
      </div>
    </div>

    <div v-if="!sortedModels.length" class="empty-state">{{ t('channel.models.noData') }}</div>
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
            <th style="text-align:center;">{{ t('channel.models.avgResponseShort') }}</th>
            <th style="text-align:center;">{{ t('channel.models.avgOutputSpeedShort') }}</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="m in sortedModels" :key="m.id">
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
              <span style="font-weight:600;">{{ formatNumber(getDisplayStat(getModelStat(m.modelName)).requestCount) }}</span>
            </td>
            <td style="font-size:12px;font-variant-numeric:tabular-nums;">
              <template v-if="getDisplayStat(getModelStat(m.modelName)).totalTokens">
                <div style="display:flex;flex-direction:column;gap:2px;">
                  <span :title="t('channel.models.inputTokens') + ': ' + formatNumber(getDisplayStat(getModelStat(m.modelName)).promptTokens) + ' | ' + t('channel.models.outputTokens') + ': ' + formatNumber(getDisplayStat(getModelStat(m.modelName)).completionTokens)">
                    {{ formatTokens(getDisplayStat(getModelStat(m.modelName)).totalTokens) }}
                  </span>
                  <span style="color:var(--text-muted);font-size:11px;">
                    {{ t('channel.models.inputTokens') }} {{ formatTokens(getDisplayStat(getModelStat(m.modelName)).promptTokens) }} / {{ t('channel.models.outputTokens') }} {{ formatTokens(getDisplayStat(getModelStat(m.modelName)).completionTokens) }}
                  </span>
                </div>
              </template>
              <span v-else style="color:var(--text-muted);">-</span>
            </td>
            <td style="text-align:center;font-variant-numeric:tabular-nums;">
              <span v-if="getModelStat(m.modelName)?.avgResponseTimeRecent30" style="font-weight:600;">
                {{ formatResponseTime(getModelStat(m.modelName)?.avgResponseTimeRecent30) }}
              </span>
              <span v-else style="color:var(--text-muted);">-</span>
            </td>
            <td style="text-align:center;font-variant-numeric:tabular-nums;">
              <span v-if="getModelStat(m.modelName)?.avgOutputSpeedRecent30" style="font-weight:600;">
                {{ formatOutputSpeed(getModelStat(m.modelName)?.avgOutputSpeedRecent30) }}
              </span>
              <span v-else style="color:var(--text-muted);">-</span>
            </td>
          </tr>
        </tbody>
      </table>

      <!-- Mobile card list view -->
      <div class="mobile-card-list">
        <div v-for="m in sortedModels" :key="m.id" class="mobile-model-card">
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
              <span class="mobile-stat-value">{{ formatNumber(getDisplayStat(getModelStat(m.modelName)).requestCount) }}</span>
            </div>
            <div class="mobile-stat">
              <span class="mobile-stat-label">{{ t('channel.models.tokenUsage') }}</span>
              <span class="mobile-stat-value">{{ formatTokens(getDisplayStat(getModelStat(m.modelName)).totalTokens) }}</span>
            </div>
            <div class="mobile-stat">
              <span class="mobile-stat-label">{{ t('channel.models.avgResponseShort') }}</span>
              <span class="mobile-stat-value">{{ formatResponseTime(getModelStat(m.modelName)?.avgResponseTimeRecent30) }}</span>
            </div>
            <div class="mobile-stat">
              <span class="mobile-stat-label">{{ t('channel.models.avgOutputSpeedShort') }}</span>
              <span class="mobile-stat-value">{{ formatOutputSpeed(getModelStat(m.modelName)?.avgOutputSpeedRecent30) }}</span>
            </div>
          </div>
        </div>
      </div>
    </div>
    </template>
  </div>

  <!-- Common Dialog -->
  <Dialog
    v-model="visible"
    :title="title"
    :type="type"
    :confirm-class="confirmClass"
    @confirm="onConfirm"
  >
    {{ message }}
  </Dialog>
</template>

<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useI18n } from '@/composables/useI18n'
import { useDialog } from '@/composables/useDialog'
import { channelApi, type Channel, type ChannelModel, type ModelUsageStat } from '@/api/channel'
import Dialog from '@/components/common/Dialog.vue'
import LoadingSpinner from '@/components/common/LoadingSpinner.vue'
import TabSwitch from '@/components/common/TabSwitch.vue'
import { formatNumber, formatTokens } from '@/utils/format'

const { t } = useI18n()
const route = useRoute()
const router = useRouter()
const { visible, title, message, type, confirmClass, onConfirm, open } = useDialog()
const channel = ref<Channel | null>(null)
const models = ref<ChannelModel[]>([])
const modelStats = ref<ModelUsageStat[]>([])
const channelAvgResponseTimeRecent30 = ref<number>(0)
const channelAvgOutputSpeedRecent30 = ref<number>(0)
const loading = ref(false)

/** Find usage stats by model name */
function getModelStat(modelName: string): ModelUsageStat | undefined {
  return modelStats.value.find(s => s.modelName === modelName)
}

type Period = 'all' | 'today' | 'week' | 'month'
const period = ref<Period>('all')

/** Get display stats for the currently selected period */
function getDisplayStat(stat: ModelUsageStat | undefined) {
  if (!stat) return { requestCount: 0, promptTokens: 0, completionTokens: 0, totalTokens: 0 }
  if (period.value === 'today') return stat.today
  if (period.value === 'week') return stat.week
  if (period.value === 'month') return stat.month
  return {
    requestCount: stat.requestCount,
    promptTokens: stat.promptTokens,
    completionTokens: stat.completionTokens,
    totalTokens: stat.totalTokens
  }
}

/** Models sorted by request count descending for the selected period */
const sortedModels = computed(() => {
  return [...models.value].sort((a, b) => {
    const countA = getDisplayStat(getModelStat(a.modelName)).requestCount
    const countB = getDisplayStat(getModelStat(b.modelName)).requestCount
    return countB - countA
  })
})

/** Total request count for selected period */
const totalRequestCount = computed(() =>
  modelStats.value.reduce((sum, s) => sum + getDisplayStat(s).requestCount, 0)
)
/** Total tokens for selected period */
const totalTokens = computed(() =>
  modelStats.value.reduce((sum, s) => sum + getDisplayStat(s).totalTokens, 0)
)
/** Total input tokens for selected period */
const totalPromptTokens = computed(() =>
  modelStats.value.reduce((sum, s) => sum + getDisplayStat(s).promptTokens, 0)
)
/** Total output tokens for selected period */
const totalCompletionTokens = computed(() =>
  modelStats.value.reduce((sum, s) => sum + getDisplayStat(s).completionTokens, 0)
)

/** Format response time: seconds with 2 decimals */
function formatResponseTime(ms: number | undefined): string {
  if (ms == null || ms === 0) return '-'
  return (ms / 1000).toFixed(2) + 's'
}

/** Format output speed: tokens/s with 1 decimal */
function formatOutputSpeed(speed: number | undefined): string {
  if (speed == null || speed === 0) return '-'
  return speed.toFixed(1) + ' t/s'
}

onMounted(async () => {
  const id = Number(route.params.id)
  loading.value = true
  try {
    const [modelsRes, statsRes] = await Promise.all([
      channelApi.getModels(id),
      channelApi.getUsageStats(id)
    ])
    channel.value = modelsRes.data.channel
    models.value = modelsRes.data.models
    modelStats.value = statsRes.data.modelStats
    channelAvgResponseTimeRecent30.value = statsRes.data.channelAvgResponseTimeRecent30 ?? 0
    channelAvgOutputSpeedRecent30.value = statsRes.data.channelAvgOutputSpeedRecent30 ?? 0
  } catch (e: any) {
    open({ title: t('error.loadFailed'), message: e.message })
    router.push('/admin/channel/list')
  } finally {
    loading.value = false
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
  grid-template-columns: repeat(6, 1fr);
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

/* Page loading state */
.page-loading {
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 60px 20px;
  color: var(--text-muted);
  font-size: 13px;
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
