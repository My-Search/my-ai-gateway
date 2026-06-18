<template>
  <div class="card">
    <div class="card-header">
      <div class="card-title">{{ t('channel.list.title') }}</div>
      <router-link to="/admin/channel/form" class="btn btn-primary"><SvgIcon name="plus" :size="14" /> {{ t('channel.list.add') }}</router-link>
    </div>
    <div class="table-container">
      <table>
        <thead>
          <tr>
            <th>ID</th>
            <th>{{ t('channel.list.name') }}</th>
            <th>{{ t('channel.list.type') }}</th>
            <th>{{ t('channel.list.endpoint') }}</th>
            <th>{{ t('channel.list.status') }}</th>
            <th>{{ t('channel.list.modelCount') }}</th>
            <th>{{ t('channel.list.requestCount') }}</th>
            <th>{{ t('channel.list.tokenUsage') }}</th>
            <th>{{ t('channel.list.createdAt') }}</th>
            <th>{{ t('channel.list.actions') }}</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="ch in channels" :key="ch.id">
            <td style="color:var(--text-muted);">{{ ch.id }}</td>
            <td><strong>{{ ch.name }}</strong></td>
            <td><span class="badge badge-info">{{ ch.channelType }}</span></td>
            <td style="max-width:200px;overflow:hidden;text-overflow:ellipsis;font-size:12px;color:var(--text-muted);">
              {{ ch.baseUrl }}
            </td>
            <td>
              <span v-if="ch.enabled === 1" class="badge badge-success">
                <span class="status-dot active"></span>{{ t('common.enabled') }}
              </span>
              <span v-else class="badge badge-danger">
                <span class="status-dot inactive"></span>{{ t('common.disabled') }}
              </span>
            </td>
            <td>
              <router-link :to="`/admin/channel/models/${ch.id}`" class="btn btn-sm btn-secondary">{{ t('channel.list.view') }}</router-link>
            </td>
            <td style="text-align:right;font-variant-numeric:tabular-nums;">
              <span style="font-weight:600;">{{ formatNumber(ch.requestCount) }}</span>
            </td>
            <td style="font-size:12px;font-variant-numeric:tabular-nums;">
              <template v-if="ch.totalTokens && ch.totalTokens > 0">
                <div style="display:flex;flex-direction:column;gap:2px;">
                  <span :title="t('channel.models.inputTokens') + ': ' + formatNumber(ch.promptTokens) + ' | ' + t('channel.models.outputTokens') + ': ' + formatNumber(ch.completionTokens)">
                    {{ formatTokens(ch.totalTokens) }}
                  </span>
                  <span style="color:var(--text-muted);font-size:11px;">
                    {{ t('channel.models.inputTokens') }} {{ formatTokens(ch.promptTokens) }} / {{ t('channel.models.outputTokens') }} {{ formatTokens(ch.completionTokens) }}
                  </span>
                </div>
              </template>
              <span v-else style="color:var(--text-muted);">-</span>
            </td>
            <td style="font-size:12px;color:var(--text-muted);">{{ ch.createdAt }}</td>
            <td>
              <div style="display:flex;gap:6px;flex-wrap:nowrap;">
                <router-link :to="`/admin/channel/form/${ch.id}`" class="btn btn-sm btn-secondary"><SvgIcon name="edit" :size="14" /> {{ t('common.edit') }}</router-link>
                <button class="btn btn-sm btn-success" @click="quickTest(ch)"><SvgIcon name="zap" :size="14" /> {{ t('channel.list.quickTest') }}</button>
                <router-link :to="`/admin/channel/reload/${ch.id}`" class="btn btn-sm btn-secondary"
                  @click.prevent="reloadModels(ch.id!)"><SvgIcon name="refresh" :size="14" /> {{ t('channel.list.refreshModels') }}</router-link>
                <button class="btn btn-sm btn-danger" @click="confirmDelete(ch)"><SvgIcon name="trash" :size="14" /> {{ t('common.delete') }}</button>
              </div>
            </td>
          </tr>
          <tr v-if="!channels.length">
            <td colspan="10" style="text-align:center;color:var(--text-muted);padding:40px;">
              {{ t('channel.list.empty') }}
            </td>
          </tr>
        </tbody>
      </table>
    </div>

    <!-- Mobile card list (shown on ≤768px) -->
    <div class="mobile-card-list">
      <div v-for="ch in channels" :key="'m-' + ch.id" class="mobile-card">
        <div class="mobile-card-header">
          <strong class="mobile-card-title">{{ ch.name }}</strong>
          <span v-if="ch.enabled === 1" class="badge badge-success">
            <span class="status-dot active"></span>{{ t('common.enabled') }}
          </span>
          <span v-else class="badge badge-danger">
            <span class="status-dot inactive"></span>{{ t('common.disabled') }}
          </span>
        </div>
        <div class="mobile-card-body">
          <div class="mobile-card-row">
            <span class="mobile-card-label">{{ t('channel.list.type') }}</span>
            <span class="badge badge-info">{{ ch.channelType }}</span>
          </div>
          <div class="mobile-card-row">
            <span class="mobile-card-label">{{ t('channel.list.endpoint') }}</span>
            <span class="mobile-card-value mobile-card-url">{{ ch.baseUrl }}</span>
          </div>
        </div>
        <div class="mobile-card-divider"></div>
        <div class="mobile-card-stats">
          <div class="mobile-card-stat">
            <span class="mobile-card-stat-label">{{ t('channel.list.requestCount') }}</span>
            <span class="mobile-card-stat-value">{{ formatNumber(ch.requestCount) }}</span>
          </div>
          <div class="mobile-card-stat">
            <span class="mobile-card-stat-label">{{ t('channel.list.tokenUsage') }}</span>
            <span class="mobile-card-stat-value">{{ formatTokens(ch.totalTokens) }}</span>
          </div>
          <div class="mobile-card-stat">
            <span class="mobile-card-stat-label">{{ t('channel.list.createdAt') }}</span>
            <span class="mobile-card-stat-value">{{ ch.createdAt }}</span>
          </div>
        </div>
        <div class="mobile-card-divider"></div>
        <div class="mobile-card-actions">
          <router-link :to="`/admin/channel/form/${ch.id}`" class="btn btn-sm btn-secondary"><SvgIcon name="edit" :size="14" /> {{ t('common.edit') }}</router-link>
          <button class="btn btn-sm btn-success" @click="quickTest(ch)"><SvgIcon name="zap" :size="14" /> {{ t('channel.list.quickTest') }}</button>
          <router-link :to="`/admin/channel/reload/${ch.id}`" class="btn btn-sm btn-secondary"
            @click.prevent="reloadModels(ch.id!)"><SvgIcon name="refresh" :size="14" /> {{ t('channel.list.refresh') }}</router-link>
          <button class="btn btn-sm btn-danger" @click="confirmDelete(ch)"><SvgIcon name="trash" :size="14" /> {{ t('common.delete') }}</button>
        </div>
      </div>
      <div v-if="!channels.length" class="mobile-card-empty">
        {{ t('channel.list.empty') }}
      </div>
    </div>

    <!-- Quick Test Modal -->
    <div v-if="showTestModal" class="modal-overlay" @click.self="closeTestModal">
      <div class="modal-box">
        <div class="modal-header">
          <SvgIcon name="zap" :size="18" /> {{ t('channel.list.quickTest') }}
          <button class="modal-close" @click="closeTestModal">&times;</button>
        </div>
        <div style="font-size:13px;color:var(--text-muted);margin-bottom:12px;">
          {{ t('channel.list.name') }}: {{ testChannel?.name }}
        </div>
        <div class="form-group">
          <label>{{ t('channel.list.testMessage') }}</label>
          <textarea v-model="testMessage" class="form-control" rows="3"></textarea>
        </div>
        <div v-if="testResult" class="test-result" :class="testResult.success ? 'success' : 'error'">
          <template v-if="testResult.success">
            <SvgIcon name="check-bold" :size="16" /> {{ t('channel.list.testSuccess') }} ({{ testResult.responseTime }}ms)
            <pre>{{ testResult.response }}</pre>
          </template>
          <template v-else>
            <SvgIcon name="x" :size="16" /> {{ t('channel.list.testFail') }}
            <pre>{{ testResult.error }}</pre>
          </template>
        </div>
        <div class="modal-actions">
          <button class="btn btn-secondary" @click="closeTestModal"><SvgIcon name="x" :size="14" /> {{ t('common.close') }}</button>
          <button class="btn btn-primary" :disabled="testLoading" @click="sendTestRequest">
            <SvgIcon name="send" :size="14" /> {{ testLoading ? t('channel.list.testing') : t('channel.list.sendTest') }}
          </button>
        </div>
      </div>
    </div>
  </div>

  <!-- Dialog -->
  <Dialog
    v-model="dialogVisible"
    :title="dialogTitle"
    :type="dialogType"
    :confirm-class="dialogConfirmClass"
    @confirm="onDialogConfirm"
  >
    {{ dialogMessage }}
  </Dialog>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { useI18n } from '@/composables/useI18n'
