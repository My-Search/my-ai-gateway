<template>
  <div class="card">
    <div class="card-header">
      <div class="card-title"><SvgIcon name="key" :size="18" /> {{ t('apikey.list.title') }}</div>
      <button class="btn btn-primary" @click="openForm()"><SvgIcon name="plus" :size="14" /> {{ t('apikey.list.add') }}</button>
    </div>
    <div class="alert alert-info">
      {{ t('apikey.list.desc') }}
    </div>

    <!-- Loading state -->
    <div v-if="loading" class="loading-container">
      <LoadingSpinner :size="32" />
    </div>

    <template v-else>
    <div class="table-container">
      <table>
        <thead>
          <tr>
            <th>{{ t('apikey.list.keyName') }}</th>
            <th>{{ t('apikey.list.keyValue') }}</th>
            <th>{{ t('apikey.list.status') }}</th>
            <th>{{ t('apikey.list.share') }}</th>
            <th>{{ t('apikey.list.lastUsed') }}</th>
            <th>{{ t('apikey.list.createdAt') }}</th>
            <th>{{ t('apikey.list.actions') }}</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="key in apiKeys" :key="key.id">
            <td><strong>{{ key.keyName }}</strong></td>
            <td>
              <code class="model-tag" style="user-select:all;cursor:pointer;" @click="copyKey(key.keyValue)">
                {{ maskKey(key.keyValue) }}
              </code>
              <CopyButton :text="key.keyValue" :title="t('apikey.list.copy')" />
            </td>
            <td>
              <span v-if="key.enabled === 1" class="badge badge-success">
                <span class="status-dot active"></span>{{ t('common.enabled') }}
              </span>
              <span v-else class="badge badge-danger">
                <span class="status-dot inactive"></span>{{ t('common.disabled') }}
              </span>
            </td>
            <td>
              <div style="display:flex;align-items:center;gap:6px;white-space:nowrap;">
                <ToggleSwitch
                  :model-value="key.shared === 1"
                  size="sm"
                  :show-label="false"
                  :title="key.shared === 1 ? t('apikey.list.clickToUnshare') : t('apikey.list.clickToShare')"
                  :disabled="shareToggling === key.id"
                  @update:model-value="toggleShare(key)"
                />
                <button
                  v-if="key.shared === 1"
                  class="btn-icon-link"
                  :title="t('apikey.list.copyShareLink')"
                  @click="shareKey(key)"
                >
                  <SvgIcon name="link" :size="14" />
                </button>
              </div>
            </td>
            <td style="font-size:12px;color:var(--text-muted);">{{ key.lastUsedAt ? formatLocalDateTimeFull(key.lastUsedAt) : t('apikey.list.neverUsed') }}</td>
            <td style="font-size:12px;color:var(--text-muted);">{{ formatLocalDateTimeFull(key.createdAt) }}</td>
            <td>
              <div style="display:flex;gap:6px;align-items:center;flex-wrap:wrap;">
                <router-link :to="`/admin/apikey/usage/${key.id}`" class="btn btn-sm btn-secondary"><SvgIcon name="detail" :size="14" /> {{ t('apikey.list.view') }}</router-link>
                <button class="btn btn-sm btn-secondary" @click="openForm(key)"><SvgIcon name="edit" :size="14" /> {{ t('apikey.list.edit') }}</button>
                <button class="btn btn-sm btn-danger" @click="confirmDelete(key)"><SvgIcon name="trash" :size="14" /> {{ t('apikey.list.delete') }}</button>
              </div>
            </td>
          </tr>
          <tr v-if="!apiKeys.length">
            <td colspan="7" style="text-align:center;color:var(--text-muted);padding:40px;">
              {{ t('apikey.list.empty') }}
            </td>
          </tr>
        </tbody>
      </table>
    </div>

    <!-- Mobile card list -->
    <div class="mobile-card-list">
      <div v-for="key in apiKeys" :key="key.id" class="mobile-card">
        <div class="mobile-card-header">
          <strong class="mobile-card-title">{{ key.keyName }}</strong>
          <span v-if="key.enabled === 1" class="badge badge-success">
            <span class="status-dot active"></span>{{ t('common.enabled') }}
          </span>
          <span v-else class="badge badge-danger">
            <span class="status-dot inactive"></span>{{ t('common.disabled') }}
          </span>
        </div>
        <div class="mobile-card-row">
          <span class="mobile-card-label">{{ t('apikey.list.keyValue') }}</span>
          <code class="model-tag" style="user-select:all;cursor:pointer;" @click="copyKey(key.keyValue)">
            {{ maskKey(key.keyValue) }}
          </code>
        </div>
        <div class="mobile-card-row">
          <span class="mobile-card-label">{{ t('apikey.list.lastUsed') }}</span>
          <span class="mobile-card-value">{{ key.lastUsedAt ? formatLocalDateTimeFull(key.lastUsedAt) : t('apikey.list.neverUsed') }}</span>
        </div>
        <div class="mobile-card-row">
          <span class="mobile-card-label">{{ t('apikey.list.createdAt') }}</span>
          <span class="mobile-card-value">{{ formatLocalDateTimeFull(key.createdAt) }}</span>
        </div>
        <div class="mobile-card-row">
          <span class="mobile-card-label">{{ t('apikey.list.share') }}</span>
          <span class="mobile-card-value" style="display:flex;align-items:center;gap:8px;">
            <ToggleSwitch
              :model-value="key.shared === 1"
              size="sm"
              :show-label="false"
              :title="key.shared === 1 ? t('apikey.list.clickToUnshare') : t('apikey.list.clickToShare')"
              :disabled="shareToggling === key.id"
              @update:model-value="toggleShare(key)"
            />
            <button
              v-if="key.shared === 1"
              class="btn-icon-link"
              :title="t('apikey.list.copyShareLink')"
              @click="shareKey(key)"
            >
              <SvgIcon name="link" :size="14" />
            </button>
          </span>
        </div>
        <div class="mobile-card-divider"></div>
        <div class="mobile-card-actions">
          <router-link :to="`/admin/apikey/usage/${key.id}`" class="btn btn-sm btn-secondary"><SvgIcon name="list" :size="14" /> {{ t('apikey.list.view') }}</router-link>
          <button class="btn btn-sm btn-secondary" @click="openForm(key)"><SvgIcon name="edit" :size="14" /> {{ t('apikey.list.edit') }}</button>
          <button class="btn btn-sm btn-danger" @click="confirmDelete(key)"><SvgIcon name="trash" :size="14" /> {{ t('apikey.list.delete') }}</button>
        </div>
      </div>
      <div v-if="!apiKeys.length" class="mobile-card-empty">
        {{ t('apikey.list.empty') }}
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
    :confirm-text="confirmText"
    @confirm="onConfirm"
  >
    {{ message }}
  </Dialog>

  <!-- Form Dialog -->
  <Dialog
    v-model="formDialogVisible"
    :title="formDialogTitle"
    type="confirm"
    :confirm-text="t('apikey.form.save')"
    width="520px"
    @confirm="handleSave"
    @cancel="closeForm"
  >
    <form @submit.prevent="handleSave" style="margin-top:8px;">
      <div class="form-group">
        <label for="keyName">{{ t('apikey.form.keyName') }}</label>
        <input id="keyName" v-model="form.keyName" class="form-control" :placeholder="t('apikey.form.keyNamePlaceholder')" required />
      </div>
      <div class="form-group">
        <label for="keyValue">{{ t('apikey.form.keyValue') }}</label>
        <input id="keyValue" v-model="form.keyValue" class="form-control" :disabled="isEdit" :placeholder="isEdit ? t('apikey.form.keyValueDisabledHint') : t('apikey.form.keyValuePlaceholder')" />
        <div class="form-hint">{{ t('apikey.form.keyValueHint') }}, {{ isEdit ? t('apikey.form.editNoModify') : t('apikey.form.autoGenerate') }}</div>
      </div>
      <div class="form-group" style="margin-bottom:0;">
        <label for="enabled">{{ t('apikey.list.status') }}</label>
        <select id="enabled" v-model.number="form.enabled" class="form-control">
          <option :value="1">{{ t('common.enabled') }}</option>
          <option :value="0">{{ t('common.disabled') }}</option>
        </select>
      </div>
    </form>
  </Dialog>
