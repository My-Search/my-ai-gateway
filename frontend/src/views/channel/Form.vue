<template>
  <div class="card">
    <div class="card-header">
      <div class="card-title">{{ isEdit ? '编辑渠道' : '添加渠道' }}</div>
      <router-link to="/admin/channel/list" class="btn btn-secondary"><SvgIcon name="arrow-left" :size="14" /> 返回列表</router-link>
    </div>

    <form @submit.prevent="handleSave" style="max-width:600px;">
      <input type="hidden" v-model="form.id" />

      <div class="form-group">
        <label for="name">渠道名称 *</label>
        <input id="name" v-model="form.name" type="text" class="form-control" placeholder="如：OpenAI、Anthropic、Azure" required />
        <div class="form-hint">自定义渠道名称，用于标识</div>
      </div>

      <div class="form-group">
        <label for="channelType">渠道类型 *</label>
        <select id="channelType" v-model="form.channelType" class="form-control" required>
          <option value="">请选择类型</option>
          <option value="openai">OpenAI 兼容</option>
          <option value="anthropic">Anthropic</option>
        </select>
        <div class="form-hint">选择渠道 API 兼容类型</div>
      </div>

      <div class="form-group">
        <label for="baseUrl">接口地址</label>
        <input id="baseUrl" v-model="form.baseUrl" type="url" class="form-control" placeholder="如：https://api.openai.com/v1" />
        <div class="form-hint">必填路径前缀，OpenAI 兼容格式为 https://xxx.com/v1</div>
      </div>

      <div class="form-group">
        <label>渠道 API Keys</label>
        <div class="form-hint">添加多个 API Key，系统会自动在它们之间进行故障转移</div>
        <div class="api-keys-list">
          <div v-for="(ak, idx) in apiKeys" :key="idx" class="api-key-item">
            <span style="flex:1;font-size:13px;"><strong>{{ ak.keyName }}</strong>: {{ maskKey(ak.apiKey) }}</span>
            <button type="button" class="btn btn-sm btn-danger" @click="removeApiKey(idx)"><SvgIcon name="trash" :size="14" /> 删除</button>
          </div>
          <div v-if="!apiKeys.length" style="color:var(--text-muted);font-size:13px;padding:8px 0;">
            暂无 API Key，点击下方按钮添加
          </div>
        </div>
        <button type="button" class="btn btn-sm btn-primary" style="margin-top:8px;" @click="openApiKeyDialog">
          <SvgIcon name="plus" :size="14" /> 添加 API Key
        </button>
      </div>

      <div class="form-group">
        <label for="enabled">状态</label>
        <select id="enabled" v-model.number="form.enabled" class="form-control">
          <option :value="1">启用</option>
          <option :value="0">禁用</option>
        </select>
      </div>

      <!-- 模型管理 -->
      <div class="form-group">
        <label>渠道模型</label>
        <div class="model-toolbar">
          <button type="button" class="btn btn-success btn-sm" :disabled="fetchLoading" @click="doFetchModels">
            <SvgIcon name="refresh" :size="14" /> {{ fetchLoading ? '获取中...' : '获取模型' }}
          </button>
          <button type="button" class="btn btn-primary btn-sm" @click="showAddModel = true">
            <SvgIcon name="plus" :size="14" /> 手动添加
          </button>
          <div class="toolbar-right">
            <span class="model-stats">共 {{ models.length }} 个</span>
            <button v-if="models.length" type="button" class="btn btn-danger btn-sm" @click="clearAllModels"><SvgIcon name="trash" :size="14" /> 清理全部</button>
          </div>
        </div>
        <div class="model-tags-container">
          <span v-for="(m, idx) in models" :key="idx"
                class="model-tag" :class="{ '_deleted': m._deleted }"
                @click="toggleModel(idx)" :title="m._deleted ? '点击恢复' : '点击移除'">
            {{ m.displayName || m.modelName }}
            <span class="tag-remove" @click.stop="removeModel(idx)">
              <SvgIcon name="x" :size="12" />
            </span>
          </span>
        </div>
        <div class="form-hint">已添加的模型将以标签形式展示，鼠标悬停可移除，保存后生效</div>
      </div>

      <div style="display:flex;gap:8px;margin-top:24px;">
        <button type="submit" class="btn btn-primary" :disabled="saving">
          <SvgIcon name="check" :size="14" /> {{ saving ? '保存中...' : '保存' }}
        </button>
        <router-link to="/admin/channel/list" class="btn btn-secondary"><SvgIcon name="x" :size="14" /> 取消</router-link>
      </div>
    </form>

    <!-- Add Model Dialog -->
    <div v-if="showAddModel" class="modal-overlay" @click.self="showAddModel = false">
      <div class="modal-box" style="width:400px;">
        <h3>手动添加模型</h3>
        <div class="form-group">
          <label>模型名称 *</label>
          <input v-model="newModelName" class="form-control" placeholder="如：gpt-4o-mini"
                 @keydown.enter.prevent="confirmAddModel" />
        </div>
        <div class="form-group">
          <label>显示名称</label>
          <input v-model="newDisplayName" class="form-control" placeholder="不填则默认为模型名称"
                 @keydown.enter.prevent="confirmAddModel" />
        </div>
        <div class="modal-actions">
          <button class="btn btn-secondary" @click="showAddModel = false"><SvgIcon name="x" :size="14" /> 取消</button>
          <button class="btn btn-primary" @click="confirmAddModel"><SvgIcon name="check" :size="14" /> 确认添加</button>
        </div>
      </div>
    </div>
  </div>

  <!-- 通用弹框 -->
  <Dialog
    v-model="dialogVisible"
    :title="dialogTitle"
    :type="dialogType"
    :confirm-class="dialogConfirmClass"
    @confirm="onDialogConfirm"
  >
    {{ dialogMessage }}
  </Dialog>

  <!-- API Key 输入弹框 -->
  <Dialog
    v-model="apiKeyDialogVisible"
    title="添加 API Key"
    type="confirm"
    confirm-text="添加"
    width="480px"
    @confirm="confirmAddApiKey"
    @cancel="closeApiKeyDialog"
  >
    <div class="form-group">
      <label>API Key 名称 *</label>
      <input v-model="newApiKeyName" class="form-control" placeholder="如：主Key、备用Key1" @keydown.enter.prevent="confirmAddApiKey" />
    </div>
    <div class="form-group" style="margin-bottom:0;">
      <label>API Key *</label>
      <input v-model="newApiKeyValue" class="form-control" placeholder="请输入 API Key" @keydown.enter.prevent="confirmAddApiKey" />
    </div>
  </Dialog>