import { channelApi, type Channel } from '@/api/channel'
import Dialog from '@/components/common/Dialog.vue'

const router = useRouter()
const { t } = useI18n()
const channels = ref<Channel[]>([])
const showTestModal = ref(false)
const testChannel = ref<Channel | null>(null)
const testMessage = ref('Hello, this is a test message.')
const testResult = ref<{ success: boolean; response?: string; responseTime?: number; error?: string } | null>(null)
const testLoading = ref(false)

/* ---------- Dialog state ---------- */
const dialogVisible = ref(false)
const dialogTitle = ref(t('dialog.title'))
const dialogMessage = ref('')
const dialogType = ref<'alert' | 'confirm'>('alert')
const dialogConfirmClass = ref('btn-primary')
let dialogOnConfirm: (() => void) | null = null

function openDialog(opts: {
  title?: string
  message: string
  type?: 'alert' | 'confirm'
  confirmClass?: string
  onConfirm?: () => void
}) {
  dialogTitle.value = opts.title ?? t('dialog.title')
  dialogMessage.value = opts.message
  dialogType.value = opts.type ?? 'alert'
  dialogConfirmClass.value = opts.confirmClass ?? 'btn-primary'
  dialogOnConfirm = opts.onConfirm ?? null
  dialogVisible.value = true
}

