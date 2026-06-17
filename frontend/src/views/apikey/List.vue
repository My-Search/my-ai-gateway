<template>
  <div class="card">
    <div class="card-header">
      <div class="card-title">API 密钥列表</div>
      <button class="btn btn-primary" @click="openForm()">+ 添加密钥</button>
    </div>
    <div class="alert alert-info">
      API 密钥用于调用网关的 API。用户请求时需要在 Header 中携带密钥。
    </div>
    <div class="table-container">
      <table>
        <thead>
          <tr>
            <th>ID</th>
            <th>密钥名称</th>
            <th>密钥值</th>
            <th>状态</th>
            <th>分享</th>
            <th>最后使用</th>
            <th>创建时间</th>
            <th>操作</th>
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
              <button class="copy-btn" @click="copyKey(key.keyValue)" title="复制密钥">
                <SvgIcon name="copy" :size="14" />
              </button>
            </td>
            <td>
              <span v-if="key.enabled === 1" class="badge badge-success">
                <span class="status-dot active"></span>启用
              </span>
              <span v-else class="badge badge-danger">
                <span class="status-dot inactive"></span>禁用
              </span>
            </td>
            <td>
              <span v-if="key.shared === 1" class="share-badge on">已分享</span>
              <span v-else class="share-badge off">未分享</span>
            </td>
            <td style="font-size:12px;color:var(--text-muted);">{{ key.lastUsedAt || '从未使用' }}</td>
            <td style="font-size:12px;color:var(--text-muted);">{{ key.createdAt }}</td>
            <td>
              <div style="display:flex;gap:6px;align-items:center;">
                <button v-if="key.shared === 1" class="btn btn-sm btn-secondary" @click="shareKey(key)" title="复制分享链接">分享</button>
                <button v-else class="btn btn-sm btn-secondary" @click="enableShare(key)" title="开启分享并复制链接">分享</button>
                <button v-if="key.shared === 1" class="btn btn-sm btn-warning" @click="confirmRevoke(key)">撤销</button>
                <button class="btn btn-sm btn-secondary" @click="openForm(key)">编辑</button>
                <button class="btn btn-sm btn-danger" @click="confirmDelete(key)">删除</button>
              </div>
            </td>
          </tr>
          <tr v-if="!apiKeys.length">
            <td colspan="8" style="text-align:center;color:var(--text-muted);padding:40px;">
              暂无 API 密钥，点击右上角「添加密钥」创建
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
            <span class="status-dot active"></span>启用
          </span>
          <span v-else class="badge badge-danger">
            <span class="status-dot inactive"></span>禁用
          </span>
        </div>
        <div class="mobile-card-row">
          <span class="mobile-card-label">密钥</span>
          <code class="model-tag" style="user-select:all;cursor:pointer;" @click="copyKey(key.keyValue)">
            {{ maskKey(key.keyValue) }}
          </code>
        </div>
        <div class="mobile-card-row">
          <span class="mobile-card-label">最后使用</span>
          <span class="mobile-card-value">{{ key.lastUsedAt || '从未使用' }}</span>
        </div>
        <div class="mobile-card-row">
          <span class="mobile-card-label">创建时间</span>
          <span class="mobile-card-value">{{ key.createdAt }}</span>
        </div>
        <div class="mobile-card-divider"></div>
        <div class="mobile-card-actions">
          <button v-if="key.shared === 1" class="btn btn-sm btn-secondary" @click="shareKey(key)">分享</button>
          <button v-else class="btn btn-sm btn-secondary" @click="enableShare(key)">分享</button>
          <button v-if="key.shared === 1" class="btn btn-sm btn-warning" @click="confirmRevoke(key)">撤销</button>
          <button class="btn btn-sm btn-secondary" @click="copyKey(key.keyValue)">复制</button>
          <button class="btn btn-sm btn-secondary" @click="openForm(key)">编辑</button>
          <button class="btn btn-sm btn-danger" @click="confirmDelete(key)">删除</button>
        </div>
      </div>
      <div v-if="!apiKeys.length" class="mobile-card-empty">
        暂无 API 密钥，点击右上角「添加密钥」创建
      </div>
    </div>
  </div>

  <!-- 通用弹框 -->
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

  <!-- 表单弹框 -->
  <Dialog
    v-model="formDialogVisible"
    :title="formDialogTitle"
    type="confirm"
    confirm-text="保存"
    width="520px"
    @confirm="handleSave"
    @cancel="closeForm"
  >
    <form @submit.prevent="handleSave" style="margin-top:8px;">
      <div class="form-group">
        <label for="keyName">密钥名称 *</label>
        <input id="keyName" v-model="form.keyName" class="form-control" placeholder="如：生产密钥" required />
      </div>
      <div class="form-group">
        <label for="keyValue">密钥值</label>
        <input id="keyValue" v-model="form.keyValue" class="form-control" :placeholder="isEdit ? '留空则不修改' : '留空则自动生成'" />
        <div class="form-hint">调用 API 时在 Authorization 头部使用 Bearer 此值，留空将自动生成</div>
      </div>
      <div class="form-group" style="margin-bottom:0;">
        <label for="enabled">状态</label>
        <select id="enabled" v-model.number="form.enabled" class="form-control">
          <option :value="1">启用</option>
          <option :value="0">禁用</option>
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

const router = useRouter()

const apiKeys = ref<ApiKey[]>([])

/* ---------- 通用弹框状态 ---------- */
const dialogVisible = ref(false)
const dialogTitle = ref('提示')
const dialogMessage = ref('')
const dialogType = ref<'alert' | 'confirm'>('alert')
const dialogConfirmClass = ref('btn-primary')
const dialogConfirmText = ref('确定')
let dialogOnConfirm: (() => void) | null = null

