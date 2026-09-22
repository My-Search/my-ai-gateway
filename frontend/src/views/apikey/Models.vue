<template>
  <div class="card">
    <div class="card-header">
      <div class="card-title">{{ t('apikey.usage.title').replace('{name}', keyName) }}</div>
      <div style="display:flex;gap:12px;align-items:center;">
        <TabSwitch
          v-if="modelStats.length"
          v-model="period"
          variant="period"
          :tabs="[
            { value: 'all', label: t('dashboard.trendAll') },
            { value: 'today', label: t('dashboard.periodToday') },
            { value: 'week', label: t('dashboard.periodWeek') },
            { value: 'month', label: t('dashboard.periodMonth') },
          ]"
        />
        <router-link to="/admin/apikey/list" class="btn btn-secondary"><SvgIcon name="arrow-left" :size="14" /> {{ t('common.back') }}</router-link>
      </div>
    </div>

    <div v-if="loading" class="page-loading">
      <LoadingSpinner :size="18" :text="t('common.loading')" />
    </div>

    <template v-else>
    <!-- Summary stats -->
    <div v-if="modelStats.length" class="usage-summary">
      <div class="stat-item">
        <div class="stat-label">{{ t('apikey.usage.totalRequests') }}</div>
        <div class="stat-value">{{ formatNumber(totalRequestCount) }}</div>
      </div>
      <div class="stat-item">
        <div class="stat-label">{{ t('apikey.usage.totalTokens') }}</div>
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
        <div class="stat-label">{{ t('apikey.usage.avgResponse') }}</div>
        <div class="stat-value">{{ formatResponseTime(keyAvgResponseTimeRecent30) }}</div>
      </div>
      <div class="stat-item">
        <div class="stat-label">{{ t('apikey.usage.avgOutputSpeed') }}</div>
        <div class="stat-value">{{ formatOutputSpeed(keyAvgOutputSpeedRecent30) }}</div>
      </div>
    </div>

    <div v-if="!sortedModels.length" class="empty-state">{{ t('apikey.usage.noData') }}</div>
    <div class="table-container" v-else>
      <!-- Desktop table view -->
      <table class="desktop-table">
        <thead>
          <tr>
            <th>{{ t('channel.models.modelName') }}</th>
            <th>{{ t('channel.models.requestCount') }}</th>
            <th>{{ t('channel.models.tokenUsage') }}</th>
            <th style="text-align:center;">{{ t('channel.models.avgResponseShort') }}</th>
            <th style="text-align:center;">{{ t('channel.models.avgOutputSpeedShort') }}</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="m in sortedModels" :key="m.modelName">
            <td><code class="model-tag">{{ m.modelName }}</code></td>
            <td style="text-align:right;font-variant-numeric:tabular-nums;">
              <span style="font-weight:600;">{{ formatNumber(getDisplayStat(m).requestCount) }}</span>
            </td>
            <td style="font-size:12px;font-variant-numeric:tabular-nums;">
              <template v-if="getDisplayStat(m).totalTokens">
                <div style="display:flex;flex-direction:column;gap:2px;">
                  <span :title="t('channel.models.inputTokens') + ': ' + formatNumber(getDisplayStat(m).promptTokens ?? 0) + ' | ' + t('channel.models.outputTokens') + ': ' + formatNumber(getDisplayStat(m).completionTokens ?? 0)">
                    {{ formatTokens(getDisplayStat(m).totalTokens) }}
                  </span>
                  <span style="color:var(--text-muted);font-size:11px;">
                    {{ t('channel.models.inputTokens') }} {{ formatTokens(getDisplayStat(m).promptTokens ?? 0) }} / {{ t('channel.models.outputTokens') }} {{ formatTokens(getDisplayStat(m).completionTokens ?? 0) }}
                  </span>
                </div>
              </template>
              <span v-else style="color:var(--text-muted);">-</span>
            </td>
            <td style="text-align:center;font-variant-numeric:tabular-nums;">
              <span v-if="m.avgResponseTimeRecent30" style="font-weight:600;">
                {{ formatResponseTime(m.avgResponseTimeRecent30) }}
              </span>
              <span v-else style="color:var(--text-muted);">-</span>
            </td>
            <td style="text-align:center;font-variant-numeric:tabular-nums;">
              <span v-if="m.avgOutputSpeedRecent30" style="font-weight:600;">
                {{ formatOutputSpeed(m.avgOutputSpeedRecent30) }}
              </span>
              <span v-else style="color:var(--text-muted);">-</span>
            </td>
          </tr>
        </tbody>
      </table>

      <!-- Mobile card list view -->
      <div class="mobile-card-list">
        <div v-for="m in sortedModels" :key="'m-' + m.modelName" class="mobile-model-card">
          <div class="mobile-card-header">
            <span class="mobile-card-title">{{ m.modelName }}</span>
          </div>
          <div class="mobile-card-divider"></div>
          <div class="mobile-card-stats">
            <div class="mobile-stat">
              <span class="mobile-stat-label">{{ t('channel.models.requestCount') }}</span>
              <span class="mobile-stat-value">{{ formatNumber(getDisplayStat(m).requestCount) }}</span>
            </div>
            <div class="mobile-stat">
              <span class="mobile-stat-label">{{ t('channel.models.tokenUsage') }}</span>
              <span class="mobile-stat-value">{{ formatTokens(getDisplayStat(m).totalTokens) }}</span>
            </div>
            <div class="mobile-stat">
              <span class="mobile-stat-label">{{ t('channel.models.avgResponseShort') }}</span>
              <span class="mobile-stat-value">{{ formatResponseTime(m.avgResponseTimeRecent30) }}</span>
            </div>
            <div class="mobile-stat">
              <span class="mobile-stat-label">{{ t('channel.models.avgOutputSpeedShort') }}</span>
              <span class="mobile-stat-value">{{ formatOutputSpeed(m.avgOutputSpeedRecent30) }}</span>
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
import { apikeyApi, type ApiKeyModelUsageStat } from '@/api/apikey'
import Dialog from '@/components/common/Dialog.vue'
import LoadingSpinner from '@/components/common/LoadingSpinner.vue'
import TabSwitch from '@/components/common/TabSwitch.vue'
import { formatNumber, formatTokens } from '@/utils/format'