</template>

<script setup lang="ts">
import { ref, onMounted, onActivated } from 'vue'
import { useRouter } from 'vue-router'
import { apikeyApi, type ApiKey } from '@/api/apikey'
import { shareApi } from '@/api/share'
import Dialog from '@/components/common/Dialog.vue'
import CopyButton from '@/components/common/CopyButton.vue'
import LoadingSpinner from '@/components/common/LoadingSpinner.vue'
import ToggleSwitch from '@/components/common/ToggleSwitch.vue'
import { useI18n } from '@/composables/useI18n'
import { useDialog } from '@/composables/useDialog'
import { useToast } from '@/composables/useToast'
import { formatLocalDateTimeFull } from '@/utils/date'

const { t } = useI18n()
const { visible, title, message, type, confirmClass, confirmText, onConfirm, open } = useDialog()
const { showToast } = useToast()

const router = useRouter()

const apiKeys = ref<ApiKey[]>([])
const loading = ref(true)
/** 切换分享状态：按钮防重复点击用的 id */
const shareToggling = ref<number | null>(null)

/* ---------- Form Dialog state ---------- */
const formDialogVisible = ref(false)
const formDialogTitle = ref(t('apikey.form.addTitle'))
const isEdit = ref(false)
const editId = ref<number | null>(null)
const saving = ref(false)
const form = ref<Partial<ApiKey>>({ keyName: '', keyValue: '', enabled: 1 })

