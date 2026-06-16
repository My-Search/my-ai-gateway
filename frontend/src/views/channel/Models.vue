<template>
  <div class="card">
    <div class="card-header">
      <div class="card-title">渠道模型 - {{ channel?.name }}</div>
      <router-link to="/admin/channel/list" class="btn btn-secondary">返回列表</router-link>
    </div>

    <!-- 汇总统计 -->
    <div v-if="modelStats.length" class="usage-summary">
      <div class="stat-item">
        <div class="stat-label">总请求次数</div>
        <div class="stat-value">{{ formatNumber(totalRequestCount) }}</div>
      </div>
      <div class="stat-item">
        <div class="stat-label">总 Token 用量</div>
        <div class="stat-value">{{ formatTokens(totalTokens) }}</div>
      </div>
      <div class="stat-item">
        <div class="stat-label">输入 Token</div>
        <div class="stat-value">{{ formatTokens(totalPromptTokens) }}</div>
      </div>
      <div class="stat-item">
        <div class="stat-label">输出 Token</div>
        <div class="stat-value">{{ formatTokens(totalCompletionTokens) }}</div>
      </div>
      <div class="stat-item">
        <div class="stat-label">近 30 次平均响应</div>
        <div class="stat-value">{{ formatResponseTime(channelAvgResponseTimeRecent30) }}</div>
      </div>
    </div>

    <div v-if="!models.length" class="empty-state">暂无模型数据</div>
    <div class="table-container" v-else>
      <!-- Desktop table view -->
      <table class="desktop-table">
        <thead>
          <tr>
            <th>模型名称</th>
            <th>显示名称</th>
            <th>状态</th>
            <th>请求次数</th>
            <th>Token 用量</th>
            <th>近30次平均响应</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="m in models" :key="m.id">
            <td><code class="model-tag">{{ m.modelName }}</code></td>
            <td>{{ m.displayName || m.modelName }}</td>
            <td><span class="badge badge-success">已关联</span></td>
            <td style="text-align:right;font-variant-numeric:tabular-nums;">
              <span style="font-weight:600;">{{ formatNumber(getModelStat(m.modelName)?.requestCount) }}</span>
            </td>
            <td style="font-size:12px;font-variant-numeric:tabular-nums;">
              <template v-if="getModelStat(m.modelName)?.totalTokens">
                <div style="display:flex;flex-direction:column;gap:2px;">
                  <span :title="`输入: ${formatNumber(getModelStat(m.modelName)?.promptTokens)} | 输出: ${formatNumber(getModelStat(m.modelName)?.completionTokens)}`">
                    {{ formatTokens(getModelStat(m.modelName)?.totalTokens) }}
                  </span>
                  <span style="color:var(--text-muted);font-size:11px;">
                    入 {{ formatTokens(getModelStat(m.modelName)?.promptTokens) }} / 出 {{ formatTokens(getModelStat(m.modelName)?.completionTokens) }}
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
            <span class="badge badge-success">已关联</span>
          </div>
          <div class="mobile-card-model-name">
            模型: <code class="model-tag">{{ m.modelName }}</code>
          </div>
          <div class="mobile-card-divider"></div>
          <div class="mobile-card-stats">
            <div class="mobile-stat">
              <span class="mobile-stat-label">请求</span>
              <span class="mobile-stat-value">{{ formatNumber(getModelStat(m.modelName)?.requestCount) }}</span>
            </div>
            <div class="mobile-stat">
              <span class="mobile-stat-label">Token</span>
              <span class="mobile-stat-value">{{ formatTokens(getModelStat(m.modelName)?.totalTokens) }}</span>
            </div>
            <div class="mobile-stat">
              <span class="mobile-stat-label">响应</span>
              <span class="mobile-stat-value">{{ formatResponseTime(getModelStat(m.modelName)?.avgResponseTimeRecent30) }}</span>
            </div>
          </div>
        </div>
      </div>
    </div>
  </div>

  <!-- 通用弹框 -->
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
import { channelApi, type Channel, type ChannelModel, type ModelUsageStat } from '@/api/channel'
import Dialog from '@/components/common/Dialog.vue'

const route = useRoute()
const router = useRouter()
const channel = ref<Channel | null>(null)
const models = ref<ChannelModel[]>([])
const modelStats = ref<ModelUsageStat[]>([])
const channelAvgResponseTimeRecent30 = ref<number>(0)

/* ---------- 弹框状态 ---------- */
const dialogVisible = ref(false)
const dialogTitle = ref('提示')
const dialogMessage = ref('')
const dialogType = ref<'alert' | 'confirm'>('alert')
let dialogOnConfirm: (() => void) | null = null

function openDialog(opts: {
  title?: string
  message: string
  type?: 'alert' | 'confirm'
  onConfirm?: () => void
}) {
  dialogTitle.value = opts.title ?? '提示'
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

/** 按模型名查找用量统计 */
function getModelStat(modelName: string): ModelUsageStat | undefined {
  return modelStats.value.find(s => s.modelName === modelName)
}

/** 汇总请求次数 */
const totalRequestCount = computed(() =>
  modelStats.value.reduce((sum, s) => sum + s.requestCount, 0)
)
/** 汇总总 token */
const totalTokens = computed(() =>
  modelStats.value.reduce((sum, s) => sum + s.totalTokens, 0)
)
/** 汇总输入 token */
const totalPromptTokens = computed(() =>
  modelStats.value.reduce((sum, s) => sum + s.promptTokens, 0)
)
/** 汇总输出 token */
const totalCompletionTokens = computed(() =>
  modelStats.value.reduce((sum, s) => sum + s.completionTokens, 0)
)

/** 格式化数字，千分位 */
function formatNumber(n: number | undefined): string {
  if (n == null) return '0'
  return n.toLocaleString()
}

/** 格式化 token 数量，大数字用 K/M 缩写 */
function formatTokens(n: number | undefined): string {
  if (n == null || n === 0) return '0'
  if (n >= 1_000_000) return (n / 1_000_000).toFixed(1) + 'M'
  if (n >= 1_000) return (n / 1_000).toFixed(1) + 'K'
  return n.toString()
}

/** 格式化响应时间：>=1000ms 显示秒，否则显示毫秒 */
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
    openDialog({ title: '加载失败', message: e.message })
    router.push('/admin/channel/list')
  }
})
</script>

<style scoped>
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
  background: var(--bg-card, #fff);
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
</style>
