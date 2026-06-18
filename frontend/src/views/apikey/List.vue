<template>
  <div class="card">
    <div class="card-header">
      <div class="card-title">{{ t('apikey.list.title') }}</div>
      <button class="btn btn-primary" @click="openForm()"><SvgIcon name="plus" :size="14" /> {{ t('apikey.list.add') }}</button>
    </div>
    <div class="alert alert-info">
      {{ t('apikey.list.desc') }}
    </div>
    <div class="table-container">
      <table>
        <thead>
          <tr>
            <th>ID</th>
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
            <td style="color:var(--text-muted);">{{ key.id }}</td>
            <td><strong>{{ key.keyName }}</strong></td>
            <td>
              <code class="model-tag" style="user-select:all;cursor:pointer;" @click="copyKey(key.keyValue)">
                {{ maskKey(key.keyValue) }}
              </code>
              <button class="copy-btn" @click="copyKey(key.keyValue)" :title="t('apikey.list.copy')">
                <SvgIcon name="copy" :size="14" />
              </button>
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
              <span v-if="key.shared === 1" class="share-badge on">{{ t('apikey.list.shared') }}</span>
              <span v-else class="share-badge off">{{ t('apikey.list.notShared') }}</span>
            </td>
            <td style="font-size:12px;color:var(--text-muted);">{{ key.lastUsedAt || t('apikey.list.neverUsed') }}</td>
            <td style="font-size:12px;color:var(--text-muted);">{{ key.createdAt }}</td>
            <td>
              <div style="display:flex;gap:6px;align-items:center;">
                <button v-if="key.shared === 1" class="btn btn-sm btn-secondary" @click="shareKey(key)" :title="t('apikey.list.shareLink')"><SvgIcon name="link" :size="14" /> {{ t('apikey.list.shareLink') }}</button>
                <button v-else class="btn btn-sm btn-secondary" @click="enableShare(key)" :title="t('apikey.list.shareLink')"><SvgIcon name="link" :size="14" /> {{ t('apikey.list.shareLink') }}</button>
                <button v-if="key.shared === 1" class="btn btn-sm btn-warning" @click="confirmRevoke(key)"><SvgIcon name="x-bold" :size="14" /> {{ t('apikey.list.revoke') }}</button>
                <button class="btn btn-sm btn-secondary" @click="openForm(key)"><SvgIcon name="edit" :size="14" /> {{ t('apikey.list.edit') }}</button>
                <button class="btn btn-sm btn-danger" @click="confirmDelete(key)"><SvgIcon name="trash" :size="14" /> {{ t('apikey.list.delete') }}</button>
              </div>
            </td>
          </tr>
          <tr v-if="!apiKeys.length">
            <td colspan="8" style="text-align:center;color:var(--text-muted);padding:40px;">
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
          <span class="mobile-card-value">{{ key.lastUsedAt || t('apikey.list.neverUsed') }}</span>
        </div>
        <div class="mobile-card-row">
          <span class="mobile-card-label">{{ t('apikey.list.createdAt') }}</span>
          <span class="mobile-card-value">{{ key.createdAt }}</span>
        </div>
        <div class="mobile-card-divider"></div>
        <div class="mobile-card-actions">
          <button v-if="key.shared === 1" class="btn btn-sm btn-secondary" @click="shareKey(key)"><SvgIcon name="link" :size="14" /> {{ t('apikey.list.shareLink') }}</button>
          <button v-else class="btn btn-sm btn-secondary" @click="enableShare(key)"><SvgIcon name="link" :size="14" /> {{ t('apikey.list.shareLink') }}</button>
          <button v-if="key.shared === 1" class="btn btn-sm btn-warning" @click="confirmRevoke(key)"><SvgIcon name="x-bold" :size="14" /> {{ t('apikey.list.revoke') }}</button>
          <button class="btn btn-sm btn-secondary" @click="copyKey(key.keyValue)"><SvgIcon name="copy" :size="14" /> {{ t('apikey.list.copy') }}</button>
          <button class="btn btn-sm btn-secondary" @click="openForm(key)"><SvgIcon name="edit" :size="14" /> {{ t('apikey.list.edit') }}</button>
          <button class="btn btn-sm btn-danger" @click="confirmDelete(key)"><SvgIcon name="trash" :size="14" /> {{ t('apikey.list.delete') }}</button>
        </div>
      </div>
      <div v-if="!apiKeys.length" class="mobile-card-empty">
        {{ t('apikey.list.empty') }}
      </div>
    </div>
  </div>

  <!-- Common Dialog -->
  <Dialog
    v-model="dialogVisible"
    :title="dialogTitle"
    :type="dialogType"
    :confirm-class="dialogConfirmClass"
    :confirm-text="dialogConfirmText"
    @confirm="onDialogConfirm"
  >
    {{ dialogMessage }}
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
import { ref, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { apikeyApi, type ApiKey } from '@/api/apikey'
import { shareApi } from '@/api/share'
import Dialog from '@/components/common/Dialog.vue'
import { useI18n } from '@/composables/useI18n'

const { t } = useI18n()

const router = useRouter()

const apiKeys = ref<ApiKey[]>([])

/* ---------- Common Dialog state ---------- */
const dialogVisible = ref(false)
const dialogTitle = ref(t('common.prompt'))
const dialogMessage = ref('')
const dialogType = ref<'alert' | 'confirm'>('alert')
const dialogConfirmClass = ref('btn-primary')
const dialogConfirmText = ref(t('dialog.confirm'))
let dialogOnConfirm: (() => void) | null = null

function openDialog(opts: {
  title?: string
  message: string
  type?: 'alert' | 'confirm'
  confirmClass?: string
  confirmText?: string
  onConfirm?: () => void
}) {
  dialogTitle.value = opts.title ?? t('common.prompt')
  dialogMessage.value = opts.message
  dialogType.value = opts.type ?? 'alert'
  dialogConfirmClass.value = opts.confirmClass ?? 'btn-primary'
  dialogConfirmText.value = opts.confirmText ?? t('dialog.confirm')
  dialogOnConfirm = opts.onConfirm ?? null
  dialogVisible.value = true
}

function onDialogConfirm() {
  dialogOnConfirm?.()
  dialogOnConfirm = null
}
/* ------------------------------ */

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
    openDialog({ title: t('common.prompt'), message: t('apikey.list.inputName') })
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
    openDialog({ title: t('apikey.form.saveFailed'), message: e.message })
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
    const toast = document.createElement('div')
    toast.textContent = t('common.copySuccess')
    Object.assign(toast.style, {
      position: 'fixed', bottom: '24px', left: '50%', transform: 'translateX(-50%)',
      background: '#10b981', color: '#fff', padding: '8px 20px', borderRadius: '8px',
      fontSize: '14px', zIndex: '9999', transition: 'opacity .3s'
    })
    document.body.appendChild(toast)
    setTimeout(() => { toast.style.opacity = '0'; setTimeout(() => toast.remove(), 300) }, 1500)
  } catch {
    openDialog({ message: t('apikey.list.manualCopyFailed') })
  }
}