function onDialogConfirm() {
  dialogOnConfirm?.()
  dialogOnConfirm = null
}
/* ------------------------------ */

async function loadChannels() {
  try {
    const res = await channelApi.list()
    channels.value = res.data
  } catch (e: any) {
    openDialog({ title: t('error.loadFailed'), message: t('error.loadFailed') + ': ' + e.message })
  }
}

async function quickTest(ch: Channel) {
  testChannel.value = ch
  testMessage.value = 'Hello, this is a test message.'
  testResult.value = null
  showTestModal.value = true
}

function closeTestModal() {
  showTestModal.value = false
  testChannel.value = null
}

async function sendTestRequest() {
  if (!testChannel.value?.id) return
  testLoading.value = true
  try {
    const res = await channelApi.quickTest(testChannel.value.id, testMessage.value, undefined)
    testResult.value = res.data
  } catch (e: any) {
    testResult.value = { success: false, error: e.message }
  } finally {
    testLoading.value = false
  }
}

function reloadModels(id: number) {
  openDialog({
    title: t('channel.list.reloadConfirm'),
    message: t('channel.list.reloadMsg'),
    type: 'confirm',
    confirmClass: 'btn-danger',
    onConfirm: async () => {
      try {
        const res = await channelApi.reloadModels(id)
        openDialog({ message: res.data.success ? t('channel.list.reloadSuccess') : t('error.loadFailed') + ': ' + res.data.error })
      } catch (e: any) {
        openDialog({ title: t('error.unknown'), message: e.message })
      }
    }
  })
}