/** Open form dialog */
function openForm(key?: ApiKey) {
  if (key?.id) {
    isEdit.value = true
    editId.value = key.id
    formDialogTitle.value = t('apikey.form.editTitle')
    form.value = { keyName: key.keyName, keyValue: key.keyValue, enabled: key.enabled }
  } else {
    isEdit.value = false
    editId.value = null
    formDialogTitle.value = t('apikey.form.addTitle')
    form.value = { keyName: '', keyValue: '', enabled: 1 }
  }
  formDialogVisible.value = true
}

function closeForm() {
  formDialogVisible.value = false
}

async function handleSave() {
  if (!form.value.keyName) {
    open({ title: t('common.prompt'), message: t('apikey.list.inputName') })
    return
  }
  saving.value = true
  try {
    const payload = { ...form.value }
    // Key value cannot be modified in edit mode
    if (isEdit.value) {
      delete payload.keyValue
    }
    if (isEdit.value && editId.value) {
      await apikeyApi.update(editId.value, payload)
    } else {
      await apikeyApi.create(payload)
    }
    formDialogVisible.value = false
    loadKeys()
  } catch (e: any) {
    open({ title: t('apikey.form.saveFailed'), message: e.message })
  } finally {
    saving.value = false
  }
}
/* ------------------------------ */

function maskKey(key: string) {
  if (!key) return ''
  if (key.length > 30) return key.substring(0, 15) + '...'
  return key
}

async function copyKey(val: string) {
  try {
    await navigator.clipboard.writeText(val)
    showToast(t('common.copySuccess'))
  } catch {
    open({ message: t('apikey.list.manualCopyFailed') })
  }
}

/**
 * Copy share link (use shareCode instead of raw key to avoid key exposure over network)
 */
function shareKey(key: ApiKey) {
  const code = key.shareCode || ''
  if (!code) {
    open({ title: t('common.prompt'), message: t('apikey.list.shareCodeMissing') })
    return
  }
  const shareUrl = `${window.location.origin}/share/${encodeURIComponent(code)}`
  navigator.clipboard.writeText(shareUrl).then(() => {
    showToast(t('apikey.list.shareLinkCopied'))
  }).catch(() => {
    open({ message: t('apikey.list.shareLinkCopyFailed') })
  })
}

/**
 * 切换分享开关：开启时自动复制分享链接；关闭需二次确认（会使分享链接失效）
 */
function toggleShare(key: ApiKey) {
  if (!key.id) return
  if (key.shared === 1) {
    open({
      title: t('apikey.list.revokeConfirm'),
      message: t('apikey.list.revokeMsg', { name: key.keyName }),
      type: 'confirm',
      confirmClass: 'btn-warning',
      confirmText: t('apikey.list.confirmRevoke'),
      onConfirm: () => revokeShare(key)
    })
  } else {
    enableShare(key)
  }
}

/**
 * Enable sharing first, then copy the link
 */