/**
 * Copy share link (use shareCode instead of raw key to avoid key exposure over network)
 */
function shareKey(key: ApiKey) {
  const code = key.shareCode || ''
  if (!code) {
    openDialog({ title: t('common.prompt'), message: t('apikey.list.shareCodeMissing') })
    return
  }
  const shareUrl = `${window.location.origin}/share/${encodeURIComponent(code)}`
  navigator.clipboard.writeText(shareUrl).then(() => {
    showToastMsg(t('apikey.list.shareLinkCopied'))
  }).catch(() => {
    openDialog({ message: t('apikey.list.shareLinkCopyFailed') })
  })
}

/**
 * Enable sharing first, then copy the link
 */
async function enableShare(key: ApiKey) {
  if (!key.id) return
  try {
    const res = await shareApi.toggleShare(key.id, true)
    if (!res.data.success) {
      openDialog({ title: t('error.unknown'), message: res.data.error || t('apikey.list.enableShareFailed') })
      return
    }
    // Update local data with shareCode from backend
    key.shared = 1
    key.shareCode = res.data.shareCode || key.shareCode
    shareKey(key)
    // Refresh list to update status
    loadKeys()
  } catch (e: any) {
    openDialog({ title: t('error.unknown'), message: e.message })
  }
}

/**
 * Confirm revoke share
 */