const { t } = useI18n()
const route = useRoute()
const router = useRouter()
const { visible, title, message, type, confirmClass, onConfirm, open } = useDialog()

const keyName = ref('')
const modelStats = ref<ApiKeyModelUsageStat[]>([])
const keyAvgResponseTimeRecent30 = ref<number>(0)
const keyAvgOutputSpeedRecent30 = ref<number>(0)
const loading = ref(false)

type Period = 'all' | 'today' | 'week' | 'month'
const period = ref<Period>('today')

const EMPTY = { requestCount: 0, promptTokens: 0, completionTokens: 0, totalTokens: 0 }

/** Get display stats for the currently selected period */
function getDisplayStat(stat: ApiKeyModelUsageStat | undefined) {
  if (!stat) return EMPTY
  if (period.value === 'today') return stat.today ?? EMPTY
  if (period.value === 'week') return stat.week ?? EMPTY
  if (period.value === 'month') return stat.month ?? EMPTY
  return {
    requestCount: stat.requestCount,
    promptTokens: stat.promptTokens ?? 0,
    completionTokens: stat.completionTokens ?? 0,
    totalTokens: stat.totalTokens
  }
}

/** Models sorted by request count descending for the selected period (ties broken by name for a stable rank) */
const sortedModels = computed(() =>
  [...modelStats.value].sort(
    (a, b) =>
      getDisplayStat(b).requestCount - getDisplayStat(a).requestCount ||
      a.modelName.localeCompare(b.modelName)
  )
)

const totalRequestCount = computed(() =>
  modelStats.value.reduce((sum, s) => sum + getDisplayStat(s).requestCount, 0)
)
const totalTokens = computed(() =>
  modelStats.value.reduce((sum, s) => sum + getDisplayStat(s).totalTokens, 0)
)
const totalPromptTokens = computed(() =>
  modelStats.value.reduce((sum, s) => sum + (getDisplayStat(s).promptTokens ?? 0), 0)
)
const totalCompletionTokens = computed(() =>
  modelStats.value.reduce((sum, s) => sum + (getDisplayStat(s).completionTokens ?? 0), 0)
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
    const res = await apikeyApi.usageStatsDetail(id)
    keyName.value = res.data.key?.keyName || ''
    modelStats.value = res.data.modelStats || []
    keyAvgResponseTimeRecent30.value = res.data.keyAvgResponseTimeRecent30 ?? 0
    keyAvgOutputSpeedRecent30.value = res.data.keyAvgOutputSpeedRecent30 ?? 0
  } catch (e: any) {
    open({ title: t('error.loadFailed'), message: e.message })
    router.push('/admin/apikey/list')
  } finally {
    loading.value = false
  }
})
</script>

<style scoped>
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

.mobile-card-list {
  display: none;
}

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
  word-break: break-all;
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
  text-align: center;
}
.mobile-stat-value {
  font-size: 14px;
  font-weight: 600;
  font-variant-numeric: tabular-nums;
}

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

.page-loading {
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 60px 20px;
  color: var(--text-muted);
  font-size: 13px;
}
</style>