function confirmDelete(ch: Channel) {
  openDialog({
    title: t('common.confirmDelete'),
    message: t('channel.list.deleteConfirm').replace('{name}', ch.name),
    type: 'confirm',
    confirmClass: 'btn-danger',
    onConfirm: () => {
      channelApi.delete(ch.id!).then(() => loadChannels()).catch(e =>
        openDialog({ title: t('error.deleteFailed'), message: e.message })
      )
    }
  })
}

/** Format number with thousands separator */
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

onMounted(loadChannels)
</script>

<style scoped>
.modal-overlay {
  position: fixed; top: 0; left: 0; right: 0; bottom: 0;
  background: rgba(0,0,0,0.6); z-index: 1000;
  display: flex; align-items: center; justify-content: center;
}
.modal-box {
  background: var(--bg-secondary); border: 1px solid var(--border-color);
  border-radius: 12px; padding: 24px; width: 480px; max-width: 90vw;
}
.modal-header {
  display: flex; align-items: center; gap: 8px;
  font-size: 16px; font-weight: 600; margin-bottom: 16px;
  justify-content: space-between;
}
.modal-close {
  background: none; border: none; color: var(--text-muted);
  cursor: pointer; font-size: 18px;
}
.modal-actions {
  display: flex; gap: 8px; margin-top: 16px; justify-content: flex-end;
}
.test-result { margin-top: 12px; padding: 12px; border-radius: 8px; font-size: 13px; max-height: 200px; overflow-y: auto; }
.test-result.success { background: rgba(63,185,80,0.1); color: var(--accent-green); }
.test-result.error { background: rgba(248,81,73,0.1); color: var(--accent-red); }
.test-result pre { margin-top: 8px; white-space: pre-wrap; word-break: break-all; font-size: 12px; }

/* ── Mobile card list ── */
.mobile-card-list { display: none; }

.mobile-card {
  background: var(--bg-secondary);
  border: 1px solid var(--border-color);
  border-radius: 10px;
  padding: 16px;
}

.mobile-card-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  margin-bottom: 12px;
}

.mobile-card-title {
  font-size: 15px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  flex: 1;
  min-width: 0;
}

.mobile-card-body {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.mobile-card-row {
  display: flex;
  align-items: baseline;
  gap: 8px;
  font-size: 13px;
}

.mobile-card-label {
  color: var(--text-muted);
  flex-shrink: 0;
}

.mobile-card-label::after {
  content: ':';
}

.mobile-card-value {
  color: var(--text-secondary);
  min-width: 0;
}

.mobile-card-url {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-size: 12px;
}

.mobile-card-divider {
  height: 1px;
  background: var(--border-color);
  margin: 12px 0;
}

.mobile-card-stats {
  display: flex;
  justify-content: space-between;
  gap: 8px;
}

.mobile-card-stat {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 2px;
  flex: 1;
  min-width: 0;
}

.mobile-card-stat-label {
  font-size: 11px;
  color: var(--text-muted);
}

.mobile-card-stat-value {
  font-size: 13px;
  font-weight: 600;
  font-variant-numeric: tabular-nums;
  text-align: center;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  max-width: 100%;
}

.mobile-card-actions {
  display: flex;
  gap: 6px;
  justify-content: center;
  flex-wrap: wrap;
}

.mobile-card-empty {
  text-align: center;
  color: var(--text-muted);
  padding: 40px 16px;
  font-size: 14px;
}

/* Responsive: mobile shows cards, desktop shows table */
@media (max-width: 768px) {
  .table-container table { display: none; }
  .mobile-card-list { display: flex; flex-direction: column; gap: 12px; }
}

@media (min-width: 769px) {
  .mobile-card-list { display: none; }
}
</style>