</template>

<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { channelApi, type Channel, type ChannelApiKey, type ChannelModel } from '@/api/channel'
import Dialog from '@/components/common/Dialog.vue'

const route = useRoute()
const router = useRouter()
const isEdit = computed(() => !!route.params.id)

const saving = ref(false)
const fetchLoading = ref(false)
const showAddModel = ref(false)
const newModelName = ref('')
const newDisplayName = ref('')

/* ---------- 通用弹框状态 ---------- */
const dialogVisible = ref(false)
const dialogTitle = ref('提示')
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
  dialogTitle.value = opts.title ?? '提示'
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

/* ---------- API Key 弹框状态 ---------- */
const apiKeyDialogVisible = ref(false)
const newApiKeyName = ref('')
const newApiKeyValue = ref('')

function openApiKeyDialog() {
  newApiKeyName.value = ''
  newApiKeyValue.value = ''
  apiKeyDialogVisible.value = true
}

function closeApiKeyDialog() {
  apiKeyDialogVisible.value = false
}

function confirmAddApiKey() {
  const keyName = newApiKeyName.value.trim()
  const apiKey = newApiKeyValue.value.trim()
  if (!keyName) {
    openDialog({ title: '提示', message: '请输入 API Key 名称' })
    return
  }
  if (!apiKey) {
    openDialog({ title: '提示', message: '请输入 API Key' })
    return
  }
  if (apiKeys.value.some(k => k.keyName === keyName)) {
    openDialog({ title: '提示', message: 'API Key 名称已存在' })
    return
  }
  apiKeys.value.push({
    keyName: keyName,
    apiKey: apiKey,
    enabled: 1,
    sortOrder: apiKeys.value.length
  })
  apiKeyDialogVisible.value = false
}
/* ------------------------------ */