function confirmRevoke(key: ApiKey) {
  openDialog({
    title: t('apikey.list.revokeConfirm'),
    message: t('apikey.list.revokeMsg', { name: key.keyName }),
    type: 'confirm',
    confirmClass: 'btn-warning',
    confirmText: t('apikey.list.confirmRevoke'),
    onConfirm: () => revokeShare(key)
  })
}

/**
 * Execute revoke share
 */
async function revokeShare(key: ApiKey) {
  if (!key.id) return
  try {
    const res = await shareApi.toggleShare(key.id, false)
    if (!res.data.success) {
      openDialog({ title: t('error.unknown'), message: res.data.error || t('apikey.list.revokeFailed') })
      return
    }
    key.shared = 0
    showToastMsg(t('apikey.list.revokeSuccess'))
    loadKeys()
  } catch (e: any) {
    openDialog({ title: t('error.unknown'), message: e.message })
  }
}

/**
 * Show toast message
 */
function showToastMsg(text: string) {
  const toast = document.createElement('div')
  toast.textContent = text
  Object.assign(toast.style, {
    position: 'fixed', bottom: '24px', left: '50%', transform: 'translateX(-50%)',
    background: '#10b981', color: '#fff', padding: '8px 20px', borderRadius: '8px',
    fontSize: '14px', zIndex: '9999', transition: 'opacity .3s'
  })
  document.body.appendChild(toast)
  setTimeout(() => { toast.style.opacity = '0'; setTimeout(() => toast.remove(), 300) }, 1500)
}

function confirmDelete(key: ApiKey) {
  openDialog({
    title: t('common.confirmDelete'),
    message: t('apikey.list.deleteConfirm', { name: key.keyName }),
    type: 'confirm',
    confirmClass: 'btn-danger',
    onConfirm: () => {
      apikeyApi.delete(key.id!).then(() => loadKeys()).catch(e =>
        openDialog({ title: t('error.deleteFailed'), message: e.message })
      )
    }
  })
}

async function loadKeys() {
  try {
    const res = await apikeyApi.list()
    apiKeys.value = res.data
  } catch (e: any) {
    openDialog({ title: t('error.loadFailed'), message: e.message })
  }
}

onMounted(loadKeys)
</script>

<style scoped>
.copy-btn {
  display: inline-flex; align-items: center; justify-content: center;
  width: 24px; height: 24px; cursor: pointer; color: var(--text-muted);
  background: var(--bg-tertiary); border: 1px solid var(--border-color);
  border-radius: 4px; padding: 0; margin-left: 8px; vertical-align: middle;
}
.copy-btn:hover { color: var(--accent-blue); border-color: var(--accent-blue); }

/* Warning button (revoke share) */
:deep(.btn-warning) {
  background: #f59e0b; color: #fff; border-color: #d97706;
}
:deep(.btn-warning:hover) {
  background: #d97706;
}

/* Share status badge */
.share-badge {
  display: inline-flex; align-items: center; gap: 4px;
  font-size: 11px; padding: 2px 8px; border-radius: 4px;
}
.share-badge.on { background: color-mix(in srgb, var(--accent-green) 15%, transparent); color: var(--accent-green); }
.share-badge.off { background: color-mix(in srgb, var(--accent-yellow) 15%, transparent); color: var(--accent-yellow); }

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