async function enableShare(key: ApiKey) {
  if (!key.id) return
  shareToggling.value = key.id
  try {
    const res = await shareApi.toggleShare(key.id, true)
    if (!res.data.success) {
      open({ title: t('error.unknown'), message: (res.data as any).error || t('apikey.list.enableShareFailed') })
      return
    }
    // Update local data with shareCode from backend
    key.shared = 1
    key.shareCode = (res.data as any).shareCode || key.shareCode
    shareKey(key)
    // Refresh list to update status
    loadKeys()
  } catch (e: any) {
    open({ title: t('error.unknown'), message: e.message })
  } finally {
    shareToggling.value = null
  }
}

/**
 * Execute revoke share
 */
async function revokeShare(key: ApiKey) {
  if (!key.id) return
  shareToggling.value = key.id
  try {
    const res = await shareApi.toggleShare(key.id, false)
    if (!res.data.success) {
      open({ title: t('error.unknown'), message: (res.data as any).error || t('apikey.list.revokeFailed') })
      return
    }
    key.shared = 0
    showToast(t('apikey.list.revokeSuccess'))
    loadKeys()
  } catch (e: any) {
    open({ title: t('error.unknown'), message: e.message })
  } finally {
    shareToggling.value = null
  }
}

function confirmDelete(key: ApiKey) {
  open({
    title: t('common.confirmDelete'),
    message: t('apikey.list.deleteConfirm', { name: key.keyName }),
    type: 'confirm',
    confirmClass: 'btn-danger',
    onConfirm: () => {
      apikeyApi.delete(key.id!).then(() => loadKeys()).catch(e =>
        open({ title: t('error.deleteFailed'), message: e.message })
      )
    }
  })
}

async function loadKeys() {
  loading.value = true
  try {
    const listRes = await apikeyApi.list()
    apiKeys.value = listRes.data
  } catch (e: any) {
    open({ title: t('error.loadFailed'), message: e.message })
  } finally {
    loading.value = false
  }
}

// 供 keep-alive 按组件名缓存（Layout.vue cachedViews）
defineOptions({ name: 'ApikeyList' })

// onMounted 负责首次加载（保证页面一定有数据，不依赖 keep-alive 是否命中）；
// onActivated 仅在 keep-alive 缓存恢复（菜单切回）时刷新数据，首次跳过避免重复加载
let activatedCount = 0
onMounted(loadKeys)
onActivated(() => {
  if (activatedCount++ > 0) loadKeys()
})
</script>

<style scoped>
/* Loading state */
.loading-container {
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 60px 0;
}

/* Warning button (revoke share) */
:deep(.btn-warning) {
  background: #f59e0b; color: #fff; border-color: #d97706;
}
:deep(.btn-warning:hover) {
  background: #d97706;
}

/* 分享列：链接图标按钮（复制分享链接） */
.btn-icon-link {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  padding: 4px 6px;
  background: transparent;
  border: 1px solid var(--border-color);
  border-radius: 4px;
  color: var(--accent-blue);
  cursor: pointer;
  transition: all 0.15s;
}
.btn-icon-link:hover {
  background: color-mix(in srgb, var(--accent-blue) 15%, transparent);
  border-color: var(--accent-blue);
}

/* Mobile card list */
.mobile-card-list {
  display: none;
  flex-direction: column;
  gap: 12px;
}

.mobile-card {
  background: var(--bg-secondary);
  border: 1px solid var(--border-color);
  border-radius: 8px;
  padding: 14px 16px;
}

.mobile-card-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 10px;
}

.mobile-card-title {
  font-size: 15px;
  color: var(--text-primary);
}

.mobile-card-row {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 6px;
  font-size: 13px;
}

.mobile-card-label {
  color: var(--text-muted);
  flex-shrink: 0;
  min-width: 56px;
}

.mobile-card-value {
  color: var(--text-secondary);
  word-break: break-all;
}

.mobile-card-divider {
  height: 1px;
  background: var(--border-color);
  margin: 10px 0;
}

.mobile-card-actions {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
  flex-wrap: wrap;
}

.mobile-card-empty {
  text-align: center;
  color: var(--text-muted);
  padding: 40px 16px;
  font-size: 14px;
}

@media (max-width: 768px) {
  .table-container table {
    display: none;
  }
  .mobile-card-list {
    display: flex;
  }
}

@media (min-width: 769px) {
  .mobile-card-list {
    display: none;
  }
}
</style>