interface ModelItem {
  id?: number
  modelName: string
  displayName?: string
  _deleted?: boolean
}

const form = ref<Partial<Channel>>({
  name: '',
  channelType: '',
  baseUrl: '',
  enabled: 1
})
const apiKeys = ref<ChannelApiKey[]>([])
const models = ref<ModelItem[]>([])

onMounted(async () => {
  if (isEdit.value) {
    const id = Number(route.params.id)
    try {
      const res = await channelApi.get(id)
      const data = res.data
      form.value = { ...data.channel }
      apiKeys.value = (data.apiKeys || []).map(k => ({ ...k }))
      models.value = (data.channelModels || []).map(m => ({
        id: m.id,
        modelName: m.modelName,
        displayName: m.displayName || m.modelName
      }))
    } catch (e: any) {
      openDialog({ title: '加载失败', message: '加载渠道信息失败: ' + e.message })
      router.push('/admin/channel/list')
    }
  }
})

function maskKey(key: string) {
  return key ? '•'.repeat(Math.min(key.length, 20)) : ''
}

function removeApiKey(index: number) {
  openDialog({
    title: '确认删除',
    message: '确认删除该 API Key？',
    type: 'confirm',
    confirmClass: 'btn-danger',
    onConfirm: () => {
      apiKeys.value.splice(index, 1)
    }
  })
}

function toggleModel(index: number) {
  const m = models.value[index]
  if (!m) return
  if (m._deleted) {
    delete m._deleted
  } else if (isEdit.value && m.id) {
    m._deleted = true
  } else {
    models.value.splice(index, 1)
  }
}

function removeModel(index: number) {
  const m = models.value[index]
  if (!m) return
  if (isEdit.value && m.id) {
    m._deleted = true
  } else {
    models.value.splice(index, 1)
  }
}

function confirmAddModel() {
  const name = newModelName.value.trim()
  if (!name) { openDialog({ title: '提示', message: '请输入模型名称' }); return }
  if (models.value.some(m => m.modelName === name)) { openDialog({ title: '提示', message: '模型已存在' }); return }
  models.value.push({
    modelName: name,
    displayName: newDisplayName.value.trim() || name
  })
  newModelName.value = ''
  newDisplayName.value = ''
  showAddModel.value = false
}

async function doFetchModels() {
  if (isEdit.value) {
    // Refresh models from API
    const id = Number(route.params.id)
    fetchLoading.value = true
    try {
      const res = await channelApi.reloadModels(id)
      if (res.data.success) {
        const existing = new Set(models.value.map(m => m.modelName))
        let added = 0
        for (const m of res.data.data || []) {
          if (!existing.has(m.modelName)) {
            models.value.push({ id: m.id, modelName: m.modelName, displayName: m.displayName || m.modelName })
            added++
          }
        }
        openDialog({ message: `新增 ${added} 个模型` })
      } else {
        openDialog({ title: '刷新失败', message: res.data.error || '刷新失败' })
      }
    } catch (e: any) {
      openDialog({ title: '刷新失败', message: e.message })
    } finally {
      fetchLoading.value = false
    }
  } else {
    // Preview fetch
    if (!form.value.channelType) { openDialog({ title: '提示', message: '请先选择渠道类型' }); return }
    if (!apiKeys.value.length) { openDialog({ title: '提示', message: '请先添加至少一个 API Key' }); return }
    fetchLoading.value = true
    try {
      const res = await channelApi.fetchModels(
        form.value.baseUrl || '',
        apiKeys.value[0].apiKey,
        form.value.channelType
      )
      if (res.data.success) {
        const existing = new Set(models.value.map(m => m.modelName))
        let added = 0
        for (const m of res.data.data || []) {
          if (!existing.has(m.modelName)) {
            models.value.push({ modelName: m.modelName, displayName: m.displayName || m.modelName })
            added++
          }
        }
        openDialog({ message: `获取到 ${res.data.count} 个模型，新增 ${added} 个` })
      } else {
        openDialog({ title: '获取失败', message: res.data.error || '获取模型列表失败' })
      }
    } catch (e: any) {
      openDialog({ title: '请求失败', message: e.message })
    } finally {
      fetchLoading.value = false
    }
  }
}