function openDialog(opts: {
  title?: string
  message: string
  type?: 'alert' | 'confirm'
  confirmClass?: string
  confirmText?: string
  onConfirm?: () => void
}) {
  dialogTitle.value = opts.title ?? '提示'
  dialogMessage.value = opts.message
  dialogType.value = opts.type ?? 'alert'
  dialogConfirmClass.value = opts.confirmClass ?? 'btn-primary'
  dialogConfirmText.value = opts.confirmText ?? '确定'
  dialogOnConfirm = opts.onConfirm ?? null
  dialogVisible.value = true
}

function onDialogConfirm() {
  dialogOnConfirm?.()
  dialogOnConfirm = null
}
/* ------------------------------ */

/* ---------- 表单弹框状态 ---------- */
const formDialogVisible = ref(false)
const formDialogTitle = ref('添加密钥')
const isEdit = ref(false)
const editId = ref<number | null>(null)
const saving = ref(false)
const form = ref<Partial<ApiKey>>({ keyName: '', keyValue: '', enabled: 1 })

/** 打开表单弹框 */
function openForm(key?: ApiKey) {
  if (key?.id) {
    isEdit.value = true
    editId.value = key.id
    formDialogTitle.value = '编辑密钥'
    form.value = { keyName: key.keyName, keyValue: '', enabled: key.enabled }
  } else {
    isEdit.value = false
    editId.value = null
    formDialogTitle.value = '添加密钥'
    form.value = { keyName: '', keyValue: '', enabled: 1 }
  }
  formDialogVisible.value = true
}

function closeForm() {
  formDialogVisible.value = false
}

async function handleSave() {
  if (!form.value.keyName) {
    openDialog({ title: '提示', message: '请输入密钥名称' })
    return
  }
  saving.value = true
  try {
    const payload = { ...form.value }
    if (isEdit.value && !payload.keyValue) {
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
    openDialog({ title: '保存失败', message: e.message })
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
    toast.textContent = '复制成功'
    Object.assign(toast.style, {
      position: 'fixed', bottom: '24px', left: '50%', transform: 'translateX(-50%)',
      background: '#10b981', color: '#fff', padding: '8px 20px', borderRadius: '8px',
      fontSize: '14px', zIndex: '9999', transition: 'opacity .3s'
    })
    document.body.appendChild(toast)
    setTimeout(() => { toast.style.opacity = '0'; setTimeout(() => toast.remove(), 300) }, 1500)
  } catch {
    openDialog({ message: '复制失败，请手动复制' })
  }
}

/**
 * 复制分享链接（新格式：/apikey/{keyValue}）
 */
function shareKey(key: ApiKey) {
  const shareUrl = `${window.location.origin}/apikey/${encodeURIComponent(key.keyValue)}`
  navigator.clipboard.writeText(shareUrl).then(() => {
    showToastMsg('分享链接已复制')
  }).catch(() => {
    openDialog({ message: '分享链接复制失败' })
  })
}

/**
 * 先开启分享，再复制链接
 */
async function enableShare(key: ApiKey) {
  if (!key.id) return
  try {
    const res = await shareApi.toggleShare(key.id, true)
    if (!res.data.success) {
      openDialog({ title: '操作失败', message: res.data.error || '开启分享失败' })
      return
    }
    key.shared = 1
    shareKey(key)
    // 刷新列表更新状态
    loadKeys()
  } catch (e: any) {
    openDialog({ title: '操作失败', message: e.message })
  }
}

/**
 * 确认撤销分享
 */
function confirmRevoke(key: ApiKey) {
  openDialog({
    title: '确认撤销分享',
    message: `确定要撤销密钥「${key.keyName}」的分享吗？撤销后原有的分享链接将立即失效，无法再访问。`,
    type: 'confirm',
    confirmClass: 'btn-warning',
    confirmText: '确认撤销',
    onConfirm: () => revokeShare(key)
  })
}

/**
 * 执行撤销分享
 */
async function revokeShare(key: ApiKey) {
  if (!key.id) return
  try {
    const res = await shareApi.toggleShare(key.id, false)
    if (!res.data.success) {
      openDialog({ title: '操作失败', message: res.data.error || '撤销分享失败' })
      return
    }
    key.shared = 0
    showToastMsg('已撤销分享，链接已失效')
    loadKeys()
  } catch (e: any) {
    openDialog({ title: '操作失败', message: e.message })
  }
}

/**
 * 显示 Toast 消息
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
    title: '确认删除',
    message: `确定要删除密钥「${key.keyName}」吗？此操作不可恢复。`,
    type: 'confirm',
    confirmClass: 'btn-danger',
    onConfirm: () => {
      apikeyApi.delete(key.id!).then(() => loadKeys()).catch(e =>
        openDialog({ title: '删除失败', message: e.message })
      )
    }
  })
}

async function loadKeys() {
  try {
    const res = await apikeyApi.list()
    apiKeys.value = res.data
  } catch (e: any) {
    openDialog({ title: '加载失败', message: e.message })
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

/* 警告按钮（撤销分享） */
:deep(.btn-warning) {
  background: #f59e0b; color: #fff; border-color: #d97706;
}
:deep(.btn-warning:hover) {
  background: #d97706;
}

/* 分享状态徽标 */
.share-badge {
  display: inline-flex; align-items: center; gap: 4px;
  font-size: 11px; padding: 2px 8px; border-radius: 4px;
}
.share-badge.on { background: #d1fae5; color: #065f46; }
.share-badge.off { background: #fef3c7; color: #92400e; }

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