function clearAllModels() {
  if (!models.value.length) return
  openDialog({
    title: '确认清理',
    message: `确认清理全部 ${models.value.length} 个模型？此操作不可恢复`,
    type: 'confirm',
    confirmClass: 'btn-danger',
    onConfirm: () => {
      models.value.forEach(m => { m._deleted = true })
    }
  })
}

async function handleSave() {
  saving.value = true
  try {
    // Filter out deleted models for submission
    const activeModels = models.value.filter(m => !m._deleted)
    const payload: any = {
      ...form.value,
      baseUrl: (form.value.baseUrl || '').trim(),
      manualModels: JSON.stringify(activeModels.map(m => ({
        id: m.id,
        modelName: m.modelName,
        displayName: m.displayName
      }))),
      apiKeysJson: JSON.stringify(apiKeys.value.map(k => ({
        id: k.id,
        keyName: k.keyName,
        apiKey: k.apiKey,
        enabled: k.enabled,
        sortOrder: k.sortOrder
      })))
    }

    if (isEdit.value) {
      const id = Number(route.params.id)
      await channelApi.update(id, payload)
      openDialog({ title: '成功', message: '渠道更新成功', onConfirm: () => router.push('/admin/channel/list') })
    } else {
      await channelApi.create(payload)
      openDialog({ title: '成功', message: '渠道创建成功', onConfirm: () => router.push('/admin/channel/list') })
    }
  } catch (e: any) {
    openDialog({ title: '保存失败', message: e.message })
  } finally {
    saving.value = false
  }
}
</script>

<style scoped>
.api-keys-list {
  display: flex; flex-direction: column; gap: 8px; margin-top: 12px;
}
.api-key-item {
  display: flex; align-items: center; gap: 8px; padding: 8px 12px;
  background: var(--bg-tertiary); border-radius: 6px;
}
.model-toolbar {
  display: flex; align-items: center; gap: 8px; flex-wrap: wrap; margin-bottom: 8px;
}
.toolbar-right { margin-left: auto; display: flex; align-items: center; gap: 8px; }
.model-stats { font-size: 12px; color: var(--text-muted); }
.model-tags-container {
  display: flex; flex-wrap: wrap; gap: 4px; margin-top: 10px; padding: 6px 8px;
  background: var(--bg-secondary); border-radius: 8px; min-height: 28px;
  max-height: 140px; overflow-y: auto;
}
.model-tags-container:empty::after {
  content: '暂无模型，点击下方"获取模型"或"手动添加"添加';
  color: var(--text-muted); font-size: 13px;
}
.model-tag {
  display: inline-flex; align-items: center; gap: 0;
  padding: 2px 8px; background: var(--bg-tertiary);
  border: 1px solid var(--border-color); border-radius: 12px;
  font-size: 11px; color: var(--text-primary); line-height: 1.5; cursor: pointer;
  transition: all 0.15s;
}
.model-tag:hover { background: var(--bg-hover); }
.model-tag._deleted { opacity: 0.5; text-decoration: line-through; background: rgba(248,81,73,0.1); }
.model-tag .tag-remove { display: none; cursor: pointer; opacity: 0.6; margin-left: 4px; }
.model-tag:hover .tag-remove { display: inline-flex; align-items: center; }
.model-tag .tag-remove:hover { opacity: 1; color: var(--accent-red); }

.modal-overlay {
  position: fixed; top: 0; left: 0; right: 0; bottom: 0;
  background: rgba(0,0,0,0.5); z-index: 200;
  display: flex; align-items: center; justify-content: center;
}
.modal-box {
  background: var(--bg-secondary); border: 1px solid var(--border-color);
  border-radius: 12px; padding: 24px;
  box-shadow: 0 8px 32px rgba(0,0,0,0.4);
}
.modal-box h3 { font-size: 16px; font-weight: 600; margin-bottom: 16px; }
.modal-actions { display: flex; gap: 8px; margin-top: 20px; justify-content: flex-end; }
</style>
